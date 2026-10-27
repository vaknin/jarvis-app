//! The call itself: connect, `hello`, stream both ways, and come back with `resume` after a drop.
//!
//! - A Close frame from the server means Jarvis ended the call. A socket that just breaks (EOF, an
//!   error, or nothing at all from the server for [`Timing::dead_after`]) is a drop: the phone plays
//!   the Dropped earcon and reconnects with backoff, sending `resume` with the call's id, until
//!   [`Timing::resume_give_up`] after the drop (the server keeps a call 5 minutes).
//! - Liveness counts only what comes in: the mic's frames go out 50 times a second even into a dead
//!   pipe (the forum tunnel is a local TCP socket, which takes writes long after iroh has gone). With
//!   nothing in for [`Timing::ping_after`] it sends a WebSocket ping, which every server answers.
//! - Writes that don't finish within [`Timing::send_timeout`] count as a drop too, so a stuck pipe
//!   can't stall the call.

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
    sync::mpsc,
    time::{Instant, sleep_until, timeout},
};

use crate::{
    player::{Earcon, Player},
    proto::{Down, Up},
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
    /// Drop when nothing came in for this long.
    pub dead_after: Duration,
    /// After a network change, drop unless something comes in within this.
    pub net_check: Duration,
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
            ping_after: Duration::from_secs(5),
            dead_after: Duration::from_secs(15),
            net_check: Duration::from_secs(4),
            send_timeout: Duration::from_secs(3),
            hangup_wait: Duration::from_secs(3),
            jitter: Duration::from_millis(120),
        }
    }
}

enum Cmd {
    Mute(bool),
    Hangup,
    NetworkChanged,
}

struct Shared {
    player: Mutex<Player>,
    events: Arc<dyn Events>,
    status: Mutex<Option<Status>>,
    live: AtomicBool,
    muted: AtomicBool,
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
        let shared = Arc::new(Shared {
            player: Mutex::new(Player::new(timing.jitter)),
            events,
            status: Mutex::new(None),
            live: AtomicBool::new(false),
            muted: AtomicBool::new(false),
        });
        let (cmd, cmd_rx) = mpsc::unbounded_channel();
        // 10 frames (200 ms): a send that lags more than that drops the newest speech, not the call.
        let (mic, mic_rx) = mpsc::channel(10);
        tokio::spawn(run(connector, shared.clone(), cmd_rx, mic_rx, timing));
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

enum Outcome {
    HungUp,
    /// The server closed: the call is over.
    Ended(String),
    /// The line broke; `live` if the call was up on it (`ready` came).
    Dropped { live: bool, why: String },
}

async fn run(
    connector: Connector,
    sh: Arc<Shared>,
    mut cmd: mpsc::UnboundedReceiver<Cmd>,
    mut mic: mpsc::Receiver<Vec<u8>>,
    t: Timing,
) {
    let mut id: Option<String> = None;
    // When the line was last lost (or the call started), and failed tries since.
    let mut lost_at = Instant::now();
    let mut tries = 0u32;
    let why = 'call: loop {
        sh.set_status(if id.is_none() { Status::Connecting } else { Status::Reconnecting });
        let connect = timeout(t.connect_timeout, connector());
        tokio::pin!(connect);
        let res = loop {
            tokio::select! {
                r = &mut connect => break r.map_err(|_| anyhow!("timed out")).and_then(|r| r),
                c = cmd.recv() => match c {
                    Some(Cmd::Hangup) | None => break 'call "hung up".to_string(),
                    Some(_) => {}
                },
            }
        };
        let dropped = match res {
            Ok((tx, rx)) => match session(tx, rx, &sh, &mut cmd, &mut mic, &t, &mut id).await {
                Outcome::HungUp => break "hung up".into(),
                Outcome::Ended(why) => break why,
                Outcome::Dropped { live, why } => {
                    if live {
                        lost_at = Instant::now();
                        tries = 0;
                        sh.earcon(Earcon::Dropped);
                    }
                    why
                }
            },
            Err(e) => format!("{e:#}"),
        };
        tracing::info!("voice: line down: {dropped}");
        let give_up = if id.is_none() { t.first_give_up } else { t.resume_give_up };
        if lost_at.elapsed() >= give_up {
            break if id.is_none() { "couldn't reach Jarvis".into() } else { "lost the line".into() };
        }
        sh.set_status(if id.is_none() { Status::Connecting } else { Status::Reconnecting });
        let wait = (t.backoff_min * 2u32.saturating_pow(tries.min(16))).min(t.backoff_max);
        tries += 1;
        let until = (Instant::now() + wait).min(lost_at + give_up);
        loop {
            tokio::select! {
                _ = sleep_until(until) => break,
                c = cmd.recv() => match c {
                    Some(Cmd::Hangup) | None => break 'call "hung up".to_string(),
                    // A new network: try now.
                    Some(Cmd::NetworkChanged) => break,
                    Some(Cmd::Mute(_)) => {}
                },
            }
        }
    };
    sh.end(why);
}

