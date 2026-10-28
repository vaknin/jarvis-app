//! The call itself: connect, `hello`, stream both ways, and come back with `resume` after a drop.
//!
//! - A Close frame from the server means Jarvis ended the call, unless its code says the server is
//!   going away or restarting (1001, 1012, 1013): that is a drop, and the phone resumes.
//! - Liveness counts only what comes in: the mic's frames go out 50 times a second even into a dead
//!   pipe (the forum tunnel is a local TCP socket, which takes writes long after iroh has gone). With
//!   nothing in for [`Timing::ping_after`] it sends a WebSocket ping, which every server answers.
//! - A line that stalls (a ping unanswered for [`Timing::pong_wait`], or a network change unanswered
//!   for [`Timing::net_check`]) keeps going while a second socket dials with `resume`; the server
//!   hands the call to the newer socket, and the phone switches once `ready` comes, without
//!   flushing what's queued to play. If the old line answers before the second one has sent
//!   `hello`, the second is called off. A socket that breaks (EOF, an error, a write that doesn't
//!   finish within [`Timing::send_timeout`], or nothing in for [`Timing::dead_after`]) is redialled
//!   at once, then with backoff, until [`Timing::resume_give_up`] (the server keeps a call 5 minutes).
//! - Aviv hears about it (Reconnecting, the Dropped earcon, the mic off, the stale reply dropped)
//!   only once the line has been down for [`Timing::grace`]: a quick switch goes unnoticed.

use std::{
    pin::Pin,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use anyhow::{Context, Result, anyhow};
use futures_util::{Sink, SinkExt, Stream, StreamExt, future::BoxFuture};
use tokio::{
    sync::{mpsc, oneshot},
    time::{Instant, sleep_until, timeout, timeout_at},
};

use crate::{
    player::{Earcon, Player},
    proto::{About, Down, Up},
};

/// One WebSocket message, as the call sees it.
#[derive(Debug, Clone, PartialEq)]
pub enum Frame {
    Text(String),
    Binary(Vec<u8>),
    Ping,
    Pong,
    Close,
}

pub type Tx = Pin<Box<dyn Sink<Frame, Error = anyhow::Error> + Send>>;
pub type Rx = Pin<Box<dyn Stream<Item = Result<Frame>> + Send>>;
/// Opens one socket to the server. Called again for every reconnect.
pub type Connector = Arc<dyn Fn() -> BoxFuture<'static, Result<(Tx, Rx)>> + Send + Sync>;

/// The real connector: a WebSocket to `url` (`ws://127.0.0.1:8790/call` through the forum tunnel).
pub fn websocket(url: String) -> Connector {
    use tokio_websockets::{ClientBuilder, Message};
    Arc::new(move || {
        let url = url.clone();
        Box::pin(async move {
            let (ws, _) = ClientBuilder::new().uri(&url).context("bad url")?.connect().await.context("connect")?;
            let (sink, stream) = ws.split();
            let tx = sink.sink_map_err(anyhow::Error::from).with(|f: Frame| async move {
                Ok::<_, anyhow::Error>(match f {
                    Frame::Text(s) => Message::text(s),
                    Frame::Binary(b) => Message::binary(b),
                    Frame::Ping => Message::ping(Vec::<u8>::new()),
                    Frame::Pong => Message::pong(Vec::<u8>::new()),
                    Frame::Close => Message::close(None, ""),
                })
            });
            let rx = stream.map(|m| {
                let m = m?;
                if let Some((code, _)) = m.as_close() {
                    let code = u16::from(code);
                    // The server restarting is a drop: the phone comes back with `resume`.
                    if matches!(code, 1001 | 1012 | 1013) {
                        return Err(anyhow!("server going away ({code})"));
                    }
                }
                Ok(if let Some(s) = m.as_text() {
                    Frame::Text(s.to_owned())
                } else if m.is_binary() {
                    Frame::Binary(m.as_payload().to_vec())
                } else if m.is_ping() {
                    Frame::Ping
                } else if m.is_pong() {
                    Frame::Pong
                } else {
                    Frame::Close
                })
            });
            Ok((Box::pin(tx) as Tx, Box::pin(rx) as Rx))
        })
    })
}

/// Where the call is, for the screen and the system's call state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Status {
    /// Dialling for the first time.
    Connecting,
    Listening,
    Thinking,
    Speaking,
    /// The line dropped; trying to get back into the same call.
    Reconnecting,
    /// Over, with why ("hung up", "Jarvis ended the call", "couldn't reach Jarvis", ...).
    Ended(String),
}

