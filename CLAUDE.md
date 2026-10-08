# Tether

A lean replacement for KDE Connect between this laptop (Omarchy/Hyprland) and the Pixel 8.
It covers chat with offline delivery, files both ways (the Android Direct Share tile is called
"Laptop"), phone media control from the bar, ring phone, pings, and mirrored phone notifications.
The full plan is in `docs/PLAN.md`. Read it before changing scope.

## Layout
- `crates/core`: protocol (`proto.rs`), SQLite chat log/outbox (`store.rs`), file helpers (`files.rs`).
  There is no platform code here. The phone uses this same core through uniffi (`crates/ffi`).
- `crates/ffi`: uniffi 0.32 bindings (`TetherNode`, records, `EventListener` callback) with its own
  tokio runtime. Kotlin package `com.kivan.tether.core` (`uniffi.toml`).
- `crates/daemon`: the `tether` binary. It runs the daemon (systemd user unit) and the CLI, which talks
  to the daemon over `$XDG_RUNTIME_DIR/tether.sock`.
- `crates/core/src/node.rs`: the iroh endpoint and the single link (pairing, dial race, outbox resend,
  file resume, idle close). Its tests run two nodes over loopback (`Net::Loopback`).
- `contrib/tether.service`: the systemd user unit. Installed on 2026-10-02 (earlier than planned, the user
  chose it) so Tether ran side by side with KDE Connect until the Phase 5 cut-over (2026-10-02: KDE Connect
  removed from the laptop, nftables opens only UDP 47114 from the LAN for Tether): binary from
  `cargo install --path crates/daemon --locked` (`~/.cargo/bin/tether`), state and the paired identity in
  `~/.local/state/tether` (moved from the old dev dir), unit listed in `~/.config/laptop/paths`.
  After daemon changes: reinstall, then `systemctl --user restart tether`.
- `android/`: Kotlin/Compose app `com.kivan.tether` (toolchain copied from ~/Projects/chordhand).
  Gradle runs cargo-ndk and uniffi-bindgen itself (`buildSrc/.../RustTasks.kt`). `Core.kt` owns the
  node: it runs only while held (UI, `SyncWorker` after FCM wake, share or reply, `OutboxWorker`
  retries, `TransferService`) or a link is open, then shuts down. `SyncWorker` is an expedited job with
  no notification; it ends once the link has been quiet 10 s with nothing queued, no transfer, no ring
  and no `peer_stays()` (the laptop asked to keep the link, e.g. `notifications --fresh`), because the
  expedited quota is small. FCM is configured from `fcm.*` in `android/local.properties`.
  Release signing reads `~/.config/tether/keystore.properties`; without it, a phone-approved root step signs (below).
  `Native.load` (in `TetherApp`) loads the .so through the JVM and hands iroh the app context, so it
  reads the phone's DNS servers; it must run before the first node start. The phone's mDNS send
  fails with EPERM (no `MulticastLock`). That's deferred on purpose: a lock costs battery, the home
  Wi-Fi drops client-to-client multicast anyway, and relay plus hole-punching finds the direct path.
