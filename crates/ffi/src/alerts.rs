//! Jarvis's notifications for Aviv: the forum gate dials this phone with each one (the jarvis repo's
//! `forum/phone/tunnel/src/alerts.rs`, which has the why and the wire format) and the app shows it.
//!
//! The phone's side is the forum tunnel's endpoint ([`crate::forum`]), which accepts [`ALERT_ALPN`]
//! from the gate's id only. [`alerts_start`] keeps that endpoint up for good, instead of closing it
//! when the forum is idle: an endpoint with no connection costs only iroh's relay ping (every 15 s,
//! hard-coded), and no QUIC keep-alives, since no connection stays open. Each alert is a connection
//! that lasts a second: one JSON line in, the app shows it, its id goes back. One it didn't show gets
//! a line saying why instead, so the sender can tell Aviv (thread #179): an empty answer told no one.

use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result};
use iroh::{Endpoint, EndpointId, endpoint::Incoming};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

use crate::RT;

pub(crate) const ALERT_ALPN: &[u8] = b"jarvis-alert/0";
/// The longest alert line read; the gate sends at most 4000 characters of text.
const MAX_LINE: u64 = 32 * 1024;
/// The longest reason sent back; the gate reads a short line.
const MAX_WHY: usize = 120;
/// How often the keeper checks that the endpoint is up (a local look, no network).
const KEEP_EVERY: Duration = Duration::from_secs(20);

/// The app's side: shows one notification. None once it is on screen, else why not, in a few words
/// ("app notifications off"). `url` is a forum path for the tap to open, or empty.
#[uniffi::export(with_foreign)]
pub trait AlertListener: Send + Sync {
    fn show(&self, id: u64, title: String, text: String, url: String) -> Option<String>;
}

static LISTENER: Mutex<Option<Arc<dyn AlertListener>>> = Mutex::new(None);

