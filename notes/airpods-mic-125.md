# AirPods mic, round 4 (thread 125): the bisect and the fix (2026-10-30)

Times are Israel time. Clips: ~/.jarvis/voice/audio/<call>/<ms>.wav. `sp` is the median rms of a clip's speech,
`7.5k` the level of the 7.5-7.9 kHz band: about 12 dB = Bluetooth's band limit, 38-51 dB = the phone's mic.
Rounds 1-3 are in git history; their "no app commit explains it" and the one-bud guess are withdrawn.

## Verdict in plain words
The mic sometimes opens in a bad state, and a reopen brings it back. On the 13:39 call today (build 251, one connection,
one pair of AirPods) the first open gave speech at raw peaks of 85, 703, 1753, climbing only as he kept talking. The trial
closed the mic and opened it again in a pause, without Tether's echo and noise handles: 3300 to 19884 for the rest of the
call, every word heard, first syllable at full level even after 14 s of silence. That is his "single word doesn't work,
keep talking and it starts": see "The gate".

Not proven: one sample can't split "the reopen" from "no Tether handles", so the fix does both. It's the first
direct measurement of a bad mic turning good, and the first evidence not resting on matching windows.

The timing stands: every AirPods call on build 234 was good, builds 235-238 landed together at 09:54 on 10-29, and since
then it has come and gone with no build change (11:41 bad, 15:15 good, 11:03-11:04 today bad, 13:39 bad then good). That's
a race, not a switch. The bad audio is filtered, not just quiet, and the phone's own peak was already low before the
server, so the server didn't cause it. It does decide what a low mic costs.

