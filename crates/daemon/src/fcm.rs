//! Waking the phone through FCM (HTTP v1). The message is content-free (`{"t":"wake"}`): the phone
//! starts its endpoint, dials us and takes the queue over the encrypted link. Google learns only
//! that a wake happened. Without a key, or when FCM fails, the node's backoff redial still delivers.

use std::{
    future::Future,
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::STANDARD, engine::general_purpose::URL_SAFE_NO_PAD};
use ring::{
    rand::SystemRandom,
    signature::{RSA_PKCS1_SHA256, RsaKeyPair},
};
use serde::Deserialize;
use serde_json::{Value, json};
use jarvis_core::{
    node::{Event, Node},
    store::State,
};
use tokio::sync::{
    broadcast::{self, error::RecvError},
    mpsc,
};
use tracing::{debug, info, warn};

const SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
/// At most one wake per this long; items queued meanwhile ride on the same wake.
pub const WAKE_GAP: Duration = Duration::from_secs(30);
/// A wake with no link this long after it counts as unanswered (FCM accepted it, the phone never
/// dialed: e.g. its DNS is broken, so Google can't reach it).
pub const ANSWER_WAIT: Duration = Duration::from_secs(30);

/// Set while the last wake went unanswered; cleared by the next link. Shown in `status`.
pub type Unanswered = Arc<AtomicBool>;

#[derive(Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
    token_uri: String,
}

#[derive(Debug)]
pub enum WakeError {
    /// FCM no longer knows the token (app uninstalled or token rotated).
    Unregistered,
    Other(anyhow::Error),
}

impl From<anyhow::Error> for WakeError {
    fn from(e: anyhow::Error) -> Self {
        Self::Other(e)
    }
}

pub trait Waker: Send + Sync + 'static {
    fn wake(&self, token: &str) -> impl Future<Output = Result<(), WakeError>> + Send;
}

pub struct Fcm {
    sa: ServiceAccount,
    key: RsaKeyPair,
    http: reqwest::Client,
    access: tokio::sync::Mutex<Option<(String, Instant)>>,
}

impl Fcm {
    pub fn load(path: &Path) -> Result<Self> {
        let raw = std::fs::read(path).with_context(|| format!("read {}", path.display()))?;
        let sa: ServiceAccount = serde_json::from_slice(&raw).context("parse service-account JSON")?;
        let key = rsa_key(&sa.private_key)?;
        // iroh brings rustls with ring but installs no process default; reqwest needs one.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let http = reqwest::Client::builder().timeout(Duration::from_secs(20)).build()?;
        Ok(Self { sa, key, http, access: Default::default() })
    }

    /// An OAuth access token, cached until a minute before it expires.
    async fn access_token(&self) -> Result<String> {
        let mut cached = self.access.lock().await;
        if let Some((tok, until)) = cached.as_ref()
            && Instant::now() < *until
        {
            return Ok(tok.clone());
        }
        let jwt = jwt(&self.sa, &self.key, unix_now())?;
        let res = self
            .http
            .post(&self.sa.token_uri)
            .form(&[("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"), ("assertion", &jwt)])
            .send()
            .await
            .context("token exchange")?;
        let status = res.status();
        let body: Value = res.json().await.context("token response")?;
        if !status.is_success() {
            bail!("token exchange: {status}: {}", body["error_description"].as_str().unwrap_or(""));
        }
        let tok = body["access_token"].as_str().context("no access_token")?.to_string();
        let ttl = body["expires_in"].as_u64().unwrap_or(3600).saturating_sub(60);
        *cached = Some((tok.clone(), Instant::now() + Duration::from_secs(ttl)));
        Ok(tok)
    }
}

impl Waker for Fcm {
    async fn wake(&self, token: &str) -> Result<(), WakeError> {
        let url = format!("https://fcm.googleapis.com/v1/projects/{}/messages:send", self.sa.project_id);
        let res = self
            .http
            .post(url)
            .bearer_auth(self.access_token().await?)
            .json(&message(token))
            .send()
            .await
            .context("FCM send")?;
        let status = res.status();
        if status.is_success() {
            return Ok(());
        }
        if status == reqwest::StatusCode::UNAUTHORIZED {
            *self.access.lock().await = None;
        }
        let body: Value = res.json().await.unwrap_or_default();
        Err(classify(status.as_u16(), &body))
    }
}

