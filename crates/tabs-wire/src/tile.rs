//! What a tile shows, folded from the record. `Tile::apply` is pure: a server keeps one per tab and
//! feeds it new events; a reconnect or a restart folds the file again and gets the same.
//!
//! - **Amber:** a `needs` with none of his words after it, and the tab hasn't closed it.
//! - **Blue:** something new to read past his last `seen`: Jarvis's written words, a report, a
//!   `needs`. His own words and lines he heard aloud aren't new. Opening the tab clears it; only
//!   answering clears amber.
//! - **Working:** any `work` open, or a job the tab started still running. It shows alongside the other
//!   two.
//! - **Quiet:** none of them. A reply the server wrote `quiet` (docs/autonomy-plan.md §2, the unread
//!   rule) isn't new to read: it's a line on the digest screen, and `digest_new` is the tab's faint mark.

use crate::autopilot::{ApTile, AskKind, Item};
use crate::event::{Body, By, Event, SessionState, Urgency, Via, Who, WorkState};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Tile {
    pub name: String,
    /// The tab's hidden line.
    pub line: String,
    /// What it needs from him. Empty when it isn't amber.
    pub needs: String,
    pub amber: bool,
    /// What the running session is on, from its start; kept after it ends, as the last it ran on.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub model: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub effort: String,
    /// His mode for the tab: empty is the default one.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub mode: String,
    /// How many things are new to read.
    pub unread: usize,
    /// Open work, by id: what it is.
    pub open: BTreeMap<String, String>,
    /// The jobs it started that are still running: their titles.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub jobs: Vec<String>,
    /// The jobs it started that wait their turn (a slot, a limit's hold): not running, so not working.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub queued: Vec<String>,
    /// The line about them, in the server's words (`jobs_line`).
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub jobs_said: String,
    /// Jarvis 2.0 only (`~/.jarvis/v2`): the issues this tab filed, open ones and those done today, as the server
    /// reads them from boards.db. Their bar stands where the jobs line was; alt+j lists them. Empty without 2.0.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub issues: Vec<IssueBar>,
    /// The session running, by the record's word.
    pub session: Option<String>,
    /// The tab's level: how hard what it asks reaches him.
    #[serde(default, skip_serializing_if = "Urgency::is_default")]
    pub level: Urgency,
    /// He let its cards take their pick when he's silent (`Body::Defaults`). Off, none has a `due`.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub defaults: bool,
    /// Its autopilot, if it has one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub autopilot: Option<ApTile>,
    /// Its questions still open, oldest first: the Needs you list is these, from every tab.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub asks: Vec<OpenAsk>,
    /// Lines on the digest screen he hasn't looked at.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub digest_new: u32,
    /// How far he has read.
    pub seen: u64,
    /// The record's end when this was folded: the N to read on from.
    pub end: u64,
    /// The offsets behind `unread`, so a client folding on from a listing counts what the server does.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    new: Vec<u64>,
    /// The offsets behind `digest_new`, the same way.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    digest: Vec<u64>,
}

/// One issue a tab filed, as its screens show it (plan section 10). The server fills it from
/// `jarvis_boards::progress`, so every screen says the same.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct IssueBar {
    pub id: i64,
    pub title: String,
    /// Its board's name.
    pub board: String,
    /// 0 Planning, 1 Building, 2 Testing, 3 Shipping, 4 Done: never moves back.
    pub step: u8,
    /// The try running or next; the screens say it from 2 on.
    #[serde(default, skip_serializing_if = "is_one")]
    pub try_n: u32,
    /// Where it is, in words: "Testing · try 2", "Planning (try 3)", "Waiting on you".
    pub label: String,
    /// The usage freeze holds it back.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub held: bool,
    /// "about 40 min", once five like it are done on its board; else empty and nothing shows.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub eta: String,
    /// Shown only when he opens its row: why it's where it is, and the rest.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub detail: Vec<String>,
}

fn is_one(n: &u32) -> bool {
    *n <= 1
}

/// The steps by name, for the stepper.
pub const STEPS: [&str; 5] = ["Planning", "Building", "Testing", "Shipping", "Done"];

impl IssueBar {
    pub fn open(&self) -> bool {
        self.step < 4
    }
}

