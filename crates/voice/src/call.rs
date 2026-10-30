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
//!   at once, then with backoff, for as long as it takes: a call the server has is never given up
//!   (thread #132: the server keeps it until Aviv hangs up). While the phone has no network
//!   ([`Call::set_online`]) it waits instead, and dials the moment one is back.
//! - A call can start as a rejoin ([`Call::rejoin`]): back to a dropped call the app had left (it
//!   was killed, or Aviv answered Jarvis calling back). When Aviv had left it, its first hello says
//!   `rejoin`, so Jarvis opens with where they were cut off; a restart he never noticed (an update
//!   in a pause) resumes it like any dropped line.
//! - A hangup the server didn't confirm (no line, or no Close within [`Timing::hangup_wait`]) is
//!   owed ([`Events::hangup_owed`]): the app delivers it later, or the dropped call stays open.
//! - When the line stalls or breaks, the transport is kicked at once ([`Events::kick`]: the ffi has
//!   iroh look for its paths again, as Android's network callback would, which can come half a
//!   minute later when Wi-Fi fades), and again after [`Timing::rekick`] if the dial still hasn't
//!   said `hello`, which then starts over.
//! - Aviv hears about it (Reconnecting, the Dropped earcon, the mic off) only once the line has
//!   been down for [`Timing::grace`]: a quick switch goes unnoticed.
//! - A drop never throws away Jarvis's reply: what the phone holds plays on, and the silence after
//!   it is the pause. The resume's hello says the last sentence held whole (`have`) and what's still
//!   queued (`queued_ms`); the server sends the rest again, each sentence from its start, and the
//!   player skips what it still has or restarts the sentence it ran dry in (`player.rs`). Only a
//!   resume that became a new call drops the old reply.

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
    boost::MicBoost,
    player::{Earcon, Player},
    proto::{About, Down, Up},
    stt::{Do, Stt, Word},
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
    /// Aviv's mic is muted by voice or not any more (the mic keeps streaming; the screen shows it).
    fn voice_muted(&self, _on: bool) {}
    /// (A call started with phone speech to text) the app's recognizer opens session `id`: the
    /// call's mic audio follows, as the server got it, then [`Events::stt_close`]. The app answers
    /// [`Call::stt_done`] with its words once the session is over, however it ended.
    fn stt_open(&self, _id: u64) {}
    fn stt_audio(&self, _id: u64, _pcm: &[u8]) {}
    /// No more audio for session `id`: the recognizer finishes it.
    fn stt_close(&self, _id: u64) {}
    /// The line stalled or broke, or the dial after it is slow: the transport should look for its
    /// paths again now (the ffi: iroh's `network_change`) instead of waiting for the system to say
    /// the network changed.
    fn kick(&self) {}
    /// The call is back on a new socket after the line went down (the server may have lost it).
    fn resumed(&self) {}
    /// The server has this call as `id` (on the first `ready`, and if a resume became a new call):
    /// the app keeps it, so a call its process dies in can be rejoined.
    fn joined(&self, _id: &str) {}
    /// Aviv hung up and the server may not have heard it (no line, or no Close after it): the app
    /// owes it the hangup of call `id` (an `"end":true` hello), or Jarvis keeps the call open.
    fn hangup_owed(&self, _id: &str) {}
}

#[derive(Debug, Clone)]
pub struct Timing {
    pub connect_timeout: Duration,
    /// Give up on the very first connect after this.
    pub first_give_up: Duration,
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
    /// The line went down and the dial after it hasn't said `hello` this long after the kick: kick
    /// again, and start that dial over.
    pub rekick: Duration,
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
            backoff_min: Duration::from_millis(500),
            backoff_max: Duration::from_secs(10),
            ping_after: Duration::from_secs(2),
            pong_wait: Duration::from_secs(3),
            dead_after: Duration::from_secs(15),
            net_check: Duration::from_secs(3),
            rekick: Duration::from_secs(3),
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
    Online(bool),
    SttDone(u64, Option<Vec<Word>>),
}

struct Shared {
    player: Mutex<Player>,
    events: Arc<dyn Events>,
    status: Mutex<Option<Status>>,
    live: AtomicBool,
    muted: AtomicBool,
    /// Muted by voice, as the server last said: the mic still streams. A tap either way ends it.
    voice_muted: AtomicBool,
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
    boost: Mutex<MicBoost>,
}

impl Call {
    /// Starts dialling at once. Must be called inside a tokio runtime.
    pub fn start(connector: Connector, events: Arc<dyn Events>, timing: Timing) -> Call {
        Self::start_about(connector, events, timing, None)
    }