/// The signed assertion exchanged for an access token (RFC 7523).
fn jwt(sa: &ServiceAccount, key: &RsaKeyPair, now: u64) -> Result<String> {
    let header = URL_SAFE_NO_PAD.encode(br#"{"alg":"RS256","typ":"JWT"}"#);
    let claims = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims(sa, now))?);
    let input = format!("{header}.{claims}");
    let mut sig = vec![0; key.public().modulus_len()];
    key.sign(&RSA_PKCS1_SHA256, &SystemRandom::new(), input.as_bytes(), &mut sig)
        .map_err(|_| anyhow::anyhow!("RSA signing failed"))?;
    Ok(format!("{input}.{}", URL_SAFE_NO_PAD.encode(sig)))
}

fn claims(sa: &ServiceAccount, now: u64) -> Value {
    json!({
        "iss": sa.client_email,
        "scope": SCOPE,
        "aud": sa.token_uri,
        "iat": now,
        "exp": now + 3600,
    })
}

/// No notification block and no content. `collapse_key` folds wakes that pile up while the phone
/// is offline into one.
fn message(token: &str) -> Value {
    json!({
        "message": {
            "token": token,
            "data": { "t": "wake" },
            "android": { "priority": "high", "collapse_key": "wake" },
        }
    })
}

fn classify(status: u16, body: &Value) -> WakeError {
    let code = body["error"]["details"]
        .as_array()
        .into_iter()
        .flatten()
        .find_map(|d| d["errorCode"].as_str());
    if status == 404 || code == Some("UNREGISTERED") {
        return WakeError::Unregistered;
    }
    let msg = body["error"]["message"].as_str().unwrap_or("");
    WakeError::Other(anyhow::anyhow!("FCM {status} {}: {msg}", code.unwrap_or("")))
}

fn rsa_key(pem: &str) -> Result<RsaKeyPair> {
    let b64: String = pem.lines().filter(|l| !l.starts_with("-----")).collect();
    let der = STANDARD.decode(b64.trim()).context("private_key is not PEM")?;
    RsaKeyPair::from_pkcs8(&der).map_err(|e| anyhow::anyhow!("private_key: {e}"))
}

fn unix_now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// Whether a queued item is worth waking the phone for. A view only when it carries `notify`: an
/// app republishes its view on every small change, and the rest can wait for the next link.
fn wakes(view: bool, data: Option<&str>) -> bool {
    !view
        || data
            .and_then(|d| serde_json::from_str::<Value>(d).ok())
            .is_some_and(|v| v.get("notify").is_some_and(|n| !n.is_null()))
}

/// What the wake loop needs from the node.
pub trait Phone: Send + 'static {
    /// Queued items worth a wake (see [`wakes`]).
    fn connected(&self) -> bool;
    fn queued(&self) -> usize;
    fn token(&self) -> Option<String>;
    fn drop_token(&self);
}

impl Phone for Node {
    fn connected(&self) -> bool {
        self.is_connected()
    }
    fn queued(&self) -> usize {
        self.outbox().map_or(0, |o| o.iter().filter(|m| wakes(m.kind == "view", m.text.as_deref())).count())
    }
    fn token(&self) -> Option<String> {
        self.push_token().ok().flatten()
    }
    fn drop_token(&self) {
        if let Err(e) = self.clear_push_token() {
            warn!("dropping push token: {e:#}");
        }
    }
}

