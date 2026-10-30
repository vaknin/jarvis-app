# AirPods mic, round 3 (thread 125): what the evidence says (2026-10-30)

Times are Israel time. Clips: ~/.jarvis/voice/audio/<call>/<ms>.wav. Levels are 16-bit peak / rms.

## Proven
- Android never sends the AirPods a mic gain: HeadsetStateMachine stores +VGM and does nothing with it
  ("Not used currently"). mMicVolume: 0 in dumpsys is just the unset default. HFP gain is ruled out.
- 10-29 15:15 (c87049-10, 23 min) was on the AirPods at normal level (spectrum identical to the morning's
  AirPods calls: pk ~6000, 7.5-7.8 kHz ~13 dB, vs ~50 on the phone's mic). Bluetooth was switched on at
  15:14:15 and off at 16:02, so he turned it on for this call. That build had phone STT, the stem press,
  the call's PLAYING media session and the phone-mic probe. So: build 238 failed at 11:41, a later build
  worked at 15:15, build 248 failed at 11:03 today. No app commit explains that sandwich.
- The "broke at 09:33 mid-call" story isn't supported: after 09:33:28 he simply stopped talking on that
  call (agent running, no words, then a quiet line until he hung up at 09:44). Nothing broke then.
- Phone STT didn't run before 10-29 11:42 (first "the phone heard" in the voice journal).
- The call's PLAYING media session dates from ff4384e (10-29 01:58), before the good morning calls.
- 11:04 today on the SCO link he WAS there. Same wideband band limit as the good calls (mSBC, nothing
  above 7.2 kHz), so these clips really came through the AirPods:
    5440 "Yeah." pk 992 rms 109 / 13000 "Can you hear me?" pk 489 rms 114 (both transcribed)
    27560 pk 4984 rms 402: normal AirPods shape; phone recognizer: "something wrong with the Bluetooth
    microphone again."; server STT returned "".
- Server VAD needs -50 LUFS (min_volume 0.6, Pipecat's default, unchanged since 10-27): rms ~100. Speech
  at rms ~100 sits right on the gate: no turn, no clip, "hears nothing". Calls -744 and -746 had 0 turns.

## Not proven
- Why the level drops 15-30 dB on some calls. On 10-29 11:41 the phone's own peak was 30-214 while talking.
- Leading guess: which bud's mic carries the call (AirPods pick one; a blocked mic mesh would fit an
  on/off fault that ignores the app). Needs a one-bud test by Aviv.

## Boost test (real clips from today's 11:04 call, c1192995-753, replayed through the server's own code)
- 27560 "something wrong with the Bluetooth microphone again": ×1 STT "" (7 of 7 words lost); ×4, ×8, ×16 word-perfect.
- Speech detector replay: 13000 ×1 nothing, ×4 too short, ×8 started. 27560 ×1 nothing, ×4 started.
- 5440 "Yeah." transcribes at every level, the detector never starts on it alone (only 96 ms voiced).
- Replaying one clip is harsher than a live call (no run-up for the 400 ms loudness window), so these are a floor.

## What was built on this
- The stand-in is gone: no second recording, no phone mic on a headset route, no banner.
- `MicBoost` (crates/voice/src/boost.rs): only on a headset's mic. Speech level followed over time, gain up to ×20, frozen in
  silence. Their hiss: ×20 no false speech, ×40 false speech. Normal AirPods speech sits at frame rms 350-460 (geometric mean).
- All three 11:04 clips through the real Rust code, then the server's own recognizer: word for word, including the
  sentence the server lost at the time. Detector replay: starts on two; "Yeah." alone never starts (96 ms voiced).
- `MicTrial`: on quiet speech only, tries the mic with no effects, then as VOICE_RECOGNITION, then as MIC, always on the
  headset. Remove once the logs answer it.
- A call on a headset uploads its mic lines to ~/.jarvis/phone-logs after a minute and at its end. `mic boost:` lines every
  2 s (gain, raw peak, speech level); a line per mic open (source, Hz, silenced, effects, mics); `trial:` lines.

## How to read the next AirPods call
- `grep 'mic' ~/.jarvis/phone-logs/<newest>`: raw peak under 1000 while he talks = quiet state; `Hz` 8000 = narrowband link.
- A `trial:` way far louder than USUAL = the app's way of opening the mic is the cause, and that way becomes the default.
- All ways equally quiet = below the app. Then the one-bud test decides.
