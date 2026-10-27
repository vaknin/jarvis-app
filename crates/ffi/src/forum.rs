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

use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::{Context, Result};
use iroh::{Endpoint, EndpointAddr, SecretKey, endpoint::presets};
use iroh_tickets::endpoint::EndpointTicket;
use tokio::{
    io::{AsyncWriteExt, copy},
    net::{TcpListener, TcpStream},
};

use crate::RT;

const ALPN: &[u8] = b"DUMBPIPEV0";
const HANDSHAKE: &[u8; 5] = b"hello";
const VOICE_HANDSHAKE: &[u8; 5] = b"voice";
/// The endpoint closes this long after the last forum connection ended.
const IDLE: Duration = Duration::from_secs(180);

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
        if let Some(ep) = ep {
            ep.network_change().await;
        }
    });
}

/// The iroh endpoint, made on first use and closed when idle.
struct Lazy {
    key: SecretKey,
    ep: tokio::sync::Mutex<Option<Endpoint>>,
    open: AtomicUsize,
    last: Mutex<Instant>,
}

impl Lazy {
    async fn get(&self) -> Result<Endpoint> {
        let mut ep = self.ep.lock().await;
        if let Some(e) = ep.as_ref() {
            return Ok(e.clone());
        }
        let e = Endpoint::builder(presets::N0).secret_key(self.key.clone()).bind().await.context("iroh")?;
        *ep = Some(e.clone());
        Ok(e)
    }

    async fn reap(self: Arc<Self>) {
        loop {
            tokio::time::sleep(Duration::from_secs(30)).await;
            let idle = self.open.load(Ordering::SeqCst) == 0 && self.last.lock().unwrap().elapsed() > IDLE;
            if idle && let Some(e) = self.ep.lock().await.take() {
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
    let lazy = Arc::new(Lazy { key, ep: Default::default(), open: AtomicUsize::new(0), last: Mutex::new(Instant::now()) });
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
            tokio::spawn(async move {
                let res = async { pipe(tcp, &lazy.get().await?, addr, handshake).await }.await;
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

/// One browser connection (or call), both ways, until both sides are done.
async fn pipe(tcp: TcpStream, endpoint: &Endpoint, addr: EndpointAddr, handshake: &[u8; 5]) -> Result<()> {
    let conn = endpoint.connect(addr, ALPN).await.context("connect")?;
    let (mut send, mut recv) = conn.open_bi().await.context("open")?;
    send.write_all(handshake).await?;
    let (mut r, mut w) = tcp.into_split();
    let up = async {
        copy(&mut r, &mut send).await?;
        send.finish()?;
        let _ = send.stopped().await;
        anyhow::Ok(())
    };
    let down = async {
        copy(&mut recv, &mut w).await?;
        w.shutdown().await?;
        anyhow::Ok(())
    };
    tokio::try_join!(up, down)?;
    Ok(())
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
        eprintln!("log: {:?}", log.0.lock().unwrap());
        assert!(heard > 0, "no audio came back");
        call.hangup();
        while !has("Ended") {
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