/// Wakes the phone when something is queued for it, or a caller `asked` (fresh notifications), and
/// it isn't connected: right away, or once `gap` has passed since the last wake. Also once at start
/// if the outbox isn't empty. A wake with no link within [`ANSWER_WAIT`] sets `unanswered`.
pub async fn run(
    phone: impl Phone,
    waker: impl Waker,
    mut rx: broadcast::Receiver<Event>,
    mut asked: mpsc::UnboundedReceiver<()>,
    gap: Duration,
    unanswered: Unanswered,
) {
    let mut last: Option<tokio::time::Instant> = None;
    let mut due = (phone.queued() > 0).then(tokio::time::Instant::now);
    let mut wanted = false;
    let mut answer_by: Option<tokio::time::Instant> = None;
    loop {
        let timer = async {
            match due {
                Some(t) => tokio::time::sleep_until(t).await,
                None => std::future::pending().await,
            }
        };
        let answer = async {
            match answer_by {
                Some(t) => tokio::time::sleep_until(t).await,
                None => std::future::pending().await,
            }
        };
        let schedule = |due: Option<tokio::time::Instant>| {
            let now = tokio::time::Instant::now();
            let at = last.map_or(now, |l| (l + gap).max(now));
            Some(due.map_or(at, |d| d.min(at)))
        };
        tokio::select! {
            ev = rx.recv() => match ev {
                Ok(Event::Connected) => {
                    answer_by = None;
                    unanswered.store(false, Ordering::Relaxed);
                }
                Ok(Event::Message(m)) if m.from_me && m.state == State::Queued && !phone.connected() => {
                    due = schedule(due);
                }
                Ok(Event::App { id: Some(_), from_me: true, view, data, .. })
                    if !phone.connected() && wakes(view, Some(&data)) =>
                {
                    due = schedule(due);
                }
                Ok(_) | Err(RecvError::Lagged(_)) => {}
                Err(RecvError::Closed) => return,
            },
            Some(()) = asked.recv() => {
                if !phone.connected() {
                    wanted = true;
                    due = schedule(due);
                }
            }
            () = answer => {
                answer_by = None;
                if !phone.connected() {
                    warn!("the phone didn't answer the FCM wake (no link after {}s)", ANSWER_WAIT.as_secs());
                    unanswered.store(true, Ordering::Relaxed);
                }
            }
            () = timer => {
                due = None;
                let want = std::mem::take(&mut wanted);
                if phone.connected() || (phone.queued() == 0 && !want) {
                    continue;
                }
                let Some(token) = phone.token() else {
                    debug!("no push token yet; relying on redial");
                    continue;
                };
                last = Some(tokio::time::Instant::now());
                match waker.wake(&token).await {
                    Ok(()) => {
                        info!("sent FCM wake");
                        answer_by = Some(tokio::time::Instant::now() + ANSWER_WAIT);
                    }
                    Err(WakeError::Unregistered) => {
                        warn!("FCM says the token is unregistered; dropping it");
                        phone.drop_token();
                    }
                    Err(WakeError::Other(e)) => warn!("FCM wake failed, relying on redial: {e:#}"),
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use jarvis_core::store::Message;
    use uuid::Uuid;

    use super::*;

    #[derive(Default)]
    struct St {
        connected: bool,
        queued: usize,
        token: Option<String>,
    }

    #[derive(Clone, Default)]
    struct FakePhone(Arc<Mutex<St>>);

    impl Phone for FakePhone {
        fn connected(&self) -> bool {
            self.0.lock().unwrap().connected
        }
        fn queued(&self) -> usize {
            self.0.lock().unwrap().queued
        }
        fn token(&self) -> Option<String> {
            self.0.lock().unwrap().token.clone()
        }
        fn drop_token(&self) {
            self.0.lock().unwrap().token = None;
        }
    }

    #[derive(Clone, Default)]
    struct FakeWaker {
        calls: Arc<Mutex<Vec<String>>>,
        unregistered: bool,
    }

    impl Waker for FakeWaker {
        async fn wake(&self, token: &str) -> Result<(), WakeError> {
            self.calls.lock().unwrap().push(token.into());
            if self.unregistered { Err(WakeError::Unregistered) } else { Ok(()) }
        }
    }

    fn queued() -> Event {
        Event::Message(Message {
            id: Uuid::new_v4(),
            seq: 0,
            from_me: true,
            ts_ms: 0,
            expires_ms: None,
            kind: "text",
            text: Some("hi".into()),
            file_name: None,
            file_size: None,
            sha256: None,
            path: None,
            state: State::Queued,
            read: false,
            channel: None,
        })
    }

    fn app_queued(data: &str, view: bool) -> Event {
        Event::App { id: Some(Uuid::new_v4()), channel: "teen".into(), data: data.into(), from_me: true, view }
    }

    struct Rig {
        phone: FakePhone,
        waker: FakeWaker,
        tx: broadcast::Sender<Event>,
        ask: mpsc::UnboundedSender<()>,
        unanswered: Unanswered,
    }

    impl Rig {
        fn new(queued: usize, unregistered: bool) -> Self {
            let phone = FakePhone::default();
            *phone.0.lock().unwrap() = St { connected: false, queued, token: Some("tok".into()) };
            let waker = FakeWaker { unregistered, ..Default::default() };
            let (tx, rx) = broadcast::channel(16);
            let (ask, asked) = mpsc::unbounded_channel();
            let unanswered = Unanswered::default();
            tokio::spawn(run(phone.clone(), waker.clone(), rx, asked, WAKE_GAP, unanswered.clone()));
            Self { phone, waker, tx, ask, unanswered }
        }
        fn queue(&self) {
            self.phone.0.lock().unwrap().queued += 1;
            self.tx.send(queued()).unwrap();
        }
        fn calls(&self) -> usize {
            self.waker.calls.lock().unwrap().len()
        }
        fn unanswered(&self) -> bool {
            self.unanswered.load(Ordering::Relaxed)
        }
    }

    async fn advance(secs: u64) {
        tokio::time::sleep(Duration::from_secs(secs)).await;
    }

    #[tokio::test(start_paused = true)]
    async fn wakes_on_queue_and_debounces() {
        let r = Rig::new(0, false);
        advance(1).await;
        assert_eq!(r.calls(), 0, "nothing queued, no wake");
        r.queue();
        advance(1).await;
        assert_eq!(r.calls(), 1);
        r.queue();
        r.queue();
        advance(10).await;
        assert_eq!(r.calls(), 1, "within the gap");
        advance(25).await;
        assert_eq!(r.calls(), 2, "one deferred wake for both items");
        advance(120).await;
        assert_eq!(r.calls(), 2, "no repeats without new items");
    }

    #[tokio::test(start_paused = true)]
    async fn an_app_item_wakes_too() {
        let r = Rig::new(0, false);
        r.phone.0.lock().unwrap().queued += 1;
        r.tx.send(app_queued(r#"{"post":{"text":"hi"}}"#, false)).unwrap();
        advance(1).await;
        assert_eq!(r.calls(), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn a_view_wakes_only_with_notify() {
        let r = Rig::new(0, false);
        r.tx.send(app_queued(r#"{"v":1,"blocks":[]}"#, true)).unwrap();
        advance(60).await;
        assert_eq!(r.calls(), 0, "a silent view waits for the next link");
        r.phone.0.lock().unwrap().queued += 1;
        r.tx.send(app_queued(r#"{"v":1,"blocks":[],"notify":{"title":"t","text":"x"}}"#, true)).unwrap();
        advance(1).await;
        assert_eq!(r.calls(), 1);
    }

    #[test]
    fn which_items_wake() {
        assert!(wakes(false, Some("not json")));
        assert!(wakes(false, None));
        assert!(!wakes(true, Some(r#"{"v":1}"#)));
        assert!(!wakes(true, Some(r#"{"v":1,"notify":null}"#)));
        assert!(!wakes(true, Some(r#"{"blocks":[{"notify":1}]}"#)), "top level only");
        assert!(wakes(true, Some(r#"{"notify":{"title":"t"}}"#)));
    }

    #[tokio::test(start_paused = true)]
    async fn wakes_when_asked_with_nothing_queued() {
        let r = Rig::new(0, false);
        r.ask.send(()).unwrap();
        advance(1).await;
        assert_eq!(r.calls(), 1);
        r.ask.send(()).unwrap();
        advance(10).await;
        assert_eq!(r.calls(), 1, "within the gap");
        advance(25).await;
        assert_eq!(r.calls(), 2);
        r.phone.0.lock().unwrap().connected = true;
        r.ask.send(()).unwrap();
        advance(60).await;
        assert_eq!(r.calls(), 2, "connected: nothing to wake");
    }

    #[tokio::test(start_paused = true)]
    async fn flags_a_wake_the_phone_never_answers() {
        let r = Rig::new(1, false);
        advance(1).await;
        assert_eq!(r.calls(), 1);
        advance(25).await;
        assert!(!r.unanswered(), "still within the wait");
        advance(10).await;
        assert!(r.unanswered());
        r.phone.0.lock().unwrap().connected = true;
        r.tx.send(Event::Connected).unwrap();
        advance(1).await;
        assert!(!r.unanswered(), "a link clears it");
    }

    #[tokio::test(start_paused = true)]
    async fn an_answered_wake_is_not_flagged() {
        let r = Rig::new(1, false);
        advance(5).await;
        assert_eq!(r.calls(), 1);
        r.phone.0.lock().unwrap().connected = true;
        r.tx.send(Event::Connected).unwrap();
        advance(1).await;
        r.phone.0.lock().unwrap().connected = false;
        advance(60).await;
        assert!(!r.unanswered());
    }

    #[tokio::test(start_paused = true)]
    async fn wakes_at_start_with_a_backlog() {
        let r = Rig::new(3, false);
        advance(1).await;
        assert_eq!(r.calls(), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn skips_when_connected_or_drained_or_tokenless() {
        let r = Rig::new(0, false);
        r.phone.0.lock().unwrap().connected = true;
        r.queue();
        advance(1).await;
        assert_eq!(r.calls(), 0, "connected");

        r.phone.0.lock().unwrap().connected = false;
        r.queue();
        advance(1).await;
        assert_eq!(r.calls(), 1);

        // Deferred wake, but the phone connected and drained the queue meanwhile.
        r.queue();
        r.phone.0.lock().unwrap().queued = 0;
        advance(40).await;
        assert_eq!(r.calls(), 1, "drained");

        r.phone.0.lock().unwrap().token = None;
        r.queue();
        advance(40).await;
        assert_eq!(r.calls(), 1, "no token");
    }

    #[tokio::test(start_paused = true)]
    async fn unregistered_drops_the_token() {
        let r = Rig::new(1, true);
        advance(1).await;
        assert_eq!(r.calls(), 1);
        assert_eq!(r.phone.token(), None);
        r.queue();
        advance(60).await;
        assert_eq!(r.calls(), 1);
    }

    #[test]
    fn message_has_no_content() {
        let m = message("T");
        assert_eq!(
            m,
            json!({"message": {"token": "T", "data": {"t": "wake"},
                "android": {"priority": "high", "collapse_key": "wake"}}})
        );
    }

    #[test]
    fn jwt_claims_and_signature() {
        let sa = ServiceAccount {
            project_id: "p".into(),
            client_email: "svc@p.iam.gserviceaccount.com".into(),
            private_key: TEST_KEY.into(),
            token_uri: "https://oauth2.googleapis.com/token".into(),
        };
        let key = rsa_key(&sa.private_key).unwrap();
        let jwt = jwt(&sa, &key, 1_000).unwrap();
        let parts: Vec<&str> = jwt.split('.').collect();
        assert_eq!(parts.len(), 3);
        let header: Value = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[0]).unwrap()).unwrap();
        assert_eq!(header, json!({"alg": "RS256", "typ": "JWT"}));
        let claims: Value = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1]).unwrap()).unwrap();
        assert_eq!(
            claims,
            json!({"iss": "svc@p.iam.gserviceaccount.com", "scope": SCOPE,
                "aud": "https://oauth2.googleapis.com/token", "iat": 1_000, "exp": 4_600})
        );
        let sig = URL_SAFE_NO_PAD.decode(parts[2]).unwrap();
        let public = ring::signature::UnparsedPublicKey::new(
            &ring::signature::RSA_PKCS1_2048_8192_SHA256,
            key.public().as_ref(),
        );
        public.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &sig).unwrap();
    }

    #[test]
    fn classifies_errors() {
        let unreg = json!({"error": {"code": 404, "message": "Requested entity was not found.",
            "details": [{"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
                "errorCode": "UNREGISTERED"}]}});
        assert!(matches!(classify(404, &unreg), WakeError::Unregistered));
        let bad = json!({"error": {"code": 400, "message": "bad",
            "details": [{"errorCode": "INVALID_ARGUMENT"}]}});
        assert!(matches!(classify(400, &bad), WakeError::Other(_)));
    }

    /// Real OAuth exchange and send to a made-up token: FCM must reject the token, not our auth.
    /// `cargo test -p jarvis-link -- --ignored fcm_live`
    #[tokio::test]
    #[ignore = "talks to Google"]
    async fn fcm_live_auth() {
        let path = std::env::var_os("JARVIS_FCM_KEY")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|| crate::default_fcm_key().unwrap());
        let fcm = Fcm::load(&path).unwrap();
        fcm.access_token().await.expect("token exchange");
        match fcm.wake("not-a-real-registration-token").await {
            Err(WakeError::Unregistered) => {}
            Err(WakeError::Other(e)) => {
                let e = format!("{e:#}");
                assert!(e.contains("INVALID_ARGUMENT") || e.contains("400"), "unexpected: {e}");
            }
            Ok(()) => panic!("FCM accepted a fake token"),
        }
    }

    /// A throwaway 2048-bit key generated for this test only.
    const TEST_KEY: &str = include_str!("../tests/test-rsa-key.pem");
}