## The gate
Real speech from the good 15:15 call, scaled, then 3.5 s of hiss, through the live `jarvis-voice analyze` (Silero and Smart
Turn, the call's own path):

| Peak | "Hey Jarvis" | "is" | 3.5 s sentence | 4.7 s sentence |
|---|---|---|---|---|
| 6000 | turn | turn | turn | turns |
| 1500 | turn | short | turn | turns |
| 700 | short | short | turn | short |
| 500 | nothing | nothing | turn | nothing |
| 350 and under | nothing | nothing | nothing | nothing |

So a bad mic at 85 to 700 sits on a fixed line: nothing, or a word dies and a sentence lands. Good AirPods calls sit at 4000
to 10000. Where each of his suspects stands:
1. **The boost (249-251):** no sustained-speech wait, it starts at x8 on any frame. But it rises fast and falls slowly, frozen
   in silence, so after loud speech a relapse goes out at x1 for 10 s of talk. It made a relapse worse; it can't be the
   cause, since 248 had none and was bad. The phone was on 251 for the 13:39 call. Gone.
2. **Server VAD:** Silero 0.7 plus a volume of 0.6 (about -50 LUFS) smoothed over 400 ms, 0.2 s to start, fixed, no noise
   floor, nothing adapts to a quiet mic. Not new, so not the regression, but it's why a low mic is deaf rather than quiet.
3. **Phone processing that ramps:** yes, in the raw peaks on the first open, 85 to 703 to 1753 in 9 s of talk with the same
   hiss. Which stage does it isn't separated.
4. **SCO going idle:** no. The mic's lines tick every 2.000 s through silence: frames never stop.

Other apps take the same stream with no gate in front and an AGC behind it: quiet audio still reaches a human ear. That's
why "works everywhere else" can be true of a mic in this state as well as of a mic that opened right.

Lined up, working against failing, on what's logged: same mic (AirPods Pro7282), same route, source and rate. 13:39 was a
redial, so no stem press. Which bud and whether the Bluetooth link was up before the first open weren't logged; the second
is now, in the `open:` line.

## The table
| When | Call | Build | Server pid | Route | sp | 7.5k | Turns | Phone STT | Started | BT on before | Good? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10-28 21:01 | c3776943-2 | <234 | 3776943 | AirPods | 827 | 12.1 | 2 | no | ? | | good |
| 10-28 21:07 | c3776943-5 | <234 | 3776943 | AirPods | 1160 | 12.0 | 354 | no | ? | | good |
| 10-28 22:49 | c3776943-8 | <234 | 3776943 | AirPods | 1032 | 12.2 | many | no | ? | | good |
| 10-28 23:15 | c1003496-1 | <234 | 1003496 | AirPods | 905 | 12.4 | 37 | no | ? | | good |
| 10-29 00:46 | c1312333-5 | <234 | 1312333 | phone | 3641 | 40.7 | 41 | no | ? | | n/a |
| 10-29 01:46-01:55 | c1588115-2..20 | <234 | 1588115 | BT off | - | - | 0 | no | short tries | | n/a |
| 10-29 06:13 | c1745463-2 | 234 | 1745463 | AirPods | 1082 | 12.0 | 58 | no | ? | seconds | good |
| 10-29 06:35 | c2063072-2 | 234 | 2063072 | AirPods | 1136 | 12.0 | 123 | no | ? | 22 min | good |
| 10-29 07:04 | c2063072-7 | 234 | 2063072 | AirPods | 1360 | 12.1 | 152 | no | ? | 51 min | good |
| 10-29 07:38 | c2063072-14 | 234 | 2063072 | AirPods | 1280 | 12.0 | 278 | no | ? | | good |
| 10-29 08:38 | c2063072-25 | 234 | 2063072 | ? | - | - | 0 | no | 6 sockets, no words | | unclear |
| 10-29 08:59 | c2063072-52 | 234 | 2063072 | AirPods | 1190 | 12.1 | 75 | no | ? | | good |
| 10-29 09:18 | c2063072-70 | 234 | 2063072 | AirPods | 1251 | 12.1 | 6 | no | ? | | good |
| 10-29 09:19 | c2063072-77 | 234 | 2063072 | AirPods | 1253 | 12.1 | 49 | no | ? | | good, last on 234 |
| **09:54** | | **238 installs (235-238 at once)** | | | | | | | | | |
| 10-29 11:41 | c3628493-2 | 238 | 3628493 | AirPods 65 s, then phone | 5535 | 48.0 | 74 | from 11:42 | ? | hours | **bad**: 65 s no clip, then phone mic |
| 10-29 14:10 | c84020-1 | 239 | 84020 | ? | - | - | 5 | ? | ? | | no clips |
| 10-29 15:14 | c87049-4 | 239 | 87049 | ? | - | - | 0 | ? | tap, 4 s | 17 s | unknown |
| 10-29 15:15 | c87049-10 | 245 (probably) | 87049 | AirPods | 1294 | 12.1 | 119 | **yes** | tap, 16 s after the update restart | 50 s | **good** |
| 10-29 16:41 | c640790-81 | 245 (probably) | 640790 | phone (BT off) | 3573 | 46.1 | 3 | yes | no MainActivity first | - | n/a |
| 10-30 07:49 | c1192995-735 | 248 | 1192995 | phone | 4587 | 38.1 | 13 | yes | | - | n/a |
| 10-30 11:03 | c1192995-744 | 248 | 1192995 | AirPods | - | - | 0 | ? | tap | 43 s | **bad**: nothing heard |
| 10-30 11:03 | c1192995-746 | 248 | 1192995 | AirPods | - | - | 0 | ? | redial | | **bad**: nothing heard |
| 10-30 11:04 | c1192995-753 | 248 | 1192995 | AirPods 41 s, then phone | 180-344 | 11-12 | - | yes | from the app | | **bad**, then phone mic fine |

`?` = not recoverable: the phone's logcat keeps about an hour and usagestats only shows the app opening.

## What separates good from bad
- **Not** phone STT: it ran on the good 15:15 call (cut 1 heard "Hey Jarvis." at 15:15:10). It first showed at 11:42 because
  no speech got through for the first 65 s of that call, so the one-minute gap says nothing.
- **Not** the time since Bluetooth connected: good 50 s, bad 43 s.
- **Not** the build alone: 245 and 248 share their mic code, and 245 was good, 248 bad.
- **Not** a stem press starting the call: today's three were started from the app.
- **The one thing that fits so far:** the good 15:15 call ran in a process started 16 s earlier by an update, so that
  process never saw the AirPods connect and never ran `MediaKeys.claim` (new in 237). The bad call's process did
  (AirPods 11:02:33, claim about 11:02:35, call 11:03:13). One good and one bad window: consistent, no more.

## The bad audio is filtered, not quiet
Median speech level by band, dB (bad n=3 clips; the 11:41 stretch saved none):

| Hz | 100 | 300 | 600 | 1000 | 2000 | 3000 | 4000 | 5500 | 7000 |
|---|---|---|---|---|---|---|---|---|---|
| good 15:15 | 96 | 96 | 86 | 78 | 73 | 67 | 43 | 33 | 12 |
| good 07:04 | 93 | 95 | 86 | 75 | 70 | 63 | 42 | 32 | 11 |
| good 09:19 | 93 | 96 | 89 | 79 | 73 | 68 | 45 | 36 | 13 |
| **bad 11:04** | 74 | 73 | 63 | 73 | 71 | 69 | 33 | 20 | 11 |

Under 1 kHz 20 dB down, 1-4 kHz equal, above 4 kHz 10-13 dB down, noise floor unchanged. A gain stage or a blocked mic
drops everything the same way; this is processing. So a volume boost was the wrong tool, as Aviv said.

Clip 27560 (pk 4984, rms 402) doesn't contradict it: the peak is two 100 ms bursts, its speech sits at sp 344. The phone's
recognizer heard it, the server's returned "". The server's speech-to-text moved to Parakeet at 17:32 on 10-29, so it's
weaker on such audio than the phone's, but it only sees what the phone sent: it decides whether quiet becomes "hears
nothing", not why it's quiet.

## What Tether does that other apps don't, on the mic path
| Thing | In 234 (good)? | Verdict |
|---|---|---|
| `AcousticEchoCanceler`, `NoiseSuppressor` turned on on the record session | yes | **Changed.** See below |
| VOICE_COMMUNICATION source | yes | Kept: it's what a VoIP app uses |
| `setPreferredDevice` on the AudioRecord | yes | Kept: it fixed the silent mic of 10-28 |
| Mic opens before the call link is up, reopens on the route change | yes | **Changed.** A race, and each reopen is a new audio session |
| Media session PLAYING through the call | yes | Kept: on every good call, and it stops a press mid-call resuming a paused player |
| `MediaKeys.claim` silence on connect | **no, 237** | **Moved** to when headphones leave |
| `MediaKeys.claim` on a ring | **no** | **Removed** |
| PERFORMANCE_MODE_LOW_LATENCY | yes | Kept |
| Downlink while Jarvis is quiet | yes | Kept: `Player::pull` gives exact zeros, no comfort noise, so it can't duck the mic |
| Telecom transactional call | yes | Kept |
| On-device recognizer | no | Kept: fed by pipe, no second recorder, ran on the good call |

**The echo processing.** All three bad call starts today logged `bt_headset_nrec=off` in `dumpsys media.audio_flinger`:
the AirPods tell the phone to leave echo and noise to them (HFP's AT+NREC=0). I have no such log from a good call
(it rolls over), and it's likely the same on every call with these AirPods, so it's a standing fact, not a separator. The
phone's effects are Fortemedia's software chain, seen on the record session on the 12:13 phone-mic call. On a Bluetooth input AudioFlinger is meant to suspend them, but
that hangs on the order of the route change, the effect chain and the session, which we reopen; I read that from memory
of AOSP, not from this phone. Not asking for the effects wouldn't stop them either: audioserver attaches them to a
VOICE_COMMUNICATION source by itself (pid 1589 at priority -1 in the dump), so round 3's `NO_EFFECTS` trial was never
really off. Only a higher-priority handle set to disabled turns them off. Whether they were enabled on the bad calls is unknown: the
dump was taken on the phone mic and shows only that the chain attaches. But filtered lows and highs at an unchanged floor look like processing, and a race like this
can come and go on identical code, which would fit 245 good and 248 bad. Best fit, unproven.

## Every commit since the last good AirPods call that touches audio
- 235 f85ca6f phone STT: not the mic; ran on the good call. Out.
- 236 efd3820 voice crate, close during takeover: not the mic. Out.
- 237 4ca5ae1 stem press: `claim` on connect and on a ring, PLAY in the receiver. Suspect. Moved and removed.
- 238 e801a17 stall kick and log: not the mic. Out.
- 239 1497b81 phone-mic stand-in: a second recorder, gone in 249. Not in today's clips' path as a filter. Out.
- 240-245 rejoin, update, callback: mic start from the background, no change once open. Out.
- 246-248 pause on drop, mute by voice: notification and mute only. Out.

## Server
Changes in the window: 78dd165 phone STT (07:52), ec23edc no-word turns and quiet stray cut-ins (08:27), hot swap
(10:30-14:13), doubt (11:22-12:50), f008a55 Parakeet (17:32), 84d9780 workspaces. The server's own echo canceller (`aec.rs`,
AEC3, in live calls since 01:58 on 10-29) runs before clips are saved, but only while Jarvis speaks on the WiiM speaker, and
its log has no "echo canceller on" in any of these calls. So what's in the clips is what the phone sent. The good 15:15 call ran after most of them; the bad 11:41 ran before most of them. The VAD gate
(-50 LUFS, Pipecat's default, unchanged since 10-27) is what turns a quiet mic into silence, but it doesn't make the mic
quiet. Server out.

## The stem double-press: in or out
**In as a suspect, not proven.** What it holds during a call: a media session PLAYING, also on every good call, and
Telecom's own session outranks it for keys. Nothing there can touch the HFP mic. What it added in 237 and is the only new
thing near the headset: 300 ms of silent media to the AirPods two seconds after they connect, plus a PLAYING state over
AVRCP. It lines up with the good and bad windows, but only in one window each, there's no mechanism, and no call on
record ran with the switch off. The switch was on through all of it (logcat, 11:03:46).

## The fix (build 252 on)
1. On Bluetooth Tether asks for no echo or noise processing: exactly the setup measured good. The system still attaches and
   manages its own, which is what the headset's NREC is for.
2. `MicWatch.kt`: a stretch on a headset reopens the mic once, in its first pause, whatever the level, because a bad open
   can't be told from a good one before he speaks. Then again if voice comes through quiet (loudest tenth under 1500 across
   1 s of voice in 5 s), twice at most until a window reads normal again; out of reopens, the call's log says so. Replayed over
   the clips of the good AirPods calls of 10-29 (587 windows), 10 read quiet, never two in a row, so a good call pays a 200 ms
   gap in a pause now and then and never reaches the log line. That first reopen lands about 1 s in, usually under Jarvis's
   greeting, and whether one that early helps is unmeasured: the 13:39 rescue came after he'd talked. The quiet check is the
   backstop. It needs 1 s of voice, so one dead word alone doesn't trigger it. Replaces the trial, which is the only thing
   ever seen to rescue the mic. With a Bluetooth headset connected the first open also waits for the route (up to 3 s), and a
   route change that only confirms the device doesn't reopen. If the buds are connected and the call stays on the phone, that
   wait costs its full 3 s.
3. `MediaKeys.claim` runs when headphones leave, never with headphones connected. No claim on a ring. Cost: if another app
   plays on the phone after the AirPods leave, the press belongs to that app until they next leave, and a press answers a
   ring only when the keys are already Tether's.
4. `MicBoost` and `MicTrial` are gone: from the reopen on the boost read x1.0 for the whole call, so it wasn't the fix. Mic
   logging stays: each open logs where it landed, and a headset call logs its peak every 2 s.
5. Phone STT stays, on every route.

## Delivery
Build 249 didn't land because nothing told the phone: not a missing pause. The updater looks 2 minutes after the process
starts, then every 6 hours, and on an app open. The process started 07:28, so the next look was about 13:30, and a publish
isn't pushed. Now it looks every 10 minutes through a call, so a new build lands in the next pause (the line drops and
rejoins, as designed in #132), and 3 minutes after a call ends (after the log's tries). The Wi-Fi rule stays: on mobile data it posts "Update ready" and a tap downloads
it. The phone took 251 at 12:39. Builds before 252 keep the old timing, so 252 reaches him at the next app open, or the 6-hour look at about 18:40.

## Thread #123's notes
1. The trial's window: its replacement counts 1 s of voice inside 5 s, so scattered noise can't fill it.
2. The log after hang-up: the upload holds its own place on the tunnel (`Busy`), so the 3-minute idle close can't cut it,
   and `LinkService` keeps the process. What could lose it was a network that was down at that moment, which was one try
   and gone. Now it tries four times over about 2.5 minutes, with the lines taken at the first try.
3. The Mute ring now reads the mic as it is, with no boost: normal AirPods sit at about 40% of the ring, a bad mic at
   under 10%, so the ring itself shows it. Hiss sits under the ring's floor, so the old speech line went with the boost.

## How to read the next AirPods call
`grep mic ~/.jarvis/phone-logs/<newest>`: `open:` lines show each open and where it landed, `reopening after N frames` the
first pause's. `peak` lines at 2000-6000 while he talks = good; `voice at peak N` = it went quiet and got reopened; `didn't
help` = reopening no longer fixes it, and that's the next lead.

Not done, another repo: a speech gate in `jarvis-voice` that follows the call's own noise floor. A fixed -50 LUFS is what
turns a quiet mic into a deaf one, on any route.
