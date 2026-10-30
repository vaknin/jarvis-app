//! CLI ↔ daemon protocol over the unix socket: one JSON request line, then one JSON reply line
//! (`{"ok":true,"data":…}` or `{"ok":false,"error":"…"}`). `watch` instead streams one event per line.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines},
    net::{
        UnixStream,
        unix::{OwnedReadHalf, OwnedWriteHalf},
    },
};

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
pub enum Request {
    Status,
    PairOffer,
    Pair { code: String },
    Unpair,
    Send { paths: Vec<PathBuf> },
    /// Files to the phone on an app channel (`jarvis-link send --channel`), queued like `Send`.
    SendChannelFile { channel: String, paths: Vec<PathBuf> },
    /// Stop a file I'm sending.
    Cancel { id: String },
    Msg { text: String },
    Ping { text: String },
    Ring,
    StopRing,
    /// `fresh`: wake the phone if needed, wait for its list, and keep the link up for a while.
    Notifications {
        #[serde(default)]
        fresh: bool,
    },
    Json { limit: usize },
    MarkRead,
    Connect,
    /// Where the phone's adb listens, asked over the link (waking the phone): the phone app's
    /// answer as JSON. `enable`: turn Wireless debugging on first, if the app may.
    Adb {
        #[serde(default)]
        enable: bool,
    },
    /// Node events as JSON lines, plus `{"type":"app","channel":…,"view":bool}` when a
    /// channel's view or thread changed (no data: re-read with `channels` or `thread`).
    Watch,
    /// To the phone on an app channel: queued (with `replace`, it supersedes my undelivered
    /// ones), or `live` (dropped when the phone isn't connected; the reply says whether it went).
    AppSend {
        channel: String,
        data: String,
        #[serde(default)]
        live: bool,
        #[serde(default)]
        replace: bool,
    },
    /// Become the channel's client: a stream of `{"id":…,"data":…}` (queued; reply
    /// `{"done":"<id>"}` once handled, or it comes again next time) and `{"data":…}` (live) lines.
    AppSubscribe { channel: String },
    /// An action from a laptop UI (the panel, `jarvis-link action`), routed to the channel's app as if
    /// the phone sent it. `data` is a JSON object; missing `from`, `uid` and `ts` are filled in.
    AppAction { channel: String, data: String },
    /// Every channel (manifests, then channels with items but no manifest), each with its newest
    /// view and badge.
    Channels,
    /// Reads the manifests again (publishing `_channels` if they changed), then as `Channels`.
    ChannelsReload,
    /// A command for a built-in `list` channel (`jarvis-link list`); the reply depends on the op.
    List { channel: String, op: crate::lists::ListOp },
    /// Deletes a channel's thread (every item and view) here and, once delivered, on the phone.
    DropThread { channel: String },
    /// A channel's items both ways (posts, replies, actions), oldest first.
    Thread {
        channel: String,
        #[serde(default = "thread_limit")]
        limit: usize,
    },
}

fn thread_limit() -> usize {
    100
}

/// What a channel client writes back on its subscription.
#[derive(Debug, Serialize, Deserialize)]
pub struct AppDone {
    pub done: String,
}

pub fn default_socket() -> PathBuf {
    if let Some(p) = std::env::var_os("JARVIS_SOCKET") {
        return p.into();
    }
    let dir = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    dir.join(jarvis_core::compat::SOCKET)
}

async fn open(sock: &Path, req: &Request) -> Result<(Lines<BufReader<OwnedReadHalf>>, OwnedWriteHalf)> {
    let stream = UnixStream::connect(sock).await.with_context(|| {
        format!(
            "the jarvis daemon isn't running ({} not found); start it with `systemctl --user start jarvis-link`",
            sock.display()
        )
    })?;
    let (r, mut w) = stream.into_split();
    let mut line = serde_json::to_vec(req)?;
    line.push(b'\n');
    w.write_all(&line).await?;
    Ok((BufReader::new(r).lines(), w))
}

pub async fn call(sock: &Path, req: &Request) -> Result<Value> {
    let (mut lines, _w) = open(sock, req).await?;
    let line = lines.next_line().await?.context("daemon closed the connection")?;
    let mut reply: Value = serde_json::from_str(&line)?;
    if reply["ok"] == Value::Bool(true) {
        Ok(reply["data"].take())
    } else {
        bail!("{}", reply["error"].as_str().unwrap_or("daemon error"))
    }
}

/// Event lines, as JSON strings, until the daemon goes away. Keep the write half alive with them.
pub async fn watch(sock: &Path) -> Result<(Lines<BufReader<OwnedReadHalf>>, OwnedWriteHalf)> {
    open(sock, &Request::Watch).await
}