/// What the call tells the app. Called from the runtime's threads, in order.
pub trait Events: Send + Sync + 'static {
    fn status(&self, status: Status);
    fn text(&self, who: &str, text: &str);
    fn error(&self, text: &str);
    /// Jarvis was talked over: the app drops what its speaker still holds.
    fn flush(&self);
    /// Jarvis's reply is paused or not (the pause button follows it).
    fn paused(&self, _on: bool) {}
}

#[derive(Debug, Clone)]
pub struct Timing {
    pub connect_timeout: Duration,
    /// Give up on the very first connect after this.
    pub first_give_up: Duration,
    /// Give up on a dropped call after this (the server keeps it 5 minutes).
    pub resume_give_up: Duration,
    pub backoff_min: Duration,
    pub backoff_max: Duration,
    /// Ping when nothing came in for this long.
    pub ping_after: Duration,
    /// A ping unanswered this long means the line stalled: a second socket dials.
    pub pong_wait: Duration,
    /// Drop the socket when nothing came in for this long.
    pub dead_after: Duration,
    /// After a network change, the line has stalled unless something comes in within this.
    pub net_check: Duration,
    /// Aviv hears of a stall or a drop only once the line has been down this long.
    pub grace: Duration,
    pub send_timeout: Duration,
    /// How long a hangup waits for the server to close.
    pub hangup_wait: Duration,
    pub jitter: Duration,
}

impl Default for Timing {
    fn default() -> Self {
        Timing {
            connect_timeout: Duration::from_secs(15),
            first_give_up: Duration::from_secs(45),
            resume_give_up: Duration::from_secs(285),
            backoff_min: Duration::from_millis(500),
            backoff_max: Duration::from_secs(10),
            ping_after: Duration::from_secs(2),
            pong_wait: Duration::from_secs(3),
            dead_after: Duration::from_secs(15),
            net_check: Duration::from_secs(3),
            grace: Duration::from_millis(2500),
            send_timeout: Duration::from_secs(3),
            hangup_wait: Duration::from_secs(3),
            jitter: Duration::from_millis(120),
        }
    }
}

enum Cmd {
    Mute(bool),
    Pause(bool),
    Hangup,
    NetworkChanged,
}

struct Shared {
    player: Mutex<Player>,
    events: Arc<dyn Events>,
    status: Mutex<Option<Status>>,
    live: AtomicBool,
    muted: AtomicBool,
    /// Pause tapped, and the server hasn't said `paused` or `flush` yet: the audio it sent before
    /// it heard the tap is dropped, so none of it plays after the tap.
    pausing: AtomicBool,
    /// Paused, as last tapped or as the server last said: a resume tells the server again.
    paused: AtomicBool,
}

impl Shared {
    fn set_status(&self, s: Status) {
        let mut cur = self.status.lock().unwrap();
        if cur.as_ref() != Some(&s) {
            *cur = Some(s.clone());
            drop(cur);
            self.events.status(s);
        }
    }

    /// The call is over for Aviv: the mic stops, the ended earcon plays, the app hears `Ended`.
    /// Only the first call counts (a hangup ends it before the server has closed).
    fn end(&self, why: String) {
        if matches!(*self.status.lock().unwrap(), Some(Status::Ended(_))) {
            return;
        }
        self.live.store(false, Ordering::SeqCst);
        {
            let mut p = self.player.lock().unwrap();
            p.earcon(Earcon::Ended);
            p.end();
        }
        self.set_status(Status::Ended(why));
    }

    fn earcon(&self, e: Earcon) {
        self.player.lock().unwrap().earcon(e);
    }
}

/// A call in progress. Dropping it hangs up.
pub struct Call {
    shared: Arc<Shared>,
    cmd: mpsc::UnboundedSender<Cmd>,
    mic: mpsc::Sender<Vec<u8>>,
}

impl Call {
    /// Starts dialling at once. Must be called inside a tokio runtime.
    pub fn start(connector: Connector, events: Arc<dyn Events>, timing: Timing) -> Call {
        Self::start_about(connector, events, timing, None)
    }

