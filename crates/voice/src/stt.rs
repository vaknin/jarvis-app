//! The phone's own speech to text (thread #118, an `"stt":"phone"` hello): the Pixel's on-device
//! recognizer hears the mic frames this socket carried, and each `cut` from the server is answered
//! with the words it heard in that turn (`heard`).
//!
//! The recognizer runs in the app; this keeps the books. Every frame sent on the line is counted (the
//! server counts the same samples) and fed to the open session, and the last [`RING`] samples are kept.
//! A cut ends the session (the recognizer's final comes only at EOF, 0.4-1.6 s later) and opens the next
//! at the cut's end, replaying what the line carried since from the ring, so no word falls between two
//! sessions. Words come back with their start in ms from their session's first sample; kept in line
//! samples, a cut takes those that start in [`from` - [`LEAD`], `to`), after the last cut's end.

use std::collections::VecDeque;

use tokio::time::{Duration, Instant};

/// Samples of the line kept for the next session (30 s at 16 kHz).
pub const RING: usize = 30 * 16_000;
/// A word may start this much before the server's `from` (300 ms).
pub const LEAD: u64 = 300 * 16;
/// A cut whose session hasn't answered within this is dropped (the server waits 2 s, then uses Parakeet).
pub const GIVE_UP: Duration = Duration::from_secs(4);

/// One recognized word: its text as formatted, and its start in ms from its session's first sample.
#[derive(Debug, Clone, PartialEq)]
pub struct Word {
    pub text: String,
    pub start_ms: u64,
}

/// What the books ask of the app's recognizer, and of the line.
#[derive(Debug, Clone, PartialEq)]
pub enum Do {
    Open(u64),
    Audio(u64, Vec<u8>),
    /// No more audio: the recognizer finishes, and the app answers [`Stt::done`].
    Close(u64),
    /// Send `heard` for cut `id`.
    Heard { id: u64, text: Option<String>, ms: u64 },
}

struct Session {
    id: u64,
    /// The line it heard, by count: positions on two lines don't compare.
    line: u64,
    /// Its first sample, in line samples.
    start: u64,
    /// The cut that closed it ended here: its words from here on belong to the next session.
    end: Option<u64>,
}

struct Pending {
    cut: u64,
    from: u64,
    to: u64,
    session: u64,
    line: u64,
    at: Instant,
}

#[derive(Default)]
pub struct Stt {
    /// Samples carried by this line so far.
    sent: u64,
    /// The last samples sent, as bytes; its first is sample `sent - ring.len() / 2`.
    ring: VecDeque<u8>,
    open: Option<Session>,
    /// Closed, their words still to come.
    closing: Vec<Session>,
    next_id: u64,
    pending: Vec<Pending>,
    /// Lines so far: the current one's number.
    line: u64,
    /// Words heard lately: their line, and their start in its samples.
    words: Vec<(u64, u64, String)>,
    /// The last answered cut's end: its words aren't the next turn's.
    answered_to: u64,
}

impl Stt {
    /// A new line: its samples count from 0. The open session ends (its words still answer the cuts
    /// waiting on it); the next frame opens a new one.
    pub fn new_line(&mut self) -> Vec<Do> {
        let mut out = Vec::new();
        if let Some(s) = self.open.take() {
            out.push(Do::Close(s.id));
            if self.pending.iter().any(|p| p.session == s.id) {
                self.closing.push(s);
            }
        }
        self.line += 1;
        self.ring.clear();
        self.sent = 0;
        self.answered_to = 0;
        out
    }

    /// A frame went out on the line.
    pub fn sent(&mut self, pcm: &[u8]) -> Vec<Do> {
        let mut out = Vec::new();
        let id = match &self.open {
            Some(s) => s.id,
            None => {
                let id = self.open_at(self.sent);
                out.push(Do::Open(id));
                id
            }
        };
        out.push(Do::Audio(id, pcm.to_vec()));
        self.ring.extend(pcm);
        let over = self.ring.len().saturating_sub(RING * 2);
        self.ring.drain(..over);
        self.sent += pcm.len() as u64 / 2;
        out
    }

    fn open_at(&mut self, start: u64) -> u64 {
        self.next_id += 1;
        self.open = Some(Session { id: self.next_id, line: self.line, start, end: None });
        self.next_id
    }

