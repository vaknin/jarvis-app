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
256 KiB logcat in seconds. (Not "nothing on 47114", as first read: the phone's node binds a random port,
`Net::Internet { port: None }` in `crates/ffi/src/lib.rs`; only the laptop uses 47114.) In netwatch 0.19.3 (`udp.rs`), a failed
`rebind()` (iroh calls it on a major network change) leaves the state `Closed` with `is_broken` false,
so nothing ever retries, and every send/recv warns. The laptop link stays dead until the app restarts.
Fix options: `[patch]` netwatch so a failed rebind marks the socket broken (retried on the next poll),
or rebuild the node's endpoint when it happens; and `netwatch=error` in the ffi's log filter.

## The evening calls (20:58-21:02): a second endpoint with the phone's id
Three calls at 20:58 died before `hello` ("Connection reset without closing handshake" at jarvis-voice).
An emulator ran 20:55-20:58 with another job's **debug** build of Tether, and debug builds carried the
Pixel's forum key. The relay (iroh-relay 1.3, `clients.rs`) sends an id's traffic to its newest client,
so the emulator took the phone's relay traffic, and on 5G (CGNAT) the call rides the relay. Debug builds
now get their own key (`~/.config/jarvis/forum-emu-iroh-secret`, already in the gate's allow file) and
the build fails without it. The 21:02:37 drop (reconnects dying in 1-3 s) is not explained by that; the
endpoint rebuild and the logs below should fix it or show it next time.

## What was built
1. `crates/voice/src/call.rs` is a state machine: a stall (no pong 3 s after a ping at 2 s quiet, or no
   answer 3 s after a network change) dials a second socket with `resume` while the old one stays up;
   the old line answering before `hello` cancels it; `ready` switches over without flushing the player.
   Reconnecting and the Dropped earcon come only after a 2.5 s grace; a hard drop redials at once; a
   network change restarts a dial that hasn't sent `hello`. Tests: `crates/voice/tests/call.rs`.
2. `crates/ffi/src/forum.rs`: each pipe logs one line when it ends, e.g.
   `forum: pipe 2 voice: connect 597 ms, via relay>direct, 36.2 s, up 781762 B, down 966825 B: done`
   (or why it failed, with the link's `close_reason`). A pipe that can't connect, or whose iroh side
   fails, closes the endpoint so the next pipe binds a fresh one, unless another pipe heard the gate in
   the last 2 s (rebuilding would kill a healthy call: the relay routes to the newest endpoint), and at
   most every 2 s. Once the app closes a pipe, the gate gets 5 s to answer before the pipe ends, instead
   of iroh's 30 s idle timeout. `netwatch=error` stops the log flood.
3. Gate (`jarvis` repo, `forum/phone/tunnel/src/lib.rs`): `open voice from … via …`, path changes, and
   `ended after …, B up, B down, via …; why`. jarvis-voice says why a socket ended and when a resume
   takes over a still-open socket.

Checked end to end with a side gate and throwaway keys (`cargo test -p tether-ffi -- --ignored
voice_through_the_gate`, `VOICE_HOLD=45` to keep the call up): killing the gate mid-call, the call
stalled, dialed a second socket and carried on once the gate was back. The second connect took ~10 s
after a gate restart (twice); not chased.

4. The laptop link's dead socket: netwatch is patched (`vendor/netwatch`, `PATCHED.md`) so a failed
   rebind marks the socket broken and the next poll binds again; the first failure and the recovery are
   logged at error level (`netwatch: rebind failed, retrying on the next poll: …` / `rebind recovered`),
   so the next one also says why the bind failed. Upstream 0.20.0 has the same bug.

## 2026-10-29: a drop leaving home Wi-Fi took ~35 s (thread #132)
At 07:13:40 the call stalled as Aviv left home; the gate's old voice connection idle-timed out at 07:14:11
and the new one only opened at 07:14:15, via the relay, though the stall's second dial should have gone at
~07:13:44. Likely: that dial rode the same iroh endpoint, whose relay link and selected path were on the
dying Wi-Fi, and nothing made iroh look again until Android's default-network callback fired.

5. A stall or a break now kicks the transport at once (`Events::kick`; the ffi calls
   `forum_network_changed`, iroh's `network_change`), and if the second dial still hasn't said `hello`
   3 s later (`Timing::rekick`), kicks again and starts that dial over (once; a network change from
   Android pushes the rekick back 3 s, since it restarts the dial itself). Tests: `a_stall_kicks_...`,
   `a_second_dial_that_gets_through_...`, `a_hard_drop_kicks_once_...`, `a_stalled_line_that_answers_ends_the_rekick`.
6. The phone's call log reaches the server: the ffi keeps its own lines (the voice crate, the ffi's forum
   and voice, iroh warnings, netwatch errors; 15 min, 512 KiB) in a ring (`crates/ffi/src/phonelog.rs`), and a
   call that went down sends the last 5 minutes (at most 128 KiB) once: 15 s after it's back, or 6 s after
   it ends without coming back. Handshake `logup` on the forum tunnel (`forum::send_log`); the gate keeps it
   in `~/.jarvis/phone-logs/` on the server. New lines that help read it: `voice: the phone's network
   changed`, `forum: network change: endpoint N looks for its paths again`, `voice: back on a new socket
   after N ms down`, `voice: log sent`.
