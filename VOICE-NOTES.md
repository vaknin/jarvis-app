# voice-phone job notes (scratch, delete before landing)
- voice-wip merged; cargo test -p tether-voice: 9 unit + 16 call tests pass.
- jarvis-voice not running (no unit, 8790 free) at start -> fake server needed.
- running forum-gate (01:42) has VOICE_TARGET -> tunnel test possible with tester key.
- Telecom: androidx.core:core-telecom 1.0.1 (transactional API, minSdk 34 ok).
- voice port on phone: 8791. debug VOICE_URL ws://10.0.2.2:8790/call.
## Emulator smoke (debug apk com.kivan.tether.debug, fake_server example on 127.0.0.1:8790), 06:27-06:31
- tile click (screen on) -> CallActivity -> call; Telecom addCall setActive Success, route Speaker, dumpsys telecom: self_mng ACTIVE voip=true
- hello/ready, states listening/thinking/speaking, text lines on screen (screenshot tmp/call1.png), flush sent by fake on answer 1
- mute button -> server got {"type":"mute","on":true}, mic stopped; CallStyle notification w/ Hang Up + Unmute (tmp/notif.png)
- killed fake server -> Reconnecting at once; restart -> hello resume "fake-call", mute re-sent, Listening (16 s)
- screen off (Asleep) 25 s: mic kept flowing to server
- tile click while keyguard showing: SystemUI wants unlock first, no hangup (expected Android behaviour for custom tiles)
- mic rate on emulator ~32 frames/s (expected 50): added a mic rate log to check if emulator or us
TODO: hangup via screen button, ended screenshot, rebuild w/ alignment fix + mic log, release apk, tunnel test via ffi + tester key, pair review, docs, land, forum reply 36, delete voice-wip

## Gate tunnel test (06:4x)
- `voice_through_the_gate` (ignored test, crates/ffi forum.rs) with a temp key added to ~/.jarvis/forum-iroh-allow and removed after:
  ready after 1.2 s through iroh → gate → 127.0.0.1:8790 (fake_server), text/state/audio came back, flush, hangup seen by the server. PASS.
## Emulator re-check (06:36–06:43)
- Mic rate now 50 frames/s exactly (500 in 9987 ms); the 32/s earlier was the emulator, not us.
- Tile now follows the call while the panel is open (was stuck on "Call"). Fixed: JarvisTile collects Voice.ui while listening.
- Hangup: screen shows "Call ended: hung up" within 0.4 s. Server saw hangup in 5/6 calls; the one miss (first call after reinstall, conn reset) didn't reproduce in 5 tries incl. the same path. Worst case: the server waits out the 5 min resume window.
