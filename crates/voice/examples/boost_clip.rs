//! Runs a 16 kHz mono WAV (a saved turn) through `MicBoost`: `boost_clip IN.wav OUT.wav`.
use std::{env, fs};
use tether_voice::{FRAME_BYTES, MicBoost};

fn main() {
    let a: Vec<String> = env::args().collect();
    let wav = fs::read(&a[1]).unwrap();
    let (head, pcm) = wav.split_at(44);
    let mut b = MicBoost::default();
    b.set(true);
    let mut out = head.to_vec();
    let frames = pcm.chunks_exact(FRAME_BYTES);
    let tail = frames.remainder();
    for f in frames {
        let mut f = f.to_vec();
        b.apply(&mut f);
        out.extend(f);
    }
    out.extend(tail);
    fs::write(&a[2], out).unwrap();
}
