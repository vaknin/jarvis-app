//! `jarvis-link daemon`: runs the node and serves the CLI/UI socket.

use std::{
    collections::{HashMap, HashSet},
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result};
use serde_json::{Value, json};
use jarvis_core::{
    node::{Event, Node},
    proto::Frame,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    sync::{broadcast::error::RecvError, mpsc},
    time::Instant,
};
use tracing::{debug, info};

use crate::{
    apps::{self, Apps, Channel},
    fcm,
    ipc::{AppDone, Request},
    presence,
};

const RING_TTL: Duration = Duration::from_secs(120);
/// How long `notifications --fresh` waits for the phone's list after asking for a wake.
const FRESH_WAIT: Duration = Duration::from_secs(25);
/// A connected phone sends its list right after the link comes up; give it this long.
const SNAPSHOT_GRACE: Duration = Duration::from_secs(3);
/// After a `--fresh` request the link stays open this long, so polling (rami-login waiting for an
/// SMS code) sees new notifications live instead of waking the phone each time.
const FRESH_HOLD: Duration = Duration::from_secs(120);
/// How long `connect` waits for a woken phone to dial in.
const WAKE_WAIT: Duration = Duration::from_secs(25);
/// The live channel the phone app answers "where is adb?" on (`Adb.kt`).
const ADB_CHANNEL: &str = "_adb";
/// How long `adb` waits for the phone's answer, wake included: a wake can be held back up to
/// `fcm::WAKE_GAP` after the last one, and the phone may turn Wireless debugging on and look for
/// its port first (~10 s at most).
const ADB_WAIT: Duration = Duration::from_secs(60);
/// After an `adb` request the link stays open this long, so a second ask is instant.
const ADB_HOLD: Duration = Duration::from_secs(30);

#[derive(Clone)]
pub struct Ctx {
    pub node: Node,
    /// Asks the FCM loop to wake the phone; `None` without a key.
    pub wake: Option<mpsc::UnboundedSender<()>>,
    /// The last wake got no link (see [`fcm::run`]); reported by `status`.
    pub unanswered: fcm::Unanswered,
    hold_until: Arc<Mutex<Option<Instant>>>,
    /// App channels with a client subscribed, and how many.
    pub clients: Clients,
    pub apps: Arc<Apps>,
    /// The phone's newest presence (`_presence`), for `status` and `watch`.
    pub presence: presence::Tracker,
}

pub type Clients = Arc<Mutex<HashMap<String, usize>>>;

/// Counts a channel client while it lives.
struct Subscribed(Clients, String);

impl Drop for Subscribed {
    fn drop(&mut self) {
        let mut c = self.0.lock().unwrap();
        if let Some(n) = c.get_mut(&self.1) {
            *n -= 1;
            if *n == 0 {
                c.remove(&self.1);
            }
        }
    }
}

impl Ctx {
    pub fn new(
        node: Node,
        wake: Option<mpsc::UnboundedSender<()>>,
        unanswered: fcm::Unanswered,
        clients: Clients,
        apps: Arc<Apps>,
        presence: presence::Tracker,
    ) -> Self {
        Self { node, wake, unanswered, hold_until: Arc::default(), clients, apps, presence }
    }

    /// Manifests first, then the channels that only have items (threads), each with its newest
    /// view (parsed) and that view's badge.
    fn channels(&self) -> Result<Value> {
        let mut list = self.apps.list();
        for name in self.node.app_channels()? {
            if !name.starts_with('_') && !list.iter().any(|c| c.name == name) {
                list.push(Channel::thread(&name));
            }
        }
        let mut out = Vec::new();
        for c in list {
            let view = self.node.app_view(&c.name)?.map(|d| parse(&d)).unwrap_or(Value::Null);
            let mut v = serde_json::to_value(&c)?;
            v["laptop"] = c.laptop.into();
            v["show"] = serde_json::to_value(c.show)?;
            v["badge"] = view.get("badge").cloned().unwrap_or(Value::Null);
            v["view"] = view;
            out.push(v);
        }
        Ok(Value::Array(out))
    }

