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

use crate::{Event, Reply, FileRef, Tile, Via};
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
    /// A new tab, on the number he named or the lowest free one: answered by `Opened`.
    Open {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        want: Option<u32>,
        #[serde(default)]
        name: String,
    },
    Close { id: String },
    /// A closed tab opens again with its whole record, on its old number if that's free or the
    /// lowest free one: answered by `Opened`.
    Reopen { id: String },
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
    },
    /// Stop the turn being written: what's written stays on the record. Claude Code stops the tab's
    /// helpers with it, and the record says so, while a background command goes on. Answered by
    /// `Done`, also when there was nothing to stop.
    Stop { id: String },
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
    /// The tab's Jarvis truly can't go on (amber), or no longer needs him.
    Needs {
        id: String,
        text: String,
        #[serde(default)]
        closed: bool,
    },
    /// Overwrite a secret in every record, in place: answered by `Scrubbed`. `word`: only as a whole
    /// word, for one group of a value with spaces, which could be any four letters.
    Scrub {
        secret: String,
        #[serde(default)]
        word: bool,
    },
    #[serde(other)]
    Other,
}

/// One tab in a listing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Info {
    pub n: u32,
    pub id: String,
    /// A voice workspace: it can be read and followed, not typed into yet.
    #[serde(default)]
    pub mirrored: bool,
    pub tile: Tile,
}

/// The server's word to a client.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "tell", rename_all = "lowercase")]
pub enum Tell {
    Tabs { tabs: Vec<Info> },
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
    /// An ask that didn't work, and why.
    Refused {
        #[serde(default, skip_serializing_if = "String::is_empty")]
        id: String,
        why: String,
    },
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
