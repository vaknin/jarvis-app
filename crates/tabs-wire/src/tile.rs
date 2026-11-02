//! What a tile shows, folded from the record. `Tile::apply` is pure: a server keeps one per tab and
//! feeds it new events; a reconnect or a restart folds the file again and gets the same.
//!
//! - **Amber:** a `needs` with none of his words after it, and the tab hasn't closed it.
//! - **Blue:** something new to read past his last `seen`: Jarvis's written words, a report, a
//!   `needs`. His own words and lines he heard aloud aren't new. Opening the tab clears it; only
//!   answering clears amber.
//! - **Working:** any `work` open, or a job the tab started still running. It shows alongside the other
//!   two.
//! - **Quiet:** none of them.

use crate::event::{Body, Event, SessionState, Via, Who, WorkState};
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
    /// The session running, by the record's word.
    pub session: Option<String>,
    /// How far he has read.
    pub seen: u64,
    /// The record's end when this was folded: the N to read on from.
    pub end: u64,
    /// The offsets behind `unread`, so a client folding on from a listing counts what the server does.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    new: Vec<u64>,
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

    pub fn quiet(&self) -> bool {
        !self.amber && !self.blue() && !self.working()
    }

    /// Whether a `seen` up to `upto` would change anything: a tab opened again and again adds no lines.
    pub fn seen_moves(&self, upto: u64) -> bool {
        upto > self.seen && self.new.iter().any(|o| *o <= upto)
    }

    pub fn apply(&mut self, e: &Event) {
        self.end = self.end.max(e.offset);
        match &e.body {
            Body::Said { who: Who::Aviv, .. } => {
                self.amber = false;
                self.needs.clear();
            }
            Body::Said { via, .. } => {
                if *via != Some(Via::Voice) {
                    self.mark_new(e.offset);
                }
            }
            Body::Report { .. } => self.mark_new(e.offset),
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
            Body::Session { id, state: SessionState::Started, .. } => {
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
            }
            Body::Jobs { jobs, queued, line } => {
                self.jobs = jobs.clone();
                self.queued = queued.clone();
                self.jobs_said = line.clone();
            }
            Body::Line { text } => self.line = text.clone(),
            Body::Named { name } => self.name = name.clone(),
            Body::Opened { .. } | Body::Closed { .. } | Body::Moved { .. } | Body::Row { .. } | Body::Delivered { .. } | Body::Other => {}
        }
    }

    fn mark_new(&mut self, offset: u64) {
        if offset > self.seen {
            self.new.push(offset);
            self.unread = self.new.len();
        }
    }
}
