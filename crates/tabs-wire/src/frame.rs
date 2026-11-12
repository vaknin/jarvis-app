//! The frames between the tab server and a client: a 4-byte big-endian length, then that many bytes
//! of JSON. Over any byte stream: a unix socket on this box, and an iroh stream later, with no frame
//! changing. Like events they only grow: a new kind or a new optional field. A reader keeps a kind it
//! doesn't know as `Other`, so an old client goes on working.
//!
//! **Ids, not numbers.** Everything names a tab by `Tab::id`, and a client resumes with
//! `Follow { id, after }`, where `after` is the offset of the last event it has: a number can go to a
//! new record, an id never does. The wall is followed the same way, as `WALL`.
//!
//! **Live text is for clients only.** `Text` carries a reply while it's being written and never
//! reaches the record; the finished block lands as an `Event`, and a client drops its live text then.

use crate::autopilot::{AskKind, Item};
use crate::{Event, Reply, FileRef, Tile, Urgency, Via};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

/// A frame bigger than this is refused before it's read: a client's bug can't fill the server.
pub const MAX_FRAME: usize = 64 << 20;

/// The id that follows the wall's own record: tabs opened, closed and moved.
pub const WALL: &str = "wall";

/// The id a refused `Phone` comes back with: no tab's.
pub const PHONE: &str = "phone";

/// One message his own hands sent a tab, `secs` ago. `card`: his answer to a card, no words typed.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Heard {
    pub words: String,
    pub secs: u64,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub card: bool,
}