    /// [`Call::start`], for a call about one thread: the first hello says which, so Jarvis opens with it.
    pub fn start_about(connector: Connector, events: Arc<dyn Events>, timing: Timing, about: Option<About>) -> Call {
        let shared = Arc::new(Shared {
            player: Mutex::new(Player::new(timing.jitter)),
            events,
            status: Mutex::new(None),
            live: AtomicBool::new(false),
            muted: AtomicBool::new(false),
            pausing: AtomicBool::new(false),
            paused: AtomicBool::new(false),
        });
        let (cmd, cmd_rx) = mpsc::unbounded_channel();
        // 10 frames (200 ms): a send that lags more than that drops the newest speech, not the call.
        let (mic, mic_rx) = mpsc::channel(10);
        tokio::spawn(run(connector, shared.clone(), cmd_rx, mic_rx, timing, about));
        Call { shared, cmd, mic }
    }

    /// One mic frame (PCM16 LE mono 16 kHz, 20 ms). Never blocks: dropped while muted, while the
    /// line is down, or if the socket is behind.
    pub fn send_audio(&self, pcm: Vec<u8>) {
        if self.shared.live.load(Ordering::SeqCst) && !self.shared.muted.load(Ordering::SeqCst) {
            let _ = self.mic.try_send(pcm);
        }
    }

    /// The next `samples` samples for the speaker; empty once the call has ended and the last
    /// earcon has played.
    pub fn pull_audio(&self, samples: usize) -> Vec<u8> {
        self.shared.player.lock().unwrap().pull(samples, Instant::now())
    }

    pub fn set_muted(&self, on: bool) {
        if self.shared.muted.swap(on, Ordering::SeqCst) != on {
            let _ = self.cmd.send(Cmd::Mute(on));
        }
    }

    /// Aviv tapped pause or play. Pause silences Jarvis here at once (the server pauses his reply,
    /// keeping it, and says `paused`); play asks the server to go on from the sentence he paused in.
    pub fn set_paused(&self, on: bool) {
        self.shared.pausing.store(on, Ordering::SeqCst);
        self.shared.paused.store(on, Ordering::SeqCst);
        if on {
            self.shared.player.lock().unwrap().flush();
            self.shared.events.flush();
        }
        let _ = self.cmd.send(Cmd::Pause(on));
    }

    pub fn muted(&self) -> bool {
        self.shared.muted.load(Ordering::SeqCst)
    }

    pub fn hangup(&self) {
        let _ = self.cmd.send(Cmd::Hangup);
    }

    /// The phone moved to another network: check the line now instead of waiting for it to go quiet.
    pub fn network_changed(&self) {
        let _ = self.cmd.send(Cmd::NetworkChanged);
    }

    pub fn status(&self) -> Option<Status> {
        self.shared.status.lock().unwrap().clone()
    }

    /// Milliseconds of Jarvis's voice waiting to play.
    pub fn buffered_ms(&self) -> u64 {
        self.shared.player.lock().unwrap().buffered_ms()
    }
}

impl Drop for Call {
    fn drop(&mut self) {
        let _ = self.cmd.send(Cmd::Hangup);
    }
}

/// The socket the call is on, and what came in on it lately.
struct Line {
    tx: Tx,
    rx: Rx,
    last_in: Instant,
    pinged: bool,
    /// A network change asked for an answer by then.
    check: Option<Instant>,
}

impl Line {
    fn new(tx: Tx, rx: Rx) -> Line {
        Line { tx, rx, last_in: Instant::now(), pinged: false, check: None }
    }

    fn stalled(&self, t: &Timing) -> bool {
        (self.pinged && self.last_in.elapsed() >= t.ping_after + t.pong_wait) || self.check.is_some_and(|c| Instant::now() >= c)
    }
}

enum Dialed {
    Ready { tx: Tx, rx: Rx, call: String },
    /// The server closed: the call is over.
    Ended(String),
    Failed(String),
}

/// A socket on its way up: connect, `hello`, `ready`.
struct Dial {
    fut: BoxFuture<'static, Dialed>,
    /// `hello` went out: the server hands the call to this socket, so it can't be called off.
    hello: Arc<AtomicBool>,
    /// Hang up: sends `hangup` if the socket is up, and ends the dial.
    stop: Option<oneshot::Sender<()>>,
}

