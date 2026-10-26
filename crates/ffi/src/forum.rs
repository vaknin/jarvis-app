//! The forum tunnel: `127.0.0.1:PORT` on the phone, over iroh to the forum's gate on Jarvis's
//! server (the jarvis repo's `forum/phone/tunnel`, `forum-gate listen`). It speaks dumbpipe's
//! protocol: one iroh connection per TCP connection, ALPN `DUMBPIPEV0`, and the dialer writes
//! `hello` first. The gate lets in only device ids it knows, so the phone dials with its own fixed
//! key. Chrome counts `http://localhost` as secure, so the forum installs as an app with push.
//!
//! Battery: only the local TCP listener is always there. The iroh endpoint (and with it the relay
//! link, which pings every 15 s) is made when the browser first connects and closed after
//! [`IDLE`] with no connection open. Its own endpoint, apart from the Tether node's.

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
/// The endpoint closes this long after the last forum connection ended.
const IDLE: Duration = Duration::from_secs(180);

static STATUS: Mutex<String> = Mutex::new(String::new());
static STARTED: Mutex<bool> = Mutex::new(false);

fn set_status(s: impl Into<String>) {
    *STATUS.lock().unwrap() = s.into();
}

/// Starts the tunnel once per process; later calls do nothing. A failure (the port taken, a bad
/// ticket) is retried every few seconds for as long as the process lives.
#[uniffi::export]
pub fn forum_start(ticket: String, port: u16, secret: String) {
    let mut started = STARTED.lock().unwrap();
    if *started {
        return;
    }
    *started = true;
    set_status("starting");
    RT.spawn(async move {
        loop {
            if let Err(e) = serve(&ticket, port, &secret).await {
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

async fn serve(ticket: &str, port: u16, secret: &str) -> Result<()> {
    let ticket: EndpointTicket = ticket.trim().parse().context("bad ticket")?;
    let addr = ticket.endpoint_addr().clone();
    let key: SecretKey = secret.trim().parse().context("bad key")?;
    let listener = TcpListener::bind(("127.0.0.1", port)).await.with_context(|| format!("port {port}"))?;
    let lazy = Arc::new(Lazy { key, ep: Default::default(), open: AtomicUsize::new(0), last: Mutex::new(Instant::now()) });
    let reaper = tokio::spawn(lazy.clone().reap());
    set_status("idle");
    let res = async {
        loop {
            let (tcp, _) = listener.accept().await?;
            let (lazy, addr) = (lazy.clone(), addr.clone());
            lazy.open.fetch_add(1, Ordering::SeqCst);
            tokio::spawn(async move {
                let res = async { pipe(tcp, &lazy.get().await?, addr).await }.await;
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

/// One browser connection, both ways, until both sides are done.
async fn pipe(tcp: TcpStream, endpoint: &Endpoint, addr: EndpointAddr) -> Result<()> {
    let conn = endpoint.connect(addr, ALPN).await.context("connect")?;
    let (mut send, mut recv) = conn.open_bi().await.context("open")?;
    send.write_all(HANDSHAKE).await?;
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
