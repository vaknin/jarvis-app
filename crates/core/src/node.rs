//! The iroh endpoint and the single link to the paired peer: pairing, dialing on demand, the
//! outbox resend, acks, file streams that resume from an offset, and the idle close.
//!
//! Only one link is kept. If both sides dial at once, the connection whose dialer has the smaller
//! endpoint id wins, so both sides keep the same one.

use std::{
    collections::HashSet,
    io::SeekFrom,
    net::SocketAddr,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::{Context, Result, anyhow, bail, ensure};
use data_encoding::BASE32_NOPAD;
use iroh::{
    Endpoint, EndpointAddr, EndpointId, PublicKey, RelayMode, SecretKey,
    address_lookup::memory::MemoryLookup,
    endpoint::{Connection, RecvStream, presets},
};
use iroh_mdns_address_lookup::MdnsAddressLookup;
use serde::Serialize;
use tokio::{
    io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt},
    sync::{Notify, broadcast, mpsc},
    time::timeout,
};
use tracing::{debug, info, warn};
use uuid::Uuid;

use crate::{
    files,
    proto::{
        ALPN, Body, FileHeader, Frame, MediaCmd, MediaState, PAIR_ALPN, PairReply, PairRequest,
        PhoneNotif, read_frame, read_msg, write_msg,
    },
    store::{Message, State, Store, now_ms},
};

/// The fixed UDP port the laptop listens on, so the firewall needs one rule.
pub const PORT: u16 = 47114;
/// The reserved live channel a node tells its app version on (`Config::app_version`).
const APP_VERSION: &str = "_version";
const PAIR_TTL: Duration = Duration::from_secs(300);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const CHUNK: usize = 256 * 1024;

const CLOSE_BYE: u32 = 0;
const CLOSE_NOT_PAIRED: u32 = 1;
const CLOSE_DUPLICATE: u32 = 2;
const CLOSE_IDLE: u32 = 3;

/// Stream reset code for a file whose sender cancelled it.
const RESET_CANCELLED: u32 = 1;

pub enum Net {
    /// n0 relays and DNS lookup plus mDNS on the LAN. `port` pins the IPv4 socket.
    Internet { port: Option<u16> },
    /// 127.0.0.1 only, no relay and no lookup; peers are found through [`Node::add_hint`]. For tests.
    Loopback,
}

pub struct Config {
    /// Holds `jarvis.db` (chat log, outbox, keys).
    pub state_dir: PathBuf,
    /// Received files land here; partial ones as hidden `.part` files.
    pub download_dir: PathBuf,
    /// Shown to the peer ("Laptop", "Pixel 8").
    pub name: String,
    pub net: Net,
    /// Close the link after this long without traffic, unless either side asked to stay connected.
    pub idle_timeout: Duration,
    /// Redial with this backoff (min, max) while the outbox has items. The laptop does this as the
    /// fallback when FCM can't wake the phone; the phone leaves it off and dials on demand.
    pub redial: Option<(Duration, Duration)>,
    /// This app's version, told to the peer on every link (the phone's; `jarvis-link status` shows it
    /// as `phone_app`, so apps send blocks only a new enough phone draws).
    pub app_version: Option<String>,
}

