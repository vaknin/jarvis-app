//! SQLite state: the chat log (which doubles as the outbox) and a few settings.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use uuid::Uuid;

use crate::proto::{Body, Item};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum State {
    /// Mine, not yet acked by the peer.
    Queued,
    /// Mine, acked.
    Delivered,
    /// Mine, never delivered before `expires_ms`.
    Expired,
    /// Theirs, a file still arriving.
    Incoming,
    /// Theirs, complete.
    Received,
    /// Mine, a file I stopped; the peer hasn't confirmed dropping it yet. UIs see it as cancelled.
    #[serde(rename = "cancelled")]
    Cancelling,
    /// A file its sender stopped (on my side, confirmed by the peer).
    Cancelled,
}

impl State {
    fn as_str(self) -> &'static str {
        match self {
            State::Queued => "queued",
            State::Delivered => "delivered",
            State::Expired => "expired",
            State::Incoming => "incoming",
            State::Received => "received",
            State::Cancelling => "cancelling",
            State::Cancelled => "cancelled",
        }
    }

    fn parse(s: &str) -> Result<Self> {
        Ok(match s {
            "queued" => State::Queued,
            "delivered" => State::Delivered,
            "expired" => State::Expired,
            "incoming" => State::Incoming,
            "received" => State::Received,
            "cancelling" => State::Cancelling,
            "cancelled" => State::Cancelled,
            other => anyhow::bail!("unknown state {other}"),
        })
    }
}

/// One chat entry as the UIs see it (also the JSON shape of `jarvis-link json`).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Message {
    pub id: Uuid,
    #[serde(skip)]
    pub seq: u64,
    pub from_me: bool,
    pub ts_ms: i64,
    #[serde(skip)]
    pub expires_ms: Option<i64>,
    pub kind: &'static str,
    pub text: Option<String>,
    pub file_name: Option<String>,
    pub file_size: Option<u64>,
    #[serde(skip)]
    pub sha256: Option<[u8; 32]>,
    /// Local file: the source for files I send, the saved file for files I receive.
    pub path: Option<PathBuf>,
    pub state: State,
    /// Theirs: seen. For app items: taken by the channel's client.
    pub read: bool,
    /// App items (kind `app`) and views (kind `view`) only: the app channel (`text` holds its data).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel: Option<String>,
}

impl Message {
    pub fn body(&self) -> Body {
        match self.kind {
            "text" => Body::Text(self.text.clone().unwrap_or_default()),
            "ping" => Body::Ping(self.text.clone().unwrap_or_default()),
            "ring" => Body::Ring,
            "drop" => Body::DropChannel(self.channel.clone().unwrap_or_default()),
            "app" | "view" => Body::App {
                channel: self.channel.clone().unwrap_or_default(),
                data: self.text.clone().unwrap_or_default(),
                replace: self.kind == "view",
            },
            _ => match &self.channel {
                Some(channel) => Body::ChannelFile {
                    channel: channel.clone(),
                    name: self.file_name.clone().unwrap_or_default(),
                    size: self.file_size.unwrap_or(0),
                    sha256: self.sha256.unwrap_or([0; 32]),
                },
                None => Body::File {
                    name: self.file_name.clone().unwrap_or_default(),
                    size: self.file_size.unwrap_or(0),
                    sha256: self.sha256.unwrap_or([0; 32]),
                },
            },
        }
    }

