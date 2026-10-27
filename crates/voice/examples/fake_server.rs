//! A stand-in for `jarvis-voice`, to try the phone's call end to end while the real one isn't up:
//! `cargo run -p tether-voice --example fake_server [ADDR]` (default `127.0.0.1:8790`, which the
//! emulator reaches as `10.0.2.2:8790`). It speaks the wire of PROTOCOL.md: `ready` after `hello`,
//! then every time it has heard 2 s of mic audio it "answers" with a 3 s tone, and the first answer
//! of each call is cut off with `flush` halfway, as if Aviv talked over it. `hangup` closes.
//! Everything it sees is printed, one line per event, so a test can grep it.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::{net::TcpListener, time::Instant};
use tokio_websockets::{Message, ServerBuilder};

const RATE: usize = 16_000;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let addr = std::env::args().nth(1).unwrap_or_else(|| "127.0.0.1:8790".into());
    let listener = TcpListener::bind(&addr).await?;
    println!("listening {addr}");
    let mut n = 0;
    loop {
        let (tcp, peer) = listener.accept().await?;
        n += 1;
        println!("[{n}] tcp from {peer}");
        tokio::spawn(async move {
            if let Err(e) = serve(tcp, n).await {
                println!("[{n}] error: {e:#}");
            }
        });
    }
}

fn tone(ms: usize, hz: f32) -> Vec<u8> {
    (0..RATE * ms / 1000)
        .flat_map(|i| (((i as f32 * hz * std::f32::consts::TAU / RATE as f32).sin() * 8000.0) as i16).to_le_bytes())
        .collect()
}

/// The socket's writing half, behind a channel, so an answer streams while the mic is still read
/// (as the real service does: Aviv can talk over it, and `hangup` is seen at once).
#[derive(Clone)]
struct Out(tokio::sync::mpsc::UnboundedSender<Message>);

impl Out {
    async fn send(&mut self, m: Message) -> anyhow::Result<()> {
        self.0.send(m).map_err(|_| anyhow::anyhow!("socket gone"))
    }
}

async fn serve(tcp: tokio::net::TcpStream, n: usize) -> anyhow::Result<()> {
    let (_req, ws) = ServerBuilder::new().accept(tcp).await?;
    let (mut sink, mut ws) = ws.split();
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Message>();
    tokio::spawn(async move {
        while let Some(m) = rx.recv().await {
            let close = m.is_close();
            if sink.send(m).await.is_err() || close {
                break;
            }
        }
    });
    let mut out = Out(tx);
    let started = Instant::now();
    let (mut mic_bytes, mut loud_frames, mut since_answer, mut answers) = (0usize, 0usize, 0usize, 0usize);
    let mut last_report = Instant::now();
    while let Some(m) = ws.next().await {
        let m = m?;
        if let Some(t) = m.as_text() {
            println!("[{n}] text {t}");
            let v: serde_json::Value = serde_json::from_str(t).unwrap_or_default();
            match v["type"].as_str() {
                Some("hello") => {
                    let id = v["resume"].as_str().unwrap_or("fake-call").to_string();
                    out.send(Message::text(format!(r#"{{"type":"ready","call":"{id}"}}"#))).await?;
                    out.send(Message::text(r#"{"type":"state","state":"listening"}"#.to_string())).await?;
                    println!("[{n}] sent ready {id}");
                }
                Some("hangup") => {
                    println!("[{n}] hangup after {:.1}s, mic {mic_bytes} bytes", started.elapsed().as_secs_f32());
                    out.send(Message::close(None, "")).await?;
                    return Ok(());
                }
                _ => {}
            }
        } else if m.is_binary() {
            let p = m.as_payload();
            mic_bytes += p.len();
            since_answer += p.len();
            let peak = p.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]]).unsigned_abs()).max().unwrap_or(0);
            if peak > 300 {
                loud_frames += 1;
            }
            if last_report.elapsed() >= Duration::from_secs(1) {
                println!("[{n}] mic {mic_bytes} bytes ({} frames of {} bytes, {loud_frames} not silent)", mic_bytes / 640, p.len());
                last_report = Instant::now();
            }
            if since_answer >= RATE * 2 * 2 {
                since_answer = 0;
                answers += 1;
                let out = out.clone();
                tokio::spawn(async move { answer(out, n, answers).await });
            }
        } else if m.is_ping() {
            println!("[{n}] ping");
        } else if m.is_close() {
            println!("[{n}] closed by the phone");
            return Ok(());
        }
    }
    println!("[{n}] socket ended (a drop) after {:.1}s", started.elapsed().as_secs_f32());
    Ok(())
}

async fn answer(mut ws: Out, n: usize, k: usize) -> anyhow::Result<()> {
    ws.send(Message::text(format!(r#"{{"type":"text","who":"aviv","text":"(fake) heard you, part {k}"}}"#))).await?;
    ws.send(Message::text(r#"{"type":"state","state":"thinking"}"#.to_string())).await?;
    tokio::time::sleep(Duration::from_millis(400)).await;
    ws.send(Message::text(r#"{"type":"state","state":"speaking"}"#.to_string())).await?;
    ws.send(Message::text(format!(r#"{{"type":"text","who":"jarvis","text":"(fake) a 3 second tone, answer {k}"}}"#))).await?;
    let audio = tone(3000, 440.0);
    // 100 ms chunks, a little faster than real time, as a TTS stream would come.
    let cut = k == 1;
    for (i, chunk) in audio.chunks(RATE * 2 / 10).enumerate() {
        ws.send(Message::binary(chunk.to_vec())).await?;
        tokio::time::sleep(Duration::from_millis(80)).await;
        if cut && i == 15 {
            ws.send(Message::text(r#"{"type":"flush"}"#.to_string())).await?;
            println!("[{n}] answer {k}: sent 1.6 s, then flush");
            break;
        }
    }
    if !cut {
        println!("[{n}] answer {k}: sent 3 s of tone");
    }
    ws.send(Message::text(r#"{"type":"state","state":"listening"}"#.to_string())).await?;
    Ok(())
}