impl Config {
    pub fn new(state_dir: PathBuf, download_dir: PathBuf, name: impl Into<String>) -> Self {
        Self {
            state_dir,
            download_dir,
            name: name.into(),
            net: Net::Internet { port: Some(PORT) },
            idle_timeout: Duration::from_secs(60),
            redial: Some((Duration::from_secs(30), Duration::from_secs(300))),
            app_version: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Status {
    pub id: String,
    pub name: String,
    pub peer: Option<PeerInfo>,
    pub connected: bool,
    pub queued: usize,
    /// When the oldest item of the outbox was queued; none while it is empty.
    pub oldest_queued_ms: Option<i64>,
    pub unread: u64,
}

/// What UIs get from [`Node::events`]; also the JSON lines of `jarvis-link watch`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum Event {
    Connected,
    Disconnected,
    Paired { peer: PeerInfo },
    Unpaired,
    /// A message was added or changed state (queued → delivered, incoming → received, …).
    Message(Message),
    /// File transfer progress, either direction.
    Progress { id: Uuid, done: u64, total: u64 },
    Media { state: Option<MediaState> },
    MediaCmd { cmd: MediaCmd },
    Notifs { list: Vec<PhoneNotif> },
    StopRing,
    /// Received messages were marked read (the laptop's unread badge clears).
    Read,
    /// The peer closed the link because it doesn't take us as its pair (it unpaired, or paired
    /// with another device). Re-pairing is the only fix.
    Refused,
    /// A file of mine can't be sent (gone, or changed while sending): it is cancelled, so the
    /// peer drops what it has, and this says why.
    SendFailed { id: Uuid, name: String, reason: String },
    /// An app channel's item or view (`id` set, queued) or live message (`id` none). It is not
    /// chat, and only that channel's client handles it. Mine are emitted when queued, so a wake
    /// can follow. `view`: the channel's whole state, replacing the sender's previous one.
    App { id: Option<Uuid>, channel: String, data: String, from_me: bool, view: bool },
}

#[derive(Clone)]
pub struct Node {
    inner: Arc<Inner>,
}

struct Inner {
    cfg: Config,
    ep: Endpoint,
    hints: MemoryLookup,
    store: Mutex<Store>,
    events: broadcast::Sender<Event>,
    link: Mutex<Option<Link>>,
    next_serial: AtomicU64,
    offer: Mutex<Option<([u8; 32], Instant)>>,
    dialing: tokio::sync::Mutex<()>,
    kick: Notify,
    stay: AtomicBool,
    receiving: Mutex<HashSet<Uuid>>,
    /// Ids the peer asked bytes for that aren't a file of mine, logged once each.
    odd_wants: Mutex<HashSet<Uuid>>,
    media: Mutex<Option<MediaState>>,
    notifs: Mutex<Vec<PhoneNotif>>,
}

#[derive(Clone)]
struct Link {
    serial: u64,
    conn: Connection,
    dialer: EndpointId,
    tx: mpsc::UnboundedSender<Frame>,
    activity: Arc<Activity>,
}

struct Activity {
    last: Mutex<Instant>,
    transfers: AtomicUsize,
    peer_stay: AtomicBool,
}

impl Activity {
    fn touch(&self) {
        *self.last.lock().unwrap() = Instant::now();
    }

    fn transfer(self: &Arc<Self>) -> TransferGuard {
        self.transfers.fetch_add(1, Ordering::SeqCst);
        TransferGuard(self.clone())
    }
}

struct TransferGuard(Arc<Activity>);

impl Drop for TransferGuard {
    fn drop(&mut self) {
        self.0.transfers.fetch_sub(1, Ordering::SeqCst);
        self.0.touch();
    }
}

impl Node {
    pub async fn start(cfg: Config) -> Result<Node> {
        std::fs::create_dir_all(&cfg.state_dir)?;
        std::fs::create_dir_all(&cfg.download_dir)?;
        let store = Store::open(&crate::compat::database(&cfg.state_dir)?)?;
        let sk = match store.get_kv("secret_key")? {
            Some(b) => SecretKey::from_bytes(
                &b.try_into().map_err(|_| anyhow!("stored secret key is corrupt"))?,
            ),
            None => {
                let k = SecretKey::generate();
                store.set_kv("secret_key", &k.to_bytes())?;
                k
            }
        };
        let hints = MemoryLookup::new();
        let alpns = vec![ALPN.to_vec(), PAIR_ALPN.to_vec()];
        let ep = match cfg.net {
            Net::Internet { port } => {
                let mut b = Endpoint::builder(presets::N0)
                    .secret_key(sk)
                    .alpns(alpns)
                    .address_lookup(hints.clone())
                    .address_lookup(MdnsAddressLookup::builder());
                if let Some(p) = port {
                    b = b.bind_addr(SocketAddr::from(([0, 0, 0, 0], p)))?;
                }
                b.bind().await?
            }
            Net::Loopback => {
                Endpoint::builder(presets::Minimal)
                    .relay_mode(RelayMode::Disabled)
                    .clear_ip_transports()
                    .bind_addr("127.0.0.1:0")?
                    .secret_key(sk)
                    .alpns(alpns)
                    .address_lookup(hints.clone())
                    .bind()
                    .await?
            }
        };
        info!(id = %ep.id(), "endpoint up");
        let redial = cfg.redial;
        let inner = Arc::new(Inner {
            cfg,
            ep,
            hints,
            store: Mutex::new(store),
            events: broadcast::channel(256).0,
            link: Mutex::new(None),
            next_serial: AtomicU64::new(1),
            offer: Mutex::new(None),
            dialing: tokio::sync::Mutex::new(()),
            kick: Notify::new(),
            stay: AtomicBool::new(false),
            receiving: Mutex::new(HashSet::new()),
            odd_wants: Mutex::new(HashSet::new()),
            media: Mutex::new(None),
            notifs: Mutex::new(Vec::new()),
        });
        tokio::spawn(inner.clone().accept_loop());
        if let Some((min, max)) = redial {
            tokio::spawn(inner.clone().redial_loop(min, max));
        }
        Ok(Node { inner })
    }

    pub fn events(&self) -> broadcast::Receiver<Event> {
        self.inner.events.subscribe()
    }

    /// How many messages the store holds.
    pub fn message_count(&self) -> Result<u64> {
        self.inner.db(|s| s.count())
    }

    pub fn id(&self) -> EndpointId {
        self.inner.ep.id()
    }

    pub fn addr(&self) -> EndpointAddr {
        self.inner.ep.addr()
    }

    /// Tells the endpoint where a peer can be reached, in addition to lookup.
    pub fn add_hint(&self, addr: EndpointAddr) {
        self.inner.hints.add_endpoint_info(addr);
    }

    pub fn peer(&self) -> Option<PeerInfo> {
        self.inner.peer()
    }

    pub fn is_connected(&self) -> bool {
        self.inner.current().is_some()
    }

    /// The device changed networks (Wi-Fi to mobile data): Android shows native code no such change,
    /// so the app tells iroh, or the link and relay stay on the old network's sockets for minutes.
    pub async fn network_change(&self) {
        self.inner.ep.network_change().await;
    }

    pub fn status(&self) -> Result<Status> {
        let i = &self.inner;
        let (outbox, unread) = i.db(|s| Ok((s.outbox()?.0, s.unread()?)))?;
        Ok(Status {
            id: i.ep.id().to_string(),
            name: i.cfg.name.clone(),
            peer: i.peer(),
            connected: self.is_connected(),
            queued: outbox.len(),
            oldest_queued_ms: outbox.iter().map(|m| m.ts_ms).min(),
            unread,
        })
    }

    pub fn recent(&self, limit: usize) -> Result<Vec<Message>> {
        self.inner.db(|s| s.recent(limit))
    }

    pub fn unread(&self) -> Result<u64> {
        self.inner.db(|s| s.unread())
    }

    pub fn mark_read(&self) -> Result<()> {
        self.inner.db(|s| s.mark_read())?;
        self.inner.emit(Event::Read);
        Ok(())
    }

    pub fn media(&self) -> Option<MediaState> {
        self.inner.media.lock().unwrap().clone()
    }

    pub fn notifs(&self) -> Vec<PhoneNotif> {
        self.inner.notifs.lock().unwrap().clone()
    }

    /// The FCM token the phone sent, used to wake it.
    pub fn push_token(&self) -> Result<Option<String>> {
        self.inner.db(|s| s.get_str("push_token"))
    }

    /// The app version the peer last told (see [`Config::app_version`]); none until it has.
    pub fn peer_app(&self) -> Result<Option<String>> {
        self.inner.db(|s| s.get_str("peer_app"))
    }

    /// Forgets the FCM token (FCM said it is no longer registered); the phone sends a fresh one on
    /// its next connect.
    pub fn clear_push_token(&self) -> Result<()> {
        self.inner.db(|s| s.del_kv("push_token"))
    }

    /// A one-time pairing code, valid for 5 minutes: `<compat::PAIR_WORD>:1:<endpoint id>:<base32 token>`.
    pub fn pair_offer(&self) -> String {
        let token: [u8; 32] = rand::random();
        *self.inner.offer.lock().unwrap() = Some((token, Instant::now() + PAIR_TTL));
        format!("{}:1:{}:{}", crate::compat::PAIR_WORD, self.id(), BASE32_NOPAD.encode(&token))
    }

    /// Pairs with the device that showed `code`.
    pub async fn pair(&self, code: &str) -> Result<PeerInfo> {
        self.inner.pair(code).await
    }

    pub fn unpair(&self) -> Result<()> {
        let i = &self.inner;
        i.db(|s| {
            s.del_kv("peer_id")?;
            s.del_kv("peer_name")?;
            s.del_kv("peer_app")?;
            s.del_kv("push_token")
        })?;
        i.drop_link(CLOSE_NOT_PAIRED, b"unpaired");
        i.emit(Event::Unpaired);
        Ok(())
    }

    pub fn send_text(&self, text: impl Into<String>) -> Result<Message> {
        self.inner.send(Body::Text(text.into()), None, None)
    }

    pub fn send_ping(&self, text: impl Into<String>) -> Result<Message> {
        self.inner.send(Body::Ping(text.into()), None, None)
    }

    /// Rings the phone; dropped if it can't be delivered within `ttl`.
    pub fn ring(&self, ttl: Duration) -> Result<Message> {
        self.inner.send(Body::Ring, None, Some(ttl))
    }

    pub async fn send_file(&self, path: &Path) -> Result<Message> {
        let path = std::path::absolute(path)?;
        let (sha256, size) = files::sha256_file(&path).await?;
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "file".into());
        self.inner.send(Body::File { name, size, sha256 }, Some(path), None)
    }

    /// Queues a file for an app channel (a photo sent to dibs): sent like [`Node::send_file`], kept
    /// by the receiver under `<state>/channels/<channel>/`, out of the chat.
    pub async fn send_channel_file(&self, channel: &str, path: &Path) -> Result<Message> {
        let path = std::path::absolute(path)?;
        let (sha256, size) = files::sha256_file(&path).await?;
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "file".into());
        self.inner.send(Body::ChannelFile { channel: channel.into(), name, size, sha256 }, Some(path), None)
    }

