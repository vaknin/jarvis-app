//! The tab screens' link to the tab server (the jarvis repo's `jarvis-tabs`), over the forum tunnel
//! with the handshake `tabs1`, which the gate pipes to the server's socket. The number moves if the
//! frames ever change in a way an older app can't read, so a stale phone is refused, not confused.
//!
//! The screens only draw. [`Desk`] folds frames into what they draw, and owns no decision of its own:
//! a tile comes from the wire's `Tile`, in the same way as on the server. Every tab is followed from
//! its end for its marks; a tab on screen is followed from 0 for its record. Only what changed
//! crosses to Kotlin: the tail of a record from the first entry that moved.
//!
//! Only while the app is on screen: [`tabs_attach`] and [`tabs_detach`] bring the link up and down,
//! and the desk stays between, so coming back draws at once and reads on from where it stopped. A
//! short trip away (a picker, a notification) keeps the link: it goes [`AWAY`] after the screens do.
//! A drop is redialed at once and read on from each tab's end; the screens call it offline only
//! once it has lasted [`DOWN_GRACE`], so a new build on the server or a change of network passes
//! unseen.

use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    sync::{Arc, LazyLock, Mutex},
    time::{Duration, Instant},
};

use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use jarvis_tabs_wire::{
    frame::{read_async, write_async},
    ApState, Ask, Body, Card, DigestLine, Event, FileRef, Info, Item, OpenAsk, Reply, Tell, Tile, Urgency, Via, WorkEnd, WorkState, Who, WALL,
};
use tokio::sync::{mpsc, Notify};

use crate::{forum, RT};

const TABS_HANDSHAKE: &[u8; 5] = b"tabs1";
/// Records kept in memory, the newest shown: going back to one is instant, and a long day stays small.
const KEEP: usize = 8;
/// A gate's restart or a tunnel's hiccup isn't worth a word on screen.
const GRACE: Duration = Duration::from_secs(2);
/// How often changes cross to the screen: a record's catch-up or a fast answer is one update.
const FRAME: Duration = Duration::from_millis(40);
/// Asked of a dropped link, doubling up to the last: an old gate refuses the handshake, and that
/// mustn't cost the battery.
const RETRY: (Duration, Duration) = (Duration::from_millis(200), Duration::from_secs(30));
/// Failed dials before a link may ask for a new endpoint.
const STALE: u32 = 4;
/// A link that was up and dropped is still shown as up this long: the server's swap to a new build
/// takes a few seconds, and so does a phone moving between networks.
const DOWN_GRACE: Duration = Duration::from_secs(10);
/// For this long after a drop, redials stay quick (at most [`QUICK`] apart) before backing off.
const QUICK_FOR: Duration = Duration::from_secs(30);
const QUICK: Duration = Duration::from_secs(1);
/// How long the link outlives the screens: coming back from a picker or a glance elsewhere finds it up.
const AWAY: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TileRec {
    pub id: String,
    pub n: u32,
    pub name: String,
    pub needs: bool,
    pub unread: bool,
    pub working: bool,
    /// His mode for the tab, canonical; empty is the default.
    pub mode: String,
    /// The model and effort of the mode (or of the default), short: "opus", "medium".
    pub model: String,
    pub effort: String,
    /// Its autopilot is on, as the server reads it (`autopilot_on`).
    pub autopilot: bool,
}

/// One tab a card's question stands in: what an answer names.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CardTabRec {
    pub id: String,
    pub name: String,
    pub q: String,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum Level {
    Low,
    Default,
    Urgent,
}

/// One question of a card: `pick` is Jarvis's, marked but not chosen.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct QuestionRec {
    pub text: String,
    pub options: Vec<String>,
    pub pick: u32,
    pub why: String,
}

/// A card of the Needs you list, or a question still open in its own tab. The flat fields are its first
/// question, `items` all of them (an autopilot's intake asks several). `due` is when silence takes it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CardRec {
    pub tabs: Vec<CardTabRec>,
    pub kind: String,
    pub level: Level,
    pub text: String,
    pub options: Vec<String>,
    pub pick: u32,
    pub why: String,
    pub due: Option<u64>,
    pub blocks: bool,
    pub at: u64,
    pub items: Vec<QuestionRec>,
}

/// A line of the digest: done, an assumption, a default taken, a stall.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DigestRec {
    pub id: String,
    pub name: String,
    pub at: u64,
    pub offset: u64,
    pub what: String,
    pub line: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClosedRec {
    pub id: String,
    pub name: String,
    pub line: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct QuoteRec {
    pub to: u64,
    pub quote: String,
    pub text: String,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum Landing {
    Sent,
    Sending,
    Refused,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum EntryRec {
    Yours { key: String, text: String, voice: bool, quotes: Vec<QuoteRec>, files: Vec<String>, state: Landing, why: String },
    Answer { key: String, offset: Option<u64>, text: String, files: Vec<String> },
    Report { key: String, from: String, line: String, text: String },
    Question { key: String, text: String, closed: bool },
    Quiet { key: String, text: String },
}

/// One issue a tab filed, as the bar and the overview show it (the wire's `IssueBar`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct IssueRec {
    pub id: i64,
    pub title: String,
    pub board: String,
    /// 0 Planning, 1 Building, 2 Testing, 3 Shipping, 4 Done.
    pub step: u8,
    pub try_n: u32,
    pub label: String,
    pub held: bool,
    pub eta: String,
    pub detail: Vec<String>,
}

fn issue_rec(i: &jarvis_tabs_wire::IssueBar) -> IssueRec {
    IssueRec {
        id: i.id,
        title: i.title.clone(),
        board: i.board.clone(),
        step: i.step.min(4),
        try_n: i.try_n,
        label: i.label.clone(),
        held: i.held,
        eta: i.eta.clone(),
        detail: i.detail.clone(),
    }
}

/// A tab that changed. `entries` replace everything from `from` on.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TabRec {
    pub tile: TileRec,
    pub from: u32,
    pub entries: Vec<EntryRec>,
    pub working: bool,
    pub what: String,
    /// When the work began, in ms.
    pub since: u64,
    /// The one small line about the jobs it started, empty with none.
    pub jobs: String,
    /// Jarvis 2.0 only: the issues it filed, open ones and those done today. Empty, the jobs line shows as today.
    pub issues: Vec<IssueRec>,
    /// His quiet words wait in it, for the turn's end or for a send-now.
    pub waiting: bool,
    pub closed: bool,
    pub loading: bool,
    /// Its questions still open, oldest first: the same cards as on the list.
    pub asks: Vec<CardRec>,
}

/// A file he attached: where the app keeps it, and what he calls it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OutFile {
    pub name: String,
    pub path: String,
}

#[uniffi::export(with_foreign)]
pub trait TabsListener: Send + Sync {
    fn wall(&self, tiles: Vec<TileRec>, closed: Vec<ClosedRec>, online: bool);
    fn tab(&self, tab: TabRec);
    /// A tab opened from words at the wall.
    fn opened(&self, id: String);
    /// Words that didn't land, for the box they came from: `id` is empty for the wall's.
    fn back(&self, id: String, text: String);
    /// A file asked for by `tabs_file`. Empty if it's gone from the server.
    fn file(&self, id: String, name: String, data: Vec<u8>);
    /// Files that didn't go after all: they go back to where he attaches.
    fn unsent(&self, files: Vec<OutFile>);
    /// The Needs you list and the digest, whole, whenever either changes.
    fn queue(&self, cards: Vec<CardRec>, digest: Vec<DigestRec>);
    /// An answer or an autopilot switch the server refused, in its words, for tab `id`.
    fn refused(&self, id: String, why: String);
}

#[derive(Debug, Clone, PartialEq)]
enum Told {
    Wall(Vec<TileRec>, Vec<ClosedRec>, bool),
    Tab(TabRec),
    Opened(String),
    Back(String, String),
    File(String, String, Vec<u8>),
    Unsent(Vec<OutFile>),
    Queue(Vec<CardRec>, Vec<DigestRec>),
    Refused(String, String),
}

/// An ask whose answer isn't about words: its `Landed` or `Refused` is its own.
#[derive(Debug, Clone, PartialEq)]
enum Asked {
    Answer,
    Autopilot,
}

fn level(l: Urgency) -> Level {
    match l {
        Urgency::Urgent => Level::Urgent,
        Urgency::Low => Level::Low,
        _ => Level::Default,
    }
}

fn card(tabs: Vec<CardTabRec>, kind: &str, lvl: Urgency, items: &[Item], due: Option<u64>, blocks: bool, at: u64) -> CardRec {
    let first = items.first().cloned().unwrap_or_default();
    let items = items.iter().map(|i| QuestionRec { text: i.text.clone(), options: i.options.clone(), pick: i.pick, why: i.why.clone() }).collect();
    CardRec { tabs, kind: kind.to_string(), level: level(lvl), text: first.text, options: first.options, pick: first.pick, why: first.why, due, blocks, at, items }
}

fn card_rec(c: &Card) -> CardRec {
    let tabs = c.tabs.iter().map(|t| CardTabRec { id: t.id.clone(), name: t.name.clone(), q: t.q.clone() }).collect();
    card(tabs, c.kind.name(), c.level, &c.items, c.due, c.blocks, c.at)
}

fn open_ask(id: &str, name: &str, a: &OpenAsk) -> CardRec {
    let tabs = vec![CardTabRec { id: id.to_string(), name: name.to_string(), q: a.q.clone() }];
    card(tabs, a.ask.map_or("other", |k| k.name()), a.level, &a.items, a.due, a.blocks, a.at)
}

fn digest_rec(d: &DigestLine) -> DigestRec {
    DigestRec { id: d.id.clone(), name: d.name.clone(), at: d.at, offset: d.offset, what: d.what.clone(), line: d.line.clone() }
}

/// A file on its way: read, as the frame carries it.
#[derive(Debug, Clone, PartialEq)]
struct Upload {
    file: OutFile,
    data: String,
}

/// Words and their files, until they've landed.
#[derive(Default)]
struct Pending {
    key: String,
    text: String,
    replies: Vec<Reply>,
    landed: bool,
    failed: Option<String>,
    /// What he attached, whole: the `Say` goes once the server has kept them all.
    files: Vec<OutFile>,
    /// Files the server hasn't said it kept yet.
    going: Vec<String>,
    kept: Vec<FileRef>,
    /// Held: the server keeps it for the turn's end, or for a send-now.
    quiet: bool,
}

