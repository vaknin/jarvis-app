# Thread #118 part 2 (phone side) work log — delete before merge
- Pixel not on LAN at start (pixel: not found). Ship route likely publish-app.sh.
- Design: crates/voice/src/stt.rs state machine (ring 30 s, sessions open/audio/close via Events, words store
  in absolute line samples, cut -> close session + reopen at `to` replaying ring, heard on session done).
  Kotlin PhoneStt: one recognizer at a time (next waits for the previous to finish), writer starts on ready,
  BUSY retry 250 ms.

## State (context limit reached mid-job)
Done, on branch jarvis/thread-118-part-2-the-phone-s:
- crates/voice: proto (Hello.stt, Up::Heard, Down::Cut), stt.rs books (ring 30 s, sessions, words in line samples,
  cut -> close + reopen at `to` replaying the ring, heard on the session's final; 7 unit tests), call.rs wiring
  (Call::start_full(.., phone_stt), Events::stt_open/stt_audio/stt_close, Call::stt_done). cargo test -p tether-voice:
  16 + 28 pass, incl. phone_stt_says_so_feeds_the_recognizer_and_answers_each_cut.
- crates/ffi voice.rs: VoiceListener.on_stt_open/audio/close, SttWord record, VoiceCall(url, listener, phone_stt),
  about(.., phone_stt), stt_done. (ffi build was running when the context ran out.)
- Kotlin: PhoneStt.kt (on-device recognizer per session, pipe fed once ready, BUSY retry 250 ms x40, one at a time,
  watchdog 6 s, availability check cached in prefs "phone-stt"), CallService wiring, TetherApp check at start,
  manifest <queries> RecognitionService.
Left:
1. Build: cargo build -p tether-ffi; android :app:assembleDebug then :app:assembleRelease (Kotlin not compiled yet).
2. Test: emulator (check isOnDeviceRecognitionAvailable there; likely false -> only the fallback path) or a headless
   real_server run with a fake recognizer; measure "cut N: the phone heard ... X ms" in the server log.
3. Install jarvis-voice from jarvis main 78dd165 (restart when idle), merge tether to master, publish-app.sh,
   adb install -r if `pixel` reaches the phone (it didn't at the start of this job).
4. Remove this file before merging to master.