    /// The channel's last `limit` files, both ways, oldest first.
    pub fn app_files(&self, channel: &str, limit: usize) -> Result<Vec<Message>> {
        self.inner.db(|s| s.app_files(channel, limit))
    }

    /// Queues an item on an app channel. With `replace` it is the channel's view, and my older
    /// views of it are dropped, delivered or not (only the newest matters).
    pub fn send_app(&self, channel: &str, data: String, replace: bool) -> Result<Uuid> {
        Ok(self.inner.send(Body::App { channel: channel.into(), data, replace }, None, None)?.id)
    }

    /// Deletes a channel's thread on both sides: drops it here now, and queues the drop for the peer.
    pub fn drop_channel(&self, channel: &str) -> Result<Uuid> {
        Ok(self.inner.send(Body::DropChannel(channel.into()), None, None)?.id)
    }

    /// An item from a local UI (the laptop's panel): stored and emitted as if the peer sent it,
    /// so the channel's client (or a start on demand) takes it.
    pub fn app_local(&self, channel: &str, data: String) -> Result<Uuid> {
        let m = self.inner.db(|s| s.app_local(channel, data))?;
        let id = m.id;
        self.inner.emit_msg(m, true);
        Ok(id)
    }

    /// The channel's newest view (the app's state), from either side.
    pub fn app_view(&self, channel: &str) -> Result<Option<String>> {
        Ok(self.inner.db(|s| s.app_view(channel))?.and_then(|m| m.text))
    }

    /// The channel's last `limit` items both ways (posts, replies, actions), oldest first; no views.
    pub fn app_history(&self, channel: &str, limit: usize) -> Result<Vec<Message>> {
        self.inner.db(|s| s.app_history(channel, limit))
    }

    /// Every channel that has an item or a view.
    pub fn app_channels(&self) -> Result<Vec<String>> {
        self.inner.db(|s| s.app_channels())
    }

    /// My items not yet delivered, oldest first (the outbox).
    pub fn outbox(&self) -> Result<Vec<Message>> {
        Ok(self.inner.db(|s| s.outbox())?.0)
    }

    /// An app channel's live message; false when there is no link.
    pub fn send_app_live(&self, channel: &str, data: String) -> bool {
        self.send_live(Frame::App { channel: channel.into(), data })
    }

    /// The channel's received items its client hasn't taken (marked with [`Node::app_done`]).
    pub fn app_pending(&self, channel: &str) -> Result<Vec<(Uuid, String)>> {
        let items = self.inner.db(|s| s.app_pending(channel))?;
        Ok(items.into_iter().map(|m| (m.id, m.text.unwrap_or_default())).collect())
    }

    pub fn app_done(&self, id: Uuid) -> Result<()> {
        self.inner.db(|s| s.app_done(id))
    }

