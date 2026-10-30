//! `jarvis-link view --check`: does a channel view say what the phone and the panel draw? The contract
//! is docs/PLAN.md "Data" and "Blocks"; the renderers skip what they don't know without a word,
//! so an app that gets a field wrong sees nothing break, only a missing button. This names it.
//! Problems are plain sentences with a JSON path; an empty list means the view is fine.

use serde_json::Value;

const TYPES: [&str; 11] = ["header", "notice", "text", "list", "thread", "checklist", "compose", "form", "progress", "buttons", "web"];
const TONES: [&str; 4] = ["info", "ok", "warn", "error"];
const STYLES: [&str; 3] = ["primary", "danger", "plain"];

/// Every problem in `v`, as "<path>: <what>".
pub fn check(v: &Value) -> Vec<String> {
    let mut p = Problems(Vec::new());
    let Some(o) = v.as_object() else {
        return vec!["the view isn't a JSON object".into()];
    };
    match o.get("v") {
        Some(x) if x.as_i64() == Some(1) => {}
        Some(_) => p.add("v", "must be 1"),
        None => p.add("v", "missing (1)"),
    }
    if let Some(b) = o.get("badge")
        && b.as_u64().is_none()
    {
        p.add("badge", "must be a whole number, 0 or more");
    }
    if let Some(n) = o.get("notify") {
        match n.as_object() {
            Some(n) => {
                p.string(n.get("text"), "notify.text", true);
                p.string(n.get("title"), "notify.title", false);
            }
            None => p.add("notify", "must be an object {title?, text}"),
        }
    }
    if let Some(t) = o.get("open_tags") {
        p.strings(Some(t), "open_tags");
    }
    // A status the phone keeps showing as an ongoing notification while the view has it (0.3.9).
    if let Some(n) = o.get("pin") {
        match n.as_object() {
            Some(n) => {
                p.string(n.get("title"), "pin.title", true);
                p.string(n.get("text"), "pin.text", false);
                p.string(n.get("tag"), "pin.tag", false);
                p.actions(n.get("actions"), "pin.actions");
            }
            None => p.add("pin", "must be an object {title, text?, tag?, actions?}"),
        }
    }
    let Some(blocks) = o.get("blocks").and_then(Value::as_array) else {
        p.add("blocks", "missing (a list of blocks)");
        return p.0;
    };
    let mut ids: Vec<&str> = Vec::new();
    for (i, b) in blocks.iter().enumerate() {
        let at = format!("blocks[{i}]");
        let Some(b) = b.as_object() else {
            p.add(&at, "isn't an object");
            continue;
        };
        if let Some(id) = b.get("id").and_then(Value::as_str) {
            if ids.contains(&id) {
                p.add(&format!("{at}.id"), &format!("\"{id}\" is used by another block (live patches and actions go by id)"));
            }
            ids.push(id);
        }
        let ty = b.get("type").and_then(Value::as_str).unwrap_or("");
        let at = format!("{at} ({ty})");
        match ty {
            "" => p.add(&at, "has no \"type\""),
            t if !TYPES.contains(&t) => p.add(&at, "is an unknown type: the phone and the panel skip it"),
            "header" => {
                p.string(b.get("title"), &format!("{at}.title"), true);
                p.string(b.get("subtitle"), &format!("{at}.subtitle"), false);
            }
            "notice" => {
                p.string(b.get("text"), &format!("{at}.text"), true);
                match b.get("tone").and_then(Value::as_str) {
                    Some(t) if TONES.contains(&t) => {}
                    Some(t) => p.add(&format!("{at}.tone"), &format!("\"{t}\" isn't info, ok, warn or error")),
                    None => {}
                }
            }
            "text" => {
                p.string(b.get("text"), &format!("{at}.text"), true);
                p.boolean(b.get("mono"), &format!("{at}.mono"));
            }
            "list" => {
                p.string(b.get("empty"), &format!("{at}.empty"), false);
                p.items(b.get("items"), &at, |p, it, at| {
                    p.string(it.get("text"), &format!("{at}.text"), true);
                    p.string(it.get("title"), &format!("{at}.title"), false);
                    p.string(it.get("meta"), &format!("{at}.meta"), false);
                    p.string(it.get("details"), &format!("{at}.details"), false);
                    if let Some(c) = it.get("chips") {
                        p.strings(Some(c), &format!("{at}.chips"));
                    }
                    p.item_controls(it, at);
                });
            }
            "thread" => {
                p.string(b.get("empty"), &format!("{at}.empty"), false);
                p.string(b.get("status"), &format!("{at}.status"), false);
                p.items(b.get("items"), &at, |p, it, at| {
                    p.string(it.get("who"), &format!("{at}.who"), true);
                    p.string(it.get("text"), &format!("{at}.text"), true);
                    match it.get("ts") {
                        Some(t) if t.as_u64().is_some() => {}
                        Some(_) => p.add(&format!("{at}.ts"), "must be a whole number (unix seconds)"),
                        None => p.add(&format!("{at}.ts"), "missing (unix seconds)"),
                    }
                    p.item_controls(it, at);
                });
            }
            "checklist" => {
                p.string(b.get("id"), &format!("{at}.id"), true);
                p.items(b.get("items"), &at, |p, it, at| {
                    p.string(it.get("label"), &format!("{at}.label"), true);
                    match it.get("checked") {
                        Some(Value::Bool(_)) => {}
                        Some(_) => p.add(&format!("{at}.checked"), "must be true or false"),
                        None => p.add(&format!("{at}.checked"), "missing (true or false)"),
                    }
                    p.actions(it.get("actions"), &format!("{at}.actions"));
                });
            }
            "compose" => {
                p.string(b.get("id"), &format!("{at}.id"), true);
                p.string(b.get("placeholder"), &format!("{at}.placeholder"), false);
                p.string(b.get("submit"), &format!("{at}.submit"), false);
                p.boolean(b.get("multi"), &format!("{at}.multi"));
                if b.get("chips").is_some() {
                    p.actions(b.get("chips"), &format!("{at}.chips"));
                }
            }
            "form" => {
                p.string(b.get("id"), &format!("{at}.id"), true);
                p.string(b.get("submit"), &format!("{at}.submit"), false);
                p.items(b.get("fields"), &format!("{at}.fields"), |p, f, at| {
                    p.string(f.get("label"), &format!("{at}.label"), true);
                    p.string(f.get("placeholder"), &format!("{at}.placeholder"), false);
                    p.string(f.get("value"), &format!("{at}.value"), false);
                    p.boolean(f.get("multi"), &format!("{at}.multi"));
                });
            }
            "progress" => {
                p.string(b.get("id"), &format!("{at}.id"), true);
                p.string(b.get("text"), &format!("{at}.text"), true);
                if let Some(c) = b.get("cancel") {
                    match c.as_object() {
                        Some(c) => {
                            p.string(c.get("id"), &format!("{at}.cancel.id"), true);
                            p.string(c.get("label"), &format!("{at}.cancel.label"), true);
                        }
                        None => p.add(&format!("{at}.cancel"), "must be an object {id, label}"),
                    }
                }
            }
            "buttons" => {
                p.items(b.get("items"), &at, |p, it, at| {
                    p.string(it.get("label"), &format!("{at}.label"), true);
                    p.style(it.get("style"), &format!("{at}.style"));
                });
            }
            _ => p.add(&at, "is reserved (not drawn yet)"),
        }
    }
    p.0
}

