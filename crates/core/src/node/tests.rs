use std::path::Path;

use tokio::sync::broadcast::Receiver;

use super::*;

const WAIT: Duration = Duration::from_secs(15);

fn config(dir: &Path, name: &str) -> Config {
    let mut cfg = Config::new(dir.join(name).join("state"), dir.join(name).join("dl"), name);
    cfg.net = Net::Loopback;
    cfg.redial = Some((Duration::from_millis(100), Duration::from_millis(400)));
    cfg
}

async fn start(dir: &Path, name: &str) -> Node {
    Node::start(config(dir, name)).await.unwrap()
}

async fn paired(dir: &Path) -> (Node, Node) {
    let a = start(dir, "a").await;
    let b = start(dir, "b").await;
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();
    (a, b)
}

fn introduce(a: &Node, b: &Node) {
    a.add_hint(b.addr());
    b.add_hint(a.addr());
}

async fn wait_for(rx: &mut Receiver<Event>, f: impl Fn(&Event) -> bool) -> Event {
    timeout(WAIT, async {
        loop {
            let e = rx.recv().await.unwrap();
            if f(&e) {
                return e;
            }
        }
    })
    .await
    .expect("event did not arrive")
}

impl Node {
    fn db_state(&self, id: Uuid) -> State {
        self.inner.db(|s| s.get(id)).unwrap().unwrap().state
    }
}

/// A delivered drop emits no event (it isn't chat), so poll.
async fn until_delivered(n: &Node, id: Uuid) {
    timeout(WAIT, async {
        while n.db_state(id) != State::Delivered {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("not delivered");
}

fn msg_in(e: &Event, id: Uuid, state: State) -> bool {
    matches!(e, Event::Message(m) if m.id == id && m.state == state)
}

#[tokio::test]
async fn pairs_then_chats_both_ways() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    assert_eq!(a.peer().unwrap().id, b.id().to_string());
    assert_eq!(a.peer().unwrap().name, "b");
    assert_eq!(b.peer().unwrap().name, "a");

    let (mut ea, mut eb) = (a.events(), b.events());
    let m = a.send_text("hi").unwrap();
    let got = wait_for(&mut eb, |e| msg_in(e, m.id, State::Received)).await;
    let Event::Message(got) = got else { unreachable!() };
    assert_eq!(got.text.as_deref(), Some("hi"));
    wait_for(&mut ea, |e| msg_in(e, m.id, State::Delivered)).await;

    let r = b.send_text("back").unwrap();
    wait_for(&mut ea, |e| msg_in(e, r.id, State::Received)).await;
    assert_eq!(a.unread().unwrap(), 1);
    assert!(a.is_connected() && b.is_connected());
}

#[tokio::test]
async fn pairing_code_is_single_use() {
    let d = tempfile::tempdir().unwrap();
    let a = start(d.path(), "a").await;
    let b = start(d.path(), "b").await;
    let c = start(d.path(), "c").await;
    introduce(&a, &b);
    introduce(&a, &c);

    let code = a.pair_offer();
    b.pair(&code).await.unwrap();
    assert!(c.pair(&code).await.is_err());

    let forged = format!("{}:1:{}:{}", crate::compat::PAIR_WORD, a.id(), BASE32_NOPAD.encode(&[7u8; 32]));
    a.pair_offer();
    assert!(c.pair(&forged).await.is_err());
    assert_eq!(a.peer().unwrap().id, b.id().to_string());
}

#[tokio::test]
async fn unpaired_endpoint_is_refused() {
    let d = tempfile::tempdir().unwrap();
    let (a, _b) = paired(d.path()).await;
    let c = start(d.path(), "c").await;
    c.add_hint(a.addr());
    let conn = c.inner.ep.connect(a.id(), ALPN).await.unwrap();
    let err = timeout(WAIT, conn.closed()).await.unwrap();
    match err {
        iroh::endpoint::ConnectionError::ApplicationClosed(close) => {
            assert_eq!(close.error_code, CLOSE_NOT_PAIRED.into());
        }
        other => panic!("expected a refusal, got {other:?}"),
    }
    assert!(c.connect().await.is_err(), "c has no peer to dial");
}

#[tokio::test]
async fn queued_while_offline_is_delivered_later() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;

    let file = d.path().join("shot.png");
    std::fs::write(&file, b"not really a png").unwrap();
    let t = a.send_text("one").unwrap();
    let f = a.send_file(&file).await.unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(a.status().unwrap().queued, 2);
    assert_eq!(a.status().unwrap().oldest_queued_ms, Some(t.ts_ms));

    // b comes back on a new port, with its old state.
    let b = start(d.path(), "b").await;
    let (mut ea, mut eb) = (a.events(), b.events());
    introduce(&a, &b);
    // The phone checks in when it starts; a's own redial may still be stuck on b's old address.
    b.connect().await.unwrap();
    wait_for(&mut eb, |e| msg_in(e, f.id, State::Received)).await;
    let got = b.recent(10).unwrap();
    assert!(got.iter().any(|m| m.id == t.id && m.text.as_deref() == Some("one")));
    let saved = got.iter().find(|m| m.id == f.id).unwrap().path.clone().unwrap();
    assert_eq!(saved, d.path().join("b/dl/shot.png"));
    assert_eq!(std::fs::read(saved).unwrap(), b"not really a png");

    wait_for(&mut ea, |e| msg_in(e, f.id, State::Delivered)).await;
    assert_eq!(a.status().unwrap().queued, 0);
    assert_eq!(a.status().unwrap().oldest_queued_ms, None);
}

#[tokio::test]
async fn a_file_gone_before_sending_fails_and_is_cancelled() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;
    let file = d.path().join("gone.pdf");
    std::fs::write(&file, b"soon deleted").unwrap();
    let f = a.send_file(&file).await.unwrap();
    std::fs::remove_file(&file).unwrap();

    let b = start(d.path(), "b").await;
    let mut ea = a.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let e = wait_for(&mut ea, |e| matches!(e, Event::SendFailed { .. })).await;
    let Event::SendFailed { id, name, .. } = e else { unreachable!() };
    assert_eq!((id, name.as_str()), (f.id, "gone.pdf"));
    // The peer dropped it and confirmed, as with a cancel.
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert_eq!(b.db_state(f.id), State::Cancelled);
    assert_eq!(a.status().unwrap().queued, 0);
}