    /// Stops a file I'm sending, mid-stream or still waiting. The peer drops what it has, and every
    /// new link repeats the cancel until it confirms. False if it isn't a queued file of mine
    /// (already delivered, say).
    pub fn cancel(&self, id: Uuid) -> Result<bool> {
        let Some(m) = self.inner.db(|s| s.cancel(id))? else { return Ok(false) };
        self.inner.emit(Event::Message(m));
        self.send_live(Frame::Cancel { id });
        Ok(true)
    }

    /// Sends live state (media, notifications, StopRing, …). Never queued: returns false when
    /// there is no link.
    pub fn send_live(&self, frame: Frame) -> bool {
        self.inner.send_live(frame)
    }

    /// Keeps the link open past the idle timeout (media playing, chat open) and tells the peer.
    pub fn set_stay(&self, stay: bool) {
        self.inner.stay.store(stay, Ordering::SeqCst);
        self.send_live(Frame::StayConnected(stay));
    }

    /// The peer asked the link to stay open (`set_stay` on its side).
    pub fn peer_stays(&self) -> bool {
        self.inner.current().is_some_and(|l| l.activity.peer_stay.load(Ordering::SeqCst))
    }

    /// Dials the peer unless already connected.
    pub async fn connect(&self) -> Result<()> {
        self.inner.connect().await
    }

    pub fn disconnect(&self) {
        self.inner.drop_link(CLOSE_BYE, b"bye");
    }

    pub async fn shutdown(&self) {
        self.disconnect();
        self.inner.ep.close().await;
        self.inner.kick.notify_one();
    }
}

impl Inner {
    fn db<R>(&self, f: impl FnOnce(&Store) -> Result<R>) -> Result<R> {
        f(&self.store.lock().unwrap())
    }

    fn emit(&self, e: Event) {
        let _ = self.events.send(e);
    }

    /// A message changed: chat goes out as `Message`, an app item as `App` (only when it is new;
    /// its later states mean nothing to the app).
    fn emit_msg(&self, m: Message, new: bool) {
        match m.channel {
            Some(channel) if matches!(m.kind, "app" | "view") => {
                if new {
                    let data = m.text.unwrap_or_default();
                    let view = m.kind == "view";
                    self.emit(Event::App { id: Some(m.id), channel, data, from_me: m.from_me, view });
                }
            }
            // A drop is no chat. Mine tells the UIs to re-read (a view without notify, so no
            // wake); the peer's needs nothing: the daemon republishes `_channels`.
            Some(channel) if m.kind == "drop" => {
                if new && m.from_me {
                    self.emit(Event::App { id: Some(m.id), channel, data: String::new(), from_me: true, view: true });
                }
            }
            _ => self.emit(Event::Message(m)),
        }
    }

    fn peer_id(&self) -> Option<EndpointId> {
        let b = self.db(|s| s.get_kv("peer_id")).ok()??;
        PublicKey::from_bytes(&b.try_into().ok()?).ok()
    }

    fn peer(&self) -> Option<PeerInfo> {
        let id = self.peer_id()?;
        let name = self.db(|s| s.get_str("peer_name")).ok().flatten().unwrap_or_default();
        Some(PeerInfo { id: id.to_string(), name })
    }

    fn set_peer(&self, id: EndpointId, name: &str) -> Result<PeerInfo> {
        if self.peer_id().is_some_and(|old| old != id) {
            self.drop_link(CLOSE_NOT_PAIRED, b"unpaired");
            self.db(|s| s.del_kv("push_token"))?;
        }
        self.db(|s| {
            s.set_kv("peer_id", id.as_bytes())?;
            s.set_kv("peer_name", name.as_bytes())
        })?;
        let peer = PeerInfo { id: id.to_string(), name: name.to_string() };
        self.emit(Event::Paired { peer: peer.clone() });
        Ok(peer)
    }

    fn current(&self) -> Option<Link> {
        self.link.lock().unwrap().clone().filter(|l| l.conn.close_reason().is_none())
    }

    /// Sends on the link that is current now. A frame that settles a change already in the
    /// store (a cancel, an ack) goes here, not on the link of the task that made the change: when
    /// both sides dial at once, that link can have been replaced meanwhile, and the new link's
    /// resend ran before the change, so the frame would wait for yet another link. A link made
    /// after the change resends it itself.
    fn send_live(&self, frame: Frame) -> bool {
        self.current().is_some_and(|l| l.tx.send(frame).is_ok())
    }

    fn drop_link(&self, code: u32, reason: &[u8]) {
        let old = self.link.lock().unwrap().take();
        if let Some(l) = old {
            l.conn.close(code.into(), reason);
            self.link_gone();
        }
    }

    /// The link is gone: media is live state, so the phone's player goes away with it.
    fn link_gone(&self) {
        self.emit(Event::Disconnected);
        if self.media.lock().unwrap().take().is_some() {
            self.emit(Event::Media { state: None });
        }
    }

    fn send(&self, body: Body, path: Option<PathBuf>, ttl: Option<Duration>) -> Result<Message> {
        let expires = ttl.map(|d| now_ms() + d.as_millis() as i64);
        let m = self.db(|s| s.enqueue(body, path, expires))?;
        self.emit_msg(m.clone(), true);
        match self.current() {
            Some(l) => {
                let _ = l.tx.send(Frame::Item(m.item()));
            }
            None => self.kick.notify_one(),
        }
        Ok(m)
    }

    async fn accept_loop(self: Arc<Self>) {
        while let Some(incoming) = self.ep.accept().await {
            let me = self.clone();
            tokio::spawn(async move {
                let conn = match incoming.await {
                    Ok(c) => c,
                    Err(e) => return debug!("incoming connection failed: {e}"),
                };
                let remote = conn.remote_id();
                if conn.alpn() == PAIR_ALPN {
                    if let Err(e) = me.serve_pair(conn).await {
                        warn!("pairing from {remote} failed: {e:#}");
                    }
                } else if me.peer_id() == Some(remote) {
                    me.start_link(conn, remote);
                } else {
                    info!("refused unpaired endpoint {remote}");
                    conn.close(CLOSE_NOT_PAIRED.into(), b"not paired");
                }
            });
        }
    }

