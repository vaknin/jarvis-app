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

use crate::autopilot::{AskKind, Autopilot, Item, QuietWhy};
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
    /// A helper's or a job's result landing in the tab (older records: a note too). `line` is one
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
    /// The tab truly can't go on without him: blocked, until his next words in the tab or a `closed`
    /// `needs` from the tab itself.
    Needs {
        text: String,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        closed: bool,
        /// The blocking `question` this stands for, for a screen from before questions: a screen that
        /// knows them shows the card, and this adds nothing new to read.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        q: String,
    },
    /// The tab's hidden one line: where it stands.
    Line { text: String },
    /// The tab's name.
    Named { name: String },
    /// He hid the tab from the wall, or showed it again. It goes on running either way; a `needs`
    /// from the tab shows it again by itself (`Tile::apply`).
    Hidden { hidden: bool },
    /// He put the tab away for later, and why, if he said. `Hidden` false ends it, as his words do.
    Parked { note: String },
    /// His choice of mode for the tab, from `mode::MODES`. It takes hold at the tab's next session.
    Mode { mode: String },
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
        /// On a start: the model and effort the session runs on.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        model: String,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        effort: String,
        /// On a turn: what it cost, as Claude Code reported it.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        turn: Option<TurnTokens>,
    },
    /// The jobs this tab started that are still running, a title each, whole every time it changes.
    /// A job outlives its tab's sessions, so a session's start or end leaves this alone. `queued`: the
    /// ones waiting for a slot or held by a limit, not started yet.
    Jobs {
        jobs: Vec<String>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        queued: Vec<String>,
        /// The line every screen shows about them, written here so none counts for itself.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        line: String,
        /// Jarvis 2.0 only: the tab's issues, as the bar and alt+j show them. Empty, it's today's line.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        issues: Vec<crate::tile::IssueBar>,
    },
    /// In the wall's own record: a tab came onto the wall, at the end of the row, at place `n`.
    Opened { n: u32, id: String },
    /// In the wall's own record: a tab left the wall, from place `n`, and the tabs after it moved down.
    /// Its record stays. A record from before `Row` has no `id`, and `n` was a number that never moved.
    Closed {
        n: u32,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        id: String,
    },
    /// In the wall's own record, from before `Row`: his tile order, whole, by the old numbers.
    Moved { order: Vec<u32> },
    /// In the wall's own record: the whole row, left to right. A tab's number is its place in it. The
    /// first one settles a wall that still has old numbers; after it, only his moving a tile writes one.
    Row { ids: Vec<String> },
    /// In the wall's own record: he pinned a tab to the top of his tab list, or took the pin off.
    Pinned { id: String, pinned: bool },
    /// In the wall's own record: he follows a thread like a feed, or stopped (`on` false).
    Watched { id: String, on: bool },
    /// Words between sessions, on the record for them and never for a screen: a note or a message from
    /// another session, which `from` names, or Jarvis's own reply to one, with `from` empty. A server
    /// sends a screen `Other` in its place, so its offset still arrives (`Tell::Event`).
    Aside {
        #[serde(default, skip_serializing_if = "String::is_empty")]
        from: String,
        text: String,
    },
    /// His quiet words up to `upto` went into a turn, or a new session's first message.
    Delivered { upto: u64 },
    /// A reaction instead of a message, on the event at offset `to`: `quote` is the paragraph of it,
    /// empty for the message as a whole. Quiet: it starts no turn, wakes nothing and is nothing unread.
    /// Pressed again, `off` takes it back, so the record only grows.
    React {
        to: u64,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        quote: String,
        emoji: String,
        who: Who,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        off: bool,
    },
    /// Words for his screen to flash, once, as they arrive. No session hears them.
    Toast { text: String },
    /// The tab's level: how hard what it asks of him reaches him. Urgent only `by` him, from his
    /// screens or his shell; a tab's session may set itself low or default (docs/autonomy-plan.md 3.4).
    Level { level: Urgency, by: By },
    /// Whether the tab's cards may take their pick when he's silent: off unless he said so in words
    /// (`jarvis tabs defaults on --asked`). Off, a card waits for him whatever its level. On, `arm` holds the
    /// deadline each card already open takes from now: `[q, due]`.
    Defaults {
        on: bool,
        by: By,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        arm: Vec<(String, u64)>,
    },
    /// A question for him, through the one door (`jarvis ask`, `asks.rs`): one card on the Needs you
    /// list. `q` is its id in the tab. Each item is one question with its options and Jarvis's pick.
    /// `due` is when silence takes the picks: only while the tab's defaults are on, never for urgent or
    /// for a kind that never defaults. A
    /// blocking one also writes a `needs` naming it, so a screen from before this shows it amber.
    Question {
        q: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ap: Option<String>,
        ask: AskKind,
        #[serde(default, skip_serializing_if = "Urgency::is_default")]
        level: Urgency,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        suggest_urgent: bool,
        items: Vec<Item>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        due: Option<u64>,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        blocks: bool,
    },
    /// A question closed: a deadline took the picks (`by: default`), it was taken back (`by: withdrawn`),
    /// or he answered (`by: aviv`, beside his `said` replying to the question's offset, which is his words).
    Answer {
        q: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        picks: Vec<u32>,
        by: By,
        #[serde(default, skip_serializing_if = "String::is_empty")]
        note: String,
    },
    /// The tab decided instead of asking (`jarvis assume`): the digest screen lists it, with "wrong".
    Assumed {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ap: Option<String>,
        text: String,
        /// A 2.0 board's decision on this issue (a planner's or a worker's, cli/src/issue/decide.rs): his 👎 on
        /// it comes back to the issue (`jarvis issue wrong`).
        #[serde(default, skip_serializing_if = "Option::is_none")]
        issue: Option<u64>,
    },
    /// The reply at offset `to` went to the digest screen instead of lighting the tab.
    Quiet { to: u64, why: QuietWhy },
    /// A tab's autopilot, whole, every time it changes (`jobs` is the precedent).
    Autopilot { ap: Autopilot },
    /// The `said` at `of` came from his own hands (the app, his call), not a session sending as him. The
    /// gates read his words from these, so they outlast a restart (tabs/src/heard.rs `from_record`).
    /// `card`: his answer to a card, no words typed.
    Hands {
        of: u64,
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        card: bool,
    },
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
    /// Typed while no turn ran, so it waits for the next turn that runs, the tab's next wake, or him to
    /// send it: a `delivered` says when it went in. On the `said` itself, so no moment has his words without their hold.
    Quiet,
    #[serde(other)]
    Other,
}

