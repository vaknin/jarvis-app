//! The call against a fake socket (channels standing in for the WebSocket), on tokio's paused
//! clock, plus one exchange over a real WebSocket on loopback.

use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use anyhow::anyhow;
use futures_util::{SinkExt, StreamExt};
use jarvis_voice::{Call, Connector, Earcon, Events, Frame, Status, Timing, call::Rx, call::Tx, websocket};
use tokio::sync::mpsc;

/// The server's side of one fake socket.
struct Server {
    from_phone: mpsc::UnboundedReceiver<Frame>,
    to_phone: mpsc::UnboundedSender<anyhow::Result<Frame>>,
}

impl Server {
    /// The next text frame from the phone, skipping audio and pings.
    async fn text(&mut self) -> String {
        loop {
            if let Frame::Text(s) = self.from_phone.recv().await.expect("phone closed") { return s }
        }
    }

    fn send(&self, s: &str) {
        let _ = self.to_phone.send(Ok(Frame::Text(s.into())));
    }

    /// Jarvis's sentence audio: samples `from..to` of a ramp (sample i is i), so a gap or a repeat shows.
    fn ramp(&self, from: i16, to: i16) {
        let b = (from..to).flat_map(|v| v.to_le_bytes()).collect();
        let _ = self.to_phone.send(Ok(Frame::Binary(b)));
    }

    fn audio(&self, samples: usize, value: i16) {
        let b = (0..samples).flat_map(|_| value.to_le_bytes()).collect();
        let _ = self.to_phone.send(Ok(Frame::Binary(b)));
    }
}

/// A connector whose every connect hands the server side to the test; `refuse` makes connects fail.
fn fake() -> (Connector, mpsc::UnboundedReceiver<Server>, Arc<AtomicBool>) {
    fake_slow(Duration::ZERO)
}

/// [`fake`], with each connect taking `slow` (as a fresh iroh dial does) before the server sees it.
fn fake_slow(slow: Duration) -> (Connector, mpsc::UnboundedReceiver<Server>, Arc<AtomicBool>) {
    let (accept_tx, accept) = mpsc::unbounded_channel();
    let refuse = Arc::new(AtomicBool::new(false));
    let r = refuse.clone();
    let conn: Connector = Arc::new(move || {
        let accept_tx = accept_tx.clone();
        let refuse = r.load(Ordering::SeqCst);
        Box::pin(async move {
            if refuse {
                return Err(anyhow!("refused"));
            }
            tokio::time::sleep(slow).await;
            let (up_tx, from_phone) = mpsc::unbounded_channel::<Frame>();
            let (to_phone, mut down_rx) = mpsc::unbounded_channel::<anyhow::Result<Frame>>();
            let _ = accept_tx.send(Server { from_phone, to_phone });
            let tx = futures_util::sink::unfold(up_tx, |s, f: Frame| async move {
                s.send(f).map_err(|_| anyhow!("server gone"))?;
                Ok::<_, anyhow::Error>(s)
            });
            let rx = futures_util::stream::poll_fn(move |cx| down_rx.poll_recv(cx));
            Ok((Box::pin(tx) as Tx, Box::pin(rx) as Rx))
        })
    });
    (conn, accept, refuse)
}

#[derive(Default)]
struct Log(Mutex<Vec<String>>);

impl Log {
    fn count(&self, s: &str) -> usize {
        self.0.lock().unwrap().iter().filter(|l| *l == s).count()
    }
    fn has(&self, s: &str) -> bool {
        self.0.lock().unwrap().iter().any(|l| l == s)
    }
    fn statuses(&self) -> Vec<String> {
        self.0.lock().unwrap().iter().filter(|l| l.starts_with("status ")).cloned().collect()
    }
}

impl Events for Log {
    fn status(&self, s: Status) {
        self.0.lock().unwrap().push(format!("status {s:?}"));
    }
    fn text(&self, who: &str, text: &str) {
        self.0.lock().unwrap().push(format!("text {who}: {text}"));
    }
    fn error(&self, text: &str) {
        self.0.lock().unwrap().push(format!("error {text}"));
    }
    fn flush(&self) {
        self.0.lock().unwrap().push("flush".into());
    }
    fn paused(&self, on: bool) {
        self.0.lock().unwrap().push(format!("paused {on}"));
    }
    fn voice_muted(&self, on: bool) {
        self.0.lock().unwrap().push(format!("voice muted {on}"));
    }
    fn stt_open(&self, id: u64) {
        self.0.lock().unwrap().push(format!("stt open {id}"));
    }
    fn stt_audio(&self, id: u64, pcm: &[u8]) {
        self.0.lock().unwrap().push(format!("stt audio {id} {}", pcm.len()));
    }
    fn stt_close(&self, id: u64) {
        self.0.lock().unwrap().push(format!("stt close {id}"));
    }
    fn kick(&self) {
        self.0.lock().unwrap().push("kick".into());
    }
    fn resumed(&self) {
        self.0.lock().unwrap().push("resumed".into());
    }
    fn joined(&self, id: &str) {
        self.0.lock().unwrap().push(format!("joined {id}"));
    }
    fn hangup_owed(&self, id: &str) {
        self.0.lock().unwrap().push(format!("owed {id}"));
    }
}