    /// Keeps the link open for `dur` (or longer, if an earlier call asked for more).
    fn hold(&self, dur: Duration) {
        let until = Instant::now() + dur;
        let start = {
            let mut slot = self.hold_until.lock().unwrap();
            let prev = *slot;
            *slot = Some(prev.map_or(until, |p| p.max(until)));
            prev.is_none()
        };
        self.node.set_stay(true);
        if start {
            let me = self.clone();
            tokio::spawn(async move {
                loop {
                    let until = me.hold_until.lock().unwrap().expect("set while held");
                    tokio::time::sleep_until(until).await;
                    let mut slot = me.hold_until.lock().unwrap();
                    if slot.is_some_and(|u| u <= Instant::now()) {
                        *slot = None;
                        me.node.set_stay(false);
                        return;
                    }
                }
            });
        }
    }

    async fn fresh_notifs(&self) -> Result<Value> {
        let node = &self.node;
        let mut rx = node.events();
        self.hold(FRESH_HOLD);
        if !node.is_connected() {
            match &self.wake {
                Some(w) => {
                    let _ = w.send(());
                }
                None => return Ok(serde_json::to_value(node.notifs())?),
            }
            let wait = async {
                let mut grace: Option<Instant> = None;
                loop {
                    let ev = match grace {
                        Some(g) => match tokio::time::timeout_at(g, rx.recv()).await {
                            Ok(ev) => ev,
                            Err(_) => return,
                        },
                        None => rx.recv().await,
                    };
                    match ev {
                        Ok(Event::Notifs { .. }) | Err(RecvError::Closed) => return,
                        Ok(Event::Connected) => grace = Some(Instant::now() + SNAPSHOT_GRACE),
                        Ok(_) | Err(RecvError::Lagged(_)) => {}
                    }
                }
            };
            let _ = tokio::time::timeout(FRESH_WAIT, wait).await;
        }
        Ok(serde_json::to_value(node.notifs())?)
    }

    /// Dials the phone, waking it first (its endpoint is off while idle, so a bare dial only reaches
    /// a phone that is already up).
    async fn connect(&self) -> Result<()> {
        let node = &self.node;
        let mut rx = node.events();
        if node.is_connected() {
            return Ok(());
        }
        let Some(w) = &self.wake else { return node.connect().await };
        let _ = w.send(());
        // Dial meanwhile: a phone that is up (or a wake held back by the FCM gap) needs no wait.
        let woke = tokio::time::timeout(WAKE_WAIT, connected(&mut rx));
        tokio::pin!(woke);
        tokio::select! {
            r = node.connect() => match r {
                Ok(()) => return Ok(()),
                Err(e) if woke.await.is_ok_and(|up| up) => { let _ = e; }
                Err(e) => return Err(e),
            },
            up = &mut woke => if !up.is_ok_and(|up| up) {
                return node.connect().await;
            },
        }
        Ok(())
    }

    /// Asks the phone where its adb listens (`jarvis-link adb`; dibs reconnects adb with it): wakes it
    /// if needed and returns its answer (see `Adb.kt`). With `enable` the phone turns Wireless
    /// debugging on first, if it may.
    async fn adb(&self, enable: bool) -> Result<Value> {
        let node = &self.node;
        let mut rx = node.events();
        self.hold(ADB_HOLD);
        // The answer echoes the id, so concurrent or late answers go to the right call.
        let id = uuid::Uuid::new_v4().to_string();
        let ask = json!({ "op": "endpoint", "id": id, "enable": enable }).to_string();
        let mut linked = node.is_connected();
        let run = async {
            if linked {
                anyhow::ensure!(node.send_app_live(ADB_CHANNEL, ask.clone()), "the link dropped");
            } else {
                let w = self.wake.as_ref().context("the phone isn't connected and there is no FCM key to wake it")?;
                let _ = w.send(());
            }
            loop {
                match rx.recv().await {
                    Ok(Event::App { id: None, channel, data, .. }) if channel == ADB_CHANNEL => {
                        let v = parse(&data);
                        if v["id"] == id.as_str() {
                            return Ok(v);
                        }
                    }
                    // Asked again on every new link: when both sides dial at once, the link that
                    // carried the ask may be the one closed. The phone answers each ask.
                    Ok(Event::Connected) => {
                        linked = true;
                        let _ = node.send_app_live(ADB_CHANNEL, ask.clone());
                    }
                    Err(RecvError::Closed) => anyhow::bail!("the daemon is stopping"),
                    Ok(_) | Err(RecvError::Lagged(_)) => {}
                }
            }
        };
        match tokio::time::timeout(ADB_WAIT, run).await {
            Ok(r) => r,
            Err(_) if linked => anyhow::bail!("the phone is connected but didn't answer (Jarvis app older than 0.3.7?)"),
            Err(_) => anyhow::bail!("the phone didn't wake up (no link within {} s)", ADB_WAIT.as_secs()),
        }
    }
}

