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
use tether_voice::{Call, Connector, Earcon, Events, Frame, Status, Timing, call::Rx, call::Tx, websocket};
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

    fn audio(&self, samples: usize, value: i16) {
        let b = (0..samples).flat_map(|_| value.to_le_bytes()).collect();
        let _ = self.to_phone.send(Ok(Frame::Binary(b)));
    }
}

/// A connector whose every connect hands the server side to the test; `refuse` makes connects fail.
fn fake() -> (Connector, mpsc::UnboundedReceiver<Server>, Arc<AtomicBool>) {
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
    // Connected, then the thinking tick.
    let n = Earcon::Connected.samples().len();
    call.pull_audio(n);
    let tick = Earcon::Thinking.samples();
    assert_eq!(pcm_values(&call.pull_audio(tick.len())), tick);
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
async fn a_drop_reconnects_with_resume() {
    let (call, s, mut accept, _r, log) = up().await;
    call.pull_audio(Earcon::Connected.samples().len());
    s.audio(16_000, 5);
    tokio::time::sleep(Duration::from_millis(50)).await;
    drop(s); // the socket just breaks
    let mut s = accept.recv().await.unwrap();
    assert!(log.has("status Reconnecting"));
    assert_eq!(call.buffered_ms(), 0, "stale reply dropped");
    let drop_tone = Earcon::Dropped.samples();
    assert_eq!(pcm_values(&call.pull_audio(drop_tone.len())), drop_tone);
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1"}"#);
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
async fn a_silent_line_is_pinged_then_dropped() {
    let (_call, mut s, mut accept, _r, log) = up().await;
    let t0 = tokio::time::Instant::now();
    loop {
        if s.from_phone.recv().await.unwrap() == Frame::Ping {
            break;
        }
    }
    assert!(t0.elapsed() >= Duration::from_millis(4900), "{:?}", t0.elapsed());
    // No pong: after 15 s of nothing the phone reconnects.
    let s2 = accept.recv().await.unwrap();
    assert!(t0.elapsed() >= Duration::from_millis(14_900) && t0.elapsed() < Duration::from_secs(20));
    assert!(log.has("status Reconnecting"));
    drop((s, s2));
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
async fn gives_up_about_five_minutes_after_a_drop() {
    let (_call, s, _accept, refuse, log) = up().await;
    refuse.store(true, Ordering::SeqCst);
    let t0 = tokio::time::Instant::now();
    drop(s);
    wait_for(&log, r#"status Ended("lost the line")"#).await;
    let took = t0.elapsed();
    assert!(took >= Duration::from_secs(280) && took <= Duration::from_secs(300), "{took:?}");
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
    assert!(log.has("status Reconnecting"));
    assert_eq!(server.await.unwrap()[1], r#"{"type":"hello","version":1,"resume":"x"}"#);
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
    assert_eq!(s.text().await, r#"{"type":"hello","version":1,"resume":"c1"}"#);
}
