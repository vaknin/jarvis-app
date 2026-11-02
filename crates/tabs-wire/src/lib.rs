//! What a client of the tab server needs and nothing else, so the shared Rust client crate (the
//! register's row on the app's shape) can take it as a dependency: the event shape, the tile a
//! client folds from events, and the frames. No files, no server, no runtime unless `async` is on.

pub mod event;
pub mod frame;
pub mod mode;
pub mod tile;

pub use event::{first_line, Body, By, Event, FileRef, Reply, SessionState, TurnTokens, Urgency, Via, Who, WorkEnd, WorkState};
pub use frame::{decode, encode, Ask, Info, Tell, FrameError, MAX_FRAME, WALL};
pub use tile::Tile;