    /// The server's `cut`: Aviv's turn `id` ran from sample `from` to `to` of this line.
    pub fn cut(&mut self, id: u64, from: u64, to: u64, now: Instant) -> Vec<Do> {
        let mut out = Vec::new();
        let Some(mut s) = self.open.take_if(|s| s.start < to) else {
            // No session heard any of it (none open, or it opened after the turn): its words are
            // the ones already in, if any.
            out.push(self.answer(id, self.line, from, to, true, now, now));
            return out;
        };
        out.push(Do::Close(s.id));
        s.end = Some(to);
        self.pending.push(Pending { cut: id, from, to, session: s.id, line: s.line, at: now });
        self.closing.push(s);
        // The next session starts where this turn ended, with what the line carried since.
        let first = self.sent - self.ring.len() as u64 / 2;
        let start = to.clamp(first, self.sent);
        let next = self.open_at(start);
        out.push(Do::Open(next));
        let skip = ((start - first) * 2) as usize;
        let (a, b) = self.ring.as_slices();
        let back: Vec<u8> = a.iter().chain(b).skip(skip).copied().collect();
        if !back.is_empty() {
            out.push(Do::Audio(next, back));
        }
        out
    }

    /// Session `id` is over: its words, or `None` if the recognizer failed. Answers the cuts that
    /// waited on it. A session that ended on its own (the recognizer gave up) is replaced at the
    /// next frame.
    pub fn done(&mut self, id: u64, words: Option<Vec<Word>>, now: Instant) -> Vec<Do> {
        let s = if self.open.as_ref().is_some_and(|s| s.id == id) {
            tracing::info!("voice stt: session {id} ended before a cut");
            self.open.take()
        } else {
            self.closing.iter().position(|s| s.id == id).map(|i| self.closing.remove(i))
        };
        let Some(s) = s else { return Vec::new() };
        let ok = words.is_some();
        for w in words.unwrap_or_default() {
            let at = s.start + w.start_ms * 16;
            if s.end.is_none_or(|e| at < e) {
                self.words.push((s.line, at, w.text));
            }
        }
        self.words.sort_by_key(|w| (w.0, w.1));
        let mut out = Vec::new();
        let (mine, rest): (Vec<_>, Vec<_>) = std::mem::take(&mut self.pending).into_iter().partition(|p| p.session == id);
        self.pending = rest;
        for p in mine {
            out.push(self.answer(p.cut, p.line, p.from, p.to, ok, p.at, now));
        }
        // Words older than the ring, or from a line no cut waits on, answer nothing any more.
        let old = self.sent.saturating_sub(RING as u64);
        let lines: Vec<u64> = self.pending.iter().map(|p| p.line).collect();
        self.words.retain(|w| if w.0 == self.line { w.1 >= old } else { lines.contains(&w.0) });
        out
    }

    #[allow(clippy::too_many_arguments)]
    fn answer(&mut self, cut: u64, line: u64, from: u64, to: u64, ok: bool, at: Instant, now: Instant) -> Do {
        let mut low = from.saturating_sub(LEAD);
        if line == self.line {
            low = low.max(self.answered_to);
            self.answered_to = self.answered_to.max(to);
        }
        let text = ok.then(|| {
            self.words.iter().filter(|w| w.0 == line && w.1 >= low && w.1 < to).map(|w| w.2.trim()).collect::<Vec<_>>().join(" ")
        });
        Do::Heard { id: cut, text, ms: (now - at).as_millis() as u64 }
    }

