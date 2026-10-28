//! Voice calls with Jarvis for the app: [`tether_voice::Call`] behind a uniffi object. The app's
//! mic thread pushes 20 ms frames with `send_audio`, its speaker thread pulls with `pull_audio`
//! (blocking writes to the AudioTrack pace it), and the listener hears the call's state.

use std::sync::Arc;

use tether_voice::{Call, Events, Status, Timing, websocket};

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
}

#[derive(uniffi::Object)]
pub struct VoiceCall(Call);

#[uniffi::export]
impl VoiceCall {
    /// Dials `url` (`ws://127.0.0.1:8790/call`, the forum tunnel's voice port) at once.
    #[uniffi::constructor]
    pub fn new(url: String, listener: Box<dyn VoiceListener>) -> Arc<Self> {
        crate::init_logging();
        let _rt = RT.enter();
        Arc::new(VoiceCall(Call::start(websocket(url), Arc::new(Bridge(listener)), Timing::default())))
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

    pub fn hangup(&self) {
        self.0.hangup();
    }

    pub fn network_changed(&self) {
        self.0.network_changed();
    }
}