    /// [`Call::start`], for a call about one thread: the first hello says which, so Jarvis opens with it.
    pub fn start_about(connector: Connector, events: Arc<dyn Events>, timing: Timing, about: Option<About>) -> Call {
        Self::start_full(connector, events, timing, about, false)
    }

    /// [`Call::start_about`]; `phone_stt`: the app's own recognizer transcribes Aviv's turns (the
    /// hello says `"stt":"phone"`, and [`Events::stt_open`] and its kin feed it; see `stt.rs`).
    pub fn start_full(connector: Connector, events: Arc<dyn Events>, timing: Timing, about: Option<About>, phone_stt: bool) -> Call {
        Self::begin(connector, events, timing, about, phone_stt, None)
    }

    /// Back to the dropped call `id` the app had left (its process died mid-call, or Aviv answered
    /// Jarvis calling back): its first hello resumes `id`, with `rejoin` when Aviv had left it
    /// (`aviv_left`), and from there it's a call like any other.
    pub fn rejoin(connector: Connector, events: Arc<dyn Events>, timing: Timing, id: String, phone_stt: bool, aviv_left: bool) -> Call {
        Self::begin(connector, events, timing, None, phone_stt, Some((id, aviv_left)))
    }

    fn begin(connector: Connector, events: Arc<dyn Events>, timing: Timing, about: Option<About>, phone_stt: bool, rejoin: Option<(String, bool)>) -> Call {
        let shared = Arc::new(Shared {
            player: Mutex::new(Player::new(timing.jitter)),
            events,
            status: Mutex::new(None),
            live: AtomicBool::new(false),
            muted: AtomicBool::new(false),
            voice_muted: AtomicBool::new(false),
            pausing: AtomicBool::new(false),
            paused: AtomicBool::new(false),
        });
        let (cmd, cmd_rx) = mpsc::unbounded_channel();
        // 10 frames (200 ms): a send that lags more than that drops the newest speech, not the call.
        let (mic, mic_rx) = mpsc::channel(10);
        tokio::spawn(run(connector, shared.clone(), cmd_rx, mic_rx, timing, about, phone_stt, rejoin));
        Call { shared, cmd, mic, boost: Mutex::default() }
    }

    /// One mic frame (PCM16 LE mono 16 kHz, 20 ms). Never blocks: dropped while muted, while the
    /// line is down, or if the socket is behind.
    pub fn send_audio(&self, mut pcm: Vec<u8>) {
        if self.shared.live.load(Ordering::SeqCst) && !self.shared.muted.load(Ordering::SeqCst) {
            self.boost.lock().unwrap().apply(&mut pcm);
            let _ = self.mic.try_send(pcm);
        }
    }

    /// Whether the mic is a headset's, whose level [`MicBoost`] looks after.
    pub fn set_mic_boost(&self, on: bool) {
        self.boost.lock().unwrap().set(on);
    }

    /// The next `samples` samples for the speaker; empty once the call has ended and the last
    /// earcon has played.
    pub fn pull_audio(&self, samples: usize) -> Vec<u8> {
        self.shared.player.lock().unwrap().pull(samples, Instant::now())
    }

