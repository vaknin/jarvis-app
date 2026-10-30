//! The call screen's ring: how well Jarvis hears Aviv right now, 0 to 1, from each mic frame as it
//! really left the phone, so after a headset's [`MicBoost`](crate::MicBoost). The mic thread writes two
//! atomics a frame and the screen reads them on its frame clock: neither ever waits for the other.

use std::{
    sync::atomic::{AtomicU32, AtomicU64, Ordering},
    time::Instant,
};

use crate::boost::SPEECH_RMS;

/// The ring's range, a sent frame's rms in dB below full scale. Aviv's real calls: speech at -28
/// (90th percentile of frames) to -22 (99th); a boosted headset's speech sits about -35, mid-ring.
const FLOOR_DB: f32 = -48.0;
const FULL_DB: f32 = -22.0;
/// No frame for this long: the mic reopening, or stuck. It reads as nothing, never as the last word.
const STALE_MS: u64 = 200;

/// A frame's (PCM16 LE) rms.
pub fn rms(pcm: &[u8]) -> f32 {
    let samples = pcm.as_chunks::<2>().0;
    let sum: f32 = samples.iter().map(|b| (i16::from_le_bytes(*b) as f32).powi(2)).sum();
    (sum / samples.len().max(1) as f32).sqrt()
}

/// How well a frame is heard, from its rms as the mic gave it (`raw`) and as it was sent. A frame
/// the boost takes for hiss reads as nothing however far it was raised: that's a pause, or a mic too
/// faint to carry a voice even at full boost, and it mustn't look heard.
pub fn level(raw: f32, sent: f32) -> f32 {
    if raw < SPEECH_RMS {
        return 0.0;
    }
    ((20.0 * (sent / 32768.0).log10() - FLOOR_DB) / (FULL_DB - FLOOR_DB)).clamp(0.0, 1.0)
}

pub struct MicMeter {
    start: Instant,
    level: AtomicU32,
    /// When it was written, ms after `start`.
    at: AtomicU64,
}

impl Default for MicMeter {
    fn default() -> Self {
        MicMeter { start: Instant::now(), level: AtomicU32::new(0), at: AtomicU64::new(0) }
    }
}

impl MicMeter {
    pub fn set(&self, level: f32) {
        self.level.store(level.to_bits(), Ordering::Relaxed);
        self.at.store(self.now(), Ordering::Relaxed);
    }

    pub fn get(&self) -> f32 {
        self.read(self.now())
    }

    fn read(&self, now: u64) -> f32 {
        if now.saturating_sub(self.at.load(Ordering::Relaxed)) > STALE_MS {
            return 0.0;
        }
        f32::from_bits(self.level.load(Ordering::Relaxed))
    }

    fn now(&self) -> u64 {
        self.start.elapsed().as_millis() as u64
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{FRAME_BYTES, MicBoost};

    /// A 20 ms frame of a sine at this rms.
    fn tone(at: f32) -> Vec<u8> {
        (0..FRAME_BYTES / 2)
            .flat_map(|i| ((at * 1.414 * (i as f32 * 0.2).sin()) as i16).to_le_bytes())
            .collect()
    }

    /// What the ring reads for one frame of `at` on a headset, after `speech` has set the boost.
    fn through_boost(speech: f32, frames: u32, at: f32) -> f32 {
        let mut b = MicBoost::default();
        b.set(true);
        for _ in 0..frames {
            b.apply(&mut tone(speech));
        }
        let mut f = tone(at);
        let raw = rms(&f);
        b.apply(&mut f);
        level(raw, rms(&f))
    }

    #[test]
    fn rms_is_of_the_samples() {
        assert!((rms(&tone(1000.0)) - 1000.0).abs() < 20.0);
        assert_eq!(rms(&[0; FRAME_BYTES]), 0.0);
        assert_eq!(rms(&[]), 0.0);
    }

    #[test]
    fn silence_is_nothing() {
        assert_eq!(level(0.0, 0.0), 0.0);
    }

    #[test]
    fn a_boosted_quiet_voice_reads_as_heard() {
        // Aviv's quiet AirPods calls: rms 100 to 114 on the clips Jarvis lost (2026-10-30).
        assert!(through_boost(110.0, 500, 110.0) > 0.45);
        // And the first word, before the boost has learned him.
        assert!(through_boost(110.0, 0, 110.0) > 0.45);
        // As faint as it can be and still be taken for a voice.
        assert!(through_boost(35.0, 3000, 35.0) > 0.45);
    }

    #[test]
    fn the_same_voice_unboosted_would_read_low() {
        assert!(level(110.0, 110.0) < 0.1);
    }

    #[test]
    fn a_mic_too_faint_even_at_full_boost_reads_as_nothing() {
        assert_eq!(through_boost(20.0, 2000, 20.0), 0.0);
        assert_eq!(through_boost(10.0, 2000, 10.0), 0.0);
    }

    #[test]
    fn hiss_in_the_pauses_stays_down_at_full_gain() {
        // Their hiss is rms 8 to 24; a voice at 35 has taken the gain nearly to the top.
        for hiss in [8.0, 16.0, 24.0] {
            assert_eq!(through_boost(35.0, 2000, hiss), 0.0, "{hiss}");
        }
    }

    #[test]
    fn a_phone_mic_goes_through_as_it_is() {
        // RMS at -28 and -22 dB: speech at the 90th and 99th percentile of frames.
        assert!(level(1300.0, 1300.0) > 0.7);
        assert!(level(2600.0, 2600.0) > 0.97);
        assert_eq!(level(13000.0, 13000.0), 1.0);
    }

    #[test]
    fn a_mic_that_stopped_feeding_reads_as_nothing() {
        let m = MicMeter::default();
        m.set(0.8);
        assert_eq!(m.read(m.now() + 10), 0.8);
        assert_eq!(m.read(m.now() + 1000), 0.0);
    }
}