/// Waits for the next link; false when the node is gone.
async fn connected(rx: &mut tokio::sync::broadcast::Receiver<Event>) -> bool {
    loop {
        match rx.recv().await {
            Ok(Event::Connected) => return true,
            Err(RecvError::Closed) => return false,
            Ok(_) | Err(RecvError::Lagged(_)) => {}
        }
    }
}

pub async fn serve(ctx: Ctx, sock: &Path) -> Result<()> {
    if UnixStream::connect(sock).await.is_ok() {
        anyhow::bail!("another jarvis daemon is already serving {}", sock.display());
    }
    let _ = std::fs::remove_file(sock);
    let listener = UnixListener::bind(sock).with_context(|| format!("bind {}", sock.display()))?;
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(sock, std::fs::Permissions::from_mode(0o600))?;
    }
    info!("listening on {}", sock.display());
    loop {
        let (stream, _) = listener.accept().await?;
        let ctx = ctx.clone();
        tokio::spawn(async move {
            if let Err(e) = client(ctx, stream).await {
                debug!("client: {e:#}");
            }
        });
    }
}

async fn client(ctx: Ctx, stream: UnixStream) -> Result<()> {
    let node = &ctx.node;
    let (r, mut w) = stream.into_split();
    let mut lines = BufReader::new(r).lines();
    let Some(line) = lines.next_line().await? else { return Ok(()) };
    let req: Request = match serde_json::from_str(&line) {
        Ok(r) => r,
        Err(e) => return reply(&mut w, Err(anyhow::anyhow!("bad request: {e}"))).await,
    };
    if let Request::AppSubscribe { channel } = req {
        if reserved(&channel) {
            return reply(&mut w, Err(anyhow::anyhow!("{channel} is the daemon's own channel"))).await;
        }
        return subscribe(&ctx, channel, lines, w).await;
    }
    if let Request::Watch = req {
        let mut rx = node.events();
        let mut presence = ctx.presence.subscribe();
        loop {
            tokio::select! {
                ev = rx.recv() => match ev {
                    // App data is for its channel's client only; UIs get a notice to re-read.
                    Ok(Event::App { id: Some(_), channel, view, .. }) => {
                        send_line(&mut w, &json!({ "type": "app", "channel": channel, "view": view })).await?;
                    }
                    // Live app frames are their client's; `_presence` comes parsed, below.
                    Ok(Event::App { id: None, .. }) => {}
                    // A channel's file is its channel's business too: a notice, as for its items.
                    Ok(Event::Message(m)) if m.channel.is_some() => {
                        send_line(&mut w, &json!({ "type": "app", "channel": m.channel, "view": false })).await?;
                    }
                    Ok(e) => {
                        let mut line = serde_json::to_vec(&e)?;
                        line.push(b'\n');
                        w.write_all(&line).await?;
                    }
                    Err(RecvError::Lagged(n)) => debug!("watcher lagged by {n} events"),
                    Err(RecvError::Closed) => return Ok(()),
                },
                p = presence.recv() => match p {
                    Ok(p) => send_line(&mut w, &p.watch_json()).await?,
                    Err(RecvError::Lagged(n)) => debug!("watcher lagged by {n} presence frames"),
                    Err(RecvError::Closed) => return Ok(()),
                },
            }
        }
    }
    let res = handle(&ctx, req).await;
    reply(&mut w, res).await
}

/// A channel client: the items waiting for it, then new ones and live messages as they come.
/// An item it doesn't mark done comes again on its next subscription.
async fn subscribe(
    ctx: &Ctx,
    channel: String,
    mut lines: tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
    mut w: tokio::net::unix::OwnedWriteHalf,
) -> Result<()> {
    let node = &ctx.node;
    let mut rx = node.events();
    *ctx.clients.lock().unwrap().entry(channel.clone()).or_default() += 1;
    let _sub = Subscribed(ctx.clients.clone(), channel.clone());
    let mut sent = HashSet::new();
    for (id, data) in node.app_pending(&channel)? {
        sent.insert(id);
        send_line(&mut w, &json!({ "id": id, "data": data })).await?;
    }
    loop {
        tokio::select! {
            ev = rx.recv() => match ev {
                Ok(Event::App { id, channel: c, data, from_me: false, view: false }) if c == channel => match id {
                    Some(id) if sent.insert(id) => send_line(&mut w, &json!({ "id": id, "data": data })).await?,
                    Some(_) => {}
                    None => send_line(&mut w, &json!({ "data": data })).await?,
                },
                Ok(_) => {}
                Err(RecvError::Lagged(n)) => debug!("app client lagged by {n} events"),
                Err(RecvError::Closed) => return Ok(()),
            },
            l = lines.next_line() => {
                let Some(l) = l? else { return Ok(()) };
                let done: AppDone = serde_json::from_str(&l).context("bad app client line")?;
                node.app_done(done.done.parse().context("bad item id")?)?;
            }
        }
    }
}