    /// Cuts whose session never answered are dropped (the server has used Parakeet's words).
    pub fn tick(&mut self, now: Instant) {
        let before = self.pending.len();
        self.pending.retain(|p| now - p.at < GIVE_UP);
        if self.pending.len() < before {
            tracing::info!("voice stt: {} cut(s) never answered", before - self.pending.len());
            let live: Vec<u64> = self.pending.iter().map(|p| p.session).collect();
            self.closing.retain(|s| live.contains(&s.id));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(v: i16) -> Vec<u8> {
        (0..320).flat_map(|_| v.to_le_bytes()).collect()
    }

    fn w(text: &str, start_ms: u64) -> Word {
        Word { text: text.into(), start_ms }
    }

    fn heard(d: &[Do]) -> Vec<(u64, Option<String>)> {
        d.iter().filter_map(|d| if let Do::Heard { id, text, .. } = d { Some((*id, text.clone())) } else { None }).collect()
    }

    #[test]
    fn the_first_frame_opens_a_session_and_feeds_it() {
        let mut s = Stt::default();
        assert_eq!(s.sent(&frame(1)), vec![Do::Open(1), Do::Audio(1, frame(1))]);
        assert_eq!(s.sent(&frame(2)), vec![Do::Audio(1, frame(2))]);
    }

    #[test]
    fn a_cut_closes_reopens_from_the_ring_and_answers_with_its_words() {
        let now = Instant::now();
        let mut s = Stt::default();
        // 100 frames = 2 s = 32000 samples.
        for i in 0..100 {
            s.sent(&frame(i));
        }
        // The turn ran 0.5 s to 1.5 s; the cut comes at 2 s.
        let d = s.cut(7, 8000, 24000, now);
        assert_eq!(d[0], Do::Close(1));
        assert_eq!(d[1], Do::Open(2));
        // The new session replays 1.5 s to 2 s: frames 75..100.
        let Do::Audio(2, back) = &d[2] else { panic!("{d:?}") };
        assert_eq!(back.len(), 25 * 640);
        assert_eq!(&back[..640], &frame(75)[..]);
        // Words: one before the lead, two inside, one after the turn's end (the next session's).
        let d = s.done(1, Some(vec![w("Noise.", 100), w("Check", 300), w("it.", 1200), w("Later.", 1600)]), now + Duration::from_millis(900));
        assert_eq!(heard(&d), vec![(7, Some("Check it.".into()))]);
        let Do::Heard { ms, .. } = d[0] else { panic!() };
        assert_eq!(ms, 900);
        // The next session's words start at its own first sample (1.5 s): "Later." is the next turn's.
        s.sent(&frame(0));
        let d = s.cut(8, 24000, 32320, now);
        assert_eq!(d[0], Do::Close(2));
        let d = s.done(2, Some(vec![w("Later.", 100)]), now);
        assert_eq!(heard(&d), vec![(8, Some("Later.".into()))]);
    }

    #[test]
    fn a_turn_overlapping_the_last_one_gets_only_its_new_words() {
        let now = Instant::now();
        let mut s = Stt::default();
        for i in 0..100 {
            s.sent(&frame(i));
        }
        s.cut(1, 0, 16000, now);
        s.done(1, Some(vec![w("One.", 0), w("Two.", 1200)]), now);
        // The second turn's `from` reaches back into the first (the server pads its start).
        s.cut(2, 8000, 32000, now);
        let d = s.done(2, Some(vec![w("Three.", 300)]), now);
        assert_eq!(heard(&d), vec![(2, Some("Three.".into()))]);
    }

    #[test]
    fn a_failed_recognizer_answers_null_and_a_cut_with_no_session_answers_at_once() {
        let now = Instant::now();
        let mut s = Stt::default();
        assert_eq!(heard(&s.cut(1, 0, 100, now)), vec![(1, Some(String::new()))]);
        s.sent(&frame(1));
        s.cut(2, 0, 320, now);
        assert_eq!(heard(&s.done(1, None, now)), vec![(2, None)]);
    }

    #[test]
    fn a_new_line_counts_from_zero_and_its_old_session_still_answers() {
        let now = Instant::now();
        let mut s = Stt::default();
        for _ in 0..10 {
            s.sent(&frame(1));
        }
        s.cut(1, 0, 3200, now);
        // Session 2 (opened by the cut) ends with the line.
        assert_eq!(s.new_line(), vec![Do::Close(2)]);
        assert_eq!(s.sent(&frame(1)), vec![Do::Open(3), Do::Audio(3, frame(1))]);
        assert_eq!(heard(&s.done(1, Some(vec![w("Hi.", 0)]), now)), vec![(1, Some("Hi.".into()))]);
        assert!(s.done(2, Some(vec![w("Lost.", 0)]), now).is_empty());
    }

    #[test]
    fn unanswered_cuts_are_dropped() {
        let now = Instant::now();
        let mut s = Stt::default();
        s.sent(&frame(1));
        s.cut(1, 0, 320, now);
        s.tick(now + GIVE_UP);
        assert!(s.done(1, Some(vec![w("Late.", 0)]), now).is_empty());
    }

    #[test]
    fn the_ring_keeps_thirty_seconds() {
        let mut s = Stt::default();
        for _ in 0..1600 {
            s.sent(&frame(1));
        }
        assert_eq!(s.ring.len(), RING * 2);
        assert_eq!(s.sent, 1600 * 320);
    }
}
