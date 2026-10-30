//! Drive the phone's call code against a real jarvis-voice: stream a 16 kHz mono WAV as the mic,
//! print what the server says, then hang up.
//! `cargo run -p tether-voice --example real_server -- CLIP.wav [URL] [OPTIONS]`
//!
//! The call goes through a small proxy here, which can break the line mid-reply:
//! - `--drop-ms N`: cut the line N ms after Jarvis's first reply audio came in, and refuse dials for
//!   `--down-ms M` (default 1000): the phone redials and resumes.
//! - `--after-said`: count N from the first `said` instead (a drop between two sentences).
//! - `--mid-sentence`: count N from the first audio after the first `said` (a drop inside sentence 2).
//! - `--stall OVER.wav`: instead of cutting, the line stops carrying anything to the phone, and OVER
//!   plays as the mic: Aviv talks over Jarvis, whose `flush` is lost with the line, and the phone
//!   switches to a second socket (the takeover path).
//! - `--out OUT.wav`: what the speaker played. `--secs S` (default 30): the call runs that long after the clip.
//!
//! At the end it maps what the speaker played against the server's sentences (each whole, as sent):
//! where it continued, restarted, repeated or skipped, and the silences.
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tether_voice::{Call, Events, Status, Timing, websocket};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::watch,
};

struct Log(Instant);
impl Events for Log {
    fn status(&self, s: Status) {
        println!("{:>6} status {s:?}", self.0.elapsed().as_millis());
    }
    fn text(&self, who: &str, t: &str) {
        println!("{:>6} {who}: {t}", self.0.elapsed().as_millis());
    }
    fn error(&self, t: &str) {
        println!("{:>6} error {t}", self.0.elapsed().as_millis());
    }
    fn flush(&self) {
        println!("{:>6} flush (app)", self.0.elapsed().as_millis());
    }
    fn resumed(&self) {
        println!("{:>6} resumed", self.0.elapsed().as_millis());
    }
}

/// What came down to the phone, frame by frame: (socket, ms since start, frame).
#[derive(Default)]
struct Tap {
    frames: Vec<(u64, u128, Down)>,
}

#[derive(Clone)]
enum Down {
    Text(String),
    Binary(Vec<u8>),
}

struct Proxy {
    t0: Instant,
    server: String,
    /// Bumped to cut every socket open now.
    cut: watch::Sender<u64>,
    refuse_until: Mutex<Option<Instant>>,
    /// Sockets up to this one carry nothing more to the phone.
    stall_upto: AtomicU64,
    next: AtomicU64,
    tap: Mutex<Tap>,
}

impl Proxy {
    async fn serve(self: Arc<Self>, l: TcpListener) {
        loop {
            let Ok((phone, _)) = l.accept().await else { return };
            if self.refuse_until.lock().unwrap().is_some_and(|t| Instant::now() < t) {
                println!("{:>6} proxy: refused a dial (the line is down)", self.t0.elapsed().as_millis());
                drop(phone);
                continue;
            }
            let id = self.next.fetch_add(1, Ordering::SeqCst) + 1;
            let p = self.clone();
            tokio::spawn(async move {
                let Ok(server) = TcpStream::connect(&p.server).await else { return };
                println!("{:>6} proxy: socket {id} up", p.t0.elapsed().as_millis());
                let (mut pr, mut pw) = phone.into_split();
                let (mut sr, mut sw) = server.into_split();
                let mut cut = p.cut.subscribe();
                let up = async {
                    let mut b = vec![0u8; 16384];
                    while let Ok(n) = pr.read(&mut b).await {
                        if n == 0 || sw.write_all(&b[..n]).await.is_err() {
                            break;
                        }
                    }
                };
                let down = async {
                    let mut b = vec![0u8; 65536];
                    let mut ws = WsParse::default();
                    while let Ok(n) = sr.read(&mut b).await {
                        if n == 0 {
                            break;
                        }
                        if id <= p.stall_upto.load(Ordering::SeqCst) {
                            continue; // stalled: read from the server, into nothing
                        }
                        for f in ws.feed(&b[..n]) {
                            p.tap.lock().unwrap().frames.push((id, p.t0.elapsed().as_millis(), f));
                        }
                        if pw.write_all(&b[..n]).await.is_err() {
                            break;
                        }
                    }
                };
                tokio::select! {
                    _ = up => {}
                    _ = down => {}
                    _ = cut.changed() => println!("{:>6} proxy: socket {id} cut", p.t0.elapsed().as_millis()),
                }
            });
        }
    }
}

