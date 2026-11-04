//! The old name, and the only place it's still spoken. The laptop ran the daemon built before the
//! rename to Jarvis (thread #190) until 2026-11-04, when it moved to `jarvis-link` with its state
//! where the old daemon left it. The phone still speaks the old protocol, and the laptop's other
//! widgets and scripts use the old socket and MPRIS names.
//!
//! To drop this: the ALPNs and the pairing code change together on both ends, and the directories,
//! the socket and the MPRIS name move in one step on the laptop, with what uses them there.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

/// The link's ALPN.
pub const ALPN: &[u8] = b"tether/1";
/// The pairing ALPN.
pub const PAIR_ALPN: &[u8] = b"tether/pair/1";
/// The first word of a pairing code (`<word>:1:<endpoint id>:<base32 token>`).
pub const PAIR_WORD: &str = "tether";
/// The daemon's socket, under `$XDG_RUNTIME_DIR`.
pub const SOCKET: &str = "tether.sock";
/// The daemon's directory, under `$XDG_STATE_HOME` and `$XDG_CONFIG_HOME`.
pub const DIR: &str = "tether";
/// The phone's MPRIS player name: the laptop's phone-media widget and dictation script look for
/// `org.mpris.MediaPlayer2.tether.` (kept 2026-11-04 when the laptop moved to `jarvis-link`).
pub const MPRIS: &str = "org.mpris.MediaPlayer2.tether.pixel";

/// The chat database's old file name.
const OLD_DB: &str = "tether.db";

/// The chat database in `state_dir`, renamed from its old name the first time. SQLite's WAL and shared
/// memory files go with it, the main file last, so a crash in between leaves the old name whole.
pub fn database(state_dir: &Path) -> Result<PathBuf> {
    let new = state_dir.join("jarvis.db");
    let old = state_dir.join(OLD_DB);
    if !new.exists() && old.exists() {
        for suffix in ["-wal", "-shm", ""] {
            let from = state_dir.join(format!("{OLD_DB}{suffix}"));
            if from.exists() {
                std::fs::rename(&from, state_dir.join(format!("jarvis.db{suffix}")))
                    .with_context(|| format!("rename {}", from.display()))?;
            }
        }
    }
    Ok(new)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_database_keeps_its_contents_under_the_new_name() {
        let dir = std::env::temp_dir().join(format!("compat-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        for f in ["tether.db", "tether.db-wal", "tether.db-shm"] {
            std::fs::write(dir.join(f), f).unwrap();
        }
        let db = database(&dir).unwrap();
        assert_eq!(std::fs::read_to_string(&db).unwrap(), "tether.db");
        assert_eq!(std::fs::read_to_string(dir.join("jarvis.db-wal")).unwrap(), "tether.db-wal");
        assert!(!dir.join("tether.db").exists());
        // Once there, the old name is never looked at again.
        std::fs::write(dir.join("tether.db"), "stale").unwrap();
        assert_eq!(std::fs::read_to_string(database(&dir).unwrap()).unwrap(), "tether.db");
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
