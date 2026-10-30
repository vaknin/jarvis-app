//! The phone's presence (`Presence.kt`): live frames on the reserved channel `_presence` saying
//! the user unlocked or is using the phone, or that its screen went off. dibs reads them from
//! `jarvis-link watch` (`{"type":"presence",…}`) and `jarvis-link status --json` (`phone_presence`), so it
//! knows the user is on the phone even away from home.
//!
//! Frame data: `{"op":"present","why":"unlock"|"use","ts":<phone epoch ms>}` or
//! `{"op":"off","ts":<ms>}`. Malformed data and unknown ops are ignored.

use std::sync::{Arc, Mutex};

use serde::Serialize;
use serde_json::{Value, json};
use jarvis_core::node::{Event, Node};
use tokio::sync::broadcast::{self, error::RecvError};
use tracing::debug;

/// The live channel the phone app reports presence on.
pub const CHANNEL: &str = "_presence";

/// One presence frame, as the UIs see it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Presence {
    /// `present` or `off`.
    pub op: &'static str,
    /// `unlock` or `use` for `present`; none for `off` (or a reason this daemon doesn't know).
    pub why: Option<&'static str>,
    /// When the phone saw it (its clock, ms since the epoch).
    pub ts_ms: i64,
    /// When this daemon got it (ms since the epoch).
    pub got_ms: i64,
}

impl Presence {
    /// Reads a frame's data; none when it is malformed or its op is unknown.
    pub fn parse(data: &str, got_ms: i64) -> Option<Self> {
        let v: Value = serde_json::from_str(data).ok()?;
        let ts_ms = v.get("ts")?.as_i64()?;
        let (op, why) = match v.get("op")?.as_str()? {
            "present" => {
                let why = match v.get("why").and_then(Value::as_str) {
                    Some("unlock") => Some("unlock"),
                    Some("use") => Some("use"),
                    _ => None,
                };
                ("present", why)
            }
            "off" => ("off", None),
            _ => return None,
        };
        Some(Self { op, why, ts_ms, got_ms })
    }

    /// `phone_presence` in `status --json`.
    pub fn status_json(&self) -> Value {
        json!({ "op": self.op, "why": self.why, "ts_ms": self.ts_ms, "got_ms": self.got_ms })
    }

    /// A `jarvis-link watch` line.
    pub fn watch_json(&self) -> Value {
        let mut v = self.status_json();
        v["type"] = "presence".into();
        v
    }
}

/// The newest presence since the daemon started, and each new frame for the watchers.
#[derive(Clone)]
pub struct Tracker {
    latest: Arc<Mutex<Option<Presence>>>,
    tx: broadcast::Sender<Presence>,
}

impl Default for Tracker {
    fn default() -> Self {
        Self { latest: Arc::default(), tx: broadcast::channel(16).0 }
    }
}