impl Pending {
    /// Its `Say` is on the wire: only such a one can land or be refused as words.
    fn said(&self) -> bool {
        self.going.is_empty() && !self.landed && self.failed.is_none()
    }
}

#[derive(Default)]
struct Hold {
    n: u32,
    tile: Tile,
    closed: bool,
    /// Its record is here, or on its way.
    loaded: bool,
    items: Vec<(u64, EntryRec)>,
    applied: BTreeSet<u64>,
    end: u64,
    live: Option<(u64, String, String)>,
    pending: Vec<Pending>,
    began: HashMap<String, u64>,
    /// The last of his quiet words not gone in yet, 0 for none.
    waiting: u64,
    seen_sent: u64,
    /// The first entry that moved since the screen last heard. `None` with `touched` set: only the
    /// tail.
    from: Option<usize>,
    touched: bool,
}

impl Hold {
    fn moved(&mut self, at: usize) {
        self.from = Some(self.from.map_or(at, |f| f.min(at)));
        self.touched = true;
    }

    fn tail(&mut self) {
        let at = self.items.len();
        self.moved(at);
    }

    /// An event of this tab, once: the end of an old follow can cross the start of a new one.
    fn apply(&mut self, e: &Event, keys: &mut u64) {
        if !self.applied.insert(e.offset) {
            return;
        }
        self.end = self.end.max(e.offset);
        let key = || e.offset.to_string();
        let names = |files: &[FileRef]| files.iter().map(|f| f.name.clone()).collect::<Vec<_>>();
        let entry = match &e.body {
            Body::Said { who: Who::Aviv, text, via, replies, files } => {
                self.close_questions();
                if *via == Some(Via::Quiet) {
                    self.waiting = self.waiting.max(e.offset);
                }
                let mine = self.pending.iter().position(|p| p.text == *text && p.replies == *replies);
                let key = mine.map_or_else(key, |i| self.pending.remove(i).key);
                let quotes = replies.iter().map(|r| QuoteRec { to: r.to, quote: r.quote.clone(), text: r.text.clone() }).collect();
                Some(EntryRec::Yours {
                    key,
                    text: text.clone(),
                    voice: *via == Some(Via::Voice),
                    quotes,
                    files: names(files),
                    state: Landing::Sent,
                    why: String::new(),
                })
            }
            Body::Said { text, files, .. } => {
                // The words that were arriving become these: same key, so the screen just goes on.
                let key = self.live.take().map_or_else(key, |(_, k, _)| k);
                Some(EntryRec::Answer { key, offset: Some(e.offset), text: text.clone(), files: names(files) })
            }
            Body::Delivered { upto } => {
                if *upto >= self.waiting {
                    self.waiting = 0;
                }
                self.tail();
                None
            }
            Body::Report { from, line, text, .. } => {
                let line = if line.is_empty() { jarvis_tabs_wire::first_line(text) } else { line.clone() };
                Some(EntryRec::Report { key: key(), from: from.clone(), line, text: text.clone() })
            }
            // One that stands for a question is its card, at the end of the tab (`TabRec.asks`).
            Body::Needs { q, .. } if !q.is_empty() => None,
            Body::Needs { text, closed: false, .. } => Some(EntryRec::Question { key: key(), text: text.clone(), closed: false }),
            Body::Needs { closed: true, .. } => {
                self.close_questions();
                None
            }
            Body::Work { id, state: WorkState::Started, .. } => {
                self.began.insert(id.clone(), e.at);
                None
            }
            Body::Work { id, state: WorkState::Ended, what, how, .. } => {
                self.began.remove(id);
                let word = match how {
                    Some(WorkEnd::Failed) => "failed",
                    Some(WorkEnd::Lost) => "was cut off",
                    Some(WorkEnd::Stopped) => "was stopped",
                    _ => "",
                };
                (!word.is_empty() && !what.is_empty()).then(|| EntryRec::Quiet { key: key(), text: format!("{what} {word}") })
            }
            _ => None,
        };
        *keys += 1;
        match entry {
            Some(entry) => {
                let at = self.items.partition_point(|(o, _)| *o < e.offset);
                self.items.insert(at, (e.offset, entry));
                self.moved(at);
            }
            None => self.tail(),
        }
    }

    fn close_questions(&mut self) {
        let mut moved = None;
        for (i, (_, entry)) in self.items.iter_mut().enumerate().rev() {
            if let EntryRec::Question { closed, .. } = entry {
                if *closed {
                    break;
                }
                *closed = true;
                moved = Some(i);
            }
        }
        if let Some(i) = moved {
            self.moved(i);
        }
    }

    fn entries(&self, from: usize) -> Vec<EntryRec> {
        let done = self.items[from.min(self.items.len())..].iter().map(|(_, e)| e.clone());
        let waiting = self.pending.iter().map(|p| EntryRec::Yours {
            key: p.key.clone(),
            text: p.text.clone(),
            voice: false,
            quotes: p.replies.iter().map(|r| QuoteRec { to: r.to, quote: r.quote.clone(), text: r.text.clone() }).collect(),
            files: p.files.iter().map(|f| f.name.clone()).collect(),
            state: if p.failed.is_some() { Landing::Refused } else { Landing::Sending },
            why: p.failed.clone().unwrap_or_default(),
        });
        let live = self.live.iter().map(|(_, key, text)| EntryRec::Answer { key: key.clone(), offset: None, text: text.clone(), files: vec![] });
        done.chain(waiting).chain(live).collect()
    }

    fn unload(&mut self) {
        (self.loaded, self.end, self.live) = (false, 0, None);
        self.items = vec![];
        self.applied = BTreeSet::new();
        self.began.clear();
        self.waiting = 0;
        self.from = None;
    }
}

/// Words that didn't go: as they'd be typed again, with what they answered.
fn as_typed(p: &Pending) -> String {
    let mut parts: Vec<String> = p.replies.iter().map(|r| format!("> {}\n{}", r.quote, r.text)).collect();
    if !p.text.is_empty() {
        parts.push(p.text.clone());
    }
    parts.join("\n\n")
}

/// Everything the screens know, folded from frames. Pure: it takes frames and calls, and leaves asks
/// in `out` and news for the screen in [`Desk::take`].
#[derive(Default)]
struct Desk {
    tabs: HashMap<String, Hold>,
    order: Vec<String>,
    closed: Vec<Info>,
    wall_end: u64,
    online: bool,
    /// A drop of a link that was up: until then the screens still hear it's up.
    quiet_until: Option<Instant>,
    /// No listing yet on this connection: every tab needs following.
    fresh: bool,
    wall_touched: bool,
    relist: bool,
    /// Records in memory, the most recently shown last.
    recent: VecDeque<String>,
    /// What each `Open` or `Reopen` on its way carries: the words and files to say in it, if any.
    /// Tabs on their way from the wall: his words, files, and whether Auto opened it.
    opening: VecDeque<Option<(String, Vec<Upload>, bool)>>,
    keys: u64,
    out: Vec<Ask>,
    told: Vec<Told>,
    cards: Vec<CardRec>,
    digest: Vec<DigestRec>,
    queue_touched: bool,
    /// Answers and switches on their way, oldest first, by tab.
    asked: VecDeque<(String, Asked)>,
    /// Questions he just answered, hidden until the server's list drops them: the tab ids and q.
    answered: Vec<(String, String)>,
}

impl Desk {
    /// The pipe is open, which says nothing yet: the gate opens it before it knows the server is
    /// there. Online comes with the first frame.
    fn connected(&mut self) {
        (self.fresh, self.wall_touched) = (true, true);
        self.out.extend([Ask::List, Ask::Closed, Ask::Follow { id: WALL.into(), after: self.wall_end }, Ask::Queue]);
    }

    /// What the screens are told: up, or down for less than [`DOWN_GRACE`].
    fn shown_online(&self) -> bool {
        self.online || self.quiet_until.is_some_and(|u| Instant::now() < u)
    }

    /// The link went down: if it was up, the screens hear nothing of it for a while.
    fn dropped(&mut self) {
        if self.online {
            self.quiet_until = Some(Instant::now() + DOWN_GRACE);
        }
        self.disconnected();
    }

    /// Nothing still on its way will land, as far as anyone here can tell: the words go back in their
    /// boxes, so nothing is lost and nothing goes twice.
    fn disconnected(&mut self) {
        self.online = false;
        self.wall_touched = true;
        self.out.clear();
        self.asked.clear();
        for (words, files, _) in std::mem::take(&mut self.opening).into_iter().flatten() {
            self.back(String::new(), words, files.into_iter().map(|u| u.file).collect());
        }
        let mut lost = vec![];
        for (id, t) in &mut self.tabs {
            let (mut back, mut files) = (vec![], vec![]);
            t.pending.retain_mut(|p| {
                if p.landed {
                    return true;
                }
                let words = as_typed(p);
                if !words.is_empty() {
                    back.push(words);
                }
                files.append(&mut p.files);
                false
            });
            // The live text stays: a new follow sends the block again from its start.
            lost.push((id.clone(), back.join("\n\n"), files));
            t.tail();
        }
        for (id, words, files) in lost {
            self.back(id, words, files);
        }
    }