struct Problems(Vec<String>);

impl Problems {
    fn add(&mut self, at: &str, what: &str) {
        self.0.push(format!("{at}: {what}"));
    }

    fn string(&mut self, v: Option<&Value>, at: &str, required: bool) {
        match v {
            Some(Value::String(_)) => {}
            Some(_) => self.add(at, "must be a string"),
            None if required => self.add(at, "missing"),
            None => {}
        }
    }

    fn boolean(&mut self, v: Option<&Value>, at: &str) {
        if v.is_some_and(|v| !v.is_boolean()) {
            self.add(at, "must be true or false");
        }
    }

    fn strings(&mut self, v: Option<&Value>, at: &str) {
        match v.and_then(Value::as_array) {
            Some(a) if a.iter().all(Value::is_string) => {}
            _ => self.add(at, "must be a list of strings"),
        }
    }

    /// `{id, label, style?, confirm?}` objects (actions, chips).
    fn actions(&mut self, v: Option<&Value>, at: &str) {
        if v.is_none() {
            return;
        }
        self.items(v, at, |p, a, at| {
            p.string(a.get("label"), &format!("{at}.label"), true);
            p.style(a.get("style"), &format!("{at}.style"));
            if a.get("confirm").is_some_and(|c| !c.is_boolean() && !c.is_string()) {
                p.add(&format!("{at}.confirm"), "must be true, false or the second press's label");
            }
        });
    }