/// How hard a tab's asks reach him. Low: his screens only. Default: notifications too. Urgent: the
/// voice reach queue too, and it never takes a default; only he sets it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Urgency {
    Low,
    #[default]
    Default,
    Urgent,
    #[serde(other)]
    Other,
}

impl Urgency {
    pub fn parse(s: &str) -> Option<Urgency> {
        match s {
            "low" => Some(Urgency::Low),
            "default" => Some(Urgency::Default),
            "urgent" => Some(Urgency::Urgent),
            _ => None,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            Urgency::Low => "low",
            Urgency::Default => "default",
            Urgency::Urgent => "urgent",
            Urgency::Other => "other",
        }
    }

    pub fn is_default(&self) -> bool {
        *self == Urgency::Default
    }
}

/// Who set something on a tab: him, from his screens or his shell, or the tab's own session. On an
/// `Answer`, only the code's two: a deadline took the default, or the question was taken back. His
/// own answer is never an `Answer`: it's his `said`, replying to the question.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum By {
    Aviv,
    Tab,
    Default,
    Withdrawn,
    #[serde(other)]
    Other,
}

/// A piece of a reply: `quote` is the words he picked from the event at offset `to`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Reply {
    pub to: u64,
    pub quote: String,
    pub text: String,
    /// Names, from the message's `files`, of the pictures he put on this quote. Every picture is in
    /// `files` too, so a build that doesn't know this still shows them all, after the words.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub files: Vec<String>,
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
    /// He stopped it, or it went with a turn he stopped. What was written stays, and his words count
    /// as answered.
    Stopped,
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
    /// Calls to the advisor.
    #[serde(skip_serializing_if = "is_zero")]
    pub advisor: u64,
    /// Tokens on models other than the tab's, which `input` and the rest don't count: the advisor's,
    /// and those of a helper on another model. Cache reads and writes are in `other_input`.
    #[serde(skip_serializing_if = "is_zero")]
    pub other_input: u64,
    #[serde(skip_serializing_if = "is_zero")]
    pub other_output: u64,
    /// What those cost, in millionths of a dollar. Claude Code reports it by model, not by caller.
    #[serde(skip_serializing_if = "is_zero")]
    pub other_micro_usd: u64,
    /// What the whole turn cost, in millionths of a dollar.
    #[serde(skip_serializing_if = "is_zero")]
    pub micro_usd: u64,
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

/// The reactions there are: 👍 yes, 👎 no (F2 sends it as a mistake), ❤️ love, 📏 too long, ⭐ important.
pub const REACTIONS: [&str; 5] = ["👍", "👎", "❤\u{fe0f}", "📏", STAR];
/// What F2 sends: it shows as 👎 and goes to the mistakes inbox as well, as a reply saying "mistake"
/// does (2026-11-05, "change the bug icon (F2) to thumbs-down"). A 👎 from the picker is only a no,
/// never filed (2026-11-05, "fix the side effect").
pub const BUG: &str = "🐞";
/// How F2's mistake shows on the record.
const BUG_SHOWN: &str = "👎";

