//! A channel's look in the shared design language (`docs/DESIGN.md`): a `hue` gives its colours
//! and an `icon` (a Lucide name, or your own 24-grid SVG) its mark. The colour recipes and the SVG
//! conversion follow `~/Projects/design` (`tokens.json` `perApp`, `src/color.rs`, `src/svg.rs`), so
//! a channel tile looks like an app's launcher tile: the white mark on `tile`.

use std::{
    fmt::Write,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail, ensure};
use serde::Serialize;

/// The colours one hue gives (`tokens.json` `perApp`), as `#RRGGBB`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct HueColors {
    pub accent: String,
    pub on_accent: String,
    pub tile: String,
}

pub fn colors(hue: f64) -> HueColors {
    HueColors { accent: oklch(0.82, 0.14, hue), on_accent: oklch(0.20, 0.04, hue), tile: oklch(0.36, 0.09, hue) }
}

/// Linear sRGB for an OKLCH colour; may be out of [0, 1] (Björn Ottosson's OKLab matrices).
fn oklch_linear(l: f64, c: f64, h_deg: f64) -> [f64; 3] {
    let h = h_deg.to_radians();
    let (a, b) = (c * h.cos(), c * h.sin());
    let l_ = l + 0.396_337_777_4 * a + 0.215_803_757_3 * b;
    let m_ = l - 0.105_561_345_8 * a - 0.063_854_172_8 * b;
    let s_ = l - 0.089_484_177_5 * a - 1.291_485_548_0 * b;
    let (l3, m3, s3) = (l_.powi(3), m_.powi(3), s_.powi(3));
    [
        4.076_741_662_1 * l3 - 3.307_711_591_3 * m3 + 0.230_969_929_2 * s3,
        -1.268_438_004_6 * l3 + 2.609_757_401_1 * m3 - 0.341_319_396_5 * s3,
        -0.004_196_086_3 * l3 - 0.703_418_614_7 * m3 + 1.707_614_701_0 * s3,
    ]
}

fn in_gamut(rgb: [f64; 3]) -> bool {
    rgb.iter().all(|&v| (-1e-4..=1.0 + 1e-4).contains(&v))
}

fn encode(v: f64) -> u8 {
    let v = v.clamp(0.0, 1.0);
    let s = if v <= 0.003_130_8 { 12.92 * v } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 };
    (s * 255.0).round() as u8
}

/// `#RRGGBB` at lightness `l` and hue `h`, with chroma `c` reduced until it fits sRGB.
fn oklch(l: f64, c: f64, h: f64) -> String {
    let mut chroma = c;
    if !in_gamut(oklch_linear(l, chroma, h)) {
        let (mut lo, mut hi) = (0.0, c);
        for _ in 0..32 {
            let mid = (lo + hi) / 2.0;
            if in_gamut(oklch_linear(l, mid, h)) { lo = mid } else { hi = mid }
        }
        chroma = lo;
    }
    let [r, g, b] = oklch_linear(l, chroma, h);
    format!("#{:02X}{:02X}{:02X}", encode(r), encode(g), encode(b))
}

/// A mark as the UIs draw it: white shapes in `view` (x, y, w, h), round caps and joins.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Mark {
    pub view: [f64; 4],
    pub paths: Vec<MarkPath>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct MarkPath {
    /// SVG path data.
    pub d: String,
    pub fill: bool,
    /// Stroke width in `view` units; 0 for none.
    pub stroke: f64,
}

/// Where `icon = "<name>"` is looked up: the copy `jarvis-link channel` keeps next to the manifests,
/// then the Lucide set vendored in `~/Projects/design` (`$JARVIS_LUCIDE` overrides it).
pub fn lucide_dir() -> Option<PathBuf> {
    std::env::var_os("JARVIS_LUCIDE")
        .map(PathBuf::from)
        .or_else(|| crate::home().ok().map(|h| h.join("Projects/design/vendor/lucide/icons")))
}