    fn style(&mut self, v: Option<&Value>, at: &str) {
        match v.and_then(Value::as_str) {
            Some(s) if STYLES.contains(&s) => {}
            Some(s) => self.add(at, &format!("\"{s}\" isn't primary, danger or plain")),
            None if v.is_some() => self.add(at, "must be a string"),
            None => {}
        }
    }

    /// A list or thread item's `actions`, `reply` and `dismiss`.
    fn item_controls(&mut self, it: &serde_json::Map<String, Value>, at: &str) {
        self.actions(it.get("actions"), &format!("{at}.actions"));
        if let Some(r) = it.get("reply") {
            match r.as_object() {
                Some(r) => {
                    self.string(r.get("id"), &format!("{at}.reply.id"), true);
                    self.string(r.get("placeholder"), &format!("{at}.reply.placeholder"), false);
                    self.string(r.get("submit"), &format!("{at}.reply.submit"), false);
                }
                None => self.add(&format!("{at}.reply"), "must be an object {id, placeholder?, submit?}"),
            }
        }
        if let Some(d) = it.get("dismiss") {
            match d.as_object() {
                Some(d) => self.string(d.get("id"), &format!("{at}.dismiss.id"), true),
                None => self.add(&format!("{at}.dismiss"), "must be an object {id}"),
            }
        }
    }

