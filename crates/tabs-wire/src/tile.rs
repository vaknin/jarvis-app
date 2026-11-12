//! What a tile shows, folded from the record. `Tile::apply` is pure: a server keeps one per tab and
//! feeds it new events; a reconnect or a restart folds the file again and gets the same.
//!
//! - **Blocked:** a `needs` with none of his words after it, and the tab hasn't closed it.
//! - **Done:** something new to read past his last `seen`: Jarvis's written words, a report, a
//!   `needs`. His own words and lines he heard aloud aren't new. Opening the tab clears it; only
//!   answering clears blocked.
//! - **Working:** any `work` open, or a job the tab started still running. It shows alongside the other
//!   two.
//! - **Quiet:** none of them. A reply the server wrote `quiet` (docs/autonomy-plan.md §2, the unread
//!   rule) isn't new to read: it's a line on the digest screen, and `digest_new` is the tab's faint mark.
//!
//! Parking is none of these: it's a tab he put away, and news of it counts the same but goes nowhere.

use crate::autopilot::{ApTile, AskKind, Item};
use crate::event::{Body, By, Event, SessionState, Urgency, Via, Who, WorkState, STAR};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// A job's title ends with this when nothing has moved in its session for a long while.
pub const QUIET_JOB: &str = " · quiet";

/// A tab he put away for later (alt+p): off the row and asleep, until he takes it back. News of it shows
/// in the Later list and nowhere else.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Parked {
    pub at: u64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub note: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Tile {
    pub name: String,
    /// The tab's hidden line.
    pub line: String,
    /// What it needs from him. Empty when it isn't blocked.
    pub needs: String,
    /// On the wire it's still `amber`: the phone reads that, and a name needn't cost it anything.
    #[serde(rename = "amber")]
    pub blocked: bool,
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
    /// read them from boards.db. Their bar stands where the jobs line was; alt+j lists them. Empty since 2.0 went.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub issues: Vec<IssueBar>,
    /// The session running, by the record's word.
    pub session: Option<String>,
    /// It has had a session and none runs now. A tab that never ran one isn't asleep.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub asleep: bool,
    /// What the running session is on, from its start; kept after it ends, as the last it ran on.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub model: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub effort: String,
    /// His mode for the tab: empty is the default one.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub mode: String,
    /// He closed it (alt+x): out of the row, and its work goes on. His words or a
    /// `needs` end this, and it goes at the end of the row.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub hidden: bool,
    /// He parked it. It's `hidden` too, and only he brings it back.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parked: Option<Parked>,
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
    /// His stars standing on this tab: while there are any, its news reaches him as a toast.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub important: u32,
    /// How far he has read.
    pub seen: u64,
    /// The record's end when this was folded: the N to read on from.
    pub end: u64,
    /// When the last thing happened in it, in unix milliseconds: the tab list's date.
    #[serde(default, skip_serializing_if = "is_zero_u64")]
    pub last: u64,
    /// The offsets behind `unread`, so a client folding on from a listing counts what the server does.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    new: Vec<u64>,
    /// The offsets behind `digest_new`, the same way.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    digest: Vec<u64>,
}

/// One issue a tab filed, as its screens show it (plan section 10). Jarvis 2.0's boards filled it; since 2.0 went
/// nothing does, so the bars stay empty, and the type stays so old records still read.
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

/// Segments the bar shows at most (boards/src/progress.rs `SEGMENTS`).
pub const SEGMENTS: usize = 8;

