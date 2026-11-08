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
    },
    Close { id: String },
    /// He chose a mode for the tab; its next session starts on it. Answered by `Done`.
    Mode { id: String, mode: String },
    /// A closed tab opens again with its whole record, at the end of the row: answered by `Opened`.
    Reopen { id: String },
    /// The library fold's move-to-thread key (stage 5e), in the main chat `id`: the subject he's on (of his message at
    /// record offset `at`, else his newest one with a subject) and the messages that would move with it. Answered by
    /// `Subject`.
    Subject {
        id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        at: Option<u64>,
    },
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
    /// Stop the turn being written: what's written stays on the record. Claude Code stops the tab's
    /// helpers with it, and the record says so, while a background command goes on. Answered by
    /// `Done`, also when there was nothing to stop.
    Stop { id: String },
    /// Send his quiet words that wait in this tab now: into the running turn, or wake a sleeping tab
    /// for them. Answered by `Done`, also when there was nothing to send.
    Release { id: String },
    /// The tab's level, `low`, `default` or `urgent`. Urgent from his screens or his shell only; a tab's
    /// session may set its own tab low or default. Answered by `Done`.
    Level { id: String, level: Urgency },
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
    /// The tab's Jarvis truly can't go on (amber), or no longer needs him.
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
    /// tab asleep or not. Never his words: those are `Say`.
    Note {
        id: String,
        from: String,
        line: String,
        text: String,
        /// What the work behind it cost, in millionths of a dollar, for an autopilot's spend.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        micro_usd: Option<u64>,
    },
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
    /// if he wrote any. It lands as his `said` replying to the question (and on a merged card, in each
    /// tab), so it starts a turn there. Answered by `Landed`.
    Answer {
        id: String,
        q: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        picks: Vec<u32>,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        text: String,
    },
    /// The tab decided instead of asking (`jarvis assume`), from its own session. Answered by `Done`.
    Assume { id: String, text: String },
    /// The Needs you list and the digest: answered by `Queue`, and again on every change, until the
    /// connection closes or `Unfollow` with `QUEUE`.
    Queue,
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
pub const QUEUE: &str = "queue";

/// A pick for an item he left open, in `Ask::Answer`: the item stays open, never his.
pub const OPEN: u32 = u32::MAX;

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

/// The server's word to a client.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "tell", rename_all = "lowercase")]
pub enum Tell {
    /// The wall's tabs; `fold` while the library fold's switch is on (`~/.jarvis/fold/threads-on`); `main` the tab that
    /// is the main chat (`~/.jarvis/main-chat`), on the wall or not, so the wall and the phone never pick different ones.
    Tabs {
        tabs: Vec<Info>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        fold: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        main: Option<String>,
    },
    /// The closed tabs, newest first. `n` is the number each last had.
    Closed { tabs: Vec<Info> },
    Opened { tab: Info },
    /// His words are on the record, at this offset.
    Landed { id: String, offset: u64 },
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
    /// An ask that didn't work, and why.
    Refused {
        #[serde(default, skip_serializing_if = "String::is_empty")]
        id: String,
        why: String,
    },
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
