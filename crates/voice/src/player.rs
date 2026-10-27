//! What the speaker plays: Jarvis's voice through a small jitter buffer, with the earcons mixed on
//! top. The app's audio thread pulls a fixed number of samples every few milliseconds and always
//! gets that many (silence when there's nothing), so its blocking writes pace it.
//!
//! The jitter buffer waits until it holds [`Player::new`]'s target (about 120 ms, for mobile data)
//! before it plays, and again after it runs dry. A reply shorter than that still plays once no new
//! audio has come for the same time. `flush` (Aviv talked over Jarvis) empties it at once; earcons
//! aren't voice and stay.

use std::{collections::VecDeque, f32::consts::TAU, time::Duration};

use tokio::time::Instant;

use crate::RATE;

/// The short sounds that tell Aviv what's happening without looking.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Earcon {
    /// The call is up (also after a reconnect): two rising notes.
    Connected,
    /// Jarvis heard the end of what Aviv said: one soft tick.
    Thinking,
    /// The line dropped and the phone is reconnecting: two falling notes.
    Dropped,
    /// The call ended: three falling notes.
    Ended,
}

impl Earcon {
    /// (frequency in Hz, 0 for a pause; length in ms), and the loudness (0..1).
    fn notes(self) -> (&'static [(f32, u32)], f32) {
        match self {
            Earcon::Connected => (&[(660.0, 90), (0.0, 30), (990.0, 130)], 0.25),
            Earcon::Thinking => (&[(1320.0, 40)], 0.08),
            Earcon::Dropped => (&[(784.0, 130), (0.0, 50), (523.0, 200)], 0.25),
            Earcon::Ended => (&[(523.0, 120), (0.0, 30), (392.0, 120), (0.0, 30), (262.0, 240)], 0.25),
        }
    }

    /// The earcon as PCM samples at [`RATE`], each note faded in and out so it doesn't click.
    pub fn samples(self) -> Vec<i16> {
        let (notes, amp) = self.notes();
        let fade = (RATE / 200) as usize; // 5 ms
        let mut out = Vec::new();
        for &(hz, ms) in notes {
            let n = (RATE * ms / 1000) as usize;
            for i in 0..n {
                let env = (i.min(n - 1 - i) as f32 / fade as f32).min(1.0);
                let s = if hz == 0.0 { 0.0 } else { (TAU * hz * i as f32 / RATE as f32).sin() * amp * env };
                out.push((s * i16::MAX as f32) as i16);
            }
        }
        out
    }
}

pub struct Player {
    voice: VecDeque<i16>,
    /// An odd byte left from the last chunk (chunks may be any length).
    carry: Option<u8>,
    playing: bool,
    last_push: Option<Instant>,
    target: usize,
    target_time: Duration,
    earcon: VecDeque<i16>,
    ended: bool,
}

impl Player {
    pub fn new(target: Duration) -> Player {
        Player {
            voice: VecDeque::new(),
            carry: None,
            playing: false,
            last_push: None,
            target: (RATE as u128 * target.as_millis() / 1000) as usize,
            target_time: target,
            earcon: VecDeque::new(),
            ended: false,
        }
    }

    /// Jarvis's audio as it came off the wire: PCM16 little-endian.
    pub fn push(&mut self, pcm: &[u8], now: Instant) {
        if self.ended {
            return;
        }
        let mut bytes = pcm.iter().copied();
        if let Some(lo) = self.carry.take() {
            match bytes.next() {
                Some(hi) => self.voice.push_back(i16::from_le_bytes([lo, hi])),
                None => self.carry = Some(lo),
            }
        }
        let rest: Vec<u8> = bytes.collect();
        let (pairs, odd) = rest.as_chunks::<2>();
        self.voice.extend(pairs.iter().map(|&b| i16::from_le_bytes(b)));
        if let [b] = odd {
            self.carry = Some(*b);
        }
        self.last_push = Some(now);
    }

    /// Drops every queued sample of Jarvis's voice now.
    pub fn flush(&mut self) {
        self.voice.clear();
        self.carry = None;
        self.playing = false;
    }

    pub fn earcon(&mut self, e: Earcon) {
        self.earcon.extend(e.samples());
    }

    /// The call is over: no more voice; what's left of the earcons still plays.
    pub fn end(&mut self) {
        self.flush();
        self.ended = true;
    }

    /// Milliseconds of voice waiting.
    pub fn buffered_ms(&self) -> u64 {
        self.voice.len() as u64 * 1000 / RATE as u64
    }

