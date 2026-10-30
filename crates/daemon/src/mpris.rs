//! The phone's media session as an MPRIS player, `org.mpris.MediaPlayer2.jarvis.pixel`, so the bar
//! widget, playerctl and media keys control the phone. The bus name is held only while the phone
//! reports a session; the phone sends state on every change and the position is extrapolated here.

use std::{
    collections::HashMap,
    hash::{DefaultHasher, Hash, Hasher},
    sync::{Arc, Mutex},
    time::Instant,
};

use anyhow::Result;
use jarvis_core::{
    node::{Event, Node},
    proto::{Frame, MediaCmd, MediaState},
};
use tokio::sync::broadcast::error::RecvError;
use tracing::{debug, info, warn};
use zbus::{
    fdo, interface,
    object_server::SignalEmitter,
    zvariant::{ObjectPath, OwnedObjectPath, OwnedValue, Value},
};

pub const BUS_NAME: &str = "org.mpris.MediaPlayer2.jarvis.pixel";
const PATH: &str = "/org/mpris/MediaPlayer2";
/// A reported position this far from the extrapolated one counts as a seek.
const SEEK_SLACK_MS: i64 = 1500;

/// The last state the phone sent and when it arrived.
#[derive(Clone)]
struct Snap {
    state: MediaState,
    at: Instant,
}

impl Snap {
    fn position_ms(&self, now: Instant) -> i64 {
        let s = &self.state;
        let mut pos = s.position_ms;
        if s.playing {
            pos += now.saturating_duration_since(self.at).as_millis() as i64;
        }
        if s.duration_ms > 0 { pos.clamp(0, s.duration_ms) } else { pos.max(0) }
    }

    fn track_id(&self) -> OwnedObjectPath {
        let mut h = DefaultHasher::new();
        (&self.state.player, &self.state.title, &self.state.artist, &self.state.album).hash(&mut h);
        let path = format!("/org/mpris/MediaPlayer2/jarvis/track/t{:016x}", h.finish());
        ObjectPath::try_from(path).expect("valid object path").into()
    }

    fn same_track(&self, other: &MediaState) -> bool {
        let s = &self.state;
        (&s.player, &s.title, &s.artist, &s.album) == (&other.player, &other.title, &other.artist, &other.album)
    }
}

type Shared = Arc<Mutex<Option<Snap>>>;

struct Root;