/// `[a-z0-9-]+`, as Lucide names are; also keeps the name inside the folder.
pub fn valid_icon(name: &str) -> bool {
    !name.is_empty() && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// The SVG file for `icon` (apps/icons first, then Lucide).
pub fn find(apps_dir: &Path, icon: &str) -> Option<PathBuf> {
    if !valid_icon(icon) {
        return None;
    }
    let own = apps_dir.join("icons").join(format!("{icon}.svg"));
    if own.is_file() {
        return Some(own);
    }
    lucide_dir().map(|d| d.join(format!("{icon}.svg"))).filter(|p| p.is_file())
}

pub fn load(path: &Path) -> Result<Mark> {
    parse(&std::fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?)
}

fn num(n: &roxmltree::Node, a: &str) -> f64 {
    n.attribute(a).and_then(|v| v.trim_end_matches("px").parse().ok()).unwrap_or(0.0)
}

fn paint(v: Option<&str>, inherited: bool) -> bool {
    match v {
        None => inherited,
        Some("none") | Some("transparent") => false,
        Some(_) => true,
    }
}

fn points(s: &str) -> Vec<f64> {
    s.split(|c: char| c == ',' || c.is_whitespace()).filter(|t| !t.is_empty()).filter_map(|t| t.parse().ok()).collect()
}

fn f(v: f64) -> String {
    let s = format!("{v:.3}");
    let s = s.trim_end_matches('0').trim_end_matches('.');
    if s == "-0" { "0".into() } else { s.into() }
}

fn rect_path(x: f64, y: f64, w: f64, h: f64, rx: f64, ry: f64) -> String {
    let (rx, ry) = (rx.min(w / 2.0), ry.min(h / 2.0));
    if rx <= 0.0 || ry <= 0.0 {
        return format!("M{},{}h{}v{}h{}z", f(x), f(y), f(w), f(h), f(-w));
    }
    let (iw, ih) = (w - 2.0 * rx, h - 2.0 * ry);
    let arc = |dx: f64, dy: f64| format!("a{},{} 0 0 1 {},{}", f(rx), f(ry), f(dx), f(dy));
    format!(
        "M{},{}h{}{}v{}{}h{}{}v{}{}z",
        f(x + rx), f(y), f(iw), arc(rx, ry), f(ih), arc(-rx, ry), f(-iw), arc(-rx, -ry), f(-ih), arc(rx, -ry)
    )
}

fn ellipse_path(cx: f64, cy: f64, rx: f64, ry: f64) -> String {
    format!(
        "M{},{}a{},{} 0 1 0 {},0a{},{} 0 1 0 {},0z",
        f(cx - rx), f(cy), f(rx), f(ry), f(2.0 * rx), f(rx), f(ry), f(-2.0 * rx)
    )
}

/// Lucide-style SVG (path, circle, ellipse, line, rect, polyline, polygon; no transforms).
pub fn parse(src: &str) -> Result<Mark> {
    let doc = roxmltree::Document::parse(src).context("not valid SVG")?;
    let root = doc.root_element();
    ensure!(root.tag_name().name() == "svg", "not an <svg>");
    let view = match root.attribute("viewBox").map(points) {
        Some(v) if v.len() == 4 => [v[0], v[1], v[2], v[3]],
        _ => [0.0, 0.0, num(&root, "width"), num(&root, "height")],
    };
    ensure!(view[2] > 0.0 && view[3] > 0.0, "the SVG has no size (viewBox or width/height)");
    let mut paths = Vec::new();
    walk(root, (true, false, 1.0), &mut paths)?;
    ensure!(!paths.is_empty(), "the SVG has no shapes");
    Ok(Mark { view, paths })
}

/// `style` is the inherited (fill, stroke, stroke width).
fn walk(node: roxmltree::Node, style: (bool, bool, f64), out: &mut Vec<MarkPath>) -> Result<()> {
    if node.attribute("transform").is_some() {
        bail!("transform attributes aren't supported; flatten the mark first");
    }
    let fill = paint(node.attribute("fill"), style.0);
    let stroke = paint(node.attribute("stroke"), style.1);
    let width = node.attribute("stroke-width").and_then(|v| v.parse().ok()).unwrap_or(style.2);
    let n = &node;
    let d = match node.tag_name().name() {
        "svg" | "g" => {
            for c in node.children().filter(|c| c.is_element()) {
                walk(c, (fill, stroke, width), out)?;
            }
            return Ok(());
        }
        "path" => n.attribute("d").unwrap_or("").to_owned(),
        "circle" => ellipse_path(num(n, "cx"), num(n, "cy"), num(n, "r"), num(n, "r")),
        "ellipse" => ellipse_path(num(n, "cx"), num(n, "cy"), num(n, "rx"), num(n, "ry")),
        "line" => format!("M{},{}L{},{}", f(num(n, "x1")), f(num(n, "y1")), f(num(n, "x2")), f(num(n, "y2"))),
        "rect" => {
            let (rx, ry) = (n.attribute("rx"), n.attribute("ry"));
            let rx_v = rx.or(ry).and_then(|v| v.parse().ok()).unwrap_or(0.0);
            let ry_v = ry.or(rx).and_then(|v| v.parse().ok()).unwrap_or(0.0);
            rect_path(num(n, "x"), num(n, "y"), num(n, "width"), num(n, "height"), rx_v, ry_v)
        }
        tag @ ("polyline" | "polygon") => {
            let p = points(n.attribute("points").unwrap_or(""));
            let mut d = String::new();
            for (i, xy) in p.as_chunks::<2>().0.iter().enumerate() {
                let _ = write!(d, "{}{},{}", if i == 0 { "M" } else { "L" }, f(xy[0]), f(xy[1]));
            }
            if tag == "polygon" {
                d.push('z');
            }
            d
        }
        "title" | "desc" | "defs" => return Ok(()),
        other => bail!("unsupported SVG element <{other}>"),
    };
    if !d.is_empty() && (fill || stroke) {
        out.push(MarkPath { d, fill, stroke: if stroke { width } else { 0.0 } });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hue_250_matches_the_design_generator() {
        // `design colors --hue 250`: jarvis's own palette.
        let c = colors(250.0);
        assert_eq!((c.accent.as_str(), c.on_accent.as_str(), c.tile.as_str()), ("#95C9FF", "#071727", "#0E3F6A"));
    }

    #[test]
    fn lucide_shapes_become_paths() {
        let src = r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19v3"/><rect x="9" y="2" width="6" height="13" rx="3"/><circle cx="12" cy="12" r="2" fill="currentColor"/><polyline points="1,2 3,4"/></svg>"#;
        let m = parse(src).unwrap();
        assert_eq!(m.view, [0.0, 0.0, 24.0, 24.0]);
        assert_eq!(m.paths.len(), 4);
        assert!(m.paths.iter().all(|p| p.stroke == 2.0));
        assert!(!m.paths[0].fill && m.paths[2].fill);
        assert_eq!(m.paths[1].d, "M12,2h0a3,3 0 0 1 3,3v7a3,3 0 0 1 -3,3h0a3,3 0 0 1 -3,-3v-7a3,3 0 0 1 3,-3z");
        assert_eq!(m.paths[3].d, "M1,2L3,4");
    }

    #[test]
    fn transforms_and_odd_names_are_refused() {
        assert!(parse(r#"<svg viewBox="0 0 24 24"><path transform="scale(2)" d="M0 0h1"/></svg>"#).is_err());
        assert!(!valid_icon("../etc/passwd") && !valid_icon("Cart") && valid_icon("shopping-cart"));
    }
}