/// One socket, from `hello` until it ends.
async fn session(
    mut tx: Tx,
    mut rx: Rx,
    sh: &Shared,
    cmd: &mut mpsc::UnboundedReceiver<Cmd>,
    mic: &mut mpsc::Receiver<Vec<u8>>,
    t: &Timing,
    id: &mut Option<String>,
) -> Outcome {
    macro_rules! send {
        ($f:expr, $live:expr) => {
            match timeout(t.send_timeout, tx.send($f)).await {
                Ok(Ok(())) => {}
                Ok(Err(e)) => return Outcome::Dropped { live: $live, why: format!("send: {e:#}") },
                Err(_) => return Outcome::Dropped { live: $live, why: "send timed out".into() },
            }
        };
    }

    send!(Frame::Text(Up::Hello { version: 1, resume: id.clone() }.json()), false);
    let deadline = Instant::now() + t.connect_timeout;
    let call = loop {
        tokio::select! {
            f = rx.next() => match f {
                Some(Ok(Frame::Text(s))) => match Down::parse(&s) {
                    Some(Down::Ready { call }) => break call,
                    Some(Down::Error { text }) => sh.events.error(&text),
                    _ => {}
                },
                Some(Ok(Frame::Close)) => return Outcome::Ended("Jarvis ended the call".into()),
                Some(Ok(_)) => {}
                Some(Err(e)) => return Outcome::Dropped { live: false, why: format!("{e:#}") },
                None => return Outcome::Dropped { live: false, why: "closed before ready".into() },
            },
            _ = sleep_until(deadline) => return Outcome::Dropped { live: false, why: "no ready".into() },
            c = cmd.recv() => match c {
                Some(Cmd::Hangup) | None => {
                    let _ = timeout(t.send_timeout, tx.send(Frame::Text(Up::Hangup.json()))).await;
                    return Outcome::HungUp;
                }
                Some(_) => {}
            },
        }
    };

    let resumed = id.as_ref() == Some(&call);
    if id.as_ref().is_some_and(|old| *old != call) {
        // The server no longer had the old call: this is a new one, without what was said before.
        sh.events.error("new call: Jarvis lost the old one");
    }
    *id = Some(call);
    sh.earcon(Earcon::Connected);
    // A new call starts unmuted; a resumed one may have been muted or unmuted while the line was down.
    let muted = sh.muted.load(Ordering::SeqCst);
    if muted || resumed {
        send!(Frame::Text(Up::Mute { on: muted }.json()), true);
    }
    while mic.try_recv().is_ok() {}
    sh.live.store(true, Ordering::SeqCst);
    sh.set_status(Status::Listening);

    let out = live(&mut tx, &mut rx, sh, cmd, mic, t).await;
    sh.live.store(false, Ordering::SeqCst);
    if matches!(out, Outcome::Dropped { .. }) {
        // What was queued of Jarvis's reply is stale now; the server goes on after the resume.
        sh.player.lock().unwrap().flush();
        sh.events.flush();
    }
    out
}

