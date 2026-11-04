//! Where `jarvis-link send --pick` and `--clipboard` get their files: the desktop's file chooser (the
//! XDG FileChooser portal, GTK here) and an image on the Wayland clipboard. Both run in the CLI, so
//! the chat panel can call them like any other `jarvis-link send`. `jarvis-link pixel-shot` adds a
//! screenshot of the phone, from the laptop's own adb.

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

    save(dir, "pasted", ext, &data.stdout)
}

fn save(dir: &Path, prefix: &str, ext: &str, data: &[u8]) -> Result<PathBuf> {
    std::fs::create_dir_all(dir)?;
    prune(dir);
    let ms = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis();
    let path = dir.join(format!("{prefix}-{ms}.{ext}"));
    std::fs::write(&path, data)?;
    Ok(path)
}

/// How long the screenshot itself may take.
const SHOT_TIMEOUT: Duration = Duration::from_secs(15);
/// How long to wait for the phone to show up in adb, which finds it over mDNS a moment after it starts.
const FIND_TIMEOUT: Duration = Duration::from_secs(5);

/// Saves a screenshot of the phone under `dir`, next to pasted images, and returns its path. It
/// comes only from this laptop's own adb (the phone it already sees); there's no other route.
/// A phone with its screen off is refused, not woken: the shot would come back black.
/// `dumpsys` and `screencap` only read the phone, so nothing changes on it.
pub async fn phone_screenshot(dir: &Path) -> Result<PathBuf> {
    let serial = match local_serial().await {
        Some(s) => s,
        None => {
            let wait = Command::new("adb").arg("wait-for-device").kill_on_drop(true).status();
            let _ = tokio::time::timeout(FIND_TIMEOUT, wait).await;
            local_serial().await.context("no phone on the laptop's adb (wireless debugging off, or not on this Wi-Fi?)")?
        }
    };
    let power = run_adb(Command::new("adb").args(["-s", &serial, "shell", "dumpsys", "power"]))
        .await
        .context("adb dumpsys power")?;
    if !screen_on(&String::from_utf8_lossy(&power)) {
        bail!("the phone's screen is off");
    }
    let png = run_adb(Command::new("adb").args(["-s", &serial, "exec-out", "screencap", "-p"]))
        .await
        .context("adb screencap")?;
    anyhow::ensure!(is_png(&png), "the phone gave no PNG");
    save(dir, "pixel", "png", &png)
}

/// Whether `adb shell dumpsys power` shows the screen on: the power manager is Awake, or the
/// display's power state is ON.
fn screen_on(dumpsys: &str) -> bool {
    dumpsys.lines().any(|l| l.contains("mWakefulness=Awake") || l.contains("Display Power: state=ON"))
}

async fn local_serial() -> Option<String> {
    let out = Command::new("adb").arg("devices").kill_on_drop(true).output();
    let out = tokio::time::timeout(FIND_TIMEOUT, out).await.ok()?.ok()?;
    first_device(&String::from_utf8_lossy(&out.stdout))
}

/// The first serial `adb devices` lists as ready ("device", not "offline" or "unauthorized").
fn first_device(list: &str) -> Option<String> {
    list.lines().skip(1).find_map(|l| match l.split_whitespace().collect::<Vec<_>>()[..] {
        [serial, "device", ..] => Some(serial.to_string()),
        _ => None,
    })
}

/// Runs one adb command under the screenshot's time cap and returns its stdout.
async fn run_adb(cmd: &mut Command) -> Result<Vec<u8>> {
    let out = cmd.stdin(Stdio::null()).kill_on_drop(true).output();
    let out = tokio::time::timeout(SHOT_TIMEOUT, out)
        .await
        .map_err(|_| anyhow::anyhow!("timed out after {}s", SHOT_TIMEOUT.as_secs()))?
        .context("run adb")?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        let why = err.lines().rfind(|l| !l.trim().is_empty()).unwrap_or("no output").trim();
        bail!("failed ({why})");
    }
    Ok(out.stdout)
}

fn is_png(data: &[u8]) -> bool {
    data.len() > 8 && data.starts_with(b"\x89PNG\r\n\x1a\n")
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

    #[test]
    fn first_device_skips_offline_and_unauthorized() {
        let list = "List of devices attached\nAAA\toffline\nBBB\tunauthorized usb:1\nadb-37171-x._adb-tls-connect._tcp\tdevice product:shiba\n\n";
        assert_eq!(first_device(list).as_deref(), Some("adb-37171-x._adb-tls-connect._tcp"));
        assert_eq!(first_device("List of devices attached\n\n"), None);
    }

    #[test]
    fn screen_on_reads_dumpsys_power() {
        assert!(screen_on("Power Manager State:\n  mWakefulness=Awake\n  mWakefulnessChanging=false\n"));
        assert!(screen_on("  Display Power: state=ON\n"));
        assert!(!screen_on("Power Manager State:\n  mWakefulness=Dozing\n  mWakefulnessChanging=false\n"));
        assert!(!screen_on("  mWakefulness=Asleep\n  Display Power: state=OFF\n"));
        assert!(!screen_on(""));
    }

    #[test]
    fn png_check_and_name() {
        assert!(is_png(b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR"));
        assert!(!is_png(b"error: no devices"));
        let dir = std::env::temp_dir().join(format!("jarvis-shot-{}", std::process::id()));
        let p = save(&dir, "pixel", "png", b"x").unwrap();
        assert!(p.file_name().unwrap().to_str().unwrap().starts_with("pixel-"));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