/// A client's ask.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "ask", rename_all = "lowercase")]
pub enum Ask {
    /// Every tab, in his order: answered by `Tabs`.
    List,
    /// A new tab, at the end of the row: answered by `Opened`. `want` comes from an old client and
    /// means nothing now, since a number is a place.
    Open {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        want: Option<u32>,
        #[serde(default)]
        name: String,
        /// A mode from `mode::MODES`; none is the default.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        mode: String,
    },
    Close { id: String },
    /// He renamed the tab: on the record as its name. Answered by `Done`.
    Rename { id: String, name: String },
    /// He hid the tab from the wall, or showed it again. Answered by `Done`.
    Hide { id: String, hidden: bool },
    /// He put the tab away for later, with a note if he wrote one. `Reopen` brings it back, and
    /// nothing else but his own words. Answered by `Done`.
    Park { id: String, note: String },
    /// The tabs he parked: answered by `Parked`.
    Parked,
    /// He chose a mode for the tab; its next session starts on it. Answered by `Done`.
    Mode { id: String, mode: String },
    /// The tab's level, `low`, `default` or `urgent`. Urgent from his screens or his shell; a tab's
    /// session may set its own tab low or default, and urgent only with `asked`: his words from the tab's
    /// last half hour asking for it (`heard.rs`). Answered by `Done`.
    Level {
        id: String,
        level: Urgency,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        asked: Option<String>,
    },
    /// Whether the tab's cards may take their pick when he's silent, after the old wait for their level.
    /// On from his screens or his shell, or from the tab's session with `asked`: a quote of his latest
    /// message letting it decide (`heard::heard_defaults`). Off from anyone. Answered by `Done`.
    Defaults {
        id: String,
        on: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        asked: Option<String>,
    },
    /// The wall says which build it is, on connecting and after every restart: the server keeps it
    /// where Jarvis can read it. Answered by `Done`.
    Build {
        /// The first twelve hex digits of the binary's sha256.
        build: String,
        /// Unix seconds this window started.
        started: u64,
        /// Tabs it has open.
        tabs: u32,
    },
    /// A closed tab opens again with its whole record, at the end of the row: answered by `Opened`.
    Reopen { id: String },
    /// The library fold's switch-on (stage 5b): every running tab binds to its library thread now, as its start
    /// would under `~/.jarvis/library-threads`. Answered by `Done`; `jarvis fold threads` checks the store after.
    LibraryBind,
    /// The library fold's move-to-thread key (stage 5e), in the main chat `id`: the subject he's on (of his message at
    /// record offset `at`, else his newest one with a subject) and the messages that would move with it. Answered by
    /// `Subject`.
    Subject {
        id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        at: Option<u64>,
    },
    /// What Jarvis's ticks on his message at record offset `to` in tab `id` stand for: answered by `Tick`, read
    /// off the record, no model call.
    Tick { id: String, to: u64 },
    /// He confirmed: the subject's messages move into a new thread. `offsets` are the ones that lit up; if the subject
    /// has more or fewer now, nothing moves and it's refused. Answered by `Split`.
    MoveSubject {
        id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        at: Option<u64>,
        offsets: Vec<u64>,
    },
    /// Every closed tab, newest first: answered by `Closed`. Their records stay, so they can be
    /// found by name and followed; a follow of one sends the record, then `Ended`.
    Closed,
    /// He pinned a tab to the top of the tab list, or took the pin off: on the wall's record, so it
    /// lasts and every screen sees it. Answered by `Done`.
    Pin { id: String, pinned: bool },
    /// The pinned tabs, open or closed: answered by `Pins`.
    Pins,
    /// He follows a thread like a feed (alt+f), or stops (`on` false): on the wall's record, so it lasts
    /// and every screen sees it. Not `Follow`, which is a client's record stream. Answered by `Watching`.
    Watch { id: String, on: bool },
    /// The threads he follows, with their tasks' news: answered by `Watching`.
    Watching,
    /// His one bulk switch, shared by laptop and phone: on, Enter holds his words for Ctrl+Enter or a running turn's
    /// end; off, Enter sends now. Kept by the server (`~/.jarvis/fold/bulk-off` while off). Answered by `Done`.
    Bulk { on: bool },
    /// He moved a tile to place `to`, counted from 0.
    Move { id: String, to: usize },
    /// His words: on the record first, then answered by `Landed`, then to the tab's Jarvis.
    Say {
        id: String,
        text: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        via: Option<Via>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        replies: Vec<Reply>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files: Vec<FileRef>,
        /// While a turn runs, hold them for its end instead of steering it; on a sleeping tab, for
        /// `Release`. Awake and idle, it changes nothing. Whatever doesn't say so goes straight in.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        quiet: bool,
    },
    /// A message from the tab whose session sends it, to another tab. The sender is never in the frame:
    /// the server works it out from the connection (`msg.rs`). `fyi` waits for the tab's next turn
    /// instead of starting one; `re` is the id of the message this answers. Answered by `Sent`.
    Message {
        to: String,
        text: String,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        fyi: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        re: Option<String>,
    },
    /// What he last said in this tab by his own hands: the screen guard's question (cli/src/screenguard.rs).
    /// Answered by `Asked`.
    Asked { id: String },
    /// Stop the turn being written: what's written stays on the record. Claude Code stops the tab's
    /// helpers with it, and the record says so, while a background command goes on. Answered by
    /// `Done`, also when there was nothing to stop.
    Stop { id: String },
    /// Wake a sleeping tab for his words that wait there, as one turn: ctrl+enter with nothing in the
    /// box. Answered by `Done`, also when there was nothing to send.
    Release { id: String },
    /// Every event after `after`, then each new one as it lands, and the live text. 0 is the start.
    Follow {
        id: String,
        #[serde(default)]
        after: u64,
    },
    Unfollow { id: String },
    /// He has read up to `upto`.
    Seen { id: String, upto: u64 },
    /// A picture or a sound beside the record, by the name in its `FileRef`: answered by `File`.
    File { id: String, name: String },
    /// A file he attached, as base64 because frames are JSON: kept beside the record, for a `Say` to
    /// name. `name` is what he called it, and keeps its extension. Answered by `Put`.
    Put { id: String, name: String, data: String },
    /// A screenshot of his phone, for the box: the server takes it over its own adb, and wakes nothing,
    /// the phone's screen and Jarvis alike. Answered by `Phone`, or `Refused` with id `PHONE`.
    Phone,
    /// The tab's Jarvis truly can't go on (blocked), or no longer needs him.
    Needs {
        id: String,
        text: String,
        #[serde(default)]
        closed: bool,
    },
    /// A helper's report for this tab: on the record, for the session's next wake and for his eyes.
    Report {
        id: String,
        from: String,
        line: String,
        text: String,
    },
    /// Another session's note for this tab: on the record like a report, and it starts a turn, the
    /// tab asleep or not. Never his words: those are `Say`. `text` is what the session reads; `body`,
    /// if any, is what the record keeps instead.
    Note {
        id: String,
        from: String,
        line: String,
        text: String,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        body: String,
        /// Between sessions: an `Aside` on the record, and so is the reply, unless it's marked for him.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        aside: bool,
        /// A question for him, by way of the tab: the note stays an `Aside`, the reply is the asking.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        asks: bool,
        /// What the work behind it cost, in millionths of a dollar, for an autopilot's spend.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        micro_usd: Option<u64>,
    },
    /// A job's report that landed nothing and asks nothing: on the record, and read with the tab's next
    /// turn, without starting one (Aviv, 2026-11-04: "why did you wake up? that's a waste of usage").
    /// Answered by `Done`.
    Fyi {
        id: String,
        from: String,
        line: String,
        text: String,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        body: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        micro_usd: Option<u64>,
    },
    /// The one line "Done: ..." for a job's work, once it's on main and runs on his devices, written by
    /// code, not a session (Aviv, 2026-11-04: "i prefer a single time, once it's 100% in and tested and
    /// on my phone/laptop"). Answered by `Done`.
    DoneLine { id: String, text: String },
    /// The jobs this tab started that are running now, and those queued, whole: on the record if that
    /// changed. Answered by `Done`.
    Jobs {
        id: String,
        jobs: Vec<String>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        queued: Vec<String>,
    },
    /// Overwrite a secret in every record, in place: answered by `Scrubbed`. `word`: only as a whole
    /// word, for one group of a value with spaces, which could be any four letters.
    Scrub {
        secret: String,
        #[serde(default)]
        word: bool,
    },
    /// A bug he filed: code writes it down, no model hears of it. `quotes`: the paragraphs as he saw
    /// them. Answered by `Bug`.
    Bug {
        id: String,
        quotes: Vec<String>,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        text: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files: Vec<FileRef>,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        build: String,
    },
    /// His words on mistake `n`, typed in the box F2 opens after filing it: they go with it in the
    /// inbox, and on the tab's record for its session. Answered by `Done`.
    BugNote { id: String, n: u32, text: String },
    /// A reaction on the event at offset `to`, pressed again it comes off. `jarvis`: `jarvis react`, which
    /// only a tab's session may run; anyone else not in a tab is him. His 👎 is answered by `Bug`, the
    /// rest by `Done`.
    React {
        id: String,
        to: u64,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        quote: String,
        emoji: String,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        jarvis: bool,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        build: String,
    },
    /// Words to flash on his screen, off the session. Answered by `Done`.
    Toast { id: String, text: String },
    /// A question for him, from a tab's own session only (`jarvis ask`): answered by `Raised` with its
    /// id, or `Refused` with why (`asks.rs` holds the form). `low` marks it below the tab's level; it
    /// can't go above: `suggest_urgent` is all a tab may do.
    Question {
        id: String,
        kind: AskKind,
        items: Vec<Item>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        low: bool,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        suggest_urgent: bool,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        blocks: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ap: Option<String>,
    },
    /// His answer to question `q` of the tab, from his screens only: `picks` per item, `text` his words
    /// if he wrote any; `OPEN` for an item he left open before one he answered. It lands as his `said`
    /// replying to the question (and on a merged card, in each tab), so it starts a turn there. Answered by `Landed`.
    Answer {
        id: String,
        q: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        picks: Vec<u32>,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        text: String,
    },
    /// Question `q` of the tab is no longer needed: it leaves the Needs you list, on the record as taken
    /// back. From the tab's own session, the main chat's session (any tab's card), or from outside every tab
    /// (a job, his shell); never from another tab. `why`: how it was settled, kept on the record (and on the
    /// library's question, for a library card); empty writes the old line. A library card must also cite what
    /// settled it (task 1318): `page`, his own page after the question was asked, or `settled`, the state its
    /// `--settled-when` shows; otherwise it is refused and stays. Answered by `Done`.
    Withdraw {
        id: String,
        q: String,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        why: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        page: Option<u64>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        settled: bool,
    },
    /// The tab decided instead of asking (`jarvis assume`), from its own session. Answered by `Done`. With
    /// `boards`, a 2.0 board decided (its planner, a worker, the sweep: `route::digests`), from outside any tab
    /// and only while 2.0 is on; `issue` is the issue it was about, for his 👎 to come back to.
    Assume {
        id: String,
        text: String,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        boards: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        issue: Option<u64>,
    },
    /// The Needs you list and the digest: answered by `Queue`, and again on every change, until the
    /// connection closes or `Unfollow` with `QUEUE`.
    Queue,
    /// What the digest reads beside the queue changed (the machine check's problems, a card past a day):
    /// every `Queue` follower gets it again. Answered by `Done`.
    Refresh,
    /// Autopilot: `on`, `off`, `accept` and `reopen` from his screens; the rest from the tab's session.
    Autopilot {
        id: String,
        op: String,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        text: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        n: Option<u32>,
    },
    #[serde(other)]
    Other,
}

