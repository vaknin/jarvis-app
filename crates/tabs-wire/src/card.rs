//! A filing card, read from one told line: "Filed 📁 Iris › Quiet by code › \"Every filing names its full path\""
//! (iris_library `board::told`). His pick D (design/library-path): the item first, "Filed ⊡ Every filing names its
//! full path", the path faint under it, "◫ Iris › ≣ Quiet by code". The tab server reads it to keep each card its
//! own paragraph; the laptop app reads it to draw the card. Only the important actions make one: his words, "i only
//! want to get messages for when a task is opened/filed and live? so add 'lands' to the list". "Added to" files his
//! ask into a task that has it already, so it is a filing card too.

use std::ops::Range;

/// What leads a told path (iris_library `board::FOLDER`).
pub const FOLDER: &str = "📁";

/// The plain one-cell icons (east_asian_width N, width 1 in wcwidth and tmux: design/library-path/NOTES.md).
pub const AREA: &str = "⌂";
pub const BOARD: &str = "◫";
pub const THREAD: &str = "≣";
pub const TASK: &str = "⊡";

/// The Nerd glyphs for the same four kinds (nf-fa-home, nf-oct-project, nf-oct-comment_discussion,
/// nf-oct-tasklist), each three bytes in UTF-8 like the plain ones, so a byte range over one holds over the other.
const NERD: [(char, char); 4] = [('⌂', '\u{f015}'), ('◫', '\u{f501}'), ('≣', '\u{f442}'), ('⊡', '\u{f4a0}')];

/// The four kinds a path names, top down.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Area,
    Board,
    Thread,
    Task,
}

impl Kind {
    pub fn icon(self) -> &'static str {
        match self {
            Kind::Area => AREA,
            Kind::Board => BOARD,
            Kind::Thread => THREAD,
            Kind::Task => TASK,
        }
    }
}

/// The action a card tells.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Filed,
    AddedTo,
    ThreadOpened,
    Landed,
    Live,
}

