//! The shapes of questions and of autopilot (docs/autonomy-plan.md §2), shared by the server and
//! both screens. Like events they only grow: a value a reader doesn't know is that enum's `Other`.

use serde::{Deserialize, Serialize};

/// What a question is about, which sets whether silence may answer it (`tabs/src/asks.rs`). A tab
/// files the six; `intake` and `stuck` come from code alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AskKind {
    Intake,
    Money,
    Irreversible,
    Wall,
    Hands,
    Ux,
    Tie,
    Stuck,
    #[serde(other)]
    Other,
}

impl AskKind {
    pub const FROM_TAB: [AskKind; 6] = [AskKind::Money, AskKind::Irreversible, AskKind::Wall, AskKind::Hands, AskKind::Ux, AskKind::Tie];

    pub fn name(&self) -> &'static str {
        match self {
            AskKind::Intake => "intake",
            AskKind::Money => "money",
            AskKind::Irreversible => "irreversible",
            AskKind::Wall => "wall",
            AskKind::Hands => "hands",
            AskKind::Ux => "ux",
            AskKind::Tie => "tie",
            AskKind::Stuck => "stuck",
            AskKind::Other => "other",
        }
    }

    pub fn parse(s: &str) -> Option<AskKind> {
        [AskKind::Intake, AskKind::Money, AskKind::Irreversible, AskKind::Wall, AskKind::Hands, AskKind::Ux, AskKind::Tie, AskKind::Stuck].into_iter().find(|k| k.name() == s)
    }
}

/// The last option of every card's question (intake, and every `jarvis ask` since t251), so the tab's options
/// never stand between him and his own answer (E1's t81: none of the four was what he meant).
pub const ELSE: &str = "Something else (write it)";

/// One question of a card: its options, Jarvis's pick, and for money, irreversible, wall and ux the
/// safe one (spends nothing, changes nothing, keeps what he has). Options are counted from 0.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Item {
    pub text: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub options: Vec<String>,
    #[serde(default)]
    pub pick: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub safe: Option<u32>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub why: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub loses: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub gets: String,
    /// Under 2.0, the issue whose worker asked it (`jarvis issue ask`): his answer goes there as a comment.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub issue: Option<u64>,
    /// Under the library fold, the library's question (`iris ask`) this card shows: his answer settles it there and
    /// reaches the conversation that asked.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub library: Option<u64>,
}

/// Why a reply went to the digest screen and didn't light its tab (J3 writes these).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum QuietWhy {
    /// Work finished, or progress: nothing asked, nothing for him word for word.
    Done,
    /// A job's or a review's report came in and the reply only passes it on.
    Report,
    /// A deadline took a question's default.
    Default,
    #[serde(other)]
    Other,
}

/// A tab's autopilot, whole: his words, the criteria a checked "done" is measured by, and where it is.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Autopilot {
    pub id: String,
    /// His words, copied by the server from the record.
    pub words: String,
    /// The offset of those words.
    #[serde(default)]
    pub from: u64,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub criteria: Vec<Criterion>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub repos: Vec<String>,
    /// Spent so far, in millionths of a dollar: shown, never a cap.
    #[serde(default)]
    pub spent: u64,
    /// Its one free nudge for these strays went in: the next stray raises the stuck card.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub nudged: bool,
    /// Turns in a row the server started that moved nothing and stopped without asking, finishing or
    /// waiting: the one stall count (an old file's `idle` is ignored).
    #[serde(default)]
    pub strays: u32,
    #[serde(default)]
    pub rounds: u32,
    pub state: ApState,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub summary: String,
    /// The commits its passing review listed, "REPO SHA subject": what must be live before "Done".
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub commits: Vec<String>,
    /// His one line "Done: ...": false while its commits aren't live yet, true once posted, never again.
    /// None for an autopilot done before the line existed, which never posts it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub told: Option<bool>,
    /// The one line he reads once it is live ("Done: " and this), written to him: checked by the server at
    /// `done`, kept apart from `summary`, which is the reviewer's.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub tell: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Criterion {
    pub n: u32,
    pub what: String,
    pub check: Check,
    pub status: Status,
    /// Who set the status: the tab, a check, the reviewer.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub by: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub evidence: String,
    /// The review couldn't judge it as written (unverifiable here, or it names code that's gone): the next
    /// `done` waits for a `jarvis autopilot edit` of it.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub stale: bool,
}

/// How a criterion is checked: a command run in a repo, or something to look at.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "how", rename_all = "lowercase")]
pub enum Check {
    Cmd { cmd: String, dir: String },
    /// A file, a picture or a URL to open.
    Look { at: String },
    #[serde(other)]
    Other,
}

impl Default for Check {
    fn default() -> Self {
        Check::Look { at: String::new() }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    #[default]
    Open,
    Claimed,
    Pass,
    Fail,
    #[serde(other)]
    Other,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ApState {
    #[default]
    Asking,
    Running,
    Review,
    Done,
    Accepted,
    Paused,
    Held,
    Stalled,
    Dropped,
    #[serde(other)]
    Other,
}

/// What a tile shows of a tab's autopilot.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ApTile {
    pub id: String,
    pub state: ApState,
    #[serde(default)]
    pub passed: u32,
    #[serde(default)]
    pub of: u32,
    #[serde(default)]
    pub spent: u64,
    /// Why it's held or stalled, when it is.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub why: String,
}

impl ApTile {
    pub fn of(ap: &Autopilot) -> ApTile {
        ApTile {
            id: ap.id.clone(),
            state: ap.state,
            passed: ap.criteria.iter().filter(|c| c.status == Status::Pass).count() as u32,
            of: ap.criteria.len() as u32,
            spent: ap.spent,
            why: if matches!(ap.state, ApState::Held | ApState::Stalled | ApState::Paused) { ap.summary.clone() } else { String::new() },
        }
    }
}
