//! The frames both sides of the wire must read alike. This repo holds a copy of the private repo's
//! `tabs/wire`, and each runs this test on the same files, so a change on one side shows on the other.

use jarvis_tabs_wire::{Ask, Tell};
use std::{fs, path::Path};

fn lines(file: &str) -> Vec<serde_json::Value> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("goldens").join(file);
    fs::read_to_string(path).unwrap().lines().filter(|l| !l.is_empty()).map(|l| serde_json::from_str(l).unwrap()).collect()
}

#[test]
fn every_ask_reads_and_writes_back_the_same() {
    for want in lines("ask.jsonl") {
        let ask: Ask = serde_json::from_value(want.clone()).unwrap();
        assert_ne!(ask, Ask::Other, "{want}");
        assert_eq!(serde_json::to_value(&ask).unwrap(), want);
    }
}

#[test]
fn every_tell_reads_and_writes_back_the_same() {
    for want in lines("tell.jsonl") {
        let tell: Tell = serde_json::from_value(want.clone()).unwrap();
        assert_ne!(tell, Tell::Other, "{want}");
        assert_eq!(serde_json::to_value(&tell).unwrap(), want);
    }
}