fn dial(connector: &Connector, sh: Arc<Shared>, t: &Timing, resume: Option<String>, about: Option<About>) -> Dial {
    let (hello, (stop, mut stopped)) = (Arc::new(AtomicBool::new(false)), oneshot::channel::<()>());
    let (connect, said, t) = (connector(), hello.clone(), t.clone());
    let fut = Box::pin(async move {
        let up = tokio::select! {
            r = timeout(t.connect_timeout, connect) => r,
            _ = &mut stopped => return Dialed::Failed("hung up".into()),
        };
        let (mut tx, mut rx) = match up {
            Err(_) => return Dialed::Failed("timed out".into()),
            Ok(Err(e)) => return Dialed::Failed(format!("{e:#}")),
            Ok(Ok(s)) => s,
        };
        match timeout(t.send_timeout, tx.send(Frame::Text(Up::Hello { version: 1, resume, about }.json()))).await {
            Ok(Ok(())) => said.store(true, Ordering::SeqCst),
            Ok(Err(e)) => return Dialed::Failed(format!("send: {e:#}")),
            Err(_) => return Dialed::Failed("send timed out".into()),
        }
        let deadline = Instant::now() + t.connect_timeout;
        loop {
            let f = tokio::select! {
                f = timeout_at(deadline, rx.next()) => f,
                _ = &mut stopped => {
                    let _ = timeout(t.send_timeout, tx.send(Frame::Text(Up::Hangup.json()))).await;
                    return Dialed::Failed("hung up".into());
                }
            };
            match f {
                Err(_) => return Dialed::Failed("no ready".into()),
                Ok(Some(Ok(Frame::Text(s)))) => match Down::parse(&s) {
                    Some(Down::Ready { call }) => return Dialed::Ready { tx, rx, call },
                    Some(Down::Error { text }) => sh.events.error(&text),
                    _ => {}
                },
                Ok(Some(Ok(Frame::Close))) => return Dialed::Ended("Jarvis ended the call".into()),
                Ok(Some(Ok(_))) => {}
                Ok(Some(Err(e))) => return Dialed::Failed(format!("{e:#}")),
                Ok(None) => return Dialed::Failed("closed before ready".into()),
            }
        }
    });
    Dial { fut, hello, stop: Some(stop) }
}

/// Everything the call loop keeps between events.
struct Run {
    connector: Connector,
    sh: Arc<Shared>,
    mic: mpsc::Receiver<Vec<u8>>,
    t: Timing,
    id: Option<String>,
    /// What the call is about, said in each hello until the server has the call.
    about: Option<About>,
    line: Option<Line>,
    next: Option<Dial>,
    /// Since when the line has been stalled or down (or the call started); `None` while it's fine.
    down: Option<Instant>,
    /// Aviv was told (Connecting or Reconnecting shows, the mic is off).
    shown: bool,
    /// Failed dials since the line was last up.
    tries: u32,
    retry_at: Option<Instant>,
    /// What the server last said it's doing, to show again if a stalled line comes back.
    state: Status,
}

async fn run(
    connector: Connector,
    sh: Arc<Shared>,
    mut cmd: mpsc::UnboundedReceiver<Cmd>,
    mic: mpsc::Receiver<Vec<u8>>,
    t: Timing,
    about: Option<About>,
) {
    sh.set_status(Status::Connecting);
    let next = Some(dial(&connector, sh.clone(), &t, None, about.clone()));
    let mut r = Run {
        connector,
        sh: sh.clone(),
        mic,
        t,
        id: None,
        about,
        line: None,
        next,
        down: Some(Instant::now()),
        shown: true,
        tries: 0,
        retry_at: None,
        state: Status::Listening,
    };
    let mut tick = tokio::time::interval(Duration::from_millis(100));
    let why = loop {
        let ended = tokio::select! {
            Some(pcm) = r.mic.recv(), if r.line.is_some() => {
                if !r.sh.muted.load(Ordering::SeqCst) {
                    r.send(Frame::Binary(pcm)).await;
                }
                None
            }
            c = cmd.recv() => match c {
                Some(Cmd::Mute(on)) => {
                    // With no line, the next `ready` sends it.
                    r.send(Frame::Text(Up::Mute { on }.json())).await;
                    None
                }
                Some(Cmd::Pause(on)) => {
                    r.send(Frame::Text(Up::Pause { on }.json())).await;
                    None
                }
                Some(Cmd::NetworkChanged) => {
                    r.network_changed().await;
                    None
                }
                Some(Cmd::Hangup) | None => {
                    r.hangup().await;
                    Some("hung up".to_string())
                }
            },
            f = frame(&mut r.line) => r.frame(f),
            d = dialed(&mut r.next) => r.dialed(d).await,
            _ = at(r.retry_at) => {
                r.retry_at = None;
                r.redial();
                None
            }
            _ = tick.tick() => r.tick().await,
        };
        if let Some(why) = ended {
            break why;
        }
    };
    sh.end(why);
}