/// The id `Unfollow` takes to stop the queue's updates.
/// A pick for an item he left open, in `Ask::Answer`: the item stays open, never his.
pub const OPEN: u32 = u32::MAX;

pub const QUEUE: &str = "queue";

/// One card of the Needs you list: one question, or the same question from several tabs, which one
/// tap answers in each.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Card {
    pub tabs: Vec<CardTab>,
    pub kind: AskKind,
    #[serde(default, skip_serializing_if = "Urgency::is_default")]
    pub level: Urgency,
    pub items: Vec<Item>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub due: Option<u64>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub blocks: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub suggest_urgent: bool,
    pub at: u64,
}

/// Where a card's question stands in one tab: what an `Answer` names.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CardTab {
    pub id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
    pub q: String,
    pub offset: u64,
}

/// A line of the digest screen: what finished, a default taken, an assumption, a stall.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DigestLine {
    pub id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
    pub at: u64,
    pub offset: u64,
    pub what: String,
    pub line: String,
}

/// What a voice asks for on `tap.sock`, after the two pipe ends in one message: its end of the pipe
/// into the tab, and its end of the pipe out. From then on the two speak Claude Code's own stream
/// lines, so a conversation can't tell a tab from a process of its own, and its pipes move through a
/// hot swap like any others. A closed pipe unbinds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Bind {
    pub id: String,
    /// What the session's commands get as `JARVIS_VOICE` while he hears this tab.
    pub key: String,
    /// How to talk when he hears the reply: goes once per session, with the first spoken message.
    pub rules: String,
    /// He hears this tab now.
    #[serde(default)]
    pub speak: bool,
}

