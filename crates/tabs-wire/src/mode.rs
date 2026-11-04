//! A tab's mode: how much model it runs on, named `MODEL-EFFORT` ("opus-medium", "haiku-high"): every
//! model at every effort. alt+m changes the model, alt+e the effort; the phone's picker and a call
//! ("switch to Haiku", "Opus low"; Aviv, 2026-11-02: "not only to Sonnet but to every model and every
//! effort level if I tell you to do it") pick one by name. This table is the one place that says which is
//! which; the server starts a session from it and the apps show it (`label`).
//!
//! There are no presets: Aviv, 2026-11-04, "the keys have changed and they don't have the modes anymore so
//! you can delete them completely". A new tab opens on Opus medium (#195 post 31: it beat Sonnet high alone
//! 8.3 to 5.6 on ten job diffs, 9 done whole against 1). Haiku max stays off every route (the slowest arm).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mode {
    pub name: &'static str,
    pub what: &'static str,
    pub model: Option<&'static str>,
    pub effort: Option<&'static str>,
    /// `Some(None)`: no advisor. `None`: the server's.
    pub advisor: Option<Option<&'static str>>,
}

/// Every model at every effort, named `MODEL-EFFORT`.
pub const MODES: &[Mode] = &[
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

/// The models a mode can name, cheapest first: the short name and how the apps say it.
pub const MODELS: &[(&str, &str)] = &[("haiku", "Haiku"), ("sonnet", "Sonnet"), ("opus", "Opus")];
/// The efforts, least first.
pub const EFFORTS: &[&str] = &["low", "medium", "high", "xhigh", "max"];

pub const DEFAULT: &str = "opus-medium";

fn find(name: &str) -> Option<&'static Mode> {
    MODES.iter().find(|m| m.name == name)
}

/// The mode called `name`, or the default for a name nobody knows (an older or newer record).
pub fn mode(name: &str) -> &'static Mode {
    find(name).unwrap_or_else(|| find(DEFAULT).expect("a default mode"))
}

pub fn known(name: &str) -> bool {
    find(name).is_some()
}

/// The name a mode that comes in goes by. The phone app's copy of this crate, until it's rebuilt, still
/// sends the three preset names that were deleted; they come in as the model and effort they were.
/// Anything else stays as it is.
pub fn canon(name: &str) -> &str {
    match name {
        "fast" => "haiku-high",
        "medium" => "opus-medium",
        "smart" => "opus-high",
        _ => name,
    }
}

/// The mode for a model (`haiku`, `sonnet`, `opus`, or a full id) at an effort.
pub fn pick(model: &str, effort: &str) -> Option<&'static str> {
    let short = MODELS.iter().map(|(s, _)| *s).find(|s| model.to_lowercase().contains(s))?;
    let effort = effort.to_lowercase();
    MODES.iter().find(|m| m.name.split_once('-') == Some((short, effort.as_str()))).map(|m| m.name)
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

/// Opus at high effort or more, which only Aviv's own words or hands pick (the
/// standing rule in cli/src/tier.rs).
pub fn his_only(name: &str) -> bool {
    let (model, effort) = parts(name);
    model == "opus" && EFFORTS.iter().position(|e| *e == effort) >= EFFORTS.iter().position(|e| *e == "high")
}

/// The same effort on the next model, Haiku, Sonnet, Opus and round: the terminal's alt+m.
pub fn next_model(name: &str) -> &'static str {
    let (model, effort) = parts(if name.is_empty() { DEFAULT } else { name });
    let at = MODELS.iter().position(|(s, _)| *s == model).unwrap_or(0);
    let to = MODELS[(at + 1) % MODELS.len()].0;
    pick(to, effort).unwrap_or(DEFAULT)
}

/// The same model at the next effort, low to max and round: the terminal's alt+e.
pub fn next_effort(name: &str) -> &'static str {
    let (model, effort) = parts(if name.is_empty() { DEFAULT } else { name });
    let at = EFFORTS.iter().position(|e| *e == effort).unwrap_or(0);
    pick(model, EFFORTS[(at + 1) % EFFORTS.len()]).unwrap_or(DEFAULT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_model_at_every_effort_with_explicit_values() {
        assert_eq!(MODES.len(), MODELS.len() * EFFORTS.len());
        assert!(MODES.iter().all(|m| m.model.is_some() && m.effort.is_some() && m.advisor == Some(None)));
        for (m, _) in MODELS {
            for e in EFFORTS {
                let name = pick(m, e).unwrap();
                assert_eq!(name, format!("{m}-{e}"));
                assert!(known(name));
                assert_eq!(parts(name), (*m, *e));
            }
        }
        let s = mode(DEFAULT);
        assert_eq!((s.model, s.effort, s.advisor), (Some("claude-opus-5-5"), Some("medium"), Some(None)));
        assert_eq!(mode("nonsense").name, "opus-medium");
        assert_eq!(mode("").name, "opus-medium");
        assert_eq!(pick("claude-haiku-5-5", "HIGH"), Some("haiku-high"));
        assert_eq!(pick("gpt", "low"), None);
        assert_eq!(pick("opus", "huge"), None);
    }

    #[test]
    fn the_deleted_presets_come_in_as_their_model_and_effort() {
        assert_eq!(canon("fast"), "haiku-high");
        assert_eq!(canon("medium"), "opus-medium");
        assert_eq!(canon("smart"), "opus-high");
        assert_eq!(canon("sonnet-high"), "sonnet-high");
        assert!(!known("fast") && !known("medium") && !known("smart"));
    }

    #[test]
    fn labels_and_his_own() {
        assert_eq!(label(""), "Opus medium");
        assert_eq!(label("haiku-high"), "Haiku high");
        assert_eq!(label("sonnet-xhigh"), "Sonnet xhigh");
        assert_eq!(say("claude-sonnet-5-5", "low"), "Sonnet low");
        assert_eq!(say("", ""), "");
        assert!(his_only("opus-high") && his_only("opus-max") && his_only("opus-xhigh"));
        assert!(!his_only("opus-medium") && !his_only("opus-low") && !his_only("sonnet-max") && !his_only("haiku-high"));
    }

    #[test]
    fn the_model_key_keeps_the_effort_and_the_effort_key_keeps_the_model() {
        assert_eq!(next_model(""), "haiku-medium", "a new tab is Opus medium, and round");
        assert_eq!(next_model("sonnet-medium"), "opus-medium");
        assert_eq!(next_model("opus-high"), "haiku-high");
        assert_eq!(next_model("haiku-high"), "sonnet-high");
        assert_eq!(next_effort(""), "opus-high");
        assert_eq!(next_effort("opus-high"), "opus-xhigh");
        assert_eq!(next_effort("opus-max"), "opus-low");
        assert_eq!(next_effort("haiku-medium"), "haiku-high");
    }
}
