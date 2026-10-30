//! File helpers: hashing outgoing files, and where incoming ones are written while they arrive.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;
use uuid::Uuid;

pub async fn sha256_file(path: &Path) -> Result<([u8; 32], u64)> {
    let mut f = tokio::fs::File::open(path)
        .await
        .with_context(|| format!("open {}", path.display()))?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut size = 0u64;
    loop {
        let n = f.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
        size += n as u64;
    }
    Ok((h.finalize().into(), size))
}

/// Partial download for an incoming file; its length is the resume offset.
pub fn part_path(dir: &Path, id: Uuid) -> PathBuf {
    dir.join(format!(".jarvis-{id}.part"))
}

/// Strips anything that could escape `dir` from a peer-supplied file name.
pub fn safe_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let clean: String = base.chars().filter(|c| !c.is_control()).collect();
    let clean = clean.trim().trim_start_matches('.');
    if clean.is_empty() { "file".to_string() } else { clean.to_string() }
}

/// `dir/name`, or `dir/name (1).ext`, … if that exists.
pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let name = safe_name(name);
    let first = dir.join(&name);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name.as_str(), ""),
    };
    (1..)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|p| !p.exists())
        .expect("unbounded")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_cannot_escape_the_folder() {
        assert_eq!(safe_name("../../.bashrc"), "bashrc");
        assert_eq!(safe_name("C:\\x\\shot.png"), "shot.png");
        assert_eq!(safe_name(""), "file");
    }

    #[test]
    fn unique_path_numbers_duplicates() {
        let d = tempfile::tempdir().unwrap();
        std::fs::write(d.path().join("a.png"), b"").unwrap();
        assert_eq!(unique_path(d.path(), "a.png"), d.path().join("a (1).png"));
        assert_eq!(unique_path(d.path(), "b"), d.path().join("b"));
    }
}
