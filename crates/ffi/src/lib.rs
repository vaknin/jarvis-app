//! uniffi bindings of [`tether_core::node::Node`] for the Android app.
//!
//! The library owns one multi-threaded tokio runtime. Async exports run their work on it and are
//! awaited from Kotlin coroutines. The app keeps one node running for as long as its process lives
//! (a foreground service), so the laptop can always dial it; the link itself closes when idle.
//! [`forum`] is the forum tunnel, a separate endpoint; [`voice`] is a voice call with Jarvis through it.

mod alerts;
mod forum;
mod voice;

use std::{
    path::PathBuf,
    sync::{Arc, LazyLock, Mutex},
    time::Duration,
};

use tether_core::{
    node::{self, Config, Net, Node},
    proto::{self, Frame},
    store::{self, State},
};
use tokio::{runtime::Runtime, sync::broadcast::error::RecvError, task::JoinHandle};

uniffi::setup_scaffolding!();

static RT: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("tether")
        .build()
        .expect("tokio runtime")
});

#[derive(Debug, uniffi::Error)]
pub enum TetherError {
    Failed { reason: String },
}

impl std::fmt::Display for TetherError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TetherError::Failed { reason } => f.write_str(reason),
        }
    }
}

impl std::error::Error for TetherError {}

impl From<anyhow::Error> for TetherError {
    fn from(e: anyhow::Error) -> Self {
        TetherError::Failed { reason: format!("{e:#}") }
    }
}

type Res<T> = Result<T, TetherError>;

