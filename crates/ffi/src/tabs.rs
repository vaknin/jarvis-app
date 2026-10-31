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
//! and the desk stays between, so coming back draws at once and reads on from where it stopped.

use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    sync::{Arc, LazyLock, Mutex},
    time::Duration,
};

use anyhow::{Context, Result};
use jarvis_tabs_wire::{
    frame::{read_async, write_async},
    Ask, Body, Event, FileRef, Info, Reply, Tell, Tile, Via, WorkEnd, WorkState, Who, WALL,
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

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TileRec {
    pub id: String,
    pub n: u32,
    pub name: String,
    pub needs: bool,
    pub unread: bool,
    pub working: bool,
    pub read_only: bool,
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
    pub closed: bool,
    pub loading: bool,
}

#[uniffi::export(with_foreign)]
pub trait TabsListener: Send + Sync {
    fn wall(&self, tiles: Vec<TileRec>, closed: Vec<ClosedRec>, online: bool);
    fn tab(&self, tab: TabRec);
    /// A tab opened from words at the wall.
    fn opened(&self, id: String);
    /// Words that didn't land, for the box they came from: `id` is empty for the wall's.
    fn back(&self, id: String, text: String);
}

#[derive(Debug, Clone, PartialEq)]
enum Told {
    Wall(Vec<TileRec>, Vec<ClosedRec>, bool),
    Tab(TabRec),
    Opened(String),
    Back(String, String),
}

struct Pending {
    key: String,
    text: String,
    replies: Vec<Reply>,
    landed: bool,
    failed: Option<String>,
}

#[derive(Default)]
struct Hold {
    n: u32,
    mirrored: bool,
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
            Body::Report { from, line, text, .. } => {
                let line = if line.is_empty() { jarvis_tabs_wire::first_line(text) } else { line.clone() };
                Some(EntryRec::Report { key: key(), from: from.clone(), line, text: text.clone() })
            }
            Body::Needs { text, closed: false } => Some(EntryRec::Question { key: key(), text: text.clone(), closed: false }),
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
            files: vec![],
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
    /// No listing yet on this connection: every tab needs following.
    fresh: bool,
    wall_touched: bool,
    relist: bool,
    /// Records in memory, the most recently shown last.
    recent: VecDeque<String>,
    /// What each `Open` or `Reopen` on its way carries: the words to say in it, if any.
    opening: VecDeque<Option<String>>,
    keys: u64,
    out: Vec<Ask>,
    told: Vec<Told>,
}

impl Desk {
    fn connected(&mut self) {
        (self.online, self.fresh, self.wall_touched) = (true, true, true);
        self.out.extend([Ask::List, Ask::Closed, Ask::Follow { id: WALL.into(), after: self.wall_end }]);
    }

    /// Nothing still on its way will land, as far as anyone here can tell: the words go back in their
    /// boxes, so nothing is lost and nothing goes twice.
    fn disconnected(&mut self) {
        self.online = false;
        self.wall_touched = true;
        self.out.clear();
        for words in std::mem::take(&mut self.opening).into_iter().flatten() {
            self.told.push(Told::Back(String::new(), words));
        }
        for (id, t) in &mut self.tabs {
            let mut back = vec![];
            t.pending.retain(|p| {
                if p.landed {
                    return true;
                }
                back.push(as_typed(p));
                false
            });
            t.live = None;
            if !back.is_empty() {
                self.told.push(Told::Back(id.clone(), back.join("\n\n")));
            }
            t.tail();
        }
    }

    fn tell(&mut self, tell: Tell) {
        match tell {
            Tell::Tabs { tabs } => self.listed(tabs),
            Tell::Closed { tabs } => {
                self.closed = tabs;
                self.wall_touched = true;
            }
            Tell::Opened { tab } => self.opened(tab),
            Tell::Landed { id, .. } => {
                if let Some(p) = self.tabs.get_mut(&id).and_then(|t| t.pending.iter_mut().find(|p| !p.landed && p.failed.is_none())) {
                    p.landed = true;
                }
            }
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
            Tell::Refused { id, why } => self.refused(id, why),
            _ => {}
        }
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
            (t.n, t.mirrored, t.closed) = (info.n, info.mirrored, false);
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
        let words = self.opening.pop_front().flatten();
        self.relist = true;
        let t = self.tabs.entry(id.clone()).or_default();
        (t.n, t.mirrored, t.closed, t.tile) = (info.n, info.mirrored, false, info.tile);
        if !self.order.contains(&id) {
            self.order.push(id.clone());
        }
        self.show(vec![id.clone()]);
        if let Some(words) = words {
            self.say(id.clone(), words, vec![]);
            self.told.push(Told::Opened(id));
        }
    }

    fn refused(&mut self, id: String, why: String) {
        // An older server than this app: what it doesn't know, the app does without.
        if why == "I don't know that ask" {
            return;
        }
        if id.is_empty() {
            if let Some(words) = self.opening.pop_front().flatten() {
                self.told.push(Told::Back(String::new(), words));
            }
            return;
        }
        if let Some(at) = self.opening.iter().position(Option::is_none) {
            self.opening.remove(at);
        }
        let mut back = None;
        if let Some(t) = self.tabs.get_mut(&id) {
            if let Some(p) = t.pending.iter_mut().find(|p| !p.landed && p.failed.is_none()) {
                p.failed = Some(why);
                back = Some(as_typed(p));
                t.tail();
            }
        }
        if let Some(words) = back {
            self.told.push(Told::Back(id, words));
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

    fn say(&mut self, id: String, text: String, quotes: Vec<QuoteRec>) {
        let Some(t) = self.tabs.get_mut(&id) else { return };
        // What failed is back in the box, and this may well be it.
        t.pending.retain(|p| p.failed.is_none());
        let replies: Vec<Reply> = quotes.into_iter().map(|q| Reply { to: q.to, quote: q.quote, text: q.text }).collect();
        self.keys += 1;
        t.pending.push(Pending { key: format!("mine{}", self.keys), text: text.clone(), replies: replies.clone(), landed: false, failed: None });
        t.tail();
        self.out.push(Ask::Say { id, text, via: Some(Via::Typed), replies, files: vec![] });
    }

    fn start(&mut self, text: String) {
        let name = text.split_whitespace().take(3).collect::<Vec<_>>().join(" ").chars().take(24).collect();
        self.opening.push_back(Some(text));
        self.out.push(Ask::Open { want: None, name });
    }

    fn reopen(&mut self, id: String) {
        self.opening.push_back(None);
        self.out.push(Ask::Reopen { id });
    }

    fn seen(&mut self, id: String) {
        let Some(t) = self.tabs.get_mut(&id).filter(|t| t.loaded && t.tile.blue()) else { return };
        let upto = t.end;
        if t.mirrored {
            // Only the voice server could record this: it clears here, for this phone.
            t.tile.apply(&Event { offset: 0, at: 0, body: Body::Seen { upto } });
            t.touched = true;
            self.wall_touched = true;
        } else if upto > t.seen_sent && t.tile.seen_moves(upto) {
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
            read_only: t.mirrored,
        }
    }

    /// What the screen hasn't heard yet.
    fn take(&mut self) -> Vec<Told> {
        let mut told = std::mem::take(&mut self.told);
        if std::mem::take(&mut self.wall_touched) {
            let tiles = self.order.iter().filter_map(|id| self.tabs.get(id).map(|t| self.tile(id, t))).collect();
            let closed = self.closed.iter().map(|c| ClosedRec { id: c.id.clone(), name: c.tile.name.clone(), line: c.tile.line.clone() }).collect();
            told.push(Told::Wall(tiles, closed, self.online));
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
                closed: t.closed,
                loading: t.end < t.tile.end,
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

struct Attached {
    listener: Arc<dyn TabsListener>,
    task: tokio::task::JoinHandle<()>,
}

static SHARED: LazyLock<Shared> = LazyLock::new(Shared::default);

/// The screen hears what's new, without the desk's lock held.
fn deliver() {
    let told = SHARED.desk.lock().unwrap().take();
    let Some(listener) = SHARED.attached.lock().unwrap().as_ref().map(|a| a.listener.clone()) else { return };
    for t in told {
        match t {
            Told::Wall(tiles, closed, online) => listener.wall(tiles, closed, online),
            Told::Tab(tab) => listener.tab(tab),
            Told::Opened(id) => listener.opened(id),
            Told::Back(id, text) => listener.back(id, text),
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
    SHARED.desk.lock().unwrap().connected();
    let mut tick = tokio::time::interval(FRAME);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut dirty = true;
    loop {
        tokio::select! {
            t = rx.recv() => {
                let Some(t) = t else { break };
                let mut desk = SHARED.desk.lock().unwrap();
                desk.tell(t);
                while let Ok(t) = rx.try_recv() {
                    desk.tell(t);
                }
                desk.settle();
                dirty = true;
            }
            _ = SHARED.nudge.notified() => dirty = true,
            _ = tick.tick(), if dirty => {
                deliver();
                dirty = false;
            }
        }
        let out = std::mem::take(&mut SHARED.desk.lock().unwrap().out);
        for ask in out {
            write_async(&mut send, &ask).await.context("write")?;
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
    let (mut wait, mut failed) = (RETRY.0, 0);
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
                (wait, failed) = (RETRY.0, 0);
                let end = serve(stream).await;
                SHARED.desk.lock().unwrap().disconnected();
                end.err().map(|e| format!("{e:#}"))
            }
            Err(e) => {
                failed += 1;
                Some(format!("{e:#}"))
            }
        };
        tracing::info!("tabs: link down: {why:?}");
        tokio::time::sleep(wait.min(GRACE)).await;
        deliver();
        tokio::time::sleep(wait.saturating_sub(GRACE)).await;
        wait = (wait * 2).min(RETRY.1);
    }
}

/// The screens are up: they hear everything now, and the link comes up.
#[uniffi::export]
pub fn tabs_attach(listener: Arc<dyn TabsListener>) {
    let mut attached = SHARED.attached.lock().unwrap();
    if let Some(old) = attached.take() {
        old.task.abort();
    }
    *attached = Some(Attached { listener, task: RT.spawn(keep_linked()) });
    drop(attached);
    let mut desk = SHARED.desk.lock().unwrap();
    desk.wall_touched = true;
    for t in desk.tabs.values_mut() {
        if t.loaded {
            t.moved(0);
        }
    }
    drop(desk);
    deliver();
}

/// The screens are gone: no link and no battery until they're back.
#[uniffi::export]
pub fn tabs_detach() {
    if let Some(old) = SHARED.attached.lock().unwrap().take() {
        old.task.abort();
    }
    SHARED.desk.lock().unwrap().disconnected();
}

fn with_desk(f: impl FnOnce(&mut Desk)) {
    f(&mut SHARED.desk.lock().unwrap());
    SHARED.nudge.notify_one();
}

#[uniffi::export]
pub fn tabs_show(ids: Vec<String>) {
    with_desk(|d| d.show(ids));
}

#[uniffi::export]
pub fn tabs_start(text: String) {
    with_desk(|d| d.start(text));
}

#[uniffi::export]
pub fn tabs_say(id: String, text: String, quotes: Vec<QuoteRec>) {
    with_desk(|d| d.say(id, text, quotes));
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

#[uniffi::export]
pub fn tabs_move(id: String, to: u32) {
    with_desk(|d| d.out.push(Ask::Move { id, to: to as usize }));
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

    fn up(tabs: &[(&str, u64)]) -> Desk {
        let mut d = Desk::default();
        d.connected();
        d.tell(Tell::Tabs { tabs: tabs.iter().enumerate().map(|(i, (id, end))| info(id, i as u32 + 1, *end)).collect() });
        d
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

    #[test]
    fn marks_fold_as_on_the_server_whether_the_tab_is_shown_or_not() {
        let mut d = up(&[("t1", 0)]);
        d.take();
        event(&mut d, "t1", said(10, Who::Jarvis, "news"));
        event(&mut d, "t1", Event { offset: 20, at: 5, body: Body::Needs { text: "which?".into(), closed: false } });
        let Told::Wall(tiles, ..) = &d.take()[0] else { panic!("the wall") };
        assert!(tiles[0].needs && tiles[0].unread);
        d.show(vec!["t1".into()]);
        event(&mut d, "t1", said(10, Who::Jarvis, "news"));
        event(&mut d, "t1", Event { offset: 20, at: 5, body: Body::Needs { text: "which?".into(), closed: false } });
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
        d.say("t1".into(), "go".into(), vec![]);
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
    fn words_that_dont_land_go_back_and_landed_ones_stay() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "landed".into(), vec![]);
        d.tell(Tell::Landed { id: "t1".into(), offset: 10 });
        d.say("t1".into(), "lost".into(), vec![QuoteRec { to: 5, quote: "q".into(), text: "w".into() }]);
        d.start("a new thing".into());
        d.take();
        d.disconnected();
        let told = d.take();
        assert!(told.contains(&Told::Back("t1".into(), "> q\nw\n\nlost".into())));
        assert!(told.contains(&Told::Back(String::new(), "a new thing".into())));
        assert_eq!(texts(&tab(&told, "t1")), ["landed"]);
    }

    #[test]
    fn a_refusal_shows_and_the_next_send_clears_it() {
        let mut d = up(&[("t1", 0)]);
        d.show(vec!["t1".into()]);
        d.say("t1".into(), "no".into(), vec![]);
        d.tell(Tell::Refused { id: "t1".into(), why: "read only".into() });
        let told = d.take();
        assert!(told.contains(&Told::Back("t1".into(), "no".into())));
        assert!(matches!(&tab(&told, "t1").entries[0], EntryRec::Yours { state: Landing::Refused, why, .. } if why == "read only"));
        d.say("t1".into(), "again".into(), vec![]);
        assert_eq!(texts(&tab(&d.take(), "t1")), ["again"]);
    }

    #[test]
    fn words_at_the_wall_open_a_tab_and_land_in_it() {
        let mut d = up(&[]);
        d.out.clear();
        d.start("Plan the garden sensors please".into());
        assert_eq!(d.out, [Ask::Open { want: None, name: "Plan the garden".into() }]);
        d.out.clear();
        d.tell(Tell::Opened { tab: info("t9", 1, 0) });
        assert!(d.out.contains(&Ask::Follow { id: "t9".into(), after: 0 }));
        assert!(d.out.iter().any(|a| matches!(a, Ask::Say { id, text, .. } if id == "t9" && text.starts_with("Plan"))));
        assert!(d.take().contains(&Told::Opened("t9".into())));
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
        event(&mut d, "t1", Event { offset: 10, at: 1, body: Body::Needs { text: "which?".into(), closed: false } });
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
        opened: Mutex<Option<String>>,
        crossings: Mutex<u32>,
    }

    impl TabsListener for Heard {
        fn wall(&self, tiles: Vec<TileRec>, _closed: Vec<ClosedRec>, online: bool) {
            *self.wall.lock().unwrap() = Some((tiles, online));
        }
        fn tab(&self, tab: TabRec) {
            *self.crossings.lock().unwrap() += 1;
            let mut tabs = self.tabs.lock().unwrap();
            let e = tabs.entry(tab.tile.id).or_default();
            e.truncate(tab.from as usize);
            e.extend(tab.entries);
        }
        fn opened(&self, id: String) {
            *self.opened.lock().unwrap() = Some(id);
        }
        fn back(&self, id: String, text: String) {
            panic!("words came back to {id}: {text}");
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
        tabs_start("Hello from the road test".into());
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
        tabs_close(id.clone());
        until("the tile to go", || {
            let wall = heard.wall.lock().unwrap().clone()?;
            wall.0.iter().all(|t| t.id != id).then_some(())
        });
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