/// When both sides dial at once, a file can fail on the link that just lost, after the winner's
/// resend went out without the cancel (the flake of the test above, 2026-10-06). The cancel must
/// still reach the peer, on the winner.
#[tokio::test]
async fn a_file_failing_on_a_replaced_link_is_cancelled_on_the_new_one() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let (mut ea, mut eb) = (a.events(), b.events());
    a.connect().await.unwrap();
    let old = a.inner.current().unwrap();
    a.inner.drop_link(CLOSE_DUPLICATE, b"duplicate");
    wait_for(&mut eb, |e| matches!(e, Event::Disconnected)).await;
    a.connect().await.unwrap();
    assert_ne!(a.inner.current().unwrap().serial, old.serial);

    // Queued after the new link's resend; the peer had it from the old link.
    let gone = d.path().join("gone.pdf");
    let body = Body::File { name: "gone.pdf".into(), size: 3, sha256: [0; 32] };
    let f = a.inner.db(|s| s.enqueue(body, Some(gone), None)).unwrap();
    b.inner.db(|s| s.receive(&f.item())).unwrap();
    // The old link's FileWant is served only now.
    assert!(a.inner.stream_file(&old, f.id, 0).await.is_err());

    wait_for(&mut ea, |e| matches!(e, Event::SendFailed { id, .. } if *id == f.id)).await;
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert_eq!(b.db_state(f.id), State::Cancelled);
}

#[tokio::test]
async fn a_file_without_a_path_fails_once_instead_of_every_link() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;
    let file = d.path().join("lost.pdf");
    std::fs::write(&file, b"x").unwrap();
    let f = a.send_file(&file).await.unwrap();
    // Rows like this were found on the laptop (2026-10-04), retried on every link forever.
    a.inner.db(|s| s.clear_path(f.id)).unwrap();

    let b = start(d.path(), "b").await;
    let mut ea = a.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let e = wait_for(&mut ea, |e| matches!(e, Event::SendFailed { .. })).await;
    assert!(matches!(e, Event::SendFailed { id, .. } if id == f.id));
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert_eq!(a.status().unwrap().queued, 0);
}

