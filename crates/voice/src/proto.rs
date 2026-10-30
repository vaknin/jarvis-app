//! The JSON control frames (PROTOCOL.md "Text frames").

use serde::{Deserialize, Serialize};

/// Phone → server.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Up {
    Hello {
        version: u32,
        resume: Option<String>,
        /// A new call placed about one thread (the Call button on its notification); never on a resume.
        #[serde(skip_serializing_if = "Option::is_none")]
        about: Option<About>,
        /// `Some("phone")`: this phone transcribes Aviv's turns itself, and answers each `cut` with `heard`.
        #[serde(skip_serializing_if = "Option::is_none")]
        stt: Option<&'static str>,
        /// Back to the dropped call `resume` after leaving it (the app died, or Aviv answered Jarvis
        /// calling back), not a redial: Jarvis opens with where they were cut off.
        #[serde(skip_serializing_if = "std::ops::Not::not")]
        rejoin: bool,
        /// Not a call: Aviv hung up the dropped call `resume` (declined the call back, or the line
        /// didn't carry his hangup). The server ends it and closes.
        #[serde(skip_serializing_if = "std::ops::Not::not")]
        end: bool,
        /// (A resume) the last of Jarvis's sentences this phone holds whole: the server sends the
        /// ones after it again. `None` from a phone that holds nothing of the call (a restart).
        #[serde(skip_serializing_if = "Option::is_none")]
        have: Option<u64>,
        /// (A resume) Jarvis's audio still queued here to play, in ms.
        #[serde(skip_serializing_if = "Option::is_none")]
        queued_ms: Option<u64>,
    },
    /// The phone's words for `cut` `id` (`None`: it has none, the server uses its own); `ms`: its
    /// recognizer's time after the cut.
    Heard { id: u64, text: Option<String>, ms: u64 },
    Mute { on: bool },
    /// Aviv tapped pause (Jarvis goes quiet, his reply kept) or play (it goes on).
    Pause { on: bool },
    Hangup,
}

/// What a call is about: the thread whose notification it was placed from, so Jarvis opens with it.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct About {
    pub topic: u64,
    pub title: String,
    /// The newest posts the notification showed, as plain text.
    pub text: String,
}

impl Up {
    pub fn json(&self) -> String {
        serde_json::to_string(self).expect("plain enum")
    }
}

/// Server → phone. Anything this version doesn't know is [`Down::Unknown`] and ignored.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Down {
    Ready { call: String },
    State { state: String },
    Flush,
    Text { who: String, text: String },
    /// Jarvis's reply is paused (a tap, "hold", or Aviv talking over him) or not any more.
    Paused { on: bool },
    Error { text: String },
    /// (An `"stt":"phone"` hello only) Aviv's turn `id` ran from sample `from` to `to` of this socket's mic audio.
    Cut { id: u64, from: u64, to: u64 },
    /// The audio that follows is Jarvis's sentence `seq` (call-wide, from 1), from its start.
    Sentence { seq: u64 },
    /// Sentence `seq`'s last sample has been sent.
    Said { seq: u64 },
    #[serde(other)]
    Unknown,
}

impl Down {
    pub fn parse(s: &str) -> Option<Down> {
        serde_json::from_str(s).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn up_is_the_contracts_json() {
        assert_eq!(Up::Hello { version: 1, resume: None, about: None, stt: None, rejoin: false, end: false, have: None, queued_ms: None }.json(), r#"{"type":"hello","version":1,"resume":null}"#);
        assert_eq!(
            Up::Hello { version: 1, resume: Some("c1".into()), about: None, stt: None, rejoin: false, end: false, have: None, queued_ms: None }.json(),
            r#"{"type":"hello","version":1,"resume":"c1"}"#
        );
        let about = About { topic: 112, title: "One app".into(), text: "Keep it?".into() };
        assert_eq!(
            Up::Hello { version: 1, resume: None, about: Some(about), stt: None, rejoin: false, end: false, have: None, queued_ms: None }.json(),
            r#"{"type":"hello","version":1,"resume":null,"about":{"topic":112,"title":"One app","text":"Keep it?"}}"#
        );
        assert_eq!(
            Up::Hello { version: 1, resume: None, about: None, stt: Some("phone"), rejoin: false, end: false, have: None, queued_ms: None }.json(),
            r#"{"type":"hello","version":1,"resume":null,"stt":"phone"}"#
        );
        assert_eq!(
            Up::Hello { version: 1, resume: Some("c1".into()), about: None, stt: None, rejoin: false, end: false, have: Some(7), queued_ms: Some(1200) }.json(),
            r#"{"type":"hello","version":1,"resume":"c1","have":7,"queued_ms":1200}"#
        );
        assert_eq!(Up::Heard { id: 3, text: Some("Check it.".into()), ms: 900 }.json(), r#"{"type":"heard","id":3,"text":"Check it.","ms":900}"#);
        assert_eq!(Up::Heard { id: 4, text: None, ms: 0 }.json(), r#"{"type":"heard","id":4,"text":null,"ms":0}"#);
        assert_eq!(Up::Mute { on: true }.json(), r#"{"type":"mute","on":true}"#);
        assert_eq!(Up::Pause { on: false }.json(), r#"{"type":"pause","on":false}"#);
        assert_eq!(Up::Hangup.json(), r#"{"type":"hangup"}"#);
    }

    #[test]
    fn down_parses_every_frame_and_skips_unknown_ones() {
        assert_eq!(Down::parse(r#"{"type":"ready","call":"abc"}"#), Some(Down::Ready { call: "abc".into() }));
        assert_eq!(Down::parse(r#"{"type":"state","state":"thinking"}"#), Some(Down::State { state: "thinking".into() }));
        assert_eq!(Down::parse(r#"{"type":"flush"}"#), Some(Down::Flush));
        assert_eq!(
            Down::parse(r#"{"type":"text","who":"jarvis","text":"hi"}"#),
            Some(Down::Text { who: "jarvis".into(), text: "hi".into() })
        );
        assert_eq!(Down::parse(r#"{"type":"paused","on":true}"#), Some(Down::Paused { on: true }));
        assert_eq!(Down::parse(r#"{"type":"error","text":"x"}"#), Some(Down::Error { text: "x".into() }));
        assert_eq!(Down::parse(r#"{"type":"cut","id":2,"from":16000,"to":48000}"#), Some(Down::Cut { id: 2, from: 16000, to: 48000 }));
        assert_eq!(Down::parse(r#"{"type":"sentence","seq":4}"#), Some(Down::Sentence { seq: 4 }));
        assert_eq!(Down::parse(r#"{"type":"said","seq":4}"#), Some(Down::Said { seq: 4 }));
        assert_eq!(Down::parse(r#"{"type":"later","x":1}"#), Some(Down::Unknown));
        assert_eq!(Down::parse("not json"), None);
    }
}
