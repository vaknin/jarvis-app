# Jarvis — own Rust replacement for KDE Connect (idea 338810ec)

## Context
The idea note: KDE Connect is bloated and unreliable. Build a leaner Rust version: simpler pairing, far fewer
options, plus a **chat** between laptop and phone that also delivers messages to an offline
device later, peer-to-peer with no cloud. It must keep the current "phone sends screenshot → laptop
clipboard" behaviour. (The note's first line, KDE Connect dark theme, is dropped: the app goes away.)

Decisions so far (user):
- Connect anywhere: direct on LAN, otherwise through **n0's free public iroh relay**. Everything stays
  end-to-end encrypted, so the relay can't read it. It is meant for hobby use: no SLA, rate-limited, and
  iroh has to be kept current. Moving to a self-hosted relay later is a config change.
- Phone → laptop: an Android **Direct Share tile "Laptop"** at the top of the share sheet, like
  KDE Connect's, not auto-sending every screenshot.
- Keep: send files and screenshots both ways, **phone media control from the laptop** (bar widget),
  **ring phone**. Drop: clipboard sync, notification toasts, the 30 other plugins.
- Laptop chat UI (picked from the mockups at https://claude.ai/artifact/1U3QvzxTCqRn5oYohQ1Cq9):
  **one QML chat component with two ways in**. Clicking the bar badge (which shows the unread count)
  opens it as a dropdown, and SUPER+M opens the same component as a centered panel.

Hidden consumers of KDE Connect found on the laptop, which all have to move to Jarvis:
| Consumer | Today | After |
|---|---|---|
| `~/.local/bin/kdeconnect-share-notify` + user unit | dbus-monitor `shareReceived` → `wl-copy` image (magick→PNG for non-PNG/GIF) + `omarchy-notification-send --image file:// --exec xdg-open` | built into the daemon; unit removed |
| `kivan.phonemedia` widget | MPRIS players with prefix `org.mpris.MediaPlayer2.kdeconnect.` | daemon publishes MPRIS `org.mpris.MediaPlayer2.jarvis.pixel`; change `busPrefix` |
| `~/.local/bin/dictation` (`playerctl -l \| grep '^kdeconnect\.'`), voxtype `pause_media_ignored_players` | player name | grep/ignore `jarvis-link` |
| scheduled-jobs `scripts/notify`, `/usr/local/bin/suspend-guard` (source `~/.config/laptop/etc/local-bin/`) | `kdeconnect-cli --ping-msg` | `jarvis-link ping "msg"` (queued if offline; now works off-LAN too) |
| `headless-browser/scripts/rami-login.ts` | reads phone notifications over KDE Connect D-Bus for the SMS code | `jarvis-link notifications --json` (phone mirrors active notifications, not shown as toasts) |
| nftables `1714-1764` tcp+udp, packages list `kdeconnect` | | open one UDP port for Jarvis instead; remove the package |

## Architecture
Project `~/Projects/jarvis`, a Cargo workspace plus a Gradle project, both on GitHub (private).

```
crates/core     protocol, SQLite store (rusqlite bundled), sync/outbox, file transfer; no platform code
crates/daemon   `jarvis-link` binary: daemon (systemd user unit) + CLI subcommands, talks to daemon over
                $XDG_RUNTIME_DIR/jarvis.sock (JSON lines)
crates/ffi      uniffi bindings of core for Android (cdylib, arm64-v8a via cargo-ndk)
android/        Kotlin + Compose app (com.kivan.jarvis), same toolchain as chordhand
shell/          Omarchy plugin(s) for the chosen chat UI + bar badge
```

**Transport**: iroh 1.x (1.3.0 is the latest, 2026-09-28; re-check at implementation time). Each device is an Ed25519
endpoint key. ALPN `jarvis/1`. Discovery: n0 preset (DNS/pkarr + relay) + `iroh-mdns-address-lookup`
(0.5, n0-maintained) for fast LAN finding. Accept handler drops any peer whose key isn't the paired one.
Daemon binds a fixed UDP port (e.g. 47114) so the firewall needs one LAN rule.

**Why one Rust core with uniffi on the phone, not a Kotlin re-implementation**: the protocol, outbox and
resume logic exist only once and are tested once. uniffi is still 0.x, but Mozilla ships it in Firefox
for Android, so it meets "production-proven". It's the one exception to the 1.0+ rule. Check the
current uniffi and cargo-ndk versions before adding them.

**Pairing (the entire flow)**: `jarvis-link pair` shows a QR in the terminal (laptop endpoint id + one-time
32-byte secret, valid 5 min). Phone app → "Pair" → scan → connects, proves secret, both store the
other's public key. No device list, no plugin toggles. `jarvis-link unpair` wipes it.

**Protocol**: one QUIC connection; control stream with length-prefixed postcard frames:
`Hello{max_seq_seen_from_you}`, `Chat{id(uuid), seq, ts, text | file_ref}`, `Ack{seq}`, `Ping{text}`,
`Ring/StopRing`, `MediaState{title, artist, pos, dur, playing, volume}`, `MediaCmd{…}`,
`Notifs{list}` (phone→laptop snapshot+diffs). Files: one uni-stream per file
`{id, name, size, sha256, offset}` + bytes, resumable from offset.

**Offline delivery**: every chat message, file offer and ping goes into the sender's SQLite outbox
with a per-sender monotonic `seq`. On every (re)connect both sides send `Hello` with the highest seq
they hold from the other, and the other resends everything after it. The receiver dedupes by id and
acks, and acked rows are marked delivered. Messages arrive when both devices are online at the same
time, which the relay makes almost always.

**Phone app** (Kotlin/Compose):
- **Wake, then connect (battery).** When idle, the phone runs no endpoint and holds no connection.
  The laptop wakes it with a content-free high-priority FCM data message. The phone then runs a
  short expedited job with no notification (`SyncWorker`, 2026-10-03; it replaced a `remoteMessaging`
  foreground service and its "Syncing with Laptop" notification) that dials, syncs, and ends once the
  link is quiet with nothing pending. Long transfers run in a `dataSync` foreground service whose
  notification is the transfer progress. It stays connected only while media is playing, the chat is open, or the app is
  in the foreground (`StayConnected`). The phone sends its FCM token as `Frame::PushToken`.
  Details are in CLAUDE.md. Check battery use after a day, under Settings → Battery for Jarvis.
- Direct Share: `res/xml/shortcuts.xml` share-target + `ShortcutManagerCompat.pushDynamicShortcut`
  ("Laptop", long-lived, category `com.kivan.jarvis.SHARE`) + `ACTION_SEND/SEND_MULTIPLE` activity
  that queues files. Text shares go into the chat as messages.
- Chat screen (Compose), pairing via QR scan (CameraX + ML Kit barcode, or ZXing embedded; pick at impl).
- `NotificationListenerService`: mirrors active notifications and, via `MediaSessionManager`,
  publishes the active media session's state and applies commands (play/pause/next/prev/seek/volume).
- Ring: max-volume alarm-stream sound + full-screen notification with Stop.
- Receiving files saves them to Downloads/Jarvis via MediaStore and shows a notification.

**Laptop daemon** (`jarvis-link daemon`, systemd user unit, `Restart=on-failure`):
- FCM wake sender (`crates/daemon/src/fcm.rs`, done): FCM HTTP v1 with a service-account JWT (ring
  RS256, reqwest; both already built for iroh) from `--fcm-key`/`$JARVIS_FCM_KEY`, default
  `~/.config/jarvis/fcm-service-account.json` (0600). It wakes when an item is queued and the phone
  isn't connected, at most once per 30 s, and once at start if the outbox isn't empty. The payload is
  `data {t: wake}`, `android {priority: high, collapse_key: wake}`. `UNREGISTERED`/404 drops the
  token. With no key or a failed send, the node's backoff redial still delivers.
  Live auth check: `cargo test -p jarvis-link -- --ignored fcm_live`.
- Received image → clipboard as image data (`wl-copy --type`, PNG/GIF as is, else convert to PNG with
  EXIF orientation via the `image` crate instead of magick) + `omarchy-notification-send -u normal
  --image file://… "Pixel 8 sent a file" <name> --exec xdg-open <path>`. Same for chat messages
  (toast opens the chat UI). Files land in `~/Downloads`. (Logic ported from
  `~/.local/bin/kdeconnect-share-notify`.)
- MPRIS server via zbus (`org.mpris.MediaPlayer2.jarvis.pixel`, Player + Volume + Seek). Only
  present while the phone has an active session.
- CLI: `jarvis-link pair|unpair|status|send <files…>|msg <text>|ping <text>|ring|notifications --json|json|watch`
  (`json` = chat snapshot, `watch` = event stream for the shell UI).
- Optional: add "Send to phone (Jarvis)" to the Omarchy share menu
  (`~/.config/omarchy/extensions/omarchy-menu.jsonc`), which covers the other idea 3c52ab0d.

## Phases
1. **Core + daemon on the laptop.** Two `jarvis-link` instances on one machine (different state dirs)
   pair, chat, queue offline, resume files. Unit tests: outbox resend after reconnect, pair rejects
   unknown key, file resume at offset.
2. **Android app.** Pair, chat, Direct Share tile, receive files. Install over adb
   (paired Wireless debugging only, never `adb tcpip`; `-s` explicit; check focus before any `adb input`). Keep the
   release keystore safe (lesson from babah).
3. **Parity with KDE Connect** (done 2026-10-02, live-checked): MPRIS/media, ring, notifications mirror, ping. Switch
   phonemedia `busPrefix`, dictation grep, voxtype ignore list, scheduled-jobs `notify`,
   suspend-guard, rami-login.ts. Run both side by side for a few days.
4. **Laptop chat UI** (done 2026-10-02; user confirmed send, 📎, badge, toast, cancel, Ctrl+V attach, image to clipboard). Plugin `kivan.jarvis` in `~/Projects/jarvis/shell/`, installed with an
   `install.sh` symlink into `~/.config/omarchy/plugins/` like capture's Ideas panel. It has two parts:
   - a `bar-widget`: the chat badge with the unread count, which opens the dropdown;
   - a `panel` (`keepLoaded`): the same `Chat.qml` centered, opened by SUPER+M through IPC target
     `jarvis-link` (`omarchy-shell jarvis toggle|open`).

   Data comes from a long-running `jarvis-link watch` Process (JSON lines) plus `jarvis-link json` for the
   snapshot; sending goes through `jarvis-link msg` / `jarvis-link send`, with a file picker for 📎 and Ctrl+V
   for images.

   Before writing any QML, check how `kivan.ideas/Ideas.qml` and the `kivan.phonemedia` popup are
   built and copy their PanelWindow and popup patterns. Size the dropdown at about 330×400 and the
   panel at about 440×470 to fit 1024×576. Add the SUPER+M bind in `~/.config/hypr/bindings.lua`
   (first check that it's free) and validate with `hyprctl reload` + `hyprctl configerrors`.

   After QML edits, run `omarchy restart shell`. Toasts for chat messages run
   `omarchy-shell jarvis open` when clicked.
5. **Cut over** (done 2026-10-02: KDE Connect removed from the laptop and the phone; idea ticked off). One sudo step: deploy nftables (drop 1714-1764, add Jarvis UDP port for LAN),
   reinstall suspend-guard, `pacman -Rns kdeconnect`. Remove kdeconnect-share-notify unit and script,
   drop `kdeconnect` from `packages-repo.txt`, uninstall KDE Connect on the phone. Read
   `~/.config/system-notes.md` first and append an entry (firewall + package change). Update memories
   (kdeconnect-share-notify, phonemedia, voxtype) and run `laptop save` for files under
   `~/.config/laptop/paths`. Tick the idea off.

## Verification
- `cargo test` in the workspace; `./gradlew :app:testDebugUnitTest`.
- Share a screenshot from the phone via the "Laptop" tile → toast appears under DND, `wl-paste -l`
  shows `image/png`, pasting in an app pastes the image.
- Phone idle with the screen off for 10 min, then `jarvis-link msg hi` → it arrives within a few seconds
  (FCM wake). `adb shell dumpsys deviceidle force-idle` (Doze) gives the same result.
- After a day, Jarvis's battery use in Settings is small; on mobile data there are no wakeups
  apart from real deliveries.
- Airplane-mode the phone, send 3 laptop messages + a file → they show as queued. Turn the phone back
  on (mobile data, not Wi-Fi) → they arrive via relay, laptop shows ✓✓.
- `omarchy-shell phonemedia status` shows phone track; play/pause/seek/volume from the bar work.
- `jarvis-link ping test` from a scheduled job arrives; `jarvis-link ring` rings; rami-login gets the SMS code.
- After cut-over: `ss -ulpn` shows only Jarvis, nft rules listed, nothing references kdeconnect
  (`grep -r kdeconnect ~/.config ~/.local/bin ~/.claude/skills`).

## App channels (2026-10-02; branch `teen-channel`)
Apps built on Jarvis get their own channel instead of the chat. The owner wants this general
("like IRC, but better"), with teen-app's חפיפה as the first user. Decided with the owner on
2026-10-02 (design: https://claude.ai/artifact/D4342Ja8c9GSVi3XvkXuZw): declarative blocks drawn
natively on the phone and in the panel; laptop input through the panel's channel mode (SUPER+N);
a channel list as the phone's root screen; a TOML manifest per channel; generic first.

**Built so far (Rust side, 2026-10-02):** everything under "Wire and store", "Manifest" and
"Daemon and CLI" below except the panel and the phone. Core: `Body::App{channel, data, replace}`,
kinds `app`/`view` (both out of the chat and the unread count), view replace on enqueue and receive,
`Event::App{…, view}`, `app_view`, `app_history`, `app_local`, `app_channels`, the 30-day prune.
Daemon: manifests (`apps.rs`, `toml` crate; a bad file is logged and skipped), `exec` through
`sh -c` with `~` expanded (30 s relaunch gap; at start, for channels with items waiting),
`_channels` published at start and on reload when it differs from the stored one, socket
`app_action` (fills `from:"laptop"`, `uid`, `ts` when missing), `channels`, `channels_reload`,
`thread`, and the watch notice. Socket sends refuse `_`-names (the daemon's own). FCM: a queued view
wakes only with a non-null top-level `notify`. CLI: `post`, `channels`, `view`, `action`, `thread`.
ffi: `Event::App.view`, `app_view`, `app_history` (`AppHistoryItem`); `send_app` now dials too.
Manifest defaults: `title` = name, `glyph` = its first letter, `dir` auto, `kind` app with `exec`
else thread, `share` false, `notify` true; channels list order is by name.

### Model
A channel is a room with one live **view** (state) plus a timeline of **items**, as Matrix keeps
room state apart from the timeline. The laptop app owns the data and publishes its view; the phone
and the panel draw it and send back **actions**, which are queued items, so offline taps merge
without a CRDT. A channel with no app is a plain **thread** (ntfy-style `jarvis-link post`).

### Wire and store
- `Body::App { channel, data, replace: bool }`. `replace = true` is a view: stored with kind
  `view` (else `app`). Storing a view, on either side, deletes the older views of that channel from
  the same sender, whatever their state (only the newest matters; also bounds the DB).
- `Event::App { id, channel, data, from_me, view }`.
- `app_pending` lists only kind `app` (actions, posts, replies), never views.
- `Node::app_view(channel)`: the newest view on the channel (mine on the laptop, the peer's on the
  phone; in practice: the newest view row of that channel).
- `Node::app_history(channel, limit)`: kind `app` items both ways, oldest first (threads).
- `Node::app_local(channel, data)`: an item from a local UI (the panel), stored as if received
  (from_me false, kind `app`) and emitted, so the subscribed app (or a start on demand) takes it.
- Taken/delivered `app` items older than 30 days are pruned when the store opens.
- `Body::ChannelFile { channel, name, size, sha256 }` (2026-10-05, for files sent to dibs): a file for a
  channel, stored as kind `file` with its `channel`, so it travels like a chat file (resume, sha256, Ack,
  cancel, progress). The receiver keeps it in `<state>/channels/<channel>/`, out of the chat, its unread
  count, Downloads and the laptop's toast and clipboard. `Node::app_files(channel)` lists them; the
  daemon's `thread` merges them in as `{"id","from_me","ts_ms","file":{"name","size","state","path"?}}`,
  and `watch` sends the channel's notice for them. ffi: `send_channel_file`, `ChatMessage.channel`.
  The laptop sends one with `jarvis-link send --channel <ch> <file>…` (socket `send_channel_file`; dibs hands the
  phone transcripts and reports this way).

### Data (JSON in `data`; Jarvis reads only what it draws)
- View (app → phone/panel, `replace`): `{"v":1, "blocks":[…], "badge":N?, "notify":{"title","text"}?, "open_tags":[…]?}`.
  `notify` is shown as a notification when that view arrives on the phone (if the channel's
  manifest allows it); `badge` is the channel's count in the list and the panel strip; `badge: 0`
  clears all of the channel's notifications on the phone. `open_tags` (2026-10-04) lists the tags
  of the posts still open: when the view arrives, the phone cancels the channel's tagged
  notifications not in it, so a question answered anywhere (the laptop too) leaves the lock screen.
  An app keeps a post's tag in `open_tags` while it is open. Without `open_tags` nothing changes.
  A queued view wakes the phone (FCM) only when it has `notify`; otherwise it waits for the next
  connection, since apps republish their view on every small change.
  `pin` (2026-10-05, app 0.3.9): `{"title", "text"?, "tag"?, "actions":[{id,label}]?}`, a status
  the phone keeps as an ongoing, silent notification while the view carries it, and removes when a
  view comes without it (dibs: "dibs has your phone · Take it back" while the user lends it the
  phone). Its buttons send actions like a card's. Older apps ignore it.
- Action (phone/panel → app, queued): `{"action":"<id>", "from":"phone"|"laptop", "uid":"<uuid>",
  "ts":<sender's clock, ms since epoch>, "value":…?, "fields":{…}?}`. `uid` is made by the sender; an app that turns it into a list item
  uses it as the item id, so the sender can drop its pending echo.
- Live (`Frame::App`): `{"patch":{"<block id>":{…fields to replace}}}` (progress text, say).
- Thread post (laptop → phone, queued): `{"post":{"text":"…","title":"…"?,"tag":"…"?,"actions":[{"id","label"}]?}}`;
  a reply is `{"text":"…","from":…}` or an action. On the phone `title` is the notification's
  title (else the channel's; the thread shows it too). Without `tag` a channel has one
  notification, replaced by its next post; a post with a `tag` gets its own (Android tag
  `app:<channel>:<tag>`), so one question doesn't take another's buttons, and a later post with
  the same tag replaces just that one. Channel notifications alert once: a replacement is silent.
  A button tap cancels only its own notification. Every post wakes the phone (FCM), as before.
- Channel `_channels` (daemon → phone, a view): `{"v":1,"channels":[{name,title,glyph,icon,accent,on_accent,tile,dir,kind,share,notify}]}`.
  `icon` is `{view:[x,y,w,h],paths:[{d,fill,stroke}]}` (SVG path data, stroke width in view units, round
  caps and joins; `mark.rs`), `accent`/`on_accent`/`tile` are `#RRGGBB` (the last two only with `hue`).

### Blocks (`{"type":…, "id":…?}` plus the fields below; unknown types are skipped)
- `header {title, subtitle?}`
- `notice {text, tone: info|ok|warn|error}`
- `text {text, mono?}`: multi-line, selectable (a report, say).
- `list {items:[{id, title?, text, meta?, chips?:[str], actions?:[{id,label,confirm?}], details?, reply?, dismiss?}], empty?}`:
  an item action sends `{"action":<action id>,"value":{"item":<item id>}}`. `details` (2026-10-04) is
  long text behind a small "Details ▾" toggle under the item's text (selectable, line breaks kept;
  open or shut survives view reloads). `reply` (2026-10-04) `{id, placeholder?: "Answer…", submit?: "Send"}`
  is a one-line box under the item for a free-text answer: it sends
  `{"action":<reply id>,"uid","value":{"item":<item id>,"text"}}`, and the text shows as pending (⏳)
  under the item until a view lists an item with that uid or no longer lists the item.
  `dismiss` (2026-10-05) `{id}` lets the user remove the item: swiped away either way on the phone, a
  small ✕ in the panel. It sends `{"action":<dismiss id>,"value":{"item":<item id>}}` like an item
  action, and the item hides at once, until a view no longer lists it (the app drops it).
- `thread {items:[{id, who, text, ts, actions?, reply?, dismiss?}], status?, empty?}` (2026-10-05, phone app
  0.3.8; dibs's conversation): a chat. `who` is `"user"` (the user's lines, drawn on the right) or the app's
  side (any other name, e.g. `"dibs"`, on the left); `ts` is unix seconds (day headers, times, grouping);
  items are oldest first and the newest 50 are drawn. `actions`, `reply` and `dismiss` are a list item's
  and send the same. `status` is a small line under the newest bubble ("dibs is on it"); `empty` shows
  with no items. A `compose` after the thread is its box: its sends show as pending bubbles at the end
  until an item has the action's uid as its id. On the phone a view with a thread is laid out
  chat-first: the blocks before it in a top area that folds to "N waiting", the thread filling the
  screen from its newest line (following new ones), the compose pinned at the bottom. Only phone
  app 0.3.8+ draws it: an app checks `phone_app` in `jarvis-link status --json` (the version the phone
  tells on every link, null until it has) and sends a `list` to an older one.
- `checklist {items:[{id,label,checked,actions?:[{id,label,confirm?}]}]}`: a tap sends
  `{"action":<block id>,"value":{"item","checked"}}`; an item action (like a list item's) sends
  `{"action":<action id>,"value":{"item":<item id>}}`.
- `compose {id, placeholder, submit, chips?:[{id,label}], multi?}`: sends
  `{"action":<id>,"uid","value":{"text","chips":[ids]}}`. Until a view lists an item with that
  uid, the renderer shows the text as a pending (⏳) item at the end of the list before it.
- `form {id, fields:[{id,label,multi?,placeholder?,value?}], submit}`: sends `{"action":<id>,"fields":{…}}`.
- `progress {id, text, cancel?:{id,label}}`: indeterminate; live patches change `text`.
- `buttons {items:[{id,label,style?:primary|danger|plain,confirm?}]}`: sends `{"action":<id>}`.
  Item actions (list, thread, checklist) take the same `style` (phone app 0.3.8+; plain before).
- `web`: reserved (a webxdc-style bundle, later).

### Manifest `~/.config/jarvis/apps/<name>.toml` (name: `[a-z0-9_-]+`)
Make one with `jarvis-link channel add <name> [--kind … --icon … --hue … --show …]` (it validates, writes the file and
reloads the daemon); `jarvis-link channel set <name> key=value…` edits one and keeps its comments;
`jarvis-link channel rm <name> [--purge]` deletes it; `--purge` also deletes its thread on both sides (a queued `DropChannel` item) and works for a thread with no manifest. Hand-written files still work.
Keys: `title`, `icon` (a Lucide name, or `--icon ./own.svg` on a 24 grid; `jarvis-link channel` copies it to
`apps/icons/<name>.svg`, which the daemon reads before the Lucide set in `~/Projects/design`), `hue`
(OKLCH degrees: the channel's accent and icon tile, `docs/DESIGN.md`), `glyph` (one emoji or letter,
shown when there is no icon), `accent` (`#rrggbb`, the old way; `hue` wins), `dir` (`auto`|`ltr`|`rtl`, default
`auto`: each text takes its own direction, Hebrew right-to-left and English left-to-right, per item on
the phone and the panel; `ltr`/`rtl` fix the whole channel), `kind` (`app`|`thread`|`list`, see below),
`exec` (a shell command; `~` expanded; started on demand), `share` (accept Android share text into the
compose), `notify` (bool), `show` (`both` default | `phone` | `laptop` | `none`: where the channel appears.
Only phone channels are in `_channels`; `none` is for channels only an app uses, still fully usable over
the socket; the old `laptop = false` means `show = "phone"`, `show` wins when both are set),
`keep_done` (list only: hours a ticked item stays, default 24). The daemon reads the folder at start and on
`jarvis-link channels --reload`, and publishes `_channels` when the list changed. A channel with items but no
manifest shows as a thread.

### Built-in kinds (`kind`)
A kind is a channel whose behaviour the daemon provides, so no app has to run: it applies the
phone's and the panel's actions itself, republishes the view, and has its own CLI group. `list` is the
first (`crates/daemon/src/lists.rs`); later kinds follow the same pattern (a state module, a socket
request, a CLI group, an arm in `apps::run`).
- **`list`**: a shopping or to-do list for any channel name. State is
  `~/.local/state/jarvis/lists/<channel>.json` (items `{id,text,done,added_ms,done_ms?,by}`); the view is
  derived from it: a compose (`add`, multi-line adds several), a checklist (`items`; pending first; each item
  has a `✕` action `rm` with confirm) and "Clear done" (`clear_done`); `badge` = pending count; no `notify`,
  so a change doesn't wake the phone. A phone's add uses its uid as the item id (idempotent on replay, and the
  ⏳ echo clears); extra lines get `1~<uid>`, `2~<uid>`. Done items older than `keep_done` hours are dropped
  (at start, on every change and hourly).
  CLI: `jarvis-link list <ch> add <item>… | ls [--pending] [--json] | done|undo|rm <id|prefix>… | clear [--all]`
  (each argument and each line of an add is one item; `ls` prints unique id prefixes; `--json` gives
  `[{id,text,done,added_ms,done_ms?,by}]` for apps, which read the pending items and tick them with `done`).
  Socket: `list{channel,op}`.

### Daemon and CLI
- Socket: `app_send` (replace = view), `app_subscribe`, `app_action{channel,data}` (local route),
  `channels` (manifests + newest view + badge each), `channels_reload`, `thread{channel,limit}`.
  `watch` also streams `{"type":"app","channel","view":bool}` change notices (no data) for views and
  thread items, so the panel can re-read.
- CLI: `jarvis-link post <ch> <text…> [--title T] [--tag K] [--action id:label]… [--loud]`, `jarvis-link channels [--json] [--reload]`,
  `jarvis-link view <ch> [<file>|-]` (publish a view), `jarvis-link action <ch> <json>`, `jarvis-link thread <ch> [--json]`,
  `jarvis-link channel add|set|rm|ls`, `jarvis-link list <ch> …` (built-in `list` kind, above).
- Panel (QML): **built 2026-10-02 and live** (checked end to end with teen-app, below).
  `Channel.qml` draws the blocks (RTL by mirroring; drafts kept by block/field across view
  reloads; ⏳ echo until a view lists the uid; confirm = second press within 4 s); `Panel.qml`
  has the strip (Chat + channels with badges), Ctrl+1…9, Ctrl+K switcher, reload on watch `app`
  notices, and IPC `channel <name>` / `toggleChannel <name>` (`open` keeps no argument, since chat
  toasts call it bare and `qs ipc` rejects a wrong arg count). SUPER+N → `omarchy-shell jarvis
  toggleChannel teen` (bound 2026-10-02; removed 2026-10-03: the owner uses teen-app's own window on
  the laptop, so `teen.toml` has `laptop = false` and חפיפה stays on the phone only). `JARVIS_PANEL_OUTPUT=<output>` is the test mode: that
  output, keyboard focus None (OnDemand took the owner's keystrokes once on a headless output).
  Checked in an isolated `qs -p` with a fake CLI: rendering of every block, RTL, the action JSON;
  then live with teen-app (typing, Enter, Esc). Not checked: Exclusive focus, dropdown with the strip.
  Live progress patches don't reach the panel (watch drops id-less `Event::App`). 
- Phone: **built and installed 2026-10-02** (merged to master; daemon reinstalled the same day; the
  phone connected and took `_channels`, no crash; the screen itself not yet seen). `Channels.kt` holds the `_channels` list, each view (live `patch` applied until the next
  view), threads, ⏳ echoes, drafts (kept across reloads) and the open screen (the last one reopens;
  `jarvis://chat`, `jarvis://channel/<name>` intents). Root screen `ui/ChannelScreen.kt` `ChannelList`:
  Chat first, then channels with badges; long press → pin. `ChannelScreen` draws the blocks in the
  channel's direction (Compose `LayoutDirection`), confirm = second press within 4 s. Actions go out
  with `Core.acquire` as queued items (`from:"phone"`, uid, ts). Notifications: channel `app.<name>`
  (group "Channels") per notifying channel; a view's `notify` and untagged thread posts post one per
  channel, tagged posts one per tag, unless the channel is on screen (opening it clears them all). Shortcuts `ch.<name>`: dynamic (launcher menu), pinnable, Direct Share
  (category SHARE_TEXT, text/plain) for `share` channels; shared text lands in the first compose.
  Not checked on a device: everything visual, shortcuts, share, notifications.
- **Checked end to end with teen-app (2026-10-02 evening, made-up data):** phone (adb) RTL layout,
  ⏳ echo, confirm on second press, text share into compose, live updates, long-press pin dialog,
  the "report ready" notification; panel opens RTL, typing + Enter adds, Esc closes. Fixed then:
  `ChannelScreen.kt` `EntryRow` dropped `TextAlign.End` on the rtl subtitle (it sat right-aligned
  away from the title); `Channel.qml` keeps the focused box in view (`focusedBox` + `reveal(item)`
  on focus and on `body` height change), since with many notes the compose box was below the
  visible area. A stale view: with no app instance running, `jarvis-link channels` showed the last
  published view, not the app's current state.

## Phone presence for dibs (`_presence`, 2026-10-06; app 0.5.6)
dibs's presence knows the user is at the laptop; this tells it when they're on the phone instead, at
home or away. The phone sends live `App` frames (never queued, dropped when the laptop can't be reached)
on the reserved channel `_presence`:
- `{"op":"present","why":"unlock","ts":<phone epoch ms>}` on ACTION_USER_PRESENT, or on SCREEN_ON while the
  keyguard isn't locked (no lock screen, Smart Lock); at most one a minute.
- `{"op":"present","why":"use","ts":…}` every 5 min while the screen stays on and unlocked (checked at each tick).
- `{"op":"off","ts":…}` on SCREEN_OFF, only over a link that is already up.

**Battery:** `PhoneListener`, which the system keeps bound for notification access, registers the receiver,
so there's no service of ours. Each `present` holds the node (`presence:<n>`), dials, sends and lets go, so
it costs at most one dial per unlock a minute and one per 5 min of screen-on use (the link then closes after
the usual 60 s idle); `off` never dials. Nothing is sent while the pairing is refused or unpaired.

**Laptop:** `presence.rs` keeps the newest frame (by the phone's ts) in memory; malformed data and unknown
ops are ignored. `jarvis-link watch` streams one line per frame,
`{"type":"presence","op":"present"|"off","why":"unlock"|"use"|null,"ts_ms":<phone ts>,"got_ms":<laptop ms>}`,
and `jarvis-link status --json` has `"phone_presence"`: the newest one without `type`, or null if none came since
the daemon started. Like `_adb`, `_presence` is refused for `app_send`/`app_action` (any `_…` name) and for
`app_subscribe`, and never listed as a channel.

## Jarvis's role next to dibs and Capture (decided 2026-10-04)
Research write-up: https://claude.ai/artifact/KEF8NckdLpSGJagxA14wWC. The plan is recorded in full in dibs's `docs/PLAN.md`.

**The user decided to keep three repos and give each one job.** Jarvis is where the user sees and answers things: chat, files and channels on the phone and in the laptop panel. dibs decides. Capture holds the notes. A dibs+Jarvis Cargo workspace is reconsidered only after stage 3 below, and only if branches that must change both repos stay common.

**What this means for Jarvis:**
- **The `dibs` channel is phone-only.**
  - dibs creates the channel itself with `show = "phone"`.
  - On the laptop, dibs questions stay in dibs's own widget.
  - The panel no longer draws a second copy.
- **The Laptop chat is for the user.**
  - It carries the user's messages, files, reminders and suspend-guard.
  - Agents don't `jarvis-link ping` or `jarvis-link msg` the user about their work; they go through dibs.
  - The source of the duplicate pings the user saw is still unconfirmed. In the repos and laptop config, `jarvis-link ping` is run only by the reminder `notify` script and suspend-guard.
- **Stage 1 notifications (built 2026-10-04):**
  - `Notifier.app` alerts once per change.
  - One dibs question need not remove another's lock-screen buttons: a post with `--tag` gets its own notification, and a view's `open_tags` clears the answered ones. dibs uses both on its side.
  - Loud posts (2026-10-09, dibs app 0.12.0): `jarvis-link post --loud` sets `"loud": true`; the phone puts it on its own channel "Urgent from dibs" (alarm sound once, long vibration) and shows it even while the channel is on screen. See `docs/DIBS-APP.md` "Urgent notifications".
  - Short cards, also for dibs: a list item's `details` (behind a toggle) and `reply` (a free-text answer box); a post's `--title`.
- **Stage 2:** `jarvis-link view --check` validates a view against the blocks `ChannelScreen.kt` and `Channel.qml` draw. Fixture views, dibs's included, are tested here.
- **Stage 3:**
  - A committed `dibs.toml` and CI, so cloud branches arrive as update questions.
  - The badge and the panel share one `jarvis-link watch` instead of running two.
