//! App channels (`docs/PLAN.md`, "App channels"). A channel's manifest is
//! `~/.config/jarvis/apps/<name>.toml`: how the phone and the panel show it, and `exec`, the shell
//! command that starts its app. The daemon reads the folder at start and on `channels_reload`, and
//! publishes the list to the phone as the `_channels` view when it changed.
//!
//! Apps start on demand: when an item arrives on a channel that has no client subscribed, the
//! daemon runs its `exec` (`sh -c`), which starts the app in the background; the app then
//! subscribes and takes the waiting items. Also at start, for a channel that already has items
//! waiting.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use jarvis_core::node::{Event, Node};
use tokio::{sync::broadcast::error::RecvError, time::Instant};
use tracing::{info, warn};

use crate::{daemon::Clients, lists::Lists, mark};

/// A starter that hasn't produced a client yet isn't run again before this.
const RELAUNCH_GAP: Duration = Duration::from_secs(30);
/// The daemon's own channel: the manifest list, as a view for the phone.
pub const CHANNELS: &str = "_channels";

/// `~/.config/jarvis/apps` (`$XDG_CONFIG_HOME` respected).
pub fn default_dir() -> Result<PathBuf> {
    Ok(crate::config_dir()?.join(jarvis_core::compat::DIR).join("apps"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Dir {
    Ltr,
    Rtl,
    /// Each text takes its own direction (Hebrew right-to-left, English left-to-right).
    Auto,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// Has an app that publishes a view and takes actions.
    App,
    /// Posts and replies only (`jarvis-link post`).
    Thread,
    /// A list the daemon keeps itself (`lists.rs`, `jarvis-link list`): no app has to run.
    List,
}

/// Where a channel shows. `none` is for channels only an app uses (still fully usable over the socket).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Show {
    Both,
    Phone,
    Laptop,
    None,
}

impl Show {
    pub fn phone(self) -> bool {
        matches!(self, Show::Both | Show::Phone)
    }
    pub fn laptop(self) -> bool {
        matches!(self, Show::Both | Show::Laptop)
    }
}

/// A manifest file as written. Everything is optional.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct Raw {
    title: Option<String>,
    /// A Lucide icon name (`mark.rs`); `glyph` is the fallback when it can't be found.
    icon: Option<String>,
    /// OKLCH hue in degrees: gives `accent`, `on_accent` and `tile` (`docs/DESIGN.md`).
    hue: Option<f64>,
    glyph: Option<String>,
    accent: Option<String>,
    dir: Option<Dir>,
    kind: Option<Kind>,
    exec: Option<String>,
    #[serde(default)]
    share: bool,
    notify: Option<bool>,
    show: Option<Show>,
    /// Old spelling of `show = "phone"` (false); `show` wins.
    laptop: Option<bool>,
    /// A `list` channel drops done items after this many hours.
    keep_done: Option<u32>,
}

/// A channel as the UIs see it (also an entry of `_channels`).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Channel {
    pub name: String,
    pub title: String,
    /// One emoji or letter; drawn when there is no `icon`.
    pub glyph: String,
    /// The white mark drawn on `tile`.
    pub icon: Option<mark::Mark>,
    /// `#RRGGBB`, from `hue` (or the old `accent` key); `None` leaves it to the UI.
    pub accent: Option<String>,
    /// Text and icons on `accent`; only with `hue`.
    pub on_accent: Option<String>,
    /// The icon's background, like an app's launcher tile; only with `hue`.
    pub tile: Option<String>,
    pub dir: Dir,
    pub kind: Kind,
    /// Accepts Android share text into its compose.
    pub share: bool,
    /// The phone may show a view's `notify` as a notification.
    pub notify: bool,
    #[serde(skip)]
    pub exec: Option<String>,
    /// Where it shows. Only phone channels are in `_channels`; the panel filters on `laptop`.
    #[serde(skip)]
    pub show: Show,
    #[serde(skip)]
    pub laptop: bool,
    /// A `list` channel drops done items after this many hours.
    #[serde(skip)]
    pub keep_done: u32,
}

/// Default for `keep_done`: tomorrow's list starts clean.
pub const KEEP_DONE_HOURS: u32 = 24;

impl Channel {
    #[cfg(test)]
    fn phone_laptop(&self) -> (bool, bool) {
        (self.show.phone(), self.laptop)
    }

    /// A channel with items but no manifest.
    pub fn thread(name: &str) -> Self {
        Self::from_raw(name, Raw::default(), None)
    }