    pub fn item(&self) -> Item {
        Item {
            id: self.id,
            seq: self.seq,
            ts_ms: self.ts_ms,
            expires_ms: self.expires_ms,
            body: self.body(),
        }
    }
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub struct Store {
    db: Connection,
}

const COLS: &str =
    "id, seq, from_me, ts_ms, expires_ms, kind, text, file_name, file_size, sha256, path, state, read, channel";
/// App items and views aren't chat: the chat views and the unread count leave them out.
// Channel files (kind `file` with a channel) belong to their channel, not the chat.
const CHAT: &str = "kind NOT IN ('app', 'view', 'drop') AND channel IS NULL";
/// Taken or delivered app items are dropped after this long.
const APP_KEEP_MS: i64 = 30 * 24 * 3600 * 1000;

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        let db = Connection::open(path).with_context(|| format!("open {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        }
        Self::init(db)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(db: Connection) -> Result<Self> {
        db.execute_batch(
            "PRAGMA journal_mode = WAL;
             CREATE TABLE IF NOT EXISTS messages (
                 id TEXT PRIMARY KEY,
                 seq INTEGER NOT NULL,
                 from_me INTEGER NOT NULL,
                 ts_ms INTEGER NOT NULL,
                 expires_ms INTEGER,
                 kind TEXT NOT NULL,
                 text TEXT,
                 file_name TEXT,
                 file_size INTEGER,
                 sha256 BLOB,
                 path TEXT,
                 state TEXT NOT NULL,
                 read INTEGER NOT NULL DEFAULT 0
             );
             CREATE INDEX IF NOT EXISTS messages_ts ON messages (ts_ms);
             CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value BLOB NOT NULL);",
        )?;
        let has_channel: bool = db.query_row(
            "SELECT COUNT(*) FROM pragma_table_info('messages') WHERE name = 'channel'",
            [],
            |r| r.get::<_, i64>(0).map(|n| n > 0),
        )?;
        if !has_channel {
            db.execute_batch("ALTER TABLE messages ADD COLUMN channel TEXT")?;
        }
        let s = Self { db };
        s.prune_apps(now_ms() - APP_KEEP_MS)?;
        Ok(s)
    }

    /// Drops app items from before `before` that are done with: mine delivered, theirs taken.
    fn prune_apps(&self, before: i64) -> Result<usize> {
        Ok(self.db.execute(
            "DELETE FROM messages WHERE kind IN ('app', 'drop') AND ts_ms < ?1
             AND ((from_me = 1 AND state = 'delivered') OR (from_me = 0 AND read = 1))",
            [before],
        )?)
    }

    pub fn get_kv(&self, key: &str) -> Result<Option<Vec<u8>>> {
        Ok(self
            .db
            .query_row("SELECT value FROM kv WHERE key = ?1", [key], |r| r.get(0))
            .optional()?)
    }

    pub fn set_kv(&self, key: &str, value: &[u8]) -> Result<()> {
        self.db.execute(
            "INSERT INTO kv (key, value) VALUES (?1, ?2)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn del_kv(&self, key: &str) -> Result<()> {
        self.db.execute("DELETE FROM kv WHERE key = ?1", [key])?;
        Ok(())
    }

    /// How many messages there are, of any kind.
    pub fn count(&self) -> Result<u64> {
        let n: i64 = self.db.query_row("SELECT COUNT(*) FROM messages", [], |r| r.get(0))?;
        Ok(n as u64)
    }

    /// SQLite's own check of the whole file: "ok", or what's wrong.
    pub fn integrity(&self) -> Result<String> {
        Ok(self.db.query_row("PRAGMA integrity_check", [], |r| r.get(0))?)
    }

    /// The app's directory moved: every file path under `from` now lives under `to`. How many changed.
    pub fn move_paths(&self, from: &str, to: &str) -> Result<u64> {
        let n = self.db.execute(
            "UPDATE messages SET path = ?2 || substr(path, length(?1) + 1) WHERE substr(path, 1, length(?1)) = ?1",
            params![from, to],
        )?;
        Ok(n as u64)
    }

    pub fn get_str(&self, key: &str) -> Result<Option<String>> {
        Ok(self.get_kv(key)?.map(|v| String::from_utf8_lossy(&v).into_owned()))
    }

    /// Adds an outgoing item to the log/outbox.
    pub fn enqueue(&self, body: Body, path: Option<PathBuf>, expires_ms: Option<i64>) -> Result<Message> {
        let seq: i64 = self.db.query_row(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE from_me = 1",
            [],
            |r| r.get(0),
        )?;
        let item = Item { id: Uuid::new_v4(), seq: seq as u64, ts_ms: now_ms(), expires_ms, body };
        let msg = to_message(&item, true, path, State::Queued, true);
        self.drop_thread_for(&msg)?;
        self.insert(&msg)?;
        self.drop_old_views(&msg)?;
        Ok(msg)
    }

    /// Stores an item from the peer. Returns `(message, is_new)`.
    pub fn receive(&self, item: &Item) -> Result<(Message, bool)> {
        if let Some(m) = self.get(item.id)? {
            if !ghost_of(&m, item) {
                return Ok((m, false));
            }
            // A sender that misread its own stored drop resent it as a nameless file
            // (until 2026-10-04); now the real item is here, it replaces that row.
            self.db.execute("DELETE FROM messages WHERE id = ?1", [m.id.to_string()])?;
        }
        let state = match item.body {
            Body::File { .. } | Body::ChannelFile { .. } => State::Incoming,
            _ => State::Received,
        };
        let msg = to_message(item, false, None, state, false);
        self.drop_thread_for(&msg)?;
        self.insert(&msg)?;
        self.drop_old_views(&msg)?;
        Ok((msg, true))
    }

    /// A `drop` item removes the channel's items and views from before it (the drop itself is
    /// stored after, so it is acked and deduped like any item). One that lands late (the peer was
    /// offline, or too old to read it) leaves what its sender posted since: the channel was
    /// set up again after the drop.
    fn drop_thread_for(&self, m: &Message) -> Result<()> {
        if m.kind == "drop" {
            self.db.execute(
                "DELETE FROM messages WHERE kind IN ('app', 'view') AND channel = ?1 AND ts_ms <= ?2",
                params![m.channel, m.ts_ms],
            )?;
        }
        Ok(())
    }

    /// After storing a view: only the newest matters, so the same sender's other views of that
    /// channel go, whatever their state (a queued one is then never sent).
    fn drop_old_views(&self, m: &Message) -> Result<()> {
        if m.kind == "view" {
            self.db.execute(
                "DELETE FROM messages WHERE kind = 'view' AND channel = ?1 AND from_me = ?2 AND id != ?3",
                params![m.channel, m.from_me, m.id.to_string()],
            )?;
        }
        Ok(())
    }

    /// An app item from a local UI (the laptop's panel): stored as if the peer sent it, so the
    /// channel's client takes it from [`Store::app_pending`] like any other.
    pub fn app_local(&self, channel: &str, data: String) -> Result<Message> {
        let body = Body::App { channel: channel.into(), data, replace: false };
        let item = Item { id: Uuid::new_v4(), seq: 0, ts_ms: now_ms(), expires_ms: None, body };
        let msg = to_message(&item, false, None, State::Received, false);
        self.insert(&msg)?;
        Ok(msg)
    }

    fn insert(&self, m: &Message) -> Result<()> {
        self.db.execute(
            &format!(
                "INSERT INTO messages ({COLS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)"
            ),
            params![
                m.id.to_string(),
                m.seq as i64,
                m.from_me,
                m.ts_ms,
                m.expires_ms,
                m.kind,
                m.text,
                m.file_name,
                m.file_size.map(|s| s as i64),
                m.sha256.map(|h| h.to_vec()),
                m.path.as_ref().map(|p| p.to_string_lossy().into_owned()),
                m.state.as_str(),
                m.read,
                m.channel,
            ],
        )?;
        Ok(())
    }

    pub fn get(&self, id: Uuid) -> Result<Option<Message>> {
        Ok(self
            .db
            .query_row(&format!("SELECT {COLS} FROM messages WHERE id = ?1"), [id.to_string()], row)
            .optional()?)
    }

    /// Unacked items, oldest first. Items past their expiry are marked expired and returned
    /// separately so the UI can show it.
    pub fn outbox(&self) -> Result<(Vec<Message>, Vec<Message>)> {
        let now = now_ms();
        let mut stmt = self.db.prepare(&format!(
            "SELECT {COLS} FROM messages WHERE from_me = 1 AND state = 'queued' ORDER BY seq"
        ))?;
        let all = stmt.query_map([], row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        let (expired, live): (Vec<_>, Vec<_>) =
            all.into_iter().partition(|m| m.expires_ms.is_some_and(|e| e <= now));
        let expired = expired
            .into_iter()
            .map(|m| self.set_state(m.id, State::Expired))
            .collect::<Result<Vec<_>>>()?
            .into_iter()
            .flatten()
            .collect();
        Ok((live, expired))
    }

    pub fn set_state(&self, id: Uuid, state: State) -> Result<Option<Message>> {
        self.db.execute(
            "UPDATE messages SET state = ?2 WHERE id = ?1",
            params![id.to_string(), state.as_str()],
        )?;
        self.get(id)
    }

    /// Marks my item as delivered; `None` if it isn't mine or was already delivered. A file I was
    /// cancelling counts too: the ack means it arrived whole before the cancel did.
    pub fn ack(&self, id: Uuid) -> Result<Option<Message>> {
        let n = self.db.execute(
            "UPDATE messages SET state = 'delivered'
             WHERE id = ?1 AND from_me = 1 AND state IN ('queued', 'cancelling')",
            [id.to_string()],
        )?;
        if n == 0 { Ok(None) } else { self.get(id) }
    }

    /// Takes my queued file out of the outbox; `None` if it isn't one (already delivered, say).
    pub fn cancel(&self, id: Uuid) -> Result<Option<Message>> {
        let n = self.db.execute(
            "UPDATE messages SET state = 'cancelling'
             WHERE id = ?1 AND from_me = 1 AND kind = 'file' AND state = 'queued'",
            [id.to_string()],
        )?;
        if n == 0 { Ok(None) } else { self.get(id) }
    }

    /// My cancelled files the peer hasn't confirmed yet; each new link repeats their cancel.
    pub fn cancelling(&self) -> Result<Vec<Uuid>> {
        let mut stmt = self.db.prepare("SELECT id FROM messages WHERE state = 'cancelling'")?;
        let ids = stmt.query_map([], |r| r.get::<_, String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(ids.iter().filter_map(|i| Uuid::parse_str(i).ok()).collect())
    }

    #[cfg(test)]
    pub fn clear_path(&self, id: Uuid) -> Result<()> {
        self.db.execute("UPDATE messages SET path = NULL WHERE id = ?1", [id.to_string()])?;
        Ok(())
    }

    /// A file of the peer's arrived whole at `path`: marked received, unless it was cancelled
    /// meanwhile (`None`; the cancel was already confirmed, so the file must go).
    pub fn received(&self, id: Uuid, path: &Path) -> Result<Option<Message>> {
        let n = self.db.execute(
            "UPDATE messages SET state = 'received', path = ?2 WHERE id = ?1 AND from_me = 0 AND state = 'incoming'",
            params![id.to_string(), path.to_string_lossy()],
        )?;
        if n == 0 { Ok(None) } else { self.get(id) }
    }

    pub fn set_path(&self, id: Uuid, path: &Path) -> Result<Option<Message>> {
        self.db.execute(
            "UPDATE messages SET path = ?2 WHERE id = ?1",
            params![id.to_string(), path.to_string_lossy()],
        )?;
        self.get(id)
    }

    /// The most recent `limit` messages, oldest first.
    pub fn recent(&self, limit: usize) -> Result<Vec<Message>> {
        let mut stmt = self.db.prepare(&format!(
            "SELECT * FROM (SELECT {COLS} FROM messages WHERE {CHAT} ORDER BY ts_ms DESC, seq DESC LIMIT ?1)
             ORDER BY ts_ms, seq"
        ))?;
        Ok(stmt.query_map([limit as i64], row)?.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn unread(&self) -> Result<u64> {
        Ok(self.db.query_row(
            &format!("SELECT COUNT(*) FROM messages WHERE from_me = 0 AND read = 0 AND {CHAT}"),
            [],
            |r| r.get::<_, i64>(0),
        )? as u64)
    }

    pub fn mark_read(&self) -> Result<()> {
        self.db.execute(&format!("UPDATE messages SET read = 1 WHERE read = 0 AND {CHAT}"), [])?;
        Ok(())
    }

    /// The channel's items (not views) from the peer that its client hasn't taken yet, oldest first.
    pub fn app_pending(&self, channel: &str) -> Result<Vec<Message>> {
        let mut stmt = self.db.prepare(&format!(
            "SELECT {COLS} FROM messages WHERE kind = 'app' AND from_me = 0 AND read = 0 AND channel = ?1
             ORDER BY ts_ms, seq"
        ))?;
        Ok(stmt.query_map([channel], row)?.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// The channel's client took this item.
    pub fn app_done(&self, id: Uuid) -> Result<()> {
        self.db.execute(
            "UPDATE messages SET read = 1 WHERE id = ?1 AND kind = 'app' AND from_me = 0",
            [id.to_string()],
        )?;
        Ok(())
    }

    /// The newest view of the channel, from either side.
    pub fn app_view(&self, channel: &str) -> Result<Option<Message>> {
        Ok(self
            .db
            .query_row(
                &format!(
                    "SELECT {COLS} FROM messages WHERE kind = 'view' AND channel = ?1
                     ORDER BY ts_ms DESC, rowid DESC LIMIT 1"
                ),
                [channel],
                row,
            )
            .optional()?)
    }

    /// The channel's last `limit` items (not views), both ways, oldest first. Ties within a
    /// millisecond go by arrival (the two sides' `seq`s don't compare).
    pub fn app_history(&self, channel: &str, limit: usize) -> Result<Vec<Message>> {
        let mut stmt = self.db.prepare(&format!(
            "SELECT * FROM (SELECT {COLS}, rowid AS r FROM messages WHERE kind = 'app' AND channel = ?1
                            ORDER BY ts_ms DESC, r DESC LIMIT ?2)
             ORDER BY ts_ms, r"
        ))?;
        Ok(stmt.query_map(params![channel, limit as i64], row)?.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// The channel's last `limit` files (both ways, any state), oldest first.
    pub fn app_files(&self, channel: &str, limit: usize) -> Result<Vec<Message>> {
        let mut stmt = self.db.prepare(&format!(
            "SELECT * FROM (SELECT {COLS}, rowid AS r FROM messages WHERE kind = 'file' AND channel = ?1
                            ORDER BY ts_ms DESC, r DESC LIMIT ?2)
             ORDER BY ts_ms, r"
        ))?;
        Ok(stmt.query_map(params![channel, limit as i64], row)?.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Every channel that has an item or a view, by name.
    pub fn app_channels(&self) -> Result<Vec<String>> {
        let mut stmt = self.db.prepare(
            "SELECT DISTINCT channel FROM messages WHERE kind IN ('app', 'view') AND channel IS NOT NULL
             ORDER BY channel",
        )?;
        Ok(stmt.query_map([], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?)
    }
}

/// A row stored from a drop that its sender resent as a file with no name and no size (a laptop
/// before 2026-10-04 read stored drops back as files), while `item` is that id's real body.
fn ghost_of(m: &Message, item: &Item) -> bool {
    !m.from_me
        && m.kind == "file"
        && m.file_name.as_deref().unwrap_or("").is_empty()
        && m.file_size.unwrap_or(0) == 0
        && !matches!(item.body, Body::File { .. } | Body::ChannelFile { .. })
}

fn to_message(item: &Item, from_me: bool, path: Option<PathBuf>, state: State, read: bool) -> Message {
    let mut channel = None;
    let (kind, text, file_name, file_size, sha256) = match &item.body {
        Body::Text(t) => ("text", Some(t.clone()), None, None, None),
        Body::Ping(t) => ("ping", Some(t.clone()), None, None, None),
        Body::Ring => ("ring", None, None, None, None),
        Body::File { name, size, sha256 } => ("file", None, Some(name.clone()), Some(*size), Some(*sha256)),
        Body::ChannelFile { channel: c, name, size, sha256 } => {
            channel = Some(c.clone());
            ("file", None, Some(name.clone()), Some(*size), Some(*sha256))
        }
        Body::DropChannel(c) => {
            channel = Some(c.clone());
            ("drop", None, None, None, None)
        }
        Body::App { channel: c, data, replace } => {
            channel = Some(c.clone());
            (if *replace { "view" } else { "app" }, Some(data.clone()), None, None, None)
        }
    };
    Message {
        id: item.id,
        seq: item.seq,
        from_me,
        ts_ms: item.ts_ms,
        expires_ms: item.expires_ms,
        kind,
        text,
        file_name,
        file_size,
        sha256,
        path,
        state,
        read,
        channel,
    }
}

fn row(r: &rusqlite::Row<'_>) -> rusqlite::Result<Message> {
    let id: String = r.get(0)?;
    let kind: String = r.get(5)?;
    let state: String = r.get(11)?;
    let sha: Option<Vec<u8>> = r.get(9)?;
    let conv = |e: anyhow::Error| {
        rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, e.into())
    };
    Ok(Message {
        id: Uuid::parse_str(&id).map_err(|e| conv(e.into()))?,
        seq: r.get::<_, i64>(1)? as u64,
        from_me: r.get(2)?,
        ts_ms: r.get(3)?,
        expires_ms: r.get(4)?,
        kind: match kind.as_str() {
            "text" => "text",
            "ping" => "ping",
            "ring" => "ring",
            "app" => "app",
            "view" => "view",
            "drop" => "drop",
            _ => "file",
        },
        text: r.get(6)?,
        file_name: r.get(7)?,
        file_size: r.get::<_, Option<i64>>(8)?.map(|s| s as u64),
        sha256: sha.and_then(|v| v.try_into().ok()),
        path: r.get::<_, Option<String>>(10)?.map(PathBuf::from),
        state: State::parse(&state).map_err(conv)?,
        read: r.get(12)?,
        channel: r.get(13)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outbox_holds_until_acked_and_receive_dedupes() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        let m1 = a.enqueue(Body::Text("one".into()), None, None).unwrap();
        let m2 = a.enqueue(Body::Text("two".into()), None, None).unwrap();
        assert_eq!((m1.seq, m2.seq), (1, 2));

        let (out, _) = a.outbox().unwrap();
        assert_eq!(out.len(), 2);
        // Delivered, then the ack is lost: the item is resent and must not duplicate.
        assert!(b.receive(&out[0].item()).unwrap().1);
        assert!(!b.receive(&out[0].item()).unwrap().1);
        assert_eq!(b.recent(10).unwrap().len(), 1);
        assert_eq!(b.unread().unwrap(), 1);

        assert_eq!(a.ack(m1.id).unwrap().unwrap().state, State::Delivered);
        assert!(a.ack(m1.id).unwrap().is_none());
        let (out, _) = a.outbox().unwrap();
        assert_eq!(out.iter().map(|m| m.id).collect::<Vec<_>>(), vec![m2.id]);
    }

    #[test]
    fn a_file_cancelled_while_finishing_is_not_received() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        let body = || Body::File { name: "a.bin".into(), size: 3, sha256: [9; 32] };
        let (m1, m2) = (a.enqueue(body(), None, None).unwrap(), a.enqueue(body(), None, None).unwrap());
        let (out, _) = a.outbox().unwrap();
        for m in &out {
            assert_eq!(b.receive(&m.item()).unwrap().0.state, State::Incoming);
        }
        let got = b.received(m1.id, Path::new("/dl/a.bin")).unwrap().unwrap();
        assert_eq!((got.state, got.path.as_deref()), (State::Received, Some(Path::new("/dl/a.bin"))));
        // The cancel came first: the finish doesn't undo it.
        b.set_state(m2.id, State::Cancelled).unwrap();
        assert!(b.received(m2.id, Path::new("/dl/a (1).bin")).unwrap().is_none());
        assert_eq!(b.get(m2.id).unwrap().unwrap().state, State::Cancelled);
    }

    fn app(d: &str, replace: bool) -> Body {
        Body::App { channel: "teen".into(), data: d.into(), replace }
    }

    #[test]
    fn a_drop_deletes_the_thread_on_both_sides() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        a.enqueue(Body::Text("hi".into()), None, None).unwrap();
        a.enqueue(app("post", false), None, None).unwrap();
        a.enqueue(app("view", true), None, None).unwrap();
        for o in a.outbox().unwrap().0 {
            b.receive(&o.item()).unwrap();
        }
        let other = Body::App { channel: "keep".into(), data: "x".into(), replace: false };
        a.enqueue(other, None, None).unwrap();
        let drop = a.enqueue(Body::DropChannel("teen".into()), None, None).unwrap();
        assert_eq!(a.app_channels().unwrap(), vec!["keep".to_string()]);
        let (new, fresh) = b.receive(&drop.item()).unwrap();
        assert!(fresh);
        assert_eq!(new.body(), Body::DropChannel("teen".into()));
        assert!(b.app_channels().unwrap().is_empty());
        assert_eq!(b.recent(10).unwrap().len(), 1, "the drop isn't chat");
        assert!(!b.receive(&drop.item()).unwrap().1, "deduped, so it is acked once");
    }

    #[test]
    fn a_stored_drop_reads_back_as_a_drop() {
        let a = Store::open_in_memory().unwrap();
        a.enqueue(Body::DropChannel("teen".into()), None, None).unwrap();
        let (out, _) = a.outbox().unwrap();
        assert_eq!(out[0].kind, "drop");
        assert_eq!(out[0].item().body, Body::DropChannel("teen".into()), "not a nameless file");
    }

    #[test]
    fn a_late_drop_keeps_what_was_posted_after_it() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        let old = a.enqueue(app("old view", true), None, None).unwrap();
        b.receive(&old.item()).unwrap();
        let drop = a.enqueue(Body::DropChannel("teen".into()), None, None).unwrap();
        // The peer missed the drop (offline, or too old to read it); the channel was set up again.
        let mut fresh = a.enqueue(app("new view", true), None, None).unwrap().item();
        fresh.ts_ms = drop.ts_ms + 1;
        b.receive(&fresh).unwrap();
        b.receive(&drop.item()).unwrap();
        let v = b.app_view("teen").unwrap().expect("the newer view survives");
        assert_eq!(v.id, fresh.id);
    }

    #[test]
    fn app_items_stay_out_of_the_chat() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        a.enqueue(Body::Text("hi".into()), None, None).unwrap();
        let post = a.enqueue(app("post", false), None, None).unwrap();
        a.enqueue(app("view", true), None, None).unwrap();
        let (out, _) = a.outbox().unwrap();
        assert_eq!(out.len(), 3);
        for o in &out {
            b.receive(&o.item()).unwrap();
        }
        assert_eq!(b.recent(10).unwrap().len(), 1);
        assert_eq!(b.unread().unwrap(), 1);
        b.mark_read().unwrap();
        let pending = b.app_pending("teen").unwrap();
        assert_eq!(pending.len(), 1, "views aren't pending");
        assert_eq!(pending[0].body(), app("post", false));
        assert_eq!(pending[0].id, post.id);
        b.app_done(post.id).unwrap();
        assert!(b.app_pending("teen").unwrap().is_empty());
        assert_eq!(b.app_view("teen").unwrap().unwrap().body(), app("view", true));
        assert_eq!(b.app_channels().unwrap(), vec!["teen".to_string()]);
    }

    fn views(s: &Store) -> i64 {
        s.db.query_row("SELECT COUNT(*) FROM messages WHERE kind = 'view'", [], |r| r.get(0)).unwrap()
    }

    #[test]
    fn a_view_replaces_the_older_ones_on_both_sides() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        let v1 = a.enqueue(app("1", true), None, None).unwrap();
        b.receive(&v1.item()).unwrap();
        a.ack(v1.id).unwrap();
        a.enqueue(app("2", true), None, None).unwrap();
        let v3 = a.enqueue(app("3", true), None, None).unwrap();
        let other = Body::App { channel: "other".into(), data: "x".into(), replace: true };
        a.enqueue(other, None, None).unwrap();
        assert_eq!(views(&a), 2, "one per channel, delivered or queued");
        let (out, _) = a.outbox().unwrap();
        assert_eq!(out.iter().filter(|m| m.channel.as_deref() == Some("teen")).count(), 1);

        // The peer's own view of the channel stays: only the same sender's are replaced.
        let mine = b.enqueue(app("b", true), None, None).unwrap();
        b.receive(&v3.item()).unwrap();
        assert_eq!(views(&b), 2);
        assert!(b.get(v1.id).unwrap().is_none());
        assert!(b.get(mine.id).unwrap().is_some());
    }

    #[test]
    fn local_items_are_pending_and_history_is_both_ways() {
        let a = Store::open_in_memory().unwrap();
        let mine = a.enqueue(app("post", false), None, None).unwrap();
        a.enqueue(app("view", true), None, None).unwrap();
        let local = a.app_local("teen", "tap".into()).unwrap();
        assert!(!local.from_me);
        assert_eq!(a.app_pending("teen").unwrap().iter().map(|m| m.id).collect::<Vec<_>>(), vec![local.id]);
        let h = a.app_history("teen", 10).unwrap();
        assert_eq!(h.iter().map(|m| m.id).collect::<Vec<_>>(), vec![mine.id, local.id]);
        assert_eq!(a.app_history("teen", 1).unwrap()[0].id, local.id, "the newest");
        assert_eq!(a.unread().unwrap(), 0);
    }

    #[test]
    fn old_done_app_items_are_pruned() {
        let a = Store::open_in_memory().unwrap();
        let old = now_ms() - APP_KEEP_MS - 1;
        let at = |m: Message| {
            a.db.execute("UPDATE messages SET ts_ms = ?2 WHERE id = ?1", params![m.id.to_string(), old]).unwrap();
            m.id
        };
        let delivered = at(a.enqueue(app("d", false), None, None).unwrap());
        a.ack(delivered).unwrap();
        let queued = at(a.enqueue(app("q", false), None, None).unwrap());
        let taken = at(a.app_local("teen", "t".into()).unwrap());
        a.app_done(taken).unwrap();
        let waiting = at(a.app_local("teen", "w".into()).unwrap());
        let view = at(a.enqueue(app("v", true), None, None).unwrap());
        let chat = at(a.enqueue(Body::Text("hi".into()), None, None).unwrap());
        a.ack(view).unwrap();
        a.ack(chat).unwrap();
        let recent = a.enqueue(app("r", false), None, None).unwrap();
        a.ack(recent.id).unwrap();

        assert_eq!(a.prune_apps(now_ms() - APP_KEEP_MS).unwrap(), 2);
        for id in [queued, waiting, view, chat, recent.id] {
            assert!(a.get(id).unwrap().is_some());
        }
        assert!(a.get(delivered).unwrap().is_none());
        assert!(a.get(taken).unwrap().is_none());
    }

    #[test]
    fn stale_items_expire_instead_of_sending() {
        let a = Store::open_in_memory().unwrap();
        a.enqueue(Body::Ring, None, Some(now_ms() - 1)).unwrap();
        let (live, expired) = a.outbox().unwrap();
        assert!(live.is_empty());
        assert_eq!(expired[0].state, State::Expired);
    }
}