    /// A tap of mute or unmute. While muted by voice it's sent even if the tap state is the same:
    /// the server lifts its voice mute on any tap.
    pub fn set_muted(&self, on: bool) {
        let voice = self.shared.voice_muted.swap(false, Ordering::SeqCst);
        if self.shared.muted.swap(on, Ordering::SeqCst) != on || voice {
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

    pub fn voice_muted(&self) -> bool {
        self.shared.voice_muted.load(Ordering::SeqCst)
    }

    pub fn hangup(&self) {
        let _ = self.cmd.send(Cmd::Hangup);
    }

    /// The app's recognizer finished session `id`: its words (start in ms from the session's first
    /// sample), or `None` if it failed.
    pub fn stt_done(&self, id: u64, words: Option<Vec<Word>>) {
        let _ = self.cmd.send(Cmd::SttDone(id, words));
    }

    /// The phone moved to another network: check the line now instead of waiting for it to go quiet.
    pub fn network_changed(&self) {
        let _ = self.cmd.send(Cmd::NetworkChanged);
    }

    /// Whether the phone has a network at all. Offline, a dropped call waits rather than dialling
    /// into nothing; back online, it dials at once.
    pub fn set_online(&self, on: bool) {
        let _ = self.cmd.send(Cmd::Online(on));
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

/// Hangs up the dropped call `id` without joining it (Aviv declined Jarvis calling back, or the
/// line didn't carry his hangup): an `"end":true` hello, and the server closes once it has it.
pub async fn end(connector: Connector, id: String, t: Timing) -> Result<()> {
    let (mut tx, mut rx) = timeout(t.connect_timeout, connector()).await.context("connect timed out")??;
    let hello = Up::Hello { version: 1, resume: Some(id), about: None, stt: None, rejoin: false, end: true, have: None, queued_ms: None };
    timeout(t.send_timeout, tx.send(Frame::Text(hello.json()))).await.context("send timed out")??;
    timeout(t.connect_timeout, async {
        loop {
            match rx.next().await {
                Some(Ok(Frame::Close)) => return Ok(()),
                Some(Ok(_)) => {}
                Some(Err(e)) => return Err(e),
                None => return Err(anyhow!("closed without a close frame")),
            }
        }
    })
    .await
    .context("no close")?
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

/// `holds`: the player holds this call's audio (it had a `ready`), so a resume says what it has.
#[allow(clippy::too_many_arguments)]
fn dial(connector: &Connector, sh: Arc<Shared>, t: &Timing, resume: Option<String>, about: Option<About>, stt: bool, rejoin: bool, holds: bool) -> Dial {
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
        // Read as the hello goes, not at dial: the old line may still be playing and bringing more.
        let (have, queued_ms) = if resume.is_some() && holds {
            let p = sh.player.lock().unwrap();
            (Some(p.have().unwrap_or(0)), Some(p.buffered_ms()))
        } else {
            (None, None)
        };
        let hello = Up::Hello { version: 1, resume, about, stt: stt.then_some("phone"), rejoin, end: false, have, queued_ms };
        match timeout(t.send_timeout, tx.send(Frame::Text(hello.json()))).await {
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
    /// The line went down and the transport was kicked: kick again then, unless a dial got through.
    rekick_at: Option<Instant>,
    /// What the server last said it's doing, to show again if a stalled line comes back.
    state: Status,
    /// The phone's own speech to text, if this call has it.
    stt: Option<Stt>,
    /// Text frames to send once the frame that asked for them is handled.
    out: Vec<Up>,
    /// The phone has a network (as the app last said).
    online: bool,
    /// Back to a call the app had left: hellos say `rejoin` until the server has it.
    rejoin: bool,
    /// A `ready` came: the player holds this call's audio, and a resume says what of it.
    holds: bool,
}

async fn run(
    connector: Connector,
    sh: Arc<Shared>,
    mut cmd: mpsc::UnboundedReceiver<Cmd>,
    mic: mpsc::Receiver<Vec<u8>>,
    t: Timing,
    about: Option<About>,
    phone_stt: bool,
    rejoin: Option<(String, bool)>,
) {
    sh.set_status(Status::Connecting);
    let (rejoin, aviv_left) = rejoin.map_or((None, false), |(id, left)| (Some(id), left));
    let next = Some(dial(&connector, sh.clone(), &t, rejoin.clone(), about.clone(), phone_stt, aviv_left, false));
    let mut r = Run {
        connector,
        sh: sh.clone(),
        mic,
        t,
        rejoin: aviv_left,
        holds: false,
        id: rejoin,
        online: true,
        about,
        line: None,
        next,
        down: Some(Instant::now()),
        shown: true,
        tries: 0,
        retry_at: None,
        rekick_at: None,
        state: Status::Listening,
        stt: phone_stt.then(Stt::default),
        out: Vec::new(),
    };
    let mut tick = tokio::time::interval(Duration::from_millis(100));
    let why = loop {
        let ended = tokio::select! {
            Some(pcm) = r.mic.recv(), if r.line.is_some() => {
                if !r.sh.muted.load(Ordering::SeqCst) {
                    let copy = r.stt.is_some().then(|| pcm.clone());
                    r.send(Frame::Binary(pcm)).await;
                    // Counted only once it's on the line: the server counts what it got.
                    if let Some(pcm) = copy.filter(|_| r.line.is_some()) {
                        let d = r.stt.as_mut().map(|s| s.sent(&pcm)).unwrap_or_default();
                        r.stt_do(d);
                    }
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
                Some(Cmd::Online(on)) => {
                    r.set_online(on).await;
                    None
                }
                Some(Cmd::SttDone(id, words)) => {
                    let d = r.stt.as_mut().map(|s| s.done(id, words, Instant::now())).unwrap_or_default();
                    r.stt_do(d);
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
        for up in std::mem::take(&mut r.out) {
            r.send(Frame::Text(up.json())).await;
        }
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
    /// Does what the phone's speech-to-text books ask: the app's recognizer hears of it at once,
    /// `heard` goes out after the frame being handled.
    fn stt_do(&mut self, d: Vec<Do>) {
        for d in d {
            match d {
                Do::Open(id) => self.sh.events.stt_open(id),
                Do::Audio(id, pcm) => self.sh.events.stt_audio(id, &pcm),
                Do::Close(id) => self.sh.events.stt_close(id),
                Do::Heard { id, text, ms } => {
                    tracing::info!("voice stt: cut {id} heard {text:?} after {ms} ms");
                    self.out.push(Up::Heard { id, text, ms });
                }
            }
        }
    }

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
        if self.down.is_none() {
            self.down = Some(Instant::now());
            self.kick();
        }
        if self.next.is_none() {
            self.redial();
        }
    }

    /// The line just went down: the transport looks for its paths now, and again in a moment if the
    /// dial hasn't got through by then.
    fn kick(&mut self) {
        self.sh.events.kick();
        self.rekick_at = Some(Instant::now() + self.t.rekick);
    }

    /// [`Timing::rekick`] after the line went down: a dial that still hasn't said hello rode the old
    /// path, so kick the transport again and start the dial over (a backoff wait ends now too).
    fn rekick(&mut self) {
        self.rekick_at = None;
        if self.down.is_none() || self.next.as_ref().is_some_and(|d| d.hello.load(Ordering::SeqCst)) {
            return;
        }
        tracing::info!("voice: no second socket {} ms after the kick: kicking again and redialling", self.t.rekick.as_millis());
        self.sh.events.kick();
        self.tries = 0;
        self.redial();
    }

    fn redial(&mut self) {
        self.retry_at = None;
        if !self.online {
            // Nothing to dial over: `set_online` dials the moment there is.
            self.next = None;
            return;
        }
        // Until the server has the call, a redial is still its first hello, and says what it's about.
        let about = if self.id.is_none() { self.about.clone() } else { None };
        self.next = Some(dial(&self.connector, self.sh.clone(), &self.t, self.id.clone(), about, self.stt.is_some(), self.rejoin, self.holds));
    }

    async fn set_online(&mut self, on: bool) {
        if on == self.online {
            return;
        }
        self.online = on;
        tracing::info!("voice: the phone is {}", if on { "online again" } else { "offline" });
        if on {
            if self.line.is_none() && self.next.is_none() {
                // Down and waiting for a network: dial now, from the shortest backoff.
                self.tries = 0;
                self.redial();
            } else {
                self.network_changed().await;
            }
        }
    }

    async fn network_changed(&mut self) {
        tracing::info!("voice: the phone's network changed");
        // The dial starts over now: give it the whole wait before a rekick starts it over again.
        if self.rekick_at.is_some() {
            self.rekick_at = Some(Instant::now() + self.t.rekick);
        }
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
        let mut heard = false;
        if let Some(stop) = self.next.as_mut().and_then(|d| d.stop.take()) {
            let _ = stop.send(());
            let fut = self.next.take().map(|d| d.fut);
            let _ = timeout(self.t.send_timeout, async { if let Some(f) = fut { f.await; } }).await;
        }
        if let Some(mut l) = self.line.take() {
            // Even on a line that just broke: a hangup ends the call, it never reconnects. If the
            // server never hears it, the app owes it (below).
            let _ = timeout(self.t.send_timeout, l.tx.send(Frame::Text(Up::Hangup.json()))).await;
            // Over for Aviv now; the socket stays a moment, since the server checks for a
            // conclusion worth keeping, then closes.
            self.sh.end("hung up".into());
            heard = timeout(self.t.hangup_wait, async {
                while let Some(Ok(f)) = l.rx.next().await {
                    if f == Frame::Close {
                        return true;
                    }
                }
                false
            })
            .await
            .unwrap_or(false);
        }
        if !heard && let Some(id) = &self.id {
            tracing::info!("voice: the server didn't confirm the hangup of {id}: owed");
            self.sh.events.hangup_owed(id);
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
            // A resume that said hello takes the call over: the old socket's Close is that, not
            // Jarvis ending the call (the server before #178 sent it without a code, and the phone
            // often read it before the new socket's `ready`: 3 of 4 resumes on 2026-10-29 ended).
            Some(Ok(Frame::Close)) if self.next.as_ref().is_some_and(|d| d.hello.load(Ordering::SeqCst)) => {
                self.broke("taken over by the resume");
                return None;
            }
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
                // While a tap's pause drops the audio, the phone holds none of it.
                Some(Down::Sentence { .. } | Down::Said { .. }) if sh.pausing.load(Ordering::SeqCst) => {}
                Some(Down::Sentence { seq }) => sh.player.lock().unwrap().sentence(seq),
                Some(Down::Said { seq }) => sh.player.lock().unwrap().said(seq),
                Some(Down::Paused { on }) => {
                    sh.pausing.store(false, Ordering::SeqCst);
                    sh.paused.store(on, Ordering::SeqCst);
                    sh.events.paused(on);
                }
                Some(Down::Muted { on }) => {
                    sh.voice_muted.store(on, Ordering::SeqCst);
                    sh.events.voice_muted(on);
                }
                Some(Down::Error { text }) => sh.events.error(&text),
                Some(Down::Cut { id, from, to }) => {
                    let d = self.stt.as_mut().map(|s| s.cut(id, from, to, now)).unwrap_or_default();
                    self.stt_do(d);
                }
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
        self.rekick_at = None;
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
                if self.down.is_some() && self.online {
                    let wait = (self.t.backoff_min * 2u32.saturating_pow(self.tries.min(16))).min(self.t.backoff_max);
                    self.tries += 1;
                    self.retry_at = Some(Instant::now() + wait);
                }
                return None;
            }
        };
        let resumed = self.id.as_ref() == Some(&call);
        if let Some(down) = self.down.filter(|_| self.id.is_some()) {
            tracing::info!("voice: back on a new socket after {} ms down{}", down.elapsed().as_millis(), if resumed { "" } else { ", as a new call" });
            self.sh.events.resumed();
        }
        if self.id.as_ref().is_some_and(|old| *old != call) {
            // The server no longer had the old call: this is a new one, without what was said before,
            // and what's still queued of the old reply won't be continued.
            self.sh.events.error("new call: Jarvis lost the old one");
            self.sh.player.lock().unwrap().flush();
            self.sh.events.flush();
        }
        self.holds = true;
        if !resumed {
            self.sh.events.joined(&call);
        }
        self.rejoin = false;
        self.id = Some(call);
        // The old socket (if any) goes here: the server has handed the call to this one.
        self.line = Some(Line::new(tx, rx));
        let d = self.stt.as_mut().map(|s| s.new_line()).unwrap_or_default();
        self.stt_do(d);
        self.down = None;
        self.tries = 0;
        self.retry_at = None;
        self.rekick_at = None;
        // A new call starts unmuted; a resumed one may have been muted or unmuted while the line was down.
        // Still muted by voice (no tap since): an unmute here would lift it, so the server keeps it and
        // says `muted` again after its `ready`.
        let muted = self.sh.muted.load(Ordering::SeqCst);
        let voice = self.sh.voice_muted.load(Ordering::SeqCst);
        if !resumed && self.sh.voice_muted.swap(false, Ordering::SeqCst) {
            self.sh.events.voice_muted(false);
        }
        if muted || resumed && !voice {
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
        if let Some(s) = self.stt.as_mut() {
            s.tick(Instant::now());
        }
        if let Some(l) = self.line.as_mut() {
            let quiet = l.last_in.elapsed();
            if quiet >= self.t.dead_after {
                self.broke(&format!("nothing for {}s", quiet.as_secs()));
            } else if l.stalled(&self.t) {
                if self.down.is_none() {
                    tracing::info!("voice: line stalled after {} ms quiet: kicking the network, dialling a second socket", quiet.as_millis());
                    self.down = Some(Instant::now());
                    self.kick();
                    if self.next.is_none() && self.retry_at.is_none() {
                        self.redial();
                    }
                }
            } else if quiet >= self.t.ping_after && !l.pinged {
                l.pinged = true;
                self.send(Frame::Ping).await;
            }
        }
        if self.rekick_at.is_some_and(|at| Instant::now() >= at) {
            self.rekick();
        }
        let down = self.down?;
        if !self.shown && down.elapsed() >= self.t.grace {
            self.shown = true;
            self.sh.live.store(false, Ordering::SeqCst);
            // What's queued of Jarvis's reply plays on; the resume picks up after it.
            self.sh.earcon(Earcon::Dropped);
            self.sh.set_status(Status::Reconnecting);
        }
        // A call the server has is never given up: it waits for Aviv, and so does the phone.
        if self.id.is_none() && down.elapsed() >= self.t.first_give_up {
            return Some("couldn't reach Jarvis".into());
        }
        None
    }
}