/// The answer to `Bind`, then the socket closes. `why` empty: bound.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Bound {
    #[serde(default)]
    pub n: u32,
    #[serde(default)]
    pub why: String,
}

/// One tab in a listing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Info {
    pub n: u32,
    pub id: String,
    /// Always false since the workspaces moved in; an old client still reads it.
    #[serde(default)]
    pub mirrored: bool,
    pub tile: Tile,
}

/// A thread he follows, as the Following screen shows it. `at`: its latest news, ms since the epoch.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Watched {
    pub id: String,
    pub name: String,
    pub open: bool,
    pub at: u64,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tasks: Vec<WatchedTask>,
}

/// One task of a followed thread: its current word only, one of the shared status words (Planning,
/// Building, Landed, Live...), as a string so a client needs no other crate. `at`: its latest news, ms.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WatchedTask {
    pub id: i64,
    pub title: String,
    pub status: String,
    pub at: u64,
}

fn yes() -> bool {
    true
}

fn is_true(b: &bool) -> bool {
    *b
}

/// The server's word to a client.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "tell", rename_all = "lowercase")]
pub enum Tell {
    /// The wall's tabs; `fold` while the library fold's switch is on (`~/.jarvis/fold/threads-on`).
    Tabs {
        tabs: Vec<Info>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        fold: bool,
        /// His bulk switch (`Ask::Bulk`): absent means on, so an old server reads as today's laptop.
        #[serde(default = "yes", skip_serializing_if = "is_true")]
        bulk: bool,
        /// The phone's copy only: the tab that is the main chat (`~/.jarvis/main-chat`), on the wall or not, so the wall
        /// and the phone never pick different ones (task 943). The server doesn't send it yet; the phone falls back.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        main: Option<String>,
    },
    /// The tabs he put away for later, last parked first. `n` means nothing here.
    Parked { tabs: Vec<Info> },
    /// The closed tabs, newest first. `n` is the number each last had.
    Closed { tabs: Vec<Info> },
    /// The ids of the pinned tabs, the first pinned first.
    Pins { ids: Vec<String> },
    /// The threads he follows, latest news first.
    Watching { threads: Vec<Watched> },
    Opened { tab: Info },
    /// His words are on the record, at this offset.
    Landed { id: String, offset: u64 },
    /// A message is on the receiving tab's record, under this id.
    Sent { msg: String, offset: u64 },
    /// His latest words in the tab, `secs` old, none if those weren't his own hands. `said`: all his own
    /// hands' words there from the last half hour, oldest first (tier.rs: a request two messages back counts).
    Asked {
        words: String,
        secs: u64,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        said: Vec<Heard>,
    },
    /// An event of a followed tab, in the record's order, each once. `event.offset` is the resume point.
    Event { id: String, event: Event },
    /// A reply being written. `text` goes at byte `from` of block `block`; a new block starts empty.
    Text { id: String, block: u64, from: usize, text: String },
    /// A resume at an offset that isn't a line's end: the client has the wrong id or a bad offset.
    BadOffset { id: String, asked: u64 },
    /// A followed tab was closed: its record is whole, and nothing more will come.
    Ended { id: String },
    Scrubbed { found: usize },
    /// A file a client asked for, as base64 because frames are JSON. Empty if it isn't there.
    File { id: String, name: String, data: String },
    /// A file is kept, as `file`: `name` is the one the `Put` gave, so a client can tell them apart.
    Put { id: String, name: String, file: FileRef },
    /// His phone's screen, a PNG as base64, for the box to attach as a picture he pasted.
    Phone { data: String },
    /// An ask that didn't work, and why.
    Refused {
        #[serde(default, skip_serializing_if = "String::is_empty")]
        id: String,
        why: String,
    },
    /// What the tab's session is doing now, in a few words, worked out by code from its own stream.
    /// For clients, never the record. Empty: nothing to say, it's thinking.
    Doing { id: String, what: String },
    /// The bug is in the inbox, as number `n`.
    Bug { id: String, n: u32 },
    /// The question is on the list, as `q` in its tab.
    Raised { id: String, q: String },
    /// The Needs you list, urgent first, then by deadline; and the digest's lines, newest first.
    Queue {
        decide: Vec<Card>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        digest: Vec<DigestLine>,
    },
    /// The main chat's subject (`Ask::Subject`): its name, and the record offsets of the messages that would move,
    /// oldest first. None: nothing he said there has a subject yet.
    Subject {
        id: String,
        #[serde(default)]
        name: String,
        #[serde(default)]
        offsets: Vec<u64>,
    },
    /// What the ticks on his message at `to` stand for (`Ask::Tick`): the words they took the place of
    /// (`event::tick_words`), empty when none were kept.
    Tick {
        id: String,
        to: u64,
        #[serde(default)]
        text: String,
    },
    /// The subject moved (`Ask::MoveSubject`): `moved` messages, into the new thread `tab`.
    Split { tab: Info, moved: usize },
    /// The ask went through and has nothing else to say.
    Done,
    #[serde(other)]
    Other,
}