    fn tell(&mut self, tell: Tell) {
        self.wall_touched |= !self.online;
        (self.online, self.quiet_until) = (true, None);
        match tell {
            Tell::Tabs { tabs } => self.listed(tabs),
            Tell::Closed { tabs } => {
                self.closed = tabs;
                self.wall_touched = true;
            }
            Tell::Opened { tab } => self.opened(tab),
            Tell::Landed { id, .. } if self.took(&id).is_some() => {}
            Tell::Landed { id, .. } => {
                if let Some(p) = self.tabs.get_mut(&id).and_then(|t| t.pending.iter_mut().find(|p| p.said())) {
                    p.landed = true;
                }
            }
            Tell::Put { id, name, file } => self.kept(id, &name, file),
            Tell::Event { id, event } => self.event(&id, &event),
            Tell::Text { id, block, from, text } => {
                if let Some(t) = self.tabs.get_mut(&id).filter(|t| t.loaded) {
                    let keys = &mut self.keys;
                    let same = t.live.as_ref().is_some_and(|(b, _, had)| *b == block && had.len() >= from);
                    if !same {
                        if from != 0 {
                            return;
                        }
                        *keys += 1;
                        t.live = Some((block, format!("live{keys}"), String::new()));
                    }
                    if let Some((_, _, had)) = &mut t.live {
                        had.truncate(from);
                        had.push_str(&text);
                    }
                    t.tail();
                }
            }
            Tell::BadOffset { id, .. } => {
                if let Some(t) = self.tabs.get_mut(&id) {
                    let shown = t.loaded;
                    t.unload();
                    t.loaded = shown;
                    t.moved(0);
                    self.out.push(Ask::Follow { id, after: 0 });
                }
            }
            Tell::Ended { id } => {
                if let Some(t) = self.tabs.get_mut(&id) {
                    t.closed = true;
                    t.touched = true;
                }
                self.relist = true;
            }
            Tell::Refused { id, why } => match self.took(&id) {
                Some(Asked::Answer) => {
                    // The card comes back with the next list, or now if the list is as it was.
                    self.answered.clear();
                    self.queue_touched = true;
                    self.told.push(Told::Refused(id, why));
                }
                Some(Asked::Autopilot) => self.told.push(Told::Refused(id, why)),
                None => self.refused(id, why),
            },
            Tell::Queue { decide, digest } => {
                self.cards = decide.iter().map(card_rec).collect();
                self.digest = digest.iter().map(digest_rec).collect();
                let open: Vec<(String, String)> = self.cards.iter().flat_map(|c| c.tabs.iter().map(|t| (t.id.clone(), t.q.clone()))).collect();
                self.answered.retain(|a| open.contains(a));
                self.queue_touched = true;
            }
            Tell::File { id, name, data } => self.told.push(Told::File(id, name, STANDARD.decode(data).unwrap_or_default())),
            _ => {}
        }
    }

    /// The oldest answer or switch on its way for tab `id`, now answered.
    fn took(&mut self, id: &str) -> Option<Asked> {
        let at = self.asked.iter().position(|(i, _)| i == id)?;
        self.asked.remove(at).map(|(_, a)| a)
    }

    /// His answer to the card that holds question `q` of tab `id`: it leaves the list at once.
    fn answer(&mut self, id: String, q: String, picks: Vec<u32>, text: String) {
        if let Some(c) = self.cards.iter().find(|c| c.tabs.iter().any(|t| t.id == id && t.q == q)) {
            self.answered.extend(c.tabs.iter().map(|t| (t.id.clone(), t.q.clone())));
        } else {
            self.answered.push((id.clone(), q.clone()));
        }
        self.queue_touched = true;
        if let Some(t) = self.tabs.get_mut(&id) {
            t.touched = true;
        }
        self.asked.push_back((id.clone(), Asked::Answer));
        self.out.push(Ask::Answer { id, q, picks, text });
    }

    fn autopilot(&mut self, id: String, on: bool) {
        self.asked.push_back((id.clone(), Asked::Autopilot));
        self.out.push(Ask::Autopilot { id, op: if on { "on" } else { "off" }.into(), text: String::new(), n: None });
    }

    fn shown(&self, tabs: &[CardTabRec]) -> bool {
        !tabs.iter().any(|t| self.answered.iter().any(|(i, q)| *i == t.id && *q == t.q))
    }

    /// What's left after a batch of frames: one listing, however many of the wall's events came.
    fn settle(&mut self) {
        if std::mem::take(&mut self.relist) {
            self.out.extend([Ask::List, Ask::Closed]);
        }
    }

    fn event(&mut self, id: &str, e: &Event) {
        if id == WALL {
            self.wall_end = self.wall_end.max(e.offset);
            self.relist = true;
            return;
        }
        let Some(t) = self.tabs.get_mut(id) else { return };
        if e.offset > t.tile.end {
            t.tile.apply(e);
            self.wall_touched = true;
            t.touched = true;
        }
        if t.loaded {
            t.apply(e, &mut self.keys);
        }
    }

    fn listed(&mut self, tabs: Vec<Info>) {
        let was = std::mem::take(&mut self.order);
        for info in tabs {
            self.order.push(info.id.clone());
            let new = !self.tabs.contains_key(&info.id);
            let t = self.tabs.entry(info.id.clone()).or_default();
            (t.n, t.closed) = (info.n, false);
            if new || info.tile.end >= t.tile.end {
                t.tile = info.tile;
            }
            t.touched = true;
            if new || self.fresh {
                let after = if t.loaded { t.end } else { t.tile.end };
                self.out.push(Ask::Follow { id: info.id, after });
            }
        }
        for id in was {
            if !self.order.contains(&id) {
                if let Some(t) = self.tabs.get_mut(&id) {
                    t.closed = true;
                    t.touched = true;
                }
            }
        }
        self.fresh = false;
        self.wall_touched = true;
    }

    fn opened(&mut self, info: Info) {
        let id = info.id.clone();
        let carried = self.opening.pop_front().flatten();
        self.relist = true;
        let t = self.tabs.entry(id.clone()).or_default();
        (t.n, t.closed, t.tile) = (info.n, false, info.tile);
        if !self.order.contains(&id) {
            self.order.push(id.clone());
        }
        self.show(vec![id.clone()]);
        if let Some((words, files, auto)) = carried {
            // Autopilot first: on, it waits for his first words and drafts from them, no chat turn.
            if auto {
                self.autopilot(id.clone(), true);
            }
            if !words.trim().is_empty() || !files.is_empty() {
                self.say(id.clone(), words, vec![], files);
            }
            self.told.push(Told::Opened(id));
        }
    }

    /// A file is on the server: once they all are, the words go with them.
    fn kept(&mut self, id: String, name: &str, file: FileRef) {
        let Some(t) = self.tabs.get_mut(&id) else { return };
        let Some(p) = t.pending.iter_mut().find(|p| p.going.iter().any(|g| g == name)) else { return };
        if let Some(i) = p.going.iter().position(|g| g == name) {
            p.going.remove(i);
        }
        p.kept.push(file);
        if p.going.is_empty() && p.failed.is_none() {
            let ask = Ask::Say { id, text: p.text.clone(), via: Some(via(p.quiet)), replies: p.replies.clone(), files: p.kept.clone(), quiet: p.quiet };
            self.out.push(ask);
        }
    }

    /// Words and files that won't go: back to him, whatever is left of them.
    fn back(&mut self, id: String, words: String, files: Vec<OutFile>) {
        if !words.is_empty() {
            self.told.push(Told::Back(id, words));
        }
        if !files.is_empty() {
            self.told.push(Told::Unsent(files));
        }
    }

    /// The first send still handing its files over failed: its words and files go back.
    fn uploads_failed(&mut self, id: &str, why: &str) -> bool {
        let Some(t) = self.tabs.get_mut(id) else { return false };
        let Some(p) = t.pending.iter_mut().find(|p| !p.going.is_empty() && p.failed.is_none()) else { return false };
        p.failed = Some(why.to_string());
        p.going.clear();
        let (words, files) = (as_typed(p), std::mem::take(&mut p.files));
        t.tail();
        self.back(id.to_string(), words, files);
        true
    }

    fn refused(&mut self, id: String, why: String) {
        // An older server than this app: what it doesn't know, the app does without. Except a file,
        // which it can't be seen to swallow: it's the one ask with no id it could answer on.
        if why == "I don't know that ask" {
            let up = self.tabs.iter().find(|(_, t)| t.pending.iter().any(|p| !p.going.is_empty())).map(|(id, _)| id.clone());
            if let Some(id) = up {
                self.uploads_failed(&id, "The server can't take files yet");
            }
            return;
        }
        if id.is_empty() {
            if let Some((words, files, _)) = self.opening.pop_front().flatten() {
                self.back(String::new(), words, files.into_iter().map(|u| u.file).collect());
            }
            return;
        }
        if self.uploads_failed(&id, &why) {
            return;
        }
        if let Some(at) = self.opening.iter().position(Option::is_none) {
            self.opening.remove(at);
        }
        let mut back = None;
        if let Some(t) = self.tabs.get_mut(&id) {
            if let Some(p) = t.pending.iter_mut().find(|p| p.said()) {
                p.failed = Some(why);
                back = Some((as_typed(p), std::mem::take(&mut p.files)));
                t.tail();
            }
        }
        if let Some((words, files)) = back {
            self.back(id, words, files);
        }
    }

    /// These tabs are on screen or a swipe away: their records come, and older ones go when too many.
    fn show(&mut self, ids: Vec<String>) {
        for id in ids {
            self.recent.retain(|r| *r != id);
            self.recent.push_back(id.clone());
            if let Some(info) = self.closed.iter().find(|c| c.id == id) {
                // A closed tab is read like any other, and its follow ends with its record.
                let t = self.tabs.entry(id.clone()).or_default();
                (t.n, t.tile, t.closed) = (info.n, info.tile.clone(), true);
            }
            let Some(t) = self.tabs.get_mut(&id) else { continue };
            if !t.loaded {
                t.unload();
                t.loaded = true;
                t.moved(0);
                self.out.push(Ask::Follow { id, after: 0 });
            } else {
                t.moved(0);
            }
        }
        while self.recent.len() > KEEP {
            let Some(old) = self.recent.pop_front() else { break };
            if let Some(t) = self.tabs.get_mut(&old) {
                t.unload();
            }
        }
    }

    fn say(&mut self, id: String, text: String, quotes: Vec<QuoteRec>, files: Vec<Upload>) {
        self.say_as(id, text, quotes, files, false);
    }

    /// Words he holds, as the laptop's enter: they wait on the server for his send-all, or ride along on a
    /// turn that runs anyway, and never start one themselves.
    fn hold(&mut self, id: String, text: String, quotes: Vec<QuoteRec>) {
        self.say_as(id, text, quotes, vec![], true);
    }

    fn say_as(&mut self, id: String, text: String, quotes: Vec<QuoteRec>, files: Vec<Upload>, quiet: bool) {
        let Some(t) = self.tabs.get_mut(&id) else {
            let replies = quotes.into_iter().map(|q| Reply { to: q.to, quote: q.quote, text: q.text }).collect();
            let words = as_typed(&Pending { text, replies, ..Pending::default() });
            self.back(String::new(), words, files.into_iter().map(|u| u.file).collect());
            return;
        };
        // What failed is back in the box, and this may well be it.
        t.pending.retain(|p| p.failed.is_none());
        let replies: Vec<Reply> = quotes.into_iter().map(|q| Reply { to: q.to, quote: q.quote, text: q.text }).collect();
        self.keys += 1;
        let (up, going) = (files.is_empty(), files.iter().map(|u| u.file.name.clone()).collect());
        let pending = Pending {
            key: format!("mine{}", self.keys),
            text: text.clone(),
            replies: replies.clone(),
            files: files.iter().map(|u| u.file.clone()).collect(),
            going,
            quiet,
            ..Pending::default()
        };
        t.pending.push(pending);
        t.tail();
        if up {
            self.out.push(Ask::Say { id, text, via: Some(via(quiet)), replies, files: vec![], quiet });
        } else {
            for u in files {
                self.out.push(Ask::Put { id: id.clone(), name: u.file.name, data: u.data });
            }
        }
    }

