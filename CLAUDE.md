# Jarvis

A lean replacement for KDE Connect between this laptop (Omarchy/Hyprland) and the Pixel 8.
It covers chat with offline delivery, files both ways (the Android Direct Share tile is called
"Laptop"), phone media control from the bar, ring phone, pings, and mirrored phone notifications.
The full plan is in `docs/PLAN.md`. Read it before changing scope.

## Layout
- `crates/core`: protocol (`proto.rs`), SQLite chat log/outbox (`store.rs`), file helpers (`files.rs`).
  There is no platform code here. The phone uses this same core through uniffi (`crates/ffi`).
- `crates/ffi`: uniffi 0.32 bindings (`JarvisNode`, records, `EventListener` callback) with its own
  tokio runtime. Kotlin package `com.kivan.jarvis.core` (`uniffi.toml`).
- `crates/daemon`: the `jarvis-link` binary. It runs the daemon (systemd user unit) and the CLI, which talks
  to the daemon over `$XDG_RUNTIME_DIR/jarvis.sock`.
- `crates/core/src/node.rs`: the iroh endpoint and the single link (pairing, dial race, outbox resend,
  file resume, idle close). Its tests run two nodes over loopback (`Net::Loopback`).
- `contrib/jarvis-link.service`: the systemd user unit. Installed on 2026-10-02 (earlier than planned, the user
  chose it) so Jarvis ran side by side with KDE Connect until the Phase 5 cut-over (2026-10-02: KDE Connect
  removed from the laptop, nftables opens only UDP 47114 from the LAN for Jarvis): binary from
  `cargo install --path crates/daemon --locked` (`~/.cargo/bin/jarvis-link`), state and the paired identity in
  `~/.local/state/jarvis` (moved from the old dev dir), unit listed in `~/.config/laptop/paths`.
  After daemon changes: reinstall, then `systemctl --user restart jarvis-link`.