    /// `dir` is the manifests' folder, where `icons/` is looked up first.
    fn from_raw(name: &str, r: Raw, dir: Option<&Path>) -> Self {
        let title = r.title.filter(|t| !t.trim().is_empty()).unwrap_or_else(|| name.to_string());
        let glyph = r
            .glyph
            .filter(|g| !g.is_empty())
            .unwrap_or_else(|| title.chars().next().map(|c| c.to_uppercase().collect()).unwrap_or_default());
        let accent = r.accent.filter(|a| {
            let ok = a.len() == 7 && a.starts_with('#') && a[1..].bytes().all(|b| b.is_ascii_hexdigit());
            if !ok {
                warn!("channel {name}: accent {a:?} isn't #rrggbb; ignored");
            }
            ok
        });
        let hue = r.hue.map(|h| mark::colors(h.rem_euclid(360.0)));
        if hue.is_some() && accent.is_some() {
            warn!("channel {name}: both hue and accent are set; accent ignored");
        }
        let (accent, on_accent, tile) = match hue {
            Some(c) => (Some(c.accent), Some(c.on_accent), Some(c.tile)),
            None => (accent, None, None),
        };
        let icon = r.icon.filter(|i| !i.is_empty()).and_then(|i| match dir.and_then(|d| mark::find(d, &i)) {
            Some(path) => mark::load(&path).inspect_err(|e| warn!("channel {name}: icon {i:?}: {e:#}; the glyph is shown")).ok(),
            None => {
                warn!("channel {name}: icon {i:?} isn't in apps/icons or the Lucide set; the glyph is shown");
                None
            }
        });
        let exec = r.exec.filter(|e| !e.trim().is_empty());
        let show = match (r.show, r.laptop) {
            (Some(s), l) => {
                if l.is_some() {
                    warn!("channel {name}: both show and laptop are set; laptop ignored");
                }
                s
            }
            (None, Some(false)) => Show::Phone,
            (None, _) => Show::Both,
        };
        Self {
            name: name.into(),
            title,
            glyph,
            icon,
            accent,
            on_accent,
            tile,
            dir: r.dir.unwrap_or(Dir::Auto),
            kind: r.kind.unwrap_or(if exec.is_some() { Kind::App } else { Kind::Thread }),
            share: r.share,
            notify: r.notify.unwrap_or(true),
            exec,
            show,
            laptop: show.laptop(),
            keep_done: r.keep_done.unwrap_or(KEEP_DONE_HOURS),
        }
    }
}

/// `[a-z0-9_-]+`, not starting with `_` (kept for the daemon's own channels). Also keeps a
/// channel name from pointing outside the folder.
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('_')
        && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}

/// Fails when `text` isn't a manifest (a typo, a bad value), the way `load` would skip it.
pub fn check_manifest(text: &str) -> Result<()> {
    toml::from_str::<Raw>(text).map(drop).context("not a valid channel manifest")
}

/// The manifests in `dir`, by name. A bad one is logged and skipped.
pub fn load(dir: &Path) -> Vec<Channel> {
    let mut list = Vec::new();
    let Ok(entries) = std::fs::read_dir(dir) else { return list };
    for e in entries.flatten() {
        let path = e.path();
        if path.extension().is_none_or(|x| x != "toml") {
            continue;
        }
        let Some(name) = path.file_stem().and_then(|s| s.to_str()) else { continue };
        if !valid_name(name) {
            warn!("{}: a channel name is [a-z0-9_-]+, not starting with _; skipped", path.display());
            continue;
        }
        let raw = std::fs::read_to_string(&path)
            .context("read")
            .and_then(|s| toml::from_str::<Raw>(&s).context("parse"));
        match raw {
            Ok(r) => list.push(Channel::from_raw(name, r, Some(dir))),
            Err(e) => warn!("{}: {e:#}; skipped", path.display()),
        }
    }
    list.sort_by(|a, b| a.name.cmp(&b.name));
    list
}

/// The manifests the daemon knows, shared by the socket and the starter.
pub struct Apps {
    dir: PathBuf,
    list: Mutex<Vec<Channel>>,
    lists: Lists,
}

impl Apps {
    /// `lists_dir` holds the saved state of the built-in `list` channels.
    pub fn new(dir: PathBuf, lists_dir: PathBuf) -> Arc<Self> {
        let list = Mutex::new(load(&dir));
        Arc::new(Self { dir, list, lists: Lists::new(lists_dir) })
    }

    pub fn lists(&self) -> &Lists {
        &self.lists
    }

    /// The built-in `list` channels take what waits on them and publish their view if it differs.
    pub fn sync_lists(&self, node: &Node) {
        for c in self.list().into_iter().filter(|c| c.kind == Kind::List) {
            if let Err(e) = self.lists.catch_up(node, &c.name, c.keep_done) {
                warn!("list {}: {e:#}", c.name);
            }
        }
    }