    fn start(&mut self, text: String, files: Vec<Upload>, auto: bool) {
        let from = if text.trim().is_empty() { files.first().map_or("", |u| u.file.name.as_str()) } else { &text };
        let name = from.split_whitespace().take(3).collect::<Vec<_>>().join(" ").chars().take(24).collect();
        self.opening.push_back(Some((text, files, auto)));
        self.out.push(Ask::Open { want: None, name });
    }

    fn reopen(&mut self, id: String) {
        self.opening.push_back(None);
        self.out.push(Ask::Reopen { id });
    }

    fn seen(&mut self, id: String) {
        let Some(t) = self.tabs.get_mut(&id).filter(|t| t.loaded && t.tile.blue()) else { return };
        let upto = t.end;
        if upto > t.seen_sent && t.tile.seen_moves(upto) {
            t.seen_sent = upto;
            self.out.push(Ask::Seen { id, upto });
        }
    }

    fn tile(&self, id: &str, t: &Hold) -> TileRec {
        TileRec {
            id: id.to_string(),
            n: t.n,
            name: t.tile.name.clone(),
            needs: t.tile.amber,
            unread: t.tile.blue(),
            working: t.tile.working(),
            mode: t.tile.mode.clone(),
            model: jarvis_tabs_wire::mode::parts(&t.tile.mode).0.to_string(),
            effort: jarvis_tabs_wire::mode::parts(&t.tile.mode).1.to_string(),
            autopilot: autopilot_on(&t.tile),
        }
    }

    /// What the screen hasn't heard yet.
    fn take(&mut self) -> Vec<Told> {
        let mut told = std::mem::take(&mut self.told);
        if std::mem::take(&mut self.wall_touched) {
            let tiles = self.order.iter().filter_map(|id| self.tabs.get(id).map(|t| self.tile(id, t))).collect();
            let closed = self.closed.iter().map(|c| ClosedRec { id: c.id.clone(), name: c.tile.name.clone(), line: c.tile.line.clone() }).collect();
            told.push(Told::Wall(tiles, closed, self.shown_online()));
        }
        if std::mem::take(&mut self.queue_touched) {
            let cards = self.cards.iter().filter(|c| self.shown(&c.tabs)).cloned().collect();
            told.push(Told::Queue(cards, self.digest.clone()));
        }
        let ids: Vec<String> = self.tabs.iter().filter(|(_, t)| t.touched && t.loaded).map(|(id, _)| id.clone()).collect();
        for id in ids {
            let t = &self.tabs[&id];
            let from = t.from.unwrap_or(t.items.len()).min(t.items.len());
            let open = t.tile.open.iter().max_by_key(|(id, _)| t.began.get(*id).copied().unwrap_or(0));
            told.push(Told::Tab(TabRec {
                tile: self.tile(&id, t),
                from: from as u32,
                entries: t.entries(from),
                working: open.is_some(),
                what: open.map_or(String::new(), |(_, what)| what.clone()),
                since: open.and_then(|(id, _)| t.began.get(id).copied()).unwrap_or(0),
                jobs: t.tile.jobs_line().unwrap_or_default(),
                issues: t.tile.issues.iter().map(issue_rec).collect(),
                waiting: t.waiting > 0,
                closed: t.closed,
                loading: t.end < t.tile.end,
                asks: t.tile.asks.iter().map(|a| open_ask(&id, &t.tile.name, a)).filter(|c| self.shown(&c.tabs)).collect(),
            }));
            let t = self.tabs.get_mut(&id).expect("just read");
            (t.from, t.touched) = (None, false);
        }
        told
    }
}

/// The desk, and what's waiting on it.
#[derive(Default)]
struct Shared {
    desk: Mutex<Desk>,
    /// Something's in `out`, or the screen should hear: the link looks.
    nudge: Notify,
    attached: Mutex<Option<Attached>>,
}

