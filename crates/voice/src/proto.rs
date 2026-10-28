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
    },
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
        assert_eq!(Up::Hello { version: 1, resume: None, about: None }.json(), r#"{"type":"hello","version":1,"resume":null}"#);
        assert_eq!(
            Up::Hello { version: 1, resume: Some("c1".into()), about: None }.json(),
            r#"{"type":"hello","version":1,"resume":"c1"}"#
        );
        let about = About { topic: 112, title: "One app".into(), text: "Keep it?".into() };
        assert_eq!(
            Up::Hello { version: 1, resume: None, about: Some(about) }.json(),
            r#"{"type":"hello","version":1,"resume":null,"about":{"topic":112,"title":"One app","text":"Keep it?"}}"#
        );
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
        assert_eq!(Down::parse(r#"{"type":"later","x":1}"#), Some(Down::Unknown));
        assert_eq!(Down::parse("not json"), None);
    }
}
