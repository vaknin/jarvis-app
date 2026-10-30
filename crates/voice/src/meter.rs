//! The call screen's ring: how well Jarvis hears Aviv right now, 0 to 1, from each mic frame as it
//! really left the phone. The mic thread writes two atomics a frame and the screen reads them on its
//! frame clock: neither ever waits for the other.

use std::{
    sync::atomic::{AtomicU32, AtomicU64, Ordering},
    time::Instant,
};

/// The ring's range, a sent frame's rms in dB below full scale. Aviv's real calls: speech at -28
/// (90th percentile of frames) to -22 (99th) on the phone's mic, about -38 on the AirPods'. Their hiss
/// sits under the floor.
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

/// How well a frame is heard, from the rms it was sent at.
pub fn level(rms: f32) -> f32 {
    ((20.0 * (rms / 32768.0).log10() - FLOOR_DB) / (FULL_DB - FLOOR_DB)).clamp(0.0, 1.0)
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
    use crate::FRAME_BYTES;

    /// A 20 ms frame of a sine at this rms.
    fn tone(at: f32) -> Vec<u8> {
        (0..FRAME_BYTES / 2)
            .flat_map(|i| ((at * 1.414 * (i as f32 * 0.2).sin()) as i16).to_le_bytes())
            .collect()
    }

    #[test]
    fn rms_is_of_the_samples() {
        assert!((rms(&tone(1000.0)) - 1000.0).abs() < 20.0);
        assert_eq!(rms(&[0; FRAME_BYTES]), 0.0);
        assert_eq!(rms(&[]), 0.0);
    }

    #[test]
    fn silence_and_hiss_are_nothing() {
        assert_eq!(level(0.0), 0.0);
        // Hiss on a Bluetooth link is rms 8 to 24.
        assert_eq!(level(24.0), 0.0);
    }

    #[test]
    fn a_quiet_mic_reads_low_and_never_as_heard() {
        // The clips Jarvis lost on 2026-10-30, rms 100 to 114.
        assert!(level(110.0) < 0.1);
    }

    #[test]
    fn a_normal_voice_reads_as_heard() {
        // The AirPods on a good call, rms 400.
        assert!((0.3..0.5).contains(&level(400.0)));
        // RMS at -28 and -22 dB: speech at the 90th and 99th percentile of frames.
        assert!(level(1300.0) > 0.7);
        assert!(level(2600.0) > 0.97);
        assert_eq!(level(13000.0), 1.0);
    }

    #[test]
    fn a_mic_that_stopped_feeding_reads_as_nothing() {
        let m = MicMeter::default();
        m.set(0.8);
        assert_eq!(m.read(m.now() + 10), 0.8);
        assert_eq!(m.read(m.now() + 1000), 0.0);
    }
}
