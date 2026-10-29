# Tether, lean (2026-10-26)

Aviv: "why not make it just like tether for dibs? i.e., it all started from when i wanted a simple
way to send messages, files and screenshots from laptop to phone and vice versa, and it ran iroh /
so can you just ride on tether? / a stripped down version, not like the dibs one which had
"channels" as well, only a chat / ... uninstall tether from my phone, and then install this new app
and also call it tether / but redesign it first to get rid of old things, i.e., take only what is
needed, not all of the old app".

## What the phone app is now

One screen, one chat with the laptop, and the forum tunnel.

- **Chat:** text both ways, delivered later when the other side is offline (the core's outbox).
- **Files and screenshots both ways:** the "Laptop" entry in the share sheet, an attach button in
  the chat, and files from the laptop land in Downloads with a notification.
- **Notifications** for what the laptop sends, with Reply.
- **Pairing:** scan the QR that `tether pair` shows on the laptop.
- **The forum tunnel:** `localhost:8080` on the phone over iroh to the forum's gate on dibs (the
  Jarvis repo's `forum/`), with this phone's own key. The forum opens in Chrome; a menu item opens it.

## How it stays reachable

The phone holds no connection while idle, as chosen on Oct 1 (Aviv, on the lean app's always-on
node: "why not do that? sounds like a good idea"). The Tether node runs only while the chat is on
screen, something of the phone's is queued or moving, a link is open, or a wake or send has just
started it; then it stops (`Core.watch`). When the laptop has something, its daemon sends a
content-free FCM wake (`crates/daemon/src/fcm.rs`), and the phone dials it (`Push.kt`). The
Firebase client values live outside git in `~/.config/jarvis/tether-fcm.properties`; without them
the app builds and works, just without wakes.

One quiet foreground service keeps the process alive, so the forum tunnel's `localhost:8080`
answers whenever Chrome opens it; the tunnel's iroh endpoint starts only then and closes after 3
minutes idle. The service itself holds no network and no wakelock. No WorkManager: the live process
is enough.

## Updates from the server

Jarvis publishes a finished build on the server (`scripts/publish-app.sh` after
`assembleRelease`; it runs the jarvis repo's `forum-gate publish`), and the app takes it through the
forum tunnel, from anywhere, mobile data too (`Updater.kt`; `update_check`/`update_fetch` in
`crates/ffi/src/forum.rs`: the gate's `build` handshake). versionCode is the repo's commit count, so
every commit is newer. The app checks on opening (at most every 10 min), from the ⋮ menu, and every
6 hours in the background; a newer build is downloaded (in the background only on Wi-Fi), checked
against its sha256 and installed by Android without a question: Tether holds
`UPDATE_PACKAGES_WITHOUT_USER_ACTION` and asks to own its updates (`setRequestUpdateOwnership`), so once
it is the installer of record no update asks. The first one after an adb install still asks for one
tap; in the background that question waits in a "Tether 1.0.N is ready" notification. On mobile data
the background check only posts that notification; its tap opens the app, which downloads and installs.
A call doesn't hold an update off (thread #132): the install is committed in a pause (no reply playing
or queued, Aviv quiet), the process restarts on MY_PACKAGE_REPLACED and the call comes back at once.
A "Cancel" on the question isn't asked again on its own for 12 hours. The very first update from the
app needs Android's "Install unknown apps" switch for Tether, once.

## What's gone

Everything else in the old app: dibs's screens and channels, the hand-over, ride, ring, phone
media control, notification mirroring, presence, the adb helper, dictation, usage
rings, live-update transfer chips. The laptop's commands for those (`tether ring`, the media
widget, `tether notifications`, `tether adb`) get no answer from the phone now.

## Built from

- `crates/core` and `crates/ffi`, unchanged: they are what the laptop's daemon runs, so the wire
  matches by construction. The forum tunnel is a small module in `crates/ffi` (`forum.rs`).
- `android/`: a new Gradle project replacing the frozen one (git keeps the old). Package
  `com.kivan.tether`, named Tether, signed with Jarvis's key in `~/.config/jarvis/`. The old app is
  uninstalled first, so a different key is fine; the phone pairs again once.
- Build: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`.
