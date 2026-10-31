//! The forum tunnel: `127.0.0.1:PORT` on the phone, over iroh to the forum's gate on Jarvis's
//! server (the jarvis repo's `forum/phone/tunnel`, `forum-gate listen`). It speaks dumbpipe's
//! protocol: one iroh connection per TCP connection, ALPN `DUMBPIPEV0`, and the dialer writes
//! `hello` first. A second port, `VOICE_PORT`, is the same pipe opened with `voice`, which the gate
//! sends to Jarvis's voice service (`jarvis-voice`, the calls of [`crate::voice`]). The gate lets in only device ids it knows, so the phone dials with its own fixed
//! key. Chrome counts `http://localhost` as secure, so the forum installs as an app with push.
//!
//! Battery: only the local TCP listener is always there. The iroh endpoint (and with it the relay
//! link, which pings every 15 s) is made when the browser (or a call) first connects and closed
//! after [`IDLE`] with no connection open; a call holds its connection open for as long as it lasts. Its own endpoint, apart from the Jarvis node's.
//!
//! A pipe that can't connect, or whose iroh side fails under it, closes the endpoint, so the next pipe
//! binds a fresh one ([`Lazy::broken`]): after a network change iroh's UDP socket can stay dead
//! (netwatch 0.19's failed rebind), and reusing it made every redial of a call die within seconds.
//! Each pipe logs one line when it ends (`forum: pipe …`): its kind, connect time, path, bytes and why.
//!
//! The same endpoint takes Jarvis's notifications, which the gate dials it with ([`crate::alerts`]):
//! once the app starts them, the endpoint stays up instead of closing when idle.
//!
//! The same endpoint asks the gate for app updates ([`update_check`], [`update_fetch`]): the
//! handshake `build`, which the gate answers itself from the builds Jarvis published there (the
//! jarvis repo's `forum-gate publish`, `forum/phone/tunnel/src/builds.rs`, which has the wire format).
//! And it sends the call's recent log lines after a drop ([`send_log`], the handshake `logup`, the
//! gate's `forum/phone/tunnel/src/phonelogs.rs`), so the next drop can be read away from home.

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
    io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader},
    net::{TcpListener, TcpStream},
};

use crate::{RT, Res, on_rt};

const ALPN: &[u8] = b"DUMBPIPEV0";
const HANDSHAKE: &[u8; 5] = b"hello";
const VOICE_HANDSHAKE: &[u8; 5] = b"voice";
const BUILD_HANDSHAKE: &[u8; 5] = b"build";
const LOG_HANDSHAKE: &[u8; 5] = b"logup";
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
/// The tunnel's task, for [`forum_stop`].
static SERVING: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

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
    let task = RT.spawn(async move {
        loop {
            if let Err(e) = serve(&ticket, port, voice_port, &secret).await {
                set_status(format!("{e:#}"));
            }
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    });
    *SERVING.lock().unwrap() = Some(task);
}

/// Lets go of everything [`forum_start`] holds, for the app that takes over the phone's key (thread
/// #190): both ports are free and the key is off the relay when this returns, and nothing here dials
/// again until the next [`forum_start`]. Blocks; never call it on the library's runtime.
#[uniffi::export]
pub fn forum_stop() {
    let mut started = STARTED.lock().unwrap();
    *started = false;
    let task = SERVING.lock().unwrap().take();
    RT.block_on(async move {
        if let Some(task) = task {
            task.abort();
            let _ = task.await;
        }
        // After the task: it's what sets this.
        let lazy = ENDPOINT.lock().unwrap().take();
        if let Some(lazy) = lazy {
            lazy.stopped.store(true, Ordering::SeqCst);
            let ep = lazy.ep.lock().await.take();
            if let Some((_, ep)) = ep {
                let _ = tokio::time::timeout(Duration::from_secs(5), ep.close()).await;
            }
        }
    });
    set_status("stopped");
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
        if let Some((n, ep)) = ep {
            tracing::info!("forum: network change: endpoint {n} looks for its paths again");
            ep.network_change().await;
        }
    });
}