impl Action {
    pub fn label(self) -> &'static str {
        match self {
            Action::Filed => "Filed",
            Action::AddedTo => "Added to",
            Action::ThreadOpened => "Thread opened",
            Action::Landed => "Landed",
            Action::Live => "Live",
        }
    }

    /// Filed, Added to and an opened thread are his ask filed: one ask is one card, so two of them for the same task
    /// are one. Landed and Live are their own events.
    pub fn files(self) -> bool {
        matches!(self, Action::Filed | Action::AddedTo | Action::ThreadOpened)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Node {
    pub kind: Kind,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Card {
    pub action: Action,
    /// The card's item: the task, or the thread when the line names no task. Never empty.
    pub item: Node,
    /// The nodes above the item, top down.
    pub above: Vec<Node>,
    /// The item's thread was opened by this action ("Opened" with a task): marked new on the path.
    pub new_thread: bool,
}

fn is_quote(c: char) -> bool {
    matches!(c, '"' | '“' | '”')
}

/// The card one told line makes, or None: any action outside the five, any other words around the line, or a
/// line that isn't a told path at all.
pub fn parse(line: &str) -> Option<Card> {
    let line = line.trim();
    let (action, path) = line.split_once(&format!(" {FOLDER} "))?;
    let path = path.trim();
    let path = path.strip_suffix('.').unwrap_or(path).trim_end();
    let mut names: Vec<&str> = path.split(" › ").map(str::trim).collect();
    if names.iter().any(|n| n.is_empty()) {
        return None;
    }
    // The task, quoted and last: "Title" or “Title”; a quote anywhere else is other words on the line.
    let last = *names.last()?;
    let task = match (last.chars().next(), last.chars().last()) {
        (Some(a), Some(b)) if is_quote(a) && is_quote(b) && last.chars().count() >= 3 => {
            let inner = &last[a.len_utf8()..last.len() - b.len_utf8()];
            if inner.trim().is_empty() {
                return None;
            }
            names.pop();
            Some(inner.trim().to_string())
        }
        _ => None,
    };
    if names.iter().any(|n| n.chars().any(is_quote)) {
        return None;
    }
    let action = match (action, task.is_some()) {
        ("Filed", _) => Action::Filed,
        ("Added to", _) => Action::AddedTo,
        ("Opened", true) => Action::Filed,
        ("Opened", false) => Action::ThreadOpened,
        ("Landed", _) => Action::Landed,
        ("Live", _) => Action::Live,
        _ => return None,
    };
    let new_thread = action == Action::Filed && line.starts_with("Opened ") && !names.is_empty();
    // Levels by position from the end: the thread right above the task, then the board, then areas.
    let mut nodes: Vec<Node> = vec![];
    let n = names.len();
    for (i, name) in names.iter().enumerate() {
        let from_end = n - 1 - i;
        let kind = match from_end {
            0 => Kind::Thread,
            1 => Kind::Board,
            _ => Kind::Area,
        };
        nodes.push(Node { kind, name: name.to_string() });
    }
    let item = match task {
        Some(t) => Node { kind: Kind::Task, name: t },
        None => nodes.pop()?,
    };
    Some(Card { action, item, above: nodes, new_thread })
}

impl Card {
    /// "Filed ⊡ Every filing names its full path", or "Thread opened ≣ Quiet by code".
    pub fn title(&self) -> String {
        format!("{} {} {}", self.action.label(), self.item.kind.icon(), self.item.name)
    }

    /// Where the item's name sits in `title()`.
    pub fn name_range(&self) -> Range<usize> {
        let at = self.action.label().len() + 1 + self.item.kind.icon().len() + 1;
        at..at + self.item.name.len()
    }

    /// Where the label and the item's icon sit in `title()`.
    pub fn label_range(&self) -> Range<usize> {
        0..self.action.label().len()
    }

    pub fn icon_range(&self) -> Range<usize> {
        let at = self.action.label().len() + 1;
        at..at + self.item.kind.icon().len()
    }

    /// "◫ Iris › ≣ Quiet by code", the thread marked " (new)" when this action opened it. Empty for a task alone.
    pub fn path(&self) -> String {
        self.path_icons().0
    }

    /// The path and where each node's icon sits in it, with its kind.
    pub fn path_icons(&self) -> (String, Vec<(Kind, Range<usize>)>) {
        let mut out = String::new();
        let mut icons = vec![];
        for (i, n) in self.above.iter().enumerate() {
            if i > 0 {
                out.push_str(" › ");
            }
            let at = out.len();
            out.push_str(n.kind.icon());
            icons.push((n.kind, at..out.len()));
            out.push(' ');
            out.push_str(&n.name);
            if self.new_thread && n.kind == Kind::Thread && i + 1 == self.above.len() {
                out.push_str(" (new)");
            }
        }
        (out, icons)
    }

    /// The task's title (or the thread's name) as one ask names it: two filings of it are one card.
    pub fn key(&self) -> String {
        self.item.name.replace(['“', '”'], "\"")
    }
}

/// The text with each plain icon swapped for its Nerd glyph (same byte length, so ranges over it hold).
pub fn nerd(s: &str) -> String {
    s.chars().map(|c| NERD.iter().find(|(p, _)| *p == c).map_or(c, |(_, n)| *n)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filecard_reads_the_levels() {
        let c = parse("Filed 📁 Jarvis tabs › Library in chat › \"Filing shows as a card with its path, drawn by code\"").expect("a card");
        assert_eq!(c.action, Action::Filed);
        assert_eq!(c.title(), "Filed ⊡ Filing shows as a card with its path, drawn by code");
        assert_eq!(c.path(), "◫ Jarvis tabs › ≣ Library in chat");
        assert_eq!(&c.title()[c.name_range()], "Filing shows as a card with its path, drawn by code");
        assert!(!c.new_thread);
        // Curly quotes and a trailing period read the same.
        let curly = parse("Added to 📁 Jarvis tabs › Library in chat › “@ mentions for areas, boards, threads and tasks”.").expect("a card");
        assert_eq!(curly.title(), "Added to ⊡ @ mentions for areas, boards, threads and tasks");
        assert_eq!(curly.path(), "◫ Jarvis tabs › ≣ Library in chat");
        // No quoted node: the last is a thread.
        let t = parse("Opened 📁 Iris › Quiet by code").expect("a card");
        assert_eq!(t.title(), "Thread opened ≣ Quiet by code");
        assert_eq!(t.path(), "◫ Iris");
        // Nerd glyphs keep the byte length.
        let p = c.path();
        assert_eq!(nerd(&p).len(), p.len());
        assert!(nerd(&c.title()).contains('\u{f4a0}') && nerd(&p).contains('\u{f501}') && nerd(&p).contains('\u{f442}'));
        let (_, icons) = c.path_icons();
        assert_eq!(icons.iter().map(|(k, _)| *k).collect::<Vec<_>>(), [Kind::Board, Kind::Thread]);
        assert_eq!(&p[icons[1].1.clone()], THREAD);
    }

    #[test]
    fn filecard_opened_with_a_task_is_one_filed_card() {
        let c = parse("Opened 📁 Jarvis tabs › TERMINAL APP UX/UI › \"Question cards: redo controls, movement and notes\"").expect("a card");
        assert_eq!(c.action, Action::Filed);
        assert_eq!(c.title(), "Filed ⊡ Question cards: redo controls, movement and notes");
        assert_eq!(c.path(), "◫ Jarvis tabs › ≣ TERMINAL APP UX/UI (new)");
        let f = parse("Filed 📁 Jarvis tabs › TERMINAL APP UX/UI › \"Question cards: redo controls, movement and notes\"").expect("a card");
        assert_eq!(f.key(), c.key(), "the same ask");
        assert!(!f.path().contains("(new)"));
    }

    #[test]
    fn filecard_only_filed_opened_landed_live() {
        let p = " 📁 Iris › Quiet by code › \"Pins\"";
        for a in ["Filed", "Added to", "Opened", "Landed", "Live"] {
            assert!(parse(&format!("{a}{p}")).is_some(), "{a}");
        }
        assert_eq!(parse(&format!("Landed{p}")).unwrap().title(), "Landed ⊡ Pins");
        assert_eq!(parse(&format!("Live{p}")).unwrap().title(), "Live ⊡ Pins");
        for a in ["Held", "Moved", "Dropped", "Resumed", "Closed", "Reopened", "Marked urgent", "Marked important", "Reworded", "Replanned"] {
            assert!(parse(&format!("{a}{p}")).is_none(), "{a}");
        }
        // Other words around the line: no card.
        assert!(parse("Added to 📁 Jarvis › Tabs › Library in chat › \"Filing shows\", and to the mockups task in the same thread.").is_none());
        assert!(parse("I filed it: Filed 📁 Iris › \"Pins\"").is_none());
        assert!(parse("Filed 📁 Iris › \"Pins\" today").is_none());
        assert!(parse("📁 Iris › \"Pins\"").is_none(), "a bare path names, it doesn't act");
        assert!(parse("Filed 📁 ").is_none());
        assert!(parse("Filed 📁 Iris ›  › \"Pins\"").is_none());
        assert!(parse("Filed as 📁 Jarvis › Iris › \"Quote his words\".").is_none());
    }

    #[test]
    fn filecard_areas_sit_above_the_board() {
        let c = parse("Filed 📁 Home › Jarvis › Tabs › Library in chat › \"Pins\"").expect("a card");
        let kinds: Vec<Kind> = c.above.iter().map(|n| n.kind).collect();
        assert_eq!(kinds, [Kind::Area, Kind::Area, Kind::Board, Kind::Thread]);
        assert_eq!(c.path(), "⌂ Home › ⌂ Jarvis › ◫ Tabs › ≣ Library in chat");
        let lone = parse("Filed 📁 \"Pins\"").expect("a card");
        assert_eq!(lone.title(), "Filed ⊡ Pins");
        assert_eq!(lone.path(), "");
    }
}