/// Runs `fut` on the library's runtime; iroh and the node's tasks need a tokio context.
async fn on_rt<T: Send + 'static>(fut: impl Future<Output = T> + Send + 'static) -> T {
    RT.spawn(fut).await.expect("tether task panicked")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MsgState {
    Queued,
    Delivered,
    Expired,
    Incoming,
    Received,
    /// Stopped by its sender (`Node::cancel`), confirmed or not.
    Cancelled,
}

impl From<State> for MsgState {
    fn from(s: State) -> Self {
        match s {
            State::Queued => MsgState::Queued,
            State::Delivered => MsgState::Delivered,
            State::Expired => MsgState::Expired,
            State::Incoming => MsgState::Incoming,
            State::Received => MsgState::Received,
            State::Cancelling | State::Cancelled => MsgState::Cancelled,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum MsgKind {
    Text,
    File,
    Ping,
    Ring,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ChatMessage {
    pub id: String,
    pub from_me: bool,
    pub ts_ms: i64,
    pub kind: MsgKind,
    /// The text of a text message or ping.
    pub text: Option<String>,
    pub file_name: Option<String>,
    pub file_size: Option<u64>,
    /// Local file: the source of a file I send, the saved file of one I receive.
    pub path: Option<String>,
    pub state: MsgState,
    pub read: bool,
    /// A channel's file (a photo sent to dibs): not part of the chat.
    pub channel: Option<String>,
}

impl From<store::Message> for ChatMessage {
    fn from(m: store::Message) -> Self {
        ChatMessage {
            id: m.id.to_string(),
            from_me: m.from_me,
            ts_ms: m.ts_ms,
            kind: match m.kind {
                "text" => MsgKind::Text,
                "ping" => MsgKind::Ping,
                "ring" => MsgKind::Ring,
                _ => MsgKind::File,
            },
            text: m.text,
            file_name: m.file_name,
            file_size: m.file_size,
            path: m.path.map(|p| p.to_string_lossy().into_owned()),
            state: m.state.into(),
            read: m.read,
            channel: m.channel,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
}

impl From<node::PeerInfo> for PeerInfo {
    fn from(p: node::PeerInfo) -> Self {
        PeerInfo { id: p.id, name: p.name }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Status {
    pub id: String,
    pub name: String,
    pub peer: Option<PeerInfo>,
    pub connected: bool,
    pub queued: u64,
    /// When the oldest queued item was queued (ms since the epoch); none while nothing is queued.
    pub oldest_queued_ms: Option<i64>,
    pub unread: u64,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum MediaCmd {
    Play,
    Pause,
    PlayPause,
    Next,
    Previous,
    Seek { position_ms: i64 },
    Volume { percent: u8 },
}

impl From<proto::MediaCmd> for MediaCmd {
    fn from(c: proto::MediaCmd) -> Self {
        match c {
            proto::MediaCmd::Play => MediaCmd::Play,
            proto::MediaCmd::Pause => MediaCmd::Pause,
            proto::MediaCmd::PlayPause => MediaCmd::PlayPause,
            proto::MediaCmd::Next => MediaCmd::Next,
            proto::MediaCmd::Previous => MediaCmd::Previous,
            proto::MediaCmd::Seek { position_ms } => MediaCmd::Seek { position_ms },
            proto::MediaCmd::Volume(percent) => MediaCmd::Volume { percent },
        }
    }
}

/// The phone's active media session, sent to the laptop on every change.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MediaState {
    /// The app playing (Spotify, YouTube).
    pub player: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    /// Position now, not at the session's last update.
    pub position_ms: i64,
    pub duration_ms: i64,
    /// 0–100, `None` when the volume can't be changed.
    pub volume: Option<u8>,
    pub can_seek: bool,
    pub can_next: bool,
    pub can_previous: bool,
}

impl From<MediaState> for proto::MediaState {
    fn from(m: MediaState) -> Self {
        proto::MediaState {
            player: m.player,
            title: m.title,
            artist: m.artist,
            album: m.album,
            playing: m.playing,
            position_ms: m.position_ms,
            duration_ms: m.duration_ms,
            volume: m.volume.map(|v| v.min(100)),
            can_seek: m.can_seek,
            can_next: m.can_next,
            can_previous: m.can_previous,
        }
    }
}

/// One active notification, mirrored to the laptop.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PhoneNotif {
    pub key: String,
    pub app: String,
    pub title: String,
    pub text: String,
    pub posted_ms: i64,
}

impl From<PhoneNotif> for proto::PhoneNotif {
    fn from(n: PhoneNotif) -> Self {
        proto::PhoneNotif { key: n.key, app: n.app, title: n.title, text: n.text, posted_ms: n.posted_ms }
    }
}

/// The node's events, minus the laptop-only ones (the phone's own media and notifications).
#[derive(Debug, Clone, uniffi::Enum)]
pub enum Event {
    Connected,
    Disconnected,
    Paired { peer: PeerInfo },
    Unpaired,
    Message { msg: ChatMessage },
    Progress { id: String, done: u64, total: u64 },
    MediaCmd { cmd: MediaCmd },
    StopRing,
    /// The laptop closed the link because it no longer takes this phone as its pair.
    Refused,
    /// A file of mine couldn't be sent (gone or changed); it is cancelled.
    SendFailed { id: String, name: String, reason: String },
    /// From the laptop on an app channel: a queued item (`id` set; mark it with `app_done`), a
    /// view (`view`, `id` set: the channel's new state, nothing to mark; read it with `app_view`),
    /// or a live message (`id` none).
    App { id: Option<String>, channel: String, data: String, view: bool },
}

/// A received app item its handler hasn't marked done.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AppItem {
    pub id: String,
    pub data: String,
}

/// One item of a channel's thread, either way.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AppHistoryItem {
    pub id: String,
    pub data: String,
    pub from_me: bool,
    pub ts_ms: i64,
}

impl Event {
    fn from_core(e: node::Event) -> Option<Self> {
        Some(match e {
            node::Event::Connected => Event::Connected,
            node::Event::Disconnected => Event::Disconnected,
            node::Event::Paired { peer } => Event::Paired { peer: peer.into() },
            node::Event::Unpaired => Event::Unpaired,
            node::Event::Message(m) => Event::Message { msg: m.into() },
            node::Event::Progress { id, done, total } => {
                Event::Progress { id: id.to_string(), done, total }
            }
            node::Event::MediaCmd { cmd } => Event::MediaCmd { cmd: cmd.into() },
            node::Event::StopRing => Event::StopRing,
            node::Event::Refused => Event::Refused,
            node::Event::SendFailed { id, name, reason } => Event::SendFailed { id: id.to_string(), name, reason },
            node::Event::App { id, channel, data, from_me: false, view } => {
                Event::App { id: id.map(|i| i.to_string()), channel, data, view }
            }
            node::Event::App { from_me: true, .. } => return None,
            node::Event::Media { .. } | node::Event::Notifs { .. } | node::Event::Read => return None,
        })
    }
}

/// Implemented in Kotlin. Called on a runtime thread, in order.
#[uniffi::export(callback_interface)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, event: Event);
}

#[derive(uniffi::Object)]
pub struct TetherNode {
    node: Node,
    listener: Mutex<Option<JoinHandle<()>>>,
}

/// Starts the phone's node: n0 relays + mDNS, a random port, and the core's redial loop, which
/// dials only while something is queued for the laptop.
#[uniffi::export]
/// `app_version` is told to the laptop on every link (`tether status --json`'s `phone_app`).
pub async fn start(state_dir: String, download_dir: String, name: String, app_version: String) -> Res<Arc<TetherNode>> {
    init_logging();
    on_rt(async move {
        let mut cfg = Config::new(PathBuf::from(state_dir), PathBuf::from(download_dir), name);
        cfg.net = Net::Internet { port: None };
        cfg.app_version = Some(app_version);
        let node = Node::start(cfg).await?;
        Ok(Arc::new(TetherNode { node, listener: Mutex::new(None) }))
    })
    .await
}

#[uniffi::export]
impl TetherNode {
    /// Delivers every later event to `listener`, replacing any earlier one.
    pub fn set_listener(&self, listener: Box<dyn EventListener>) {
        let mut rx = self.node.events();
        let task = RT.spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(e) => {
                        if let Some(e) = Event::from_core(e) {
                            listener.on_event(e);
                        }
                    }
                    Err(RecvError::Lagged(n)) => tracing::warn!("listener lagged by {n} events"),
                    Err(RecvError::Closed) => return,
                }
            }
        });
        if let Some(old) = self.listener.lock().unwrap().replace(task) {
            old.abort();
        }
    }

    pub fn status(&self) -> Res<Status> {
        let s = self.node.status()?;
        Ok(Status {
            id: s.id,
            name: s.name,
            peer: s.peer.map(Into::into),
            connected: s.connected,
            queued: s.queued as u64,
            oldest_queued_ms: s.oldest_queued_ms,
            unread: s.unread,
        })
    }

    pub fn is_connected(&self) -> bool {
        self.node.is_connected()
    }

    /// The laptop asked the link to stay open (e.g. `tether notifications --fresh`).
    pub fn peer_stays(&self) -> bool {
        self.node.peer_stays()
    }

    /// The newest `limit` messages, oldest first.
    pub fn recent(&self, limit: u32) -> Res<Vec<ChatMessage>> {
        Ok(self.node.recent(limit as usize)?.into_iter().map(Into::into).collect())
    }

    pub fn unread(&self) -> Res<u64> {
        Ok(self.node.unread()?)
    }

    pub fn mark_read(&self) -> Res<()> {
        Ok(self.node.mark_read()?)
    }

    /// Pairs with the laptop whose QR showed `code` (`tether:1:<id>:<token>`).
    pub async fn pair(&self, code: String) -> Res<PeerInfo> {
        let node = self.node.clone();
        Ok(on_rt(async move { node.pair(&code).await }).await?.into())
    }

    pub fn unpair(&self) -> Res<()> {
        Ok(self.node.unpair()?)
    }

    /// Queues a chat message and dials if not connected.
    pub fn send_text(&self, text: String) -> Res<ChatMessage> {
        let m = self.node.send_text(text)?;
        self.dial_soon();
        Ok(m.into())
    }

    pub fn send_ping(&self, text: String) -> Res<ChatMessage> {
        let m = self.node.send_ping(text)?;
        self.dial_soon();
        Ok(m.into())
    }

    /// Queues the file at `path`, which must stay in place until the message is delivered.
    /// Stops a file this phone is sending; false if it already finished (or isn't one).
    pub fn cancel(&self, id: String) -> Res<bool> {
        let id = uuid::Uuid::parse_str(&id).map_err(anyhow::Error::from)?;
        Ok(self.node.cancel(id)?)
    }

    pub async fn send_file(&self, path: String) -> Res<ChatMessage> {
        let node = self.node.clone();
        let m = on_rt(async move { node.send_file(std::path::Path::new(&path)).await }).await?;
        self.dial_soon();
        Ok(m.into())
    }

    /// Queues the file at `path` for an app channel (a photo sent to dibs): it travels like a chat
    /// file, but the laptop keeps it for the channel's app. `path` must stay until it's delivered.
    pub async fn send_channel_file(&self, channel: String, path: String) -> Res<ChatMessage> {
        let node = self.node.clone();
        let m = on_rt(async move { node.send_channel_file(&channel, std::path::Path::new(&path)).await }).await?;
        self.dial_soon();
        Ok(m.into())
    }

    /// The channel's last `limit` files, both ways and in any state, oldest first (a received
    /// one's `path` is where it was kept, under `<state>/channels/<channel>/`).
    pub fn app_files(&self, channel: String, limit: u32) -> Res<Vec<ChatMessage>> {
        Ok(self.node.app_files(&channel, limit as usize)?.into_iter().map(Into::into).collect())
    }

    /// Queues an item for the laptop on an app channel (with `replace`, a view) and dials if not
    /// connected; returns its id.
    pub fn send_app(&self, channel: String, data: String, replace: bool) -> Res<String> {
        let id = self.node.send_app(&channel, data, replace)?;
        self.dial_soon();
        Ok(id.to_string())
    }

    /// A live app message; false when there is no link.
    pub fn send_app_live(&self, channel: String, data: String) -> bool {
        self.node.send_app_live(&channel, data)
    }

    pub fn app_pending(&self, channel: String) -> Res<Vec<AppItem>> {
        let items = self.node.app_pending(&channel)?;
        Ok(items.into_iter().map(|(id, data)| AppItem { id: id.to_string(), data }).collect())
    }

    pub fn app_done(&self, id: String) -> Res<()> {
        let id = id.parse().map_err(|e: uuid::Error| TetherError::Failed { reason: e.to_string() })?;
        Ok(self.node.app_done(id)?)
    }

    /// The channel's newest view (the laptop app's state, JSON), if any.
    pub fn app_view(&self, channel: String) -> Res<Option<String>> {
        Ok(self.node.app_view(&channel)?)
    }

    /// The channel's last `limit` items both ways (a thread), oldest first; no views.
    pub fn app_history(&self, channel: String, limit: u32) -> Res<Vec<AppHistoryItem>> {
        let items = self.node.app_history(&channel, limit as usize)?;
        Ok(items
            .into_iter()
            .map(|m| AppHistoryItem {
                id: m.id.to_string(),
                data: m.text.unwrap_or_default(),
                from_me: m.from_me,
                ts_ms: m.ts_ms,
            })
            .collect())
    }

    /// Sends the FCM registration token; false when not connected.
    pub fn send_push_token(&self, token: String) -> bool {
        self.node.send_live(Frame::PushToken(token))
    }

    pub fn stop_ring(&self) -> bool {
        self.node.send_live(Frame::StopRing)
    }

    /// Live media state (`None`: no session); dropped when not connected.
    pub fn send_media(&self, state: Option<MediaState>) -> bool {
        self.node.send_live(Frame::Media(state.map(Into::into)))
    }

    /// The full list of active notifications; dropped when not connected.
    pub fn send_notifs(&self, list: Vec<PhoneNotif>) -> bool {
        self.node.send_live(Frame::Notifs(list.into_iter().map(Into::into).collect()))
    }

    /// Keeps the link open past the idle timeout while the app is in front or media plays.
    pub fn set_stay(&self, stay: bool) {
        self.node.set_stay(stay);
    }

    /// Dials the laptop unless connected. Gives up after 30 s.
    pub async fn connect(&self) -> Res<()> {
        let node = self.node.clone();
        Ok(on_rt(async move { node.connect().await }).await?)
    }

    pub fn disconnect(&self) {
        self.node.disconnect();
    }

    /// Tell iroh the phone changed networks (see [`Node::network_change`]).
    pub async fn network_change(&self) {
        let node = self.node.clone();
        on_rt(async move { node.network_change().await }).await;
    }

    /// Closes the link and the endpoint. The object is unusable afterwards; call [`start`] again.
    pub async fn shutdown(&self) {
        if let Some(t) = self.listener.lock().unwrap().take() {
            t.abort();
        }
        let node = self.node.clone();
        on_rt(async move {
            // Bounded: a hung close must not keep the wake service alive.
            let _ = tokio::time::timeout(Duration::from_secs(5), node.shutdown()).await;
        })
        .await;
    }
}

impl TetherNode {
    fn dial_soon(&self) {
        if self.node.is_connected() {
            return;
        }
        let node = self.node.clone();
        RT.spawn(async move {
            if let Err(e) = node.connect().await {
                tracing::debug!("dial after send failed: {e:#}");
            }
        });
    }
}

fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let filter = tracing_subscriber::EnvFilter::new("info,tether_core=debug,iroh=warn,netwatch=error,swarm_discovery=error");
        let b = tracing_subscriber::fmt().with_env_filter(filter).with_ansi(false);
        #[cfg(target_os = "android")]
        let b = b.without_time().with_writer(logcat::Writer::default);
        let _ = b.try_init();
    });
}

