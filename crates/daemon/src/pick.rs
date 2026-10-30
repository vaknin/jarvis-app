//! Where `jarvis-link send --pick` and `--clipboard` get their files: the desktop's file chooser (the
//! XDG FileChooser portal, GTK here) and an image on the Wayland clipboard. Both run in the CLI, so
//! the chat panel can call them like any other `jarvis-link send`.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    pin::Pin,
    process::Stdio,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use tokio::process::Command;
use zbus::{
    Connection, Proxy,
    export::futures_core::Stream,
    zvariant::{OwnedObjectPath, OwnedValue, Value},
};

const PORTAL: &str = "org.freedesktop.portal.Desktop";
/// Pasted images older than this are deleted on the next paste; the file has to outlive delivery,
/// since a send resumes from the original.
const KEEP_PASTED: Duration = Duration::from_secs(14 * 24 * 3600);

/// Files chosen in the portal's file chooser; empty if the user cancelled.
pub async fn choose_files() -> Result<Vec<PathBuf>> {
    let conn = Connection::session().await.context("connect to the session bus")?;
    let token = format!("jarvis{}", std::process::id());
    let sender = conn.unique_name().context("no bus name")?.trim_start_matches(':').replace('.', "_");
    // Subscribe to the request's Response before the call, so a fast answer can't be missed.
    let path = format!("/org/freedesktop/portal/desktop/request/{sender}/{token}");
    let request = Proxy::new(&conn, PORTAL, path, "org.freedesktop.portal.Request").await?;
    let mut responses = request.receive_signal("Response").await?;

    let chooser =
        Proxy::new(&conn, PORTAL, "/org/freedesktop/portal/desktop", "org.freedesktop.portal.FileChooser").await?;
    let options = HashMap::from([("handle_token", Value::from(token.as_str())), ("multiple", Value::from(true))]);
    let _: OwnedObjectPath = chooser
        .call("OpenFile", &("", "Send to phone", options))
        .await
        .context("open the file chooser portal")?;

    let msg = std::future::poll_fn(|cx| Pin::new(&mut responses).poll_next(cx))
        .await
        .context("the portal went away")?;
    let (code, results): (u32, HashMap<String, OwnedValue>) = msg.body().deserialize()?;
    // 1 = cancelled, 2 = ended some other way.
    if code != 0 {
        return Ok(Vec::new());
    }
    let uris: Vec<String> = match results.get("uris") {
        Some(v) => v.try_clone()?.try_into()?,
        None => Vec::new(),
    };
    uris.iter().map(|u| uri_path(u)).collect()
}

fn uri_path(uri: &str) -> Result<PathBuf> {
    let rest = uri.strip_prefix("file://").with_context(|| format!("not a local file: {uri}"))?;
    let mut bytes = Vec::with_capacity(rest.len());
    let mut it = rest.bytes();
    while let Some(b) = it.next() {
        if b == b'%' {
            let hex = [it.next().unwrap_or(b'0'), it.next().unwrap_or(b'0')];
            let s = std::str::from_utf8(&hex)?;
            bytes.push(u8::from_str_radix(s, 16).with_context(|| format!("bad escape in {uri}"))?);
        } else {
            bytes.push(b);
        }
    }
    use std::os::unix::ffi::OsStringExt;
    Ok(PathBuf::from(std::ffi::OsString::from_vec(bytes)))
}

/// Saves the image on the clipboard under `dir` (pasted images live there until they're old) and
/// returns its path. Fails if the clipboard holds no image.
pub async fn clipboard_image(dir: &Path) -> Result<PathBuf> {
    let out = Command::new("wl-paste").arg("--list-types").output().await.context("run wl-paste")?;
    let types = String::from_utf8_lossy(&out.stdout);
    let types: Vec<&str> = types.lines().collect();
    let Some((mime, ext)) = [("image/png", "png"), ("image/jpeg", "jpg"), ("image/webp", "webp"), ("image/gif", "gif")]
        .into_iter()
        .find(|(m, _)| types.contains(m))
    else {
        bail!("no image on the clipboard");
    };
    let data = Command::new("wl-paste")
        .args(["--no-newline", "--type", mime])
        .stderr(Stdio::inherit())
        .output()
        .await?;
    anyhow::ensure!(data.status.success() && !data.stdout.is_empty(), "wl-paste gave no {mime} data");

    std::fs::create_dir_all(dir)?;
    prune(dir);
    let ms = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis();
    let path = dir.join(format!("pasted-{ms}.{ext}"));
    std::fs::write(&path, &data.stdout)?;
    Ok(path)
}

fn prune(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for e in entries.flatten() {
        let old = e.metadata().and_then(|m| m.modified()).is_ok_and(|t| t.elapsed().unwrap_or_default() > KEEP_PASTED);
        if old {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uri_path_decodes_escapes() {
        assert_eq!(uri_path("file:///home/k/My%20File%C3%A9.txt").unwrap(), PathBuf::from("/home/k/My Fileé.txt"));
        assert!(uri_path("https://example.com/x").is_err());
    }
}
