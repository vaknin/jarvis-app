//! Voice calls with Jarvis for the app: [`tether_voice::Call`] behind a uniffi object. The app's
//! mic thread pushes 20 ms frames with `send_audio`, its speaker thread pulls with `pull_audio`
//! (blocking writes to the AudioTrack pace it), and the listener hears the call's state.
//!
//! When the line stalls or breaks, the call kicks the forum tunnel's iroh endpoint at once
//! ([`crate::forum::forum_network_changed`]) rather than waiting for Android's network callback. A call
//! that went down sends its recent log lines to the server once ([`crate::phonelog`]): shortly after
//! it's back, or when it ends without coming back.

use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

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
    /// Aviv's mic is muted by voice ("mute my mic") or not any more. The mic keeps streaming (the
    /// server listens for "unmute"); only the screen changes. A tap of mute or unmute ends it.
    fn on_voice_muted(&self, on: bool);
    /// (A call with `phone_stt`) the phone's recognizer opens session `id`; the call's mic audio
    /// follows as the server got it (20 ms frames, or a backlog at once), then `on_stt_close`.
    /// Answer `VoiceCall.stt_done` once the session is over, however it ended. Never block here.
    fn on_stt_open(&self, id: u64);
    fn on_stt_audio(&self, id: u64, pcm: Vec<u8>);
    /// No more audio for session `id`: the recognizer finishes it.
    fn on_stt_close(&self, id: u64);
    /// The server has this call as `id`: keep it, so a call the app dies in can be rejoined.
    fn on_joined(&self, id: String);
    /// Aviv hung up but the server may not have heard: deliver it later with [`voice_end`].
    fn on_hangup_owed(&self, id: String);
}

/// One word the phone's recognizer heard: as formatted, and its start in ms from its session's first sample.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SttWord {
    pub text: String,
    pub start_ms: u64,
}

/// After the line is back, this long before the log goes: the old pipe's own end line (its path,
/// bytes and why) lands within a few seconds of the switch.
const LOG_AFTER_RESUME: Duration = Duration::from_secs(15);
/// After a call that went down ends, this long before the log goes (the pipes' end lines).
const LOG_AFTER_END: Duration = Duration::from_secs(6);

struct Bridge(Box<dyn VoiceListener>, Arc<Drops>);

/// What this call's line did, for its one log upload.
#[derive(Default)]
struct Drops {
    went_down: AtomicBool,
    sent: AtomicBool,
}

impl Drops {
    /// Sends the log once per call, `after` from now.
    fn send(&self, why: String, after: Duration) {
        if self.sent.swap(true, Ordering::SeqCst) {
            return;
        }
        upload(why, after);
    }
}

/// Sends the recent log lines to the server, `after` from now. Best effort.
fn upload(why: String, after: Duration) {
    RT.spawn(async move {
        tokio::time::sleep(after).await;
        let lines = crate::phonelog::recent(crate::phonelog::SEND_WINDOW, crate::phonelog::SEND_MAX);
        match crate::forum::send_log(&why, &lines).await {
            Ok(answer) => tracing::info!("voice: log sent ({} B, {why}): {answer}", lines.len()),
            Err(e) => tracing::warn!("voice: log not sent ({why}): {e:#}"),
        }
    });
}

/// A line from the app's mic code, for the call's log.
#[uniffi::export]
pub fn mic_note(line: String) {
    tracing::info!("mic: {line}");
}

/// Sends the call's log to the server now (a call on a headset, whose mic lines are worth reading away
/// from the phone: logcat rolls over in minutes).
#[uniffi::export]
pub fn send_call_log(why: String) {
    upload(why, Duration::ZERO);
}

impl Events for Bridge {
    fn status(&self, s: Status) {
        if let Status::Ended(why) = &s
            && self.1.went_down.load(Ordering::SeqCst)
        {
            self.1.send(format!("ended after a drop: {why}"), LOG_AFTER_END);
        }
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
    fn voice_muted(&self, on: bool) {
        self.0.on_voice_muted(on);
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
    fn kick(&self) {
        self.1.went_down.store(true, Ordering::SeqCst);
        crate::forum::forum_network_changed();
    }
    fn resumed(&self) {
        self.1.send("resumed".into(), LOG_AFTER_RESUME);
    }
    fn joined(&self, id: &str) {
        self.0.on_joined(id.into());
    }
    fn hangup_owed(&self, id: &str) {
        self.0.on_hangup_owed(id.into());
    }
}

/// Hangs up the dropped call `id` on `url` without joining it (Aviv declined Jarvis calling back,
/// or a hangup the line didn't carry). Blocks up to half a minute: call it off the main thread.
/// True once the server has it.
#[uniffi::export]
pub fn voice_end(url: String, id: String) -> bool {
    crate::init_logging();
    match RT.block_on(tether_voice::call::end(websocket(url), id.clone(), Timing::default())) {
        Ok(()) => {
            tracing::info!("voice: hung up the dropped call {id}");
            true
        }
        Err(e) => {
            tracing::warn!("voice: couldn't hang up the dropped call {id}: {e:#}");
            false
        }
    }
}

fn bridge(listener: Box<dyn VoiceListener>) -> Arc<Bridge> {
    Arc::new(Bridge(listener, Default::default()))
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
        Arc::new(VoiceCall(Call::start_full(websocket(url), bridge(listener), Timing::default(), None, phone_stt)))
    }

    /// [`VoiceCall::new`] for a call about forum thread `topic` (its notification's Call button): the
    /// first hello carries the thread's title and newest posts, so Jarvis opens with it.
    #[uniffi::constructor]
    pub fn about(url: String, listener: Box<dyn VoiceListener>, topic: u64, title: String, text: String, phone_stt: bool) -> Arc<Self> {
        crate::init_logging();
        let _rt = RT.enter();
        let about = tether_voice::proto::About { topic, title, text };
        Arc::new(VoiceCall(Call::start_full(websocket(url), bridge(listener), Timing::default(), Some(about), phone_stt)))
    }

    /// Back to the dropped call `id` the app had left (its process died mid-call, or Aviv answered
    /// Jarvis calling back). `aviv_left`: Jarvis opens with where they were cut off; without it (a
    /// restart Aviv never noticed, like an update in a pause) the call just goes on.
    #[uniffi::constructor]
    pub fn rejoin(url: String, listener: Box<dyn VoiceListener>, id: String, phone_stt: bool, aviv_left: bool) -> Arc<Self> {
        crate::init_logging();
        let _rt = RT.enter();
        Arc::new(VoiceCall(Call::rejoin(websocket(url), bridge(listener), Timing::default(), id, phone_stt, aviv_left)))
    }

    /// One 20 ms mic frame (640 bytes PCM16 LE mono 16 kHz). Never blocks.
    pub fn send_audio(&self, pcm: Vec<u8>) {
        self.0.send_audio(pcm);
    }

    /// Whether the mic is a headset's, whose level the call looks after (`tether_voice::MicBoost`).
    pub fn set_mic_boost(&self, on: bool) {
        self.0.set_mic_boost(on);
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

    /// The phone has a network or not (Android's default network came or went): offline, a dropped
    /// call waits; back online, it dials at once.
    pub fn set_online(&self, on: bool) {
        self.0.set_online(on);
    }
}