#[tokio::test]
async fn a_file_want_for_something_not_a_file_of_mine_is_ignored() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;
    let drop = a.inner.db(|s| s.enqueue(Body::DropChannel("dibs".into()), None, None)).unwrap();
    // A peer that took the drop for a file it waits bytes for asks for them on every link.
    let b = start(d.path(), "b").await;
    let fake = crate::proto::Item { id: drop.id, seq: drop.seq, ts_ms: drop.ts_ms, expires_ms: None,
        body: Body::File { name: "x".into(), size: 10, sha256: [0; 32] } };
    b.inner.db(|s| s.receive(&fake)).unwrap();

    introduce(&a, &b);
    b.connect().await.unwrap();
    timeout(WAIT, async {
        while !a.inner.odd_wants.lock().unwrap().contains(&drop.id) {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("the FileWant never came");
    // Nothing was streamed or failed: it waits, and the link carries on.
    assert_eq!(a.db_state(drop.id), State::Queued);
    let mut eb = b.events();
    let m = a.send_text("still fine").unwrap();
    wait_for(&mut eb, |e| msg_in(e, m.id, State::Received)).await;
}

#[tokio::test]
async fn a_ghost_file_from_a_misread_drop_becomes_the_drop() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let post = a.inner.db(|s| s.enqueue(Body::App { channel: "ramitest".into(), data: "{}".into(), replace: false }, None, None)).unwrap();
    b.shutdown().await;
    let drop = a.inner.db(|s| s.enqueue(Body::DropChannel("ramitest".into()), None, None)).unwrap();
    // The laptop used to resend its stored drops as nameless files, so the phone kept one of
    // these, waiting for bytes, and the laptop failed it on every link (2026-10-04).
    let b = start(d.path(), "b").await;
    b.inner.db(|s| s.receive(&post.item())).unwrap();
    let ghost = crate::proto::Item { id: drop.id, seq: drop.seq, ts_ms: drop.ts_ms, expires_ms: None,
        body: Body::File { name: String::new(), size: 0, sha256: [0; 32] } };
    b.inner.db(|s| s.receive(&ghost)).unwrap();

    introduce(&a, &b);
    b.connect().await.unwrap();
    until_delivered(&a, drop.id).await;
    let row = b.inner.db(|s| s.get(drop.id)).unwrap().unwrap();
    assert_eq!(row.kind, "drop");
    assert!(b.inner.db(|s| s.app_channels()).unwrap().is_empty(), "the drop went through");
    assert_eq!(a.status().unwrap().queued, 0);
}

#[tokio::test]
async fn file_resumes_from_partial_download() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;

    let data: Vec<u8> = (0..1_000_000u32).map(|i| (i * 7 % 251) as u8).collect();
    let file = d.path().join("big.bin");
    std::fs::write(&file, &data).unwrap();
    let f = a.send_file(&file).await.unwrap();

    // An earlier attempt got 300 kB across before the link dropped.
    let dl = d.path().join("b/dl");
    std::fs::write(files::part_path(&dl, f.id), &data[..300_000]).unwrap();

    let b = start(d.path(), "b").await;
    let mut eb = b.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let first = wait_for(&mut eb, |e| matches!(e, Event::Progress { id, .. } if *id == f.id)).await;
    assert!(matches!(first, Event::Progress { done: 300_000, total: 1_000_000, .. }));
    wait_for(&mut eb, |e| msg_in(e, f.id, State::Received)).await;
    assert_eq!(std::fs::read(dl.join("big.bin")).unwrap(), data);
    assert!(!files::part_path(&dl, f.id).exists());
}

