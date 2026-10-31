//! Thread #195's rule, in one place: which mic hears Aviv, and whether Jarvis speaks. Pure, so every
//! case is a test; the app only reports what it senses and does what comes back, and logs
//! [`Pick::rule`], so one real run says which rule picked the mic.
//!
//! Being on a call decides who speaks, nothing else does: the screen doesn't, and neither does
//! another app in front. On a call Jarvis speaks and the mic is the Lark if it's plugged in, else
//! the AirPods or a headset if connected, else the phone's. Dictating, Jarvis writes and the mic is
//! the Lark, else the phone's, so the AirPods keep their music at full quality. The pick reads no
//! screen, so a screen turning over mid-call can't flip the mic.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Sense {
    pub lark: bool,
    /// AirPods or any other headset with a mic of its own.
    pub airpods: bool,
    /// A real call he started or answered. Otherwise he's dictating.
    pub call: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mic {
    Lark,
    Phone,
    AirPods,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pick {
    pub mic: Mic,
    pub speak: bool,
    /// Android's call route: only for what truly needs it. It forces the AirPods into call quality.
    pub telecom: bool,
    /// Which rule, for the log.
    pub rule: &'static str,
}

pub fn pick(s: Sense) -> Pick {
    let (mic, rule) = match () {
        _ if s.call && s.lark => (Mic::Lark, "call: lark plugged in"),
        _ if s.call && s.airpods => (Mic::AirPods, "call: airpods mic"),
        _ if s.call => (Mic::Phone, "call: phone mic"),
        _ if s.lark => (Mic::Lark, "dictation: lark plugged in"),
        _ => (Mic::Phone, "dictation: phone mic"),
    };
    // The Lark records on its own, in stereo at 48 kHz; the call route would take it away.
    Pick { mic, speak: s.call, telecom: s.call && mic != Mic::Lark, rule }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(lark: bool, airpods: bool, call: bool) -> Pick {
        pick(Sense { lark, airpods, call })
    }

    #[test]
    fn every_case() {
        let cases = [
            ((true, true, true), Mic::Lark, true, false, "call: lark plugged in"),
            ((true, false, true), Mic::Lark, true, false, "call: lark plugged in"),
            ((false, true, true), Mic::AirPods, true, true, "call: airpods mic"),
            ((false, false, true), Mic::Phone, true, true, "call: phone mic"),
            ((true, true, false), Mic::Lark, false, false, "dictation: lark plugged in"),
            ((true, false, false), Mic::Lark, false, false, "dictation: lark plugged in"),
            ((false, true, false), Mic::Phone, false, false, "dictation: phone mic"),
            ((false, false, false), Mic::Phone, false, false, "dictation: phone mic"),
        ];
        for ((lark, airpods, call), mic, speak, telecom, rule) in cases {
            assert_eq!(at(lark, airpods, call), Pick { mic, speak, telecom, rule }, "{lark} {airpods} {call}");
        }
    }

    #[test]
    fn dictating_never_touches_the_airpods() {
        for lark in [false, true] {
            let p = at(lark, true, false);
            assert!(p.mic != Mic::AirPods && !p.telecom && !p.speak);
        }
    }

    #[test]
    fn jarvis_speaks_exactly_on_a_call() {
        for (lark, airpods) in [(false, false), (false, true), (true, false), (true, true)] {
            assert!(at(lark, airpods, true).speak);
            assert!(!at(lark, airpods, false).speak);
        }
    }
}