async fn send_line(w: &mut (impl AsyncWriteExt + Unpin), v: &Value) -> Result<()> {
    let mut line = serde_json::to_vec(v)?;
    line.push(b'\n');
    w.write_all(&line).await?;
    Ok(())
}

async fn reply(w: &mut (impl AsyncWriteExt + Unpin), res: Result<Value>) -> Result<()> {
    let v = match res {
        Ok(data) => json!({ "ok": true, "data": data }),
        Err(e) => json!({ "ok": false, "error": format!("{e:#}") }),
    };
    let mut line = serde_json::to_vec(&v)?;
    line.push(b'\n');
    w.write_all(&line).await?;
    Ok(())
}

async fn handle(ctx: &Ctx, req: Request) -> Result<Value> {
    let node = &ctx.node;
    Ok(match req {
        Request::Status => {
            let mut v = serde_json::to_value(node.status()?)?;
            v["wake_unanswered"] = ctx.unanswered.load(std::sync::atomic::Ordering::Relaxed).into();
            v["phone_app"] = node.peer_app()?.into();
            v["phone_presence"] = ctx.presence.latest().map_or(Value::Null, |p| p.status_json());
            v
        }
        Request::PairOffer => json!({ "code": node.pair_offer() }),
        Request::Pair { code } => serde_json::to_value(node.pair(&code).await?)?,
        Request::Unpair => {
            node.unpair()?;
            Value::Null
        }
        Request::Send { paths } => {
            let mut sent = Vec::new();
            for p in paths {
                sent.push(node.send_file(&p).await?);
            }
            serde_json::to_value(sent)?
        }
        Request::SendChannelFile { channel, paths } => {
            check_channel(&channel)?;
            let mut sent = Vec::new();
            for p in paths {
                sent.push(node.send_channel_file(&channel, &p).await?);
            }
            serde_json::to_value(sent)?
        }
        Request::Cancel { id } => {
            anyhow::ensure!(node.cancel(id.parse().context("bad message id")?)?, "not a file that is still sending");
            Value::Null
        }
        Request::Msg { text } => serde_json::to_value(node.send_text(text)?)?,
        Request::Ping { text } => serde_json::to_value(node.send_ping(text)?)?,
        Request::Ring => serde_json::to_value(node.ring(RING_TTL)?)?,
        Request::StopRing => {
            anyhow::ensure!(node.send_live(Frame::StopRing), "the phone isn't connected");
            Value::Null
        }
        Request::Notifications { fresh: false } => serde_json::to_value(node.notifs())?,
        Request::Notifications { fresh: true } => ctx.fresh_notifs().await?,
        Request::Json { limit } => json!({
            "status": node.status()?,
            "media": node.media(),
            "messages": node.recent(limit)?,
        }),
        Request::MarkRead => {
            node.mark_read()?;
            Value::Null
        }
        Request::Connect => {
            ctx.connect().await?;
            Value::Null
        }
        Request::Adb { enable } => ctx.adb(enable).await?,
        Request::AppSend { channel, data, live, replace } => {
            check_channel(&channel)?;
            if live {
                json!({ "sent": node.send_app_live(&channel, data) })
            } else {
                json!({ "id": node.send_app(&channel, data, replace)? })
            }
        }
        Request::AppAction { channel, data } => {
            check_channel(&channel)?;
            json!({ "id": node.app_local(&channel, action(&data)?)? })
        }
        Request::Channels => ctx.channels()?,
        Request::ChannelsReload => {
            ctx.apps.reload(node)?;
            ctx.channels()?
        }
        Request::List { channel, op } => {
            check_channel(&channel)?;
            let c = ctx.apps.get(&channel).filter(|c| c.kind == apps::Kind::List);
            let c = c.with_context(|| format!("{channel} isn't a list channel (kind = \"list\" in its manifest)"))?;
            ctx.apps.lists().run_op(node, &channel, c.keep_done, op)?
        }
        Request::DropThread { channel } => {
            check_channel(&channel)?;
            let n = node.app_history(&channel, usize::MAX >> 1)?.len();
            node.drop_channel(&channel)?;
            json!({ "dropped": n })
        }
        Request::Thread { channel, limit } => {
            let items = node.app_history(&channel, limit)?.into_iter().map(|m| {
                let data = m.text.as_deref().map_or(Value::Null, parse);
                (m.ts_ms, json!({ "id": m.id, "data": data, "from_me": m.from_me, "ts_ms": m.ts_ms }))
            });
            // The channel's files (a photo sent to dibs): `path` once it has arrived.
            let files = node.app_files(&channel, limit)?.into_iter().map(|m| (m.ts_ms, file_json(&m)));
            let mut all: Vec<(i64, Value)> = items.chain(files).collect();
            all.sort_by_key(|(ts, _)| *ts);
            let skip = all.len().saturating_sub(limit);
            Value::Array(all.into_iter().skip(skip).map(|(_, v)| v).collect())
        }
        Request::Watch | Request::AppSubscribe { .. } => unreachable!("handled by the caller"),
    })
}