#[tokio::test]
async fn cancel_stops_a_file_midway() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let file = d.path().join("huge.bin");
    std::fs::write(&file, vec![7u8; 64 << 20]).unwrap();
    let (mut ea, mut eb) = (a.events(), b.events());
    let f = a.send_file(&file).await.unwrap();
    wait_for(&mut eb, |e| matches!(e, Event::Progress { id, done, .. } if *id == f.id && *done > 0)).await;
    assert!(a.cancel(f.id).unwrap());

    wait_for(&mut eb, |e| msg_in(e, f.id, State::Cancelled)).await;
    // My side shows it at once, then hears the peer confirm.
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert_eq!(a.status().unwrap().queued, 0);
    let dl = d.path().join("b/dl");
    timeout(WAIT, async {
        while files::part_path(&dl, f.id).exists() {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("the partial file was not removed");
    assert!(!dl.join("huge.bin").exists());
    // Delivered or cancelled files can't be cancelled again.
    assert!(!a.cancel(f.id).unwrap());
}

#[tokio::test]
async fn the_peer_unpairing_reports_refused() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let mut ea = a.events();
    a.connect().await.unwrap();
    b.unpair().unwrap();
    wait_for(&mut ea, |e| matches!(e, Event::Refused)).await;
    // Dialing again is refused at the door, and says so again.
    let _ = a.connect().await;
    wait_for(&mut ea, |e| matches!(e, Event::Refused)).await;
}

#[tokio::test]
async fn cancel_while_offline_reaches_the_peer_later() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;
    let file = d.path().join("later.bin");
    std::fs::write(&file, b"never sent").unwrap();
    let f = a.send_file(&file).await.unwrap();
    assert!(a.cancel(f.id).unwrap());
    assert_eq!(a.db_state(f.id), State::Cancelling);

    let b = start(d.path(), "b").await;
    let mut ea = a.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert!(b.recent(10).unwrap().iter().all(|m| m.id != f.id));
}

#[tokio::test]
async fn idle_link_closes_unless_asked_to_stay() {
    let d = tempfile::tempdir().unwrap();
    let mut ca = config(d.path(), "a");
    ca.idle_timeout = Duration::from_millis(800);
    let a = Node::start(ca).await.unwrap();
    let b = start(d.path(), "b").await;
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();

    let mut ea = a.events();
    b.connect().await.unwrap();
    b.set_stay(true);
    wait_for(&mut ea, |e| matches!(e, Event::Connected)).await;
    tokio::time::sleep(Duration::from_millis(2000)).await;
    assert!(a.is_connected(), "b asked to stay connected");
    assert!(a.peer_stays() && !b.peer_stays());

    b.set_stay(false);
    wait_for(&mut ea, |e| matches!(e, Event::Disconnected)).await;
}

#[tokio::test]
async fn stay_set_before_connecting_reaches_the_peer() {
    let d = tempfile::tempdir().unwrap();
    let mut ca = config(d.path(), "a");
    ca.idle_timeout = Duration::from_millis(800);
    let a = Node::start(ca).await.unwrap();
    let b = start(d.path(), "b").await;
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();

    // The phone app sets stay when it starts the node, before there is any link.
    b.set_stay(true);
    let mut ea = a.events();
    b.connect().await.unwrap();
    wait_for(&mut ea, |e| matches!(e, Event::Connected)).await;
    tokio::time::sleep(Duration::from_millis(2000)).await;
    assert!(a.is_connected(), "b asked to stay connected before dialing");
}

#[tokio::test]
async fn simultaneous_dials_settle_on_one_link() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let (ra, rb) = tokio::join!(a.connect(), b.connect());
    ra.unwrap();
    rb.unwrap();
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert!(a.is_connected() && b.is_connected());
    let smaller = if a.id().as_bytes() < b.id().as_bytes() { a.id() } else { b.id() };
    assert_eq!(a.inner.current().unwrap().dialer, smaller);
    assert_eq!(b.inner.current().unwrap().dialer, smaller);

    let mut eb = b.events();
    let m = a.send_text("after the race").unwrap();
    wait_for(&mut eb, |e| msg_in(e, m.id, State::Received)).await;
}

#[tokio::test]
async fn media_state_is_dropped_with_the_link() {
    let d = tempfile::tempdir().unwrap();
    let (laptop, phone) = paired(d.path()).await;
    let mut el = laptop.events();
    phone.connect().await.unwrap();
    wait_for(&mut el, |e| matches!(e, Event::Connected)).await;
    let state = MediaState {
        player: "Spotify".into(),
        title: "t".into(),
        artist: "a".into(),
        album: String::new(),
        playing: true,
        position_ms: 1000,
        duration_ms: 60_000,
        volume: Some(40),
        can_seek: true,
        can_next: true,
        can_previous: true,
    };
    assert!(phone.send_live(Frame::Media(Some(state.clone()))));
    wait_for(&mut el, |e| matches!(e, Event::Media { state: Some(_) })).await;
    assert_eq!(laptop.media(), Some(state));

    phone.disconnect();
    wait_for(&mut el, |e| matches!(e, Event::Media { state: None })).await;
    assert_eq!(laptop.media(), None);
}