/// Starts taking Jarvis's notifications, shown through `listener`; a second call only swaps the
/// listener. Needs [`crate::forum::forum_start`].
#[uniffi::export]
pub fn alerts_start(listener: Arc<dyn AlertListener>) {
    if LISTENER.lock().unwrap().replace(listener).is_some() {
        return;
    }
    RT.spawn(async {
        // Both start with the process, and forum_start's endpoint comes a moment later.
        while !crate::forum::keep_up().await {
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        loop {
            tokio::time::sleep(KEEP_EVERY).await;
            crate::forum::keep_up().await;
        }
    });
}

/// Whether the endpoint should accept alerts at all.
pub(crate) fn wanted() -> bool {
    LISTENER.lock().unwrap().is_some()
}

/// Takes the alerts that come to `ep` until it closes. Only `gate` gets in.
pub(crate) async fn accept(ep: Endpoint, gate: EndpointId) {
    while let Some(incoming) = ep.accept().await {
        tokio::spawn(async move {
            if let Err(e) = take(incoming, gate).await {
                tracing::warn!("alert: {e:#}");
            }
        });
    }
}

async fn take(incoming: Incoming, gate: EndpointId) -> Result<()> {
    let conn = incoming.accept()?.await.context("accept")?;
    if conn.remote_id() != gate || conn.alpn() != ALERT_ALPN {
        conn.close(1u8.into(), b"not allowed");
        anyhow::bail!("refused {}", conn.remote_id().fmt_short());
    }
    let (mut send, recv) = tokio::time::timeout(Duration::from_secs(10), conn.accept_bi()).await.context("no stream")??;
    let mut line = String::new();
    tokio::time::timeout(Duration::from_secs(10), BufReader::new(recv).take(MAX_LINE).read_line(&mut line))
        .await
        .context("no alert")??;
    let v: serde_json::Value = serde_json::from_str(line.trim()).context("bad alert")?;
    let id = v["id"].as_u64().context("no id")?;
    let s = |k: &str| v[k].as_str().unwrap_or_default().to_string();
    let (title, text, url) = (s("title"), s("text"), s("url"));
    let listener = LISTENER.lock().unwrap().clone();
    let why = match listener {
        // The endpoint is up but the app never said who shows these: the gate hears that too.
        None => Some("no listener".to_string()),
        // A join error is the app's callback panicking through uniffi, past Alerts.show's own catch.
        Some(l) => tokio::task::spawn_blocking(move || l.show(id, title, text, url)).await.unwrap_or_else(|e| Some(format!("threw: {e}"))),
    };
    match &why {
        None => {
            tracing::info!("alert {id}: shown");
            send.write_all(format!("{id}\n").as_bytes()).await?;
        }
        Some(w) => {
            tracing::info!("alert {id}: not shown: {w}");
            send.write_all(format!("{}\n", one_line(w)).as_bytes()).await?;
        }
    }
    send.finish()?;
    // The gate closes once it has the answer.
    let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
    Ok(())
}

/// A reason as one short line, never empty: the gate reads a line, and an empty one is an old phone's.
fn one_line(why: &str) -> String {
    let line: String = why.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(MAX_WHY).collect();
    if line.is_empty() { "no reason".to_string() } else { line }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// What it was shown, and what it says when it won't show one.
    #[derive(Default)]
    struct Shown(Mutex<Vec<(u64, String, String)>>, Mutex<Option<String>>);

    impl AlertListener for Shown {
        fn show(&self, id: u64, title: String, text: String, url: String) -> Option<String> {
            self.0.lock().unwrap().push((id, title, format!("{text}{url}")));
            let why = self.1.lock().unwrap().clone();
            if why.as_deref() == Some("panic") {
                panic!("Callback interface failure");
            }
            why
        }
    }

    async fn endpoint(alpns: Vec<Vec<u8>>) -> Endpoint {
        Endpoint::builder(iroh::endpoint::presets::Minimal)
            .relay_mode(iroh::RelayMode::Disabled)
            .clear_ip_transports()
            .bind_addr("127.0.0.1:0")
            .unwrap()
            .alpns(alpns)
            .bind()
            .await
            .unwrap()
    }

    /// What the gate does: one line out, the answer back.
    async fn send(from: &Endpoint, to: &Endpoint, line: &str) -> Result<String> {
        let conn = from.connect(to.addr(), ALERT_ALPN).await?;
        let (mut s, r) = conn.open_bi().await?;
        s.write_all(line.as_bytes()).await?;
        s.finish()?;
        let mut answer = String::new();
        BufReader::new(r).read_line(&mut answer).await?;
        conn.close(0u8.into(), b"done");
        Ok(answer)
    }

    #[tokio::test]
    async fn shows_what_the_gate_sends_and_says_so() {
        let shown = Arc::new(Shown::default());
        *LISTENER.lock().unwrap() = Some(shown.clone());
        let (phone, gate, stranger) = (endpoint(vec![ALERT_ALPN.to_vec()]).await, endpoint(vec![]).await, endpoint(vec![]).await);
        tokio::spawn(accept(phone.clone(), gate.id()));

        let answer = send(&gate, &phone, "{\"id\":42,\"ts_ms\":1,\"title\":\"Job done\",\"text\":\"tether notify \",\"url\":\"/t/42\"}\n").await.unwrap();
        assert_eq!(answer, "42\n");
        assert_eq!(shown.0.lock().unwrap().as_slice(), [(42, "Job done".to_string(), "tether notify /t/42".to_string())]);

        // Not shown (notifications off): the reason instead of the id, so the sender can say it.
        *shown.1.lock().unwrap() = Some("Jarvis channel off".to_string());
        assert_eq!(send(&gate, &phone, "{\"id\":43,\"title\":\"\",\"text\":\"x\"}\n").await.unwrap(), "Jarvis channel off\n");

        // A callback that panics through uniffi still answers.
        *shown.1.lock().unwrap() = Some("panic".to_string());
        let answer = send(&gate, &phone, "{\"id\":45,\"title\":\"\",\"text\":\"x\"}\n").await.unwrap();
        assert!(answer.starts_with("threw: ") && answer.ends_with('\n') && answer.lines().count() == 1, "{answer:?}");

        // Anyone but the gate is closed before a byte is read.
        assert!(send(&stranger, &phone, "{\"id\":44,\"title\":\"\",\"text\":\"spoof\"}\n").await.is_err());
        assert_eq!(shown.0.lock().unwrap().len(), 3);

        // The tunnel up with nobody to show it: said, not a dropped connection.
        *LISTENER.lock().unwrap() = None;
        assert_eq!(send(&gate, &phone, "{\"id\":46,\"title\":\"\",\"text\":\"x\"}\n").await.unwrap(), "no listener\n");
    }

    #[test]
    fn a_reason_is_one_short_line() {
        assert_eq!(one_line("threw: a\nb  c"), "threw: a b c");
        assert_eq!(one_line("  \n"), "no reason");
        assert_eq!(one_line(&"x".repeat(500)).len(), MAX_WHY);
    }
}