    pub fn list(&self) -> Vec<Channel> {
        self.list.lock().unwrap().clone()
    }

    pub fn get(&self, name: &str) -> Option<Channel> {
        self.list.lock().unwrap().iter().find(|c| c.name == name).cloned()
    }

    /// Reads the folder again and publishes the list if it changed.
    pub fn reload(&self, node: &Node) -> Result<()> {
        let list = load(&self.dir);
        let lists: Vec<String> = list.iter().filter(|c| c.kind == Kind::List).map(|c| c.name.clone()).collect();
        self.lists.retain(&lists);
        *self.list.lock().unwrap() = list;
        self.publish(node)?;
        self.sync_lists(node);
        Ok(())
    }

    /// Queues the list as the `_channels` view, unless the newest one already says the same.
    /// True when it was queued.
    pub fn publish(&self, node: &Node) -> Result<bool> {
        let list: Vec<Channel> = self.list().into_iter().filter(|c| c.show.phone()).collect();
        let data = json!({ "v": 1, "channels": list }).to_string();
        if node.app_view(CHANNELS)?.as_deref() == Some(data.as_str()) {
            return Ok(false);
        }
        info!("publishing the channel list ({} channels)", list.len());
        node.send_app(CHANNELS, data, true)?;
        Ok(true)
    }
}

/// `~` at the start of a word becomes `home`, so `exec = "~/bin/app"` works even quoted.
fn expand_tilde(cmd: &str, home: &str) -> String {
    let mut out = String::with_capacity(cmd.len());
    let mut prev: Option<char> = None;
    let mut chars = cmd.chars().peekable();
    while let Some(c) = chars.next() {
        let word_start = prev.is_none_or(|p| p.is_whitespace() || p == '"' || p == '\'' || p == '=');
        let ends = chars.peek().is_none_or(|n| *n == '/' || n.is_whitespace() || *n == '"' || *n == '\'');
        if c == '~' && word_start && ends {
            out.push_str(home);
        } else {
            out.push(c);
        }
        prev = Some(c);
    }
    out
}