fn app_ev(e: &Event) -> bool {
    matches!(e, Event::App { .. })
}

#[tokio::test]
async fn app_views_replace_and_items_reach_the_channel_not_the_chat() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;

    // Offline: two views queued, the newer replaces the older; a post queues beside them.
    a.send_app("teen", "old".into(), true).unwrap();
    let v = a.send_app("teen", "new".into(), true).unwrap();
    let post = a.send_app("teen", "post".into(), false).unwrap();
    assert_eq!(a.status().unwrap().queued, 2);
    assert_eq!(a.app_view("teen").unwrap().as_deref(), Some("new"));

    let b = start(d.path(), "b").await;
    let mut eb = b.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let got = wait_for(&mut eb, app_ev).await;
    let Event::App { id, channel, data, from_me, view } = got else { unreachable!() };
    assert_eq!((id, channel.as_str(), data.as_str(), from_me, view), (Some(v), "teen", "new", false, true));
    let got = wait_for(&mut eb, app_ev).await;
    assert!(matches!(got, Event::App { id: Some(id), view: false, .. } if id == post));
    assert!(b.recent(10).unwrap().is_empty(), "not in the chat");
    assert_eq!(b.unread().unwrap(), 0);
    assert_eq!(b.app_view("teen").unwrap().as_deref(), Some("new"));
    assert_eq!(b.app_pending("teen").unwrap(), vec![(post, "post".to_string())], "views aren't pending");
    b.app_done(post).unwrap();
    assert!(b.app_pending("teen").unwrap().is_empty());

    // Online: a newer view replaces the delivered one on the receiving side too.
    let v2 = a.send_app("teen", "newer".into(), true).unwrap();
    wait_for(&mut eb, |e| matches!(e, Event::App { id: Some(id), .. } if *id == v2)).await;
    assert_eq!(b.app_view("teen").unwrap().as_deref(), Some("newer"));
    assert!(b.inner.db(|s| s.get(v)).unwrap().is_none(), "the old view is gone");
    assert!(a.inner.db(|s| s.get(v)).unwrap().is_none());

    // A reply both ways makes the thread; views stay out of it.
    let mut ea = a.events();
    let reply = b.send_app("teen", "reply".into(), false).unwrap();
    wait_for(&mut ea, |e| matches!(e, Event::App { id: Some(id), .. } if *id == reply)).await;
    let h = a.app_history("teen", 10).unwrap();
    let h: Vec<_> = h.iter().map(|m| (m.text.as_deref().unwrap(), m.from_me)).collect();
    assert_eq!(h, vec![("post", true), ("reply", false)]);
    assert_eq!(a.app_channels().unwrap(), vec!["teen".to_string()]);

    assert!(b.send_app_live("teen", "progress".into()));
    let got = wait_for(&mut ea, app_ev).await;
    assert!(matches!(got, Event::App { id: None, ref data, view: false, .. } if data == "progress"));
}

#[tokio::test]
async fn a_local_app_item_is_pending_and_emitted() {
    let d = tempfile::tempdir().unwrap();
    let a = start(d.path(), "a").await;
    let mut ea = a.events();
    let id = a.app_local("teen", "tap".into()).unwrap();
    let got = wait_for(&mut ea, app_ev).await;
    assert!(matches!(got, Event::App { id: Some(i), from_me: false, view: false, .. } if i == id));
    assert_eq!(a.app_pending("teen").unwrap(), vec![(id, "tap".to_string())]);
    assert_eq!(a.status().unwrap().queued, 0, "nothing to send");
    assert_eq!(a.status().unwrap().unread, 0);
}