impl Shared {
    /// A panic elsewhere must not take every later call with it: this runs in the process of a call.
    fn desk(&self) -> std::sync::MutexGuard<'_, Desk> {
        self.desk.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

struct Attached {
    listener: Arc<dyn TabsListener>,
    task: tokio::task::JoinHandle<()>,
    /// The screens went: the link goes when this ends, unless they're back first.
    leaving: Option<tokio::task::JoinHandle<()>>,
}

static SHARED: LazyLock<Shared> = LazyLock::new(Shared::default);

/// The screen hears what's new, without the desk's lock held.
fn deliver() {
    let told = SHARED.desk().take();
    let Some(listener) = SHARED.attached.lock().unwrap().as_ref().map(|a| a.listener.clone()) else { return };
    for t in told {
        match t {
            Told::Wall(tiles, closed, online) => listener.wall(tiles, closed, online),
            Told::Tab(tab) => listener.tab(tab),
            Told::Opened(id) => listener.opened(id),
            Told::Back(id, text) => listener.back(id, text),
            Told::File(id, name, data) => listener.file(id, name, data),
            Told::Unsent(files) => listener.unsent(files),
            Told::Queue(cards, digest) => listener.queue(cards, digest),
            Told::Refused(id, why) => listener.refused(id, why),
        }
    }
}

/// One connection, until it drops: frames in, asks out, the screen told at most once a frame.
async fn serve(stream: forum::Stream) -> Result<()> {
    let forum::Stream { mut send, recv, .. } = stream;
    let (tx, mut rx) = mpsc::channel::<Tell>(256);
    let reader = RT.spawn(async move {
        let mut recv = tokio::io::BufReader::with_capacity(64 * 1024, recv);
        loop {
            match read_async::<Tell>(&mut recv).await {
                Ok(t) => {
                    if tx.send(t).await.is_err() {
                        return "the screen went".to_string();
                    }
                }
                Err(jarvis_tabs_wire::FrameError::Bad(_)) => {}
                Err(e) => return e.to_string(),
            }
        }
    });
    let aborted = reader.abort_handle();
    let _reader = scopeguard(move || aborted.abort());
    SHARED.desk().connected();
    let mut tick = tokio::time::interval(FRAME);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    // The screens hear nothing until a frame or the grace, as on the dial.
    let grace = tokio::time::sleep(GRACE);
    tokio::pin!(grace);
    let (mut dirty, mut heard, mut waited) = (false, false, false);
    loop {
        let out = std::mem::take(&mut SHARED.desk().out);
        for ask in out {
            write_async(&mut send, &ask).await.context("write")?;
        }
        tokio::select! {
            t = rx.recv() => {
                let Some(t) = t else { break };
                heard = true;
                let mut desk = SHARED.desk();
                desk.tell(t);
                while let Ok(t) = rx.try_recv() {
                    desk.tell(t);
                }
                desk.settle();
                dirty = true;
            }
            _ = SHARED.nudge.notified() => dirty = true,
            () = &mut grace, if !waited => (waited, dirty) = (true, true),
            _ = tick.tick(), if dirty && (heard || waited) => {
                deliver();
                dirty = false;
            }
        }
    }
    anyhow::bail!("the link dropped")
}

/// Runs `f` when dropped.
fn scopeguard(f: impl FnOnce()) -> impl Drop {
    struct Guard<F: FnOnce()>(Option<F>);
    impl<F: FnOnce()> Drop for Guard<F> {
        fn drop(&mut self) {
            if let Some(f) = self.0.take() {
                f();
            }
        }
    }
    Guard(Some(f))
}

async fn keep_linked() {
    let (mut wait, mut failed, mut down) = (RETRY.0, 0, Instant::now());
    loop {
        let dial = forum::open_stream(TABS_HANDSHAKE, failed >= STALE);
        tokio::pin!(dial);
        let linked = tokio::select! {
            r = &mut dial => r,
            () = tokio::time::sleep(GRACE) => {
                deliver();
                dial.await
            }
        };
        let why = match linked {
            Ok(stream) => {
                failed = 0;
                let end = serve(stream).await;
                let mut desk = SHARED.desk();
                // A gate with no server behind it opens the pipe and shuts it at once. Only a frame
                // proves the link, or the dial would hammer the gate five times a second.
                if desk.online {
                    (wait, down) = (RETRY.0, Instant::now());
                    // Once the grace is out, the screens hear it's down, unless it's back by then.
                    RT.spawn(async {
                        tokio::time::sleep(DOWN_GRACE).await;
                        SHARED.desk().wall_touched = true;
                        deliver();
                    });
                }
                desk.dropped();
                drop(desk);
                end.err().map(|e| format!("{e:#}"))
            }
            Err(e) => {
                failed += 1;
                Some(format!("{e:#}"))
            }
        };
        tracing::info!("tabs: link down: {why:?}");
        tokio::time::sleep(wait.min(GRACE)).await;
        if wait >= GRACE {
            deliver();
        }
        tokio::time::sleep(wait.saturating_sub(GRACE)).await;
        wait = (wait * 2).min(if down.elapsed() < QUICK_FOR { QUICK } else { RETRY.1 });
    }
}

/// The screens are up and the link comes up. They hear nothing until it's there or the grace has
/// passed, so a quick link never shows them "offline" on the way. Back within [`AWAY`], the link
/// they left is still up and they carry on on it.
#[uniffi::export]
pub fn tabs_attach(listener: Arc<dyn TabsListener>) {
    let mut attached = SHARED.attached.lock().unwrap();
    if let Some(a) = attached.as_mut().filter(|a| !a.task.is_finished()) {
        if let Some(l) = a.leaving.take() {
            l.abort();
        }
        a.listener = listener;
        drop(attached);
        let mut desk = SHARED.desk();
        desk.wall_touched = true;
        desk.queue_touched = true;
        for t in desk.tabs.values_mut() {
            if t.loaded {
                t.moved(0);
            }
        }
        drop(desk);
        SHARED.nudge.notify_one();
        return;
    }
    let old = attached.take();
    *attached = Some(Attached { listener, task: RT.spawn(keep_linked()), leaving: None });
    drop(attached);
    let mut desk = SHARED.desk();
    // The screens came back without the link going down: what it carried goes back as on a drop.
    if let Some(old) = old {
        old.task.abort();
        desk.dropped();
    }
    desk.wall_touched = true;
    for t in desk.tabs.values_mut() {
        if t.loaded {
            t.moved(0);
        }
    }
}

/// The screens are gone: no link and no battery once they've been gone [`AWAY`].
#[uniffi::export]
pub fn tabs_detach() {
    let mut attached = SHARED.attached.lock().unwrap();
    let Some(a) = attached.as_mut() else { return };
    if a.leaving.is_some() {
        return;
    }
    a.leaving = Some(RT.spawn(async {
        tokio::time::sleep(AWAY).await;
        let gone = SHARED.attached.lock().unwrap().take();
        if let Some(old) = gone {
            old.task.abort();
            SHARED.desk().dropped();
        }
    }));
}

fn with_desk(f: impl FnOnce(&mut Desk)) {
    f(&mut SHARED.desk());
    SHARED.nudge.notify_one();
}

#[uniffi::export]
pub fn tabs_show(ids: Vec<String>) {
    with_desk(|d| d.show(ids));
}

/// Reads what he attached, off the desk's lock: a file that's gone can't be sent, and his words still can.
fn uploads(files: Vec<OutFile>) -> Vec<Upload> {
    files
        .into_iter()
        .filter_map(|file| match std::fs::read(&file.path) {
            Ok(bytes) => Some(Upload { data: STANDARD.encode(bytes), file }),
            Err(e) => {
                tracing::warn!("can't read {}: {e}", file.name);
                None
            }
        })
        .collect()
}

#[uniffi::export]
pub fn tabs_start(text: String, files: Vec<OutFile>) {
    let files = uploads(files);
    with_desk(|d| d.start(text, files, false));
}

/// Auto at the wall: a new tab, with whatever is in the box, and its autopilot on once the server has it.
/// Nothing in the box is fine: autopilot waits for his first words.
#[uniffi::export]
pub fn tabs_start_auto(text: String, files: Vec<OutFile>) {
    let files = uploads(files);
    with_desk(|d| d.start(text, files, true));
}

#[uniffi::export]
pub fn tabs_say(id: String, text: String, quotes: Vec<QuoteRec>, files: Vec<OutFile>) {
    let files = uploads(files);
    with_desk(|d| d.say(id, text, quotes, files));
}

/// Words or replies he holds: they wait for his send-all, or ride along on a turn that runs anyway.
#[uniffi::export]
pub fn tabs_hold(id: String, text: String, quotes: Vec<QuoteRec>) {
    with_desk(|d| d.hold(id, text, quotes));
}

/// Held words go as `quiet` too: the server then holds them in every state, an awake idle tab
/// included, where `quiet` alone would start a turn (t104).
fn via(quiet: bool) -> Via {
    if quiet { Via::Quiet } else { Via::Typed }
}

/// Send-now: what waits in the tab goes in at once, as the laptop's ctrl+enter.
#[uniffi::export]
pub fn tabs_release(id: String) {
    with_desk(|d| d.out.push(Ask::Release { id }));
}

#[uniffi::export]
pub fn tabs_seen(id: String) {
    with_desk(|d| d.seen(id));
}

#[uniffi::export]
pub fn tabs_close(id: String) {
    with_desk(|d| d.out.push(Ask::Close { id }));
}

#[uniffi::export]
pub fn tabs_reopen(id: String) {
    with_desk(|d| d.reopen(id));
}

/// Asks for a file beside the record, by the name in its entry. `TabsListener::file` answers.
#[uniffi::export]
pub fn tabs_file(id: String, name: String) {
    with_desk(|d| d.out.push(Ask::File { id, name }));
}

/// Stops the turn being written; what's written stays.
#[uniffi::export]
pub fn tabs_stop(id: String) {
    with_desk(|d| d.out.push(Ask::Stop { id }));
}

/// Sets a tab's model and effort for its next message. A pair that has no mode is ignored.
#[uniffi::export]
pub fn tabs_mode(id: String, model: String, effort: String) {
    if let Some(mode) = jarvis_tabs_wire::mode::pick(&model, &effort) {
        with_desk(|d| {
            if let Some(t) = d.tabs.get_mut(&id) {
                t.tile.mode = mode.to_string();
                d.wall_touched = true;
                t.touched = true;
            }
            d.out.push(Ask::Mode { id, mode: mode.to_string() });
        });
    }
}

/// His answer to question `q` of tab `id`: the picks, a note, or both. On a card from several tabs it
/// answers in each. A refusal comes back through `TabsListener::refused`.
#[uniffi::export]
pub fn tabs_answer(id: String, q: String, picks: Vec<u32>, text: String) {
    with_desk(|d| d.answer(id, q, picks, text));
}

/// His autopilot switch for tab `id`. The server may refuse it (until autopilot is built, it does).
#[uniffi::export]
pub fn tabs_autopilot(id: String, on: bool) {
    with_desk(|d| d.autopilot(id, on));
}

/// An answer from a notification, with the app's screens down: its own short link, the answer, and
/// what the server said. `None` once it landed, else why not.
#[uniffi::export]
pub fn tabs_answer_now(id: String, q: String, picks: Vec<u32>, text: String) -> Option<String> {
    let run = async move {
        let forum::Stream { mut send, recv, .. } = forum::open_stream(TABS_HANDSHAKE, false).await?;
        write_async(&mut send, &Ask::Answer { id: id.clone(), q, picks, text }).await.context("write")?;
        let mut recv = tokio::io::BufReader::new(recv);
        loop {
            match read_async::<Tell>(&mut recv).await {
                Ok(Tell::Landed { id: got, .. }) if got == id => return Ok(None),
                Ok(Tell::Refused { why, .. }) => return Ok(Some(why)),
                Ok(_) | Err(jarvis_tabs_wire::FrameError::Bad(_)) => {}
                Err(e) => anyhow::bail!("{e}"),
            }
        }
    };
    let r: Result<Option<String>> = RT.block_on(async { tokio::time::timeout(Duration::from_secs(30), run).await.unwrap_or_else(|_| anyhow::bail!("no answer in 30 s")) });
    r.unwrap_or_else(|e| Some(format!("{e:#}")))
}

#[uniffi::export]
pub fn tabs_move(id: String, to: u32) {
    with_desk(|d| d.out.push(Ask::Move { id, to: to as usize }));
}

/// On unless it's finished, dropped or switched off, as the server reads it: paused with why "off" is his
/// switch, so Auto shows off and a tap sends "on"; paused for any other reason (parked, esc, closed) is
/// still on. Aviv, t67, 2026-11-03: the phone showed a switched-off autopilot as on, and each tap sent an
/// "off" the server ignored.
fn autopilot_on(t: &Tile) -> bool {
    t.autopilot.as_ref().is_some_and(|a| !matches!(a.state, ApState::Done | ApState::Accepted | ApState::Dropped) && !(a.state == ApState::Paused && a.why == "off"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn said(offset: u64, who: Who, text: &str) -> Event {
        Event { offset, at: 1000 + offset, body: Body::Said { who, text: text.into(), via: None, replies: vec![], files: vec![] } }
    }

    fn info(id: &str, n: u32, end: u64) -> Info {
        let mut tile = Tile::default();
        (tile.name, tile.end) = (id.into(), end);
        Info { n, id: id.into(), mirrored: false, tile }
    }

    #[test]
    fn a_pipe_that_says_nothing_is_not_online() {
        let mut d = Desk::default();
        d.connected();
        assert!(!d.online);
        d.tell(Tell::Tabs { tabs: vec![] });
        assert!(d.online);
        d.disconnected();
        assert!(!d.online);
    }

    fn up(tabs: &[(&str, u64)]) -> Desk {
        let mut d = Desk::default();
        d.connected();
        d.tell(Tell::Tabs { tabs: tabs.iter().enumerate().map(|(i, (id, end))| info(id, i as u32 + 1, *end)).collect() });
        d
    }

    /// A tab's number is its place in the row, on the server, and whatever says the row changed, a new
    /// event an installed app doesn't know included, order and numbers come in the list it asks for.
    #[test]
    fn the_row_and_its_numbers_are_the_servers_whatever_the_event() {
        let mut d = up(&[("t1", 10), ("t2", 10), ("t3", 10)]);
        for (i, kind) in [r#""kind":"closed","n":1,"id":"t1""#, r#""kind":"row","ids":["t3","t2"]"#].iter().enumerate() {
            let e: Event = serde_json::from_str(&format!(r#"{{"offset":{},"at":1,{kind}}}"#, i + 1)).unwrap();
            d.out.clear();
            event(&mut d, WALL, e);
            d.settle();
            assert_eq!(d.out, [Ask::List, Ask::Closed], "{kind}");
        }
        d.tell(Tell::Tabs { tabs: vec![info("t3", 1, 10), info("t2", 2, 10)] });
        let told = d.take();
        let tiles = told.iter().find_map(|t| if let Told::Wall(tiles, ..) = t { Some(tiles) } else { None }).expect("the wall");
        let row: Vec<(&str, u32)> = tiles.iter().map(|t| (t.id.as_str(), t.n)).collect();
        assert_eq!(row, [("t3", 1), ("t2", 2)]);
    }

    fn event(d: &mut Desk, id: &str, e: Event) {
        d.tell(Tell::Event { id: id.into(), event: e });
    }

    fn tab(told: &[Told], id: &str) -> TabRec {
        told.iter().find_map(|t| if let Told::Tab(r) = t { (r.tile.id == id).then(|| r.clone()) } else { None }).expect("that tab")
    }

    fn texts(r: &TabRec) -> Vec<String> {
        r.entries
            .iter()
            .map(|e| match e {
                EntryRec::Yours { text, .. } | EntryRec::Answer { text, .. } | EntryRec::Question { text, .. } | EntryRec::Quiet { text, .. } => text.clone(),
                EntryRec::Report { line, .. } => line.clone(),
            })
            .collect()
    }

    fn yes_no(q: &str, tabs: &[&str]) -> Card {
        Card {
            tabs: tabs.iter().map(|t| jarvis_tabs_wire::CardTab { id: (*t).into(), name: format!("{t} name"), q: q.into(), offset: 30 }).collect(),
            kind: jarvis_tabs_wire::AskKind::Ux,
            level: Urgency::Urgent,
            items: vec![Item { text: "Use the phone's mic?".into(), options: vec!["Yes".into(), "No".into()], pick: 0, ..Item::default() }],
            due: None,
            blocks: true,
            suggest_urgent: false,
            at: 7,
        }
    }

    fn queued(told: &[Told]) -> (Vec<CardRec>, Vec<DigestRec>) {
        told.iter().find_map(|t| if let Told::Queue(c, d) = t { Some((c.clone(), d.clone())) } else { None }).expect("the queue")
    }

    #[test]
    fn tabs_queue_is_asked_on_every_connection_and_crosses_whole() {
        let mut d = up(&[("t1", 0)]);
        assert!(d.out.contains(&Ask::Queue));
        let line = DigestLine { id: "t1".into(), name: "Piano".into(), at: 3, offset: 40, what: "done".into(), line: "Dark mode landed".into() };
        d.tell(Tell::Queue { decide: vec![yes_no("q1", &["t1", "t2"])], digest: vec![line] });
        let (cards, digest) = queued(&d.take());
        assert_eq!((cards.len(), cards[0].tabs.len(), cards[0].level, cards[0].options.clone(), cards[0].pick), (1, 2, Level::Urgent, vec!["Yes".to_string(), "No".to_string()], 0));
        assert_eq!((digest[0].what.as_str(), digest[0].line.as_str()), ("done", "Dark mode landed"));
        assert!(!d.take().iter().any(|t| matches!(t, Told::Queue(..))), "only when it changes");
    }

    #[test]
    fn tabs_an_answer_leaves_the_list_at_once_and_comes_back_if_refused() {
        let mut d = up(&[("t1", 0)]);
        d.tell(Tell::Queue { decide: vec![yes_no("q1", &["t1", "t2"])], digest: vec![] });
        d.take();
        d.out.clear();
        d.answer("t2".into(), "q1".into(), vec![0], "only on calls".into());
        assert_eq!(d.out, [Ask::Answer { id: "t2".into(), q: "q1".into(), picks: vec![0], text: "only on calls".into() }]);
        assert!(queued(&d.take()).0.is_empty(), "gone from every tab it stood in");
        // A list from before the answer doesn't bring it back.
        d.tell(Tell::Queue { decide: vec![yes_no("q1", &["t1", "t2"])], digest: vec![] });
        assert!(queued(&d.take()).0.is_empty());
        d.tell(Tell::Refused { id: "t2".into(), why: "q1 isn't open in t2 any more".into() });
        let told = d.take();
        assert!(told.contains(&Told::Refused("t2".into(), "q1 isn't open in t2 any more".into())));
        assert_eq!(queued(&told).0.len(), 1, "back on the list");
    }

    #[test]
    fn tabs_a_card_of_several_questions_crosses_with_all_of_them() {
        let mut d = up(&[("t1", 0)]);
        let mut c = yes_no("q1", &["t1"]);
        c.items.push(Item { text: "Which blue?".into(), options: vec!["Navy".into(), "Sky".into()], pick: 1, why: "sky fits".into(), ..Item::default() });
        c.items.push(Item { text: "Level".into(), options: vec!["Low".into(), "Default".into()], pick: 1, ..Item::default() });
        d.tell(Tell::Queue { decide: vec![c], digest: vec![] });
        let cards = queued(&d.take()).0;
        let items: Vec<_> = cards[0].items.iter().map(|i| (i.text.as_str(), i.pick)).collect();
        assert_eq!(items, [("Use the phone's mic?", 0), ("Which blue?", 1), ("Level", 1)]);
        assert_eq!((cards[0].text.as_str(), cards[0].items[1].why.as_str()), ("Use the phone's mic?", "sky fits"), "the flat fields stay the first");
        // He tapped only the second: the first goes as left open, and the card leaves the list.
        d.out.clear();
        d.answer("t1".into(), "q1".into(), vec![jarvis_tabs_wire::OPEN, 0], String::new());
        assert_eq!(d.out, [Ask::Answer { id: "t1".into(), q: "q1".into(), picks: vec![jarvis_tabs_wire::OPEN, 0], text: String::new() }]);
        assert!(queued(&d.take()).0.is_empty());
    }

    #[test]
    fn tabs_an_answer_landing_is_not_words_landing() {
        let mut d = up(&[("t1", 0)]);
        d.say("t1".into(), "hello".into(), vec![], vec![]);
        d.answer("t1".into(), "q1".into(), vec![1], String::new());
        d.tell(Tell::Landed { id: "t1".into(), offset: 50 });
        assert!(d.tabs["t1"].pending.iter().all(|p| !p.landed), "the answer's landing is its own");
        d.tell(Tell::Landed { id: "t1".into(), offset: 51 });
        assert!(d.tabs["t1"].pending[0].landed);
    }

    #[test]
    fn tabs_auto_reads_a_switched_off_autopilot_as_off_and_a_parked_one_as_on() {
        let tile = |state, why: &str| {
            let mut t = Tile::default();
            t.autopilot = Some(jarvis_tabs_wire::ApTile { id: "a1".into(), state, passed: 0, of: 0, spent: 0, why: why.into() });
            t
        };
        assert!(!autopilot_on(&tile(ApState::Paused, "off")), "his switch: a tap must send on");
        assert!(autopilot_on(&tile(ApState::Paused, "parked")));
        assert!(autopilot_on(&tile(ApState::Running, "")));
        assert!(!autopilot_on(&tile(ApState::Done, "")));
        assert!(!autopilot_on(&Tile::default()));
    }

    #[test]
    fn tabs_the_autopilot_switch_says_the_servers_refusal_and_keeps_his_words() {
        let mut d = up(&[("t1", 0)]);
        d.say("t1".into(), "hello".into(), vec![], vec![]);
        d.out.clear();
        d.autopilot("t1".into(), true);
        assert_eq!(d.out, [Ask::Autopilot { id: "t1".into(), op: "on".into(), text: String::new(), n: None }]);
        d.tell(Tell::Refused { id: "t1".into(), why: "autopilot isn't built yet".into() });
        let told = d.take();
        assert!(told.contains(&Told::Refused("t1".into(), "autopilot isn't built yet".into())));
        assert!(!told.iter().any(|t| matches!(t, Told::Back(..))), "his words aren't sent back");
        assert!(d.tabs["t1"].pending[0].failed.is_none());
    }

    #[test]
    fn tabs_a_tabs_issues_cross_with_it_and_none_is_todays_jobs_line() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        let jobs = |issues| Body::Jobs { jobs: vec!["Dark mode".into()], queued: vec![], line: "1 job running: Dark mode".into(), issues };
        event(&mut d, "t1", Event { offset: 10, at: 5, body: jobs(vec![]) });
        let r = tab(&d.take(), "t1");
        assert_eq!((r.jobs.as_str(), r.issues.len()), ("1 job running: Dark mode", 0), "without 2.0, today's line alone");
        let i = jarvis_tabs_wire::IssueBar { id: 7, title: "Dark mode".into(), board: "Piano".into(), step: 2, try_n: 2, label: "Testing · try 2".into(), held: true, ..Default::default() };
        event(&mut d, "t1", Event { offset: 11, at: 6, body: jobs(vec![i]) });
        let r = tab(&d.take(), "t1");
        assert_eq!((r.issues[0].id, r.issues[0].step, r.issues[0].try_n, r.issues[0].held, r.issues[0].label.as_str()), (7, 2, 2, true, "Testing · try 2"));
    }

    #[test]
    fn tabs_a_tabs_open_questions_cross_with_it() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        let item = Item { text: "Blue or green?".into(), options: vec!["Blue".into(), "Green".into()], pick: 1, ..Item::default() };
        let body = Body::Question { q: "q9".into(), ap: None, ask: jarvis_tabs_wire::AskKind::Tie, level: Urgency::Low, suggest_urgent: false, items: vec![item], due: Some(99), blocks: false };
        event(&mut d, "t1", Event { offset: 10, at: 5, body });
        event(&mut d, "t1", Event { offset: 11, at: 5, body: Body::Needs { text: "Blue or green?".into(), closed: false, q: "q9".into() } });
        let r = tab(&d.take(), "t1");
        assert!(r.entries.is_empty(), "its needs line stands for the card, which already shows");
        assert_eq!(r.asks.len(), 1);
        assert_eq!((r.asks[0].tabs[0].q.as_str(), r.asks[0].pick, r.asks[0].due, r.asks[0].level), ("q9", 1, None, Level::Low), "defaults off: no deadline");
        d.answer("t1".into(), "q9".into(), vec![0], String::new());
        assert!(tab(&d.take(), "t1").asks.is_empty(), "answered here, gone here too");

        // He lets the tab decide: a question asked from then on keeps its deadline.
        event(&mut d, "t1", Event { offset: 12, at: 6, body: Body::Defaults { on: true, by: jarvis_tabs_wire::By::Aviv, arm: vec![] } });
        let item = Item { text: "Red or gold?".into(), options: vec!["Red".into(), "Gold".into()], pick: 0, ..Item::default() };
        let body = Body::Question { q: "q10".into(), ap: None, ask: jarvis_tabs_wire::AskKind::Tie, level: Urgency::Low, suggest_urgent: false, items: vec![item], due: Some(99), blocks: false };
        event(&mut d, "t1", Event { offset: 13, at: 6, body });
        let r = tab(&d.take(), "t1");
        assert_eq!(r.asks.len(), 1);
        assert_eq!((r.asks[0].tabs[0].q.as_str(), r.asks[0].pick, r.asks[0].due, r.asks[0].level), ("q10", 0, Some(99), Level::Low), "defaults on: the deadline crosses");
    }

    #[test]
    fn a_connection_lists_and_follows_every_tab_from_its_end() {
        let d = up(&[("t1", 50), ("t2", 0)]);
        assert!(d.out.contains(&Ask::Follow { id: "t1".into(), after: 50 }));
        assert!(d.out.contains(&Ask::Follow { id: "t2".into(), after: 0 }));
        assert!(d.out.contains(&Ask::Follow { id: WALL.into(), after: 0 }));
    }

    #[test]
    fn showing_a_tab_reads_its_record_and_only_the_tail_crosses_after() {
        let mut d = up(&[("t1", 20)]);
        d.out.clear();
        d.show(vec!["t1".into()]);
        assert_eq!(d.out, [Ask::Follow { id: "t1".into(), after: 0 }]);
        assert!(tab(&d.take(), "t1").loading);
        event(&mut d, "t1", said(10, Who::Aviv, "hi"));
        event(&mut d, "t1", said(20, Who::Jarvis, "hello"));
        let first = tab(&d.take(), "t1");
        assert_eq!((first.from, texts(&first), first.loading), (0, vec!["hi".to_string(), "hello".to_string()], false));
        event(&mut d, "t1", said(30, Who::Jarvis, "more"));
        let next = tab(&d.take(), "t1");
        assert_eq!((next.from, texts(&next)), (2, vec!["more".to_string()]));
    }

    #[test]
    fn the_end_of_an_old_follow_crossing_a_new_one_loses_and_doubles_nothing() {
        let mut d = up(&[("t1", 10)]);
        d.show(vec!["t1".into()]);
        event(&mut d, "t1", said(20, Who::Jarvis, "late, from the old follow"));
        event(&mut d, "t1", said(10, Who::Aviv, "first"));
        event(&mut d, "t1", said(20, Who::Jarvis, "late, from the old follow"));
        let r = tab(&d.take(), "t1");
        assert_eq!(texts(&r), ["first", "late, from the old follow"]);
    }

    fn shown_up(told: &[Told]) -> bool {
        told.iter().find_map(|t| if let Told::Wall(_, _, up) = t { Some(*up) } else { None }).expect("the wall")
    }

    /// The server restarts for a new build under a connected screen: the drop isn't shown, the link
    /// asks on from each tab's end, and every line is there once, the reply mid-way included.
    #[test]
    fn a_drop_and_redial_shows_nothing_and_loses_and_doubles_no_line() {
        let mut d = up(&[("t1", 10)]);
        d.show(vec!["t1".into()]);
        event(&mut d, "t1", said(10, Who::Aviv, "first"));
        event(&mut d, "t1", said(20, Who::Jarvis, "second"));
        d.tell(Tell::Text { id: "t1".into(), block: 1, from: 0, text: "third, half".into() });
        d.take();

        d.dropped();
        assert!(shown_up(&d.take()), "a drop under the grace isn't shown");
        assert!(texts(&tab(&{ d.tabs.get_mut("t1").unwrap().tail(); d.take() }, "t1")).contains(&"third, half".to_string()), "the reply mid-way stays");

        d.connected();
        d.tell(Tell::Tabs { tabs: vec![info("t1", 1, 30)] });
        assert!(d.out.contains(&Ask::Follow { id: "t1".into(), after: 20 }), "{:?}", d.out);
        // The new server sends the live block whole, and the old follow's last line crosses the new one's.
        d.tell(Tell::Text { id: "t1".into(), block: 1, from: 0, text: "third, half way".into() });
        event(&mut d, "t1", said(20, Who::Jarvis, "second"));
        event(&mut d, "t1", said(30, Who::Jarvis, "third, whole"));
        let told = d.take();
        assert!(shown_up(&told));
        let all = { d.tabs.get_mut("t1").unwrap().moved(0); texts(&tab(&d.take(), "t1")) };
        assert_eq!(all, ["first", "second", "third, whole"]);

        // A drop that outlasts the grace is shown.
        d.dropped();
        d.quiet_until = Instant::now().checked_sub(Duration::from_secs(1));
        assert!(!shown_up(&d.take()));
    }

    #[test]
    fn marks_fold_as_on_the_server_whether_the_tab_is_shown_or_not() {
        let mut d = up(&[("t1", 0)]);
        d.take();
        event(&mut d, "t1", said(10, Who::Jarvis, "news"));
        event(&mut d, "t1", Event { offset: 20, at: 5, body: Body::Needs { text: "which?".into(), closed: false, q: String::new() } });
        let Told::Wall(tiles, ..) = &d.take()[0] else { panic!("the wall") };
        assert!(tiles[0].needs && tiles[0].unread);
        d.show(vec!["t1".into()]);
        event(&mut d, "t1", said(10, Who::Jarvis, "news"));
        event(&mut d, "t1", Event { offset: 20, at: 5, body: Body::Needs { text: "which?".into(), closed: false, q: String::new() } });
        d.out.clear();
        d.seen("t1".into());
        assert_eq!(d.out, [Ask::Seen { id: "t1".into(), upto: 20 }]);
        d.seen("t1".into());
        assert_eq!(d.out.len(), 1, "once");
    }

    #[test]
    fn what_i_said_keeps_its_place_as_it_lands() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.take();
        d.say("t1".into(), "go".into(), vec![], vec![]);
        let EntryRec::Yours { key, state: Landing::Sending, .. } = tab(&d.take(), "t1").entries[0].clone() else { panic!() };
        d.tell(Tell::Landed { id: "t1".into(), offset: 10 });
        event(&mut d, "t1", said(10, Who::Aviv, "go"));
        let r = tab(&d.take(), "t1");
        let EntryRec::Yours { key: landed, state: Landing::Sent, .. } = &r.entries[0] else { panic!() };
        assert_eq!(&key, landed);
        assert_eq!(r.entries.len(), 1);
    }

    #[test]
    fn an_answer_arriving_keeps_its_place_as_it_lands() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.take();
        d.tell(Tell::Text { id: "t1".into(), block: 1, from: 0, text: "The ch".into() });
        d.tell(Tell::Text { id: "t1".into(), block: 1, from: 6, text: "ords".into() });
        let r = tab(&d.take(), "t1");
        let EntryRec::Answer { key, offset: None, text, .. } = r.entries[0].clone() else { panic!() };
        assert_eq!(text, "The chords");
        event(&mut d, "t1", said(10, Who::Jarvis, "The chords pass."));
        let r = tab(&d.take(), "t1");
        assert_eq!(r.entries.len(), 1);
        let EntryRec::Answer { key: landed, offset: Some(10), .. } = &r.entries[0] else { panic!() };
        assert_eq!(&key, landed);
    }

    #[test]
    fn words_for_a_tab_it_doesnt_know_go_back_with_their_quote() {
        let mut d = up(&[("t1", 0)]);
        d.take();
        d.say("gone".into(), "same".into(), vec![QuoteRec { to: 5, quote: "q".into(), text: "w".into() }], vec![]);
        assert!(d.take().contains(&Told::Back(String::new(), "> q\nw\n\nsame".into())));
    }

    #[test]
    fn words_that_dont_land_go_back_and_landed_ones_stay() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "landed".into(), vec![], vec![]);
        d.tell(Tell::Landed { id: "t1".into(), offset: 10 });
        d.say("t1".into(), "lost".into(), vec![QuoteRec { to: 5, quote: "q".into(), text: "w".into() }], vec![]);
        d.start("a new thing".into(), vec![], false);
        d.take();
        d.disconnected();
        let told = d.take();
        assert!(told.contains(&Told::Back("t1".into(), "> q\nw\n\nlost".into())));
        assert!(told.contains(&Told::Back(String::new(), "a new thing".into())));
        assert_eq!(texts(&tab(&told, "t1")), ["landed"]);
    }

    /// Hold goes to the server quiet, as the laptop's enter; the tab says words wait until they went in.
    #[test]
    fn a_held_reply_goes_quiet_and_waits_until_delivered() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.out.clear();
        let q = QuoteRec { to: 5, quote: "q".into(), text: "w".into() };
        d.hold("t1".into(), String::new(), vec![q]);
        // Via quiet: the server holds it in every state, an awake idle tab included, so it never starts a turn.
        assert!(matches!(&d.out[..], [Ask::Say { quiet: true, via: Some(Via::Quiet), text, replies, .. }] if text.is_empty() && replies.len() == 1));
        d.out.clear();
        d.hold("t1".into(), "for later".into(), vec![]);
        assert!(matches!(&d.out[..], [Ask::Say { quiet: true, via: Some(Via::Quiet), text, .. }] if text == "for later"));
        let said: Event = serde_json::from_str(r#"{"offset":7,"at":1,"kind":"said","who":"aviv","text":"","via":"quiet","replies":[{"to":5,"quote":"q","text":"w"}]}"#).unwrap();
        event(&mut d, "t1", said);
        assert!(tab(&d.take(), "t1").waiting);
        event(&mut d, "t1", serde_json::from_str(r#"{"offset":8,"at":2,"kind":"delivered","upto":7}"#).unwrap());
        assert!(!tab(&d.take(), "t1").waiting);
    }

    #[test]
    fn a_file_comes_whole_and_a_missing_one_comes_empty() {
        let mut d = up(&[("t1", 0)]);
        d.take();
        d.tell(Tell::File { id: "t1".into(), name: "a.png".into(), data: "aGk=".into() });
        d.tell(Tell::File { id: "t1".into(), name: "b.png".into(), data: String::new() });
        let told = d.take();
        assert!(told.contains(&Told::File("t1".into(), "a.png".into(), b"hi".to_vec())));
        assert!(told.contains(&Told::File("t1".into(), "b.png".into(), vec![])));
    }

    #[test]
    fn a_refusal_shows_and_the_next_send_clears_it() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "no".into(), vec![], vec![]);
        d.tell(Tell::Refused { id: "t1".into(), why: "read only".into() });
        let told = d.take();
        assert!(told.contains(&Told::Back("t1".into(), "no".into())));
        assert!(matches!(&tab(&told, "t1").entries[0], EntryRec::Yours { state: Landing::Refused, why, .. } if why == "read only"));
        d.say("t1".into(), "again".into(), vec![], vec![]);
        assert_eq!(texts(&tab(&d.take(), "t1")), ["again"]);
    }

    #[test]
    fn words_at_the_wall_open_a_tab_and_land_in_it() {
        let mut d = up(&[]);
        d.out.clear();
        d.start("Plan the garden sensors please".into(), vec![], false);
        assert_eq!(d.out, [Ask::Open { want: None, name: "Plan the garden".into() }]);
        d.out.clear();
        d.tell(Tell::Opened { tab: info("t9", 1, 0) });
        assert!(d.out.contains(&Ask::Follow { id: "t9".into(), after: 0 }));
        assert!(d.out.iter().any(|a| matches!(a, Ask::Say { id, text, .. } if id == "t9" && text.starts_with("Plan"))));
        assert!(d.take().contains(&Told::Opened("t9".into())));
    }

    #[test]
    fn auto_at_the_wall_opens_a_tab_with_autopilot_on_before_any_word() {
        let mut d = up(&[]);
        d.out.clear();
        d.start(String::new(), vec![], true);
        assert_eq!(d.out, [Ask::Open { want: None, name: String::new() }]);
        d.out.clear();
        d.tell(Tell::Opened { tab: info("t9", 1, 0) });
        assert!(d.out.iter().any(|a| matches!(a, Ask::Autopilot { id, op, .. } if id == "t9" && op == "on")), "{:?}", d.out);
        assert!(!d.out.iter().any(|a| matches!(a, Ask::Say { .. })), "nothing of his to say yet");
        assert!(d.take().contains(&Told::Opened("t9".into())));
    }

    fn up_file(name: &str) -> Upload {
        Upload { file: OutFile { name: name.into(), path: format!("/x/{name}") }, data: "aGk=".into() }
    }

    fn kept(name: &str) -> FileRef {
        FileRef { name: name.into(), alt: name.into() }
    }

    #[test]
    fn files_go_up_first_and_the_words_follow_them() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.take();
        d.out.clear();
        d.say("t1".into(), "look".into(), vec![], vec![up_file("a.png"), up_file("b.pdf")]);
        assert_eq!(d.out.len(), 2);
        assert!(d.out.iter().all(|a| matches!(a, Ask::Put { id, .. } if id == "t1")));
        let EntryRec::Yours { files, state: Landing::Sending, .. } = tab(&d.take(), "t1").entries[0].clone() else { panic!() };
        assert_eq!(files, ["a.png", "b.pdf"]);
        d.out.clear();
        d.tell(Tell::Put { id: "t1".into(), name: "a.png".into(), file: kept("a.png") });
        assert!(d.out.is_empty(), "the words wait for the last file");
        d.tell(Tell::Put { id: "t1".into(), name: "b.pdf".into(), file: kept("b.pdf") });
        assert_eq!(
            d.out,
            [Ask::Say { id: "t1".into(), text: "look".into(), via: Some(Via::Typed), replies: vec![], files: vec![kept("a.png"), kept("b.pdf")], quiet: false }]
        );
    }

    #[test]
    fn a_file_with_no_words_opens_a_tab_named_after_it() {
        let mut d = up(&[]);
        d.out.clear();
        d.start("  ".into(), vec![up_file("garden plan.png")], false);
        assert_eq!(d.out, [Ask::Open { want: None, name: "garden plan.png".into() }]);
        d.out.clear();
        d.tell(Tell::Opened { tab: info("t9", 1, 0) });
        assert!(d.out.iter().any(|a| matches!(a, Ask::Put { id, name, .. } if id == "t9" && name == "garden plan.png")));
    }

    #[test]
    fn a_file_the_server_refuses_comes_back_with_the_words() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "look".into(), vec![], vec![up_file("a.png")]);
        d.tell(Tell::Refused { id: "t1".into(), why: "a.png is too big to send".into() });
        let told = d.take();
        assert!(told.contains(&Told::Back("t1".into(), "look".into())));
        assert!(told.contains(&Told::Unsent(vec![up_file("a.png").file])));
        assert!(matches!(&tab(&told, "t1").entries[0], EntryRec::Yours { state: Landing::Refused, why, .. } if why == "a.png is too big to send"));
    }

    #[test]
    fn an_older_server_never_swallows_a_file() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "".into(), vec![], vec![up_file("a.png")]);
        d.tell(Tell::Refused { id: String::new(), why: "I don't know that ask".into() });
        assert!(d.take().contains(&Told::Unsent(vec![up_file("a.png").file])));
    }

    #[test]
    fn a_link_lost_mid_upload_gives_the_file_back() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "look".into(), vec![], vec![up_file("a.png")]);
        d.tell(Tell::Put { id: "t1".into(), name: "a.png".into(), file: kept("a.png") });
        d.disconnected();
        let told = d.take();
        assert!(told.contains(&Told::Back("t1".into(), "look".into())));
        assert!(told.contains(&Told::Unsent(vec![up_file("a.png").file])));
        d.start("new".into(), vec![up_file("b.png")], false);
        d.disconnected();
        assert!(d.take().contains(&Told::Unsent(vec![up_file("b.png").file])));
    }

    #[test]
    fn reconnecting_reads_on_from_each_tabs_end() {
        let mut d = up(&[("t1", 0), ("t2", 40)]);
        d.show(vec!["t1".into()]);
        event(&mut d, "t1", said(10, Who::Aviv, "hi"));
        d.disconnected();
        d.connected();
        d.out.clear();
        d.tell(Tell::Tabs { tabs: vec![info("t1", 1, 10), info("t2", 2, 40)] });
        assert_eq!(d.out, [Ask::Follow { id: "t1".into(), after: 10 }, Ask::Follow { id: "t2".into(), after: 40 }]);
        event(&mut d, "t1", said(10, Who::Aviv, "hi"));
        assert_eq!(d.tabs["t1"].items.len(), 1);
    }

    #[test]
    fn an_older_server_doesnt_stop_anything() {
        let mut d = up(&[("t1", 0)]);
        d.tell(Tell::Refused { id: String::new(), why: "I don't know that ask".into() });
        assert!(d.take().iter().all(|t| !matches!(t, Told::Back(..))));
    }

    #[test]
    fn old_records_leave_memory_and_the_marks_stay() {
        let ids: Vec<String> = (0..KEEP + 2).map(|i| format!("t{i}")).collect();
        let mut d = up(&ids.iter().map(|i| (i.as_str(), 10)).collect::<Vec<_>>());
        for id in &ids {
            d.show(vec![id.clone()]);
        }
        assert!(!d.tabs["t0"].loaded && !d.tabs["t1"].loaded && d.tabs["t2"].loaded);
        event(&mut d, "t0", said(20, Who::Jarvis, "news"));
        assert!(d.tabs["t0"].tile.blue());
        d.out.clear();
        d.show(vec!["t0".into()]);
        assert_eq!(d.out, [Ask::Follow { id: "t0".into(), after: 0 }]);
    }

    #[test]
    fn a_question_closes_when_he_answers() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        event(&mut d, "t1", Event { offset: 10, at: 1, body: Body::Needs { text: "which?".into(), closed: false, q: String::new() } });
        event(&mut d, "t1", said(20, Who::Aviv, "the first"));
        let r = tab(&d.take(), "t1");
        assert!(matches!(&r.entries[0], EntryRec::Question { closed: true, .. }));
    }
}