impl Tracker {
    pub fn latest(&self) -> Option<Presence> {
        self.latest.lock().unwrap().clone()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Presence> {
        self.tx.subscribe()
    }

    /// Takes one frame: kept when it is the newest by the phone's clock (a frame that took longer
    /// on the way doesn't undo a later one), and passed to the watchers either way.
    pub fn take(&self, p: Presence) {
        {
            let mut latest = self.latest.lock().unwrap();
            if latest.as_ref().is_none_or(|l| p.ts_ms >= l.ts_ms) {
                *latest = Some(p.clone());
            }
        }
        let _ = self.tx.send(p);
    }
}

/// Feeds the tracker from the phone's `_presence` frames until the node stops.
pub async fn run(node: Node, tracker: Tracker) {
    let mut rx = node.events();
    loop {
        match rx.recv().await {
            Ok(Event::App { id: None, channel, data, from_me: false, .. }) if channel == CHANNEL => {
                match Presence::parse(&data, jarvis_core::store::now_ms()) {
                    Some(p) => tracker.take(p),
                    None => debug!("presence: ignored {data:?}"),
                }
            }
            Ok(_) => {}
            Err(RecvError::Lagged(n)) => debug!("presence lagged by {n} events"),
            Err(RecvError::Closed) => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_parse() {
        let p = Presence::parse(r#"{"op":"present","why":"unlock","ts":1700000000000}"#, 5).unwrap();
        assert_eq!(p, Presence { op: "present", why: Some("unlock"), ts_ms: 1_700_000_000_000, got_ms: 5 });
        let p = Presence::parse(r#"{"op":"present","why":"use","ts":7}"#, 9).unwrap();
        assert_eq!((p.op, p.why), ("present", Some("use")));
        let p = Presence::parse(r#"{"op":"off","ts":7}"#, 9).unwrap();
        assert_eq!((p.op, p.why), ("off", None));
        // A reason this daemon doesn't know still counts as present.
        assert_eq!(Presence::parse(r#"{"op":"present","why":"new","ts":7}"#, 9).unwrap().why, None);
    }

    #[test]
    fn malformed_frames_and_unknown_ops_are_ignored() {
        for bad in [
            "",
            "nope",
            "[1]",
            r#"{"op":"present"}"#,
            r#"{"op":"present","ts":"7"}"#,
            r#"{"ts":7}"#,
            r#"{"op":"dance","ts":7}"#,
            r#"{"op":1,"ts":7}"#,
        ] {
            assert_eq!(Presence::parse(bad, 1), None, "{bad}");
        }
    }

    #[test]
    fn json_shapes() {
        let p = Presence { op: "present", why: Some("unlock"), ts_ms: 10, got_ms: 12 };
        assert_eq!(p.watch_json(), json!({"type":"presence","op":"present","why":"unlock","ts_ms":10,"got_ms":12}));
        let off = Presence { op: "off", why: None, ts_ms: 20, got_ms: 21 };
        assert_eq!(off.status_json(), json!({"op":"off","why":null,"ts_ms":20,"got_ms":21}));
    }

    #[test]
    fn the_newest_by_the_phones_clock_is_kept() {
        let t = Tracker::default();
        let mut rx = t.subscribe();
        assert_eq!(t.latest(), None);
        let unlock = Presence { op: "present", why: Some("unlock"), ts_ms: 100, got_ms: 1 };
        let off = Presence { op: "off", why: None, ts_ms: 200, got_ms: 2 };
        t.take(unlock.clone());
        t.take(off.clone());
        // A late frame from before the screen went off goes to watchers but isn't the newest.
        t.take(Presence { op: "present", why: Some("use"), ts_ms: 150, got_ms: 3 });
        assert_eq!(t.latest(), Some(off.clone()));
        assert_eq!(rx.try_recv().unwrap(), unlock);
        assert_eq!(rx.try_recv().unwrap(), off);
        assert_eq!(rx.try_recv().unwrap().ts_ms, 150);
    }

    /// The phone's live frame over a real (loopback) link reaches the tracker; other channels and
    /// garbage don't.
    #[tokio::test]
    async fn a_phone_frame_reaches_the_tracker() {
        use std::time::Duration;
        use jarvis_core::node::{Config, Net};
        let d = tempfile::tempdir().unwrap();
        let start = |name: &str| {
            let mut cfg = Config::new(d.path().join(name).join("state"), d.path().join(name).join("dl"), name);
            cfg.net = Net::Loopback;
            Node::start(cfg)
        };
        let (laptop, phone) = (start("laptop").await.unwrap(), start("phone").await.unwrap());
        laptop.add_hint(phone.addr());
        phone.add_hint(laptop.addr());
        phone.pair(&laptop.pair_offer()).await.unwrap();
        phone.connect().await.unwrap();

        let t = Tracker::default();
        let mut rx = t.subscribe();
        tokio::spawn(run(laptop.clone(), t.clone()));
        tokio::task::yield_now().await;
        assert!(phone.send_app_live("teen", r#"{"op":"off","ts":1}"#.into()));
        assert!(phone.send_app_live(CHANNEL, "garbage".into()));
        assert!(phone.send_app_live(CHANNEL, r#"{"op":"present","why":"unlock","ts":42}"#.into()));
        let got = tokio::time::timeout(Duration::from_secs(15), rx.recv()).await.unwrap().unwrap();
        assert_eq!((got.op, got.why, got.ts_ms), ("present", Some("unlock"), 42));
        assert!(got.got_ms > 0);
        assert_eq!(t.latest(), Some(got));
    }
}