async fn live(
    tx: &mut Tx,
    rx: &mut Rx,
    sh: &Shared,
    cmd: &mut mpsc::UnboundedReceiver<Cmd>,
    mic: &mut mpsc::Receiver<Vec<u8>>,
    t: &Timing,
) -> Outcome {
    macro_rules! send {
        ($f:expr) => {
            match timeout(t.send_timeout, tx.send($f)).await {
                Ok(Ok(())) => {}
                Ok(Err(e)) => return Outcome::Dropped { live: true, why: format!("send: {e:#}") },
                Err(_) => return Outcome::Dropped { live: true, why: "send timed out".into() },
            }
        };
    }

    let mut last_in = Instant::now();
    let mut pinged = false;
    let mut check: Option<Instant> = None;
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    loop {
        tokio::select! {
            Some(pcm) = mic.recv() => {
                if !sh.muted.load(Ordering::SeqCst) {
                    send!(Frame::Binary(pcm));
                }
            }
            c = cmd.recv() => match c {
                Some(Cmd::Mute(on)) => send!(Frame::Text(Up::Mute { on }.json())),
                Some(Cmd::NetworkChanged) => {
                    send!(Frame::Ping);
                    check = Some(Instant::now() + t.net_check);
                }
                Some(Cmd::Hangup) | None => {
                    // Even on a line that just broke: a hangup ends the call, it never reconnects.
                    // The server ends a call it doesn't hear back from after 5 minutes anyway.
                    let _ = timeout(t.send_timeout, tx.send(Frame::Text(Up::Hangup.json()))).await;
                    // Over for Aviv now; the socket stays a moment, since the server checks for a
                    // conclusion worth keeping, then closes.
                    sh.end("hung up".into());
                    let _ = timeout(t.hangup_wait, async { while let Some(Ok(f)) = rx.next().await { if f == Frame::Close { break } } }).await;
                    return Outcome::HungUp;
                }
            },
            f = rx.next() => {
                last_in = Instant::now();
                pinged = false;
                check = None;
                match f {
                    Some(Ok(Frame::Binary(b))) => sh.player.lock().unwrap().push(&b, last_in),
                    Some(Ok(Frame::Text(s))) => match Down::parse(&s) {
                        Some(Down::State { state }) => {
                            let s = match state.as_str() {
                                "listening" => Status::Listening,
                                "thinking" => Status::Thinking,
                                "speaking" => Status::Speaking,
                                _ => continue,
                            };
                            if s == Status::Thinking && sh.status.lock().unwrap().as_ref() != Some(&Status::Thinking) {
                                sh.earcon(Earcon::Thinking);
                            }
                            sh.set_status(s);
                        }
                        Some(Down::Flush) => {
                            sh.player.lock().unwrap().flush();
                            sh.events.flush();
                        }
                        Some(Down::Text { who, text }) => sh.events.text(&who, &text),
                        Some(Down::Error { text }) => sh.events.error(&text),
                        Some(Down::Ready { .. }) | Some(Down::Unknown) | None => {}
                    },
                    Some(Ok(Frame::Close)) => return Outcome::Ended("Jarvis ended the call".into()),
                    Some(Ok(Frame::Ping | Frame::Pong)) => {}
                    Some(Err(e)) => return Outcome::Dropped { live: true, why: format!("{e:#}") },
                    None => return Outcome::Dropped { live: true, why: "closed".into() },
                }
            }
            _ = tick.tick() => {
                let quiet = last_in.elapsed();
                if quiet >= t.dead_after {
                    return Outcome::Dropped { live: true, why: format!("nothing for {}s", quiet.as_secs()) };
                }
                if check.is_some_and(|c| Instant::now() >= c) {
                    return Outcome::Dropped { live: true, why: "no answer after a network change".into() };
                }
                if quiet >= t.ping_after && !pinged {
                    pinged = true;
                    send!(Frame::Ping);
                }
            }
        }
    }
}
