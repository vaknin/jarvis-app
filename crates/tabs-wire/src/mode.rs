//! A tab's mode: how much model it runs on. Three presets, the ones alt+e goes round, and beside them
//! every model at every effort (`PICKS`), named `MODEL-EFFORT` ("sonnet-high"), for when Aviv picks one
//! himself: on the phone's picker, or out loud on a call ("switch to Haiku", "Opus low"; Aviv,
//! 2026-11-02: "not only to Sonnet but to every model and every effort level if I tell you to do it").
//! This table is the one place that says which is which; the server starts a session from it and the
//! apps show it (`label`).
//!
//! Picked from the tier tests of thread #195 (notes/job-tiers.md, "Second test" and "Third test"):
//! - **medium** is Opus 5.5 at medium effort, no advisor, the same as a job's medium (#195 post 31: the
//!   winner of the third test takes both). It beat Sonnet high alone 8.3 to 5.6 on ten job diffs, 9 done
//!   whole against 1, at about twice the dollars. No test has measured it on tab replies.
//! - **fast** is Haiku 5.5 at high effort, no advisor (USAGE, 1 Nov, with Aviv: usage first). In the
//!   #195 lookup test Haiku high tied Sonnet low on web (7.8 vs 7.4) and code (7.7 vs 8.1) at about a
//!   third of the price. Haiku max stays off every route (by far the slowest arm).
//! - **smart** is Opus 5.5 at high effort, no advisor: kept for hard work by judgment.
//!
//! A new tab opens on medium; there is no separate default.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mode {
    pub name: &'static str,
    pub what: &'static str,
    pub model: Option<&'static str>,
    pub effort: Option<&'static str>,
    /// `Some(None)`: no advisor. `None`: the server's.
    pub advisor: Option<Option<&'static str>>,
}

pub const MODES: &[Mode] = &[
    Mode { name: "fast", what: "fast and cheap: Haiku, high effort", model: Some("claude-haiku-5-5"), effort: Some("high"), advisor: Some(None) },
    Mode { name: "medium", what: "the usual: Opus, medium effort", model: Some("claude-opus-5-5"), effort: Some("medium"), advisor: Some(None) },
    Mode { name: "smart", what: "for the hard ones: Opus, high effort", model: Some("claude-opus-5-5"), effort: Some("high"), advisor: Some(None) },
];

/// Every model at every effort, named `MODEL-EFFORT`. A pick that is a preset's model and effort goes
/// by the preset's name (`canon`).
pub const PICKS: &[Mode] = &[
    Mode { name: "haiku-low", what: "Haiku, low effort", model: Some("claude-haiku-5-5"), effort: Some("low"), advisor: Some(None) },
    Mode { name: "haiku-medium", what: "Haiku, medium effort", model: Some("claude-haiku-5-5"), effort: Some("medium"), advisor: Some(None) },
    Mode { name: "haiku-high", what: "Haiku, high effort", model: Some("claude-haiku-5-5"), effort: Some("high"), advisor: Some(None) },
    Mode { name: "haiku-xhigh", what: "Haiku, extra high effort", model: Some("claude-haiku-5-5"), effort: Some("xhigh"), advisor: Some(None) },
    Mode { name: "haiku-max", what: "Haiku, max effort", model: Some("claude-haiku-5-5"), effort: Some("max"), advisor: Some(None) },
    Mode { name: "sonnet-low", what: "Sonnet, low effort", model: Some("claude-sonnet-5-5"), effort: Some("low"), advisor: Some(None) },
    Mode { name: "sonnet-medium", what: "Sonnet, medium effort", model: Some("claude-sonnet-5-5"), effort: Some("medium"), advisor: Some(None) },
    Mode { name: "sonnet-high", what: "Sonnet, high effort", model: Some("claude-sonnet-5-5"), effort: Some("high"), advisor: Some(None) },
    Mode { name: "sonnet-xhigh", what: "Sonnet, extra high effort", model: Some("claude-sonnet-5-5"), effort: Some("xhigh"), advisor: Some(None) },
    Mode { name: "sonnet-max", what: "Sonnet, max effort", model: Some("claude-sonnet-5-5"), effort: Some("max"), advisor: Some(None) },
    Mode { name: "opus-low", what: "Opus, low effort", model: Some("claude-opus-5-5"), effort: Some("low"), advisor: Some(None) },
    Mode { name: "opus-medium", what: "Opus, medium effort", model: Some("claude-opus-5-5"), effort: Some("medium"), advisor: Some(None) },
    Mode { name: "opus-high", what: "Opus, high effort", model: Some("claude-opus-5-5"), effort: Some("high"), advisor: Some(None) },
    Mode { name: "opus-xhigh", what: "Opus, extra high effort", model: Some("claude-opus-5-5"), effort: Some("xhigh"), advisor: Some(None) },
    Mode { name: "opus-max", what: "Opus, max effort", model: Some("claude-opus-5-5"), effort: Some("max"), advisor: Some(None) },
];

/// The models a pick can name, cheapest first: the short name and how the apps say it.
pub const MODELS: &[(&str, &str)] = &[("haiku", "Haiku"), ("sonnet", "Sonnet"), ("opus", "Opus")];
/// The efforts, least first.
pub const EFFORTS: &[&str] = &["low", "medium", "high", "xhigh", "max"];

pub const DEFAULT: &str = "medium";

fn find(name: &str) -> Option<&'static Mode> {
    MODES.iter().chain(PICKS).find(|m| m.name == name)
}

