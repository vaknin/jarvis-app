//! Wire format: every frame on the control stream is a big-endian u32 length followed by a
//! postcard-encoded [`Frame`]. File bytes travel on their own uni streams, each starting with a
//! length-prefixed [`FileHeader`].

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use uuid::Uuid;

/// ALPN for the paired data connection.
pub const ALPN: &[u8] = crate::compat::ALPN;
/// ALPN used once, while pairing.
pub const PAIR_ALPN: &[u8] = crate::compat::PAIR_ALPN;

/// Largest control frame we accept. Notification snapshots are the big ones.
const MAX_FRAME: u32 = 4 << 20;

/// Something one device wants the other to get, even if it is offline right now.
/// Items are stored in the sender's outbox until the receiver acks them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Item {
    pub id: Uuid,
    pub seq: u64,
    pub ts_ms: i64,
    /// Drop instead of delivering after this time (rings go stale fast).
    pub expires_ms: Option<i64>,
    pub body: Body,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Body {
    Text(String),
    File { name: String, size: u64, sha256: [u8; 32] },
    /// A one-line alert ("Suspend blocked: …"); shown as a notification, kept in the chat.
    Ping(String),
    /// Make the phone ring until stopped.
    Ring,
    /// For an app built on top of Jarvis (the teen channel, say), not the chat. `data` is the
    /// app's own JSON; Jarvis only carries it. With `replace` it is the channel's view (its whole
    /// state): storing it, on either side, drops that sender's older views of the channel.
    App { channel: String, data: String, replace: bool },
    /// Delete a channel's thread: both sides drop every item and view of it. Silent (no wake).
    DropChannel(String),
    /// A file for an app channel (a photo sent to dibs), not the chat: it travels like [`Body::File`]
    /// (resume, sha256, Ack, cancel), but the receiver keeps it under `<state>/channels/<channel>/`,
    /// out of the chat and Downloads. Appended last: an older peer skips the frame and the sender
    /// keeps the item queued until it updates.
    ChannelFile { channel: String, name: String, size: u64, sha256: [u8; 32] },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MediaState {
    pub player: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// 0–100, `None` when the phone can't change it.
    pub volume: Option<u8>,
    pub can_seek: bool,
    pub can_next: bool,
    pub can_previous: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum MediaCmd {
    Play,
    Pause,
    PlayPause,
    Next,
    Previous,
    Seek { position_ms: i64 },
    Volume(u8),
}

/// One active notification on the phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PhoneNotif {
    pub key: String,
    pub app: String,
    pub title: String,
    pub text: String,
    pub posted_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Frame {
    Hello { name: String },
    Item(Item),
    Ack { id: Uuid },
    /// Ask the sender to stream a file from `offset` (resume).
    FileWant { id: Uuid, offset: u64 },
    /// Live state, never queued. `None` = nothing playing.
    Media(Option<MediaState>),
    MediaCmd(MediaCmd),
    Notifs(Vec<PhoneNotif>),
    StopRing,
    /// Phone → laptop: FCM registration token, used to wake the phone when it isn't connected.
    PushToken(String),
    /// Phone → laptop: whether the phone wants to stay connected (media playing, chat open).
    /// When false, either side closes the connection after the idle timeout.
    StayConnected(bool),
    /// A file item, either way: its sender stopped it, or its receiver confirms it dropped it.
    Cancel { id: Uuid },
    /// An app channel's live message (progress, say), never queued.
    App { channel: String, data: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileHeader {
    pub id: Uuid,
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PairRequest {
    Hello { token: [u8; 32], name: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PairReply {
    Accepted { name: String },
    Rejected,
}

pub async fn write_msg<T: Serialize>(w: &mut (impl AsyncWrite + Unpin), msg: &T) -> Result<()> {
    let bytes = postcard::to_stdvec(msg)?;
    w.write_all(&(bytes.len() as u32).to_be_bytes()).await?;
    w.write_all(&bytes).await?;
    Ok(())
}

/// Reads one message; `Ok(None)` on a clean end of stream between frames.
pub async fn read_msg<T: for<'de> Deserialize<'de>>(
    r: &mut (impl AsyncRead + Unpin),
) -> Result<Option<T>> {
    match read_raw(r).await? {
        Some(buf) => Ok(Some(postcard::from_bytes(&buf)?)),
        None => Ok(None),
    }
}

/// Reads one control frame. The inner error is a frame that arrived whole but doesn't decode (a
/// newer peer's): the stream is still in step, so the caller can skip it.
pub async fn read_frame(
    r: &mut (impl AsyncRead + Unpin),
) -> Result<Option<std::result::Result<Frame, postcard::Error>>> {
    Ok(read_raw(r).await?.map(|buf| postcard::from_bytes(&buf)))
}

async fn read_raw(r: &mut (impl AsyncRead + Unpin)) -> Result<Option<Vec<u8>>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }
    let len = u32::from_be_bytes(len);
    if len > MAX_FRAME {
        bail!("frame of {len} bytes is too large");
    }
    let mut buf = vec![0u8; len as usize];
    r.read_exact(&mut buf).await.context("truncated frame")?;
    Ok(Some(buf))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_frame_that_does_not_decode_is_skipped_and_the_next_one_reads() {
        let mut buf = Vec::new();
        write_msg(&mut buf, &Frame::StopRing).await.unwrap();
        // A newer peer's frame: an enum variant this build doesn't have.
        buf.extend_from_slice(&4u32.to_be_bytes());
        buf.extend_from_slice(&[200, 1, 2, 3]);
        write_msg(&mut buf, &Frame::Ack { id: Uuid::nil() }).await.unwrap();
        let mut r = buf.as_slice();
        assert_eq!(read_frame(&mut r).await.unwrap().unwrap().unwrap(), Frame::StopRing);
        assert!(read_frame(&mut r).await.unwrap().unwrap().is_err());
        assert_eq!(read_frame(&mut r).await.unwrap().unwrap().unwrap(), Frame::Ack { id: Uuid::nil() });
        assert!(read_frame(&mut r).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn frames_round_trip() {
        let frames = vec![
            Frame::Hello { name: "Laptop".into() },
            Frame::Item(Item {
                id: Uuid::new_v4(),
                seq: 7,
                ts_ms: 1,
                expires_ms: None,
                body: Body::File { name: "a.png".into(), size: 3, sha256: [9; 32] },
            }),
            Frame::MediaCmd(MediaCmd::Seek { position_ms: 1234 }),
            Frame::Cancel { id: Uuid::new_v4() },
            Frame::Item(Item {
                id: Uuid::new_v4(),
                seq: 8,
                ts_ms: 2,
                expires_ms: None,
                body: Body::App { channel: "teen".into(), data: "{\"v\":1}".into(), replace: true },
            }),
            Frame::App { channel: "teen".into(), data: "{}".into() },
        ];
        let mut buf = Vec::new();
        for f in &frames {
            write_msg(&mut buf, f).await.unwrap();
        }
        let mut r = buf.as_slice();
        for f in &frames {
            assert_eq!(read_msg::<Frame>(&mut r).await.unwrap().as_ref(), Some(f));
        }
        assert_eq!(read_msg::<Frame>(&mut r).await.unwrap(), None);
    }
}