    async fn redial_loop(self: Arc<Self>, min: Duration, max: Duration) {
        let mut backoff = min;
        loop {
            tokio::select! {
                _ = self.kick.notified() => {}
                _ = tokio::time::sleep(backoff) => {}
            }
            if self.ep.is_closed() {
                return;
            }
            let queued = self.db(|s| Ok(s.outbox()?.0.len())).unwrap_or(0);
            if self.current().is_some() || self.peer_id().is_none() || queued == 0 {
                backoff = min;
                continue;
            }
            match self.connect().await {
                Ok(()) => backoff = min,
                Err(e) => {
                    debug!("redial failed: {e:#}");
                    backoff = (backoff * 2).min(max);
                }
            }
        }
    }

    async fn connect(self: &Arc<Self>) -> Result<()> {
        if self.current().is_some() {
            return Ok(());
        }
        let _dialing = self.dialing.lock().await;
        if self.current().is_some() {
            return Ok(());
        }
        let peer = self.peer_id().context("not paired")?;
        let conn = timeout(CONNECT_TIMEOUT, self.ep.connect(peer, ALPN))
            .await
            .context("connect timed out")??;
        self.start_link(conn, self.ep.id());
        Ok(())
    }

    /// Makes `conn` the link (or drops it if the current one wins) and starts serving it.
    fn start_link(self: &Arc<Self>, conn: Connection, dialer: EndpointId) {
        let (tx, rx) = mpsc::unbounded_channel();
        let link = Link {
            serial: self.next_serial.fetch_add(1, Ordering::SeqCst),
            conn,
            dialer,
            tx,
            activity: Arc::new(Activity {
                last: Mutex::new(Instant::now()),
                transfers: AtomicUsize::new(0),
                peer_stay: AtomicBool::new(false),
            }),
        };
        {
            let mut slot = self.link.lock().unwrap();
            if let Some(old) = slot.as_ref() {
                let old_alive = old.conn.close_reason().is_none();
                if old_alive && old.dialer.as_bytes() < link.dialer.as_bytes() {
                    link.conn.close(CLOSE_DUPLICATE.into(), b"duplicate");
                    return;
                }
                old.conn.close(CLOSE_DUPLICATE.into(), b"duplicate");
            }
            *slot = Some(link.clone());
        }
        debug!(serial = link.serial, dialer = %dialer, "link up");
        tokio::spawn(log_paths(link.conn.clone(), link.serial));
        self.emit(Event::Connected);
        // `set_stay` may have run while there was no link; the peer must hear it on this one.
        if self.stay.load(Ordering::SeqCst) {
            let _ = link.tx.send(Frame::StayConnected(true));
        }
        if let Err(e) = self.resend(&link) {
            warn!("resend failed: {e:#}");
        }
        let me = self.clone();
        tokio::spawn(async move {
            if let Err(e) = me.drive(&link, rx).await {
                debug!(serial = link.serial, "link ended: {e:#}");
            }
            if let Some(iroh::endpoint::ConnectionError::ApplicationClosed(c)) = link.conn.close_reason()
                && c.error_code == CLOSE_NOT_PAIRED.into()
            {
                info!("the peer refused us: not paired");
                me.emit(Event::Refused);
            }
            link.conn.close(CLOSE_BYE.into(), b"bye");
            let was_current = {
                let mut slot = me.link.lock().unwrap();
                let cur = slot.as_ref().is_some_and(|l| l.serial == link.serial);
                if cur {
                    *slot = None;
                }
                cur
            };
            if was_current {
                me.link_gone();
                // Anything enqueued while the link was dying goes out on the next one.
                me.kick.notify_one();
            }
        });
    }

    fn resend(&self, link: &Link) -> Result<()> {
        let (live, expired) = self.db(|s| s.outbox())?;
        for m in expired {
            self.emit(Event::Message(m));
        }
        for m in live {
            let _ = link.tx.send(Frame::Item(m.item()));
        }
        for id in self.db(|s| s.cancelling())? {
            let _ = link.tx.send(Frame::Cancel { id });
        }
        Ok(())
    }

    async fn drive(self: &Arc<Self>, link: &Link, mut rx: mpsc::UnboundedReceiver<Frame>) -> Result<()> {
        // The dialer opens the control stream; it becomes visible to the peer with the first write.
        let (mut send, mut recv) = if link.dialer == self.ep.id() {
            link.conn.open_bi().await?
        } else {
            link.conn.accept_bi().await?
        };
        write_msg(&mut send, &Frame::Hello { name: self.cfg.name.clone() }).await?;
        // A live frame on a reserved channel, so a peer that predates it drops it unseen.
        if let Some(v) = &self.cfg.app_version {
            write_msg(&mut send, &Frame::App { channel: APP_VERSION.into(), data: v.clone() }).await?;
        }
        let act = &link.activity;

        let writer = async {
            while let Some(f) = rx.recv().await {
                write_msg(&mut send, &f).await?;
                act.touch();
            }
            anyhow::Ok(())
        };
        let reader = async {
            while let Some(f) = read_frame(&mut recv).await? {
                act.touch();
                match f {
                    Ok(f) => self.on_frame(link, f).await?,
                    // A newer peer's frame (a kind of item this build doesn't know): skip it, so the
                    // rest of the link still works. Its item stays queued on the peer until we update.
                    Err(e) => debug!(serial = link.serial, "skipped a frame this build can't read: {e}"),
                }
            }
            anyhow::Ok(())
        };
        let unis = async {
            loop {
                let r = link.conn.accept_uni().await?;
                tokio::spawn(self.clone().receive_file(link.clone(), r));
            }
        };
        let idle = async {
            let tick = (self.cfg.idle_timeout / 4).min(Duration::from_secs(5));
            loop {
                tokio::time::sleep(tick).await;
                let busy = self.stay.load(Ordering::SeqCst)
                    || act.peer_stay.load(Ordering::SeqCst)
                    || act.transfers.load(Ordering::SeqCst) > 0;
                if !busy && act.last.lock().unwrap().elapsed() >= self.cfg.idle_timeout {
                    debug!(serial = link.serial, "closing idle link");
                    link.conn.close(CLOSE_IDLE.into(), b"idle");
                    return anyhow::Ok(());
                }
            }
        };
        tokio::select! {
            r = writer => r,
            r = reader => r,
            r = unis => r,
            r = idle => r,
        }
    }

