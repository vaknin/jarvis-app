# dibs on the phone: its own app inside the Tether APK (task #22, 2026-10-05)

The user chose (question #249, 2026-10-05): dibs gets a real app with sections, **inside the Tether
APK**, not a second APK. Background and the options weighed: `~/Projects/dibs/docs/RESEARCH-phone-app.md`
("(b+)"). Tether stays the link; dibs gets its own launcher icon, its own Recents card and its own
screens in their own Gradle module, fed by a structured payload on the `dibs` channel's view.

## What the user gets

- A **dibs** icon on the home screen, next to Tether's. It opens straight into dibs, which has its own
  card in Recents. Tether's icon stays the Laptop chat, files and the other channels; the Dibs entry in
  Tether's channel list and every dibs notification open the dibs app too.
- Four tabs at the bottom, each with a badge:
  1. **Chat** (home): the conversation with dibs, newest at the bottom, the box pinned. Photos and files
     go with a message (📎: photo picker, camera, any file; or Share → dibs from another app). A question
     asked in the chat carries its buttons on its line.
  2. **Waiting**: every open question as a full card (line, why, Details, named buttons, an answer box),
     phone requests, and "decided for you" lines (Got it, Undo).
  3. **Work**: tasks (state, repo, what it's doing now, how long), the live sessions (busy or idle, what
     they hold), ships in progress. Tap a task for its recent lines; Tell it something; Stop it.
  4. **Recap**: "while you were away" and the day's feed (did lines, updates installed, closed sessions).
- Above the tabs, two lend toggles, **Phone** and **Laptop** (task #29, the user's word 48): each says "Yours" or
  "lent to dibs" with dibs's line (until when, who is on it), and one tap lends it or takes it back. A dibs
  without `lends` gets the older bar, **"dibs has your phone · Take it back"** (task #19).

## Chat: modern, tidy, never overwhelming (the user, 2026-10-05, words 31 and 32)

- **No swipe-to-delete.** Lines stay put. Long-press a line: Copy, Hide (the old swipe, tucked away).
- **Short by default, full on a tap.** A long line shows its first lines with a soft fade and "More";
  a tap opens it in place. dibs may send a `short` with the full `text` (its confirmations: "Started task
  X, as you said." with the quote behind the tap). dibs no longer cuts the user's quote out of the line
  (`dibs act`); the full text always reaches the phone.
- **dibs's own notes aren't bubbles.** Confirmations made by dibs's code ("Started task …", "Answered …,
  as you said …") are small centred lines in muted text, like a chat app's system lines. Bubbles are the
  conversation itself.
- **Finished cards fold.** A question asked in the chat shows its buttons until it's answered; then the
  card shrinks to one muted line: "✓ Inside Tether · 17:58".
- **Old days fold.** Today (and anything newer than the last six hours) is drawn in full; earlier days
  fold behind "Earlier · Mon 4 Oct · 23 lines", which opens on a tap. Grouped runs, a day header as an
  eyebrow, one time per run.
- "dibs is on it" is three calm dots under the newest line while dibs works. Scrolled up, a small
  "↓ 2 new" pill brings you back.

## Look (the user's call; asked with a mockup page before the screens are built)

- The shared design language (`~/Projects/design`): dark only, Rubik + Geist Mono, Lucide icons, one big
  tabular number with an eyebrow per screen (Waiting: how many wait; Work: how many run), 16 dp gutter,
  radii 8/14/24. Generated, never hand-edited (`docs/DESIGN.md`).
- **dibs's hue.** The channel uses 300 (violet) today. Checked with a protanopia simulation (the user is
  protan): violet's tile and accent are almost the same colour as Tether's blue (ΔE 3 against 8 in
  normal vision), so the two icons would look alike. Candidates that stay apart: 150 (green: ΔE 13 tile,
  17 accent under protan) and 170 (teal: 10 and 12). 60 to 120 sit on the warning amber, 0 to 50 near
  danger red. The mockup page shows them side by side, normal and protan.
- The mark: handshake (today's channel icon), hand (raised: "dibs!"), or another Lucide mark.
- **Chosen (the user, 2026-10-05):** teal 180 and a deadpan face drawn for dibs (`android/dibs/dibs-mark.svg`),
  from five marks based on dibs's personality.

## How it's built

### Tether's phone app (`android/`)
- **`:dibs` Gradle module** (`android/dibs`, Android library, package `com.kivan.tether.dibs`): the
  screens, `DibsActivity`, the model parsed from the payload, and its own generated theme (dibs's hue),
  icons and launcher. It knows nothing of Tether's core; it talks through one narrow interface,
  `DibsHost` (the payload as a flow, the link state, `act`, `say` with files, a UI hold, thumbnails).
  `:app` implements it (`DibsBridge.kt`) over `Channels` and `Core`. Splitting into a second APK later
  is then a packaging change.
- **`DibsActivity`**: its own `MAIN/LAUNCHER` entry (label "dibs", dibs's icon), `taskAffinity`
  `com.kivan.tether.dibs` (its own Recents card), `singleTask`. On start it takes the `ui` hold like
  `MainActivity`, so the link stays up while it's on screen and the notifications clear.
- Generated resources in the module are renamed `ic_dibs_*` (launcher, monochrome, notification), since
  the app's same-named ones would win the resource merge.
- Tether's side: the Dibs entry in the channel list, `tether://channel/dibs` intents, dibs's shortcut
  and its notifications open `DibsActivity` when the view carries the payload (else today's screen).
- Version **0.4.0** (versionCode 14; task #19 takes 0.3.9). Never uninstall: `adb install -r`.

### The payload (dibs → phone, in the `dibs` channel's view)
The view stays a v1 view (`badge`, `open_tags`, `pin`, `notify` keep working), plus a top-level `dibs`
object. Tether's daemon and `view --check` ignore it; the module reads it. For an app ≥ 0.4.0 dibs sends
no blocks except the lend card (old screens aren't shown any more); older apps keep today's blocks.

```json
"dibs": {
  "v": 1, "now": 1791213484,
  "state": {"brain": "running|idle|off", "busy": true, "line": "dibs is on it", "usage": "…"?},
  "lend": {"until": 1791215000, "holder": "rami-0f", "waiting": 2, "text": "Until 15:40 · …"}?,
  "lends": {"phone": {"lent": false, "text": "", "until": null, "action": "phone-lend"},
            "laptop": {"lent": true, "text": "Until you take it back · rami-0f is on it", "until": 1791240000, "action": "laptop-back"}}?,
  "talk": [{"id": "64", "who": "user|dibs", "text": "full text", "short": "…"?, "ts": 1791212701,
            "note": true?, "ask": {"q": 249, "actions": [{"id","label","style"}], "reply": true,
                                   "outcome": "Inside Tether"?}?,
            "files": [{"id": "<tether file id>", "name": "a.jpg", "size": 123, "image": true}]?}],
  "questions": [{"id": 249, "title": "…", "why": "…", "details": "…"?, "from": "dibs", "repo": "tether"?,
                 "ts": …, "blocking": false, "phone": {"secs": 1800, "unlock": true}?,
                 "actions": [{"id": "y249", "label": "Yes", "style": "primary"}], "reply": "r249"?}],
  "decided": [{"id": 250, "text": "…", "why": "…", "from": "…", "ts": …}],
  "tasks": [{"id": 16, "name": "…", "state": "running", "repo": "dibs", "minutes": 46,
             "text": "…", "doing": "last line", "background": false}],
  "sessions": [{"name": "…", "repo": "dibs", "branch": "brain-3a", "status": "busy|idle|shell",
                "task": 16?, "holds": ["repo:dibs:master"]}],
  "peek": {"who": "…", "at": …, "lines": ["…"]}?,
  "recap": {"away": {"id": 4, "title": "…", "lines": ["…"], "seen": false}?,
            "feed": [{"ts": …, "kind": "did|update|closed|decided|answered", "text": "…", "who": "…"?}]},
  "badges": {"waiting": 3, "work": 1, "recap": 1}
}
```

### Actions (phone → dibs, queued as today, `{"action", "value"?, "uid", "ts"}`)
Today's ids stay: `a|d|y|n|x|k<id>`, `r<id>` (typed answer), `w<id>` (away "Got it"), `h<talk id>`
(hide), `say` (`value.text`). New: `say` with `value.files` (ids of files sent to the channel, below),
`peek` (`value.who`), `tell` (`value.task`, `value.text`), `stop` (`value.task`), `undo` (`value.item`:
handed to the brain as the user's request), `phone-back` (task #19), `phone-lend`, `laptop-lend`, `laptop-back` (task #29: a toggle sends its
`action` as is; a tap seen more than 10 min late lends nothing, and taps made while an agent works the phone's
screen lend nothing either).
`ack-decided` (`value.items`: the decision ids the Waiting tab showed; "Got it to all", questions
among them stay open). `badges.waiting` counts questions only.

### Files to dibs (Tether's core and daemon)
- New body `Body::ChannelFile { channel, name, size, sha256 }`, appended to the enum (an older peer
  skips the frame and the sender keeps it queued; the ship installs the daemon before the phone). It's
  a file in every other way: stored as kind `file` with its `channel` set, so resume, sha256, Ack,
  cancel, progress and `TransferService` all work unchanged.
- The receiver puts a channel file in `<state>/channels/<channel>/` (not Downloads), with no toast,
  no clipboard, out of the Laptop chat and its unread count. `tether thread <ch> --json` lists it as
  `{"id","from_me","ts_ms","file":{"name","size","state","path"?}}`, and `tether watch` sends the
  channel's notice when one completes.
- The phone sends each file with `send_channel_file`, then one `say` action naming their ids with the
  caption. dibs applies it once every named file has arrived (it waits otherwise, and a later notice
  retries), keeps the paths with the talk line and hands the brain the caption plus the paths, so it can
  read the images. The phone draws its own thumbnails (`Thumbs`, by file id).

### dibs (`~/Projects/dibs`, own branch)
- `tether.rs`: the payload (`dibs_json`), gated on `phone_app` ≥ 0.4.0; the new actions; files on `say`.
- `talk.rs`: `files`, `short` and `note` columns; `dibs act`'s lines keep the full quote (`short`
  without it) and are notes. Coordinate with the brain sessions working in `talk.rs`/`words.rs`.

## Order of work
1. This plan; mockups page and the look question to the user (escalate, with the page's link).
2. Rust: `ChannelFile` in core + ffi + daemon, tests over loopback.
3. dibs: payload, actions, files (branch in `~/Projects/dibs`), tests.
4. Android: the `:dibs` module skeleton, `DibsHost`, `DibsActivity`, wiring; the screens once the
   user has picked the look; unit tests for the payload parser and chat folding.
5. Independent review; on the Pixel with screenshots when the user is home (`dibs phone request`);
   `dibs ship` in Tether (daemon first, then the app) and in dibs.

## Later (not in this task)
- dibs's replies as a MessagingStyle conversation notification (Person "dibs").
- The Quick Settings lend tile, a home-screen widget, the voice button, files from dibs to the phone.
