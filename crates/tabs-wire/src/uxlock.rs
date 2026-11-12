//! The UX lock: what Aviv already uses, written down, so none of it goes without his word. On
//! 2026-11-01 a job reworked the wall's quoting on its own and the reply under a paragraph was gone:
//! "you did such a massive design change without consulting me, this is a bug and shouldn't happen
//! again". A `ux.lock` file lists the keys and the flows; what's here says what a change to it needs.
//!
//! ```text
//! # a line starting with # is a comment
//! key GROUP | KEY / KEY | NAME | WHAT IT DOES
//! flow NAME
//!   what: WHAT IT DOES, IN WORDS
//!   a step, one per line
//!   approved: 2026-11-01 "his own words"
//! removed key ...
//! ```
//!
//! Adding is free. An entry that disappears, or whose steps or key change, needs an `approved:` line
//! it didn't have before, with the date and his words in quotes. To take one out, it stays in the
//! file as `removed`, with that line. Only the words of `what` changed, with the same keys or steps,
//! is a `reworded:` line of the job's own, and it still has to say so first in its report.
//!
//! This is the plain text side, for the wall's test and `jarvis guard-hook` alike. Checking that the
//! quote is really his is the guard's, since a test has no records to read.

/// What the file is called, in any repo.
pub const FILE: &str = "ux.lock";

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Entry {
    /// `key Anywhere | alt+n | new-tab`, `flow quote-reply`: the entry, apart from its words.
    pub id: String,
    pub what: String,
    /// A flow's steps.
    pub steps: Vec<String>,
    pub removed: bool,
    pub approved: Vec<String>,
    pub reworded: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Approval {
    pub date: String,
    pub quote: String,
}

/// `2026-11-01 "his words"`. Two words at least: a bare "yes" could be anyone's answer to anything.
pub fn approval(line: &str) -> Option<Approval> {
    let (date, quote) = line.trim().split_once(' ')?;
    let digits = date.split('-').map(str::len).eq([4, 2, 2]) && date.chars().all(|c| c.is_ascii_digit() || c == '-');
    let quote = quote.trim().strip_prefix('"')?.strip_suffix('"')?;
    (digits && quote.split_whitespace().count() >= 2).then(|| Approval { date: date.to_string(), quote: quote.to_string() })
}

/// The entries of a lock file. A key's first three fields are what it is, the fourth what it does.
pub fn parse(text: &str) -> Vec<Entry> {
    let mut out: Vec<Entry> = vec![];
    for line in text.lines() {
        if line.trim().is_empty() || line.trim_start().starts_with('#') {
            continue;
        }
        if line.starts_with(char::is_whitespace) {
            let Some(e) = out.last_mut() else { continue };
            let line = line.trim();
            if let Some(w) = line.strip_prefix("what:") {
                e.what = w.trim().to_string();
            } else if let Some(a) = line.strip_prefix("approved:") {
                e.approved.push(a.trim().to_string());
            } else if let Some(r) = line.strip_prefix("reworded:") {
                e.reworded.push(r.trim().to_string());
            } else {
                e.steps.push(line.to_string());
            }
            continue;
        }
        let (removed, head) = line.strip_prefix("removed ").map_or((false, line), |h| (true, h));
        let mut e = Entry { removed, id: head.trim().to_string(), ..Entry::default() };
        if head.starts_with("key ") {
            if let Some((id, what)) = head.rsplit_once(" | ") {
                e.id = id.trim().to_string();
                e.what = what.trim().to_string();
            }
        }
        out.push(e);
    }
    out
}

/// How an entry that was there has changed.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum How {
    /// Not in the file any more, not even as `removed`.
    Gone,
    Removed,
    Changed,
    Reworded,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Change {
    pub id: String,
    pub how: How,
    /// The approvals it gained. None that hold, and it needs one.
    pub approved: Vec<Approval>,
    /// A `reworded:` line it gained.
    pub reworded: bool,
}

impl Change {
    /// Does the file say what it needs to? A quote's being his is checked apart.
    pub fn ok(&self) -> bool {
        match self.how {
            How::Gone => false,
            How::Reworded => self.reworded || !self.approved.is_empty(),
            How::Removed | How::Changed => !self.approved.is_empty(),
        }
    }

    pub fn say(&self) -> String {
        let id = &self.id;
        match self.how {
            How::Gone => format!("\"{id}\" disappeared from the lock (taking one out leaves it as `removed`, with an `approved:` line)"),
            How::Removed => format!("\"{id}\" is removed"),
            How::Changed => format!("\"{id}\" changed"),
            How::Reworded => format!("the words of \"{id}\" changed"),
        }
    }
}

fn same(a: &Entry, b: &Entry) -> bool {
    a.id == b.id && a.what == b.what && a.steps == b.steps && a.removed == b.removed
}

/// What happened to each entry of `old` that's different in `new`. Keys with the same id (enter means
/// several things while quoting) pair up: unchanged ones first, then the rest in order.
pub fn changes(old: &str, new: &str) -> Vec<Change> {
    let mut new = parse(new);
    let mut left = vec![];
    for e in parse(old).into_iter().filter(|e| !e.removed) {
        match new.iter().position(|n| same(n, &e)) {
            Some(i) => {
                new.remove(i);
            }
            None => left.push(e),
        }
    }
    let mut out = vec![];
    for e in left {
        let Some(i) = new.iter().position(|n| n.id == e.id) else {
            out.push(Change { id: e.id, how: How::Gone, approved: vec![], reworded: false });
            continue;
        };
        let n = new.remove(i);
        let how = if n.removed {
            How::Removed
        } else if n.steps != e.steps {
            How::Changed
        } else {
            How::Reworded
        };
        let approved = n.approved.iter().filter(|a| !e.approved.contains(a)).filter_map(|a| approval(a)).collect();
        let reworded = n.reworded.iter().any(|r| !r.is_empty() && !e.reworded.contains(r));
        out.push(Change { id: e.id, how, approved, reworded });
    }
    out
}

/// Every `approved:` line `new` has that `old` didn't, on any entry, a new one too: his agreement to a
/// design, which needs no mockup.
pub fn agreed(old: &str, new: &str) -> Vec<Approval> {
    let was = parse(old);
    parse(new).iter().flat_map(|n| {
        let had = was.iter().filter(|e| e.id == n.id).flat_map(|e| e.approved.iter()).collect::<Vec<_>>();
        n.approved.iter().filter(move |a| !had.contains(a)).filter_map(|a| approval(a)).collect::<Vec<_>>()
    }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const OLD: &str = "# comment\nkey Tab | alt+n | new-tab | a new tab\nkey Quote | enter | | open a box\nkey Quote | enter | | keep the reply\n\nflow quote\n  what: a reply under its paragraph\n  press Enter\n  see box\n";

    #[test]
    fn an_approval_a_file_gained_is_his_agreement_on_a_new_entry_too() {
        let line = "  approved: 2026-11-01 \"sounds good to me\"\n";
        let flow = format!("flow peek\n  what: the list peeks\n  see list\n{line}");
        assert_eq!(agreed(OLD, &format!("{OLD}{flow}")), [Approval { date: "2026-11-01".into(), quote: "sounds good to me".into() }]);
        assert_eq!(agreed(&flow, &flow), [], "had it already");
        assert_eq!(agreed(&flow, OLD), [], "only taken out");
    }

    fn only(old: &str, new: &str) -> Change {
        let c = changes(old, new);
        assert_eq!(c.len(), 1, "{c:?}");
        c[0].clone()
    }

    #[test]
    fn it_reads_keys_and_flows() {
        let e = parse(OLD);
        assert_eq!(e.len(), 4);
        assert_eq!(e[0].id, "key Tab | alt+n | new-tab");
        assert_eq!(e[0].what, "a new tab");
        assert_eq!(e[3].id, "flow quote");
        assert_eq!(e[3].what, "a reply under its paragraph");
        assert_eq!(e[3].steps, ["press Enter", "see box"]);
    }

    #[test]
    fn adding_and_moving_are_free() {
        let more = format!("key Tab | alt+z | zoom | zoom in\n{OLD}flow another\n  press Up\n");
        assert_eq!(changes(OLD, &more), vec![]);
        assert_eq!(changes("", OLD), vec![], "a lock that wasn't there has nothing to lose");
    }

    #[test]
    fn an_entry_that_disappears_can_never_pass() {
        let c = only(OLD, &OLD.replace("key Tab | alt+n | new-tab | a new tab\n", ""));
        assert_eq!(c.how, How::Gone);
        assert!(!c.ok());
        assert!(changes(OLD, "").iter().all(|c| !c.ok()), "nor can emptying the file");
    }

    #[test]
    fn taking_one_out_needs_his_words_and_a_date() {
        let out = OLD.replace("key Tab | alt+n | new-tab | a new tab\n", "removed key Tab | alt+n | new-tab | a new tab\n");
        assert!(!only(OLD, &out).ok());
        let with = |line: &str| out.replace("removed key Tab | alt+n | new-tab | a new tab\n", &format!("removed key Tab | alt+n | new-tab | a new tab\n  approved: {line}\n"));
        let c = only(OLD, &with("2026-11-01 \"yes remove it\""));
        assert_eq!(c.how, How::Removed);
        assert!(c.ok());
        assert_eq!(c.approved[0].quote, "yes remove it");
        for bad in ["yes remove it", "yesterday \"yes remove it\"", "2026-11-01 \"yes\"", "2026-11-01 yes remove it", ""] {
            assert!(!only(OLD, &with(bad)).ok(), "{bad}");
        }
    }

    #[test]
    fn a_changed_flow_needs_a_new_approval_not_an_old_one() {
        let new = OLD.replace("  see box\n", "  see one shared box\n");
        assert_eq!(only(OLD, &new).how, How::Changed);
        assert!(!only(OLD, &new).ok());
        let once = OLD.replace("  see box\n", "  see box\n  approved: 2026-10-01 \"keep the box\"\n");
        let again = new.replace("  see one shared box\n", "  see one shared box\n  approved: 2026-10-01 \"keep the box\"\n");
        assert!(!only(&once, &again).ok(), "last time's approval doesn't cover this change");
        let fresh = again.replace("2026-10-01 \"keep the box\"\n", "2026-10-01 \"keep the box\"\n  approved: 2026-11-01 \"one shared box then\"\n");
        assert!(only(&once, &fresh).ok());
    }

    #[test]
    fn a_rewording_is_the_job_s_own_to_say_but_a_new_step_is_not() {
        let new = OLD.replace("key Tab | alt+n | new-tab | a new tab", "key Tab | alt+n | new-tab | open a new tab");
        assert_eq!(only(OLD, &new).how, How::Reworded);
        assert!(!only(OLD, &new).ok());
        let said = new.replace("open a new tab\n", "open a new tab\n  reworded: 2026-11-01 same key, clearer words\n");
        assert!(only(OLD, &said).ok());
        let steps = OLD.replace("  see box\n", "  see box\n  see more\n  reworded: 2026-11-01 only words\n");
        assert!(!only(OLD, &steps).ok(), "a step is behaviour");
    }

    #[test]
    fn keys_with_the_same_id_pair_up_by_what_is_unchanged() {
        let new = OLD.replace("| keep the reply", "| send the reply");
        let c = only(OLD, &new);
        assert_eq!(c.how, How::Reworded);
        assert_eq!(c.id, "key Quote | enter |");
    }

    #[test]
    fn an_old_tombstone_can_go() {
        assert_eq!(changes("removed key Tab | alt+n | new-tab | a new tab\n  approved: 2026-11-01 \"yes remove\"\n", ""), vec![]);
    }
}