#[interface(name = "org.mpris.MediaPlayer2")]
impl Root {
    fn raise(&self) {}
    fn quit(&self) {}
    #[zbus(property)]
    fn can_quit(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn can_raise(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn has_track_list(&self) -> bool {
        false
    }
    #[zbus(property)]
    fn identity(&self) -> &str {
        "Pixel 8"
    }
    #[zbus(property)]
    fn supported_uri_schemes(&self) -> Vec<String> {
        Vec::new()
    }
    #[zbus(property)]
    fn supported_mime_types(&self) -> Vec<String> {
        Vec::new()
    }
}

struct Player {
    node: Node,
    snap: Shared,
}

impl Player {
    fn get(&self) -> Option<Snap> {
        self.snap.lock().unwrap().clone()
    }

    fn state<T>(&self, f: impl FnOnce(&MediaState) -> T, default: T) -> T {
        self.get().map_or(default, |s| f(&s.state))
    }

    fn cmd(&self, cmd: MediaCmd) {
        if !self.node.send_live(Frame::MediaCmd(cmd)) {
            debug!("media command dropped: phone not connected");
        }
    }
}

#[interface(name = "org.mpris.MediaPlayer2.Player")]
impl Player {
    fn next(&self) {
        self.cmd(MediaCmd::Next);
    }
    fn previous(&self) {
        self.cmd(MediaCmd::Previous);
    }
    fn pause(&self) {
        self.cmd(MediaCmd::Pause);
    }
    fn play_pause(&self) {
        self.cmd(MediaCmd::PlayPause);
    }
    fn stop(&self) {
        self.cmd(MediaCmd::Pause);
    }
    fn play(&self) {
        self.cmd(MediaCmd::Play);
    }
    /// `offset` is in microseconds, relative to the current position.
    fn seek(&self, offset: i64) {
        if let Some(s) = self.get() {
            let pos = s.position_ms(Instant::now()) + offset / 1000;
            if pos > s.state.duration_ms && s.state.duration_ms > 0 {
                self.cmd(MediaCmd::Next);
            } else {
                self.cmd(MediaCmd::Seek { position_ms: pos.max(0) });
            }
        }
    }
    fn set_position(&self, track_id: ObjectPath<'_>, position: i64) {
        if let Some(s) = self.get()
            && s.track_id().as_ref() == track_id
            && (0..=s.state.duration_ms * 1000).contains(&position)
        {
            self.cmd(MediaCmd::Seek { position_ms: position / 1000 });
        }
    }
    fn open_uri(&self, _uri: &str) -> fdo::Result<()> {
        Err(fdo::Error::NotSupported("the phone opens nothing from here".into()))
    }

    #[zbus(signal)]
    async fn seeked(emitter: &SignalEmitter<'_>, position: i64) -> zbus::Result<()>;

    #[zbus(property)]
    fn playback_status(&self) -> &str {
        match self.get() {
            Some(s) if s.state.playing => "Playing",
            Some(_) => "Paused",
            None => "Stopped",
        }
    }
    #[zbus(property)]
    fn rate(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn set_rate(&self, _rate: f64) {}
    #[zbus(property)]
    fn minimum_rate(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn maximum_rate(&self) -> f64 {
        1.0
    }
    #[zbus(property)]
    fn metadata(&self) -> HashMap<String, OwnedValue> {
        self.get().map(|s| metadata(&s)).unwrap_or_default()
    }
    #[zbus(property)]
    fn volume(&self) -> f64 {
        self.state(|s| s.volume.map_or(1.0, |v| f64::from(v) / 100.0), 1.0)
    }
    #[zbus(property)]
    fn set_volume(&self, volume: f64) {
        if self.state(|s| s.volume.is_some(), false) {
            self.cmd(MediaCmd::Volume((volume.clamp(0.0, 1.0) * 100.0).round() as u8));
        }
    }
    #[zbus(property(emits_changed_signal = "false"))]
    fn position(&self) -> i64 {
        self.get().map_or(0, |s| s.position_ms(Instant::now()) * 1000)
    }
    #[zbus(property)]
    fn can_go_next(&self) -> bool {
        self.state(|s| s.can_next, false)
    }
    #[zbus(property)]
    fn can_go_previous(&self) -> bool {
        self.state(|s| s.can_previous, false)
    }
    #[zbus(property)]
    fn can_play(&self) -> bool {
        self.get().is_some()
    }
    #[zbus(property)]
    fn can_pause(&self) -> bool {
        self.get().is_some()
    }
    #[zbus(property)]
    fn can_seek(&self) -> bool {
        self.state(|s| s.can_seek && s.duration_ms > 0, false)
    }
    #[zbus(property)]
    fn can_control(&self) -> bool {
        true
    }
}

fn metadata(s: &Snap) -> HashMap<String, OwnedValue> {
    let st = &s.state;
    let mut m = HashMap::new();
    let mut put = |k: &str, v: Value<'_>| {
        if let Ok(v) = OwnedValue::try_from(v) {
            m.insert(k.to_string(), v);
        }
    };
    put("mpris:trackid", Value::from(s.track_id()));
    if st.duration_ms > 0 {
        put("mpris:length", Value::from(st.duration_ms * 1000));
    }
    put("xesam:title", Value::from(st.title.as_str()));
    if !st.artist.is_empty() {
        put("xesam:artist", Value::from(vec![st.artist.as_str()]));
    }
    if !st.album.is_empty() {
        put("xesam:album", Value::from(st.album.as_str()));
    }
    // The app playing on the phone (Spotify, YouTube); shown by players that read it.
    put("jarvis:app", Value::from(st.player.as_str()));
    m
}

/// Serves the player until the node's event stream ends. Without a session bus it logs and stops.
pub async fn run(node: Node) {
    if let Err(e) = serve(node).await {
        warn!("MPRIS off: {e:#}");
    }
}

async fn serve(node: Node) -> Result<()> {
    let snap: Shared = Arc::default();
    let mut rx = node.events();
    let conn = zbus::connection::Builder::session()?
        .serve_at(PATH, Root)?
        .serve_at(PATH, Player { node: node.clone(), snap: snap.clone() })?
        .build()
        .await?;
    let iface = conn.object_server().interface::<_, Player>(PATH).await?;
    let mut owned = false;
    // State may have arrived before we subscribed.
    let mut next = Some(node.media());
    loop {
        let state = match next.take() {
            Some(s) => s,
            None => match rx.recv().await {
                Ok(Event::Media { state }) => state,
                Ok(_) | Err(RecvError::Lagged(_)) => continue,
                Err(RecvError::Closed) => return Ok(()),
            },
        };
        let now = Instant::now();
        let (track_changed, seeked) = {
            let mut cur = snap.lock().unwrap();
            let changes = match (cur.as_ref(), state.as_ref()) {
                (Some(old), Some(new)) => (
                    !old.same_track(new),
                    old.same_track(new) && (old.position_ms(now) - new.position_ms).abs() > SEEK_SLACK_MS,
                ),
                _ => (true, false),
            };
            *cur = state.clone().map(|state| Snap { state, at: now });
            changes
        };
        match (&state, owned) {
            (Some(_), false) => {
                conn.request_name(BUS_NAME).await?;
                owned = true;
                info!("phone media session up");
                continue;
            }
            (None, true) => {
                conn.release_name(BUS_NAME).await?;
                owned = false;
                info!("phone media session gone");
                continue;
            }
            (None, false) => continue,
            (Some(_), true) => {}
        }
        let p = iface.get().await;
        let e = iface.signal_emitter();
        p.playback_status_changed(e).await?;
        p.volume_changed(e).await?;
        p.can_go_next_changed(e).await?;
        p.can_go_previous_changed(e).await?;
        p.can_seek_changed(e).await?;
        if track_changed {
            p.metadata_changed(e).await?;
        }
        if seeked && let Some(pos) = state.as_ref().map(|s| s.position_ms) {
            Player::seeked(e, pos * 1000).await?;
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    fn state(playing: bool) -> MediaState {
        MediaState {
            player: "Spotify".into(),
            title: "Song".into(),
            artist: "Band".into(),
            album: String::new(),
            playing,
            position_ms: 10_000,
            duration_ms: 12_000,
            volume: Some(50),
            can_seek: true,
            can_next: true,
            can_previous: false,
        }
    }

    #[test]
    fn position_runs_only_while_playing_and_stops_at_the_end() {
        let at = Instant::now();
        let later = at + Duration::from_secs(1);
        assert_eq!(Snap { state: state(true), at }.position_ms(later), 11_000);
        assert_eq!(Snap { state: state(false), at }.position_ms(later), 10_000);
        assert_eq!(Snap { state: state(true), at }.position_ms(at + Duration::from_secs(9)), 12_000);
    }

    #[test]
    fn metadata_has_the_track() {
        let s = Snap { state: state(true), at: Instant::now() };
        let m = metadata(&s);
        assert_eq!(m["mpris:length"], OwnedValue::from(12_000_000i64));
        assert_eq!(String::try_from(m["xesam:title"].clone()).unwrap(), "Song");
        assert!(!m.contains_key("xesam:album"));
        let other = Snap { state: MediaState { title: "Other".into(), ..state(true) }, at: Instant::now() };
        assert_ne!(s.track_id(), other.track_id());
        assert_eq!(s.track_id(), Snap { state: state(false), at: Instant::now() }.track_id());
    }
}
