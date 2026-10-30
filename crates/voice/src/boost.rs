//! A quiet headset's mic, brought up to where the server hears it. On some calls the AirPods' mic
//! comes over Bluetooth 15 to 30 dB down (2026-10-29 and 30, Aviv at a peak of 500 where he's usually at
//! 6000): the server's speech gate sits right there, so he's heard in pieces or not at all, and the
//! same clips times four come out word for word. The gain follows his speech: up fast when he's loud,
//! down slowly, frozen in silence so a pause doesn't turn the hiss up, and never so far that a frame
//! clips. It only ever raises: a mic at the usual level gets a little under ×2, a loud one nothing.

use crate::FRAME_BYTES;

/// Where speech should sit: the rms of a frame of speech on a normal AirPods call, geometric mean,
/// measured at 350 to 460 on four calls, plus a margin.
const TARGET: f32 = 600.0;
/// As far as it goes. Their hiss at ×40 started false speech at the server, at ×20 it didn't.
const MAX_GAIN: f32 = 20.0;
/// Before any speech: enough for a quiet line, and the limit below looks after a normal one.
const START_GAIN: f32 = 8.0;
/// A frame this loud (rms) counts as him; their hiss sits at 8 to 24.
const SPEECH_RMS: f32 = 30.0;
/// Under this rms a frame is hiss or Jarvis's own bleed, so the gain fades to nothing there.
const HISS_RMS: f32 = 20.0;
/// How much of the way each frame of speech moves the level: up within half a second of speech, down
/// over 10 s of it, so one loud plosive or a quieter word doesn't swing it.
const UP: f32 = 0.04;
const DOWN: f32 = 0.002;
/// No frame goes past this peak.
const LIMIT: f32 = 30_000.0;
/// One log line per this many frames: 2 s.
const LOG_EVERY: u32 = 100;

pub struct MicBoost {
    on: bool,
    /// His speech level, as the natural log of a frame's rms.
    level: f32,
    frames: u32,
    /// The loudest raw frame since the last log line.
    peak: f32,
}

impl Default for MicBoost {
    fn default() -> Self {
        MicBoost { on: false, level: (TARGET / START_GAIN).ln(), frames: 0, peak: 0.0 }
    }
}

impl MicBoost {
    /// On while the mic is a headset's. The level carries across a reopen of the same call's mic.
    pub fn set(&mut self, on: bool) {
        self.on = on;
    }

    /// Raises one frame (PCM16 LE) in place.
    pub fn apply(&mut self, pcm: &mut [u8]) {
        if !self.on || pcm.len() != FRAME_BYTES {
            return;
        }
        let (mut sum, mut peak) = (0.0, 0.0f32);
        for b in pcm.as_chunks::<2>().0 {
            let s = i16::from_le_bytes(*b) as f32;
            sum += s * s;
            peak = peak.max(s.abs());
        }
        let rms = (sum / (FRAME_BYTES / 2) as f32).sqrt();
        if rms >= SPEECH_RMS {
            let d = rms.ln() - self.level;
            self.level += d * if d > 0.0 { UP } else { DOWN };
        }
        let want = self.gain();
        // 1 at silence, the full gain from HISS_RMS up.
        let g = (1.0 + (want - 1.0) * (rms / HISS_RMS).min(1.0)).min(LIMIT / peak.max(1.0)).max(1.0);
        self.peak = self.peak.max(peak);
        self.frames += 1;
        if self.frames.is_multiple_of(LOG_EVERY) {
            tracing::info!("mic boost: x{want:.1}, peak {:.0}, speech at {:.0}", self.peak, self.level.exp());
            self.peak = 0.0;
        }
        if g > 1.0 {
            for b in pcm.as_chunks_mut::<2>().0 {
                let s = i16::from_le_bytes(*b) as f32 * g;
                b.copy_from_slice(&(s.round().clamp(-32767.0, 32767.0) as i16).to_le_bytes());
            }
        }
    }

    /// What speech is multiplied by now.
    fn gain(&self) -> f32 {
        (TARGET / self.level.exp()).clamp(1.0, MAX_GAIN)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 20 ms frame of a sine at this peak.
    fn tone(peak: f32) -> Vec<u8> {
        (0..FRAME_BYTES / 2)
            .flat_map(|i| {
                let s = peak * (i as f32 * 0.2).sin();
                (s as i16).to_le_bytes()
            })
            .collect()
    }

    fn peak(pcm: &[u8]) -> i32 {
        pcm.chunks_exact(2).map(|b| (i16::from_le_bytes([b[0], b[1]]) as i32).abs()).max().unwrap()
    }

    fn feed(b: &mut MicBoost, at: f32, frames: u32) -> Vec<u8> {
        let mut out = Vec::new();
        for _ in 0..frames {
            out = tone(at);
            b.apply(&mut out);
        }
        out
    }

    fn on() -> MicBoost {
        let mut b = MicBoost::default();
        b.set(true);
        b
    }

    #[test]
    fn off_leaves_the_frame_alone() {
        let mut b = MicBoost::default();
        assert_eq!(peak(&feed(&mut b, 300.0, 200)), peak(&tone(300.0)));
    }

    #[test]
    fn quiet_speech_comes_up_to_the_usual_level() {
        let mut b = on();
        let out = feed(&mut b, 200.0, 500);
        assert!((600..1200).contains(&(peak(&out) * 100 / 141)), "rms {}", peak(&out) * 100 / 141);
    }

    #[test]
    fn normal_speech_goes_through_as_it_is() {
        let mut b = on();
        feed(&mut b, 6000.0, 100);
        assert_eq!(peak(&feed(&mut b, 6000.0, 1)), peak(&tone(6000.0)));
    }

    #[test]
    fn the_first_word_of_a_normal_call_never_clips() {
        let mut b = on();
        for _ in 0..20 {
            let mut f = tone(12_000.0);
            b.apply(&mut f);
            assert!(peak(&f) <= LIMIT as i32 + 1, "{}", peak(&f));
        }
    }

    #[test]
    fn a_pause_does_not_turn_the_hiss_up() {
        let mut b = on();
        feed(&mut b, 6000.0, 100);
        let before = b.gain();
        // 5 minutes of hiss.
        feed(&mut b, 12.0, 15_000);
        assert_eq!(b.gain(), before);
    }

    #[test]
    fn silence_stays_silent_at_full_gain() {
        let mut b = on();
        feed(&mut b, 50.0, 5000);
        assert!(b.gain() > 15.0);
        let out = feed(&mut b, 2.0, 1);
        assert!(peak(&out) < 20, "{}", peak(&out));
    }

    #[test]
    fn a_loud_plosive_does_not_drop_the_gain() {
        let mut b = on();
        feed(&mut b, 200.0, 500);
        let g = b.gain();
        feed(&mut b, 5000.0, 2);
        assert!(b.gain() > g * 0.7, "{} after {g}", b.gain());
    }
}
