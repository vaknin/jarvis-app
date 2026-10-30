//! The old name, and the only place it's still spoken. The laptop runs the daemon built before the
//! rename to Jarvis (thread #190), and laptop work is on hold, so the phone must keep speaking its
//! protocol, and a future `jarvis-link` must find its identity where that daemon left it.
//!
//! Drop all of this once the laptop runs `jarvis-link`: the ALPNs and the pairing code change together
//! on both ends, and the directories and the socket move in one step there.

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
