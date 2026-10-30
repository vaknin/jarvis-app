//! Laptop side effects of what the phone sends: toasts, and received images on the clipboard as
//! image data (ported from ~/.local/bin/kdeconnect-share-notify).

use std::{io::Cursor, path::Path, process::Stdio};

use anyhow::{Context, Result};
use image::{
    DynamicImage, ImageDecoder, ImageFormat, ImageReader,
    codecs::png::{CompressionType, FilterType, PngEncoder},
};
use jarvis_core::{
    node::{Event, Node},
    store::{Message, State},
};
use tokio::{io::AsyncWriteExt, process::Command, sync::broadcast::error::RecvError};
use tracing::warn;

pub async fn run(node: Node) {
    let mut rx = node.events();
    loop {
        match rx.recv().await {
            // A channel's file (a photo sent to dibs) is its app's, not a download: no toast, no clipboard.
            Ok(Event::Message(m)) if !m.from_me && m.channel.is_none() => {
                if let Err(e) = handle(&node, &m).await {
                    warn!("desktop side effect for {} failed: {e:#}", m.id);
                }
            }
            Ok(Event::SendFailed { name, reason, .. }) => {
                let body = format!("Couldn't send {name}: {reason}");
                if let Err(e) = toast(&["-g", "󰀦", "Jarvis", &body], OPEN_CHAT).await {
                    warn!("send-failed toast: {e:#}");
                }
            }
            Ok(_) | Err(RecvError::Lagged(_)) => {}
            Err(RecvError::Closed) => return,
        }
    }
}

/// A click on a chat toast opens the chat panel (the `kivan.jarvis` shell plugin).
const OPEN_CHAT: &[&str] = &["omarchy-shell", "jarvis", "open"];

async fn handle(node: &Node, m: &Message) -> Result<()> {
    if m.state != State::Received {
        return Ok(());
    }
    let who = node
        .peer()
        .map(|p| p.name)
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| "Phone".into());
    let text = m.text.clone().unwrap_or_default();
    match m.kind {
        "file" => {
            let path = m.path.as_deref().context("received file has no path")?;
            on_file(&who, path).await
        }
        "text" => toast(&["-g", "󰍡", &who, &text], OPEN_CHAT).await,
        "ping" => toast(&["-g", "󰂚", &who, &text], OPEN_CHAT).await,
        _ => Ok(()),
    }
}

/// Toast that gets through Do Not Disturb (it's an omarchy-action toast); a click opens the file.
/// Images also go onto the clipboard, ready for Ctrl+V.
async fn on_file(who: &str, path: &Path) -> Result<()> {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let is_image = image::ImageFormat::from_path(path).is_ok_and(|f| f.can_read());
    let uri = file_uri(path);
    let mut args = vec!["-g", "󰄜"];
    if is_image {
        args.extend(["--image", &uri]);
        let path = path.to_owned();
        tokio::spawn(async move {
            if let Err(e) = copy_image(&path).await {
                warn!("copying {} to the clipboard failed: {e:#}", path.display());
            }
        });
    }
    let headline = format!("{who} sent a file");
    // A name like `--image=…` would be read as the toast's option: lead it with an invisible joiner.
    let name = if name.starts_with('-') { format!("\u{2060}{name}") } else { name };
    args.extend([headline.as_str(), name.as_str()]);
    toast(&args, &["xdg-open", &path.to_string_lossy()]).await
}

async fn toast(args: &[&str], exec: &[&str]) -> Result<()> {
    let mut cmd = Command::new("omarchy-notification-send");
    cmd.args(["-u", "normal"]).args(args);
    if !exec.is_empty() {
        cmd.arg("--exec").args(exec);
    }
    let status = cmd.status().await.context("run omarchy-notification-send")?;
    anyhow::ensure!(status.success(), "omarchy-notification-send exited with {status}");
    Ok(())
}

pub async fn copy_image(path: &Path) -> Result<()> {
    let p = path.to_owned();
    let (mime, data) = tokio::task::spawn_blocking(move || clipboard_image(&p)).await??;
    let mut child = Command::new("wl-copy")
        .args(["--type", mime])
        .stdin(Stdio::piped())
        .spawn()
        .context("run wl-copy")?;
    child.stdin.take().context("wl-copy stdin")?.write_all(&data).await?;
    // wl-copy forks to serve the clipboard; the parent exits once it has the data.
    let status = child.wait().await?;
    anyhow::ensure!(status.success(), "wl-copy exited with {status}");
    Ok(())
}

/// The bytes to put on the clipboard. Pasting apps mostly only take image/png, so anything but
/// PNG and GIF (kept for animation) becomes a PNG, with the EXIF rotation applied since PNG can't
/// carry it. Fast compression: a 12 MP photo takes about 1 s instead of 4.
pub fn clipboard_image(path: &Path) -> Result<(&'static str, Vec<u8>)> {
    let bytes = std::fs::read(path)?;
    let format = image::guess_format(&bytes)?;
    match format {
        ImageFormat::Png => return Ok(("image/png", bytes)),
        ImageFormat::Gif => return Ok(("image/gif", bytes)),
        _ => {}
    }
    let mut decoder = ImageReader::with_format(Cursor::new(&bytes), format).into_decoder()?;
    let orientation = decoder.orientation()?;
    let mut img = DynamicImage::from_decoder(decoder)?;
    img.apply_orientation(orientation);
    let mut out = Vec::new();
    img.write_with_encoder(PngEncoder::new_with_quality(
        &mut out,
        CompressionType::Fast,
        FilterType::Adaptive,
    ))?;
    Ok(("image/png", out))
}

/// `file://` URI for the toast's image (the shell loads images from URIs, not paths).
fn file_uri(path: &Path) -> String {
    let mut uri = String::from("file://");
    for b in path.to_string_lossy().bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'/' | b'-' | b'_' | b'.' | b'~' => {
                uri.push(b as char)
            }
            _ => uri.push_str(&format!("%{b:02X}")),
        }
    }
    uri
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jpeg_becomes_png_and_png_passes_through() {
        let d = tempfile::tempdir().unwrap();
        let img = DynamicImage::new_rgb8(4, 2);
        let jpg = d.path().join("a.jpg");
        let png = d.path().join("a.png");
        img.save(&jpg).unwrap();
        img.save(&png).unwrap();

        let (mime, data) = clipboard_image(&jpg).unwrap();
        assert_eq!(mime, "image/png");
        let back = image::load_from_memory(&data).unwrap();
        assert_eq!((back.width(), back.height()), (4, 2));

        let (mime, data) = clipboard_image(&png).unwrap();
        assert_eq!(mime, "image/png");
        assert_eq!(data, std::fs::read(&png).unwrap());
    }

    #[test]
    fn uri_escapes_spaces() {
        assert_eq!(file_uri(Path::new("/home/k/Downloads/a b.png")), "file:///home/k/Downloads/a%20b.png");
    }
}