pub async fn run(node: Node, clients: Clients, apps: Arc<Apps>) {
    let mut rx = node.events();
    let home = crate::home().map(|h| h.to_string_lossy().into_owned()).unwrap_or_default();
    let mut last: HashMap<String, Instant> = HashMap::new();
    let mut launch = |channel: &str| {
        if clients.lock().unwrap().contains_key(channel) {
            return;
        }
        let Some(exec) = apps.get(channel).and_then(|c| c.exec) else { return };
        if last.get(channel).is_some_and(|t| t.elapsed() < RELAUNCH_GAP) {
            return;
        }
        last.insert(channel.to_string(), Instant::now());
        let cmd = expand_tilde(&exec, &home);
        info!("starting the {channel} app ({cmd})");
        let spawned = tokio::process::Command::new("sh")
            .arg("-c")
            .arg(&cmd)
            .stdin(std::process::Stdio::null())
            .kill_on_drop(false)
            .spawn();
        match spawned {
            Ok(mut child) => {
                tokio::spawn(async move {
                    let _ = child.wait().await;
                });
            }
            Err(e) => warn!("starting {cmd}: {e}"),
        }
    };
    apps.sync_lists(&node);
    let mut tick = tokio::time::interval(Duration::from_secs(3600));
    tick.tick().await;
    for c in apps.list().into_iter().filter(|c| c.kind != Kind::List) {
        if node.app_pending(&c.name).is_ok_and(|p| !p.is_empty()) {
            launch(&c.name);
        }
    }
    loop {
        tokio::select! {
            // Done items age out of a list even when nothing touches it.
            _ = tick.tick() => apps.sync_lists(&node),
            ev = rx.recv() => match ev {
                Ok(Event::App { id: Some(id), channel, data, from_me: false, view: false }) => {
                    match apps.get(&channel) {
                        Some(c) if c.kind == Kind::List => {
                            if let Err(e) = apps.lists().on_item(&node, &channel, c.keep_done, id, &data) {
                                warn!("list {channel}: {e:#}");
                            }
                        }
                        _ => launch(&channel),
                    }
                }
                Ok(_) | Err(RecvError::Lagged(_)) => {}
                Err(RecvError::Closed) => return,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manifests_load_with_defaults_and_bad_ones_are_skipped() {
        let d = tempfile::tempdir().unwrap();
        let w = |name: &str, s: &str| std::fs::write(d.path().join(name), s).unwrap();
        w(
            "demo.toml",
            r##"title = "Demo"
glyph = "🏠"
accent = "#d79921"
dir = "rtl"
exec = "~/bin/demo-app --serve"
share = true
"##,
        );
        w("notes.toml", "accent = \"orange\"\n");
        w("typo.toml", "titel = \"x\"\n");
        w("Bad.toml", "");
        w("_channels.toml", "");
        w("readme.md", "");
        let list = load(d.path());
        assert_eq!(list.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(), ["demo", "notes"]);
        let demo = &list[0];
        assert_eq!((demo.kind, demo.dir, demo.share, demo.notify), (Kind::App, Dir::Rtl, true, true));
        assert_eq!(demo.accent.as_deref(), Some("#d79921"));
        let j = serde_json::to_value(demo).unwrap();
        assert_eq!(j["dir"], "rtl");
        assert!(j.get("exec").is_none(), "the phone doesn't need it");
        let notes = &list[1];
        assert_eq!((notes.title.as_str(), notes.glyph.as_str()), ("notes", "N"));
        assert_eq!((notes.accent.clone(), notes.kind, notes.dir), (None, Kind::Thread, Dir::Auto));
        assert_eq!((notes.show, notes.keep_done), (Show::Both, KEEP_DONE_HOURS));
    }

    #[test]
    fn hue_gives_the_colours_and_icon_the_mark() {
        let d = tempfile::tempdir().unwrap();
        std::fs::create_dir(d.path().join("icons")).unwrap();
        std::fs::write(
            d.path().join("icons/cart.svg"),
            r#"<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="8" cy="21" r="1"/></svg>"#,
        )
        .unwrap();
        std::fs::write(d.path().join("shop.toml"), "icon = \"cart\"\nhue = 250\naccent = \"#d79921\"\n").unwrap();
        std::fs::write(d.path().join("lost.toml"), "icon = \"no-such-icon\"\nglyph = \"L\"\n").unwrap();
        let list = load(d.path());
        let (lost, shop) = (&list[0], &list[1]);
        assert_eq!(shop.accent.as_deref(), Some("#95C9FF"), "hue wins over accent");
        assert_eq!((shop.on_accent.as_deref(), shop.tile.as_deref()), (Some("#071727"), Some("#0E3F6A")));
        let j = serde_json::to_value(shop).unwrap();
        assert_eq!(j["icon"]["view"], serde_json::json!([0.0, 0.0, 24.0, 24.0]));
        assert_eq!(j["icon"]["paths"][0]["stroke"], 2.0);
        assert!((lost.icon.is_none() && lost.tile.is_none()) && lost.glyph == "L", "a missing icon falls back to the glyph");
    }

    #[test]
    fn show_decides_where_a_channel_appears_and_laptop_false_still_works() {
        let d = tempfile::tempdir().unwrap();
        let w = |name: &str, s: &str| std::fs::write(d.path().join(name), s).unwrap();
        w("old.toml", "laptop = false\n");
        w("both.toml", "kind = \"list\"\ndir = \"auto\"\nkeep_done = 6\n");
        w("hidden.toml", "show = \"none\"\n");
        w("lap.toml", "show = \"laptop\"\n");
        w("clash.toml", "show = \"laptop\"\nlaptop = false\n");
        let list = load(d.path());
        let by = |n: &str| list.iter().find(|c| c.name == n).unwrap();
        assert_eq!((by("old").show, by("old").phone_laptop()), (Show::Phone, (true, false)));
        assert_eq!((by("both").kind, by("both").keep_done, by("both").phone_laptop()), (Kind::List, 6, (true, true)));
        assert_eq!(by("hidden").phone_laptop(), (false, false));
        assert_eq!(by("lap").phone_laptop(), (false, true));
        assert_eq!(by("clash").show, Show::Laptop, "show wins");
        let j = serde_json::to_value(by("both")).unwrap();
        assert_eq!((j["kind"].as_str(), j["dir"].as_str()), (Some("list"), Some("auto")));
        assert!(j.get("show").is_none() && j.get("keep_done").is_none());
    }

    #[test]
    fn tilde_expands_at_word_starts_only() {
        let h = "/home/u";
        assert_eq!(expand_tilde("~/bin/app --x", h), "/home/u/bin/app --x");
        assert_eq!(expand_tilde("app ~/a \"~/b c\" --dir=~/d", h), "app /home/u/a \"/home/u/b c\" --dir=/home/u/d");
        assert_eq!(expand_tilde("cd ~ && a~b ~user", h), "cd /home/u && a~b ~user");
    }
}