    /// A list of objects, each with a string `id` unique in the list, checked by `each`.
    fn items(&mut self, v: Option<&Value>, at: &str, each: impl Fn(&mut Problems, &serde_json::Map<String, Value>, &str)) {
        let at = if at.ends_with(')') { format!("{at}.items") } else { at.to_string() };
        let Some(items) = v.and_then(Value::as_array) else {
            self.add(&at, "missing (a list)");
            return;
        };
        let mut ids: Vec<&str> = Vec::new();
        for (i, it) in items.iter().enumerate() {
            let here = format!("{at}[{i}]");
            let Some(it) = it.as_object() else {
                self.add(&here, "isn't an object");
                continue;
            };
            match it.get("id").and_then(Value::as_str) {
                Some(id) if ids.contains(&id) => self.add(&format!("{here}.id"), &format!("\"{id}\" appears twice")),
                Some(id) => ids.push(id),
                None => self.add(&format!("{here}.id"), "missing (a string)"),
            }
            each(self, it, &here);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn the_fixture_views_pass() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/views");
        let mut n = 0;
        for e in std::fs::read_dir(&dir).unwrap() {
            let path = e.unwrap().path();
            if path.extension().is_some_and(|x| x == "json") {
                let v: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
                assert_eq!(check(&v), Vec::<String>::new(), "{}", path.display());
                n += 1;
            }
        }
        assert!(n >= 2, "fixtures in {}", dir.display());
    }

    #[test]
    fn the_list_views_jarvis_makes_pass() {
        let mut l = crate::lists::List::default();
        assert_eq!(check(&l.view()), Vec::<String>::new(), "empty");
        l.apply_op(crate::lists::ListOp::Add { text: "milk\nbread".into() }, 1).unwrap();
        let first = l.items[0].id.clone();
        l.apply_op(crate::lists::ListOp::Done { ids: vec![first] }, 2).unwrap();
        assert_eq!(check(&l.view()), Vec::<String>::new(), "with a done item");
    }

    #[test]
    fn a_pin_is_checked() {
        let ok = json!({"v": 1, "blocks": [], "pin": {"tag": "lend", "title": "dibs has your phone", "text": "Until 15:40",
            "actions": [{"id": "phone-back", "label": "Take it back"}]}});
        assert_eq!(check(&ok), Vec::<String>::new());
        let bad = json!({"v": 1, "blocks": [], "pin": {"text": 3, "actions": [{"id": "x"}]}});
        assert_eq!(check(&bad), ["pin.title: missing", "pin.text: must be a string", "pin.actions[0].label: missing"]);
        assert_eq!(check(&json!({"v": 1, "blocks": [], "pin": "x"})), ["pin: must be an object {title, text?, tag?, actions?}"]);
    }

    #[test]
    fn problems_are_named_with_their_path() {
        let v = json!({"v": 2, "badge": -1, "open_tags": [1], "blocks": [
            {"type": "notice", "text": "x", "tone": "loud"},
            {"type": "list", "items": [{"id": "a", "text": "t", "actions": [{"id": "y"}], "reply": {}, "dismiss": {}}, {"id": "a", "text": 3, "dismiss": "x"}]},
            {"type": "carousel"},
            {"type": "checklist", "id": "c", "items": [{"id": "i", "label": "l", "checked": "yes"}]},
            {"id": "c", "type": "buttons", "items": [{"id": "b", "label": "B", "style": "big"}]},
        ]});
        let p = check(&v);
        let want = [
            "v: must be 1",
            "badge: must be a whole number, 0 or more",
            "open_tags: must be a list of strings",
            "blocks[0] (notice).tone: \"loud\" isn't info, ok, warn or error",
            "blocks[1] (list).items[0].actions[0].label: missing",
            "blocks[1] (list).items[0].reply.id: missing",
            "blocks[1] (list).items[0].dismiss.id: missing",
            "blocks[1] (list).items[1].id: \"a\" appears twice",
            "blocks[1] (list).items[1].text: must be a string",
            "blocks[1] (list).items[1].dismiss: must be an object {id}",
            "blocks[2] (carousel): is an unknown type: the phone and the panel skip it",
            "blocks[3] (checklist).items[0].checked: must be true or false",
            "blocks[4].id: \"c\" is used by another block (live patches and actions go by id)",
            "blocks[4] (buttons).items[0].style: \"big\" isn't primary, danger or plain",
        ];
        assert_eq!(p, want);
        let t = json!({"v": 1, "blocks": [{"type": "thread", "status": 1, "items": [
            {"id": "u1", "who": "user", "text": "hi", "ts": 1759640400},
            {"id": "s2", "text": "hello", "ts": "now", "actions": [{"id": "y", "label": "Yes", "style": "loud", "confirm": 2}]},
            {"id": "s3", "who": "dibs", "text": "x", "reply": {"placeholder": "Answer…"}, "dismiss": {}},
        ]}]});
        assert_eq!(check(&t), [
            "blocks[0] (thread).status: must be a string",
            "blocks[0] (thread).items[1].who: missing",
            "blocks[0] (thread).items[1].ts: must be a whole number (unix seconds)",
            "blocks[0] (thread).items[1].actions[0].style: \"loud\" isn't primary, danger or plain",
            "blocks[0] (thread).items[1].actions[0].confirm: must be true, false or the second press's label",
            "blocks[0] (thread).items[2].ts: missing (unix seconds)",
            "blocks[0] (thread).items[2].reply.id: missing",
            "blocks[0] (thread).items[2].dismiss.id: missing",
        ]);
        assert_eq!(check(&json!([])), ["the view isn't a JSON object"]);
        assert_eq!(check(&json!({"v": 1})), ["blocks: missing (a list of blocks)"]);
    }
}