async fn frame(line: &mut Option<Line>) -> Option<Result<Frame>> {
    match line {
        Some(l) => l.rx.next().await,
        None => std::future::pending().await,
    }
}

async fn dialed(next: &mut Option<Dial>) -> Dialed {
    match next {
        Some(d) => (&mut d.fut).await,
        None => std::future::pending().await,
    }
}

async fn at(when: Option<Instant>) {
    match when {
        Some(t) => sleep_until(t).await,
        None => std::future::pending().await,
    }
}

impl Run {
    /// Sends on the line, if there is one; a send that fails or hangs breaks it.
    async fn send(&mut self, f: Frame) {
        let Some(l) = self.line.as_mut() else { return };
        let why = match timeout(self.t.send_timeout, l.tx.send(f)).await {
            Ok(Ok(())) => return,
            Ok(Err(e)) => format!("send: {e:#}"),
            Err(_) => "send timed out".into(),
        };
        self.broke(&why);
    }

    /// The line broke: redial at once (unless a dial is already on its way).
    fn broke(&mut self, why: &str) {
        tracing::info!("voice: line down: {why}");
        self.line = None;
        self.down.get_or_insert_with(Instant::now);
        if self.next.is_none() {
            self.redial();
        }
    }

    fn redial(&mut self) {
        self.retry_at = None;
        // Until the server has the call, a redial is still its first hello, and says what it's about.
        let about = if self.id.is_none() { self.about.clone() } else { None };
        self.next = Some(dial(&self.connector, self.sh.clone(), &self.t, self.id.clone(), about));
    }

    async fn network_changed(&mut self) {
        // A dial that hasn't said hello yet was on the old network: start it over on the new one.
        if self.next.as_ref().is_some_and(|d| !d.hello.load(Ordering::SeqCst)) || self.retry_at.is_some() {
            self.tries = 0;
            self.redial();
        }
        if let Some(l) = self.line.as_mut() {
            l.check = Some(Instant::now() + self.t.net_check);
            self.send(Frame::Ping).await;
        }
    }

    async fn hangup(&mut self) {
        if let Some(stop) = self.next.as_mut().and_then(|d| d.stop.take()) {
            let _ = stop.send(());
            let fut = self.next.take().map(|d| d.fut);
            let _ = timeout(self.t.send_timeout, async { if let Some(f) = fut { f.await; } }).await;
        }
        if let Some(mut l) = self.line.take() {
            // Even on a line that just broke: a hangup ends the call, it never reconnects.
            // The server ends a call it doesn't hear back from after 5 minutes anyway.
            let _ = timeout(self.t.send_timeout, l.tx.send(Frame::Text(Up::Hangup.json()))).await;
            // Over for Aviv now; the socket stays a moment, since the server checks for a
            // conclusion worth keeping, then closes.
            self.sh.end("hung up".into());
            let _ = timeout(self.t.hangup_wait, async {
                while let Some(Ok(f)) = l.rx.next().await {
                    if f == Frame::Close {
                        break;
                    }
                }
            })
            .await;
        }
    }

    /// Something came in on the line. Returns why the call ended, if it did.
    fn frame(&mut self, f: Option<Result<Frame>>) -> Option<String> {
        let sh = self.sh.clone();
        let l = self.line.as_mut()?;
        l.last_in = Instant::now();
        l.pinged = false;
        l.check = None;
        let now = l.last_in;
        match f {
            Some(Ok(Frame::Close)) => return Some("Jarvis ended the call".into()),
            Some(Err(e)) => {
                self.broke(&format!("{e:#}"));
                return None;
            }
            None => {
                self.broke("closed");
                return None;
            }
            Some(Ok(_)) if self.down.is_some() => self.back(),
            Some(Ok(_)) => {}
        }
        match f {
            Some(Ok(Frame::Binary(_))) if sh.pausing.load(Ordering::SeqCst) => {}
            Some(Ok(Frame::Binary(b))) => sh.player.lock().unwrap().push(&b, now),
            Some(Ok(Frame::Text(s))) => match Down::parse(&s) {
                Some(Down::State { state }) => {
                    let s = match state.as_str() {
                        "listening" => Status::Listening,
                        "thinking" => Status::Thinking,
                        "speaking" => Status::Speaking,
                        _ => return None,
                    };
                    self.state = s.clone();
                    // No sound for a turn: it should feel like talking to a person (Aviv, 2026-10-28).
                    sh.set_status(s);
                }
                Some(Down::Flush) => {
                    sh.pausing.store(false, Ordering::SeqCst);
                    sh.player.lock().unwrap().flush();
                    sh.events.flush();
                }
                Some(Down::Text { who, text }) => sh.events.text(&who, &text),
                Some(Down::Paused { on }) => {
                    sh.pausing.store(false, Ordering::SeqCst);
                    sh.paused.store(on, Ordering::SeqCst);
                    sh.events.paused(on);
                }
                Some(Down::Error { text }) => sh.events.error(&text),
                Some(Down::Ready { .. }) | Some(Down::Unknown) | None => {}
            },
            _ => {}
        }
        None
    }