    /// The next `n` samples as PCM16 little-endian bytes (silence where there's nothing). After
    /// [`Player::end`], once the last earcon has played, it returns nothing: the audio thread stops.
    pub fn pull(&mut self, n: usize, now: Instant) -> Vec<u8> {
        if self.ended && self.earcon.is_empty() {
            return Vec::new();
        }
        if !self.playing && !self.voice.is_empty() {
            let quiet = self.last_push.is_some_and(|t| now.saturating_duration_since(t) >= self.target_time);
            self.playing = self.voice.len() >= self.target || quiet;
        }
        let mut out = vec![0i16; n];
        if self.playing {
            let take = n.min(self.voice.len());
            for (o, s) in out.iter_mut().zip(self.voice.drain(..take)) {
                *o = s;
            }
            if self.voice.is_empty() {
                // Ran dry: the reply ended, or the network is late. Wait for the target again.
                self.playing = false;
            }
        }
        for o in out.iter_mut() {
            match self.earcon.pop_front() {
                Some(e) => *o = o.saturating_add(e),
                None => break,
            }
        }
        out.iter().flat_map(|s| s.to_le_bytes()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: usize = (RATE / 1000) as usize;

    fn pcm(samples: usize, value: i16) -> Vec<u8> {
        (0..samples).flat_map(|_| value.to_le_bytes()).collect()
    }

    fn samples(b: &[u8]) -> Vec<i16> {
        b.as_chunks::<2>().0.iter().map(|c| i16::from_le_bytes([c[0], c[1]])).collect()
    }

    fn player() -> Player {
        Player::new(Duration::from_millis(120))
    }

    #[test]
    fn waits_for_the_target_before_playing() {
        let mut p = player();
        let t = Instant::now();
        p.push(&pcm(100 * MS, 7), t);
        assert!(samples(&p.pull(20 * MS, t)).iter().all(|&s| s == 0), "100 ms isn't enough yet");
        p.push(&pcm(20 * MS, 7), t);
        let out = samples(&p.pull(20 * MS, t));
        assert_eq!(out.len(), 20 * MS);
        assert!(out.iter().all(|&s| s == 7));
        assert_eq!(p.buffered_ms(), 100);
    }

    #[test]
    fn a_short_reply_plays_once_nothing_more_comes() {
        let mut p = player();
        let t = Instant::now();
        p.push(&pcm(40 * MS, 5), t);
        assert!(samples(&p.pull(20 * MS, t + Duration::from_millis(60))).iter().all(|&s| s == 0));
        let out = samples(&p.pull(20 * MS, t + Duration::from_millis(120)));
        assert!(out.iter().all(|&s| s == 5));
    }

    #[test]
    fn running_dry_pads_with_silence_and_waits_again() {
        let mut p = player();
        let t = Instant::now();
        p.push(&pcm(130 * MS, 3), t);
        for _ in 0..6 {
            p.pull(20 * MS, t);
        }
        let out = samples(&p.pull(20 * MS, t));
        assert_eq!(&out[..10 * MS], &vec![3; 10 * MS][..]);
        assert!(out[10 * MS..].iter().all(|&s| s == 0));
        // New audio arrives a little: it waits for the target again instead of stuttering.
        p.push(&pcm(30 * MS, 4), t);
        assert!(samples(&p.pull(20 * MS, t)).iter().all(|&s| s == 0));
    }

    #[test]
    fn flush_drops_everything_queued_at_once() {
        let mut p = player();
        let t = Instant::now();
        p.push(&pcm(5000 * MS, 9), t);
        assert!(samples(&p.pull(20 * MS, t)).iter().all(|&s| s == 9));
        p.flush();
        assert_eq!(p.buffered_ms(), 0);
        assert!(samples(&p.pull(20 * MS, t + Duration::from_secs(1))).iter().all(|&s| s == 0));
    }

    #[test]
    fn odd_length_chunks_keep_samples_whole() {
        let mut p = Player::new(Duration::ZERO);
        let t = Instant::now();
        let bytes: Vec<u8> = [1000i16, -2000, 3000].iter().flat_map(|s| s.to_le_bytes()).collect();
        p.push(&bytes[..1], t);
        p.push(&bytes[1..4], t);
        p.push(&bytes[4..], t);
        assert_eq!(samples(&p.pull(3, t)), vec![1000, -2000, 3000]);
    }

    #[test]
    fn earcons_mix_over_voice_and_survive_a_flush() {
        let mut p = Player::new(Duration::ZERO);
        let t = Instant::now();
        p.earcon(Earcon::Connected);
        let len = Earcon::Connected.samples().len();
        p.push(&pcm(10, 100), t);
        p.flush();
        let out = samples(&p.pull(len, t));
        assert_eq!(out, Earcon::Connected.samples());
        assert!(out.iter().any(|&s| s.abs() > 5000), "audible");
    }

    #[test]
    fn after_the_end_only_the_last_earcon_plays_then_nothing() {
        let mut p = Player::new(Duration::ZERO);
        let t = Instant::now();
        p.push(&pcm(1000, 100), t);
        p.earcon(Earcon::Ended);
        p.end();
        p.push(&pcm(1000, 100), t);
        let len = Earcon::Ended.samples().len();
        let mut got = 0;
        loop {
            let b = p.pull(320, t);
            if b.is_empty() {
                break;
            }
            got += b.len() / 2;
        }
        assert!(got >= len && got < len + 320);
    }
}