async fn wait_for(log: &Log, s: &str) {
    for _ in 0..60_000 {
        if log.has(s) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("never got {s:?}; log: {:?}", log.0.lock().unwrap());
}

/// Starts a call and answers its hello with `ready` id c1.
async fn up() -> (Call, Server, mpsc::UnboundedReceiver<Server>, Arc<AtomicBool>, Arc<Log>) {
    let (conn, mut accept, refuse) = fake();
    let log = Arc::new(Log::default());
    let call = Call::start(conn, log.clone(), Timing::default());
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":null}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    (call, s, accept, refuse, log)
}

fn pcm_values(b: &[u8]) -> Vec<i16> {
    b.as_chunks::<2>().0.iter().map(|c| i16::from_le_bytes([c[0], c[1]])).collect()
}

#[tokio::test(start_paused = true)]
async fn connects_plays_the_earcon_and_streams_the_mic() {
    let (call, mut s, _a, _r, log) = up().await;
    assert_eq!(log.statuses(), vec!["status Connecting", "status Listening"]);
    let earcon = Earcon::Connected.samples();
    assert_eq!(pcm_values(&call.pull_audio(earcon.len())), earcon);

    call.send_audio(vec![1; 640]);
    loop {
        if let Frame::Binary(b) = s.from_phone.recv().await.unwrap() {
            assert_eq!(b, vec![1; 640]);
            break;
        }
    }
}

#[tokio::test(start_paused = true)]
async fn the_meter_reads_the_mic_as_it_went_out() {
    let (call, _s, _a, _r, _log) = up().await;
    let frame: Vec<u8> = (0..320).flat_map(|i| (if i % 2 == 0 { 400i16 } else { -400i16 }).to_le_bytes()).collect();
    call.send_audio(frame.clone());
    assert!(call.mic_level() > 0.0, "{}", call.mic_level());

    call.set_muted(true);
    call.send_audio(frame);
    assert_eq!(call.mic_level(), 0.0);
}

#[tokio::test(start_paused = true)]
async fn replies_play_after_the_jitter_target_and_flush_drops_them() {
    let (call, s, _a, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"state","state":"speaking"}"#);
    s.audio(16_000, 300); // a second of Jarvis
    wait_for(&log, "status Speaking").await;
    while call.buffered_ms() < 1000 {
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 300));
    s.send(r#"{"type":"flush"}"#);
    wait_for(&log, "flush").await;
    assert_eq!(call.buffered_ms(), 0);
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 0));
}