/// Does the gate answer from here? Asked with a throwaway key before the phone's own may go on the
/// air: the gate turns a stranger away by name, which proves it's up and reachable, and the app that
/// holds the phone's key is left alone.
#[uniffi::export]
pub async fn forum_probe(ticket: String) -> Res<()> {
    Ok(on_rt(async move {
        let ticket: EndpointTicket = ticket.trim().parse().context("bad ticket")?;
        let ep = Endpoint::builder(presets::N0).secret_key(SecretKey::generate()).bind().await.context("iroh")?;
        let answer = tokio::time::timeout(Duration::from_secs(30), async {
            let conn = ep.connect(ticket.endpoint_addr().clone(), ALPN).await.map_err(|e| e.to_string())?;
            Err::<(), String>(conn.closed().await.to_string())
        })
        .await;
        ep.close().await;
        match answer {
            Ok(Err(why)) if why.contains("not allowed") => Ok(()),
            Ok(Err(why)) => anyhow::bail!("the server: {why}"),
            Ok(Ok(())) => unreachable!(),
            Err(_) => anyhow::bail!("the server didn't answer in 30 s"),
        }
    })
    .await?)
}

/// When a pipe last read from the gate.
type Heard = Arc<Mutex<Instant>>;

/// The iroh endpoint, made on first use and closed when idle.
struct Lazy {
    key: SecretKey,
    /// The gate.
    addr: EndpointAddr,
    /// The endpoint, with which one it is (each rebuild counts up).
    ep: tokio::sync::Mutex<Option<(u64, Endpoint)>>,
    made: AtomicU64,
    open: AtomicUsize,
    last: Mutex<Instant>,
    rebuilt: Mutex<Option<Instant>>,
    /// The open pipes: their endpoint, and when each last read from the gate.
    pipes: Mutex<HashMap<u64, (u64, Heard)>>,
    /// [`keep_up`] holds the endpoint open.
    held: AtomicBool,
    /// [`forum_stop`] ran: no endpoint is made again, so a late ask can't put the key back on the relay.
    stopped: AtomicBool,
}

