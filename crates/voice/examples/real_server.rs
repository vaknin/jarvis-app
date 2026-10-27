//! Drive the phone's call code against a real jarvis-voice: stream a 16 kHz mono WAV as the mic,
//! print what the server says, then hang up. `cargo run -p tether-voice --example real_server -- CLIP.wav [URL]`
use std::sync::Arc;
use std::time::{Duration, Instant};
use tether_voice::{websocket, Call, Events, Status, Timing};

struct Log(Instant);
impl Events for Log {
    fn status(&self, s: Status) { println!("{:>6} status {s:?}", self.0.elapsed().as_millis()); }
    fn text(&self, who: &str, t: &str) { println!("{:>6} {who}: {t}", self.0.elapsed().as_millis()); }
    fn error(&self, t: &str) { println!("{:>6} error {t}", self.0.elapsed().as_millis()); }
    fn flush(&self) { println!("{:>6} flush", self.0.elapsed().as_millis()); }
}

#[tokio::main]
async fn main() {
    let a: Vec<String> = std::env::args().collect();
    let url = a.get(2).cloned().unwrap_or("ws://127.0.0.1:8790/call".into());
    let wav = std::fs::read(&a[1]).unwrap();
    let pcm = &wav[44..];
    let call = Call::start(websocket(url), Arc::new(Log(Instant::now())), Timing::default());
    tokio::time::sleep(Duration::from_millis(800)).await;
    let mut got = 0usize;
    let mut tick = tokio::time::interval(Duration::from_millis(20));
    // the clip, then 14 s of silence while Jarvis answers; pull the speaker like the app does
    let silence = vec![0u8; 640];
    for i in 0..(pcm.len() / 640 + 700) {
        tick.tick().await;
        let f = pcm.get(i * 640..(i + 1) * 640).unwrap_or(&silence);
        call.send_audio(f.to_vec());
        got += call.pull_audio(320).iter().filter(|b| **b != 0).count();
    }
    println!("speaker got {} non-zero bytes", got);
    call.hangup();
    tokio::time::sleep(Duration::from_secs(20)).await;
    println!("final {:?}", call.status());
}
