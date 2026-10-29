//! Voice calls with Jarvis for the app: [`tether_voice::Call`] behind a uniffi object. The app's
//! mic thread pushes 20 ms frames with `send_audio`, its speaker thread pulls with `pull_audio`
//! (blocking writes to the AudioTrack pace it), and the listener hears the call's state.

use std::sync::Arc;

use tether_voice::{Call, Events, Status, Timing, Word, websocket};

use crate::RT;

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum CallState {
    Connecting,
    Listening,
    Thinking,
    Speaking,
    Reconnecting,
    Ended { why: String },
}

impl From<Status> for CallState {
    fn from(s: Status) -> Self {
        match s {
            Status::Connecting => CallState::Connecting,
            Status::Listening => CallState::Listening,
            Status::Thinking => CallState::Thinking,
            Status::Speaking => CallState::Speaking,
            Status::Reconnecting => CallState::Reconnecting,
            Status::Ended(why) => CallState::Ended { why },
        }
    }
}

/// Implemented in Kotlin. Called on a runtime thread, in order.
#[uniffi::export(callback_interface)]
pub trait VoiceListener: Send + Sync {
    fn on_state(&self, state: CallState);
    /// What was heard (`who` "aviv") or said ("jarvis").
    fn on_text(&self, who: String, text: String);
    fn on_error(&self, text: String);
    /// Jarvis was talked over: drop what the AudioTrack still holds.
    fn on_flush(&self);
    /// Jarvis's reply is paused (a tap, "hold", or Aviv talking over him) or goes on.
    fn on_paused(&self, on: bool);
    /// (A call with `phone_stt`) the phone's recognizer opens session `id`; the call's mic audio
    /// follows as the server got it (20 ms frames, or a backlog at once), then `on_stt_close`.
    /// Answer `VoiceCall.stt_done` once the session is over, however it ended. Never block here.
    fn on_stt_open(&self, id: u64);
    fn on_stt_audio(&self, id: u64, pcm: Vec<u8>);
    /// No more audio for session `id`: the recognizer finishes it.
    fn on_stt_close(&self, id: u64);
}

/// One word the phone's recognizer heard: as formatted, and its start in ms from its session's first sample.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SttWord {
    pub text: String,
    pub start_ms: u64,
}

struct Bridge(Box<dyn VoiceListener>);

impl Events for Bridge {
    fn status(&self, s: Status) {
        self.0.on_state(s.into());
    }
    fn text(&self, who: &str, text: &str) {
        self.0.on_text(who.into(), text.into());
    }
    fn error(&self, text: &str) {
        self.0.on_error(text.into());
    }
    fn flush(&self) {
        self.0.on_flush();
    }
    fn paused(&self, on: bool) {
        self.0.on_paused(on);
    }
    fn stt_open(&self, id: u64) {
        self.0.on_stt_open(id);
    }
    fn stt_audio(&self, id: u64, pcm: &[u8]) {
        self.0.on_stt_audio(id, pcm.to_vec());
    }
    fn stt_close(&self, id: u64) {
        self.0.on_stt_close(id);
    }
}

#[derive(uniffi::Object)]
pub struct VoiceCall(Call);

#[uniffi::export]
impl VoiceCall {
    /// Dials `url` (`ws://127.0.0.1:8790/call`, the forum tunnel's voice port) at once. `phone_stt`:
    /// the phone's own recognizer transcribes Aviv's turns (the listener's `on_stt_*`).
    #[uniffi::constructor]
    pub fn new(url: String, listener: Box<dyn VoiceListener>, phone_stt: bool) -> Arc<Self> {
        crate::init_logging();
        let _rt = RT.enter();
        Arc::new(VoiceCall(Call::start_full(websocket(url), Arc::new(Bridge(listener)), Timing::default(), None, phone_stt)))
    }

    /// [`VoiceCall::new`] for a call about forum thread `topic` (its notification's Call button): the
    /// first hello carries the thread's title and newest posts, so Jarvis opens with it.
    #[uniffi::constructor]
    pub fn about(url: String, listener: Box<dyn VoiceListener>, topic: u64, title: String, text: String, phone_stt: bool) -> Arc<Self> {
        crate::init_logging();
        let _rt = RT.enter();
        let about = tether_voice::proto::About { topic, title, text };
        Arc::new(VoiceCall(Call::start_full(websocket(url), Arc::new(Bridge(listener)), Timing::default(), Some(about), phone_stt)))
    }

    /// One 20 ms mic frame (640 bytes PCM16 LE mono 16 kHz). Never blocks.
    pub fn send_audio(&self, pcm: Vec<u8>) {
        self.0.send_audio(pcm);
    }

    /// The next `samples` samples for the speaker; empty once the call is over and its last
    /// earcon has played.
    pub fn pull_audio(&self, samples: u32) -> Vec<u8> {
        self.0.pull_audio(samples as usize)
    }

    pub fn set_muted(&self, on: bool) {
        self.0.set_muted(on);
    }

    /// Pause Jarvis mid-reply (true), or let him go on (false).
    pub fn set_paused(&self, on: bool) {
        self.0.set_paused(on);
    }

    /// The recognizer finished session `id`: its words, or null if it failed.
    pub fn stt_done(&self, id: u64, words: Option<Vec<SttWord>>) {
        let words = words.map(|w| w.into_iter().map(|w| Word { text: w.text, start_ms: w.start_ms }).collect());
        self.0.stt_done(id, words);
    }

    pub fn hangup(&self) {
        self.0.hangup();
    }

    pub fn network_changed(&self) {
        self.0.network_changed();
    }
}
