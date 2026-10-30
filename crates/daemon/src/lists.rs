//! The built-in `list` channel kind (`docs/PLAN.md`, "App channels"): a to-do or shopping list the
//! daemon keeps itself, so no app has to run. Works for any channel whose manifest says
//! `kind = "list"`.
//!
//! The state is `<state dir>/lists/<channel>.json`. The view the phone and the panel draw is
//! derived from it and republished after every change; the phone's and the panel's taps arrive
//! as ordinary app items and are applied here. Other programs use `jarvis-link list <channel> …`
//! (add, ls, done, undo, rm, clear), which goes through the same code.

use std::{
    collections::HashMap,
    path::PathBuf,
    sync::Mutex,
};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use jarvis_core::{node::Node, store::now_ms};
use tracing::warn;
use uuid::Uuid;

const HOUR_MS: i64 = 3_600_000;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Item {
    pub id: String,
    pub text: String,
    #[serde(default)]
    pub done: bool,
    pub added_ms: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub done_ms: Option<i64>,
    /// Who added it: `phone`, `laptop` or `app`.
    #[serde(default)]
    pub by: String,
}

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
pub struct List {
    pub items: Vec<Item>,
}

/// What `jarvis-link list` asks of a list channel.
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum ListOp {
    Add { text: String },
    Ls { #[serde(default)] pending: bool },
    Done { ids: Vec<String> },
    Undo { ids: Vec<String> },
    Rm { ids: Vec<String> },
    /// Everything, or with `done_only` just the ticked items.
    Clear { #[serde(default)] done_only: bool },
}

impl List {
    /// Adds one item per non-empty line. The first line takes `id` (the sender's uid, so its
    /// pending echo clears and a replayed add is a no-op), the next ones `1~<id>`, `2~<id>`, … (distinct from the start, so short prefixes tell them apart)
    /// Returns the ids added.
    pub fn add(&mut self, text: &str, id: Option<&str>, by: &str, now: i64) -> Vec<String> {
        let base = id.map(str::to_string).unwrap_or_else(|| Uuid::new_v4().to_string());
        let mut added = Vec::new();
        for (n, line) in text.lines().map(str::trim).filter(|l| !l.is_empty()).enumerate() {
            let id = if n == 0 { base.clone() } else { format!("{n}~{base}") };
            if self.items.iter().any(|i| i.id == id) {
                continue;
            }
            self.items.push(Item { id: id.clone(), text: line.into(), done: false, added_ms: now, done_ms: None, by: by.into() });
            added.push(id);
        }
        added
    }

    /// The item an id or a unique id prefix names.
    pub fn resolve(&self, key: &str) -> Result<usize> {
        if let Some(i) = self.items.iter().position(|i| i.id == key) {
            return Ok(i);
        }
        let mut hits = self.items.iter().enumerate().filter(|(_, i)| !key.is_empty() && i.id.starts_with(key));
        match (hits.next(), hits.next()) {
            (Some((i, _)), None) => Ok(i),
            (None, _) => bail!("no item {key:?}"),
            _ => bail!("{key:?} matches more than one item"),
        }
    }

    pub fn set_done(&mut self, i: usize, done: bool, now: i64) -> bool {
        let it = &mut self.items[i];
        if it.done == done {
            return false;
        }
        it.done = done;
        it.done_ms = done.then_some(now);
        true
    }

    pub fn remove_done(&mut self) -> usize {
        let n = self.items.len();
        self.items.retain(|i| !i.done);
        n - self.items.len()
    }

    /// Drops done items older than `keep_hours`.
    pub fn prune(&mut self, keep_hours: u32, now: i64) -> usize {
        let n = self.items.len();
        let limit = i64::from(keep_hours) * HOUR_MS;
        self.items.retain(|i| !(i.done && i.done_ms.is_some_and(|t| now - t > limit)));
        n - self.items.len()
    }

    pub fn pending(&self) -> usize {
        self.items.iter().filter(|i| !i.done).count()
    }

    /// The view: an add box, the items (pending first, newest last within each group), and
    /// "Clear done" when something is ticked. No `notify`, so a change doesn't wake the phone.
    pub fn view(&self) -> Value {
        let mut order: Vec<&Item> = self.items.iter().collect();
        order.sort_by_key(|i| (i.done, if i.done { -i.done_ms.unwrap_or(0) } else { i.added_ms }));
        let items: Vec<Value> = order
            .iter()
            .map(|i| {
                json!({
                    "id": i.id, "label": i.text, "checked": i.done,
                    "actions": [{ "id": "rm", "label": "✕", "confirm": true }],
                })
            })
            .collect();
        let mut blocks = vec![json!({
            "type": "compose", "id": "add", "placeholder": "Add item…", "submit": "Add", "multi": true,
        })];
        if items.is_empty() {
            blocks.push(json!({ "type": "notice", "id": "empty", "text": "Nothing here yet.", "tone": "info" }));
        } else {
            blocks.push(json!({ "type": "checklist", "id": "items", "items": items }));
        }
        if self.items.iter().any(|i| i.done) {
            blocks.push(json!({
                "type": "buttons", "id": "clear",
                "items": [{ "id": "clear_done", "label": "Clear done", "confirm": true }],
            }));
        }
        json!({ "v": 1, "badge": self.pending(), "blocks": blocks })
    }

    /// Applies one action from the phone or the panel (`{"action":…,"value":…,"uid","from"}`).
    /// True when the list changed.
    pub fn apply_action(&mut self, a: &Value, now: i64) -> bool {
        let by = a["from"].as_str().filter(|f| matches!(*f, "phone" | "laptop")).unwrap_or("phone");
        let item = |a: &Value| a["value"]["item"].as_str().and_then(|k| self.resolve(k).ok());
        match a["action"].as_str() {
            Some("add") => {
                let text = a["value"]["text"].as_str().unwrap_or("");
                !self.add(text, a["uid"].as_str(), by, now).is_empty()
            }
            Some("items") => match (item(a), a["value"]["checked"].as_bool()) {
                (Some(i), Some(c)) => self.set_done(i, c, now),
                _ => false,
            },
            Some("rm") => match item(a) {
                Some(i) => {
                    self.items.remove(i);
                    true
                }
                None => false,
            },
            Some("clear_done") => self.remove_done() > 0,
            _ => false,
        }
    }

    /// Runs a CLI op; the JSON is the socket reply. The bool says whether the list changed.
    pub fn apply_op(&mut self, op: ListOp, now: i64) -> Result<(Value, bool)> {
        Ok(match op {
            ListOp::Add { text } => {
                let ids = self.add(&text, None, "app", now);
                anyhow::ensure!(!ids.is_empty(), "nothing to add");
                let changed = !ids.is_empty();
                (json!({ "added": ids }), changed)
            }
            ListOp::Ls { pending } => {
                let v: Vec<&Item> = self.items.iter().filter(|i| !pending || !i.done).collect();
                (serde_json::to_value(v)?, false)
            }
            ListOp::Done { ids } => self.apply_ids(IdsOp::Done, &ids, now)?,
            ListOp::Undo { ids } => self.apply_ids(IdsOp::Undo, &ids, now)?,
            ListOp::Rm { ids } => self.apply_ids(IdsOp::Rm, &ids, now)?,
            ListOp::Clear { done_only } => {
                let n = if done_only {
                    self.remove_done()
                } else {
                    std::mem::take(&mut self.items).len()
                };
                (json!({ "removed": n }), n > 0)
            }
        })
    }

    /// `done`, `undo` and `rm` by id or prefix. All ids must resolve, or nothing changes.
    fn apply_ids(&mut self, kind: IdsOp, keys: &[String], now: i64) -> Result<(Value, bool)> {
        let mut idx = keys.iter().map(|k| self.resolve(k)).collect::<Result<Vec<_>>>()?;
        idx.sort_unstable();
        idx.dedup();
        let mut changed = 0;
        match kind {
            IdsOp::Done => idx.iter().for_each(|&i| changed += usize::from(self.set_done(i, true, now))),
            IdsOp::Undo => idx.iter().for_each(|&i| changed += usize::from(self.set_done(i, false, now))),
            IdsOp::Rm => {
                for &i in idx.iter().rev() {
                    self.items.remove(i);
                    changed += 1;
                }
            }
        }
        Ok((json!({ "changed": changed }), changed > 0))
    }
}

#[derive(Debug, Clone, Copy)]
enum IdsOp {
    Done,
    Undo,
    Rm,
}

/// All list channels' state, loaded lazily from `dir` and saved after each change.
pub struct Lists {
    dir: PathBuf,
    lists: Mutex<HashMap<String, List>>,
}

impl Lists {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir, lists: Mutex::default() }
    }

    fn path(&self, channel: &str) -> PathBuf {
        self.dir.join(format!("{channel}.json"))
    }

    fn load(&self, channel: &str) -> List {
        match std::fs::read_to_string(self.path(channel)) {
            Ok(s) => serde_json::from_str(&s).unwrap_or_else(|e| {
                warn!("list {channel}: {e}; starting empty (the old file is kept as .bad)");
                let _ = std::fs::rename(self.path(channel), self.path(channel).with_extension("bad"));
                List::default()
            }),
            Err(_) => List::default(),
        }
    }

    fn save(&self, channel: &str, list: &List) -> Result<()> {
        std::fs::create_dir_all(&self.dir).context("create the lists folder")?;
        let path = self.path(channel);
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(list)?).context("write the list")?;
        std::fs::rename(&tmp, &path).context("replace the list")
    }

    /// Runs `f` on the channel's list under the lock; when it says the list changed (or the
    /// prune did), saves it. The returned value is `f`'s. Does not publish: see `sync`.
    pub fn with<R>(&self, channel: &str, keep_hours: u32, f: impl FnOnce(&mut List) -> Result<(R, bool)>) -> Result<(R, bool)> {
        let mut all = self.lists.lock().unwrap();
        let list = all.entry(channel.to_string()).or_insert_with(|| self.load(channel));
        let pruned = list.prune(keep_hours, now_ms()) > 0;
        let (r, changed) = f(list)?;
        if changed || pruned {
            self.save(channel, list)?;
        }
        Ok((r, changed || pruned))
    }

    /// Queues the channel's view unless the newest stored one already says the same.
    pub fn sync(&self, node: &Node, channel: &str, keep_hours: u32) -> Result<bool> {
        let (view, _) = self.with(channel, keep_hours, |l| Ok((l.view().to_string(), false)))?;
        if node.app_view(channel)?.as_deref() == Some(view.as_str()) {
            return Ok(false);
        }
        node.send_app(channel, view, true)?;
        Ok(true)
    }

    /// One item from the phone or the panel: apply it, mark it handled, publish the view.
    pub fn on_item(&self, node: &Node, channel: &str, keep_hours: u32, id: Uuid, data: &str) -> Result<()> {
        let a: Value = serde_json::from_str(data).unwrap_or(Value::Null);
        self.with(channel, keep_hours, |l| Ok(((), l.apply_action(&a, now_ms()))))?;
        node.app_done(id)?;
        self.sync(node, channel, keep_hours)?;
        Ok(())
    }

    /// Takes whatever is waiting on the channel (items that arrived while the daemon was down),
    /// then publishes the view if it differs.
    pub fn catch_up(&self, node: &Node, channel: &str, keep_hours: u32) -> Result<()> {
        for (id, data) in node.app_pending(channel)? {
            self.on_item(node, channel, keep_hours, id, &data)?;
        }
        self.sync(node, channel, keep_hours)?;
        Ok(())
    }

    /// A `jarvis-link list` command: apply, publish if it changed, reply.
    pub fn run_op(&self, node: &Node, channel: &str, keep_hours: u32, op: ListOp) -> Result<Value> {
        let now = now_ms();
        let (reply, changed) = self.with(channel, keep_hours, |l| l.apply_op(op, now))?;
        if changed {
            self.sync(node, channel, keep_hours)?;
        }
        Ok(reply)
    }

    /// Drops the in-memory state of channels that aren't lists any more (the file stays), so a
    /// channel re-added under the same name reads its file again.
    pub fn retain(&self, names: &[String]) {
        self.lists.lock().unwrap().retain(|k, _| names.contains(k));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: i64 = 1_000_000_000;

    #[test]
    fn add_splits_lines_and_a_replayed_id_is_a_no_op() {
        let mut l = List::default();
        assert_eq!(l.add("milk\n\n  eggs \nbread", Some("u1"), "phone", T), ["u1", "1~u1", "2~u1"]);
        assert_eq!(l.items.iter().map(|i| i.text.as_str()).collect::<Vec<_>>(), ["milk", "eggs", "bread"]);
        assert!(l.add("milk\neggs\nbread", Some("u1"), "phone", T).is_empty(), "replay");
        assert!(l.add("  \n", None, "app", T).is_empty());
        assert_eq!(l.add("x", None, "app", T).len(), 1);
    }

    #[test]
    fn ids_resolve_by_unique_prefix() {
        let mut l = List::default();
        l.add("a", Some("abc1"), "app", T);
        l.add("b", Some("abd2"), "app", T);
        assert_eq!(l.resolve("abc").unwrap(), 0);
        assert_eq!(l.resolve("abd2").unwrap(), 1);
        assert!(l.resolve("ab").is_err(), "ambiguous");
        assert!(l.resolve("zz").is_err());
        assert!(l.resolve("").is_err());
    }

    #[test]
    fn done_undo_rm_clear_and_prune() {
        let mut l = List::default();
        l.add("a\nb\nc", Some("u"), "app", T);
        let (r, ch) = l.apply_ids(IdsOp::Done, &["u".into(), "1~u".into()], T + 5).unwrap();
        assert_eq!((r["changed"].clone(), ch), (json!(2), true));
        assert_eq!(l.pending(), 1);
        assert!(!l.apply_ids(IdsOp::Done, &["u".into()], T + 6).unwrap().1, "already done");
        assert!(l.apply_ids(IdsOp::Rm, &["u".into(), "nope".into()], T).is_err());
        assert_eq!(l.items.len(), 3, "a bad id changes nothing");
        l.apply_ids(IdsOp::Undo, &["1~u".into()], T).unwrap();
        assert_eq!(l.pending(), 2);
        let (r, _) = l.apply_op(ListOp::Clear { done_only: true }, T).unwrap();
        assert_eq!(r["removed"], 1);
        l.apply_ids(IdsOp::Done, &["2~u".into()], T).unwrap();
        assert_eq!(l.prune(24, T + 23 * HOUR_MS), 0);
        assert_eq!(l.prune(24, T + 25 * HOUR_MS), 1);
        assert_eq!(l.items.len(), 1);
        let (r, _) = l.apply_op(ListOp::Clear { done_only: false }, T).unwrap();
        assert_eq!((r["removed"].clone(), l.items.len()), (json!(1), 0));
    }

    #[test]
    fn actions_from_the_phone_map_onto_the_list() {
        let mut l = List::default();
        assert!(!l.apply_action(&json!({"action":"nope"}), T));
        assert!(l.apply_action(&json!({"action":"add","uid":"u1","from":"phone","value":{"text":"milk\neggs"}}), T));
        assert_eq!(l.items[0].by, "phone");
        assert!(l.apply_action(&json!({"action":"items","value":{"item":"u1","checked":true}}), T));
        assert!(l.items[0].done);
        assert!(!l.apply_action(&json!({"action":"items","value":{"item":"gone","checked":true}}), T));
        assert!(l.apply_action(&json!({"action":"clear_done"}), T));
        assert_eq!(l.items.len(), 1);
        assert!(l.apply_action(&json!({"action":"rm","value":{"item":"1~u1"}}), T));
        assert!(l.items.is_empty());
    }

    #[test]
    fn the_view_lists_pending_first_and_carries_a_badge() {
        let mut l = List::default();
        let v = l.view();
        assert_eq!((v["badge"].clone(), v["blocks"][0]["type"].clone(), v["blocks"][1]["type"].clone()), (json!(0), json!("compose"), json!("notice")));
        l.add("a", Some("1"), "app", T);
        l.add("b", Some("2"), "app", T + 1);
        l.apply_ids(IdsOp::Done, &["1".into()], T + 2).unwrap();
        let v = l.view();
        assert_eq!(v["badge"], 1);
        let items = &v["blocks"][1]["items"];
        assert_eq!((items[0]["id"].clone(), items[0]["checked"].clone(), items[1]["id"].clone(), items[1]["checked"].clone()), (json!("2"), json!(false), json!("1"), json!(true)));
        assert_eq!(items[0]["actions"][0]["id"], "rm");
        assert_eq!(v["blocks"][2]["items"][0]["id"], "clear_done");
        assert!(v.get("notify").is_none());
    }

    #[test]
    fn state_survives_a_restart_and_a_bad_file_is_set_aside() {
        let d = tempfile::tempdir().unwrap();
        let a = Lists::new(d.path().join("lists"));
        a.with("groceries", 24, |l| Ok(((), !l.add("milk", Some("u"), "app", now_ms()).is_empty()))).unwrap();
        let b = Lists::new(d.path().join("lists"));
        let (n, _) = b.with("groceries", 24, |l| Ok((l.items.len(), false))).unwrap();
        assert_eq!(n, 1);
        std::fs::write(d.path().join("lists/groceries.json"), "{nope").unwrap();
        let c = Lists::new(d.path().join("lists"));
        assert_eq!(c.with("groceries", 24, |l| Ok((l.items.len(), false))).unwrap().0, 0);
        assert!(d.path().join("lists/groceries.bad").exists());
    }
}
