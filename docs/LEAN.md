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

One quiet foreground service keeps the process, the Tether node and the forum tunnel up, and
starts at boot. The node is always listening, so the laptop dials it whenever it has something
(directly at home, through the relay away); the link still closes when idle. No Firebase, no wake
pushes, no WorkManager: the laptop's daemon notes the missing push key and its redial delivers
(`crates/daemon/src/fcm.rs`).

## What's gone

Everything else in the old app: dibs's screens and channels, the hand-over, ride, ring, phone
media control, notification mirroring, presence, the adb helper, self-update, dictation, usage
rings, live-update transfer chips. The laptop's commands for those (`tether ring`, the media
widget, `tether notifications`, `tether adb`) get no answer from the phone now.

## Built from

- `crates/core` and `crates/ffi`, unchanged: they are what the laptop's daemon runs, so the wire
  matches by construction. The forum tunnel is a small module in `crates/ffi` (`forum.rs`).
- `android/`: a new Gradle project replacing the frozen one (git keeps the old). Package
  `com.kivan.tether`, named Tether, signed with Jarvis's key in `~/.config/jarvis/`. The old app is
  uninstalled first, so a different key is fine; the phone pairs again once.
- Build: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleRelease`.