impl Lazy {
    async fn get(&self) -> Result<(u64, Endpoint)> {
        let mut ep = self.ep.lock().await;
        if let Some(e) = ep.as_ref() {
            return Ok(e.clone());
        }
        anyhow::ensure!(!self.stopped.load(Ordering::SeqCst), "the forum tunnel is stopped");
        let e = Endpoint::builder(presets::N0)
            .secret_key(self.key.clone())
            .alpns(vec![crate::alerts::ALERT_ALPN.to_vec()])
            .bind()
            .await
            .context("iroh")?;
        tokio::spawn(crate::alerts::accept(e.clone(), self.addr.id));
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
            if self.stopped.load(Ordering::SeqCst) {
                return;
            }
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
        addr: addr.clone(),
        ep: Default::default(),
        made: AtomicU64::new(0),
        open: AtomicUsize::new(0),
        last: Mutex::new(Instant::now()),
        rebuilt: Mutex::new(None),
        pipes: Default::default(),
        held: AtomicBool::new(false),
        stopped: AtomicBool::new(false),
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

/// For [`crate::alerts`]: the endpoint stays up from now on (a hold the reaper respects), and is
/// made again if it was closed (a rebuild after a broken pipe). False before [`forum_start`] has
/// got that far.
pub(crate) async fn keep_up() -> bool {
    let Some(lazy) = ENDPOINT.lock().unwrap().clone() else { return false };
    if !lazy.held.swap(true, Ordering::SeqCst) {
        lazy.open.fetch_add(1, Ordering::SeqCst);
    }
    if lazy.ep.lock().await.is_none() {
        match lazy.get().await {
            Ok(_) => set_status("on"),
            Err(e) => set_status(format!("{e:#}")),
        }
    }
    true
}

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

/// A stream to the gate that isn't a pipe: it holds the endpoint open, as a forum connection does.
pub(crate) struct Stream {
    pub send: iroh::endpoint::SendStream,
    pub recv: iroh::endpoint::RecvStream,
    _conn: Connection,
    _busy: Busy,
}

/// A long-lived stream on the tunnel's endpoint, for the tab screens. A failure here only costs a
/// redial: the endpoint is shared with calls, so a tab link never closes it while anything else runs
/// on it. With nothing else on it, a dead endpoint is rebuilt as a pipe would, once `stale` says it's
/// been failing a while.
pub(crate) async fn open_stream(handshake: &[u8; 5], stale: bool) -> Result<Stream> {
    let lazy = tunnel().await?;
    lazy.open.fetch_add(1, Ordering::SeqCst);
    let busy = Busy(lazy.clone());
    let (which, ep) = lazy.get().await?;
    let linked = tokio::time::timeout(Duration::from_secs(20), async {
        let conn = ep.connect(lazy.addr.clone(), ALPN).await.context("connect")?;
        let (mut send, recv) = conn.open_bi().await.context("open")?;
        send.write_all(handshake).await.context("hello")?;
        anyhow::Ok((conn, send, recv))
    })
    .await
    .context("the server didn't answer in 20 s")
    .and_then(|r| r);
    match linked {
        Ok((conn, send, recv)) => Ok(Stream { send, recv, _conn: conn, _busy: busy }),
        Err(e) => {
            // `open` counts a call from before it reaches `pipes`, and this dial itself.
            if stale && lazy.open.load(Ordering::SeqCst) <= 1 {
                lazy.broken(PIPES.fetch_add(1, Ordering::Relaxed), which, &format!("tabs: {e:#}")).await;
            }
            Err(e)
        }
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

/// A build Jarvis published for this app on the server.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppUpdate {
    pub version_code: i64,
    pub version_name: String,
    /// Bytes of the APK.
    pub size: u64,
    pub sha256: String,
    /// What changed, in plain words (the commits' "For you:" lines).
    pub notes: String,
}

/// The newest build the server offers for `app` (the applicationId), or none. Whether it's newer
/// than this one is the app's to decide. Needs [`forum_start`] first.
#[uniffi::export]
pub async fn update_check(app: String) -> Res<Option<AppUpdate>> {
    Ok(on_rt(async move { ask_build(&app, None).await }).await?)
}

/// Downloads the newest build for `app` to `path`, checked against its size and sha256 (a `.part`
/// beside it until then), and says which one it was.
#[uniffi::export]
pub async fn update_fetch(app: String, path: String) -> Res<AppUpdate> {
    Ok(on_rt(async move { ask_build(&app, Some(path.as_ref())).await?.context("nothing published") }).await?)
}

/// Holds the tunnel's endpoint open while an update ask runs, as a forum connection does.
struct Busy(Arc<Lazy>);

impl Drop for Busy {
    fn drop(&mut self) {
        *self.0.last.lock().unwrap() = Instant::now();
        self.0.open.fetch_sub(1, Ordering::SeqCst);
    }
}

/// The tunnel's endpoint, waiting a moment for it if the app's service hasn't started it yet.
async fn tunnel() -> Result<Arc<Lazy>> {
    let mut lazy = None;
    for _ in 0..50 {
        lazy = ENDPOINT.lock().unwrap().clone();
        if lazy.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    lazy.context("the forum tunnel isn't running")
}

async fn ask_build(app: &str, into: Option<&std::path::Path>) -> Result<Option<AppUpdate>> {
    // The tunnel starts with the app's service; give it a moment if this ask came first.
    let lazy = tunnel().await?;
    lazy.open.fetch_add(1, Ordering::SeqCst);
    let _busy = Busy(lazy.clone());
    let (which, ep) = lazy.get().await?;
    let linked = tokio::time::timeout(Duration::from_secs(30), async {
        let conn = ep.connect(lazy.addr.clone(), ALPN).await.context("connect")?;
        let (send, recv) = conn.open_bi().await.context("open")?;
        anyhow::Ok((conn, send, recv))
    })
    .await
    .context("the server didn't answer in 30 s")
    .and_then(|r| r);
    let (conn, mut send, recv) = match linked {
        Ok(l) => l,
        Err(e) => {
            // As a pipe does: a dead endpoint is rebuilt for the next ask.
            lazy.broken(PIPES.fetch_add(1, Ordering::Relaxed), which, &format!("update: {e:#}")).await;
            return Err(e);
        }
    };
    send.write_all(BUILD_HANDSHAKE).await?;
    send.write_all(format!("{app}\n").as_bytes()).await?;
    if into.is_some() {
        send.write_all(b"get\n").await?;
    }
    send.finish()?;
    let mut recv = BufReader::new(recv);
    let mut line = String::new();
    tokio::time::timeout(Duration::from_secs(30), (&mut recv).take(64 * 1024).read_line(&mut line))
        .await
        .context("the server didn't answer in 30 s")??;
    let update = parse(&line)?;
    if let (Some(path), Some(u)) = (into, &update) {
        download(&mut recv, u, path).await?;
    }
    conn.close(0u8.into(), b"done");
    Ok(update)
}

/// Sends `lines` (the call's recent log, [`crate::phonelog`]) to the gate, which keeps them under
/// `~/.jarvis/phone-logs/`. The wire, after the `logup` handshake: one line of JSON about the upload
/// (`why`, `sent_ms`), then the lines as UTF-8 text until the stream ends; the gate answers `ok <file>`
/// on one line. Best effort: a failure never touches the endpoint (a live call may be on it).
pub(crate) async fn send_log(why: &str, lines: &str) -> Result<String> {
    let lazy = tunnel().await?;
    lazy.open.fetch_add(1, Ordering::SeqCst);
    let _busy = Busy(lazy.clone());
    let (_, ep) = lazy.get().await?;
    let sent_ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
    let head = serde_json::json!({ "why": why, "sent_ms": sent_ms });
    tokio::time::timeout(Duration::from_secs(60), async {
        let conn = ep.connect(lazy.addr.clone(), ALPN).await.context("connect")?;
        let (mut send, recv) = conn.open_bi().await.context("open")?;
        send.write_all(LOG_HANDSHAKE).await?;
        send.write_all(format!("{head}\n").as_bytes()).await?;
        send.write_all(lines.as_bytes()).await?;
        send.finish()?;
        let mut answer = String::new();
        BufReader::new(recv).take(1024).read_line(&mut answer).await?;
        conn.close(0u8.into(), b"done");
        let answer = answer.trim();
        anyhow::ensure!(answer.starts_with("ok"), "the gate said {answer:?}");
        anyhow::Ok(answer.to_string())
    })
    .await
    .context("the gate didn't take the log in 60 s")?
}

fn parse(line: &str) -> Result<Option<AppUpdate>> {
    let v: serde_json::Value = serde_json::from_str(line.trim()).context("the server's answer")?;
    if v.as_object().is_some_and(|o| o.is_empty()) {
        return Ok(None);
    }
    let s = |k: &str| v[k].as_str().unwrap_or_default().to_string();
    Ok(Some(AppUpdate {
        version_code: v["version_code"].as_i64().context("no version_code")?,
        version_name: s("version_name"),
        size: v["size"].as_u64().context("no size")?,
        sha256: s("sha256"),
        notes: s("notes"),
    }))
}

/// Reads exactly `u.size` bytes into `path`, or fails and leaves nothing there. A minute without a
/// byte is a failure (the phone lost the network).
async fn download(recv: &mut (impl tokio::io::AsyncRead + Unpin), u: &AppUpdate, path: &std::path::Path) -> Result<()> {
    use sha2::{Digest, Sha256};
    let part = path.with_extension("part");
    let mut file = tokio::fs::File::create(&part).await.with_context(|| format!("{}", part.display()))?;
    let res = async {
        let mut hash = Sha256::new();
        let mut buf = vec![0u8; 64 * 1024];
        let mut got = 0u64;
        while got < u.size {
            let want = buf.len().min((u.size - got) as usize);
            let n = tokio::time::timeout(Duration::from_secs(60), recv.read(&mut buf[..want]))
                .await
                .context("the download stalled")??;
            if n == 0 {
                anyhow::bail!("the download ended at {got} of {} bytes", u.size);
            }
            hash.update(&buf[..n]);
            file.write_all(&buf[..n]).await?;
            got += n as u64;
        }
        let sum: String = hash.finalize().iter().map(|b| format!("{b:02x}")).collect();
        anyhow::ensure!(sum == u.sha256, "the download's sha256 doesn't match");
        file.sync_all().await?;
        anyhow::Ok(())
    }
    .await;
    if res.is_err() {
        let _ = tokio::fs::remove_file(&part).await;
        return res;
    }
    tokio::fs::rename(&part, path).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use jarvis_voice::{Call, Events, Status, Timing, websocket};

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

    /// A real call through the gate on dibs: `FORUM_TICKET=… FORUM_SECRET=… cargo test -p jarvis-link-ffi
    /// -- --ignored voice_through_the_gate` with a voice service on 127.0.0.1:8790 (jarvis-voice, or
    /// `cargo run -p jarvis-voice --example fake_server`), and a key the gate lets in.
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

#[cfg(test)]
mod stop_tests {
    use iroh::{EndpointAddr, SecretKey};
    use iroh_tickets::endpoint::EndpointTicket;

    fn up(port: u16) -> bool {
        std::net::TcpStream::connect(("127.0.0.1", port)).is_ok()
    }

    fn wait(port: u16, want: bool) {
        let t = std::time::Instant::now();
        while up(port) != want {
            assert!(t.elapsed().as_secs() < 10, "port {port} never went {want}: {}", super::forum_status());
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
    }

    /// Both ports go and come back, and an ask left over from before the stop can't bind the key again.
    #[test]
    fn stop_frees_the_ports_and_the_key() {
        let gate = SecretKey::generate().public();
        let ticket = EndpointTicket::new(EndpointAddr::new(gate)).to_string();
        let key = SecretKey::generate().to_bytes().iter().map(|b| format!("{b:02x}")).collect::<String>();
        super::forum_start(ticket.clone(), 18183, 18184, key.clone());
        wait(18183, true);
        wait(18184, true);
        while super::ENDPOINT.lock().unwrap().is_none() {
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        let old = super::ENDPOINT.lock().unwrap().clone().unwrap();
        super::forum_stop();
        assert!(!up(18183) && !up(18184), "still listening after the stop");
        assert_eq!(super::forum_status(), "stopped");
        assert!(super::RT.block_on(old.get()).is_err(), "a late ask bound the key again");
        super::forum_start(ticket, 18183, 18184, key);
        wait(18183, true);
        super::forum_stop();
    }
}

#[cfg(test)]
mod update_tests {
    #[test]
    fn parses_the_gates_answer() {
        assert_eq!(super::parse("{}\n").unwrap(), None);
        let u = super::parse(r#"{"app":"com.kivan.jarvis","version_code":230,"version_name":"1.0.230","size":7,"sha256":"ab","file":"230.apk","notes":"For you: x","published_ms":1}"#)
            .unwrap()
            .unwrap();
        assert_eq!((u.version_code, u.size, u.notes.as_str()), (230, 7, "For you: x"));
        assert!(super::parse("nope").is_err());
    }

    #[tokio::test]
    async fn download_checks_size_and_sum() {
        use sha2::{Digest, Sha256};
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("u.apk");
        let sum: String = Sha256::digest(b"apk 230").iter().map(|b| format!("{b:02x}")).collect();
        let u = super::AppUpdate { version_code: 230, version_name: String::new(), size: 7, sha256: sum, notes: String::new() };
        super::download(&mut &b"apk 230"[..], &u, &path).await.unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"apk 230");
        std::fs::remove_file(&path).unwrap();
        // Short, or not the bytes announced: nothing is left behind.
        assert!(super::download(&mut &b"apk"[..], &u, &path).await.is_err());
        assert!(super::download(&mut &b"apk 231"[..], &u, &path).await.is_err());
        assert!(std::fs::read_dir(dir.path()).unwrap().next().is_none());
    }

    /// A real update through a gate: `FORUM_TICKET=… FORUM_SECRET=… BUILD_APP=… BUILD_SHA=… cargo test
    /// -p jarvis-ffi -- --ignored update_through_the_gate`, against a gate (the jarvis repo's
    /// `forum-gate listen`) with a build published for BUILD_APP and a key it lets in.
    #[test]
    #[ignore]
    fn update_through_the_gate() {
        let env = |k: &str| std::env::var(k).unwrap();
        super::forum_start(env("FORUM_TICKET"), 18082, 18792, env("FORUM_SECRET"));
        let rt = tokio::runtime::Runtime::new().unwrap();
        let t = std::time::Instant::now();
        let u = rt.block_on(super::update_check(env("BUILD_APP"))).unwrap().expect("nothing published");
        eprintln!("check {:?}: {u:?}", t.elapsed());
        assert!(rt.block_on(super::update_check("com.example.none".into())).unwrap().is_none());
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("u.apk");
        let t = std::time::Instant::now();
        let got = rt.block_on(super::update_fetch(env("BUILD_APP"), path.to_str().unwrap().into())).unwrap();
        eprintln!("fetch {} bytes in {:?}", got.size, t.elapsed());
        assert_eq!(got, u);
        assert_eq!(got.sha256, env("BUILD_SHA"));
        assert_eq!(std::fs::metadata(&path).unwrap().len(), u.size);
    }
}