/// Minimal logcat writer: each formatted line goes to `__android_log_write` with tag "tether".
#[cfg(target_os = "android")]
mod logcat {
    use std::{ffi::CString, io};

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
    }

    const INFO: i32 = 4;

    #[derive(Default)]
    pub struct Writer(Vec<u8>);

    impl io::Write for Writer {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.0.extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    impl Drop for Writer {
        fn drop(&mut self) {
            let text: Vec<u8> = self.0.iter().copied().filter(|&b| b != 0 && b != b'\n').collect();
            if let Ok(text) = CString::new(text) {
                // SAFETY: both pointers are NUL-terminated and live for the call.
                unsafe { __android_log_write(INFO, c"tether".as_ptr().cast(), text.as_ptr().cast()) };
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Two FFI nodes can't use loopback (that's core-only), so this only checks the plumbing that
    /// needs no network: start, status, queue, recent, shutdown, restart on the same state.
    #[test]
    fn start_queue_restart() {
        let dir = std::env::temp_dir().join(format!("tether-ffi-{}", uuid::Uuid::new_v4()));
        let state = dir.join("state").to_string_lossy().into_owned();
        let dl = dir.join("dl").to_string_lossy().into_owned();
        RT.block_on(async {
            let n = start(state.clone(), dl.clone(), "Phone".into(), "0.0.0".into()).await.unwrap();
            let id = n.status().unwrap().id;
            assert!(n.status().unwrap().peer.is_none());
            // Not paired: queues fine, the background dial just fails.
            let m = n.send_text("hi".into()).unwrap();
            assert_eq!(m.state, MsgState::Queued);
            assert_eq!(m.kind, MsgKind::Text);
            n.shutdown().await;
            let n = start(state, dl, "Phone".into(), "0.0.0".into()).await.unwrap();
            assert_eq!(n.status().unwrap().id, id, "key survives a restart");
            let r = n.recent(10).unwrap();
            assert_eq!(r.len(), 1);
            assert_eq!(r[0].text.as_deref(), Some("hi"));
            n.shutdown().await;
        });
        let _ = std::fs::remove_dir_all(dir);
    }
}

/// Gives iroh the JavaVM and application context, so it reads the phone's DNS servers and network
/// interfaces over JNI instead of falling back to public resolvers. JNA loads this library with
/// `dlopen`, so `JNI_OnLoad` never runs; the app calls `Native.init(context)` once, before the
/// first [`start`].
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kivan_tether_Native_init(
    env: *mut jni_sys::JNIEnv,
    _this: jni_sys::jobject,
    context: jni_sys::jobject,
) {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| unsafe {
        let f = &(**env).v1_1;
        let mut vm: *mut jni_sys::JavaVM = std::ptr::null_mut();
        if (f.GetJavaVM)(env, &mut vm) != jni_sys::JNI_OK {
            return;
        }
        // A global reference keeps the context valid for the life of the process.
        let ctx = (f.NewGlobalRef)(env, context);
        iroh::dns::install_android_jni_context(vm.cast(), ctx.cast());
    });
}