#[tokio::test]
async fn the_peer_tells_its_app_version_on_each_link_and_it_is_not_an_app_item() {
    let d = tempfile::tempdir().unwrap();
    let a = start(d.path(), "a").await;
    let mut cb = config(d.path(), "b");
    cb.app_version = Some("0.3.7".into());
    let b = Node::start(cb).await.unwrap();
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();
    assert_eq!(b.peer_app().unwrap(), None, "a has no version to tell");

    let mut ea = a.events();
    b.connect().await.unwrap();
    timeout(WAIT, async {
        while a.peer_app().unwrap().is_none() {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("no version");
    assert_eq!(a.peer_app().unwrap().as_deref(), Some("0.3.7"));
    while let Ok(e) = ea.try_recv() {
        assert!(!matches!(e, Event::App { .. }), "the version reached the apps: {e:?}");
    }
    a.unpair().unwrap();
    assert_eq!(a.peer_app().unwrap(), None, "forgotten with the peer");
}

#[tokio::test]
async fn a_channel_file_goes_to_its_channel_not_the_chat() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let (mut ea, mut eb) = (a.events(), b.events());
    let file = d.path().join("shot.png");
    std::fs::write(&file, b"a photo for dibs").unwrap();

    let f = b.send_channel_file("dibs", &file).await.unwrap();
    assert_eq!(f.channel.as_deref(), Some("dibs"));
    let got = wait_for(&mut ea, |e| msg_in(e, f.id, State::Received)).await;
    let Event::Message(got) = got else { unreachable!() };
    let saved = got.path.clone().unwrap();
    assert_eq!(saved, d.path().join("a/state/channels/dibs/shot.png"));
    assert_eq!(std::fs::read(&saved).unwrap(), b"a photo for dibs");
    assert_eq!(got.channel.as_deref(), Some("dibs"));
    wait_for(&mut eb, |e| msg_in(e, f.id, State::Delivered)).await;

    // Not in the chat or its unread count, on either side; listed with the channel.
    assert!(a.recent(10).unwrap().is_empty() && b.recent(10).unwrap().is_empty());
    assert_eq!(a.unread().unwrap(), 0);
    let files = a.app_files("dibs", 10).unwrap();
    assert_eq!(files.iter().map(|m| (m.id, m.file_name.as_deref())).collect::<Vec<_>>(), vec![(f.id, Some("shot.png"))]);
    assert!(a.app_files("teen", 10).unwrap().is_empty());
    assert!(!d.path().join("a/dl/shot.png").exists());
}

#[tokio::test]
async fn a_queued_channel_file_is_resent_as_one() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    a.shutdown().await;
    let file = d.path().join("log.txt");
    std::fs::write(&file, b"the log").unwrap();
    // Queued while the laptop is away: the resend reads it back from the store.
    let f = b.send_channel_file("dibs", &file).await.unwrap();
    let a = start(d.path(), "a").await;
    let mut ea = a.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let got = wait_for(&mut ea, |e| msg_in(e, f.id, State::Received)).await;
    let Event::Message(got) = got else { unreachable!() };
    assert_eq!(got.channel.as_deref(), Some("dibs"));
    assert_eq!(got.path.unwrap(), d.path().join("a/state/channels/dibs/log.txt"));
}

#[tokio::test]
async fn the_laptop_sends_a_channel_file_to_the_phone() {
    // `jarvis-link send --channel dibs`: a transcript for the dibs app, kept with the channel.
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let (mut ea, mut eb) = (a.events(), b.events());
    let file = d.path().join("transcript-31-ab12.json.gz");
    std::fs::write(&file, vec![7u8; 300_000]).unwrap();

    let f = a.send_channel_file("dibs", &file).await.unwrap();
    let got = wait_for(&mut eb, |e| msg_in(e, f.id, State::Received)).await;
    let Event::Message(got) = got else { unreachable!() };
    assert_eq!(got.channel.as_deref(), Some("dibs"));
    let saved = got.path.clone().unwrap();
    assert_eq!(saved, d.path().join("b/state/channels/dibs/transcript-31-ab12.json.gz"));
    assert_eq!(std::fs::read(&saved).unwrap(), vec![7u8; 300_000]);
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Delivered)).await;

    assert!(b.recent(10).unwrap().is_empty());
    assert_eq!(b.unread().unwrap(), 0);
    let files = b.app_files("dibs", 10).unwrap();
    assert_eq!(files.iter().map(|m| m.id).collect::<Vec<_>>(), vec![f.id]);
    assert!(!d.path().join("b/dl/transcript-31-ab12.json.gz").exists());
}