- `android/`: **the lean app (2026-10-26, `docs/LEAN.md`)**, rebuilt from scratch after dibs was shut down: one chat
  with the laptop, files both ways (share tile "Laptop", attach), notifications with Reply, pairing, and the forum
  tunnel (`crates/ffi/src/forum.rs`). Package `com.kivan.jarvis`, signed with `~/.config/jarvis/jarvis.keystore`.
  `LinkService` (a quiet foreground service, started with the app and at boot) keeps the node running, so the
  laptop can always dial it; no FCM. `Native.load` hands iroh the app context (the phone's DNS servers).
  Updates come from Jarvis's server through the forum tunnel, from anywhere (`Updater.kt`, docs/LEAN.md
  "Updates from the server"): after `assembleRelease` on committed master, `scripts/publish-app.sh` offers it,
  and the phone installs it without a question once Jarvis is its installer of record (the first install after an
  adb install still asks for one tap), mid-call too: only in a pause, and the call comes back on the restart (thread #132). It looks every 6 hours, every 10 minutes through a call, and 3 minutes after a call ends. versionCode is the commit count (`git rev-list --count HEAD`).
  Jarvis's notifications (`jarvis notify` on the server, 2026-10-28): the forum gate dials the tunnel's endpoint
  (ALPN `jarvis-alert/0`, the gate's id only; `crates/ffi/src/alerts.rs`), `Alerts.kt` shows each on the "Jarvis"
  channel and answers its id, or a short reason (`app notifications off`, `Jarvis channel off`, `no listener`, `threw …`; thread #179), and the server falls back to Chrome push, then the forum chat with a line on what to turn on. `JarvisApp` starts alerts, the tunnel, `Watch`, `Updater.loop` and `Tasks.report` with the process, since Android can refuse `LinkService`. Once started, the endpoint stays
  up (iroh's relay ping every 15 s is the standing battery cost; no connection is held). Design: the jarvis repo's
  `notes/jarvis-notify.md`.
  One app, voice first (thread #112, 2026-10-29; the forum app `com.vaknin.forum` folded in): the label is "Jarvis".
  Home (`MainActivity`, `ui/HomeScreen.kt`) is the big Call button plus What's new (`Brief.kt`, `GET /brief` on the
  tunnel's 8791; the forum's unread list until that answers). The top bar opens the forum (`forum/ForumActivity`, a
  WebView on `Forum.URL`) and the laptop chat (`LaptopActivity`). `forum/Watch.kt` starts with the process. It notifies
  only for what is aimed at Aviv: Discourse's unread notifications of types 1/6/7/15, and chat DMs and @mentions. It
  never marks anything read. Assistant: `Assist.kt` (VoiceInteractionService; the session opens `CallActivity` START),
  and `CallActivity` takes ACTION_ASSIST. Media keys: `MediaKeys.kt`. A call holds a MediaSession, so afterwards
  the keys reach `MediaKeys.Receiver`, until another media app plays; headphones leaving with nothing playing
  re-claim them (`MediaKeys.claim`, 300 ms of silence under a PLAYING session, from `LinkService`; never with headphones connected, so nothing of ours touches the AirPods before a call, thread #125). With nothing
  playing the AirPods send PLAY for a press and a double-press alike (never NEXT; the Pixel's `dumpsys
  bluetooth_manager` "Media Key Events", 2026-10-29), so NEXT, PLAY and PLAY_PAUSE all call. If the call screen
  hasn't opened 3 s after a press, a full-screen "Call Jarvis" notification stands in. Logs: tag `JarvisKeys`;
  what the receiver got: `dumpsys activity broadcasts history` (MEDIA_BUTTON).
  Everything below about adb, presence, dibs's app, channels, media and root steps describes the OLD app
  (now in the dibs repo's history): the lean app doesn't answer those.
- Voice calls with Jarvis (call mode, forum thread #36, 2026-10-27): `crates/voice` is the client of
  `~/Projects/jarvis/voice/PROTOCOL.md` (WebSocket, PCM16 mono 16 kHz, `hello`/`ready`/`state`/`flush`/`mute`/`pause`/`paused`/`muted`/`hangup`,
  `resume` after a drop with backoff, never given up while the server has the call (it waits for a network when offline; a call the app left is rejoined, thread #132); jitter buffer and earcons in `player.rs`; a drop never flushes Jarvis's reply: the phone plays on what it holds, its resume hello says `have` (the last sentence held whole, from `sentence`/`said`) and `queued_ms`, and the server resends the rest, each sentence from its start: the player skips what it still has, or restarts the sentence it ran dry in; only a resume that became a new call flushes, thread #132, the jarvis repo's `notes/voice-fixes/pause-resume.md`). Its tests run
  on a fake socket on tokio's paused clock; `cargo run -p jarvis-voice --example real_server -- CLIP.wav [URL]` drives this crate against a real `jarvis-voice` (use a `serve --dry-run` on another port; `--drop-ms N [--down-ms M] [--after-said|--mid-sentence]` or `--stall OVER.wav` break the line mid-reply through a proxy, and it maps what the speaker played onto the sentences: missed, doubled, restarted); `cargo run -p jarvis-voice --example fake_server` stands in for
  `jarvis-voice` on 127.0.0.1:8790. The ffi's `VoiceCall` wraps it; the forum tunnel's second port 8791 opens its pipe
  with `voice`, which the gate sends to jarvis-voice. In the app: `CallService` (foreground, phoneCall + microphone; a
  self-managed Telecom call through androidx core-telecom, so the AirPods use their call profile and a press hangs
  up; communication mode by hand only if Telecom refuses; the mic reopens on each route change and follows it, never onto another device. With a Bluetooth headset connected the first open waits for the route (up to 3 s), the app turns the phone's echo and noise processing off on it with its own higher-priority handles, since the system attaches both anyway (the headset does its own and says so), and `MicWatch.kt` reopens a headset's mic once in its first pause and again if voice comes through quiet, because a reopen is what brought the AirPods' mic back when it opened 20 to 40 dB down under the server's speech gate: `notes/airpods-mic-125.md`. A call on a headset sends its log,
  mic lines included, to `~/.jarvis/phone-logs/` after a minute, on leaving the headset and at its end, and any call sends it before an update restarts the app; `crates/voice/src/meter.rs` meters what leaves the phone, kept in an atomic the ring around Mute reads on the frame clock through `VoiceCall.mic_level` and `MicLevel`, never through `Voice.Ui`; `MicFeed` is a debug receiver that feeds it fake levels, since the fake server's mic is silent, thread #123), `CallActivity` (big Call button, Mute, Hang up, and the audio route
  (`AudioRoutes.kt`: a tap cycles earpiece, speaker, Bluetooth, wired; through Telecom's endpoints, AudioManager's
  communication device when routed by hand), the call's length from connect (also the notification's chronometer),
  the transcript as a chat that scrolls back through past calls (`CallLog`, `filesDir/call-log.jsonl`, newest 2000
  pieces; a `text` frame is the speaker's whole line so far, so one from the same speaker replaces the last piece
  (`CallLog.fold`, unit-tested), never appended; it follows new lines only at the bottom; `CallText` shapes a line at render time into paragraphs, lists and bold, never changing a word and stored verbatim: `CALL_LINES=FILE` runs its tests over a file of real lines, one a line, which stay out of this public repo, thread #123), shown over the lock screen, proximity off in a pocket),
  the Quick Settings tile "Jarvis" and the "Call Jarvis" shortcut. Pause (2026-10-28, the jarvis repo's
  `notes/voice-fixes/tap-pause.md`): a wide Pause/Continue pill over the buttons and the same action in the call
  notification (the lock screen, mid-workout), shown while Jarvis speaks or thinks, or is paused. A tap silences
  the phone at once and drops the audio still on its way until the server answers `paused` (`Call::set_paused`);
  the server keeps the reply and continues from the sentence he stopped in; `paused` from the server (a tap,
  the spoken "stop", or Aviv talking over him) drives `Voice.Ui.paused`, and a pause the server never heard is
  sent again on resume. A mute by voice ("mute my mic", the server's `muted`, thread #159) keeps the mic streaming (the
  server listens for "unmute"): `Voice.Ui.voiceMuted` shows `Muted by voice · say "unmute"` on the screen and in the notification, the
  button turns to Unmute, and its tap sends `mute` off, which lifts it on the server; a resume never sends that unmute by itself.
  `fake_server --voice-mute` mutes by voice at its second answer. Aviv's spoken words are "stop" and "continue", so the button says Pause/Continue. A turn makes no sound (only connect, drop and end
  have earcons) and the call notification is quiet (low channel, alerts once), so the screen stays off. Debug
  builds (`com.kivan.jarvis.debug`) call `ws://10.0.2.2:8790/call` directly, for the emulator, and carry the
  emulator's forum key (`~/.config/jarvis/forum-emu-iroh-secret`), never the Pixel's: an endpoint with the phone's
  id anywhere else takes its relay traffic. Why calls dropped and what the call/pipe logs mean: `docs/voice-link-drops.md`. A dropped call is never over until Aviv hangs up (thread #132): `CallStore` keeps the call the app
  is in (a restart, an update included, rejoins it at once: `Voice.resume` from `BootReceiver`/`LinkService`) and the
  hangups the server never confirmed (sent again with the ffi's `voice_end`). The server rings for a dropped call
  the app isn't redialing (alert url `/callback/<id>`), and reach rings Aviv when he's available and out (thread #36,
  `/ring/<item>`; the older `/call` too): `Callback.kt`, an incoming-call notification (full screen on the lock screen,
  `CallScreen`'s `Incoming`: "Jarvis", Decline and Answer, like a regular call) with the ringtone only with the ringer
  on and Do Not Disturb off, else silent; the stem press answers; a missed one says "Call back", and Decline or
  dismissing it hangs the dropped call up. Declining a ring sends `voice_end` with the item id, and the server texts
  it; unanswered, the ring stops after a minute and the server texts it. The availability chip is available /
  unavailable. Debug: `am startservice -n com.kivan.jarvis.debug/com.kivan.jarvis.LinkService -a
  com.kivan.jarvis.ALERT --es url /callback/ID` (or `--es url /ring/reach-ID --es title Jarvis --es text "Incoming call"`). A stall or break kicks iroh at once (`Events::kick` → `forum_network_changed`) and again 3 s on if the
  second dial hasn't said hello; a call that went down sends its recent log lines to the server once (`crates/ffi/src/phonelog.rs`,
  handshake `logup`, kept in the server's `~/.jarvis/phone-logs/`).
- adb on any network (`Adb.kt`, `jarvis-link adb [--enable]`, 2026-10-05): dibs asks where the phone's adb listens
  when adb can't see it (the phone's Wi-Fi filter drops multicast mDNS while the screen is off). The daemon wakes
  the phone (FCM), sends a live `App` frame on the reserved channel `_adb` (`{"op":"endpoint","enable":bool}`) and
  waits up to 40 s for the reply on the same channel: `wifi`, `addrs` (`ip/prefix`), `adb_wifi`, `port` and `name`
  (NsdManager own-service lookup, the port checked by binding it on loopback), `can_enable`, `enabled`, `refused`.
  Two permissions are granted once over adb (the user's yes, 2026-10-05): WRITE_SECURE_SETTINGS (turns
  `adb_wifi_enabled` back on; Android writes 0 back on a network not marked "Always allow", which is `refused`) and
  ACCESS_LOCAL_NETWORK (Android 17: without it NsdManager opens a picker screen instead of answering, so `Adb.kt`
  only looks up the port when it's granted). **An APK without these manifest lines drops both grants.** The app
  holds a multicast lock for 20 s per ask so adb's own mDNS sees the phone meanwhile. `jarvis-link connect` now wakes
  the phone first (it used to only dial, which timed out against an idle phone).
- Phone presence for dibs (`Presence.kt`, `crates/daemon/src/presence.rs`, app 0.5.6, 2026-10-06): the phone tells
  the laptop when the user unlocks or uses it, so dibs knows they're on the phone even away from home. Live `App`
  frames on the reserved channel `_presence` (never queued): `{"op":"present","why":"unlock"|"use","ts":<phone ms>}`
  and `{"op":"off","ts":…}`. `PhoneListener` (bound by the system, so no service of ours) registers the receiver
  for USER_PRESENT, SCREEN_ON (counts as an unlock when the keyguard isn't locked) and SCREEN_OFF. Battery: an
  unlock dials at most once a minute (unless the screen went off between, as the laptop was told "off"); while the screen stays on and unlocked a `use` renewal dials every 5 min;
  `off` goes only over a link already up (`Core.sendLive`), never dials; nothing while refused or unpaired. The
  daemon keeps the newest (by the phone's ts) in memory: `jarvis-link watch` gets
  `{"type":"presence","op","why"|null,"ts_ms","got_ms"}` per frame, `jarvis-link status --json` `phone_presence`
  (the same without `type`, null until the first frame since the daemon started). `_presence` and `_adb` are
  refused for sending (`check_channel`, as any `_…`) and for `app_subscribe`.
- (Frozen, see above: edit in the dibs repo.) dibs's own app (`android/dibs`, Gradle module `:dibs`, plan in `docs/DIBS-APP.md`, version 0.16.0; a 🎤 in every message box, `Dictation.kt`): `DibsActivity`
  (own launcher icon and Recents card) with five tabs (Chat, Ideas, Waiting, Tasks, Recap) under one slim pinned bar (mark, name, state, marks for what is lent, two usage rings (5h, 7d, filled by percent used with the number inside; a tap shows when they reset), ⋮); the lend switches, the spending limit and laptop load live on the dibs page (`Page.Status`, `ui/StatusPage.kt`), opened by a tap on the bar. Drawn from the `dibs` payload in
  the dibs channel's view (`Payload.kt`; chat folding in `ChatModel.kt`, task order in `TasksModel.kt`, unit-tested).
  Tasks holds the user's own tasks (`yours`) until ticked, with dibs's own work folded at its bottom (a dibs without
  `yours` gets the old Work tab). Over the tabs, a small back stack (`Dibs.pages`): a task's page (state, questions,
  report, result, a chat with its own agent via `task-say`), its transcript and its REPORT.md, both fetched as dibs
  channel files (`DibsHost.channelFile`, ffi `app_files`). The chat parts are shared in `ui/ChatParts.kt`.
  It knows nothing of the core: the app implements `DibsHost` in `DibsBridge.kt` (actions via `Channels.act`, files
  via `sendChannelFile` then one `say` or `task-say` naming their ids, the `dibs-ui` hold while on screen). Every way into the dibs channel (channel list, shortcut,
  notification, share tile) opens `DibsActivity`; `MainActivity.classic` (extra `CLASSIC`) is Jarvis's old screen,
  for a dibs that sends no payload. Its look is regenerated by `android/dibs/regen-look.sh` (hue 180), never by hand.
- Root steps from the phone (`android/dibs` `Root.kt`, `RootKey.kt`, `ui/RootPage.kt`; docs/DIBS-APP.md "Root steps",
  dibs task #145, app 0.8.0): a `kind: "root"` Waiting card opens a page with the script and files word for word, the
  phone's own warnings and request code; Approve asks for a fingerprint or face and signs the message the laptop's
  `dibs-root` checks (`RootMessage.build`, byte-exact; the phone hashes every text itself) with the Keystore key
  `dibs-root-v1` (StrongBox, strong biometric on every use, never the PIN; new fingerprints end it). Set up
  (`kind: "rootkey"`) makes the key and sends its public half (`root-key`). Release signing: without
  `~/.config/jarvis/keystore.properties`, `scripts/install-phone.sh` builds unsigned and has `dibs root request` run
  the fixed `scripts/sign-apk.sh` (approved on the phone), then installs the APK it signed.
  Since dibs moved to the home server, ships run there (dibs task #288, 2026-10-08): the server has no Jarvis
  daemon (the recipe installs and restarts it only where `jarvis-link.service` exists) and reaches the phone over adb
  on the home Wi-Fi, and its root helper signs with a copy of the laptop's release key (the user's choice, task #295:
  one line pasted at the laptop piped the key folder from root to root and installed `sign-apk` beside the helper).
- Phone root screen: the channel list (`ui/ChannelScreen.kt`, state in `Channels.kt`); the chat is one entry.
  A channel view with a `thread` block (dibs's conversation) is laid out chat-first (`ChatFirst` in
  `ChannelScreen.kt`): the blocks before it fold to "N waiting", the thread fills the screen, its
  compose is the input bar. Its bubbles, day headers, runs and input bar are the Laptop chat's,
  shared in `ui/ChatParts.kt`. The phone tells its app version on every link; `jarvis-link status --json`
  shows it as `phone_app` (dibs sends a thread only to 0.3.8+).
- Phone UI: `ui/JarvisScreen.kt` (grouped bubbles, day headers, inline time and ✓✓, links, image
  thumbnails, file chips, setup sheet and unpair in the ⋮ menu). **Design: read `docs/DESIGN.md`
  before any UI work.** Jarvis uses the shared design language from `~/Projects/design` at hue 250
  (dark only, Rubik + Geist Mono, Lucide icons); the theme, fonts, launcher and icons are generated,
  never edited by hand, and channels pick an `icon` + `hue` the same way. `Thumbs` keeps
  `filesDir/thumbs/<id>.webp`, written on send and on save, because neither original stays put.
- Phone notifications (`Notifier.kt`): chat, pings and received files are **one** conversation
  notification (MessagingStyle, Person "Laptop", shortcut `laptop` so it sits under Conversations).
  Each line is appended to the posted notification, which holds the only state. Images show
  inline (MediaStore URI), other files are 📎 lines plus Open. Reply (RemoteInput), Mark as read and Copy
  (only when the newest line is a text; copies that text) go through `ChatActionReceiver` (a reply
  queues `sendText` and starts `SyncWorker`).
  It clears when the chat opens. `Transfers` shows one progress notification per batch of overlapping
  transfers ("Sending 3 files"; ProgressStyle, Live Update chip, Cancel only when sending: the core
  cancels only my own files) once a transfer runs past 2 s; it is the notification of
  `TransferService` (FGS type `dataSync`), which keeps the process up through the batch.
  The `problems` channel has three notices: delivery stuck (queued 30 min, Retry now; posted by
  `OutboxWorker` and when the node stops, cleared on refresh), pairing lost (`Event::Refused`, the
  laptop closed with not-paired; also a banner in the chat that opens unpair) and send failed
  (`Event::SendFailed`: the file went missing or shrank; the peer drops its `.part`).
  There is no "syncing" or "connected" notification.
- Phase 3 on the phone: `PhoneListener` (NotificationListenerService) mirrors notifications only
  over a link that is already up (a new link gets a snapshot) and owns `MediaMirror`. While a
  session plays (it watches every active session, so one that resumes takes over), MediaMirror holds
  the node (`media` hold, stay on) with no notification or service
  of its own (the media session's own notification is enough), and lets go 5 min after pause. It skips KDE Connect's sessions
  (`org.kde.kdeconnect_tp`): they mirror the laptop's MPRIS players, ours included, and would loop. KDE Connect is gone since the cut-over; the skip is harmless and stays.
  Battery is "Unrestricted" on the Pixel (`dumpsys deviceidle whitelist +com.kivan.jarvis`), which
  also lets `TransferService` start from the background. `Ringer` + `RingActivity`: alarm stream at max, full-screen Stop, 5 min cap.
- App channels (`docs/PLAN.md`, "App channels"): `apps.rs` reads the manifests
  `~/.config/jarvis/apps/<name>.toml` (at start and `jarvis-link channels --reload`), publishes the list
  as the `_channels` view, and starts a channel's app on demand (`exec` via `sh -c`). Channel data
  never enters the chat; the socket has `app_send/app_subscribe/app_action/channels/thread/list`.
  Create and change channels with `jarvis-link channel add|set|rm|ls` (`channel_cmd.rs`), not by hand.
  `show = both|phone|laptop|none` decides where a channel appears (`none`: only apps use it);
  `dir = auto` (default) gives each text its own direction.
  `jarvis-link view --check [<file>|-]` (`viewcheck.rs`) checks a view against the blocks the phone and the panel
  draw (docs/PLAN.md "Blocks"); fixture views, dibs's included, are in `crates/daemon/tests/views` and
  must pass. Publishing a view prints the same problems to stderr. Built-in kinds are channels the daemon
  runs itself: `kind = "list"` (`lists.rs`, `jarvis-link list <ch> add|ls|done|undo|rm|clear`) is a
  to-do/shopping list for any channel name, whose state lives in `~/.local/state/jarvis/lists/`
  and which an app reads with `jarvis-link list <ch> ls --pending --json`. New kinds follow that pattern.
  Channel notifications (`Notifier.app`) alert once: one per channel, or one per post `tag`
  (`jarvis-link post --tag`, Android tag `app:<channel>:<tag>`); a button tap cancels only its own, and a
  view's `open_tags` cancels the tagged ones it no longer lists.
- Daemon Phase 3: `mpris.rs` (zbus) holds `org.mpris.MediaPlayer2.jarvis.pixel` only while the
  phone reports a session. `jarvis-link notifications --fresh` wakes the phone (FCM), waits for the
  snapshot and keeps the link up 2 min (rami-login polls it). `jarvis-link ring --stop`.
- Phase 4, laptop chat: `shell/kivan.jarvis` (Omarchy plugin, linked and enabled by `shell/install.sh`,
  badge placed before `omarchy.tray`). `Badge.qml` is the bar widget (unread count; it has its own
  `jarvis-link watch` and re-reads `status --json`; the core's `read` event clears it). `Panel.qml`
  (keepLoaded) owns the data, the IPC target `jarvis-link` (`toggle|open|close|dropdown <x>|isOpen`)
  and one layer-shell window that shows `Chat.qml` as the dropdown (330×400, `ExclusionMode.Normal`
  so it sits under the bar) or centered (440×470, SUPER+M in `~/.config/hypr/bindings.lua`). Both
  modes are layer-shell with exclusive keyboard focus, not a `PopupCard`, because nothing in Omarchy
  types into an xdg popup. 📎 runs `jarvis-link send --pick` (FileChooser portal over zbus; `ashpd` is
  only 0.13), and the panel hides until the chooser closes.
  Ctrl+V on an image runs `jarvis-link paste` (saves the clipboard image under `~/.local/state/jarvis/pasted`,
  pruned after 14 days, and prints its path), which goes into an attach strip above the input and is not
  sent; Enter sends the strip and the text through `ui.sendWith`. Otherwise Ctrl+V pastes text. Right-click
  on a file: Open, and Copy image (`jarvis-link copy <file>`, PNG on the clipboard) or Copy path.
  Chat and ping toasts run `omarchy-shell jarvis open` on click.
- Image bubbles fit the whole image at its own aspect on both sides: the laptop inside 220×220
  (`Chat.qml`, `sourceSize` sets both sides), the phone inside 260×320 (`ContentScale.Fit`). Never crop.
- Logging: the ffi's filter is `info,jarvis_core=debug,iroh=warn,netwatch=error,swarm_discovery=error`
  (`crates/ffi/src/lib.rs`), because swarm_discovery logs every failed mDNS send (EPERM) on the phone.
- netwatch (iroh's UDP sockets) is a patched copy in `vendor/netwatch` (`[patch.crates-io]`): a failed rebind
  after a network change is retried instead of leaving the laptop link's socket dead (`vendor/netwatch/PATCHED.md`).
- Tabs, the app's next home (thread #167): `android/.../tabs`, opened from a button on today's home, which stays as it
  was. The look is the jarvis repo's `design/jarvis-app/LOOK.md`, "On the phone"; `docs/DESIGN.md` doesn't apply here.
  `Stage.kt` holds the one zoom (tap, pinch, predictive back) between the wall (`Wall.kt`, `Grid.kt`) and a tab
  (`TabPage.kt`); `ReplyPage.kt` is the reply page. The screens only draw `Deck`: `RealDeck.kt` keeps what's stored and
  shows it at once, and carries frames through `crates/ffi/src/tabs.rs` on the tunnel's handshake `tabs1`, which the gate
  pipes to the tab server's socket. `crates/tabs-wire` is a copy of the jarvis repo's `tabs/wire` (that repo is private),
  held to the same goldens. Fake tabs (`src/debug/.../Fake.kt`) exist only in debug and bench builds: `am start -n
  com.kivan.jarvis.debug/com.kivan.jarvis.tabs.TabsActivity --ei tabs 9`. Screenshots without an emulator:
  `:app:testDebugUnitTest -Proborazzi.test.record=true --tests '*ScreensTest*'`, in `app/build/shots`. Startup and frames:
  `-Pbench` adds a shrunk emulator-only build and the `:bench` module; `-Pbench=plain` is what `Profile` takes
  `app/src/main/baseline-prof.txt` from.

## The move from the old name (thread #190)
The app was `com.kivan.tether` until 2026-10-30. Android treats the new id as another app, so `com.kivan.jarvis` moved
in instead of updating: tag `old-app-last` is the old app's last build, which offers the new one and hands over its
data through a signature-only provider. The new app sits in standby, link off (`Move.off`, enforced in `Forum.start`,
`Core.start`, `Voice.start`), until it has everything; `Move.kt` holds the steps. Exactly one app may hold the phone's
iroh key. Only two places may still say the old name: `crates/core/src/compat.rs` (the laptop's daemon predates the
rename: ALPNs, pairing code, socket, directories; drop it once the laptop runs `jarvis-link`) and `OLD_APP` in
`android/app/build.gradle.kts` (drop `Move` once no phone can have the old app).

## Commits
End every commit message with one line `For you: <what the user will notice, in plain words>` (e.g. `For you: the phone stops showing "Queued 4".`). dibs's update question shows these lines instead of the commit titles, which the user found unreadable. A commit the user won't notice (docs, tests) can skip it.

## Build and test
Rust is pinned in `mise.toml` (1.98.1). Run `cargo test` and `cargo build --release`.
Android: `cd android && ANDROID_HOME=~/Android/Sdk PATH=~/.cargo/bin:$PATH ./gradlew :app:assembleRelease` (needs the `aarch64-linux-android` target and
`cargo-ndk`).

## Design decisions (keep these)
- **Transport is iroh 1.3** with `presets::N0`, which includes n0's free public relay, chosen by the
  user. Traffic is end-to-end encrypted. Add `iroh-mdns-address-lookup` 0.6 for the LAN and bind a fixed UDP port
  (`bind_addr("0.0.0.0:47114")`) so nftables needs one rule. See `docs/iroh-1.3-notes.md`.
- **Battery: the phone has no connection of its own when idle; it is woken through FCM.** The user
  agreed to this on 2026-10-01, and battery must be watched. **Superseded on trial (2026-10-26):** the lean app
  keeps the node always on instead (no Firebase); measure the battery, and bring the FCM wake back if it costs.
  - Why: iroh sends a QUIC heartbeat every 5 s on an open connection. The home-relay link also pings
    every 15 s, and that interval is a hard-coded constant (`iroh-1.3.0/src/socket/transports/relay/actor.rs:74`).
    Doze cuts both anyway.
  - Phone→laptop: the phone dials only when it has something to send. The laptop is always listening.
  - Laptop→phone: if the phone isn't connected, the laptop sends a high-priority FCM data message
    with **no content** (`{"t":"wake"}`). The phone wakes, starts its iroh endpoint, dials the laptop,
    takes delivery of the queue, and then shuts the endpoint down. Google learns only that a ping
    happened.
  - The phone stays connected (endpoint up, connection open) only while it is cheap or needed:
    while media is playing (so bar controls are instant), while the chat screen is open, or while the
    app is in the foreground.
  - Any connection closes after about 60 s with no traffic.
  - Without FCM (no key, or Google is down) the laptop retries queued items with backoff (30 s up to
    5 min), and the phone checks in when the app starts and when its network changes.
  - High-priority FCM must lead to a visible notification, or Android throttles it. Chat, files,
    pings and rings all show one. Media commands are only sent while the phone is already connected.
  - FCM setup: a free Firebase project. The service-account JSON goes to
    `~/.config/jarvis/fcm-service-account.json` (0600). The laptop calls the FCM HTTP v1 API.
    The phone sends its registration token over the paired connection (`Frame::PushToken`).
    The sender is `crates/daemon/src/fcm.rs`: it sends at most one wake per 30 s, `--fcm-key` sets
    the key path, and `cargo test -p jarvis-link -- --ignored fcm_live` checks auth against Google.
    A wake with no link 30 s later is logged as unanswered and reported as `wake_unanswered` in
    `status` (the panel shows "phone didn't answer"); the next link clears it. Known cause
    (2026-10-03): Tailscale on the phone with strict Private DNS broke all system DNS, so Play
    services couldn't reach FCM. Private DNS is now Automatic (see `~/.config/system-notes.md`).
  - The user rejected battery-costly background daemons on the phone before.
- **If both sides dial at once**, keep the connection whose dialer has the smaller endpoint id. Both
  sides then agree.
- **Offline delivery.** Every item goes into the sender's `messages` table as `queued`. On each
  connect the sender resends all queued items. The receiver dedupes by uuid and sends `Ack`, and the
  sender marks the item `delivered`. A file item is acked only after its bytes are complete and the
  sha256 matches. Resuming a file works like this: the receiver sends `FileWant{offset = len of .part}`,
  and the sender opens a uni stream with a `FileHeader` and streams from that offset.
- **Pairing.** `jarvis-link pair` shows a QR with `jarvis-link:1:<endpoint id>:<base32 32-byte token>`, valid
  for 5 minutes and usable once. The phone connects with `PAIR_ALPN` and sends the token, and then
  each side stores the other's id. Connections from any other endpoint id are refused.
- **Media and notifications are live state** and are never queued.
- **Cancelling a file** (`jarvis-link cancel <id>`, ✕ on the phone): the sender marks it `cancelling`, stops
  streaming, and sends `Frame::Cancel{id}`. The receiver drops the `.part`, marks it `cancelled`, and echoes
  `Cancel` back as confirmation; then the sender marks it `cancelled`. The sender repeats the Cancel on each
  new link until it is confirmed. If the file finished first, the receiver's Ack wins and it stays delivered.
- **Laptop side effects stay in the daemon, not the core.** They are:
  - an image goes onto the clipboard as image data (`wl-copy --type`, converted to PNG with EXIF
    orientation);
  - a toast with `omarchy-notification-send --image file://… --exec xdg-open`, because DND drops
    toasts from other senders;
  - MPRIS `org.mpris.MediaPlayer2.jarvis.pixel` through zbus.

## Machine notes
- Phone over adb: `pixel` (in `~/.local/bin`) connects to the Pixel over Wi-Fi and prints its serial: last address that
  worked, else a scan of LAN hosts for the wireless-debugging port. The phone trusts dibs's old key, now `~/.android/adbkey`
  (Jarvis's own unpaired one is `adbkey.jarvis-own`). Always pass `-s`, and check which app has focus before any `adb input`.
- QML plugin edits only take effect after `omarchy restart shell`.

## Screens
- A change to a screen of the dibs app or the Jarvis app is shot and looked at before it ships: `dibs screens list --changed`, then `dibs screens shoot --changed` after the tests (the screen map is `screens.toml` in the dibs repo; the Pixel is shot only when it is lent, by intent, never by tapping). `dibs screens shoot <names>` for before/after shots, `--all` only for a big rework. `DibsActivity.EXTRA_PAGE` opens a read-only page by intent for this.