    /// A stalled line answered after all: call off a dial that hasn't said hello yet.
    fn back(&mut self) {
        if self.next.as_ref().is_some_and(|d| d.hello.load(Ordering::SeqCst)) {
            // The server is taking the call to the new socket: wait for its `ready`.
            return;
        }
        self.next = None;
        self.retry_at = None;
        self.tries = 0;
        self.down = None;
        if self.shown {
            self.shown = false;
            self.sh.live.store(true, Ordering::SeqCst);
            self.sh.set_status(self.state.clone());
        }
    }

    async fn dialed(&mut self, d: Dialed) -> Option<String> {
        self.next = None;
        let (tx, rx, call) = match d {
            Dialed::Ready { tx, rx, call } => (tx, rx, call),
            Dialed::Ended(why) => return Some(why),
            Dialed::Failed(why) => {
                tracing::info!("voice: dial failed: {why}");
                if self.down.is_some() {
                    let wait = (self.t.backoff_min * 2u32.saturating_pow(self.tries.min(16))).min(self.t.backoff_max);
                    self.tries += 1;
                    self.retry_at = Some(Instant::now() + wait);
                }
                return None;
            }
        };
        let resumed = self.id.as_ref() == Some(&call);
        if self.id.as_ref().is_some_and(|old| *old != call) {
            // The server no longer had the old call: this is a new one, without what was said before.
            self.sh.events.error("new call: Jarvis lost the old one");
        }
        self.id = Some(call);
        // The old socket (if any) goes here: the server has handed the call to this one.
        self.line = Some(Line::new(tx, rx));
        self.down = None;
        self.tries = 0;
        self.retry_at = None;
        // A new call starts unmuted; a resumed one may have been muted or unmuted while the line was down.
        let muted = self.sh.muted.load(Ordering::SeqCst);
        if muted || resumed {
            self.send(Frame::Text(Up::Mute { on: muted }.json())).await;
        }
        // Paused when the line dropped (or tapped while it was down): the server hears it again, so
        // the button and Jarvis agree whatever the server kept.
        if resumed && self.sh.paused.load(Ordering::SeqCst) {
            self.send(Frame::Text(Up::Pause { on: true }.json())).await;
        }
        if self.shown {
            self.shown = false;
            self.sh.earcon(Earcon::Connected);
            while self.mic.try_recv().is_ok() {}
            self.state = Status::Listening;
            self.sh.set_status(Status::Listening);
            self.sh.live.store(true, Ordering::SeqCst);
        }
        None
    }

    async fn tick(&mut self) -> Option<String> {
        if let Some(l) = self.line.as_mut() {
            let quiet = l.last_in.elapsed();
            if quiet >= self.t.dead_after {
                self.broke(&format!("nothing for {}s", quiet.as_secs()));
            } else if l.stalled(&self.t) {
                if self.down.is_none() {
                    tracing::info!("voice: line stalled after {} ms quiet: dialling a second socket", quiet.as_millis());
                    self.down = Some(Instant::now());
                    if self.next.is_none() && self.retry_at.is_none() {
                        self.redial();
                    }
                }
            } else if quiet >= self.t.ping_after && !l.pinged {
                l.pinged = true;
                self.send(Frame::Ping).await;
            }
        }
        let down = self.down?;
        if !self.shown && down.elapsed() >= self.t.grace {
            self.shown = true;
            self.sh.live.store(false, Ordering::SeqCst);
            // What was queued of Jarvis's reply is stale now; the server goes on after the resume.
            self.sh.player.lock().unwrap().flush();
            self.sh.events.flush();
            self.sh.earcon(Earcon::Dropped);
            self.sh.set_status(Status::Reconnecting);
        }
        let first = self.id.is_none();
        if down.elapsed() >= if first { self.t.first_give_up } else { self.t.resume_give_up } {
            return Some(if first { "couldn't reach Jarvis".into() } else { "lost the line".into() });
        }
        None
    }
}