/// The tab's bar: the average step of its open issues, 0.0 to 1.0, how many, and whether the freeze holds any.
/// None with nothing open.
pub fn issues_bar(issues: &[IssueBar]) -> Option<(f64, usize, bool)> {
    let open: Vec<&IssueBar> = issues.iter().filter(|i| i.open()).collect();
    (!open.is_empty()).then(|| (open.iter().map(|i| f64::from(i.step)).sum::<f64>() / (open.len() as f64 * 4.0), open.len(), open.iter().any(|i| i.held)))
}

/// A question of the tab's that's still open: answered by his `said` replying to `offset`, or closed
/// by an `answer` (a default taken, or taken back).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct OpenAsk {
    pub q: String,
    /// The question's event: what his answer replies to.
    pub offset: u64,
    pub at: u64,
    pub ask: Option<AskKind>,
    #[serde(default, skip_serializing_if = "Urgency::is_default")]
    pub level: Urgency,
    pub items: Vec<Item>,
    /// When silence takes the picks: none, never, as when the tab's defaults are off.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub due: Option<u64>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub blocks: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub suggest_urgent: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ap: Option<String>,
}

fn is_zero(n: &u32) -> bool {
    *n == 0
}

impl Tile {
    pub fn blue(&self) -> bool {
        self.unread > 0
    }

    pub fn working(&self) -> bool {
        self.at_work() || !self.jobs.is_empty()
    }

    /// A turn or a helper in the tab itself: its jobs alone leave a tab asleep, with nothing to stop.
    pub fn at_work(&self) -> bool {
        !self.open.is_empty()
    }

    /// The tab's one small line about its jobs, as the server wrote it: the phone shows these words
    /// and counts nothing itself. A record from before the server wrote it gets the old words.
    pub fn jobs_line(&self) -> Option<String> {
        let n = self.jobs.len();
        Some(self.jobs_said.clone()).filter(|l| !l.is_empty()).or_else(|| (n > 0).then(|| format!("{n} job{} running: {}", if n == 1 { "" } else { "s" }, self.jobs.join(", "))))
    }

    /// Only digest lines are new: the faint mark, not the dot.
    pub fn faint(&self) -> bool {
        self.digest_new > 0 && !self.amber && !self.blue()
    }

    pub fn quiet(&self) -> bool {
        !self.amber && !self.blue() && !self.working()
    }

    /// Whether a `seen` up to `upto` would change anything: a tab opened again and again adds no lines.
    pub fn seen_moves(&self, upto: u64) -> bool {
        upto > self.seen && self.new.iter().chain(&self.digest).any(|o| *o <= upto)
    }

