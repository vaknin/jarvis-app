//! The forum tunnel: `127.0.0.1:PORT` on the phone, over iroh to the forum's gate on Jarvis's
//! server (the jarvis repo's `forum/phone/tunnel`, `forum-gate listen`). It speaks dumbpipe's
//! protocol: one iroh connection per TCP connection, ALPN `DUMBPIPEV0`, and the dialer writes
//! `hello` first. A second port, `VOICE_PORT`, is the same pipe opened with `voice`, which the gate
//! sends to Jarvis's voice service (`jarvis-voice`, the calls of [`crate::voice`]). The gate lets in only device ids it knows, so the phone dials with its own fixed
//! key. Chrome counts `http://localhost` as secure, so the forum installs as an app with push.
//!
//! Battery: only the local TCP listener is always there. The iroh endpoint (and with it the relay
//! link, which pings every 15 s) is made when the browser (or a call) first connects and closed
//! after [`IDLE`] with no connection open; a call holds its connection open for as long as it lasts. Its own endpoint, apart from the Tether node's.
//!
//! A pipe that can't connect, or whose iroh side fails under it, closes the endpoint, so the next pipe
//! binds a fresh one ([`Lazy::broken`]): after a network change iroh's UDP socket can stay dead
//! (netwatch 0.19's failed rebind), and reusing it made every redial of a call die within seconds.
//! Each pipe logs one line when it ends (`forum: pipe …`): its kind, connect time, path, bytes and why.

use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::{Context, Result};
use iroh::{
    Endpoint, EndpointAddr, SecretKey,
    endpoint::{Connection, ConnectionError, PathEvent, presets},
};
use iroh_tickets::endpoint::EndpointTicket;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};

use crate::RT;

const ALPN: &[u8] = b"DUMBPIPEV0";
const HANDSHAKE: &[u8; 5] = b"hello";
const VOICE_HANDSHAKE: &[u8; 5] = b"voice";
/// The endpoint closes this long after the last forum connection ended.
const IDLE: Duration = Duration::from_secs(180);
/// At most one rebuild this often, so a gate that's really down doesn't churn endpoints.
const REBUILD_EVERY: Duration = Duration::from_secs(2);
/// Another pipe that heard from the gate this recently proves the endpoint works: no rebuild.
const HEARD: Duration = Duration::from_secs(2);
/// Once the app has closed its side, how long the gate's side may stay silent before the pipe ends,
/// instead of waiting out iroh's idle timeout on a gate that went away.
const LINGER: Duration = Duration::from_secs(5);

static STATUS: Mutex<String> = Mutex::new(String::new());
static STARTED: Mutex<bool> = Mutex::new(false);
static ENDPOINT: Mutex<Option<Arc<Lazy>>> = Mutex::new(None);

fn set_status(s: impl Into<String>) {
    *STATUS.lock().unwrap() = s.into();
}

