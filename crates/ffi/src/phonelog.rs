//! The call's recent log lines, kept in memory so a dropped call can be read on the server away from
//! home (no adb, no logcat permission): a bounded ring fed by its own tracing layer (the voice crate,
//! the ffi's forum tunnel and voice, iroh's warnings, netwatch's errors), sent once per call that went
//! down, through the forum gate's `logup` handshake ([`crate::forum::send_log`]), which stores it
//! under `~/.jarvis/phone-logs/` on the server.

use std::{
    collections::VecDeque,
    io,
    sync::Mutex,
    time::{Duration, SystemTime},
};

use tracing::Level;
use tracing_subscriber::filter::Targets;

/// Lines older than this go.
const KEEP: Duration = Duration::from_secs(15 * 60);
/// And the ring never holds more than this many bytes of lines.
const MAX_BYTES: usize = 512 * 1024;
/// What one upload carries: the last few minutes, at most this many bytes (the newest kept).
pub(crate) const SEND_WINDOW: Duration = Duration::from_secs(5 * 60);
pub(crate) const SEND_MAX: usize = 128 * 1024;

struct Ring {
    lines: VecDeque<(SystemTime, String)>,
    bytes: usize,
}

static RING: Mutex<Ring> = Mutex::new(Ring { lines: VecDeque::new(), bytes: 0 });

fn push(at: SystemTime, line: String) {
    let mut r = RING.lock().unwrap();
    r.bytes += line.len();
    r.lines.push_back((at, line));
    while let Some((t, l)) = r.lines.front() {
        let old = at.duration_since(*t).is_ok_and(|d| d > KEEP);
        if !old && r.bytes <= MAX_BYTES {
            break;
        }
        r.bytes -= l.len();
        r.lines.pop_front();
    }
}

/// The lines of the last `window`, oldest first, at most `max` bytes (the oldest dropped first).
pub(crate) fn recent(window: Duration, max: usize) -> String {
    let now = SystemTime::now();
    let r = RING.lock().unwrap();
    let mut take = Vec::new();
    let mut bytes = 0;
    for (t, l) in r.lines.iter().rev() {
        if now.duration_since(*t).is_ok_and(|d| d > window) || bytes + l.len() + 1 > max {
            break;
        }
        bytes += l.len() + 1;
        take.push(l.as_str());
    }
    let mut out = String::with_capacity(bytes);
    for l in take.into_iter().rev() {
        out.push_str(l);
        out.push('\n');
    }
    out
}

/// What the ring keeps: the call and its transport, not the laptop link's chatter.
pub(crate) fn targets() -> Targets {
    Targets::new()
        .with_target("jarvis_voice", Level::INFO)
        .with_target("jarvis_ffi", Level::INFO)
        .with_target("iroh", Level::WARN)
        .with_target("netwatch", Level::ERROR)
}

/// One formatted event (the fmt layer writes each whole, then drops the writer): pushed on drop.
#[derive(Default)]
pub(crate) struct Line(Vec<u8>);

impl io::Write for Line {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Drop for Line {
    fn drop(&mut self) {
        let s = String::from_utf8_lossy(&self.0);
        let s = s.trim_end();
        if !s.is_empty() {
            push(SystemTime::now(), s.to_string());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_recent_lines_within_their_caps() {
        let now = SystemTime::now();
        push(now - Duration::from_secs(20 * 60), "too old".into());
        push(now - Duration::from_secs(10 * 60), "outside the window".into());
        push(now - Duration::from_secs(60), "voice: line stalled".into());
        push(now, "voice: back on a new socket".into());
        // (Other tests may log into the ring meanwhile: look only at these lines.)
        let got = recent(SEND_WINDOW, SEND_MAX);
        let mine: Vec<&str> = got.lines().filter(|l| l.starts_with("voice: ") || l.starts_with("outside")).collect();
        assert_eq!(mine, ["voice: line stalled", "voice: back on a new socket"]);
        // Over the byte cap, the newest stay.
        assert!(recent(SEND_WINDOW, 30).len() <= 30);
        assert!(!RING.lock().unwrap().lines.iter().any(|(_, l)| l == "too old"), "dropped past KEEP");
        // The ring's own byte cap.
        for _ in 0..(MAX_BYTES / 1000 + 10) {
            push(SystemTime::now(), "x".repeat(1000));
        }
        let r = RING.lock().unwrap();
        assert!(r.bytes <= MAX_BYTES && r.bytes == r.lines.iter().map(|(_, l)| l.len()).sum::<usize>());
    }
}