/// "mistake" or "mistake: what he saw" (or the old "bug"), any case: what he said of it, when that's
/// all he wrote.
pub fn bug_words(said: &str) -> Option<&str> {
    let (head, rest) = said.split_once(':').unwrap_or((said, ""));
    let head = head.trim();
    (head.eq_ignore_ascii_case("mistake") || head.eq_ignore_ascii_case("bug")).then(|| rest.trim())
}
/// His star makes the tab important: its news reaches him as a toast.
pub const STAR: &str = "⭐";

/// How a refused reaction starts: a screen's words aren't in question, so it has nothing to give back.
pub const NO_REACTION: &str = "Reaction: ";

/// One of `REACTIONS`, however it was typed: a heart often comes without its variation selector.
pub fn reaction(emoji: &str) -> Option<&'static str> {
    let bare = |e: &str| e.trim_end_matches('\u{fe0f}').to_string();
    if bare(emoji.trim()) == BUG {
        return Some(BUG_SHOWN);
    }
    REACTIONS.into_iter().find(|r| bare(r) == bare(emoji.trim()))
}

/// In a job's report, a line of its own that puts the report on his screen.
pub const FOR_AVIV: &str = "[[aviv]]";

/// A job's id, `job-repo-what-1793546047`. What a call queued goes by a thread's: that one is for him,
/// read out, and no session comes after it to tell him.
fn is_job(from: &str) -> bool {
    from.starts_with("job-") && !from.contains(char::is_whitespace) && from.rsplit('-').next().is_some_and(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()))
}

/// An issue's news, `issue 12` (cli/src/issue/sink.rs): its turn is off his screen, as a job's report's.
fn is_issue(from: &str) -> bool {
    from.strip_prefix("issue ").is_some_and(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()))
}

/// What a helper's report goes by: `HELPER` and its description (`server/tab.rs`, `task_ended`).
pub const HELPER: &str = "helper: ";

/// A helper's report: an Agent-tool helper the session ran in the background. Older records have its
/// description alone, which starts with its step word (`find:`, `read:`...).
pub fn is_helper(from: &str) -> bool {
    from.starts_with(HELPER) || ["find:", "read:", "wait:", "fact:", "decide:"].iter().any(|p| from.starts_with(p))
}

impl Body {
    /// A job's report: it's for the session of its tab, which tells him in its own reply. It stays on
    /// the record and goes to no screen, and is nothing unread, unless a line of it is `FOR_AVIV`. So is
    /// an autopilot's review, its done line and proof among them: Aviv, 2026-11-03, "I don't want to
    /// see these messages". So are an issue's news, a helper's report and the watchdog's word on a wait: Aviv, 2026-11-07,
    /// "im not supposed to see messages from workers".
    pub fn for_session(&self) -> bool {
        matches!(self, Body::Report { from, text, .. } if (is_job(from) || is_issue(from) || is_helper(from) || from == "review" || from == "watchdog") && !text.lines().any(|l| l.trim() == FOR_AVIV))
    }

    /// A page of a library conversation someone other than him wrote (code's progress, a review's verdict, a worker's
    /// report, Home's post), as a thread's tab put it on its record before task 1762: a report from `library` (code's
    /// name in the tab server, `fold::FROM`) or from Home. Since then each lands as an aside; on the records from
    /// before it is still the runner's bookkeeping, never his. Aviv, 2026-11-11: "why do I see its thinking process?
    /// it shouldn't be visible to me".
    pub fn library_report(&self) -> bool {
        matches!(self, Body::Report { from, text, .. } if ["library", "home", "Home"].contains(&from.as_str()) && !text.lines().any(|l| l.trim() == FOR_AVIV))
    }

    /// No screen draws it and it is nothing new to read: a report for the session, or a library page from before
    /// task 1762. What the session reads of it stays as it was, so a record's seed keeps its bytes.
    pub fn off_screen(&self) -> bool {
        self.for_session() || self.library_report()
    }
}

/// The first line of a report, for a report with no line of its own.
pub fn first_line(text: &str) -> String {
    text.trim().lines().next().unwrap_or("").trim_start_matches(['#', '*', ' ']).trim_end_matches('*').to_string()
}