/// Server → client WebSocket frames (unmasked), enough for jarvis-voice's.
#[derive(Default)]
struct WsParse {
    buf: Vec<u8>,
    /// The handshake's response is HTTP, before the first frame.
    open: bool,
    partial: Option<(u8, Vec<u8>)>,
}

impl WsParse {
    fn feed(&mut self, b: &[u8]) -> Vec<Down> {
        self.buf.extend_from_slice(b);
        if !self.open {
            let Some(end) = self.buf.windows(4).position(|w| w == b"\r\n\r\n") else { return vec![] };
            self.buf.drain(..end + 4);
            self.open = true;
        }
        let mut out = vec![];
        loop {
            if self.buf.len() < 2 {
                return out;
            }
            let (fin, op, masked) = (self.buf[0] & 0x80 != 0, self.buf[0] & 0x0f, self.buf[1] & 0x80 != 0);
            let (len, mut at) = match self.buf[1] & 0x7f {
                126 if self.buf.len() >= 4 => (u16::from_be_bytes([self.buf[2], self.buf[3]]) as usize, 4),
                127 if self.buf.len() >= 10 => (u64::from_be_bytes(self.buf[2..10].try_into().unwrap()) as usize, 10),
                126 | 127 => return out,
                n => (n as usize, 2),
            };
            if masked {
                at += 4;
            }
            if self.buf.len() < at + len {
                return out;
            }
            let payload: Vec<u8> = self.buf[at..at + len].to_vec();
            self.buf.drain(..at + len);
            let (op, payload) = match (op, self.partial.take()) {
                (0, Some((op, mut p))) => {
                    p.extend(payload);
                    (op, p)
                }
                (op, _) => (op, payload),
            };
            if !fin {
                self.partial = Some((op, payload));
                continue;
            }
            match op {
                1 => out.push(Down::Text(String::from_utf8_lossy(&payload).into())),
                2 => out.push(Down::Binary(payload)),
                _ => {}
            }
        }
    }
}

fn pcm(b: &[u8]) -> Vec<i16> {
    b.as_chunks::<2>().0.iter().map(|c| i16::from_le_bytes(*c)).collect()
}