    pub fn apply(&mut self, e: &Event) {
        self.end = self.end.max(e.offset);
        match &e.body {
            Body::Said { who: Who::Aviv, replies, .. } => {
                // His answer to a question is his words replying to it: a tap or typed, the same.
                self.asks.retain(|a| !replies.iter().any(|r| r.to == a.offset));
                self.unblock();
            }
            Body::Said { via, .. } => {
                if *via != Some(Via::Voice) {
                    self.mark_new(e.offset);
                }
            }
            Body::Report { .. } => self.mark_new(e.offset),
            Body::Needs { closed: true, q, .. } if !q.is_empty() => self.unblock(),
            Body::Needs { text, q, .. } if !q.is_empty() => {
                // A blocking question's twin, for screens from before questions: the card is what's new.
                self.amber = true;
                self.needs = text.clone();
            }
            Body::Needs { closed: true, .. } => {
                self.amber = false;
                self.needs.clear();
            }
            Body::Needs { text, .. } => {
                self.amber = true;
                self.needs = text.clone();
                self.mark_new(e.offset);
            }
            Body::Work { id, state: WorkState::Started, what, .. } => {
                self.open.insert(id.clone(), what.clone());
            }
            Body::Work { id, state: WorkState::Ended, .. } => {
                self.open.remove(id);
            }
            Body::Work { .. } => {}
            Body::Session { id, state: SessionState::Started, model, effort, .. } => {
                if !model.is_empty() {
                    self.model = model.clone();
                    self.effort = effort.clone();
                }
                self.open.clear();
                self.session = Some(id.clone());
            }
            // Only the session running: a reseed can write the new start before the old end.
            Body::Session { id, state: SessionState::Ended, .. } => {
                if self.session.as_deref().is_none_or(|s| s == id) {
                    self.open.clear();
                    self.session = None;
                }
            }
            Body::Session { .. } => {}
            Body::Seen { upto } => {
                self.seen = self.seen.max(*upto);
                let seen = self.seen;
                self.new.retain(|o| *o > seen);
                self.unread = self.new.len();
                self.digest.retain(|o| *o > seen);
                self.digest_new = self.digest.len() as u32;
            }
            Body::Jobs { jobs, queued, line, issues } => {
                self.issues = issues.clone();
                self.jobs = jobs.clone();
                self.queued = queued.clone();
                self.jobs_said = line.clone();
            }
            Body::Line { text } => self.line = text.clone(),
            Body::Level { level, .. } => self.level = *level,
            Body::Defaults { on, arm, .. } => {
                self.defaults = *on;
                for a in &mut self.asks {
                    a.due = arm.iter().find(|(q, _)| *q == a.q).map(|(_, d)| *d).filter(|_| *on);
                }
            }
            Body::Named { name } => self.name = name.clone(),
            Body::Mode { mode } => self.mode = mode.clone(),
            Body::Question { q, ap, ask, level, suggest_urgent, items, due, blocks } => {
                self.asks.retain(|a| a.q != *q);
                self.asks.push(OpenAsk { q: q.clone(), offset: e.offset, at: e.at, ask: Some(*ask), level: *level, items: items.clone(), due: due.filter(|_| self.defaults), blocks: *blocks, suggest_urgent: *suggest_urgent, ap: ap.clone() });
                self.mark_new(e.offset);
            }
            Body::Answer { q, by, .. } => {
                self.asks.retain(|a| a.q != *q);
                if *by == By::Default {
                    self.mark_digest(e.offset);
                }
            }
            Body::Autopilot { ap } => self.autopilot = Some(ApTile::of(ap)),
            Body::Assumed { .. } => self.mark_digest(e.offset),
            // The reply goes from the dot to the digest screen.
            Body::Quiet { to, .. } => {
                if let Some(i) = self.new.iter().position(|o| o == to) {
                    self.new.remove(i);
                    self.unread = self.new.len();
                }
                self.mark_digest(*to);
            }
            Body::Opened { .. } | Body::Closed { .. } | Body::Moved { .. } | Body::Row { .. } | Body::Delivered { .. } | Body::Other => {}
        }
    }

    /// Amber clears, unless a blocking question he hasn't answered still holds it.
    fn unblock(&mut self) {
        match self.asks.iter().find(|a| a.blocks) {
            Some(a) => self.needs = a.items.first().map(|i| i.text.clone()).unwrap_or_default(),
            None => {
                self.amber = false;
                self.needs.clear();
            }
        }
    }

    fn mark_digest(&mut self, offset: u64) {
        if offset > self.seen && !self.digest.contains(&offset) {
            self.digest.push(offset);
            self.digest_new = self.digest.len() as u32;
        }
    }

    fn mark_new(&mut self, offset: u64) {
        if offset > self.seen {
            self.new.push(offset);
            self.unread = self.new.len();
        }
    }
}

#[cfg(test)]
mod mode_tests {
    use super::*;

    fn at(body: Body) -> Event {
        Event { offset: 1, at: 1, body }
    }

    #[test]
    fn mode_and_session_model_fold_into_the_tile() {
        let mut t = Tile::default();
        t.apply(&at(Body::Mode { mode: "sonnet-high".into() }));
        assert_eq!(t.mode, "sonnet-high");
        t.apply(&at(Body::Session {
            id: "s".into(),
            state: SessionState::Started,
            why: String::new(),
            cut: None,
            turn: None,
            model: "claude-opus-4".into(),
            effort: "high".into(),
        }));
        assert_eq!((t.model.as_str(), t.effort.as_str()), ("claude-opus-4", "high"));
        // A start with no model (an older record) keeps what it had.
        t.apply(&at(Body::Session { id: "s2".into(), state: SessionState::Started, why: String::new(), cut: None, turn: None, model: String::new(), effort: String::new() }));
        assert_eq!(t.model, "claude-opus-4");
    }
}
