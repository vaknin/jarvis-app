//! The phone's end of a voice call with Jarvis: the wire of the jarvis repo's `voice/PROTOCOL.md`
//! (a WebSocket, PCM16 mono 16 kHz both ways, JSON control frames), the jitter buffer the speaker
//! side pulls from, the earcons, and the reconnect with `resume`. No platform code: the Android app
//! reaches it through `crates/ffi` (`VoiceCall`), and another app (motoparty) can use it the same way.

pub mod meter;
pub mod call;
pub mod player;
pub mod proto;
pub mod stt;

pub use call::{Call, Connector, Events, Frame, Status, Timing, websocket};
pub use stt::Word;
pub use player::{Earcon, Player};

/// Samples per second, both ways.
pub const RATE: u32 = 16_000;
/// One mic frame: 20 ms of PCM16 mono at [`RATE`].
pub const FRAME_BYTES: usize = 640;
