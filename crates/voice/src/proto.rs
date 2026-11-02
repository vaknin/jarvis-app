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
        /// (A new call) where it stands: a tab's id, `""` the wall, so his first words open a new
        /// tab. `None`: the server's pick, which for a ring being answered is the tab that asked.
        #[serde(skip_serializing_if = "Option::is_none")]
        tab: Option<String>,
        /// (A new call) `Some(false)`: he dictates, Jarvis writes. `None` is a call.
        #[serde(skip_serializing_if = "Option::is_none")]
        speak: Option<bool>,
    },
    Mute { on: bool },
    /// Aviv tapped pause (Jarvis goes quiet, his reply kept) or play (it goes on).
    Pause { on: bool },
    /// Where he is. `tab`: its id, none at the wall. `speak`: he hears Jarvis, else Jarvis writes.
    /// `stay`: he never opened a tab this time: only `speak` counts, the server keeps the tab he was on.
    Where {
        tab: Option<String>,
        speak: bool,
        #[serde(skip_serializing_if = "std::ops::Not::not")]
        stay: bool,
    },
    /// What he said on screen: `send` goes, otherwise it's dropped (the phone took it into a box).
    Words { send: bool },
    /// Answering a `mictest` on: the mic it records from and whether the phone's noise filter is on it.
    Micsetup { mic: String, ns: Option<bool> },
    /// Through a mic test on the Lark: its left channel as it came, 48 kHz PCM16 LE, base64.
    Raw { pcm: String },
    Hangup,
}

/// What a call is about: the thread whose notification it was placed from, so Jarvis opens with it.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct About {
    pub topic: u64,
    pub title: String,
    /// The newest posts the notification showed, as plain text.
    pub text: String,
    /// A tracked task's Call Jarvis button: its slug, and the server loads its file for the call.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub task: Option<String>,
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
    /// Aviv's mic is muted by voice ("mute my mic") or not any more: the phone keeps streaming
    /// (the server listens for "unmute"); only the screen shows it.
    Muted { on: bool },
    Error { text: String },
    /// The audio that follows is Jarvis's sentence `seq` (call-wide, from 1), from its start.
    Sentence { seq: u64 },
    /// Sentence `seq`'s last sample has been sent.
    Said { seq: u64 },
    /// All he has said and not sent yet; empty once it's gone.
    Words { text: String },
    /// His words opened tab `n`, or a word moved him there.
    Tab { id: String, n: u32 },
    /// One line for the ongoing notification: no sound, no ping.
    Line { text: String },
    /// A mic test starts or ends (the server's `mictest.rs`). On: answer `micsetup` and, on the Lark,
    /// send its raw audio as `raw` until off. `ns`: the phone's noise filter for the test, else its setting.
    Mictest {
        on: bool,
        #[serde(default)]
        ns: Option<bool>,
    },
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
        assert_eq!(Up::Hello { version: 1, resume: None, about: None, rejoin: false, end: false, have: None, queued_ms: None, tab: None, speak: None }.json(), r#"{"type":"hello","version":1,"resume":null}"#);
        assert_eq!(
            Up::Hello { version: 1, resume: Some("c1".into()), about: None, rejoin: false, end: false, have: None, queued_ms: None, tab: None, speak: None }.json(),
            r#"{"type":"hello","version":1,"resume":"c1"}"#
        );
        assert_eq!(
            Up::Hello { version: 1, resume: None, about: None, rejoin: false, end: false, have: None, queued_ms: None, tab: Some(String::new()), speak: Some(false) }.json(),
            r#"{"type":"hello","version":1,"resume":null,"tab":"","speak":false}"#
        );
        let about = About { topic: 112, title: "One app".into(), text: "Keep it?".into(), task: None };
        assert_eq!(
            Up::Hello { version: 1, resume: None, about: Some(about), rejoin: false, end: false, have: None, queued_ms: None, tab: None, speak: None }.json(),
            r#"{"type":"hello","version":1,"resume":null,"about":{"topic":112,"title":"One app","text":"Keep it?"}}"#
        );
        assert_eq!(
            Up::Hello { version: 1, resume: Some("c1".into()), about: None, rejoin: false, end: false, have: Some(7), queued_ms: Some(1200), tab: None, speak: None }.json(),
            r#"{"type":"hello","version":1,"resume":"c1","have":7,"queued_ms":1200}"#
        );
        assert_eq!(Up::Mute { on: true }.json(), r#"{"type":"mute","on":true}"#);
        assert_eq!(Up::Pause { on: false }.json(), r#"{"type":"pause","on":false}"#);
        assert_eq!(Up::Where { tab: Some("t3".into()), speak: false, stay: false }.json(), r#"{"type":"where","tab":"t3","speak":false}"#);
        assert_eq!(Up::Where { tab: None, speak: true, stay: true }.json(), r#"{"type":"where","tab":null,"speak":true,"stay":true}"#);
        assert_eq!(Up::Words { send: true }.json(), r#"{"type":"words","send":true}"#);
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
        assert_eq!(Down::parse(r#"{"type":"muted","on":true}"#), Some(Down::Muted { on: true }));
        assert_eq!(Down::parse(r#"{"type":"error","text":"x"}"#), Some(Down::Error { text: "x".into() }));
        assert_eq!(Down::parse(r#"{"type":"sentence","seq":4}"#), Some(Down::Sentence { seq: 4 }));
        assert_eq!(Down::parse(r#"{"type":"said","seq":4}"#), Some(Down::Said { seq: 4 }));
        assert_eq!(Down::parse(r#"{"type":"words","text":"so"}"#), Some(Down::Words { text: "so".into() }));
        assert_eq!(Down::parse(r#"{"type":"tab","id":"t3","n":3}"#), Some(Down::Tab { id: "t3".into(), n: 3 }));
        assert_eq!(Down::parse(r#"{"type":"line","text":"Done."}"#), Some(Down::Line { text: "Done.".into() }));
        assert_eq!(Down::parse(r#"{"type":"mictest","on":true,"ns":false}"#), Some(Down::Mictest { on: true, ns: Some(false) }));
        assert_eq!(Down::parse(r#"{"type":"mictest","on":false}"#), Some(Down::Mictest { on: false, ns: None }));
        assert_eq!(Up::Micsetup { mic: "lark".into(), ns: Some(false) }.json(), r#"{"type":"micsetup","mic":"lark","ns":false}"#);
        assert_eq!(Down::parse(r#"{"type":"later","x":1}"#), Some(Down::Unknown));
        assert_eq!(Down::parse("not json"), None);
    }
}