- adb on any network (`Adb.kt`, `tether adb [--enable]`, 2026-10-05): dibs asks where the phone's adb listens
  when adb can't see it (the phone's Wi-Fi filter drops multicast mDNS while the screen is off). The daemon wakes
  the phone (FCM), sends a live `App` frame on the reserved channel `_adb` (`{"op":"endpoint","enable":bool}`) and
  waits up to 40 s for the reply on the same channel: `wifi`, `addrs` (`ip/prefix`), `adb_wifi`, `port` and `name`
  (NsdManager own-service lookup, the port checked by binding it on loopback), `can_enable`, `enabled`, `refused`.
  Two permissions are granted once over adb (the user's yes, 2026-10-05): WRITE_SECURE_SETTINGS (turns
  `adb_wifi_enabled` back on; Android writes 0 back on a network not marked "Always allow", which is `refused`) and
  ACCESS_LOCAL_NETWORK (Android 17: without it NsdManager opens a picker screen instead of answering, so `Adb.kt`
  only looks up the port when it's granted). **An APK without these manifest lines drops both grants.** The app
  holds a multicast lock for 20 s per ask so adb's own mDNS sees the phone meanwhile. `tether connect` now wakes
  the phone first (it used to only dial, which timed out against an idle phone).
- Phone presence for dibs (`Presence.kt`, `crates/daemon/src/presence.rs`, app 0.5.6, 2026-10-06): the phone tells
  the laptop when the user unlocks or uses it, so dibs knows they're on the phone even away from home. Live `App`
  frames on the reserved channel `_presence` (never queued): `{"op":"present","why":"unlock"|"use","ts":<phone ms>}`
  and `{"op":"off","ts":…}`. `PhoneListener` (bound by the system, so no service of ours) registers the receiver
  for USER_PRESENT, SCREEN_ON (counts as an unlock when the keyguard isn't locked) and SCREEN_OFF. Battery: an
  unlock dials at most once a minute (unless the screen went off between, as the laptop was told "off"); while the screen stays on and unlocked a `use` renewal dials every 5 min;
  `off` goes only over a link already up (`Core.sendLive`), never dials; nothing while refused or unpaired. The
  daemon keeps the newest (by the phone's ts) in memory: `tether watch` gets
  `{"type":"presence","op","why"|null,"ts_ms","got_ms"}` per frame, `tether status --json` `phone_presence`
  (the same without `type`, null until the first frame since the daemon started). `_presence` and `_adb` are
  refused for sending (`check_channel`, as any `_…`) and for `app_subscribe`.
- dibs's own app (`android/dibs`, Gradle module `:dibs`, plan in `docs/DIBS-APP.md`, version 0.11.0): `DibsActivity`
  (own launcher icon and Recents card) with five tabs (Chat, Ideas, Waiting, Tasks, Recap) under one slim pinned bar (mark, name, state, marks for what is lent or a usage window past 80 %, ⋮); the lend switches, usage and laptop load live on the dibs page (`Page.Status`, `ui/StatusPage.kt`), opened by a tap on the bar. Drawn from the `dibs` payload in
  the dibs channel's view (`Payload.kt`; chat folding in `ChatModel.kt`, task order in `TasksModel.kt`, unit-tested).
  Tasks holds the user's own tasks (`yours`) until ticked, with dibs's own work folded at its bottom (a dibs without
  `yours` gets the old Work tab). Over the tabs, a small back stack (`Dibs.pages`): a task's page (state, questions,
  report, result, a chat with its own agent via `task-say`), its transcript and its REPORT.md, both fetched as dibs
  channel files (`DibsHost.channelFile`, ffi `app_files`). The chat parts are shared in `ui/ChatParts.kt`.
  It knows nothing of the core: the app implements `DibsHost` in `DibsBridge.kt` (actions via `Channels.act`, files
  via `sendChannelFile` then one `say` or `task-say` naming their ids, the `dibs-ui` hold while on screen). A tap shows at once (a spinner on the button or the line at the bottom) until dibs's view lists its uid in
  `heard`; not heard after 20 s of link: "dibs didn't answer" with Try again (`Dibs.tap`, `ui/TapLine.kt`). Every way into the dibs channel (channel list, shortcut,
  notification, share tile) opens `DibsActivity`; `MainActivity.classic` (extra `CLASSIC`) is Tether's old screen,
  for a dibs that sends no payload. Its look is regenerated by `android/dibs/regen-look.sh` (hue 180), never by hand.
- Root steps from the phone (`android/dibs` `Root.kt`, `RootKey.kt`, `ui/RootPage.kt`; docs/DIBS-APP.md "Root steps",
  dibs task #145, app 0.8.0): a `kind: "root"` Waiting card opens a page with the script and files word for word, the
  phone's own warnings and request code; Approve asks for a fingerprint or face and signs the message the laptop's
  `dibs-root` checks (`RootMessage.build`, byte-exact; the phone hashes every text itself) with the Keystore key
  `dibs-root-v1` (StrongBox, strong biometric on every use, never the PIN; new fingerprints end it). Set up
  (`kind: "rootkey"`) makes the key and sends its public half (`root-key`). Release signing: without
  `~/.config/tether/keystore.properties`, `scripts/install-phone.sh` builds unsigned and has `dibs root request` run
  the fixed `scripts/sign-apk.sh` (approved on the phone), then installs the APK it signed.
- Phone root screen: the channel list (`ui/ChannelScreen.kt`, state in `Channels.kt`); the chat is one entry.
  A channel view with a `thread` block (dibs's conversation) is laid out chat-first (`ChatFirst` in
  `ChannelScreen.kt`): the blocks before it fold to "N waiting", the thread fills the screen, its
  compose is the input bar. Its bubbles, day headers, runs and input bar are the Laptop chat's,
  shared in `ui/ChatParts.kt`. The phone tells its app version on every link; `tether status --json`
  shows it as `phone_app` (dibs sends a thread only to 0.3.8+).
- Phone UI: `ui/TetherScreen.kt` (grouped bubbles, day headers, inline time and ✓✓, links, image
  thumbnails, file chips, setup sheet and unpair in the ⋮ menu). **Design: read `docs/DESIGN.md`
  before any UI work.** Tether uses the shared design language from `~/Projects/design` at hue 250
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
  Battery is "Unrestricted" on the Pixel (`dumpsys deviceidle whitelist +com.kivan.tether`), which
  also lets `TransferService` start from the background. `Ringer` + `RingActivity`: alarm stream at max, full-screen Stop, 5 min cap.
- App channels (`docs/PLAN.md`, "App channels"): `apps.rs` reads the manifests
  `~/.config/tether/apps/<name>.toml` (at start and `tether channels --reload`), publishes the list
  as the `_channels` view, and starts a channel's app on demand (`exec` via `sh -c`). Channel data
  never enters the chat; the socket has `app_send/app_subscribe/app_action/channels/thread/list`.
  Create and change channels with `tether channel add|set|rm|ls` (`channel_cmd.rs`), not by hand.
  `show = both|phone|laptop|none` decides where a channel appears (`none`: only apps use it);
  `dir = auto` (default) gives each text its own direction.
  `tether view --check [<file>|-]` (`viewcheck.rs`) checks a view against the blocks the phone and the panel
  draw (docs/PLAN.md "Blocks"); fixture views, dibs's included, are in `crates/daemon/tests/views` and
  must pass. Publishing a view prints the same problems to stderr. Built-in kinds are channels the daemon
  runs itself: `kind = "list"` (`lists.rs`, `tether list <ch> add|ls|done|undo|rm|clear`) is a
  to-do/shopping list for any channel name, whose state lives in `~/.local/state/tether/lists/`
  and which an app reads with `tether list <ch> ls --pending --json`. New kinds follow that pattern.
  Channel notifications (`Notifier.app`) alert once: one per channel, or one per post `tag`
  (`tether post --tag`, Android tag `app:<channel>:<tag>`); a button tap cancels only its own, and a
  view's `open_tags` cancels the tagged ones it no longer lists.
- Daemon Phase 3: `mpris.rs` (zbus) holds `org.mpris.MediaPlayer2.tether.pixel` only while the
  phone reports a session. `tether notifications --fresh` wakes the phone (FCM), waits for the
  snapshot and keeps the link up 2 min (rami-login polls it). `tether ring --stop`.
- Phase 4, laptop chat: `shell/kivan.tether` (Omarchy plugin, linked and enabled by `shell/install.sh`,
  badge placed before `omarchy.tray`). `Badge.qml` is the bar widget (unread count; it has its own
  `tether watch` and re-reads `status --json`; the core's `read` event clears it). `Panel.qml`
  (keepLoaded) owns the data, the IPC target `tether` (`toggle|open|close|dropdown <x>|isOpen`)
  and one layer-shell window that shows `Chat.qml` as the dropdown (330×400, `ExclusionMode.Normal`
  so it sits under the bar) or centered (440×470, SUPER+M in `~/.config/hypr/bindings.lua`). Both
  modes are layer-shell with exclusive keyboard focus, not a `PopupCard`, because nothing in Omarchy
  types into an xdg popup. 📎 runs `tether send --pick` (FileChooser portal over zbus; `ashpd` is
  only 0.13), and the panel hides until the chooser closes.
  Ctrl+V on an image runs `tether paste` (saves the clipboard image under `~/.local/state/tether/pasted`,
  pruned after 14 days, and prints its path), which goes into an attach strip above the input and is not
  sent; Enter sends the strip and the text through `ui.sendWith`. Otherwise Ctrl+V pastes text. Right-click
  on a file: Open, and Copy image (`tether copy <file>`, PNG on the clipboard) or Copy path.
  Chat and ping toasts run `omarchy-shell tether open` on click.
- Image bubbles fit the whole image at its own aspect on both sides: the laptop inside 220×220
  (`Chat.qml`, `sourceSize` sets both sides), the phone inside 260×320 (`ContentScale.Fit`). Never crop.
- Logging: the ffi's filter is `info,tether_core=debug,iroh=warn,swarm_discovery=error`
  (`crates/ffi/src/lib.rs`), because swarm_discovery logs every failed mDNS send (EPERM) on the phone.

## Commits
End every commit message with one line `For you: <what the user will notice, in plain words>` (e.g. `For you: the phone stops showing "Queued 4".`). dibs's update question shows these lines instead of the commit titles, which the user found unreadable. A commit the user won't notice (docs, tests) can skip it.

## Build and test
Rust is pinned in `mise.toml` (1.98.1). Run `cargo test` and `cargo build --release`.
Android: `cd android && ./gradlew :app:assembleRelease` (needs the `aarch64-linux-android` target and
`cargo-ndk`).

## Design decisions (keep these)
- **Transport is iroh 1.3** with `presets::N0`, which includes n0's free public relay, chosen by the
  user. Traffic is end-to-end encrypted. Add `iroh-mdns-address-lookup` 0.6 for the LAN and bind a fixed UDP port
  (`bind_addr("0.0.0.0:47114")`) so nftables needs one rule. See `docs/iroh-1.3-notes.md`.
- **Battery: the phone has no connection of its own when idle; it is woken through FCM.** The user
  agreed to this on 2026-10-01, and battery must be watched.
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
    `~/.config/tether/fcm-service-account.json` (0600). The laptop calls the FCM HTTP v1 API.
    The phone sends its registration token over the paired connection (`Frame::PushToken`).
    The sender is `crates/daemon/src/fcm.rs`: it sends at most one wake per 30 s, `--fcm-key` sets
    the key path, and `cargo test -p tether -- --ignored fcm_live` checks auth against Google.
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
- **Pairing.** `tether pair` shows a QR with `tether:1:<endpoint id>:<base32 32-byte token>`, valid
  for 5 minutes and usable once. The phone connects with `PAIR_ALPN` and sends the token, and then
  each side stores the other's id. Connections from any other endpoint id are refused.
- **Media and notifications are live state** and are never queued.
- **Cancelling a file** (`tether cancel <id>`, ✕ on the phone): the sender marks it `cancelling`, stops
  streaming, and sends `Frame::Cancel{id}`. The receiver drops the `.part`, marks it `cancelled`, and echoes
  `Cancel` back as confirmation; then the sender marks it `cancelled`. The sender repeats the Cancel on each
  new link until it is confirmed. If the file finished first, the receiver's Ack wins and it stays delivered.
- **Laptop side effects stay in the daemon, not the core.** They are:
  - an image goes onto the clipboard as image data (`wl-copy --type`, converted to PNG with EXIF
    orientation);
  - a toast with `omarchy-notification-send --image file://… --exec xdg-open`, because DND drops
    toasts from other senders;
  - MPRIS `org.mpris.MediaPlayer2.tether.pixel` through zbus.

## Machine notes
- Phone over adb: `dibs phone connect` finds it on any network (through `tether adb` when mDNS can't); never
  store or ask for an IP or port. Always pass `-s`, and check which app has focus before any `adb input`.
- QML plugin edits only take effect after `omarchy restart shell`.