/// The tab's bar: one segment per issue on the tile, in filing order, each its own step (0 to 4); past `SEGMENTS`
/// the most recent open ones. Then how many are done of all, and whether the freeze holds any. None with none.
pub fn issues_bar(issues: &[IssueBar]) -> Option<(Vec<u8>, usize, usize, bool)> {
    if issues.is_empty() {
        return None;
    }
    let steps: Vec<u8> = if issues.len() <= SEGMENTS {
        issues.iter().map(|i| i.step).collect()
    } else {
        let open: Vec<u8> = issues.iter().filter(|i| i.open()).map(|i| i.step).collect();
        open[open.len().saturating_sub(SEGMENTS)..].to_vec()
    };
    Some((steps, issues.iter().filter(|i| !i.open()).count(), issues.len(), issues.iter().any(|i| i.held)))
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

/// The tab's one small line about its jobs, written by the server: "2 jobs running: a, b. 3 queued: c,
/// d, e". Queued ones are never counted as running.
pub fn jobs_words(running: &[String], queued: &[String]) -> Option<String> {
    let list = |what: &str, all: &[String]| format!("{} job{} {what}: {}", all.len(), if all.len() == 1 { "" } else { "s" }, all.join(", "));
    match (running.is_empty(), queued.is_empty()) {
        (true, true) => None,
        (false, true) => Some(list("running", running)),
        (true, false) => Some(list("queued", queued)),
        (false, false) => Some(format!("{}. {} queued: {}", list("running", running), queued.len(), queued.join(", "))),
    }
}

/// What a session is told of its tab's jobs: which run and which wait, so it never calls a queued one
/// running.
pub fn jobs_note(running: &[String], queued: &[String]) -> Option<String> {
    let mut parts = vec![];
    if !running.is_empty() {
        parts.push(format!("Jobs you started, still running: {}.", running.join("; ")));
    }
    if !queued.is_empty() {
        parts.push(format!("Jobs you started, queued and not started yet: {}.", queued.join("; ")));
    }
    (!parts.is_empty()).then(|| format!("({} Each one's report comes here as a turn.)", parts.join(" ")))
}

fn is_zero_u64(n: &u64) -> bool {
    *n == 0
}

fn is_zero(n: &u32) -> bool {
    *n == 0
}

impl Tile {
    pub fn done(&self) -> bool {
        self.unread > 0
    }

    pub fn working(&self) -> bool {
        self.at_work() || !self.jobs.is_empty()
    }

    /// A turn or a helper in the tab itself: its jobs alone leave a tab asleep, with nothing to stop.
    pub fn at_work(&self) -> bool {
        !self.open.is_empty()
    }

    /// Only its jobs are running: the session is idle, so nothing here is Jarvis writing.
    pub fn background(&self) -> bool {
        !self.at_work() && !self.jobs.is_empty()
    }

    /// The tab's one small line about its jobs, as the server wrote it: every screen shows these words
    /// and counts nothing itself. A record from before the server wrote it gets the same words here.
    pub fn jobs_line(&self) -> Option<String> {
        Some(self.jobs_said.clone()).filter(|l| !l.is_empty()).or_else(|| jobs_words(&self.jobs, &self.queued))
    }

    /// Only digest lines are new: the faint mark, not the dot.
    pub fn faint(&self) -> bool {
        self.digest_new > 0 && !self.done()
    }

    pub fn idle(&self) -> bool {
        !self.blocked && !self.done() && !self.working()
    }

    /// Whether a `seen` up to `upto` would change anything: a tab opened again and again adds no lines.
    pub fn seen_moves(&self, upto: u64) -> bool {
        upto > self.seen && self.new.iter().chain(&self.digest).any(|o| *o <= upto)
    }

    pub fn apply(&mut self, e: &Event) {
        self.end = self.end.max(e.offset);
        self.last = self.last.max(e.at);
        match &e.body {
            Body::Said { who: Who::Aviv, replies, .. } => {
                // His answer to a question is his words replying to it: a tap or typed, the same.
                self.asks.retain(|a| !replies.iter().any(|r| r.to == a.offset));
                self.hidden = false;
                self.parked = None;
                // A blocking question he hasn't answered still holds it.
                match self.asks.iter().find(|a| a.blocks) {
                    Some(a) => self.needs = a.items.first().map(|i| i.text.clone()).unwrap_or_default(),
                    None => {
                        self.blocked = false;
                        self.needs.clear();
                    }
                }
            }
            Body::Said { via, .. } => {
                if *via != Some(Via::Voice) {
                    self.mark_new(e.offset);
                }
            }
            Body::Report { .. } => {
                // A parked tab's session is asleep, so nobody tells him: the list's dot does.
                if !e.body.off_screen() || self.parked.is_some() {
                    self.mark_new(e.offset);
                }
            }
            Body::Needs { closed: true, q, .. } if !q.is_empty() => match self.asks.iter().find(|a| a.blocks) {
                // Another blocking question of the tab still stands.
                Some(a) => self.needs = a.items.first().map(|i| i.text.clone()).unwrap_or_default(),
                None => {
                    self.blocked = false;
                    self.needs.clear();
                }
            },
            Body::Needs { text, q, .. } if !q.is_empty() => {
                // A blocking question's twin, for screens from before questions: the card is what's new.
                self.hidden &= self.parked.is_some();
                self.blocked = true;
                self.needs = text.clone();
            }
            Body::Needs { closed: true, .. } => {
                self.blocked = false;
                self.needs.clear();
            }
            Body::Needs { text, .. } => {
                // A tab that truly needs him can't stay out of sight, unless he put it away himself.
                self.hidden &= self.parked.is_some();
                self.blocked = true;
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
                self.open.clear();
                self.session = Some(id.clone());
                self.asleep = false;
                if !model.is_empty() {
                    self.model = model.clone();
                    self.effort = effort.clone();
                }
            }
            // Only the session running: a reseed can write the new start before the old end.
            Body::Session { id, state: SessionState::Ended, .. } => {
                if self.session.as_deref().is_none_or(|s| s == id) {
                    self.open.clear();
                    self.session = None;
                    self.asleep = true;
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
            Body::Named { name } => self.name = name.clone(),
            Body::Hidden { hidden } => {
                self.hidden = *hidden;
                if !hidden {
                    self.parked = None;
                }
            }
            Body::Parked { note } => {
                self.hidden = true;
                self.parked = Some(Parked { at: e.at, note: note.clone() });
            }
            Body::Mode { mode } => self.mode = crate::mode::canon(mode).to_string(),
            Body::Level { level, .. } => self.level = *level,
            Body::Defaults { on, arm, .. } => {
                self.defaults = *on;
                for a in &mut self.asks {
                    a.due = arm.iter().find(|(q, _)| *q == a.q).map(|(_, d)| *d).filter(|_| *on);
                }
            }
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
            Body::React { emoji, who: Who::Aviv, off, .. } if emoji == STAR => {
                self.important = if *off { self.important.saturating_sub(1) } else { self.important + 1 };
            }
            Body::Opened { .. } | Body::Closed { .. } | Body::Moved { .. } | Body::Row { .. } | Body::Pinned { .. } | Body::Watched { .. } | Body::Aside { .. } | Body::Delivered { .. } | Body::React { .. } | Body::Toast { .. } | Body::Hands { .. } | Body::Other => {}
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
mod tests {
    use super::*;

    fn press(t: &mut Tile, emoji: &str, who: Who, off: bool) {
        let e = Event { offset: t.end + 1, at: 1, body: Body::React { to: 1, quote: String::new(), emoji: emoji.into(), who, off } };
        t.apply(&e);
    }

    #[test]
    fn only_his_stars_mark_a_tab_and_a_reaction_is_nothing_to_read() {
        let mut t = Tile::default();
        press(&mut t, "👍", Who::Aviv, false);
        press(&mut t, STAR, Who::Jarvis, false);
        assert_eq!((t.important, t.unread), (0, 0));
        press(&mut t, STAR, Who::Aviv, false);
        press(&mut t, STAR, Who::Aviv, false);
        press(&mut t, STAR, Who::Aviv, true);
        assert_eq!(t.important, 1);
        press(&mut t, STAR, Who::Aviv, true);
        press(&mut t, STAR, Who::Aviv, true);
        assert_eq!(t.important, 0, "never below none");
    }

    fn land(t: &mut Tile, body: Body) {
        let e = Event { offset: t.end + 1, at: 7, body };
        t.apply(&e);
    }

    fn report(from: &str) -> Body {
        Body::Report { from: from.into(), line: "line".into(), text: "text".into(), files: vec![] }
    }

    #[test]
    fn a_parked_tab_stays_away_through_news_and_counts_it() {
        let mut t = Tile::default();
        land(&mut t, Body::Parked { note: "after the exam".into() });
        assert!(t.hidden);
        assert_eq!(t.parked, Some(Parked { at: 7, note: "after the exam".into() }));
        land(&mut t, report("job-a-1793586019"));
        assert_eq!(t.unread, 1, "nobody tells him, so it's new to him");
        land(&mut t, Body::Needs { text: "which one?".into(), closed: false, q: String::new() });
        assert!(t.hidden && t.parked.is_some() && t.blocked, "needing him doesn't bring it back");
    }

    #[test]
    fn he_brings_a_parked_tab_back_by_a_word_or_the_list() {
        for back in [Body::Said { who: Who::Aviv, text: "hi".into(), via: None, replies: vec![], files: vec![] }, Body::Hidden { hidden: false }] {
            let mut t = Tile::default();
            land(&mut t, Body::Parked { note: String::new() });
            land(&mut t, back);
            assert!(!t.hidden && t.parked.is_none());
        }
    }

    #[test]
    fn a_level_is_the_last_one_set_and_nothing_to_read() {
        use crate::event::By;
        let mut t = Tile::default();
        assert_eq!(t.level, Urgency::Default);
        land(&mut t, Body::Level { level: Urgency::Urgent, by: By::Aviv });
        assert_eq!((t.level, t.unread), (Urgency::Urgent, 0));
        land(&mut t, Body::Level { level: Urgency::Low, by: By::Tab });
        assert_eq!(t.level, Urgency::Low);
        let e: Event = serde_json::from_str(r#"{"at":1,"kind":"level","level":"urgent","by":"aviv"}"#).unwrap();
        assert_eq!(e.body, Body::Level { level: Urgency::Urgent, by: By::Aviv });
    }

    #[test]
    fn out_of_later_a_jobs_report_is_the_sessions_again() {
        let mut t = Tile::default();
        land(&mut t, report("job-a-1793586019"));
        assert_eq!(t.unread, 0);
        land(&mut t, Body::Needs { text: "which one?".into(), closed: false, q: String::new() });
        assert!(!t.hidden);
    }
}
