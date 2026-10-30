//! `jarvis-link channel add|set|rm`: the supported way to create and change a channel manifest
//! (`~/.config/jarvis/apps/<name>.toml`). It edits the file; the CLI then asks the daemon to reload.
//! Every result is checked by parsing it as a manifest, so a typo or a bad value is refused here
//! instead of the daemon logging and skipping the file later.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail, ensure};
use toml_edit::{DocumentMut, value};

use crate::{apps, mark};

/// What `channel add` was given; everything is optional.
#[derive(Debug, Default)]
pub struct New {
    pub title: Option<String>,
    /// A Lucide name, or a path to your own 24-grid `.svg` (copied in as `icons/<stem>.svg`).
    pub icon: Option<String>,
    pub hue: Option<f64>,
    pub glyph: Option<String>,
    pub accent: Option<String>,
    pub dir: Option<String>,
    pub kind: Option<String>,
    pub exec: Option<String>,
    pub show: Option<String>,
    pub share: bool,
    pub no_notify: bool,
    pub keep_done: Option<u32>,
}

/// Manifest keys, with how `set` types their values.
const STRINGS: [&str; 7] = ["title", "glyph", "accent", "dir", "kind", "exec", "show"];
const BOOLS: [&str; 3] = ["share", "notify", "laptop"];
const NUMBERS: [&str; 1] = ["keep_done"];
/// `icon` (copied into `icons/`, see [icon]) and `hue` (degrees) are handled on their own.
const LOOK: [&str; 2] = ["icon", "hue"];

/// Copies the icon's SVG into `<dir>/icons/<name>.svg`, so the daemon needs nothing outside the
/// apps folder, and returns the name the manifest stores. `icon` is a Lucide name or an `.svg` path.
fn icon(dir: &Path, icon: &str) -> Result<String> {
    let (name, src) = if icon.ends_with(".svg") {
        let src = PathBuf::from(icon);
        let stem = src.file_stem().and_then(|s| s.to_str()).unwrap_or("").to_lowercase();
        ensure!(mark::valid_icon(&stem), "name the file [a-z0-9-]+.svg: {icon}");
        (stem, src)
    } else {
        ensure!(mark::valid_icon(icon), "bad icon name {icon:?} (Lucide names are [a-z0-9-]+, see https://lucide.dev/icons)");
        let src = mark::find(dir, icon).with_context(|| {
            let lucide = mark::lucide_dir().map(|d| d.display().to_string()).unwrap_or_default();
            format!("no icon {icon:?} in {}/icons or {lucide}", dir.display())
        })?;
        (icon.to_string(), src)
    };
    let text = std::fs::read_to_string(&src).with_context(|| format!("read {}", src.display()))?;
    mark::parse(&text).with_context(|| format!("{} can't be a channel icon", src.display()))?;
    let dest = dir.join("icons").join(format!("{name}.svg"));
    if dest != src {
        std::fs::create_dir_all(dest.parent().context("no folder")?).context("create the icons folder")?;
        std::fs::write(&dest, text).with_context(|| format!("write {}", dest.display()))?;
    }
    Ok(name)
}

fn hue(v: &str) -> Result<f64> {
    let h: f64 = v.parse().with_context(|| format!("hue is degrees, 0 to 360: {v:?}"))?;
    ensure!((0.0..=360.0).contains(&h), "hue is degrees, 0 to 360: {v:?}");
    Ok(h)
}

fn path(dir: &Path, name: &str) -> Result<PathBuf> {
    ensure!(apps::valid_name(name), "bad channel name {name:?} (use [a-z0-9_-]+, not starting with _)");
    Ok(dir.join(format!("{name}.toml")))
}

/// Writes `doc` after checking that it parses as a manifest.
fn write(path: &Path, doc: &DocumentMut) -> Result<()> {
    let text = doc.to_string();
    apps::check_manifest(&text)?;
    std::fs::create_dir_all(path.parent().context("no folder")?).context("create the apps folder")?;
    let tmp = path.with_extension("toml.tmp");
    std::fs::write(&tmp, text).context("write the manifest")?;
    std::fs::rename(&tmp, path).context("replace the manifest")
}

pub fn add(dir: &Path, name: &str, new: &New, force: bool) -> Result<PathBuf> {
    let path = path(dir, name)?;
    ensure!(force || !path.exists(), "{name} already exists (`jarvis-link channel set {name} …` changes it, --force replaces it)");
    let mut doc = DocumentMut::new();
    let strs = [
        ("title", &new.title),
        ("glyph", &new.glyph),
        ("accent", &new.accent),
        ("dir", &new.dir),
        ("kind", &new.kind),
        ("exec", &new.exec),
        ("show", &new.show),
    ];
    for (k, v) in strs {
        if let Some(v) = v {
            doc[k] = value(v.as_str());
        }
    }
    if let Some(i) = &new.icon {
        doc["icon"] = value(icon(dir, i)?);
    }
    if let Some(h) = new.hue {
        doc["hue"] = value(hue(&h.to_string())?);
    }
    if new.share {
        doc["share"] = value(true);
    }
    if new.no_notify {
        doc["notify"] = value(false);
    }
    if let Some(h) = new.keep_done {
        doc["keep_done"] = value(i64::from(h));
    }
    write(&path, &doc)?;
    Ok(path)
}