/// A channel file in `thread`: `{"id","from_me","ts_ms","file":{"name","size","state","path"?}}`;
/// `path` only once it has arrived (or, for mine, while it's there to send).
fn file_json(m: &jarvis_core::store::Message) -> Value {
    let mut f = json!({ "name": m.file_name, "size": m.file_size, "state": m.state });
    if let Some(p) = &m.path {
        f["path"] = p.to_string_lossy().into();
    }
    json!({ "id": m.id, "from_me": m.from_me, "ts_ms": m.ts_ms, "file": f })
}

/// App data as JSON for the UIs; data that isn't JSON comes as a string.
fn parse(data: &str) -> Value {
    serde_json::from_str(data).unwrap_or_else(|_| Value::String(data.into()))
}

/// A channel the socket may send on: a plain name, and not one of the daemon's own (`_…`).
fn check_channel(channel: &str) -> Result<()> {
    anyhow::ensure!(apps::valid_name(channel), "bad channel name {channel:?} (use [a-z0-9_-]+, not starting with _)");
    Ok(())
}

/// The live channels the daemon itself answers (`check_channel` refuses them, as any `_…` name,
/// for sending); no app client gets their frames either.
fn reserved(channel: &str) -> bool {
    channel == ADB_CHANNEL || channel == presence::CHANNEL
}

/// Completes a laptop UI's action envelope: `from`, `uid` and `ts` unless it set them.
/// A local action's envelope. `from` and `ts` are always ours: a local caller can't pass its
/// action off as the phone's, or date it back (dibs takes a phone tap as the user's own word).
fn action(data: &str) -> Result<String> {
    let mut v: Value = serde_json::from_str(data).context("an action is a JSON object")?;
    let o = v.as_object_mut().context("an action is a JSON object")?;
    o.insert("from".into(), json!("laptop"));
    o.entry("uid").or_insert_with(|| json!(uuid::Uuid::new_v4().to_string()));
    o.insert("ts".into(), json!(jarvis_core::store::now_ms()));
    Ok(v.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_laptop_action_gets_its_envelope() {
        let v: Value = serde_json::from_str(&action(r#"{"action":"add","uid":"u1"}"#).unwrap()).unwrap();
        assert_eq!((v["action"].as_str(), v["from"].as_str(), v["uid"].as_str()), (Some("add"), Some("laptop"), Some("u1")));
        assert!(v["ts"].as_i64().is_some_and(|t| t > 0));
        // Posing as the phone, or backdating, doesn't stick.
        let v: Value = serde_json::from_str(&action(r#"{"action":"a42","from":"phone","ts":1}"#).unwrap()).unwrap();
        assert_eq!(v["from"].as_str(), Some("laptop"));
        assert!(v["ts"].as_i64().is_some_and(|t| t > 1));
        assert!(action("[1]").is_err());
        assert!(action("nope").is_err());
        assert!(check_channel("_channels").is_err());
        assert!(check_channel("teen").is_ok());
        assert!(check_channel(presence::CHANNEL).is_err());
        assert!(reserved(presence::CHANNEL) && reserved(ADB_CHANNEL) && !reserved("teen"));
    }
}