    async fn on_frame(self: &Arc<Self>, link: &Link, f: Frame) -> Result<()> {
        match f {
            Frame::Hello { name } => {
                if let Some(id) = self.peer_id()
                    && self.peer().is_some_and(|p| p.name != name)
                {
                    self.set_peer(id, &name)?;
                }
            }
            Frame::Item(item) => {
                let (msg, new) = self.db(|s| s.receive(&item))?;
                if msg.from_me {
                    return Ok(());
                }
                if new {
                    self.emit_msg(msg.clone(), true);
                }
                if msg.state == State::Incoming {
                    if !self.receiving.lock().unwrap().contains(&msg.id) {
                        let offset = self.part_len(msg.id).await;
                        let _ = link.tx.send(Frame::FileWant { id: msg.id, offset });
                    }
                } else {
                    let _ = link.tx.send(Frame::Ack { id: msg.id });
                }
            }
            Frame::Ack { id } => {
                if let Some(m) = self.db(|s| s.ack(id))? {
                    self.emit_msg(m, false);
                }
            }
            Frame::FileWant { id, offset } => {
                if self.odd_want(id)? {
                    return Ok(());
                }
                let (me, link) = (self.clone(), link.clone());
                tokio::spawn(async move {
                    if let Err(e) = me.stream_file(&link, id, offset).await {
                        warn!("sending file {id} failed: {e:#}");
                    }
                });
            }
            Frame::Media(state) => {
                *self.media.lock().unwrap() = state.clone();
                self.emit(Event::Media { state });
            }
            Frame::MediaCmd(cmd) => self.emit(Event::MediaCmd { cmd }),
            Frame::Notifs(list) => {
                *self.notifs.lock().unwrap() = list.clone();
                self.emit(Event::Notifs { list });
            }
            Frame::StopRing => self.emit(Event::StopRing),
            Frame::PushToken(t) => self.db(|s| s.set_kv("push_token", t.as_bytes()))?,
            Frame::StayConnected(b) => link.activity.peer_stay.store(b, Ordering::SeqCst),
            Frame::Cancel { id } => self.on_cancel(link, id).await?,
            Frame::App { channel, data } if channel == APP_VERSION => self.db(|s| s.set_kv("peer_app", data.as_bytes()))?,
            Frame::App { channel, data } => {
                self.emit(Event::App { id: None, channel, data, from_me: false, view: false })
            }
        }
        Ok(())
    }

    async fn on_cancel(&self, link: &Link, id: Uuid) -> Result<()> {
        match self.db(|s| s.get(id))? {
            // My file: the peer confirms it dropped it.
            Some(m) if m.from_me => {
                if m.state == State::Cancelling
                    && let Some(m) = self.db(|s| s.set_state(id, State::Cancelled))?
                {
                    self.emit(Event::Message(m));
                }
            }
            // It finished before the cancel got here; the ack tells the sender so.
            Some(m) if m.state == State::Received => {
                let _ = link.tx.send(Frame::Ack { id });
            }
            m => {
                if m.is_some_and(|m| m.state == State::Incoming) {
                    if let Some(m) = self.db(|s| s.set_state(id, State::Cancelled))? {
                        self.emit(Event::Message(m));
                    }
                    // A running receive notices the state and deletes the part itself.
                    if !self.receiving.lock().unwrap().contains(&id) {
                        let _ = tokio::fs::remove_file(files::part_path(&self.cfg.download_dir, id)).await;
                    }
                }
                let _ = link.tx.send(Frame::Cancel { id });
            }
        }
        Ok(())
    }

    /// A `FileWant` for something that isn't a file of mine (true: nothing to stream). Logged once
    /// per id and otherwise ignored: the item stays queued until the peer reads it right (an older
    /// phone kept a drop the laptop had resent as a nameless file; its update replaces that row,
    /// applies the drop and acks it).
    fn odd_want(&self, id: Uuid) -> Result<bool> {
        let msg = self.db(|s| s.get(id))?;
        if msg.as_ref().is_some_and(|m| m.from_me && m.kind == "file") {
            return Ok(false);
        }
        if self.odd_wants.lock().unwrap().insert(id) {
            let what = msg.map_or("unknown".to_string(), |m| format!("{} ({:?}, from_me {})", m.kind, m.state, m.from_me));
            warn!("the peer asked for the bytes of {id}, which isn't a file of mine: {what}");
        }
        Ok(true)
    }

    /// Gives up on a file of mine that can't be sent: cancelled like [`Node::cancel`], so the
    /// peer drops its part, plus [`Event::SendFailed`] with the reason. Only once per file, though
    /// two links can ask for it at once.
    fn fail_send(&self, msg: &Message, reason: &str) -> Result<()> {
        let Some(m) = self.db(|s| s.cancel(msg.id))? else { return Ok(()) };
        warn!("file {} can't be sent: {reason}", msg.id);
        self.emit(Event::Message(m));
        self.send_live(Frame::Cancel { id: msg.id });
        let name = msg.file_name.clone().unwrap_or_else(|| "file".into());
        self.emit(Event::SendFailed { id: msg.id, name, reason: reason.into() });
        Ok(())
    }