/// The whole road against a private gate and tab server: the job's `tabs-e2e.sh` starts them, with a
/// fake Claude. Run with `TABS_E2E_TICKET` and `TABS_E2E_KEY` set, and `--ignored`.
#[cfg(test)]
mod road {
    use super::*;

    #[derive(Default)]
    struct Heard {
        wall: Mutex<Option<(Vec<TileRec>, bool)>>,
        tabs: Mutex<HashMap<String, Vec<EntryRec>>>,
        closed: Mutex<Vec<ClosedRec>>,
        opened: Mutex<Option<String>>,
        file: Mutex<Option<(String, Vec<u8>)>>,
        crossings: Mutex<u32>,
    }

    impl TabsListener for Heard {
        fn wall(&self, tiles: Vec<TileRec>, closed: Vec<ClosedRec>, online: bool) {
            *self.closed.lock().unwrap() = closed;
            *self.wall.lock().unwrap() = Some((tiles, online));
        }
        fn tab(&self, tab: TabRec) {
            *self.crossings.lock().unwrap() += 1;
            let mut tabs = self.tabs.lock().unwrap();
            let e = tabs.entry(tab.tile.id).or_default();
            e.truncate(tab.from as usize);
            e.extend(tab.entries);
        }
        fn file(&self, _id: String, name: String, data: Vec<u8>) {
            *self.file.lock().unwrap() = Some((name, data));
        }
        fn unsent(&self, _files: Vec<OutFile>) {}
        fn opened(&self, id: String) {
            *self.opened.lock().unwrap() = Some(id);
        }
        fn back(&self, id: String, text: String) {
            panic!("words came back to {id}: {text}");
        }
        fn queue(&self, _cards: Vec<CardRec>, _digest: Vec<DigestRec>) {}
        fn refused(&self, id: String, why: String) {
            panic!("{id} refused: {why}");
        }
    }