/// Starts the tunnel once per process (the forum on `port`, the voice service on `voice_port`);
/// later calls do nothing. A failure (a port taken, a bad ticket) is retried every few seconds for
/// as long as the process lives.
#[uniffi::export]
pub fn forum_start(ticket: String, port: u16, voice_port: u16, secret: String) {
    let mut started = STARTED.lock().unwrap();
    if *started {
        return;
    }
    *started = true;
    set_status("starting");
    RT.spawn(async move {
        loop {
            if let Err(e) = serve(&ticket, port, voice_port, &secret).await {
                set_status(format!("{e:#}"));
            }
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    });
}

/// "idle", "on", "on (last error: …)", or why it's down.
#[uniffi::export]
pub fn forum_status() -> String {
    STATUS.lock().unwrap().clone()
}

/// The phone changed networks: iroh looks for its paths again now rather than on its own schedule.
#[uniffi::export]
pub fn forum_network_changed() {
    let Some(lazy) = ENDPOINT.lock().unwrap().clone() else { return };
    RT.spawn(async move {
        let ep = lazy.ep.lock().await.clone();
        if let Some((_, ep)) = ep {
            ep.network_change().await;
        }
    });
}

/// When a pipe last read from the gate.
type Heard = Arc<Mutex<Instant>>;

/// The iroh endpoint, made on first use and closed when idle.
struct Lazy {
    key: SecretKey,
    /// The endpoint, with which one it is (each rebuild counts up).
    ep: tokio::sync::Mutex<Option<(u64, Endpoint)>>,
    made: AtomicU64,
    open: AtomicUsize,
    last: Mutex<Instant>,
    rebuilt: Mutex<Option<Instant>>,
    /// The open pipes: their endpoint, and when each last read from the gate.
    pipes: Mutex<HashMap<u64, (u64, Heard)>>,
}

impl Lazy {
    async fn get(&self) -> Result<(u64, Endpoint)> {
        let mut ep = self.ep.lock().await;
        if let Some(e) = ep.as_ref() {
            return Ok(e.clone());
        }
        let e = Endpoint::builder(presets::N0).secret_key(self.key.clone()).bind().await.context("iroh")?;
        let e = (self.made.fetch_add(1, Ordering::SeqCst), e);
        *ep = Some(e.clone());
        Ok(e)
    }

    /// `ep` failed a pipe: close it (and with it every pipe still on it, which a fresh endpoint with
    /// the same id would cut off at the relay anyway), so the next pipe makes a new one.
    async fn broken(&self, pipe: u64, which: u64, why: &str) {
        let alive = self.pipes.lock().unwrap().iter().any(|(n, (ep, heard))| {
            *n != pipe && *ep == which && heard.lock().unwrap().elapsed() < HEARD
        });
        if alive {
            tracing::info!("forum: keeping the endpoint, another pipe hears the gate: {why}");
            return;
        }
        {
            let mut at = self.rebuilt.lock().unwrap();
            if at.is_some_and(|t| t.elapsed() < REBUILD_EVERY) {
                return;
            }
            *at = Some(Instant::now());
        }
        let mut cur = self.ep.lock().await;
        if cur.as_ref().is_some_and(|(n, _)| *n == which)
            && let Some((_, ep)) = cur.take()
        {
            drop(cur);
            tracing::warn!("forum: closing the endpoint, the next pipe makes a new one: {why}");
            ep.close().await;
        }
    }

    async fn reap(self: Arc<Self>) {
        loop {
            tokio::time::sleep(Duration::from_secs(30)).await;
            let idle = self.open.load(Ordering::SeqCst) == 0 && self.last.lock().unwrap().elapsed() > IDLE;
            if idle && let Some((_, e)) = self.ep.lock().await.take() {
                e.close().await;
                set_status("idle");
            }
        }
    }
}

async fn serve(ticket: &str, port: u16, voice_port: u16, secret: &str) -> Result<()> {
    let ticket: EndpointTicket = ticket.trim().parse().context("bad ticket")?;
    let addr = ticket.endpoint_addr().clone();
    let key: SecretKey = secret.trim().parse().context("bad key")?;
    let forum = TcpListener::bind(("127.0.0.1", port)).await.with_context(|| format!("port {port}"))?;
    let voice = TcpListener::bind(("127.0.0.1", voice_port)).await.with_context(|| format!("port {voice_port}"))?;
    let lazy = Arc::new(Lazy {
        key,
        ep: Default::default(),
        made: AtomicU64::new(0),
        open: AtomicUsize::new(0),
        last: Mutex::new(Instant::now()),
        rebuilt: Mutex::new(None),
        pipes: Default::default(),
    });
    *ENDPOINT.lock().unwrap() = Some(lazy.clone());
    let reaper = tokio::spawn(lazy.clone().reap());
    set_status("idle");
    let res = async {
        loop {
            let (tcp, handshake) = tokio::select! {
                r = forum.accept() => (r?.0, HANDSHAKE),
                r = voice.accept() => (r?.0, VOICE_HANDSHAKE),
            };
            tcp.set_nodelay(true)?;
            let (lazy, addr) = (lazy.clone(), addr.clone());
            lazy.open.fetch_add(1, Ordering::SeqCst);
            let n = PIPES.fetch_add(1, Ordering::Relaxed);
            tokio::spawn(async move {
                let res = match lazy.get().await {
                    Ok((which, ep)) => {
                        let heard = Arc::new(Mutex::new(Instant::now()));
                        lazy.pipes.lock().unwrap().insert(n, (which, heard.clone()));
                        let end = pipe(n, tcp, &ep, addr, handshake, &heard).await;
                        if let Some(why) = &end.broken {
                            lazy.broken(n, which, why).await;
                        }
                        lazy.pipes.lock().unwrap().remove(&n);
                        end.res
                    }
                    Err(e) => Err(e),
                };
                match res {
                    Ok(()) => set_status("on"),
                    Err(e) => set_status(format!("on (last error: {e:#})")),
                }
                *lazy.last.lock().unwrap() = Instant::now();
                lazy.open.fetch_sub(1, Ordering::SeqCst);
            });
        }
    }
    .await;
    reaper.abort();
    res
}

static PIPES: AtomicU64 = AtomicU64::new(1);

/// How a pipe ended: `broken` says why its endpoint should go, if it should.
struct End {
    res: Result<()>,
    broken: Option<String>,
}

/// One browser connection (or call), both ways, until both sides are done.
async fn pipe(n: u64, tcp: TcpStream, endpoint: &Endpoint, addr: EndpointAddr, handshake: &[u8; 5], heard: &Mutex<Instant>) -> End {
    let kind = if handshake == VOICE_HANDSHAKE { "voice" } else { "forum" };
    let t0 = Instant::now();
    let conn = match async {
        let conn = endpoint.connect(addr, ALPN).await.context("connect")?;
        let streams = conn.open_bi().await.context("open")?;
        anyhow::Ok((conn, streams))
    }
    .await
    {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("forum: pipe {n} {kind}: no link after {} ms: {e:#}", t0.elapsed().as_millis());
            return End { broken: Some(format!("{e:#}")), res: Err(e) };
        }
    };
    let ((conn, (mut send, mut recv)), connect_ms) = (conn, t0.elapsed().as_millis());
    let paths = Arc::new(Mutex::new(vec![via(&conn)]));
    let watch = tokio::spawn({
        let (mut events, paths) = (conn.path_events(), paths.clone());
        async move {
            use futures_util::StreamExt;
            while let Some(e) = events.next().await {
                if let PathEvent::Selected { remote_addr, .. } = e {
                    let p = if remote_addr.is_relay() { "relay" } else { "direct" };
                    let mut paths = paths.lock().unwrap();
                    if paths.last() != Some(&p) {
                        paths.push(p);
                    }
                }
            }
        }
    });
    let (up_n, down_n) = (AtomicU64::new(0), AtomicU64::new(0));
    // Set when the iroh side failed (not the app's socket, which a browser resets all the time).
    let lost = AtomicBool::new(false);
    let res = async {
        if let Err(e) = send.write_all(handshake).await {
            lost.store(true, Ordering::Relaxed);
            return Err(anyhow::Error::from(e).context("hello"));
        }
        let (mut r, mut w) = tcp.into_split();
        let app_done = tokio::sync::Notify::new();
        let up = async {
            pump(&mut r, &mut send, &up_n, Side::Writer, &lost, None).await?;
            send.finish()?;
            app_done.notify_one();
            let _ = tokio::time::timeout(LINGER, send.stopped()).await;
            anyhow::Ok(())
        };
        let down = async {
            let quiet = async {
                app_done.notified().await;
                loop {
                    let since = heard.lock().unwrap().elapsed();
                    if since >= LINGER {
                        break;
                    }
                    tokio::time::sleep(LINGER - since).await;
                }
            };
            tokio::select! {
                res = pump(&mut recv, &mut w, &down_n, Side::Reader, &lost, Some(heard)) => res?,
                _ = quiet => anyhow::bail!("the app closed and the gate said nothing for {} s", LINGER.as_secs()),
            }
            w.shutdown().await?;
            anyhow::Ok(())
        };
        tokio::try_join!(up, down)?;
        anyhow::Ok(())
    }
    .await;
    let lasted = t0.elapsed();
    let reason = conn.close_reason();
    drop(conn);
    watch.abort();
    let lost = lost.load(Ordering::Relaxed)
        || matches!(reason, Some(ConnectionError::TimedOut | ConnectionError::Reset | ConnectionError::TransportError(_)));
    let why = match (&res, &reason) {
        (Ok(()), _) => "done".to_string(),
        (Err(e), Some(r)) => format!("{e:#} (link: {r})"),
        (Err(e), None) => format!("{e:#}"),
    };
    tracing::info!(
        "forum: pipe {n} {kind}: connect {connect_ms} ms, via {}, {:.1} s, up {} B, down {} B: {why}",
        paths.lock().unwrap().join(">"),
        lasted.as_secs_f32(),
        up_n.load(Ordering::Relaxed),
        down_n.load(Ordering::Relaxed),
    );
    let broken = (res.is_err() && lost).then(|| format!("pipe {n}: {why}"));
    End { res, broken }
}