    fn still(&self, id: Uuid, state: State) -> Result<bool> {
        Ok(self.db(|s| s.get(id))?.is_some_and(|m| m.state == state))
    }

    async fn part_len(&self, id: Uuid) -> u64 {
        let part = files::part_path(&self.cfg.download_dir, id);
        tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0)
    }

    /// Sender side of a file: a uni stream with a [`FileHeader`], then the bytes from `offset`.
    async fn stream_file(&self, link: &Link, id: Uuid, offset: u64) -> Result<()> {
        let msg = self
            .db(|s| s.get(id))?
            .filter(|m| m.from_me && m.kind == "file" && m.state == State::Queued)
            .context("not a queued file of mine")?;
        // A queued file with no path can never be sent: fail it once instead of on every link.
        let Some(path) = msg.path.clone() else {
            self.fail_send(&msg, "the file has no local copy")?;
            bail!("file {id} has no local path");
        };
        let size = msg.file_size.unwrap_or(0);
        let mut f = match tokio::fs::File::open(&path).await {
            Ok(f) => f,
            Err(e) => {
                self.fail_send(&msg, "the file is gone")?;
                return Err(e).with_context(|| format!("open {}", path.display()));
            }
        };
        let offset = offset.min(size);
        f.seek(SeekFrom::Start(offset)).await?;
        let _transfer = link.activity.transfer();
        let mut s = link.conn.open_uni().await?;
        write_msg(&mut s, &FileHeader { id, offset }).await?;
        let mut meter = Meter::new(id, size);
        let mut done = offset;
        meter.tick(self, done);
        let mut buf = vec![0u8; CHUNK];
        while done < size {
            if !self.still(id, State::Queued)? {
                let _ = s.reset(RESET_CANCELLED.into());
                return Ok(());
            }
            let want = buf.len().min((size - done) as usize);
            let n = f.read(&mut buf[..want]).await?;
            if n == 0 {
                let _ = s.reset(RESET_CANCELLED.into());
                self.fail_send(&msg, "the file changed while sending")?;
                bail!("{} shrank while sending", path.display());
            }
            s.write_all(&buf[..n]).await?;
            done += n as u64;
            link.activity.touch();
            meter.tick(self, done);
        }
        s.finish()?;
        s.stopped().await?;
        Ok(())
    }

    async fn receive_file(self: Arc<Self>, link: Link, mut r: RecvStream) {
        let mut id = None;
        let res = self.try_receive(&link, &mut r, &mut id).await;
        let Some(id) = id else {
            if let Err(e) = res {
                warn!("bad file stream: {e:#}");
            }
            return;
        };
        if let Err(e) = res {
            // The link may still be fine (e.g. a duplicate stream lost the race), or a new one may
            // have replaced it after the sender's resend came in while this part still counted as
            // receiving: ask again, on the current link. The wait also lets a cancel arrive, which
            // can trail the sender's stream reset.
            tokio::time::sleep(Duration::from_secs(1)).await;
            if self.still(id, State::Cancelled).unwrap_or(false) {
                debug!("file {id} cancelled by the sender");
                let _ = tokio::fs::remove_file(files::part_path(&self.cfg.download_dir, id)).await;
                return;
            }
            warn!("receiving file {id} stopped: {e:#}");
            let still_incoming = self
                .db(|s| s.get(id))
                .ok()
                .flatten()
                .is_some_and(|m| m.state == State::Incoming);
            if still_incoming && !self.receiving.lock().unwrap().contains(&id) {
                let offset = self.part_len(id).await;
                self.send_live(Frame::FileWant { id, offset });
            }
        }
    }

    async fn try_receive(&self, link: &Link, r: &mut RecvStream, id_out: &mut Option<Uuid>) -> Result<()> {
        let hdr: FileHeader = read_msg(r).await?.context("empty file stream")?;
        let msg = self
            .db(|s| s.get(hdr.id))?
            .filter(|m| !m.from_me && m.state == State::Incoming)
            .context("file stream for an unknown item")?;
        if !self.receiving.lock().unwrap().insert(hdr.id) {
            let _ = r.stop(0u32.into());
            return Ok(());
        }
        let _receiving = ReceivingGuard(self, hdr.id);
        *id_out = Some(hdr.id);
        let _transfer = link.activity.transfer();

        let size = msg.file_size.unwrap_or(0);
        let part = files::part_path(&self.cfg.download_dir, hdr.id);
        let mut f = tokio::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(&part)
            .await?;
        let have = f.metadata().await?.len();
        if have < hdr.offset {
            bail!("sender resumed at {} but only {have} bytes are here", hdr.offset);
        }
        f.set_len(hdr.offset).await?;
        f.seek(SeekFrom::Start(hdr.offset)).await?;

        let mut meter = Meter::new(hdr.id, size);
        let mut done = hdr.offset;
        meter.tick(self, done);
        let mut buf = vec![0u8; CHUNK];
        while done < size {
            if !self.still(hdr.id, State::Incoming)? {
                let _ = r.stop(0u32.into());
                bail!("cancelled");
            }
            let want = buf.len().min((size - done) as usize);
            let Some(n) = r.read(&mut buf[..want]).await? else { break };
            f.write_all(&buf[..n]).await?;
            done += n as u64;
            link.activity.touch();
            meter.tick(self, done);
        }
        f.flush().await?;
        drop(f);
        if done < size {
            bail!("stream ended at {done} of {size} bytes");
        }

        let (hash, len) = files::sha256_file(&part).await?;
        if Some(hash) != msg.sha256 || len != size {
            // Start over on the next connect, when the sender offers the item again.
            tokio::fs::remove_file(&part).await.ok();
            warn!("file {} failed its checksum; discarded", hdr.id);
            return Ok(());
        }
        let name = msg.file_name.as_deref().unwrap_or("file");
        let dest = match &msg.channel {
            // A channel's file stays with Jarvis's state, out of Downloads.
            Some(ch) => {
                let dir = self.cfg.state_dir.join("channels").join(files::safe_name(ch));
                tokio::fs::create_dir_all(&dir).await?;
                files::unique_path(&dir, name)
            }
            None => files::unique_path(&self.cfg.download_dir, name),
        };
        if tokio::fs::rename(&part, &dest).await.is_err() {
            // Another filesystem (Downloads elsewhere than the state).
            tokio::fs::copy(&part, &dest).await?;
            tokio::fs::remove_file(&part).await.ok();
        }
        // A cancel that came in after the last chunk has been confirmed to the sender already.
        let Some(m) = self.db(|s| s.received(hdr.id, &dest))? else {
            tokio::fs::remove_file(&dest).await.ok();
            return Ok(());
        };
        self.emit(Event::Message(m));
        self.send_live(Frame::Ack { id: hdr.id });
        Ok(())
    }

    async fn serve_pair(&self, conn: Connection) -> Result<()> {
        let (mut send, mut recv) = timeout(Duration::from_secs(10), conn.accept_bi()).await??;
        let req = timeout(Duration::from_secs(10), read_msg::<PairRequest>(&mut recv))
            .await??
            .context("no pairing request")?;
        let PairRequest::Hello { token, name } = req;
        let ok = {
            let mut offer = self.offer.lock().unwrap();
            match *offer {
                Some((t, until)) if Instant::now() < until && ct_eq(&t, &token) => {
                    *offer = None;
                    true
                }
                _ => false,
            }
        };
        // Store the peer before replying, so its first dial after pairing is accepted.
        let reply = if ok {
            let peer = self.set_peer(conn.remote_id(), &name)?;
            info!("paired with {} ({})", peer.name, peer.id);
            PairReply::Accepted { name: self.cfg.name.clone() }
        } else {
            PairReply::Rejected
        };
        write_msg(&mut send, &reply).await?;
        send.finish()?;
        // Let the reply arrive; the dialer closes once it has read it.
        let _ = timeout(Duration::from_secs(5), conn.closed()).await;
        if !ok {
            bail!("wrong or expired pairing code");
        }
        Ok(())
    }

    async fn pair(&self, code: &str) -> Result<PeerInfo> {
        let (id, token) = parse_code(code)?;
        let conn = timeout(CONNECT_TIMEOUT, self.ep.connect(id, PAIR_ALPN))
            .await
            .context("connect timed out")??;
        let res = async {
            let (mut send, mut recv) = conn.open_bi().await?;
            write_msg(&mut send, &PairRequest::Hello { token, name: self.cfg.name.clone() }).await?;
            send.finish()?;
            timeout(Duration::from_secs(10), read_msg::<PairReply>(&mut recv))
                .await??
                .context("no pairing reply")
        }
        .await;
        conn.close(CLOSE_BYE.into(), b"done");
        match res? {
            PairReply::Accepted { name } => self.set_peer(id, &name),
            PairReply::Rejected => bail!("pairing code rejected (expired or already used)"),
        }
    }
}