#[tokio::test(start_paused = true)]
async fn text_state_and_error_frames_reach_the_app() {
    let (call, s, _a, _r, log) = up().await;
    s.send(r#"{"type":"text","who":"aviv","text":"what's the weather"}"#);
    s.send(r#"{"type":"state","state":"thinking"}"#);
    s.send(r#"{"type":"error","text":"tts slow"}"#);
    s.send(r#"{"type":"whatever"}"#);
    wait_for(&log, "error tts slow").await;
    assert!(log.has("text aviv: what's the weather"));
    assert!(log.has("status Thinking"));
    // Connected, then nothing: a turn makes no sound.
    let n = Earcon::Connected.samples().len();
    call.pull_audio(n);
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 0));
}

#[tokio::test(start_paused = true)]
async fn mute_tells_the_server_and_stops_the_mic() {
    let (call, mut s, _a, _r, _log) = up().await;
    call.set_muted(true);
    assert_eq!(s.text().await, r#"{"type":"mute","on":true}"#);
    call.send_audio(vec![2; 640]);
    call.set_muted(false);
    assert_eq!(s.text().await, r#"{"type":"mute","on":false}"#);
    call.send_audio(vec![3; 640]);
    loop {
        if let Frame::Binary(b) = s.from_phone.recv().await.unwrap() {
            assert_eq!(b, vec![3; 640], "the muted frame never went");
            break;
        }
    }
}

#[tokio::test(start_paused = true)]
async fn hangup_sends_hangup_and_ends_with_the_earcon() {
    let (call, mut s, _a, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    call.hangup();
    assert_eq!(s.text().await, r#"{"type":"hangup"}"#);
    // Ended at once, before the server has closed: the screen and the earcon don't wait for it.
    tokio::time::timeout(Duration::from_millis(100), wait_for(&log, r#"status Ended("hung up")"#)).await.expect("ended at once");
    let _ = s.to_phone.send(Ok(Frame::Close));
    let mut n = 0;
    loop {
        let b = call.pull_audio(320);
        if b.is_empty() {
            break;
        }
        n += b.len() / 2;
    }
    assert!(n >= Earcon::Ended.samples().len());
}

#[tokio::test(start_paused = true)]
async fn a_close_from_the_server_ends_the_call_without_resuming() {
    let (_call, s, mut accept, _r, log) = up().await;
    let _ = s.to_phone.send(Ok(Frame::Close));
    wait_for(&log, r#"status Ended("Jarvis ended the call")"#).await;
    tokio::time::sleep(Duration::from_secs(30)).await;
    assert!(accept.try_recv().is_err(), "no reconnect after a close");
}

#[tokio::test(start_paused = true)]
async fn a_drop_reconnects_with_resume_and_tells_aviv_after_the_grace() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.audio(16_000, 5);
    tokio::time::sleep(Duration::from_millis(50)).await;
    let t0 = tokio::time::Instant::now();
    drop(s); // the socket just breaks
    let mut s = accept.recv().await.unwrap();
    assert!(t0.elapsed() < Duration::from_millis(50), "redialled at once: {:?}", t0.elapsed());
    assert!(!log.has("status Reconnecting"), "not yet: inside the grace");
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":1000}"#);
    // No ready for 3 s: past the 2.5 s grace, Aviv hears it.
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert!(log.has("status Reconnecting"));
    assert_eq!(call.buffered_ms(), 1000, "the reply plays on through the drop");
    assert!(log.has("status Reconnecting"));
    let drop_tone = Earcon::Dropped.samples();
    let mixed: Vec<i16> = drop_tone.iter().map(|&e| e.saturating_add(5)).collect();
    assert_eq!(pcm_values(&call.pull_audio(drop_tone.len())), mixed, "the drop earcon over the reply");
    s.send(r#"{"type":"ready","call":"c1"}"#);
    while log.statuses().last().unwrap() != "status Listening" {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(!log.0.lock().unwrap().iter().any(|l| l.starts_with("error")));
}

#[tokio::test(start_paused = true)]
async fn a_muted_call_stays_muted_after_a_resume() {
    let (call, mut s, mut accept, _r, _log) = up().await;
    call.set_muted(true);
    assert_eq!(s.text().await, r#"{"type":"mute","on":true}"#);
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    assert_eq!(s.text().await, r#"{"type":"mute","on":true}"#);
}

/// The next binary frame from the phone, skipping text and pings.
async fn next_audio(s: &mut Server) -> Vec<u8> {
    loop {
        if let Frame::Binary(b) = s.from_phone.recv().await.expect("phone closed") {
            return b;
        }
    }
}

#[tokio::test(start_paused = true)]
async fn a_voice_mute_keeps_the_mic_streaming_and_a_tap_lifts_it() {
    let (call, mut s, _a, _r, log) = up().await;
    s.send(r#"{"type":"muted","on":true}"#);
    wait_for(&log, "voice muted true").await;
    assert!(call.voice_muted() && !call.muted());
    // The server listens for "unmute": the mic still goes.
    call.send_audio(vec![7; 640]);
    assert_eq!(next_audio(&mut s).await, vec![7; 640]);
    // Unmute tapped while muted by voice: sent although the tap state didn't change.
    call.set_muted(false);
    assert_eq!(s.text().await, r#"{"type":"mute","on":false}"#);
    assert!(!call.voice_muted());
}

#[tokio::test(start_paused = true)]
async fn a_resume_keeps_a_voice_mute_and_a_new_call_drops_it() {
    let (call, s, mut accept, _r, log) = up().await;
    s.send(r#"{"type":"muted","on":true}"#);
    wait_for(&log, "voice muted true").await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    // No unmute on the resume: that would lift the voice mute on the server.
    call.send_audio(vec![3; 640]);
    match s.from_phone.recv().await.unwrap() {
        Frame::Binary(b) => assert_eq!(b, vec![3; 640]),
        f => panic!("sent {f:?} before the mic"),
    }
    assert!(call.voice_muted());
    // The server lost the call: the new one isn't muted.
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c2"}"#);
    wait_for(&log, "voice muted false").await;
    assert!(!call.voice_muted());
}

#[tokio::test(start_paused = true)]
async fn pause_goes_quiet_at_once_and_drops_what_was_on_its_way() {
    let (call, mut s, _a, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"state","state":"speaking"}"#);
    s.audio(16_000, 300);
    while call.buffered_ms() < 1000 {
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
    call.set_paused(true);
    assert_eq!(call.buffered_ms(), 0, "the tap didn't silence him here");
    assert!(log.has("flush"), "the app's AudioTrack wasn't told to drop its part");
    assert_eq!(s.text().await, r#"{"type":"pause","on":true}"#);
    // Sent before the server heard the tap: none of it plays.
    s.audio(8_000, 300);
    s.send(r#"{"type":"paused","on":true}"#);
    wait_for(&log, "paused true").await;
    assert_eq!(call.buffered_ms(), 0, "audio in flight played after the tap");
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 0));

    // Continue: the server goes on, and what it sends plays.
    call.set_paused(false);
    assert_eq!(s.text().await, r#"{"type":"pause","on":false}"#);
    s.send(r#"{"type":"paused","on":false}"#);
    s.audio(16_000, 400);
    wait_for(&log, "paused false").await;
    while call.buffered_ms() < 1000 {
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 400));
}

#[tokio::test(start_paused = true)]
async fn a_pause_with_nothing_to_pause_lets_the_next_reply_play() {
    let (call, mut s, _a, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    call.set_paused(true);
    assert_eq!(s.text().await, r#"{"type":"pause","on":true}"#);
    // The server had nothing to pause and says so: the button goes back, the next reply plays.
    s.send(r#"{"type":"paused","on":false}"#);
    wait_for(&log, "paused false").await;
    s.audio(16_000, 500);
    while call.buffered_ms() < 1000 {
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
}

#[tokio::test(start_paused = true)]
async fn a_pause_is_sent_again_on_resume() {
    let (call, mut s, mut accept, _r, log) = up().await;
    call.set_paused(true);
    assert_eq!(s.text().await, r#"{"type":"pause","on":true}"#);
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    assert_eq!(s.text().await, r#"{"type":"mute","on":false}"#);
    assert_eq!(s.text().await, r#"{"type":"pause","on":true}"#);
    // Answered this time, then dropped again: still paused, so said again.
    s.send(r#"{"type":"paused","on":true}"#);
    wait_for(&log, "paused true").await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    assert_eq!(s.text().await, r#"{"type":"mute","on":false}"#);
    assert_eq!(s.text().await, r#"{"type":"pause","on":true}"#);
    // Continued: a resume says nothing of pause.
    call.set_paused(false);
    assert_eq!(s.text().await, r#"{"type":"pause","on":false}"#);
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    assert_eq!(s.text().await, r#"{"type":"mute","on":false}"#);
    call.set_muted(true);
    assert_eq!(s.text().await, r#"{"type":"mute","on":true}"#);
}

#[tokio::test(start_paused = true)]
async fn a_new_call_id_after_resume_is_reported() {
    let (_call, s, mut accept, _r, log) = up().await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c2"}"#);
    wait_for(&log, "error new call: Jarvis lost the old one").await;
}

#[tokio::test(start_paused = true)]
async fn a_resume_that_became_a_new_call_drops_the_old_reply() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.audio(16_000, 5);
    tokio::time::sleep(Duration::from_millis(50)).await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    assert_eq!(call.buffered_ms(), 1000, "kept through the drop");
    s.send(r#"{"type":"ready","call":"c2"}"#);
    wait_for(&log, "flush").await;
    assert_eq!(call.buffered_ms(), 0, "the old call's reply won't be continued");
}

async fn buffered(call: &Call, ms: u64) {
    while call.buffered_ms() < ms {
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
}

/// Pulls `n` samples of voice, checking they're `from..from+n` of the ramp.
fn plays(call: &Call, from: i16, n: usize) {
    let got = pcm_values(&call.pull_audio(n));
    let want: Vec<i16> = (from..from + n as i16).collect();
    assert_eq!(got, want, "the ramp from {from}");
}

#[tokio::test(start_paused = true)]
async fn a_drop_mid_sentence_resends_it_and_one_still_playing_goes_on_seamlessly() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 8000); // half of a one-second sentence
    buffered(&call, 500).await;
    plays(&call, 0, 2000);
    drop(s);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":375}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    // The server sends sentence 1 again from its start; the phone was still playing it.
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 16000);
    s.send(r#"{"type":"said","seq":1}"#);
    buffered(&call, 875).await;
    plays(&call, 2000, 14000);
    assert_eq!(call.buffered_ms(), 0, "nothing doubled");
    assert!(!log.has("flush"));
    // The next drop says it holds sentence 1 whole.
    drop(s);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":1,"queued_ms":0}"#);
}

#[tokio::test(start_paused = true)]
async fn a_sentence_it_ran_dry_in_during_the_drop_plays_again_from_its_start() {
    let (call, s, mut accept, refuse, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 8000);
    buffered(&call, 500).await;
    refuse.store(true, Ordering::SeqCst);
    drop(s);
    // What it has plays on, then silence: the pause.
    plays(&call, 0, 8000);
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 0));
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert!(log.has("status Reconnecting"));
    let drop_tone = Earcon::Dropped.samples();
    assert_eq!(pcm_values(&call.pull_audio(drop_tone.len())), drop_tone);
    refuse.store(false, Ordering::SeqCst);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    while log.statuses().last().unwrap() != "status Listening" {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let back = Earcon::Connected.samples();
    assert_eq!(pcm_values(&call.pull_audio(back.len())), back, "nothing of the old reply left to play");
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 16000);
    s.send(r#"{"type":"said","seq":1}"#);
    buffered(&call, 1000).await;
    plays(&call, 0, 16000); // from the start, not from 8000 (mid-word)
}

#[tokio::test(start_paused = true)]
async fn a_drop_after_a_said_resends_only_what_it_lacks() {
    let (call, s, mut accept, _r, _log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 4000);
    s.send(r#"{"type":"said","seq":1}"#);
    s.send(r#"{"type":"sentence","seq":2}"#);
    s.ramp(4000, 6000);
    buffered(&call, 375).await;
    plays(&call, 0, 3000);
    drop(s);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":1,"queued_ms":187}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    // Even a sentence it holds whole, if the server sends it, plays once.
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 4000);
    s.send(r#"{"type":"said","seq":1}"#);
    s.send(r#"{"type":"sentence","seq":2}"#);
    s.ramp(4000, 8000);
    s.send(r#"{"type":"said","seq":2}"#);
    s.send(r#"{"type":"sentence","seq":3}"#);
    s.ramp(8000, 9000);
    buffered(&call, 375).await;
    plays(&call, 3000, 6000);
    assert_eq!(call.buffered_ms(), 0);
}

#[tokio::test(start_paused = true)]
async fn a_flush_lost_with_the_line_comes_first_on_resume() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"sentence","seq":1}"#);
    s.ramp(0, 8000);
    s.send(r#"{"type":"said","seq":1}"#);
    buffered(&call, 500).await;
    drop(s); // the barge-in's flush was on its way
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":1,"queued_ms":500}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    s.send(r#"{"type":"flush"}"#);
    s.send(r#"{"type":"sentence","seq":2}"#);
    s.ramp(100, 2100);
    wait_for(&log, "flush").await;
    buffered(&call, 125).await;
    plays(&call, 100, 2000);
}

#[tokio::test(start_paused = true)]
async fn a_silent_line_is_pinged_then_dropped() {
    let (_call, mut s, mut accept, _r, log) = up().await;
    let t0 = tokio::time::Instant::now();
    loop {
        if s.from_phone.recv().await.unwrap() == Frame::Ping {
            break;
        }
    }
    assert!(t0.elapsed() >= Duration::from_millis(1900), "{:?}", t0.elapsed());
    // No pong for 3 s: a second socket dials while the first stays.
    let mut s2 = accept.recv().await.unwrap();
    assert!(t0.elapsed() >= Duration::from_millis(4900) && t0.elapsed() < Duration::from_secs(6), "{:?}", t0.elapsed());
    assert_eq!(s2.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
    // Nobody answers either: after 15 s of nothing the first one goes, and Aviv was told.
    while tokio::time::timeout(Duration::from_secs(30), s.from_phone.recv()).await.unwrap().is_some() {}
    assert!(t0.elapsed() >= Duration::from_millis(14_900) && t0.elapsed() < Duration::from_secs(16), "{:?}", t0.elapsed());
    assert!(log.has("status Reconnecting"));
    drop(s2);
}

#[tokio::test(start_paused = true)]
async fn a_stalled_line_switches_to_a_second_socket_without_a_gap() {
    let (call, mut s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.send(r#"{"type":"state","state":"speaking"}"#);
    s.audio(16_000, 5); // a second of reply, not played yet
    let t0 = tokio::time::Instant::now();
    // The line goes quiet: the ping at 2 s goes unanswered.
    let mut s2 = accept.recv().await.unwrap();
    assert!(t0.elapsed() < Duration::from_secs(6), "{:?}", t0.elapsed());
    assert_eq!(s2.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":1000}"#);
    s2.send(r#"{"type":"ready","call":"c1"}"#);
    s2.send(r#"{"type":"state","state":"speaking"}"#);
    assert_eq!(s2.text().await, r#"{"type":"mute","on":false}"#);
    // The old socket is let go, and nothing told Aviv.
    while s.from_phone.recv().await.is_some() {}
    let statuses = log.statuses();
    assert!(!statuses.iter().any(|l| l.contains("Reconnecting")), "{statuses:?}");
    assert_eq!(call.buffered_ms(), 1000, "what was queued still plays");
    let drop_tone = Earcon::Dropped.samples();
    assert_ne!(pcm_values(&call.pull_audio(drop_tone.len())), drop_tone, "no drop earcon");
    // The mic goes to the new socket.
    call.send_audio(vec![3; 640]);
    loop {
        if let Frame::Binary(b) = s2.from_phone.recv().await.unwrap() {
            assert_eq!(b, vec![3; 640]);
            break;
        }
    }
}

#[tokio::test(start_paused = true)]
async fn a_stalled_line_that_answers_calls_off_the_second_socket() {
    let (conn, mut accept, _refuse) = fake_slow(Duration::from_secs(2));
    let log = Arc::new(Log::default());
    let _call = Call::start(conn, log.clone(), Timing::default());
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    let t0 = tokio::time::Instant::now();
    // The ping goes unanswered: the stall at 5 s starts a dial that takes 2 s to connect.
    loop {
        if s.from_phone.recv().await.unwrap() == Frame::Ping {
            break;
        }
    }
    tokio::time::sleep_until(t0 + Duration::from_millis(5500)).await;
    let _ = s.to_phone.send(Ok(Frame::Pong));
    // From here on the line answers every ping.
    let until = t0 + Duration::from_secs(20);
    while let Ok(Some(f)) = tokio::time::timeout_at(until, s.from_phone.recv()).await {
        if f == Frame::Ping {
            let _ = s.to_phone.send(Ok(Frame::Pong));
        }
    }
    assert!(accept.try_recv().is_err(), "the second dial was called off before its hello");
    assert!(!log.statuses().iter().any(|l| l.contains("Reconnecting")));
}

#[tokio::test(start_paused = true)]
async fn a_pong_keeps_a_quiet_line_up() {
    let (_call, mut s, mut accept, _r, _log) = up().await;
    for _ in 0..6 {
        loop {
            if s.from_phone.recv().await.unwrap() == Frame::Ping {
                break;
            }
        }
        let _ = s.to_phone.send(Ok(Frame::Pong));
    }
    assert!(accept.try_recv().is_err(), "30 s quiet but answering: still the same socket");
}

#[tokio::test(start_paused = true)]
async fn a_network_change_checks_the_line_at_once() {
    let (call, mut s, mut accept, _r, _log) = up().await;
    let t0 = tokio::time::Instant::now();
    call.network_changed();
    assert_eq!(s.from_phone.recv().await.unwrap(), Frame::Ping);
    let _s2 = accept.recv().await.unwrap();
    assert!(t0.elapsed() < Duration::from_secs(6), "dropped after the 4 s check, not the 15 s one");
}

#[tokio::test(start_paused = true)]
async fn a_dropped_call_is_never_given_up() {
    let (_call, s, mut accept, refuse, log) = up().await;
    refuse.store(true, Ordering::SeqCst);
    drop(s);
    // An hour without the server: still trying, never ended (the server keeps the call too).
    tokio::time::sleep(Duration::from_secs(3600)).await;
    assert!(!log.0.lock().unwrap().iter().any(|l| l.starts_with("status Ended")), "{:?}", log.statuses());
    assert_eq!(log.statuses().last().unwrap(), "status Reconnecting");
    // Back, within the longest backoff: the same call.
    refuse.store(false, Ordering::SeqCst);
    let mut s = tokio::time::timeout(Duration::from_secs(11), accept.recv()).await.expect("redialled").unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    while log.statuses().last().unwrap() != "status Listening" {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

#[tokio::test(start_paused = true)]
async fn offline_it_waits_and_dials_the_moment_the_network_is_back() {
    let (conn, mut accept, refuse) = fake();
    let log = Arc::new(Log::default());
    let call = Call::start(conn, log.clone(), Timing::default());
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    refuse.store(true, Ordering::SeqCst);
    call.set_online(false);
    drop(s);
    wait_for(&log, "status Reconnecting").await;
    // The drop's own redial fails; after that, nothing is dialled while offline.
    tokio::time::sleep(Duration::from_secs(1)).await;
    refuse.store(false, Ordering::SeqCst);
    tokio::time::sleep(Duration::from_secs(600)).await;
    assert!(accept.try_recv().is_err(), "dialled while offline");
    let t0 = tokio::time::Instant::now();
    call.set_online(true);
    let mut s = accept.recv().await.unwrap();
    assert!(t0.elapsed() < Duration::from_millis(50), "dialled at once: {:?}", t0.elapsed());
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    while log.statuses().last().unwrap() != "status Listening" {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

#[tokio::test(start_paused = true)]
async fn a_rejoin_resumes_the_call_saying_so_once_and_never_gives_up() {
    let (conn, mut accept, refuse) = fake();
    let log = Arc::new(Log::default());
    refuse.store(true, Ordering::SeqCst);
    let _call = Call::rejoin(conn, log.clone(), Timing::default(), "c7".into(), false, true);
    // Not the first connect's 45 s: the server has this call.
    tokio::time::sleep(Duration::from_secs(120)).await;
    assert!(!log.0.lock().unwrap().iter().any(|l| l.starts_with("status Ended")));
    refuse.store(false, Ordering::SeqCst);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c7","rejoin":true}"#);
    s.send(r#"{"type":"ready","call":"c7"}"#);
    wait_for(&log, "status Listening").await;
    assert!(!log.has("joined c7"), "the app already has it");
    // A drop after it is a plain resume.
    drop(s);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c7","have":0,"queued_ms":0}"#);
}

#[tokio::test(start_paused = true)]
async fn a_restart_aviv_never_noticed_resumes_without_saying_so() {
    let (conn, mut accept, _refuse) = fake();
    let log = Arc::new(Log::default());
    let _call = Call::rejoin(conn, log.clone(), Timing::default(), "c8".into(), false, false);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c8"}"#);
    s.send(r#"{"type":"ready","call":"c8"}"#);
    wait_for(&log, "status Listening").await;
}

#[tokio::test(start_paused = true)]
async fn the_app_hears_the_calls_id_and_a_hangup_the_server_never_confirmed() {
    let (call, mut s, _a, _r, log) = up().await;
    assert!(log.has("joined c1"));
    call.hangup();
    assert_eq!(s.text().await, r#"{"type":"hangup"}"#);
    // No Close within the wait: the app owes the server this hangup.
    wait_for(&log, "owed c1").await;
    drop(s);

    let (call, mut s, _a, _r, log) = up().await;
    call.hangup();
    assert_eq!(s.text().await, r#"{"type":"hangup"}"#);
    let _ = s.to_phone.send(Ok(Frame::Close));
    wait_for(&log, r#"status Ended("hung up")"#).await;
    tokio::time::sleep(Duration::from_secs(10)).await;
    assert!(!log.has("owed c1"), "the server closed: it has the hangup");
}

#[tokio::test(start_paused = true)]
async fn the_first_connect_gives_up_sooner() {
    let (conn, _accept, refuse) = fake();
    refuse.store(true, Ordering::SeqCst);
    let log = Arc::new(Log::default());
    let t0 = tokio::time::Instant::now();
    let _call = Call::start(conn, log.clone(), Timing::default());
    wait_for(&log, r#"status Ended("couldn't reach Jarvis")"#).await;
    assert!(t0.elapsed() <= Duration::from_secs(50));
}

#[tokio::test(start_paused = true)]
async fn hangup_while_reconnecting_ends_at_once() {
    let (call, s, _accept, refuse, log) = up().await;
    refuse.store(true, Ordering::SeqCst);
    drop(s);
    wait_for(&log, "status Reconnecting").await;
    call.hangup();
    wait_for(&log, r#"status Ended("hung up")"#).await;
}

/// The real thing: tokio-websockets on loopback, as `jarvis-voice` would answer.
#[tokio::test]
async fn a_real_websocket_exchange() {
    use tokio_websockets::{Message, ServerBuilder};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = tokio::spawn(async move {
        let (tcp, _) = listener.accept().await.unwrap();
        let (_req, mut ws) = ServerBuilder::new().accept(tcp).await.unwrap();
        let hello = ws.next().await.unwrap().unwrap();
        assert_eq!(hello.as_text(), Some(r#"{"type":"hello","version":1,"resume":null}"#));
        ws.send(Message::text(r#"{"type":"ready","call":"x"}"#.to_string())).await.unwrap();
        ws.send(Message::binary([0x10u8, 0x00].repeat(16_000))).await.unwrap();
        let mut got_audio = false;
        while let Some(Ok(m)) = ws.next().await {
            if m.is_binary() {
                got_audio = m.as_payload().len() == 640;
            } else if m.as_text() == Some(r#"{"type":"hangup"}"#) {
                let _ = ws.close().await;
                break;
            }
        }
        got_audio
    });
    let log = Arc::new(Log::default());
    let call = Call::start(websocket(format!("ws://127.0.0.1:{port}/call")), log.clone(), Timing::default());
    wait_for(&log, "status Listening").await;
    call.send_audio(vec![0; 640]);
    while call.buffered_ms() < 1000 {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    call.pull_audio(Earcon::Connected.samples().len());
    assert!(pcm_values(&call.pull_audio(320)).iter().all(|&v| v == 0x10));
    tokio::time::sleep(Duration::from_millis(50)).await;
    call.hangup();
    wait_for(&log, r#"status Ended("hung up")"#).await;
    assert!(server.await.unwrap(), "the server got a 640-byte mic frame");
}

#[tokio::test(start_paused = true)]
async fn a_hangup_on_a_broken_line_ends_the_call_without_reconnecting() {
    let (call, s, mut accept, _r, log) = up().await;
    // The phone's writes fail, and nothing has told it yet.
    let Server { from_phone, to_phone } = s;
    drop(from_phone);
    call.hangup();
    wait_for(&log, r#"status Ended("hung up")"#).await;
    drop(to_phone);
    tokio::time::sleep(Duration::from_secs(30)).await;
    assert!(accept.try_recv().is_err(), "no reconnect after a hangup");
}

#[tokio::test(start_paused = true)]
async fn an_unmute_while_the_line_is_down_reaches_the_server_on_resume() {
    let (call, mut s, mut accept, refuse, log) = up().await;
    call.set_muted(true);
    assert_eq!(s.text().await, r#"{"type":"mute","on":true}"#);
    refuse.store(true, Ordering::SeqCst);
    drop(s);
    wait_for(&log, "status Reconnecting").await;
    call.set_muted(false);
    refuse.store(false, Ordering::SeqCst);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    assert_eq!(s.text().await, r#"{"type":"mute","on":false}"#);
}

/// A server restarting closes with "going away": the phone resumes instead of ending the call.
#[tokio::test]
async fn a_server_going_away_is_a_drop_not_the_end() {
    use tokio_websockets::{CloseCode, Message, ServerBuilder};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = tokio::spawn(async move {
        let mut hellos = vec![];
        for code in [CloseCode::GOING_AWAY, CloseCode::NORMAL_CLOSURE] {
            let (tcp, _) = listener.accept().await.unwrap();
            let (_req, mut ws) = ServerBuilder::new().accept(tcp).await.unwrap();
            hellos.push(ws.next().await.unwrap().unwrap().as_text().unwrap().to_owned());
            ws.send(Message::text(r#"{"type":"ready","call":"x"}"#.to_string())).await.unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
            let _ = ws.send(Message::close(Some(code), "")).await;
            while let Some(Ok(_)) = ws.next().await {}
        }
        hellos
    });
    let log = Arc::new(Log::default());
    let _call = Call::start(websocket(format!("ws://127.0.0.1:{port}/call")), log.clone(), Timing::default());
    wait_for(&log, r#"status Ended("Jarvis ended the call")"#).await;
    assert!(!log.has("status Reconnecting"), "back within the grace");
    assert_eq!(server.await.unwrap()[1], r#"{"type":"hello","version":1,"resume":"x","have":0,"queued_ms":0}"#);
}

#[tokio::test(start_paused = true)]
async fn a_network_change_while_reconnecting_tries_again_at_once() {
    let (call, s, mut accept, refuse, log) = up().await;
    refuse.store(true, Ordering::SeqCst);
    drop(s);
    wait_for(&log, "status Reconnecting").await;
    // Several failed tries: the backoff is up to seconds now.
    tokio::time::sleep(Duration::from_secs(20)).await;
    refuse.store(false, Ordering::SeqCst);
    let t0 = tokio::time::Instant::now();
    call.network_changed();
    let mut s = accept.recv().await.unwrap();
    assert!(t0.elapsed() < Duration::from_millis(100), "{:?}", t0.elapsed());
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
}

#[tokio::test(start_paused = true)]
async fn a_drop_back_within_the_grace_goes_unnoticed() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.audio(16_000, 5);
    tokio::time::sleep(Duration::from_millis(50)).await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    tokio::time::sleep(Duration::from_secs(1)).await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    tokio::time::sleep(Duration::from_secs(5)).await;
    assert!(!log.statuses().iter().any(|l| l.contains("Reconnecting")), "{:?}", log.statuses());
    assert_eq!(call.buffered_ms(), 1000, "the reply wasn't flushed");
}

#[tokio::test(start_paused = true)]
async fn a_call_about_a_thread_says_so_until_the_server_has_it_but_never_on_resume() {
    let (conn, mut accept, _refuse) = fake();
    let log = Arc::new(Log::default());
    let about = jarvis_voice::proto::About { topic: 112, title: "One app".into(), text: "Keep it?".into(), task: None };
    let _call = Call::start_about(conn, log.clone(), Timing::default(), Some(about));
    let with = r#"{"type":"hello","version":1,"resume":null,"about":{"topic":112,"title":"One app","text":"Keep it?"}}"#;
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, with);
    drop(s); // broke before ready: the redial is still the first hello
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, with);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
}

#[tokio::test(start_paused = true)]
async fn phone_stt_says_so_feeds_the_recognizer_and_answers_each_cut() {
    let (conn, mut accept, _refuse) = fake();
    let log = Arc::new(Log::default());
    let call = Call::start_full(conn, log.clone(), Timing::default(), None, true);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":null,"stt":"phone"}"#);
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    // One second of mic: 50 frames, each fed to session 1 as it goes out.
    for _ in 0..50 {
        call.send_audio(vec![0; 640]);
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    wait_for(&log, "stt open 1").await;
    assert_eq!(log.0.lock().unwrap().iter().filter(|l| *l == "stt audio 1 640").count(), 50);
    // The turn ran 0.2 s to 0.8 s: session 1 closes, session 2 opens at 0.8 s with the 0.2 s since.
    s.send(r#"{"type":"cut","id":1,"from":3200,"to":12800}"#);
    wait_for(&log, "stt close 1").await;
    wait_for(&log, "stt audio 2 6400").await;
    tokio::time::sleep(Duration::from_millis(900)).await;
    let w = |t: &str, ms| jarvis_voice::Word { text: t.into(), start_ms: ms };
    call.stt_done(1, Some(vec![w("Check", 300), w("it.", 500), w("Next.", 850)]));
    let heard = s.text().await;
    assert!(heard.starts_with(r#"{"type":"heard","id":1,"text":"Check it.","ms":9"#), "{heard}");
}

#[tokio::test(start_paused = true)]
async fn the_old_sockets_close_during_a_takeover_does_not_end_the_call() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    // The line stalls and a resume says hello; the server takes the call over and closes the old
    // socket before the new one's `ready` reaches the phone (thread #178).
    let mut s2 = accept.recv().await.unwrap();
    assert_eq!(s2.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
    let _ = s.to_phone.send(Ok(Frame::Close));
    tokio::time::sleep(Duration::from_millis(200)).await;
    s2.send(r#"{"type":"ready","call":"c1"}"#);
    assert_eq!(s2.text().await, r#"{"type":"mute","on":false}"#);
    let statuses = log.statuses();
    assert!(!statuses.iter().any(|l| l.contains("Ended") || l.contains("Reconnecting")), "{statuses:?}");
    call.send_audio(vec![3; 640]);
    loop {
        if let Frame::Binary(b) = s2.from_phone.recv().await.unwrap() {
            assert_eq!(b, vec![3; 640]);
            break;
        }
    }
}

/// Waits (on the paused clock) until `log` has `n` of `s`; how long that took from `t0`.
async fn wait_count(log: &Log, s: &str, n: usize, t0: tokio::time::Instant) -> Duration {
    for _ in 0..60_000 {
        if log.count(s) >= n {
            return t0.elapsed();
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("never got {n} of {s:?}; log: {:?}", log.0.lock().unwrap());
}

#[tokio::test(start_paused = true)]
async fn a_stall_kicks_the_network_at_once_and_again_while_the_second_dial_hangs() {
    // Every connect takes 10 s, as a dial on a path that went with the old Wi-Fi does.
    let (conn, mut accept, _refuse) = fake_slow(Duration::from_secs(10));
    let log = Arc::new(Log::default());
    let _call = Call::start(conn, log.clone(), Timing::default());
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    assert_eq!(log.count("kick"), 0, "the first connect kicks nothing");
    let t0 = tokio::time::Instant::now();
    // The ping at 2 s goes unanswered: the stall at 5 s kicks at once, with the second dial.
    let at = wait_count(&log, "kick", 1, t0).await;
    assert!(at >= Duration::from_millis(4900) && at < Duration::from_millis(5300), "{at:?}");
    // 3 s on, that dial still hasn't said hello: kicked again, and the dial starts over.
    let at = wait_count(&log, "kick", 2, t0).await;
    assert!(at >= Duration::from_millis(7900) && at < Duration::from_millis(8300), "{at:?}");
    // Only the dial started at 8 s gets through (the one from 5 s was dropped), and no more kicks.
    let mut s2 = accept.recv().await.unwrap();
    assert!(t0.elapsed() >= Duration::from_millis(17_900) && t0.elapsed() < Duration::from_millis(18_300), "{:?}", t0.elapsed());
    assert_eq!(s2.text().await, r#"{"type":"hello","version":1,"resume":"c1","have":0,"queued_ms":0}"#);
    s2.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "resumed").await;
    // (Under 5 s, or the new line, unanswered here, would stall in its turn.)
    tokio::time::sleep(Duration::from_secs(4)).await;
    assert!(accept.try_recv().is_err(), "the first second dial was called off");
    assert_eq!(log.count("kick"), 2, "{:?}", log.0.lock().unwrap());
    assert_eq!(log.count("resumed"), 1);
    drop(s);
}

#[tokio::test(start_paused = true)]
async fn a_second_dial_that_gets_through_is_not_kicked_again() {
    let (_call, _s, mut accept, _r, log) = up().await;
    let t0 = tokio::time::Instant::now();
    let mut s2 = accept.recv().await.unwrap();
    assert_eq!(log.count("kick"), 1, "kicked with the stall's dial");
    s2.text().await;
    // Hello went out: the server takes its time with ready, but the path works. No rekick.
    tokio::time::sleep_until(t0 + Duration::from_secs(9)).await;
    s2.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "resumed").await;
    tokio::time::sleep(Duration::from_secs(4)).await;
    assert_eq!(log.count("kick"), 1, "{:?}", log.0.lock().unwrap());
    assert!(accept.try_recv().is_err());
}

#[tokio::test(start_paused = true)]
async fn a_hard_drop_kicks_once_and_a_quick_resume_needs_no_more() {
    let (_call, s, mut accept, _r, log) = up().await;
    drop(s);
    let mut s = accept.recv().await.unwrap();
    assert_eq!(log.count("kick"), 1, "kicked as the line broke");
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "resumed").await;
    tokio::time::sleep(Duration::from_secs(4)).await;
    assert_eq!(log.count("kick"), 1, "{:?}", log.0.lock().unwrap());
}

#[tokio::test(start_paused = true)]
async fn a_stalled_line_that_answers_ends_the_rekick() {
    let (conn, mut accept, _refuse) = fake_slow(Duration::from_secs(2));
    let log = Arc::new(Log::default());
    let _call = Call::start(conn, log.clone(), Timing::default());
    let mut s = accept.recv().await.unwrap();
    s.text().await;
    s.send(r#"{"type":"ready","call":"c1"}"#);
    wait_for(&log, "status Listening").await;
    let t0 = tokio::time::Instant::now();
    wait_count(&log, "kick", 1, t0).await;
    // The old line answers before the second dial (2 s to connect) has said hello: no rekick.
    let _ = s.to_phone.send(Ok(Frame::Pong));
    let until = t0 + Duration::from_secs(12);
    while let Ok(Some(f)) = tokio::time::timeout_at(until, s.from_phone.recv()).await {
        if f == Frame::Ping {
            let _ = s.to_phone.send(Ok(Frame::Pong));
        }
    }
    assert_eq!(log.count("kick"), 1, "{:?}", log.0.lock().unwrap());
    assert!(accept.try_recv().is_err());
}

#[tokio::test(start_paused = true)]
async fn end_hangs_up_a_dropped_call_without_joining_it() {
    let (conn, mut accept, _refuse) = fake();
    let done = tokio::spawn(jarvis_voice::call::end(conn, "c3".into(), Timing::default()));
    let mut s = accept.recv().await.unwrap();
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c3","end":true}"#);
    let _ = s.to_phone.send(Ok(Frame::Close));
    done.await.unwrap().expect("the server closed: it has the hangup");

    // A server that never closes: not delivered (the app tries again).
    let (conn, mut accept, _refuse) = fake();
    let done = tokio::spawn(jarvis_voice::call::end(conn, "c3".into(), Timing::default()));
    let _s = accept.recv().await.unwrap();
    assert!(done.await.unwrap().is_err());
}