/// "relay" or "direct": the path the connection sends on now.
fn via(conn: &Connection) -> &'static str {
    let paths = conn.paths();
    match paths.iter().find(|p| p.is_selected()) {
        Some(p) if p.is_relay() => "relay",
        Some(_) => "direct",
        None => "none",
    }
}

/// Which end of a [`pump`] is the iroh stream.
#[derive(Clone, Copy, PartialEq)]
enum Side {
    Reader,
    Writer,
}

/// `copy`, counting what went through even when it fails, setting `lost` when the iroh end fails,
/// and `heard` on every read.
async fn pump(
    r: &mut (impl AsyncRead + Unpin),
    w: &mut (impl AsyncWrite + Unpin),
    n: &AtomicU64,
    iroh: Side,
    lost: &AtomicBool,
    heard: Option<&Mutex<Instant>>,
) -> Result<()> {
    let mut buf = vec![0u8; 16 * 1024];
    let fail = |side: Side, e: std::io::Error| {
        if side == iroh {
            lost.store(true, Ordering::Relaxed);
        }
        anyhow::Error::from(e).context(if side == iroh { "iroh side" } else { "app side" })
    };
    loop {
        let k = r.read(&mut buf).await.map_err(|e| fail(Side::Reader, e))?;
        if k == 0 {
            return Ok(());
        }
        if let Some(h) = heard {
            *h.lock().unwrap() = Instant::now();
        }
        w.write_all(&buf[..k]).await.map_err(|e| fail(Side::Writer, e))?;
        n.fetch_add(k as u64, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use tether_voice::{Call, Events, Status, Timing, websocket};

    #[derive(Default)]
    struct Log(Mutex<Vec<String>>);

    impl Events for Log {
        fn status(&self, s: Status) {
            self.0.lock().unwrap().push(format!("{s:?}"));
        }
        fn text(&self, who: &str, text: &str) {
            self.0.lock().unwrap().push(format!("{who}: {text}"));
        }
        fn error(&self, text: &str) {
            self.0.lock().unwrap().push(format!("error {text}"));
        }
        fn flush(&self) {}
    }

    /// A real call through the gate on dibs: `FORUM_TICKET=… FORUM_SECRET=… cargo test -p tether-ffi
    /// -- --ignored voice_through_the_gate` with a voice service on 127.0.0.1:8790 (jarvis-voice, or
    /// `cargo run -p tether-voice --example fake_server`), and a key the gate lets in.
    #[test]
    #[ignore]
    fn voice_through_the_gate() {
        let (ticket, secret) = (std::env::var("FORUM_TICKET").unwrap(), std::env::var("FORUM_SECRET").unwrap());
        eprintln!("id {}", secret.trim().parse::<super::SecretKey>().unwrap().public());
        crate::init_logging();
        super::forum_start(ticket, 18080, 18791, secret);
        let log = std::sync::Arc::new(Log::default());
        let _rt = crate::RT.enter();
        let call = Call::start(websocket("ws://127.0.0.1:18791/call".into()), log.clone(), Timing::default());
        let has = |s: &str| log.0.lock().unwrap().iter().any(|l| l.contains(s));
        let start = std::time::Instant::now();
        while !has("Listening") {
            assert!(start.elapsed().as_secs() < 60, "no call: {:?} / {}", log.0.lock().unwrap(), super::forum_status());
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        eprintln!("ready after {:?}", start.elapsed());
        // 3 s of mic, then the answer: Jarvis's audio comes back through the tunnel.
        let t = std::time::Instant::now();
        let mut frame = 0;
        let mut heard = 0usize;
        while t.elapsed().as_secs() < 10 && heard < 16_000 {
            if t.elapsed().as_millis() as usize >= frame * 20 {
                call.send_audio(vec![0; 640]);
                frame += 1;
            }
            heard += call.buffered_ms() as usize * 16;
            let _ = call.pull_audio(160);
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        // VOICE_HOLD=N keeps the call up N more seconds (to restart the gate under it, say).
        let hold = std::env::var("VOICE_HOLD").ok().and_then(|s| s.parse().ok()).unwrap_or(0u64);
        let t = std::time::Instant::now();
        while t.elapsed().as_secs() < hold {
            call.send_audio(vec![0; 640]);
            let _ = call.pull_audio(320);
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        eprintln!("log: {:?}", log.0.lock().unwrap());
        assert!(heard > 0, "no audio came back");
        assert!(log.0.lock().unwrap().iter().rev().find(|l| !l.contains(':')).is_some_and(|l| !l.contains("Reconnecting")), "still down");
        call.hangup();
        while !has("Ended") {
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
