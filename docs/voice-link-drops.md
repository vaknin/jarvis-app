# Why voice calls drop (2026-10-28 investigation)

## What the logs show
- **20:02:44** (socket 3 dropped, then a *new* call at 20:03:40, not a resume): an APK install. The job
  "AirPods mic fix installed" reported at 20:02:35; installing the app kills the process and the call.
  Installs must wait for no call (as `jarvis-voice-restart-when-idle` does for the server).
- **20:17:04** (socket 5 dropped; socket 6 resumed at 20:17:19, and socket 7 reset in the same second):
  the phone gave up its WebSocket, then the redial took ~15 s (= `connect_timeout`). Two dials landed at
  once, so one attempt had been abandoned mid-connect (a `NetworkChanged` does `continue 'call`, which
  drops the pending connect). Most likely: a network blip; `call.rs` drops the line 4 s after a network
  change with no answer (`net_check`) or after 15 s quiet (`dead_after`), and a fresh iroh dial is
  slower than letting the existing QUIC connection migrate (iroh keeps a path 15 s, a relay path 30 s).
- Phone logs for both times are gone: see the flood below. The gate (`forum-gate`) logs nothing per
  connection, and jarvis-voice logs a drop without its reason.
- On mobile data iroh warns "IPv4 address detected by QAD varies by destination": a symmetric NAT
  (CGNAT), so the call usually rides the relay there.

## Found on the way: the laptop link's socket is dead after a network change
`WARN netwatch::udp: socket closed` ~70 times a second (980 lines in 12 s), which wipes the phone's
256 KiB logcat in seconds. `/proc/net/udp` shows uid 10707 holding the forum endpoint's ports and mDNS,
but **nothing on 47114**: the Tether node's socket is `Closed`. In netwatch 0.19.3 (`udp.rs`), a failed
`rebind()` (iroh calls it on a major network change) leaves the state `Closed` with `is_broken` false,
so nothing ever retries, and every send/recv warns. The laptop link stays dead until the app restarts.
Fix options: `[patch]` netwatch so a failed rebind marks the socket broken (retried on the next poll),
or rebuild the node's endpoint when it happens; and `netwatch=error` in the ffi's log filter.

## Plan (not done yet)
1. `crates/voice/src/call.rs`: on a stall (no pong ~3 s after a ping, or no answer after a network
   change), dial a second socket with `resume` *while keeping the old one*; the server already lets a
   resume take over a live socket (`CallIn::Attach` replaces `conn`). Cancel it if the old line answers
   before `hello` is sent; switch without flushing the player once `ready` comes. Show Reconnecting and
   play the Dropped earcon only if the line is down past a ~2.5 s grace; first retry after a hard drop
   at once, not after 500 ms. `ping_after` 2 s. Tests on the paused clock in `crates/voice/tests/call.rs`.
2. `crates/ffi/src/forum.rs`: log each pipe (kind, time to connect, `path_events` Selected relay/direct,
   duration, `close_reason`).
3. Gate (`~/Projects/jarvis/forum/phone/tunnel/src/lib.rs`): the same per-connection log; deploy with a
   restart when no call is on. jarvis-voice `server.rs`: say why a socket ended and when one takes over.