/// What Jarvis's ticks on his message at `to` stand for: the words each tick took the place of, kept as an
/// aside written right after it (only more of Jarvis's reactions between, as two ticks taking the place of
/// one). A tick taken back still counts: a ✓ that ✓✓ replaced is part of what happened. Each once, in record
/// order, a blank line between; empty when no words were kept. An aside further on is about something else,
/// so it is never taken.
pub fn tick_words(events: &[Event], to: u64) -> String {
    let mut out: Vec<&str> = vec![];
    for (i, e) in events.iter().enumerate() {
        let tick = matches!(&e.body, Body::React { to: t, quote, emoji, who: Who::Jarvis, off: false } if *t == to && quote.is_empty() && (emoji == "✓" || emoji == "✓✓"));
        if !tick {
            continue;
        }
        let next = events[i + 1..].iter().find(|n| !matches!(n.body, Body::React { who: Who::Jarvis, .. }));
        if let Some(Event { body: Body::Aside { from, text }, .. }) = next {
            if from.is_empty() && !text.trim().is_empty() && !out.contains(&text.as_str()) {
                out.push(text);
            }
        }
    }
    out.join("\n\n")
}

#[cfg(test)]
mod ticks {
    use super::*;

    fn ev(offset: u64, body: Body) -> Event {
        Event { offset, at: offset, body }
    }
    fn his(offset: u64, text: &str) -> Event {
        ev(offset, Body::Said { who: Who::Aviv, text: text.into(), via: None, replies: vec![], files: vec![] })
    }
    fn react(offset: u64, to: u64, emoji: &str, who: Who, off: bool) -> Event {
        ev(offset, Body::React { to, quote: String::new(), emoji: emoji.into(), who, off })
    }
    fn aside(offset: u64, from: &str, text: &str) -> Event {
        ev(offset, Body::Aside { from: from.into(), text: text.into() })
    }

    #[test]
    fn tick_words_a_tick_then_its_aside_gives_the_aside() {
        let e = [his(10, "put the emulator on my laptop"), react(20, 10, "✓✓", Who::Jarvis, false), aside(30, "", "Filed as Phone emulator on the laptop.")];
        assert_eq!(tick_words(&e, 10), "Filed as Phone emulator on the laptop.");
    }

    #[test]
    fn tick_words_got_then_done_gives_both_in_order() {
        let e = [
            his(10, "do it"),
            react(20, 10, "✓", Who::Jarvis, false),
            aside(30, "", "On it."),
            react(40, 10, "✓", Who::Jarvis, true),
            react(50, 10, "✓✓", Who::Jarvis, false),
            aside(60, "", "Done: it runs."),
        ];
        assert_eq!(tick_words(&e, 10), "On it.\n\nDone: it runs.");
    }

    #[test]
    fn tick_words_a_thumb_or_another_sessions_aside_is_never_taken() {
        let thumb = [his(10, "x"), react(20, 10, "👍", Who::Jarvis, false), aside(30, "", "not this")];
        assert_eq!(tick_words(&thumb, 10), "");
        let other = [his(10, "x"), react(20, 10, "✓", Who::Jarvis, false), aside(30, "job-a-b-1", "a job's words")];
        assert_eq!(tick_words(&other, 10), "");
        let his_tick = [his(10, "x"), react(20, 10, "✓", Who::Aviv, false), aside(30, "", "not this")];
        assert_eq!(tick_words(&his_tick, 10), "");
    }

    #[test]
    fn tick_words_an_aside_after_his_next_message_is_never_taken() {
        let e = [his(10, "x"), react(20, 10, "✓", Who::Jarvis, false), his(30, "y"), aside(40, "", "about y")];
        assert_eq!(tick_words(&e, 10), "");
    }

    #[test]
    fn tick_words_takes_only_the_aside_right_after_the_tick() {
        // An old tick whose words were dropped: a later aside of the same turn is about something else.
        let said = ev(30, Body::Said { who: Who::Jarvis, text: "more".into(), via: None, replies: vec![], files: vec![] });
        let e = [his(10, "x"), react(20, 10, "✓", Who::Jarvis, false), said, aside(40, "", "Nothing for Aviv")];
        assert_eq!(tick_words(&e, 10), "");
        // Another of Jarvis's ticks between, as when one reply files two asks, is still right after.
        let two = [his(10, "a"), his(12, "b"), react(20, 10, "✓✓", Who::Jarvis, false), react(22, 12, "✓✓", Who::Jarvis, false), aside(30, "", "Filed both.")];
        assert_eq!(tick_words(&two, 10), "Filed both.");
        assert_eq!(tick_words(&two, 12), "Filed both.");
    }

    #[test]
    fn tick_words_no_tick_gives_empty() {
        assert_eq!(tick_words(&[his(10, "x"), aside(20, "", "words")], 10), "");
        assert_eq!(tick_words(&[], 10), "");
    }
}