struct ReceivingGuard<'a>(&'a Inner, Uuid);

impl Drop for ReceivingGuard<'_> {
    fn drop(&mut self) {
        self.0.receiving.lock().unwrap().remove(&self.1);
    }
}

/// Throttles progress events to a few per second.
struct Meter {
    id: Uuid,
    total: u64,
    last: Option<Instant>,
}

impl Meter {
    fn new(id: Uuid, total: u64) -> Self {
        Self { id, total, last: None }
    }

    fn tick(&mut self, inner: &Inner, done: u64) {
        let due = self.last.is_none_or(|t| t.elapsed() >= Duration::from_millis(250));
        if due || done == self.total {
            self.last = Some(Instant::now());
            inner.emit(Event::Progress { id: self.id, done, total: self.total });
        }
    }
}

fn ct_eq(a: &[u8; 32], b: &[u8; 32]) -> bool {
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub fn parse_code(code: &str) -> Result<(EndpointId, [u8; 32])> {
    let parts: Vec<&str> = code.trim().split(':').collect();
    let [word, "1", id, token] = parts[..] else {
        bail!("not a jarvis pairing code");
    };
    ensure!(word == crate::compat::PAIR_WORD, "not a jarvis pairing code");
    let id: EndpointId = id.parse().context("bad endpoint id in pairing code")?;
    let token = BASE32_NOPAD
        .decode(token.as_bytes())
        .ok()
        .and_then(|t| <[u8; 32]>::try_from(t).ok())
        .context("bad token in pairing code")?;
    Ok((id, token))
}

#[cfg(test)]
mod tests;

/// Logs the open paths of a link (direct IP or relay, the selected one marked) and their RTT whenever
/// the set or the selection changes, so a slow transfer can be told apart from a bad route.
/// Polls because it only needs to catch changes.
async fn log_paths(conn: Connection, serial: u64) {
    let mut last: Option<String> = None;
    while conn.close_reason().is_none() {
        let mut paths: Vec<String> = conn
            .paths()
            .iter()
            .map(|p| {
                let kind = if p.is_relay() { "relay" } else { "direct" };
                let sel = if p.is_selected() { "*" } else { "" };
                format!("{sel}{kind} {:?} {} ms", p.remote_addr(), p.rtt().as_millis())
            })
            .collect();
        paths.sort();
        // RTTs jitter; the set and the selection are what matter.
        let key: Vec<&str> = paths.iter().map(|p| p.rsplitn(3, ' ').nth(2).unwrap_or(p)).collect();
        let key = key.join(", ");
        if last.as_deref() != Some(key.as_str()) {
            info!(serial, "paths: {}", paths.join(", "));
            last = Some(key);
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
}