fn seq_of(s: &str, kind: &str) -> Option<u64> {
    let v: serde_json::Value = serde_json::from_str(s).ok()?;
    (v["type"] == kind).then(|| v["seq"].as_u64()).flatten()
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info").with_target(false).without_time().init();
    let a: Vec<String> = std::env::args().collect();
    let mut url = "ws://127.0.0.1:8790/call".to_string();
    let (mut drop_ms, mut down_ms, mut after_said, mut mid, mut stall, mut out, mut secs) = (None, 1000u64, false, false, None, None, 30u64);
    let mut it = a[2..].iter();
    while let Some(x) = it.next() {
        match x.as_str() {
            "--drop-ms" => drop_ms = it.next().map(|v| v.parse::<u128>().unwrap()),
            "--down-ms" => down_ms = it.next().unwrap().parse().unwrap(),
            "--after-said" => after_said = true,
            "--mid-sentence" => mid = true,
            "--stall" => stall = it.next().cloned(),
            "--out" => out = it.next().cloned(),
            "--secs" => secs = it.next().unwrap().parse().unwrap(),
            u => url = u.to_string(),
        }
    }
    let wav = std::fs::read(&a[1]).unwrap();
    let clip = wav[44..].to_vec();
    let over = stall.as_ref().map(|f| std::fs::read(f).unwrap()[44..].to_vec());
    if over.is_some() && drop_ms.is_none() {
        drop_ms = Some(0);
    }

    let t0 = Instant::now();
    let server = url.trim_start_matches("ws://").split('/').next().unwrap().to_string();
    let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = l.local_addr().unwrap().port();
    let proxy = Arc::new(Proxy {
        t0,
        server,
        cut: watch::channel(0).0,
        refuse_until: Mutex::new(None),
        stall_upto: AtomicU64::new(0),
        next: AtomicU64::new(0),
        tap: Mutex::new(Tap::default()),
    });
    tokio::spawn(proxy.clone().serve(l));
    let path = url.splitn(4, '/').nth(3).unwrap_or("call").to_string();
    let call = Call::start(websocket(format!("ws://127.0.0.1:{port}/{path}")), Arc::new(Log(t0)), Timing::default());
    tokio::time::sleep(Duration::from_millis(800)).await;

    let mut played: Vec<i16> = Vec::new();
    let mut tick = tokio::time::interval(Duration::from_millis(20));
    let silence = vec![0u8; 640];
    let mut mic: Vec<u8> = clip;
    let mut at = 0usize;
    let mut broke = false;
    let ticks = mic.len() / 640 + secs as usize * 50;
    for _ in 0..ticks {
        tick.tick().await;
        let f = mic.get(at..at + 640).unwrap_or(&silence).to_vec();
        at += 640;
        call.send_audio(f);
        played.extend(pcm(&call.pull_audio(320)));
        if let Some(d) = drop_ms.filter(|_| !broke) {
            let tap = proxy.tap.lock().unwrap();
            let said = tap.frames.iter().position(|(_, _, f)| matches!(f, Down::Text(s) if seq_of(s, "said").is_some()));
            let from = match (after_said, mid, said) {
                (true, _, Some(i)) => Some(tap.frames[i].1),
                (_, true, Some(i)) => tap.frames[i..].iter().find(|(_, _, f)| matches!(f, Down::Binary(_))).map(|f| f.1),
                (false, false, _) => tap.frames.iter().find(|(_, _, f)| matches!(f, Down::Binary(_))).map(|f| f.1),
                _ => None,
            };
            drop(tap);
            if from.is_some_and(|f| t0.elapsed().as_millis() >= f + d) {
                broke = true;
                let now = t0.elapsed().as_millis();
                if let Some(o) = &over {
                    println!("{now:>6} STALL: nothing more reaches the phone on socket {}; Aviv talks over Jarvis", proxy.next.load(Ordering::SeqCst));
                    proxy.stall_upto.store(proxy.next.load(Ordering::SeqCst), Ordering::SeqCst);
                    mic = o.clone();
                    at = 0;
                } else {
                    println!("{now:>6} DROP: the line is cut, dials refused for {down_ms} ms ({} ms of reply queued)", call.buffered_ms());
                    *proxy.refuse_until.lock().unwrap() = Some(Instant::now() + Duration::from_millis(down_ms));
                    proxy.cut.send_modify(|g| *g += 1);
                }
            }
        }
    }
    call.hangup();
    tokio::time::sleep(Duration::from_secs(3)).await;
    println!("final {:?}", call.status());

    let tap = proxy.tap.lock().unwrap();
    println!("\n--- what came down, by socket ---");
    let mut cur: HashMap<u64, Option<u64>> = HashMap::new();
    // Each sentence's audio as sent, per copy: (seq, socket) → samples, and whether `said` closed it.
    let mut copies: Vec<(u64, u64, Vec<i16>, bool, u128)> = Vec::new();
    for (id, t, f) in tap.frames.iter() {
        match f {
            Down::Text(s) => {
                if let Some(seq) = seq_of(s, "sentence") {
                    cur.insert(*id, Some(seq));
                    copies.push((seq, *id, Vec::new(), false, *t));
                } else if let Some(seq) = seq_of(s, "said")
                    && let Some(c) = copies.iter_mut().rev().find(|c| c.0 == seq && c.1 == *id)
                {
                    c.3 = true;
                }
                if !s.contains("\"text\"") {
                    println!("{t:>6} socket {id}: {s}");
                }
            }
            Down::Binary(b) => {
                if let Some(Some(seq)) = cur.get(id)
                    && let Some(c) = copies.iter_mut().rev().find(|c| c.0 == *seq && c.1 == *id)
                {
                    c.2.extend(pcm(b));
                }
            }
        }
    }
    for (seq, id, p, whole, t) in &copies {
        println!("{t:>6} socket {id} sentence {seq}: {} ms{}", p.len() / 16, if *whole { ", whole" } else { ", cut off" });
    }
    if let Some(o) = &out {
        write_wav(o, &played);
        println!("speaker → {o}");
    }

    // The reference: each sentence's longest copy.
    let mut refs: HashMap<u64, Vec<i16>> = HashMap::new();
    for (seq, _, p, _, _) in &copies {
        if refs.get(seq).is_none_or(|r| r.len() < p.len()) {
            refs.insert(*seq, p.clone());
        }
    }
    let mut index: HashMap<[i16; 8], Vec<(u64, usize)>> = HashMap::new();
    for (seq, r) in &refs {
        for i in 0..r.len().saturating_sub(8) {
            let k: [i16; 8] = r[i..i + 8].try_into().unwrap();
            if k.iter().any(|&x| x != 0) {
                index.entry(k).or_default().push((*seq, i));
            }
        }
    }
    let matches = |blk: &[i16], seq: u64, off: usize| -> bool {
        let r = &refs[&seq];
        let n = blk.len().min(r.len() - off);
        blk[..n] == r[off..off + n]
    };
    println!("\n--- what the speaker played (20 ms blocks, mapped onto the sentences) ---");
    // Runs: (start ms of the call's audio, blocks, what).
    let mut runs: Vec<(usize, usize, String)> = Vec::new();
    let mut expect: Option<(u64, usize)> = None;
    let mut run_from: Option<(u64, usize)> = None;
    let mut hits: Vec<(u64, usize, usize)> = Vec::new();
    let push_run = |runs: &mut Vec<(usize, usize, String)>, i: usize, what: String| match runs.last_mut() {
        Some(l) if l.2 == what => l.1 += 1,
        _ => runs.push((i, 1, what)),
    };
    for (i, blk) in played.chunks(320).enumerate() {
        if blk.iter().all(|&x| x == 0) {
            push_run(&mut runs, i, "silence".into());
            expect = None;
            continue;
        }
        let k: [i16; 8] = blk[..8].try_into().unwrap();
        let hit = expect
            .filter(|&(s, o)| refs.get(&s).is_some_and(|r| o < r.len()) && matches(blk, s, o))
            .or_else(|| index.get(&k).and_then(|v| v.iter().copied().find(|&(s, o)| matches(blk, s, o))));
        let Some((seq, off)) = hit else {
            push_run(&mut runs, i, "not Jarvis's voice (earcon)".into());
            expect = None;
            continue;
        };
        // This block's samples, split where it crosses into the next sentence.
        let len = refs[&seq].len();
        hits.push((seq, off, blk.len().min(len - off)));
        if off + blk.len() > len {
            hits.push((seq + 1, 0, off + blk.len() - len));
        }
        let continued = expect == Some((seq, off));
        if !continued {
            run_from = Some((seq, off));
        }
        let (fs, fo) = run_from.unwrap();
        let what = format!("sentence {fs} from {} ms", fo / 16);
        push_run(&mut runs, i, what);
        // Where the next block should start: on in this sentence, or into the next.
        expect = if off + 320 < len { Some((seq, off + 320)) } else { Some((seq + 1, off + 320 - len)) };
    }
    for (i, n, what) in &runs {
        if what == "silence" && *n < 3 {
            continue;
        }
        println!("{:>6} ms of speaker audio: {} ms of {what}", i * 20, n * 20);
    }
    // Per sentence: how much of it played, and how much of that twice (a restart, or a repeat).
    let mut spans: HashMap<u64, Vec<(usize, usize)>> = HashMap::new();
    for &(s, o, n) in &hits {
        spans.entry(s).or_default().push((o, o + n));
    }
    let mut seqs: Vec<_> = refs.keys().copied().collect();
    seqs.sort();
    for s in seqs {
        let mut v = spans.remove(&s).unwrap_or_default();
        let sum: usize = v.iter().map(|(a, b)| b - a).sum();
        v.sort();
        let mut union = 0;
        let mut end = 0;
        for (a, b) in v {
            let a = a.max(end);
            if b > a {
                union += b - a;
                end = b;
            }
        }
        let len = refs[&s].len();
        println!("sentence {s}: {} ms long, {} ms of it played, {} ms missed, {} ms played twice", len / 16, union / 16, (len - union.min(len)) / 16, (sum - union) / 16);
    }
}

fn write_wav(path: &str, s: &[i16]) {
    let data: Vec<u8> = s.iter().flat_map(|x| x.to_le_bytes()).collect();
    let mut h = Vec::new();
    h.extend(b"RIFF");
    h.extend((36 + data.len() as u32).to_le_bytes());
    h.extend(b"WAVEfmt ");
    h.extend(16u32.to_le_bytes());
    h.extend(1u16.to_le_bytes());
    h.extend(1u16.to_le_bytes());
    h.extend(16000u32.to_le_bytes());
    h.extend(32000u32.to_le_bytes());
    h.extend(2u16.to_le_bytes());
    h.extend(16u16.to_le_bytes());
    h.extend(b"data");
    h.extend((data.len() as u32).to_le_bytes());
    h.extend(data);
    std::fs::write(path, h).unwrap();
}