#[derive(Debug)]
pub enum FrameError {
    /// The stream ended, cleanly or halfway through a frame.
    Closed,
    TooBig(usize),
    Bad(serde_json::Error),
    Io(std::io::Error),
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FrameError::Closed => write!(f, "the connection closed"),
            FrameError::TooBig(n) => write!(f, "a frame of {n} bytes, over the {MAX_FRAME} limit"),
            FrameError::Bad(e) => write!(f, "not a frame: {e}"),
            FrameError::Io(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for FrameError {}

impl From<std::io::Error> for FrameError {
    fn from(e: std::io::Error) -> Self {
        match e.kind() {
            std::io::ErrorKind::UnexpectedEof => FrameError::Closed,
            _ => FrameError::Io(e),
        }
    }
}

/// A frame's bytes, length first.
pub fn encode<T: Serialize>(frame: &T) -> Vec<u8> {
    let body = serde_json::to_vec(frame).expect("a frame always serializes");
    let mut out = Vec::with_capacity(body.len() + 4);
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    out.extend_from_slice(&body);
    out
}

pub fn decode<T: DeserializeOwned>(body: &[u8]) -> Result<T, FrameError> {
    serde_json::from_slice(body).map_err(FrameError::Bad)
}

fn length(head: [u8; 4]) -> Result<usize, FrameError> {
    let n = u32::from_be_bytes(head) as usize;
    if n > MAX_FRAME {
        return Err(FrameError::TooBig(n));
    }
    Ok(n)
}

/// A frame from a blocking stream.
pub fn read<T: DeserializeOwned>(r: &mut impl std::io::Read) -> Result<T, FrameError> {
    let mut head = [0; 4];
    r.read_exact(&mut head)?;
    let mut body = vec![0; length(head)?];
    r.read_exact(&mut body)?;
    decode(&body)
}

/// A frame onto a blocking stream.
pub fn write<T: Serialize>(w: &mut impl std::io::Write, frame: &T) -> Result<(), FrameError> {
    w.write_all(&encode(frame))?;
    Ok(w.flush()?)
}

/// A frame from any async stream. Not safe to cancel halfway: give a stream one reader task.
#[cfg(feature = "async")]
pub async fn read_async<T: DeserializeOwned>(r: &mut (impl tokio::io::AsyncRead + Unpin)) -> Result<T, FrameError> {
    use tokio::io::AsyncReadExt;
    let mut head = [0; 4];
    r.read_exact(&mut head).await?;
    let mut body = vec![0; length(head)?];
    r.read_exact(&mut body).await?;
    decode(&body)
}

/// A frame onto any async stream.
#[cfg(feature = "async")]
pub async fn write_async<T: Serialize>(w: &mut (impl tokio::io::AsyncWrite + Unpin), frame: &T) -> Result<(), FrameError> {
    use tokio::io::AsyncWriteExt;
    w.write_all(&encode(frame)).await?;
    Ok(w.flush().await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_reaction_is_one_of_the_set_with_or_without_its_variation() {
        assert_eq!(crate::event::reaction("❤"), Some("❤\u{fe0f}"));
        assert_eq!(crate::event::reaction("❤\u{fe0f}"), Some("❤\u{fe0f}"));
        assert_eq!(crate::event::reaction("👍"), Some("👍"));
        assert_eq!(crate::event::reaction("🤏"), None);
        assert_eq!(crate::event::reaction(""), None);
    }
    use crate::Body;

    #[test]
    fn a_frame_comes_back_as_it_went() {
        let t = Tell::Event { id: "t3".into(), event: Event { offset: 42, at: 7, body: Body::Line { text: "x".into() } } };
        let bytes = encode(&t);
        assert_eq!(u32::from_be_bytes(bytes[..4].try_into().unwrap()) as usize, bytes.len() - 4);
        assert_eq!(read::<Tell>(&mut &bytes[..]).unwrap(), t);
    }

    #[test]
    fn a_kind_from_the_future_is_kept_as_other() {
        assert_eq!(decode::<Tell>(br#"{"tell":"hologram","x":1}"#).unwrap(), Tell::Other);
        assert_eq!(decode::<Ask>(br#"{"ask":"teleport"}"#).unwrap(), Ask::Other);
        // a field from the future is ignored
        assert_eq!(decode::<Tell>(br#"{"tell":"done","x":1}"#).unwrap(), Tell::Done);
    }

    /// An old window and a new server (and the reverse) must keep talking: a listing from before
    /// the fields added since still reads, and an event kind a window doesn't know is skipped, not fatal.
    #[test]
    fn a_listing_and_an_event_across_builds_still_read() {
        let old_tile = br#"{"name":"A","line":"","needs":"","amber":false,"unread":0,"open":{},"session":null,"seen":0,"end":0}"#;
        let t = decode::<crate::Tile>(old_tile).unwrap();
        assert!(t.jobs.is_empty() && t.mode.is_empty() && !t.hidden);
        let future = br#"{"tell":"event","id":"t1","event":{"offset":3,"at":1,"kind":"hologram","x":[1,2]}}"#;
        let Tell::Event { event, .. } = decode::<Tell>(future).unwrap() else { panic!("an event") };
        assert_eq!(event.body, Body::Other);
        let newer_tile = br#"{"name":"A","line":"","needs":"","amber":false,"unread":0,"open":{},"session":null,"seen":0,"end":0,"jobs":["x"],"mood":"calm"}"#;
        assert_eq!(decode::<crate::Tile>(newer_tile).unwrap().jobs, vec!["x".to_string()]);
    }

    /// A picture on a quote: a build from before it reads the new reply and keeps every picture in
    /// `files`; a build from after reads the old reply with none on it.
    #[test]
    fn a_reply_with_pictures_reads_in_an_older_build_and_an_older_reply_reads_in_a_newer_one() {
        let old = br#"{"to":4,"quote":"the plan","text":""}"#;
        let r = decode::<crate::Reply>(old).unwrap();
        assert!(r.files.is_empty());
        assert_eq!(String::from_utf8(serde_json::to_vec(&r).unwrap()).unwrap(), String::from_utf8(old.to_vec()).unwrap(), "nothing new on the wire when there is no picture");
        let new = crate::Reply { to: 4, quote: "the plan".into(), text: String::new(), files: vec!["1-0.png".into()] };
        #[derive(serde::Deserialize, Debug, PartialEq)]
        struct Before {
            to: u64,
            quote: String,
            text: String,
        }
        let seen: Before = serde_json::from_slice(&serde_json::to_vec(&new).unwrap()).unwrap();
        assert_eq!(seen, Before { to: 4, quote: "the plan".into(), text: String::new() });
    }

    #[test]
    fn bulk_switch_goes_both_ways() {
        assert_eq!(String::from_utf8(serde_json::to_vec(&Ask::Bulk { on: false }).unwrap()).unwrap(), r#"{"ask":"bulk","on":false}"#);
        assert_eq!(decode::<Ask>(br#"{"ask":"bulk","on":true}"#).unwrap(), Ask::Bulk { on: true });
    }

    /// An old server's listing has no `bulk`: it reads as on, today's laptop; on stays off the wire.
    #[test]
    fn bulk_absent_reads_as_on_and_on_is_not_written() {
        assert_eq!(decode::<Tell>(br#"{"tell":"tabs","tabs":[]}"#).unwrap(), Tell::Tabs { tabs: vec![], fold: false, bulk: true, main: None });
        assert_eq!(String::from_utf8(serde_json::to_vec(&Tell::Tabs { tabs: vec![], fold: false, bulk: true, main: None }).unwrap()).unwrap(), r#"{"tell":"tabs","tabs":[]}"#);
        let off = Tell::Tabs { tabs: vec![], fold: false, bulk: false, main: None };
        assert_eq!(String::from_utf8(serde_json::to_vec(&off).unwrap()).unwrap(), r#"{"tell":"tabs","tabs":[],"bulk":false}"#);
        assert_eq!(decode::<Tell>(br#"{"tell":"tabs","tabs":[],"bulk":false}"#).unwrap(), off);
    }

    #[test]
    fn a_huge_length_is_refused_before_it_is_read() {
        let bytes = u32::MAX.to_be_bytes();
        assert!(matches!(read::<Tell>(&mut &bytes[..]), Err(FrameError::TooBig(_))));
    }

    #[test]
    fn a_cut_frame_says_closed() {
        let bytes = encode(&Tell::Done);
        assert!(matches!(read::<Tell>(&mut &bytes[..3]), Err(FrameError::Closed)));
        assert!(matches!(read::<Tell>(&mut &bytes[..5]), Err(FrameError::Closed)));
    }
}