/// The mode called `name`, or the default for a name nobody knows (an older or newer record).
pub fn mode(name: &str) -> &'static Mode {
    find(name).unwrap_or_else(|| MODES.iter().find(|m| m.name == DEFAULT).expect("a default mode"))
}

pub fn known(name: &str) -> bool {
    find(name).is_some()
}

/// The name a mode goes by: a pick that is a preset's model and effort is the preset ("opus-high" is
/// "smart"), so the record, the guard and alt+e see one name for one thing. Unknown stays as it is.
pub fn canon(name: &str) -> &str {
    let Some(m) = PICKS.iter().find(|m| m.name == name) else { return name };
    MODES.iter().find(|p| p.model == m.model && p.effort == m.effort).map_or(m.name, |p| p.name)
}

/// The mode for a model (`haiku`, `sonnet`, `opus`, or a full id) at an effort, by its `canon` name.
pub fn pick(model: &str, effort: &str) -> Option<&'static str> {
    let short = MODELS.iter().map(|(s, _)| *s).find(|s| model.to_lowercase().contains(s))?;
    let effort = effort.to_lowercase();
    let m = PICKS.iter().find(|m| m.name.split_once('-') == Some((short, effort.as_str())))?;
    Some(find(canon(m.name)).expect("a preset").name)
}

/// The model's short name and the effort of the mode called `name` ("opus", "medium").
pub fn parts(name: &str) -> (&'static str, &'static str) {
    let m = mode(name);
    let model = m.model.unwrap_or("");
    (MODELS.iter().map(|(s, _)| *s).find(|s| model.contains(s)).unwrap_or(""), m.effort.unwrap_or(""))
}

/// How the apps say a model and an effort: "Opus medium", "Haiku xhigh". A model it doesn't know is
/// said as it came.
pub fn say(model: &str, effort: &str) -> String {
    let m = MODELS.iter().find(|(s, _)| model.to_lowercase().contains(s)).map_or(model, |(_, l)| l);
    [m, effort].into_iter().filter(|w| !w.is_empty()).collect::<Vec<_>>().join(" ")
}

/// How the apps say the mode called `name` (empty is the default): "Opus medium".
pub fn label(name: &str) -> String {
    let m = mode(if name.is_empty() { DEFAULT } else { name });
    say(m.model.unwrap_or(""), m.effort.unwrap_or(""))
}

/// Opus at high effort or more: smart or above, which only Aviv's own words or hands pick (the
/// standing rule in cli/src/tier.rs).
pub fn his_only(name: &str) -> bool {
    let (model, effort) = parts(name);
    model == "opus" && EFFORTS.iter().position(|e| *e == effort) >= EFFORTS.iter().position(|e| *e == "high")
}

/// The one after `name`, round again: the key that changes a tab's mode. It goes round the presets
/// only; from a pick it starts at the first.
pub fn next(name: &str) -> &'static str {
    let name = canon(if name.is_empty() { DEFAULT } else { name });
    match MODES.iter().position(|m| m.name == name) {
        Some(i) => MODES[(i + 1) % MODES.len()].name,
        None => MODES[0].name,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn three_modes_each_with_explicit_values() {
        assert_eq!(MODES.len(), 3);
        assert!(MODES.iter().all(|m| m.model.is_some() && m.effort.is_some() && m.advisor == Some(None)));
        let s = mode("medium");
        assert_eq!((s.model, s.effort, s.advisor), (Some("claude-opus-5-5"), Some("medium"), Some(None)));
        assert_eq!(mode("nonsense").name, "medium");
        assert_eq!(mode("").name, "medium");
    }

    #[test]
    fn next_goes_round() {
        assert_eq!(next("fast"), "medium");
        assert_eq!(next("medium"), "smart");
        assert_eq!(next("smart"), "fast");
        assert_eq!(next(""), "smart");
        assert_eq!(next("sonnet-low"), "fast");
        assert_eq!(next("opus-medium"), "smart");
    }

    #[test]
    fn every_model_at_every_effort() {
        assert_eq!(PICKS.len(), MODELS.len() * EFFORTS.len());
        for (m, _) in MODELS {
            for e in EFFORTS {
                let name = pick(m, e).unwrap();
                assert!(known(name));
                assert_eq!(parts(name), (*m, *e));
            }
        }
        assert_eq!(pick("opus", "high"), Some("smart"));
        assert_eq!(pick("claude-haiku-5-5", "HIGH"), Some("fast"));
        assert_eq!(pick("opus", "medium"), Some("medium"));
        assert_eq!(pick("sonnet", "low"), Some("sonnet-low"));
        assert_eq!(pick("gpt", "low"), None);
        assert_eq!(pick("opus", "huge"), None);
        assert_eq!(canon("opus-high"), "smart");
        assert_eq!(canon("sonnet-high"), "sonnet-high");
    }

    #[test]
    fn labels_and_his_own() {
        assert_eq!(label(""), "Opus medium");
        assert_eq!(label("fast"), "Haiku high");
        assert_eq!(label("sonnet-xhigh"), "Sonnet xhigh");
        assert_eq!(say("claude-sonnet-5-5", "low"), "Sonnet low");
        assert_eq!(say("", ""), "");
        assert!(his_only("smart") && his_only("opus-max") && his_only("opus-xhigh"));
        assert!(!his_only("medium") && !his_only("opus-low") && !his_only("sonnet-max") && !his_only("fast"));
    }
}