/// `key=value` pairs onto an existing manifest, keeping its comments. An empty value removes the key.
pub fn set(dir: &Path, name: &str, pairs: &[String]) -> Result<PathBuf> {
    let path = path(dir, name)?;
    let text = std::fs::read_to_string(&path).with_context(|| format!("{name} has no manifest ({})", path.display()))?;
    let mut doc: DocumentMut = text.parse().with_context(|| format!("{} isn't valid TOML", path.display()))?;
    for pair in pairs {
        let (k, v) = pair.split_once('=').with_context(|| format!("{pair:?}: use key=value"))?;
        let k = k.trim();
        if v.is_empty() {
            doc.remove(k);
        } else if k == "icon" {
            doc[k] = value(icon(dir, v)?);
        } else if k == "hue" {
            doc[k] = value(hue(v)?);
        } else if STRINGS.contains(&k) {
            doc[k] = value(v);
        } else if BOOLS.contains(&k) {
            doc[k] = value(v.parse::<bool>().with_context(|| format!("{k} is true or false"))?);
        } else if NUMBERS.contains(&k) {
            doc[k] = value(v.parse::<i64>().with_context(|| format!("{k} is a whole number"))?);
        } else {
            bail!("unknown key {k:?} (keys: {})", [LOOK.as_slice(), &STRINGS, &BOOLS, &NUMBERS].concat().join(", "));
        }
    }
    write(&path, &doc)?;
    Ok(path)
}

/// Deletes the manifest. True when there was one.
pub fn rm(dir: &Path, name: &str) -> Result<bool> {
    let path = path(dir, name)?;
    match std::fs::remove_file(&path) {
        Ok(()) => Ok(true),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e).with_context(|| format!("remove {}", path.display())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_writes_a_manifest_the_daemon_loads() {
        let d = tempfile::tempdir().unwrap();
        let new = New { title: Some("Groceries".into()), glyph: Some("🛒".into()), kind: Some("list".into()), keep_done: Some(12), ..Default::default() };
        let p = add(d.path(), "groceries", &new, false).unwrap();
        assert_eq!(p, d.path().join("groceries.toml"));
        let list = apps::load(d.path());
        assert_eq!(list.len(), 1);
        let c = &list[0];
        assert_eq!((c.name.as_str(), c.title.as_str(), c.glyph.as_str(), c.kind, c.keep_done), ("groceries", "Groceries", "🛒", apps::Kind::List, 12));
        assert!(add(d.path(), "groceries", &new, false).is_err(), "no silent overwrite");
        assert!(add(d.path(), "groceries", &New::default(), true).is_ok());
    }

    #[test]
    fn bad_names_and_values_are_refused_and_write_nothing() {
        let d = tempfile::tempdir().unwrap();
        assert!(add(d.path(), "Bad Name", &New::default(), false).is_err());
        assert!(add(d.path(), "_hidden", &New::default(), false).is_err());
        assert!(add(d.path(), "../x", &New::default(), false).is_err());
        assert!(add(d.path(), "ok", &New { dir: Some("sideways".into()), ..Default::default() }, false).is_err());
        assert!(add(d.path(), "ok", &New { show: Some("nowhere".into()), ..Default::default() }, false).is_err());
        assert!(!d.path().join("ok.toml").exists());
        assert_eq!(std::fs::read_dir(d.path()).unwrap().count(), 0);
    }

    #[test]
    fn set_edits_keys_and_keeps_comments() {
        let d = tempfile::tempdir().unwrap();
        std::fs::write(d.path().join("t.toml"), "# my channel\ntitle = \"T\"\nlaptop = false # phone only\n").unwrap();
        set(d.path(), "t", &["show=both".into(), "keep_done=6".into(), "notify=false".into(), "laptop=".into()]).unwrap();
        let text = std::fs::read_to_string(d.path().join("t.toml")).unwrap();
        assert!(text.starts_with("# my channel\n"), "{text}");
        assert!(!text.contains("laptop"), "{text}");
        let c = &apps::load(d.path())[0];
        assert_eq!((c.show, c.keep_done, c.notify), (apps::Show::Both, 6, false));
        assert!(set(d.path(), "t", &["bogus=1".into()]).is_err());
        assert!(set(d.path(), "t", &["dir=up".into()]).is_err(), "validated");
        assert!(set(d.path(), "t", &["share=maybe".into()]).is_err());
        assert!(set(d.path(), "nope", &["title=x".into()]).is_err());
        let after = std::fs::read_to_string(d.path().join("t.toml")).unwrap();
        assert_eq!(after, text, "a refused set changes nothing");
    }

    #[test]
    fn rm_deletes_only_the_manifest() {
        let d = tempfile::tempdir().unwrap();
        add(d.path(), "a", &New::default(), false).unwrap();
        assert!(rm(d.path(), "a").unwrap());
        assert!(!rm(d.path(), "a").unwrap());
        assert!(rm(d.path(), "../etc").is_err());
    }
}