    fn until<T>(what: &str, mut f: impl FnMut() -> Option<T>) -> T {
        for _ in 0..600 {
            if let Some(v) = f() {
                return v;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        panic!("no sign of: {what}");
    }

    #[test]
    #[ignore]
    fn the_whole_road() {
        let var = |k: &str| std::env::var(k).unwrap();
        forum::forum_start(var("TABS_E2E_TICKET"), 18193, 18194, var("TABS_E2E_KEY"));
        let heard = Arc::new(Heard::default());
        let t0 = std::time::Instant::now();
        tabs_attach(heard.clone());
        until("the wall", || heard.wall.lock().unwrap().clone().filter(|w| w.1));
        println!("wall up in {} ms", t0.elapsed().as_millis());
        tabs_start("Hello from the road test".into(), vec![]);
        let id = until("the tab", || heard.opened.lock().unwrap().clone());
        println!("tab {id} in {} ms", t0.elapsed().as_millis());
        until("the words on the record", || {
            let tabs = heard.tabs.lock().unwrap();
            tabs.get(&id)?.iter().find(|e| matches!(e, EntryRec::Yours { state: Landing::Sent, .. })).cloned()
        });
        println!("landed in {} ms", t0.elapsed().as_millis());
        let answer = until("an answer", || {
            let tabs = heard.tabs.lock().unwrap();
            tabs.get(&id)?.iter().find_map(|e| if let EntryRec::Answer { offset: Some(_), text, .. } = e { Some(text.clone()) } else { None })
        });
        println!("answered in {} ms: {answer}", t0.elapsed().as_millis());
        tabs_seen(id.clone());
        until("blue to clear", || {
            let wall = heard.wall.lock().unwrap().clone()?;
            wall.0.iter().find(|t| t.id == id && !t.unread).cloned()
        });
        tabs_file(id.clone(), "nothing-here.png".into());
        let (name, data) = until("a file's answer", || heard.file.lock().unwrap().clone());
        assert_eq!((name.as_str(), data.len()), ("nothing-here.png", 0));
        tabs_close(id.clone());
        until("the tile to go", || {
            let wall = heard.wall.lock().unwrap().clone()?;
            wall.0.iter().all(|t| t.id != id).then_some(())
        });
        until("the tab in the closed list", || heard.closed.lock().unwrap().iter().any(|c| c.id == id).then_some(()));
        tabs_reopen(id.clone());
        until("the tile back", || {
            let wall = heard.wall.lock().unwrap().clone()?;
            wall.0.iter().find(|t| t.id == id).cloned()
        });
        tabs_close(id.clone());
        // The link goes and comes back, and reads on.
        tabs_detach();
        let again = Arc::new(Heard::default());
        tabs_attach(again.clone());
        until("the wall again", || again.wall.lock().unwrap().clone().filter(|w| w.1));
        println!("back in {} ms, {} crossings", t0.elapsed().as_millis(), heard.crossings.lock().unwrap());
        tabs_detach();
        forum::forum_stop();
    }
}
