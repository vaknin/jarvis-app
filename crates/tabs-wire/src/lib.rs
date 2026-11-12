//! What a client of the tab server needs and nothing else, so the shared Rust client crate (the
//! register's row on the app's shape) can take it as a dependency: the event shape, the tile a
//! client folds from events, and the frames. No files, no server, no runtime unless `async` is on.

pub mod autopilot;
pub mod card;
pub mod event;
pub mod frame;
pub mod mode;
pub mod tile;
pub mod uxlock;

pub use event::{bug_words, first_line, reaction, Body, By, Event, FileRef, Reply, SessionState, TurnTokens, Urgency, Via, Who, WorkEnd, WorkState, BUG, NO_REACTION, REACTIONS, STAR};
pub use frame::{decode, encode, Ask, Card, CardTab, DigestLine, Info, Tell, FrameError, Watched, WatchedTask, MAX_FRAME, OPEN, PHONE, QUEUE, WALL};
pub use autopilot::{ApState, ApTile, AskKind, Autopilot, Check, Criterion, Item, QuietWhy, Status};
pub use tile::{issues_bar, jobs_note, jobs_words, IssueBar, OpenAsk, Parked, Tile, QUIET_JOB};
