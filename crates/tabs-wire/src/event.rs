//! The event shape, frozen on 2026-10-31: both screens depend on it, so a field or a kind is never
//! changed or taken away. New kinds and new optional fields are the only way it grows. A reader
//! keeps a kind it doesn't know as `Body::Other`, a value it doesn't know as that enum's `Other`,
//! and ignores a field it doesn't know, so an old screen goes on working.
//!
//! One line of a record is one event, written in a single write:
//! `{"at":1793430374000,"kind":"said","who":"aviv","text":"go ahead"}`
//!
//! **Offset.** An event's offset is the byte just past its line: its id, and where to read on from.
//! `Page::next` of the last read, or the offset of the last event seen, is the N of "everything
//! after N", and 0 is the start. It isn't stored, because the file's position says it; it's set on
//! every event read and goes along on the wire to a screen. Nothing ever moves a byte, so offsets
//! never change: a secret pasted by mistake is overwritten in place (`Writer::scrub`).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Event {
    #[serde(default, skip_serializing_if = "is_zero")]
    pub offset: u64,
    /// Unix milliseconds.
    pub at: u64,
    #[serde(flatten)]
    pub body: Body,
}

fn is_zero(n: &u64) -> bool {
    *n == 0
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Body {
    /// Words in the tab. A send from the reply box is one `said`: `replies` are the pieces, each a
    /// quote with his words under it, and `text` is whatever he wrote outside them.
    Said {
        who: Who,
        text: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        via: Option<Via>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        replies: Vec<Reply>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files: Vec<FileRef>,
    },
    /// A helper's or a job's result landing in the tab, or a note from another tab. `line` is one
    /// short line of it, `from` who sent it.
    Report {
        from: String,
        line: String,
        text: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        files: Vec<FileRef>,
    },
    /// Work in the tab: a turn, a helper, a build. The moving line shows while any is open. `what` is
    /// empty for a turn, which stays out of the plain lines.
    Work {
        id: String,
        state: WorkState,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        what: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        how: Option<WorkEnd>,
    },
    /// The tab truly can't go on without him: amber, until his next words in the tab or a `closed`
    /// `needs` from the tab itself.
    Needs {
        text: String,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        closed: bool,
    },
    /// The tab's hidden one line: where it stands.
    Line { text: String },
    /// The tab's name.
    Named { name: String },
    /// He has read up to `upto`, on any screen. Only ever moves forward.
    Seen { upto: u64 },
    /// A session of this tab started or ended, either of which closes any work still open, or
    /// finished a turn.
    Session {
        id: String,
        state: SessionState,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        why: String,
        /// On a start: the offset the system prompt's part of the record ends at. It moves only on a
        /// cold wake, so a warm one finds the older part in the prompt cache.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        cut: Option<u64>,
        /// On a turn: what it cost, as Claude Code reported it.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        turn: Option<TurnTokens>,
    },
    /// In the wall's own record: a tab got number `n`.
    Opened { n: u32, id: String },
    /// In the wall's own record: tab `n` closed. Its record stays and the number is free.
    Closed { n: u32 },
    /// In the wall's own record: his tile order, whole. Written only when he moves a tile.
    Moved { order: Vec<u32> },
    /// A kind this code doesn't know yet.
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Who {
    Aviv,
    Jarvis,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Via {
    /// Said aloud, or spoken to him. Jarvis's spoken lines were heard, so they aren't new to read.
    Voice,
    Typed,
    #[serde(other)]
    Other,
}

/// A piece of a reply: `quote` is the words he picked from the event at offset `to`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Reply {
    pub to: u64,
    pub quote: String,
    pub text: String,
}

/// A picture or a sound beside the record. `alt` is what the plain lines say of it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileRef {
    pub name: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub alt: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WorkState {
    Started,
    Ended,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WorkEnd {
    Done,
    Failed,
    /// Its session died under it.
    Lost,
    #[serde(other)]
    Other,
}

/// One turn's tokens. A turn that died has no `output`: its result never came, so the rest is the
/// sum of the model calls it made.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct TurnTokens {
    /// Across all the turn's model calls, and each call re-reads the context.
    pub input: u64,
    pub read: u64,
    pub written: u64,
    pub output: u64,
    /// What the last call held, which is the session's context now.
    pub context: u64,
    /// When the last call went out, ms since the epoch: the prompt cache's clock.
    pub last_call: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SessionState {
    Started,
    Ended,
    Turn,
    #[serde(other)]
    Other,
}

/// A line of the voice server's `N.log.jsonl`, the only other shape a record may hold: the day the
/// writers move, those files go on growing as records, so their old offsets stay good.
#[derive(Deserialize)]
struct VoiceLine {
    at: u64,
    kind: String,
    text: String,
    #[serde(default)]
    job: String,
}

/// One line of a record, or `None` if it's torn or garbage. `offset` is left for the caller.
pub fn parse(line: &[u8]) -> Option<Event> {
    let e: Event = serde_json::from_slice(line).ok()?;
    if e.body != Body::Other {
        return Some(e);
    }
    let Ok(v) = serde_json::from_slice::<VoiceLine>(line) else { return Some(e) };
    let said = |who, text| Body::Said { who, text, via: Some(Via::Voice), replies: vec![], files: vec![] };
    let body = match v.kind.as_str() {
        "aviv" => said(Who::Aviv, v.text),
        "jarvis" => said(Who::Jarvis, v.text),
        "queued" => Body::Report { from: v.job, line: first_line(&v.text), text: v.text, files: vec![] },
        // "queue" was read out, and that takes everything waiting.
        "read" => Body::Seen { upto: u64::MAX },
        _ => return Some(e),
    };
    Some(Event { offset: 0, at: v.at * 1000, body })
}

/// The first line of a report, for a report with no line of its own.
pub fn first_line(text: &str) -> String {
    text.trim().lines().next().unwrap_or("").trim_start_matches(['#', '*', ' ']).trim_end_matches('*').to_string()
}
