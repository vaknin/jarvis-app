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
     asked in the chat carries its buttons on its line, and every question waiting on the user shows
     there too (task #66, 2026-10-06: dibs adds a quiet line for it, no notification of its own; the
     same question as its Waiting card, so answering either closes both). The chat is where the user wants
     questions (word 217): a question's notification opens the chat, not Waiting.
  2. **Waiting**: only what needs the user: every open question as a full card (line, why, Details,
     named buttons, a box for words), phone requests. "Decided for you" moved to Recap (word 127).
  3. **Work**: tasks (state, repo, what it's doing now, how long), the live sessions (busy or idle, what
     they hold), ships in progress. Tap a task for its recent lines; Stop it. (No "Tell it": the user talks only to dibs, word 221.)
  4. **Recap**: "while you were away" and the day's feed. **One entry per finished piece of work** (the user,
     2026-10-05, task #31: a finished task showed three or four near-identical lines, some technical, cut with
     "…" and nothing behind a tap): a short plain line of what changed for them, "You asked: …" (why), whose
     it was; a tap opens it whole (the task's report, its other lines, then all of what they asked, folded
     again since it was written for the agent), a long press copies it. dibs folds the lines (`src/recap.rs`): a task's `dibs did`s, shipped "For you" lines, its
     report and dibs's closing line are one entry, as are a handoff chain's sessions. Below it, **Decided for
     you** (word 127), each readable at a glance (word 155): the project, what was decided as one full plain
     sentence, "Why: …", and Undo. dibs's writer (a cheap Claude call in `dibs watch`) writes these from the
     agent's text; until it has, the agent's words show, marked so. The agent's whole text folds behind
     "Agent's words". Bookkeeping ("Started task X") is left out.
     **Since 0.10.0 (the morning recap, below), a dibs that sends `recap.items` gets an unread list instead:**
     "Unread N" with "M need you · K small fixes folded", one row per brief, "The rest" (small fixes folded,
     Decided for you). This older "while you were away" and feed tab stays for a dibs that sends no `items`.
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
- **The list never moves on its own (app 0.16.0).** `pinned` (`ChatPin`, `Dibs.chatPin`) is a remembered flag, true at
  start, changed only when the user scrolls (at the bottom within 8 dp: pinned; anywhere else: not) or taps the pill,
  never by sending or by something arriving. Pinned, the newest end is asked for after every composition
  (`requestScrollToItem(0)`), so the newest stays in sight through new lines, the typing dots, the status line, the
  Wait chip, the keyboard and a growing box. Not pinned, nothing moves the view, not even sending; the pill (tag
  `new-pill`) says `N new` (rows after the last one seen at the bottom, my own echo too) or `Latest` and a tap goes
  down and pins again. The same rule holds on the Ask page and a task's chat page (which opens at the end only when the
  agent replied since, and follows the very end while pinned). The main chat's place survives a restart: a debounced
  save (and on stop) through `DibsHost.note`/`keepNote`, restored once per process to the same row and offset (gone
  or pinned: the bottom).

## Talking into the box (task #377, 2026-10-09: the first step of "talk to dibs by voice")

The user dictates with Gboard and wanted less typing; the research (`~/Tasks/2026-10-09-dibs-tether-research-a-new-way-of/REPORT.md`)
picked a talk button wherever dibs needs words. Every box (the chat, a task's chat, Ask about) has a 🎤 between the
text and Send (`Dictation.kt`, `Talk` in `ui/ChatParts.kt`):
- **Local only** (the user's rule): Android's on-device recognizer (`createOnDeviceSpeechRecognizer`, the engine
  Gboard's voice typing uses). No fallback to a network service: without it the box says "This phone can't recognise
  speech offline".
- Words appear live (partial results) after what's already in the box, a capital where a sentence starts
  (`joinSpoken`), and count as typing (the Wait pings hold dibs's answer). The user reads them and taps Send; nothing
  sends by itself yet.
- One segmented session across pauses until the user taps the square, sends, types (the keyboard wins), leaves the
  screen, or stays quiet 6 s. A recognizer that ignores segmentation gives one phrase; tap 🎤 again.
- **Words to expect** (`EXTRA_BIASING_STRINGS`, `biasWords`): dibs's own ("dibs", "Tether", "worktree", …) and the
  projects in the task index, busiest first, at most 40. Whether the on-device engine honours them is the open test
  (motoparty found it ignored them for "motoparty").
- A message with spoken words carries `"spoken": true` in its `say` / `task-say` value (not `thread-say`). dibs
  ignores it today; it's there so its brain can allow for a misheard word.

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
- **No "…" anywhere (the user's rule, 2026-10-06, app 0.5.9):** no text is cut with an ellipsis and no string of the app's
  own ends in "…"; text wraps to as many lines as it needs (titles, the header's usage, rows, chips, the top bars).
- **A card's addresses open and its codes copy (task #303, 2026-10-09):** a GitHub device login came as a card ("enter
  code 070B-16D2 at github.com/login/device") the user could not use. Every dibs text (`linkified`) makes web addresses
  tappable, bare ones too when they end in a well-known domain (`.com`, `.ai`, `.dev` and a few more; never a file name like
  `server.md`), and sign-in codes ("code 482913", or GitHub's four-and-four `070B-16D2`) mono and tap-to-copy. A question
  card (Waiting, a task's page, its chat bubble) also shows a "Copy <code>" button per code. The rules are `CardText.kt`.

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
- Version **0.4.0** (versionCode 14; task #19 takes 0.3.9); the Recap rework is 0.4.3 (17); Your tasks is
  0.5.0 (19); the lend toggles are 0.5.1 (20; 18 went unused); task #64's fixes are 0.5.4 (23; 0.5.2 and 0.5.3 went to parallel tasks); task #66's questions, plain decisions and talking only to dibs are 0.5.5 (24); phone presence (`_presence`, task #59) is 0.5.6 (25); the brain's state in the header (`doing`/`words`) is 0.5.10 (29); Ideas is 0.6.0 (31); Ask about (task #102) is 0.7.0 (32); root steps (task #145) are 0.8.0 (33); notifications that never cut a long title (task #131) are 0.8.1 (34); one slim bar and the dibs page (task #137) are 0.9.0 (35); the morning recap (unread list, merged story screen) is 0.10.0 (36); the board's Active and Backlog lists with a Start or Park button on each card are 0.10.5 (41); task and idea links (task #261) are 0.11.0 (42). Question cards with tappable addresses and a Copy button for sign-in codes (task #303) are 0.11.1 (43). instant feedback on taps (task #124) is 0.12.1 (45). The Recap's To look over section (task #336) is 0.13.0 (46). The drop box (Ideas v2, task #267) is 0.14.0 (47). dibs's pick on question buttons (task #360) is 0.14.1 (48). Talking into the box (🎤, task #377) is 0.15.0 (49). The chat that never scrolls on its own is 0.16.0 (50). Never uninstall:
  `adb install -r`.

### The payload (dibs → phone, in the `dibs` channel's view)
The view stays a v1 view (`badge`, `open_tags`, `pin`, `notify` keep working), plus a top-level `dibs`
object. Tether's daemon and `view --check` ignore it; the module reads it. For an app ≥ 0.4.0 dibs sends
no blocks except the lend card (old screens aren't shown any more); older apps keep today's blocks.

```json
"dibs": {
  "v": 1, "now": 1791213484,
  "state": {"brain": "running|idle|off", "busy": true, "line": "dibs is on it", "usage": "…"?,
            "doing": "working|idle|out|starting|off"?, "words": "Out of usage until 12:20"?,
            "hold": {"kind": "wait|stop|held", "button": "…", …}?},
  "lend": {"until": 1791215000, "holder": "rami-0f", "waiting": 2, "text": "Until 15:40 · …"}?,
  "lends": {"phone": {"lent": false, "text": "", "until": null, "action": "phone-lend"},
            "laptop": {"lent": true, "text": "Until you take it back · rami-0f is on it", "until": 1791240000, "action": "laptop-back"}}?,
  "talk": [{"id": "64", "who": "user|dibs", "text": "full text", "short": "…"?, "ts": 1791212701,
            "note": true?, "ask": {"q": 249, "actions": [{"id","label","style","pick": true?}], "reply": "r249"?,
                                   "hint": "Add a comment…"?, "outcome": "Inside Tether"?,
                                   "read": {"label": "Read it in full", "task": 42, "url": "https://…" | null}?}?,
            "files": [{"id": "<tether file id>", "name": "a.jpg", "size": 123, "image": true}]?}],
  "questions": [{"id": 249, "title": "…", "why": "…", "details": "…"?, "from": "dibs", "repo": "tether"?,
                 "ts": …, "blocking": false, "phone": {"secs": 1800, "unlock": true}?,
                 "actions": [{"id": "y249", "label": "Yes", "style": "primary", "pick": true}], "reply": "r249"?,
                 "hint": "Answer in your own words…"?,
                 "read": {"label": "Read it in full", "task": 42, "url": "https://…" | null}?}],
  "looks": [{"id": 7, "kind": "plan|design|research|answer|page", "title": "…", "pick": "what dibs picked and why, or found",
             "link": "https://…"?, "task": 210?, "follow": 211?, "follow_state": "queued|running|done|ended"?,
             "ts": …, "read": false}],              // "To look over" in Recap (0.12.0), newest first, at most 30
  "decided": [{"id": 250, "text": "…", "why": "…", "from": "…", "ts": …}],
  "tasks": [{"id": 16, "name": "…", "state": "running", "repo": "dibs", "minutes": 46,
             "text": "…", "doing": "last line", "background": false}],
  "sessions": [{"name": "…", "repo": "dibs", "branch": "brain-3a", "status": "busy|idle|shell",
                "task": 16?, "holds": ["repo:dibs:master"]}],
  "peek": {"who": "…", "at": …, "lines": ["…"]}?,
  "recap": {"away": {"id": 4, "title": "…", "lines": ["…"], "seen": false}?,
            "feed": [{"id": "t25", "ts": …, "kind": "done|stopped|did|closed|update", "who": "…", "text": "what changed, whole",
                      "repo": "…"?, "why": "first sentence of the ask"?, "asked": "the whole ask"?,
                      "more": ["its other lines, whole"]?, "report": "the task's report, whole"?,
                      "reopen": "<claude session id>"?}],
            "decided": [{"id": 250, "text": "…", "why": "…", "from": "…", "ts": …, "undo": true,
                         "project": "Tether", "plain": true, "raw": "the agent's whole text"}]?,
            "items": [<Brief>]?, "unread": 3?, "needs": 1?,          // the morning recap, below (0.10.0)
            "small": {"n": 47, "groups": [{"project": "dibs", "n": 21, "lines": ["…"]}]}?,
            "stories": [{"task": 189, "title": "…", "ts": …, "project": "dibs"?}]?},   // every full story kept, newest first
  "badges": {"waiting": 3, "work": 1, "recap": 1, "looks": 2}   // recap: the unread count with `items`, else 0/1; looks: open ones
}
```

### Actions (phone → dibs, queued as today, `{"action", "value"?, "uid", "ts"}`)
Today's ids stay: `a|d|y|n|x|k<id>`, `r<id>` (typed answer), `w<id>` (away "Got it"), `h<talk id>`
(hide), `say` (`value.text`). New: `say` with `value.files` (ids of files sent to the channel, below),
`peek` (`value.who`), `tell` (`value.task`, `value.text`), `stop` (`value.task`), `reopen` (`value.reopen`: an open
Recap row's "Open on laptop" resumes that saved chat in a tab on the laptop, task #32), `undo` (`value.item`:
handed to the brain as the user's request), `phone-back` (task #19), `phone-lend`, `laptop-lend`, `laptop-back` (task #29: a toggle sends its
`action` as is; a tap seen more than 10 min late lends nothing, and taps made while an agent works the phone's
screen lend nothing either).
`ack-decided` (`value.items`: the decision ids the Waiting tab showed; "Got it to all", questions
among them stay open; unused since 0.5.x, when decisions moved to Recap with Undo only). `badges.waiting` counts questions only.

**Look (task #336, 0.12.0).** `look` `{"id": <look id>, "act": "keep|got|change|reply", "text"?}`: Keep or Got it closes the
look; Change or Reply carries the user's words in `text` (required) and dibs passes them on to the build it started
(or, for a read, to the brain). Sent through `Dibs.answer("l<id>", …)`, so the card is gone here at once.

**dibs's pick (task #360, 0.14.1).** An action carries `"pick": true` when it is the answer dibs recommends; at most
one does, and dibs sends it with `"style": "primary"`, so every other button is plain or outlined. The app fills
only that button and writes "dibs's pick" under it (words, so it never rests on colour alone). No `pick` anywhere
on a card: no button is filled. An older dibs sends none, an older app fills the primary one without the words. A
root step's page fills Approve when its `root.pick` is `accept` and marks Deny when it is `reject`.

**Questions' buttons and words (task #66, 2026-10-06).** Each button says what a tap does (dibs's
docs/PLAN.md, "Question buttons", lists every kind). An `x<id>` button (a close: "Drop this
question", "Never offer it") comes only where it differs from the second one, so a card has two or
three. Every question but the weekly retro's has `reply` and a `hint` (its box's placeholder): the
words sent alone are `r<id>` as before; typed and then a button tapped (not `x`), the tap carries
them as `value.comment`, which dibs hands to its brain (the tap still answers).
`hold` and `go-ahead` (task #69, below). `recap-seen` (0.10.0): `{"task": 223}` when a brief is opened (or swiped onto),
`{"all": true}` for "Mark all read".

**Read it in full (task #264, from #262).** A finished plan or research question carries `read` (on its
chat `ask` and on its Waiting card; absent on other questions and on closed ones). It draws as an outline
button above the question's buttons. A tap opens `url` in the browser (only `https://` is taken; if it
won't open, the task's page), or the task's page (`Page.Task(task)`) when `url` is null. It does not answer
the question. `label` defaults to "Read it in full"; a `read` without a task is ignored.

### Hold dibs's answer while the user is still writing (task #69, 2026-10-06)
The user asked (word 145) to add, clarify or correct before dibs answers; each line used to wake dibs's brain at
once. dibs now waits ~4 s after each chat line (a burst makes one answer, a single urgent line still goes within
seconds) and while the app says the user is typing; lines that reach it mid-reply are folded into that reply.
- **Typing:** the chat box sends a *live* message on the dibs channel, `{"typing": true, "ts": <phone ms>}`, at most every 5 s while the
  user types in it (not for text the app puts there, a share or the follow-up prefix), and `{"typing": false}` once
  when it's emptied or the screen leaves (`Typing` in `Dibs.kt`).
  Live messages are never stored or queued (`DibsHost.live`, ffi `send_app_live`); dibs reads them as the
  channel's subscribed client. dibs holds its wake for 15 s after a ping. A sent line clears the pings sent before it (by `ts`), so a ping for the next
  line, which can arrive before the queued line itself, still holds.
- **The chip** over the box is `state.hold`, words and all (the app only draws it): `{"kind": "wait|held",
  "note": "…"?, "button": "…", "action": "hold|go-ahead", "style": "outline|primary", "tapped": {…}?}`.
  Renamed and redone in task #224 (app 0.10.2, the user's word 497; it was "Wait, I'm not done" / "Stop, I'm not
  done" / "Go ahead"). `wait`: the button "Wait" (pause icon, outline) while dibs owes the user a reply; it sends `hold`,
  and `tapped` is the chip to show at once after the tap. `held`: the button "Go" (play icon, primary, `go-ahead`) with
  the note "dibs keeps its reply until you tap Go" while dibs still writes, or "dibs's reply is ready" once it is kept.
  A tap shows at once, until a view agrees (15 s at most). The note is one line (ellipsis) and the row reads as one node
  for TalkBack. The typing dots follow `state.busy` only: Wait changes when the reply shows, not whether dibs works,
  and Go starts no work, so it never brings them up (a kept reply that is ready has no dots).
- **dibs's side** (`src/hold.rs`): Wait never stops dibs. Its `dibs say` succeeds but the reply is kept back, not shown;
  Go shows it at once. If the user writes more first, the kept reply goes back to the brain with their lines. After
  10 min without a sign of the user the Wait lapses and the reply shows. A Wait after dibs already answered does
  nothing. Docs/PLAN.md in dibs ("Wait / Go").

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

## Your tasks (task #37, Capture note #68; planned 2026-10-05, **built 2026-10-05** in the app, 0.5.0)

### As built (app side, build order steps 3 to 5)
- `:dibs`: `Payload.kt` (`YourTask`, `TaskResult`, `Shipped`; `yours` is null from a dibs that doesn't send it,
  which keeps the old Work tab), `TasksModel.kt` (grouping, order, state words, the steps summary), `Transcript.kt`
  (parser and rows), `Markdown.kt` (REPORT.md as blocks), all unit-tested (`TasksModelTest`, `TranscriptTest` on
  `src/test/resources/transcript-33.json`, `MarkdownTest`, `PayloadTest`).
- Screens: `ui/TasksTab.kt` (Work's content is `workItems` in the "dibs's own work" fold), `ui/TaskPage.kt`,
  `ui/TranscriptScreen.kt`, `ui/ReportScreen.kt`. The chat's parts moved to `ui/ChatParts.kt`; each chat has its own
  `Composer` (draft, picked files, `say`); the task page's own box went with word 221.
- Back stack: `Dibs.pages` (`Page.Task|Transcript|Report`), popped by system back; `DibsActivity.EXTRA_TASK`
  (`com.kivan.tether.dibs.TASK`, a Long) opens a task. The other tabs keep their scroll under a page
  (`SaveableStateHolder`); the Chat tab still opens at its newest line.
- `:app`: `DibsHost.channelFile(prefix)` reads the core's new `app_files` (ffi) listing, again on each received
  channel file (`Core.channelFiles`); with no node running it reads `state/channels/dibs/`. `send` takes the action
  (`task-say` with the task). Fetched files are pruned at start and on each arrival: the newest per task and kind is
  kept, then dropped after 14 days or 7 days after its task's `ticked` (while the view still lists it). Every dibs
  notification opens the chat (word 221), a task's too.
- Differently from the plan: a task's page opens at the top (its report) unless its agent replied since the page was
  last open (then at the chat); ticking off from the page goes back to the list; "Getting it" offers Try again after
  90 s. Icons added through `regen-look.sh`: list-checks, arrow-left, refresh-cw, chevron-right, scroll-text,
  circle-check.

**The user's words (note #68):** with eight sessions at once they lose track of what is happening. When a task
*they* asked for finishes, they want to read what it found in a place that waits for them and doesn't vanish after
some minutes, and they want to be the one who ticks it off. They also want to read the whole transcript if they
choose, and to talk to the agent that did the work: "ask him questions about it in order to understand what
happened, why did he do what he chose to do, and perhaps iterate with him … or change a direction, or pivot." Tasks
dibs starts by itself are dibs's business. **Scope agreed with dibs (question #301):** the user's tasks wait in the
dibs app until ticked, grouped by project. Each one shows its plain summary and its full transcript, and the user
can talk to that task's own agent from the phone. No per-project dibs chats and no urgency meter. The dibs side of
the plan is in `~/Projects/dibs/docs/PLAN.md`, "Your tasks on the phone".

### What the user gets
- **A Tasks tab in place of Work.** It holds every task the user asked for, grouped by project (tether, dibs,
  the research folders under "Other"), from its start until they tick it off. Running ones are listed too, so a
  task's whole life is in one place.
  - **A row:** a plain title, its state ("Needs you", "Working · 40 min", "Done 21:36", "Stopped") and one line
    (what changed, or what it's doing now). A done task the user hasn't opened yet is bold.
  - **Order:** a group with something that needs the user comes first, then the others by newest activity. Inside
    a group: needs you, done and unread, working, done and read, stopped or failed.
  - **No big number** (task #64, the user's word 122: they never used "Yours · 40 open"). Instead, a line in the
    accent says what the tab's badge counts: "2 for you: 1 finished to read, 1 asking you" (none when nothing
    does), and a done task not opened yet says "Done 07:54 · new". The phone counts the badge itself from `yours`
    (the same rule as `badges.tasks`), so it drops as soon as a task is opened here.
  - **Titles** are dibs's, cleaned on the phone (`plainTitle`): an aside in brackets goes, including one cut open
    ("Cut phone notification clutter (the user"), and a title that is only a tag before a colon in the user's
    words ("PLAN ONLY") takes the words after it. dibs's `yours::title_from` still makes those titles; fixing it
    there is left for the dibs side. A title the phone takes from the user's words (after a tag) is their whole
    first clause, never cut (until 0.5.9 it was cut at 60 characters with "…").
  - **The laptop** (word 145): a "Laptop" line at the top, memory, load, builds running and waiting, agents, from
    `state.laptop` when dibs sends it.
- **The board** (app 0.5.9, the user's decision 2026-10-06: the phone app doesn't group or sort tasks itself; dibs
  builds the board and both apps draw it unchanged). From a dibs that sends `board` next to `yours`, the tab draws it
  in place of the project groups and the Ticked fold; without it, the old list stays. The Laptop line and the
  "for you" words stay on top.
  - **Two lists since 0.10.5 (dibs task #184):** `columns` holds exactly **Active** (key `active`) and **Backlog**
    (key `backlog`), then Done. An older dibs sends three, `now`, `next`, `later` ("Working now", "Queue", "Later"):
    the app still reads and draws any columns sent, in order, so the phone can update before dibs does.
  - Each column with cards gets its title as a section heading ("Active", "Backlog") and its cards in
    the order sent; an empty column is left out, and one quiet line shows when all are. Then **Done · 12**, folded
    by default, with its cards. dibs's own work stays folded at the bottom.
  - **A card:** "#31" (small, muted; ideas have no number), the title whole and wrapping, dibs's state words (a
    working one with the busy dot when its `yours` task is busy), the tags as small pills ("On hold", "work saved"),
    a "Full story" pill once a story is ready or being written, and `now` muted when it says something.
  - **The button on the face (0.10.5):** a card's `primary` (`"park"` on every Active card, `"start"` on every Backlog
    card, absent on Done and from an older dibs) is drawn as a small accent-coloured text button at the right of the
    title row, "Park" or "Start". A tap sends `task-act` with that act. The long-press menu leaves it out and lists the
    rest of `actions`. An Active card's `lane` (`running|waiting`) is read and kept; the app draws nothing of its own
    from it (dibs's state words already say it). The laptop's room line ("No room for another task") sits above the
    column keyed `active` (an older dibs: `next`).
  - **A tap** opens a task's page; an idea has none, so a tap opens its menu. **A long press** opens the menu of the
    card's `actions` in plain words (minus its `primary`): Start, Park, Start now, Move up, Move down, Stop, Delete,
    Full story. Only what the card lists shows; dibs's `move` is never in a menu. The names an older dibs sends keep
    their words: Put on hold (`hold`), Resume (`resume`), Move to the queue (`to_next`), Move to the Backlog
    (`to_later`, was "Move to Later"). Stop and Delete ask again ("Stop it?"; Delete shows the card's
    `delete_text` under it, what deleting loses) and act on the second tap within 4 s. Full story opens the story's
    page; the rest go back as the phone action `task-act` (`Dibs.TASK_ACT`, one constant, as the name may still
    change): `{"key": "task:31", "act": "hold", "before": "task:30"?, "confirm": true?}`. `confirm` is sent only
    after the second tap of Stop or Delete; dibs applies a `delete` only with it.
  - A card or column without a `key` is dropped and a repeated key is kept once (the list keys on them); a JSON
    `null` text reads as empty, never as the word "null".
  - Payload (`Payload.kt`: `Board`, `BoardColumn`, `BoardCard`; `DibsView.board` is null without it):
    ```json
    "board": {"columns": [{"key": "active|backlog", "title": "Active", "cards": [Card]}],   // older dibs: now|next|later
              "done": {"count": 12, "cards": [Card]}}
    Card: {"key": "task:31|idea:7", "n": 31|null, "title": "whole", "state_words": "built, waiting to land",
           "now": "what it's doing, or empty", "tags": ["On hold", "work saved"], "story": {…like yours[].story}|null,
           "actions": ["start","park","start_now","up","down","stop","delete","story"],   // older dibs also: hold, resume, to_next, to_later
           "primary": "park|start"?, "lane": "running|waiting"?,   // primary: every Active (park) and Backlog (start) card
           "delete_text": "what Delete loses, in plain words"?,
           "progress": {"stage": "plan|build|review|ship", "words": "building, step 2 of 5", "from": <start>,
                        "eta": <likely done>, "lo": <earliest>, "hi": <latest>, "guess": bool, "seen": 12}?}
    ```
  - **Progress** (a running task, dibs 2026-10-08, the user's word 608): its stage in words and
    "done in ~15 min", a range ("done in ~10–30 min") when the tasks it's judged by varied, "(rough guess)" with
    few of them; no `eta` (nothing like it finished yet): the words only. dibs sends times, not minutes, and keeps a
    card's times until the estimate really moves (each new view wakes the link); the app counts down on its own
    clock (`Progress.kt`). The bar under it is solid up to the latest finish and light up to the earliest.
- **dibs's own work** (its background tasks, the live sessions, ships) folds into one line at the bottom,
  "dibs's own work · 3", which opens on a tap. It holds today's Work tab content, and nothing in it pings.
- **One ping per task** when it finishes, when it asks the user something, or when its agent replies to the user. A
  tap opens the task. The ping goes away when the task is opened or ticked off. Tasks dibs started by itself never
  ping.
- **Ticking off** is the user's call alone; nothing is ticked automatically. A ticked task leaves the list and
  goes under "Ticked · 12" at the bottom for a week, where Untick brings it back.
- **Recap** stays the feed of everything. A Recap row that belongs to one of the user's tasks opens that task.

### Screens
1. **The Tasks tab**, as above. A long press on a row gives Tick off and Open on laptop.
2. **The task page**, from top to bottom:
   - The title and state, when it started, how long it ran and when it finished.
   - Its open questions as the same cards as in Waiting. Answering in either place closes the question in both.
   - **What it did:** the task's report, in full.
   - **Result:** a "Report" chip when the task wrote a REPORT.md (it opens in a reader on the phone), and "Shipped to
     tether · 3 changes" with their "For you" lines.
   - **You asked:** in full (until 0.5.9 folded to three lines ending in "…").
   - **Transcript.**
   - The earlier messages with its agent, read-only (below), and **Ask dibs about it**.
   - The top bar has **Tick off ✓** once the task is finished, Stop while it runs, and Open on laptop in the ⋮ menu.
3. **The transcript** reads like a document from the top, with "↓ End" to jump.
   - The task's prompt comes first, in full.
   - The agent's text is shown in full.
   - The user's own lines (typed at the laptop or sent from the phone) show as their bubbles.
   - dibs's notes and "keep going" pushes show as muted system lines.
   - Tool calls fold into one line per run, e.g. "12 steps: read 5 files, edited recap.rs, ran cargo test ✓". A tap
     lists the steps, and a tap on a step shows its input and output, each cut at 2 KB, saying so when cut.
   - Seams between the task's sessions read "Continued in a fresh context" (after a handoff or /clear) or "Reopened
     22:10".
   - A running task's transcript is a snapshot: "As of 21:40 · Refresh".
4. **No chat with the task's agent** (the user, word 221, 2026-10-06: "I ONLY talk to dibs, never to other agents").
   Until then (2026-10-05) the page had a box that went straight to the agent; it's gone.
   - The messages exchanged before stay, read-only, under "Earlier messages", in the dibs chat's bubbles.
   - **Ask dibs about it** at the bottom opens the dibs chat with "About <title>: " typed in. dibs passes on what's
     for the agent (`dibs task tell`).
   - "<title> is on it" shows as dots while its session is busy.

### Data (dibs → phone)
These are new keys in the `dibs` payload, sent only to app 0.5.0 and newer. Older apps keep `tasks` and the Work tab.
```json
"yours": [{"id": 31, "title": "Recap: one entry per finished job", "name": "the-dibs-app-s-recap-tab",
           "project": "tether", "state": "needs|working|paused|done|stopped|failed", "ts": 1791230000,
           "started": …, "finished": …?, "minutes": 95, "line": "what changed, or doing now",
           "asked": "the user's words, whole", "report": "whole"?,
           "result": {"report_md": true?, "shipped": [{"repo": "tether", "for_you": ["…"]}]?}?,
           "questions": [249], "busy": false, "live": true, "unread": true, "ticked": …?,
           "talk": [{"id": "t31-4", "who": "user|agent|note", "text": "whole", "short": "…"?, "ts": …,
                     "files": [{"id", "name", "size", "image"}]?}]}],
"badges": {"tasks": 2, …}
```
- `badges.tasks` counts the done-and-unread tasks plus the ones that need the user.
- Ticked tasks stay in the list for 7 days with `ticked` set. `talk` holds the last 30 lines; older ones are in the
  transcript.
- A Recap entry of one of the user's tasks carries `task: <id>` and leaves out `report` and `more`, because the task
  page has them.
- **Size.** Today's view is 93 KB, it is republished on every change, and the frame cap is 4 MB. A task adds about
  5 KB, but dropping the Recap duplicates takes most of that back. dibs keeps the view under 256 KB by trimming the
  oldest talk lines first.
- **Transcripts and REPORT.md never ride in the view.** The phone asks for one, and dibs sends it as a channel file.
  - Measured on tasks #23, #29, #31, #32 and #33: the raw transcripts are 0.7–6 MB, of which 4–30 KB is text,
    plus 30–180 tool calls. Rendered with each tool's input and output cut at 2 KB, that makes about 50–400 KB, and
    gzip shrinks it about 4×.
  - Format (`transcript-<task>-<rev>.json.gz`):
    `{"v":1, "task":31, "rev":"…", "as_of":…, "parts":[{"session","how":"start|handoff|clear|reopen","ts"}],
    "turns":[{"who":"ask|user|agent|note|steps","ts", "text"?, "src":"laptop|phone"?,
    "steps":[{"tool":"Edit","line":"Edit src/recap.rs","in"?,"out"?,"error":true?}]?}]}`.
  - The phone shows "Getting it" (the link is up while the app is on screen). It keeps the newest file per task and
    deletes it 7 days after the task is ticked, or after 14 days.

### Actions (phone → dibs)
- New:
  - `task-say` (`value.task`, `value.text`, `value.files`?): sent only by apps before 0.5.5. Since word 221 dibs hands
    it to its brain as the user's chat line about that task, never to the agent.
  - `tick` / `untick` (`value.task`).
  - `seen` (`value.task`): sent when its page opens. It clears `unread` and the task's ping.
  - `fetch` (`value.task`, `value.what`: `transcript|report`).
  - `task-act` (0.5.9, `value.key`: a board card's key, `value.act`: one of its `actions`, `value.before`?: for a
    move, `value.confirm`?: `true` once Stop's or Delete's confirm step was taken): a board card's long-press menu
    ("The board" above) or, since 0.10.5, the Start/Park button on its face (`value.act` = its `primary`).
- Unchanged: `stop`, `reopen` and the question answers. `tell` stays for older apps.

### What changes in Tether
- **Daemon:** a new `tether send --channel <ch> <file>…` (socket op `send_channel_file`), so dibs can hand the
  phone a file on its channel.
  - The core already sends `ChannelFile` both ways, and the phone already keeps channel files in
    `channels/<ch>/`, out of Downloads and the chat (`Core.kt`). The core doesn't change.
  - A loopback test in the laptop → phone direction.
- **`:app`:**
  - `DibsHost` gets `channelFile(name)`, a flow of the file once it has arrived, read through `appFiles`.
  - A dibs notification whose tag is `task:<id>` opens `DibsActivity` on that task (intent extra `task`).
  - Old dibs channel files are pruned.
- **`:dibs`:**
  - `Payload.kt` reads `yours` and `badges.tasks`.
  - `TasksTab.kt` replaces `WorkTab.kt`, whose content moves into the fold at the bottom.
  - New `TaskPage.kt`, plus `TranscriptScreen.kt` with its parser `Transcript.kt` (unit-tested on a real rendered
    file).
  - The task chat reuses the dibs chat's parts, so these move out of `ChatTab.kt` (813 lines) into shared parts
    first.
  - A small back stack inside `DibsActivity`: tab → task → transcript or report.
- **Version 0.5.0.** The ship installs the daemon first, then dibs, then the app.

### Build order (each step shippable and reviewed)
1. Tether daemon: `send --channel` and its test. Nothing uses it yet, so it ships alone.
2. dibs: the data and actions, delivery to the task's agent and its replies coming back, the transcript renderer
   (`dibs task transcript`, also useful at the laptop) and `fetch`. The plan is in dibs's PLAN.md.
3. App: the Tasks tab and the task page (summary, questions, result, tick off, stop, Open on laptop). This is
   useful before the chat and the transcript exist.
4. App: the task chat.
5. App: the transcript and report readers.
6. An independent review of both repos. Check on the Pixel with screenshots (`dibs phone request`), then `dibs ship`
   in the order above.

### Open questions for the user (the defaults go ahead if they say "build it" without answering)
1. **Should the Tasks tab replace Work**, with dibs's own work folded at its bottom? Default: yes. Four tabs stay
   four.
2. **Where should a finished task's chat reopen** when the user messages it from the phone? Either in their herdr
   `work` (visible but not focused, so they can watch it when back) or out of sight in dibs's hidden session.
   Default: where the task ran, which is `work` for their tasks.
3. **Should every reply from a task's agent ping the phone**, or only finishing and asking? Default: a reply to a
   line the user sent pings (they're waiting for it). There is one ping per task, updated in place.
4. **Should tasks also be ticked off at the laptop?** The widget's FINISHED section (task #32) would get ✓ and
   an unread dot, with the same state as the phone. Default: yes, it's small.

**Not planned:** per-project dibs chats, and an urgency meter. Two things already cover what the note asked for:
only the user's own tasks ping, and "Needs you" sorts first. If the list is still too long after real use, a "this
one matters" flag would be the next step.

## Task #64 (2026-10-06): the Chat box, pictures, usage

- **The Chat box went missing** (the user's word 117: they had to write through the Tasks tab). Cause, found with
  the JVM screen tests (`ScreensTest`, Robolectric and Roborazzi): with the keyboard open on a small screen the
  header and the lend toggles left the chat no room, and the box was squeezed out of the layout (at a 45 %
  keyboard on 320×568 dp it wasn't laid out at all). Now the toggles step aside while typing, as the tab bar
  already did, and `ScreensTest` checks the box at 30, 45 and 55 % keyboards. PNGs:
  `./gradlew :dibs:testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'` → `dibs/build/outputs/roborazzi/`.
- **Typed answers on every question card** are task #66's (dibs-tether-questions), on both sides.
- **Pictures full screen** (word 125): a tap on a thumbnail opens `ImageViewer` (pinch or double tap to zoom, drag
  while zoomed, back or ✕ closes). `DibsHost.image(id, maxPx)` gives the sharpest copy: a file dibs sent is kept
  in its channel folder; the user's own sent copies are gone, so theirs show the 720 px thumbnail. Images dibs
  sends now get a thumbnail on arrival (`Core`), so they show as pictures, not chips.
- **Usage** (word 131): the header shows each window, "5h 88% · resets 12:20", amber from 80 %, from
  `state.limits` (`{"ts", "windows": [{"name", "pct", "resets"}]}`, added on the dibs side by task #66).
  A line too long for its room wraps after its "·" ("7d 93% ·" over "resets Sun 10:00"), never cut (0.5.9).

## Full story (task #85, 2026-10-06)

The user's words: a long readable account of a task (what was asked, what the agent tried, what failed and why, the
choices and their reasons, research findings, what's left), written only when they ask. The dibs side writes it;
the app (0.5.7) asks for it, shows it and lets them talk to dibs about it.

- **Contract.** Each `yours[]` task may carry `"story": {"state": "writing"|"ready"|"failed", "ts"?, "stale"?,
  "have"?, "since"?, "by"?: "agent"|"writer"}` (`Story` in `Payload.kt`; absent: never asked). `ts` is when the kept
  one was written, `stale` that the task moved on since, `have` that one is kept (readable while a new one is
  written), `since` when writing began.
  - Getting it: `fetch` with `{"task", "what": "story"}`. A kept one comes at once as `story-<task>-<hash>.md`
    (plain Markdown); with none, dibs starts writing it (a few minutes) and sends the file when done.
  - Writing it anew: `story` with `{"task", "again": true}` (`Dibs.story`).
  - A chat line may carry `"open": {"story": <task>}` (`TalkLine.open`): dibs's note that a story is ready.
  - From the story, messages to dibs are ordinary `say`s whose value also carries
    `"about": {"story": <task>, "kind": "ask"|"follow", "quote"?}` (`About`, `Composer.about`, sent once then cleared).
- **Task page:** a "Full story" row after the Result block, before "You asked": "A long read: what it tried, what
  failed, the choices, what's left" until asked, then "dibs is writing it" (or "Its agent is writing it"),
  "Written 21:40", "Written 21:40 · the task moved on since" (`storyWords`). A tap opens the story.
- **The story** (`ui/StoryScreen.kt`, `Page.Story`): fetched like the transcript (`rememberFetch`, once more when
  `story.ts` is newer than the file here), but only when it was never asked for or is `ready`: while one is written,
  or after a failure, entering never sends `fetch` (each could start a paid write), drawn with the report's blocks (`Block`, `inline`, shared through
  `ReadLook`) in roomier type. On top, "Written 21:40 by dibs's writer" (or "by its agent"); when stale, a quiet
  "The task moved on since this was written." with Write it again; a kept one while a new one is written says
  "A new version is being written". With no file yet: "dibs is writing it" with "you can leave, dibs tells you in
  the chat" (no 90-second timeout), or "It couldn't be written" with Try again. A long press on a paragraph or a point offers "Ask dibs about this part" (the chat opens
  with it as `quote`) and Copy, so the text isn't selectable. The bar under it wraps on a narrow screen: Show the
  conversation (the transcript), Start a follow-up, Ask dibs about this.
- **Chat:** the ready note has a "Read it" chip that opens the story (while the task is listed). While the next
  message is about a story, a chip over the box says so ("About the full story of X", "Follow-up to X", with the
  quoted paragraph); ✕ drops it. A follow-up's draft starts "Follow-up: " so the line reads right later; that prefix alone
  can't be sent (`Composer.canSend`), as a bare follow-up would start a task from nothing.
- `:app` prunes fetched stories like transcripts and reports (`fetchedOf`): the newest per task is kept.
- Tests: `PayloadTest.fullStories`, `FormatTest` (`storyWords`, `aboutWords`), `DibsTest` (what the `say` carries),
  `ScreensTest` (the row, the story at 412 and 320 dp, writing, Read it and the chip).

## Ideas (task #68, Capture moves into dibs, 2026-10-07; app 0.6.0)

The user's call (2026-10-06, word 126): Capture's notes move into dibs; the phone keeps a quick way to record or
type an idea, in the dibs design. dibs holds the notes (`notes.rs`, `ideasapp.rs`); the phone records, has
Gemini (free tier, key in `local.properties`) transcribe and title, and sends the result. dibs's PLAN.md
"Capture moves into dibs" has the whole plan.

- **Contract.** The payload's `ideas` (dibs ≥ the step 2 ship; `ideasapp::APP` = 0.6.0 gates it): `notes` newest
  first (`id`, `num`, `label` "#45", `title`, `summary`, `meta`, `status {text, tone}`, `task`, `actions` while its
  question is open, `page` buttons, `transcript` or a `fetch` button, `adds`: its additions' ids), `total`,
  `empty`, `trash {title, note, items}`. Shared sample: dibs's `tests/samples/ideas.json`, copied to
  `dibs/src/test/resources/ideas.json`. Actions: `idea-new {id, created, title, summary, transcript, duration_ms?}`,
  `idea-add {note, id, created, text, title, summary}` (a blank title keeps the note's), `idea-answer`, `idea-done`,
  `idea-restore`, and `fetch {what: "idea", note, hash}`, which brings `idea-<id>-<hash>.md`; the page and the
  worker take only the file whose hash the view names (`IdeaNote.loadPrefix`). `:app` prunes them as transcripts.
- **Drafts** (`ideas/Drafts.kt`): what was made here and isn't listed yet, one JSON file each under
  `files/dibs-ideas`. A recording's draft is written when it starts, so a crash or a restart keeps what it got;
  only an empty one goes. A whole draft goes to Tether's queue (`DibsHost.actStored`, which returns once it is
  stored) and is dropped when a view lists it (an addition: when its note's `adds` has it). An addition whose note
  is in Trash, or gone, fails and keeps its words (Retry, Delete). One not listed 10 min after it went goes again,
  only while the link is up. The last view's `ideas` is kept on disk for a cold worker.
- **Gemini** (`ideas/IdeaWorker.kt`, Capture's rules): one unique work per draft, through one rate gate; a 429
  holds every draft without using an attempt; 8 attempts; the same failed status twice is for good. An addition
  goes up with its note's text so far (the view's, else the loaded file, else the phone's own draft, plus this
  phone's unlisted additions). Without that text, it is only transcribed and the note keeps its title. A typed
  note Gemini can't title goes as typed; a recording in which nothing was heard is dropped, with a toast.
- **Screens:** Ideas is the second tab (`ui/IdeasTab.kt`): Record, a box to type, "On this phone" drafts, the
  notes with their question's buttons, Trash folded. A note's page (`ui/IdeaPage.kt`, `Page.Idea`): Summary |
  Transcript, Copy, add by typing or recording, Done. A tap shows at once (`IdeaTaps`) until a view shows it
  applied (Done: off the list; Restore: back; an answer: its buttons or state changed); while the link is up it
  gives way after 5 min.
- **Quick capture:** the Idea tile (`IdeaTileService`) opens `IdeaActivity` over the lock screen: a dark sheet,
  Voice or Text, then a calm record screen (time, level line, one Stop) or a box and Save. The dibs icon's
  shortcuts "Record an idea" and "Type an idea" go straight in.
- Tests: `IdeasTest` (the sample, drafts, recovery, the worker's paths with a fake Gemini), `IdeasScreensTest`.

## Ask about (task #102, 2026-10-07; app 0.7.0)

The user chose option A of dibs's docs/plan/talking.md: a short conversation about one subject (a task, a note,
later a project or a file) with a helper that knows only that subject, so questions don't wake dibs's brain or
crowd the main chat. The contract both apps draw is the task's DESIGN.md (payload and actions below); the
mockups are its p1–p9. The user never sees the word "thread": it is **Ask about it** / **Ask about this note**,
**Back to your questions** while one is open, **Ask more** once ended (dibs words the label). Tether already has a
"thread" channel kind, so the Kotlin says `Asking`, `AskPage`, `Page.Ask(about)`, `AskRow`.

- **Contract.** The payload's `threads` (dibs ≥ the #102 ship; dibs gates it on app 0.7.0): conversations active in
  the last 24 hours, at most 6, newest first, each with all its lines: `about` (the key: `task:<n>`, `note:<id>`,
  `project:<name>`, `file:<path>`), `id`, `title`, `eyebrow`, `state` (reading | answering | open | ending | ended),
  `row {title, words, tone: new|busy|plain}`, `intro`, `reading`, `overview {text, chips}`, `lines [{id, uid?, who:
  user|dibs|note, text, ts, wait?, chips?}]`, `asked` (chips already sent), `status {busy, words}`, `ended {words,
  kept_title, kept, foot}`, `placeholder`, `done`, `more`, `ts`, `last_ts` (`Asking` in `Payload.kt`; every field
  optional). A main chat line carries `thread: <about>` (drawn as that conversation's row) and a line passed on to
  dibs carries `under` ("From your conversation about …"). Every subject that can be asked about carries
  `ask {about, label}`: `yours[i]`, task cards of `board`, `ideas.notes[i]`. Sample: dibs's `tests/samples/threads.json` (written by
  its real builder, with the actions it takes), copied to `dibs/src/test/resources/threads.json`. A conversation's
  notification carries tag `ask:<thread id>`; its tap opens that conversation's page (`DibsActivity.EXTRA_ASK`, with
  `EXTRA_ASK_ABOUT` the subject `Notifier.app` found in the loaded view, so a cold start opens it before the screen
  has a view; without one, it opens once a view lists the id, within 30 s and unless the user changed tab or left).
  The screen is exported, so the extra counts only as a `task:`/`note:`/`project:`/`file:` subject. Reopened from
  Recents, `DibsActivity` ignores the intent that first opened it.
- **Actions** (all through `act`): `thread-open {about}` (open, or bring an ended one back), `thread-say {about,
  text}` with the line's uid (its echo clears when a `lines[].uid` matches), `thread-done {about}`, `thread-seen
  {about, n}` (the page is on screen showing `n` lines; sent once per newest line, not per count, since dibs sends
  at most 40 lines, and again when the overview arrives).
- **The page** (`ui/AskPage.kt`): opens at once on the tap, keyed by `about`, before dibs lists it ("Opening", the
  button's subject title, the reading card); the box works from the start and early lines wait as dashed echoes
  ("Waiting for the overview"). Then the intro, the overview card with its suggested questions (chips that wrap; a
  tap sends one as the user's line, an asked one shows a check), the lines (the user's on the right, waiting ones
  dashed with dibs's words under them; dibs's as Markdown on the ground; notes small and centred with an arrow),
  only the newest answer's chips (the overview's until an answer has some), the status row, and the ended card.
  Done in the header (no confirm; the box greys at once); once ended, Ask more replaces the box. A conversation
  that leaves the payload closes its page. No attachments here. dibs lists an open or a line at once over a live
  link: one still unlisted 20 s after the link came up (`Dibs.unheard`; dibs refused it, or couldn't find the
  subject; dibs also ignores an open over an hour old) shows "dibs couldn't open this" with Try again (a new
  `thread-open`, and the wait starts over) and Back in place of the reading card and the box, and a line says
  "dibs hasn't taken this yet" under it; either clears if dibs lists it later. Done and Ask more come back on the
  same clock, 15 s after the tap once the link is up, if dibs didn't act on them.
- **Ways in:** the task page's bar (in place of "Ask dibs about it", which an older dibs still gets), the full
  story's bar, its paragraph long-press ("Ask about this part": the page opens with "About “<its first words>”: "
  in the box, nothing sent), and a note's page under its question's buttons. "Start a follow-up" stays on the full
  story's bar, where #85 put it.
- **Main chat:** one row per conversation (`AskRow` in `ChatParts.kt`), updated in place: tile, "Asking about: …",
  how it stands (accent with a dot when new, a pulsing dot while busy), ›; a tap opens it. A line for a conversation
  no longer listed reads as a plain note.
- Tests: `AskTest` (the sample, older payloads, what each tap sends), `AskScreensTest` (412 and 320 dp: reading,
  overview and chips, a full conversation and Done, ending, ended, the main chat row, long titles and chips, the
  entry buttons).

## Root steps approved from the phone (task #145, 2026-10-07; app 0.8.0)

A root step an agent needs (a fix in `/etc`, a service restart) runs only after the user approves it on the phone
with their fingerprint or face. The laptop's root helper (`dibs-root`, in the dibs repo) trusts nothing the user's
account can write, only a signature made by a key in the phone's security chip. Design: dibs's task #142 PLAN.md;
the wire formats both sides build byte for byte are the task's SPEC.md (dibs task #145), summed up here.

- **Contract.** A root request is a Waiting question of `kind: "root"`, actions `[{"id": "d<id>", "label": "Deny"}]`
  (no Approve: approving needs the page) and a `root` object: `request`, `machine` (32 hex), `nonce` (32 hex),
  `expires` (unix s), `network` (bool), `home` (`no` | `ro`), `timeout` (s), `script` (text), `files` (`{name,
  text}` shown whole, or `{name, size, sha256}` binary), `why` (the agent's own words), `note` and `pick`
  (`accept` | `reject`; both null until dibs has checked it, up to ~3 min), `allow_list` (`{name, action}`).
  Parsed into `RootRequest` (`Root.kt`). The key's setup is a question of `kind: "rootkey"`, whose Set up the phone
  handles itself.
- **The signed message** (`RootMessage.build`, unit-tested against the spec's fixed vector): UTF-8 lines, each
  ending in `\n`: `dibs-root v1`, `machine`, `request`, `nonce`, `expires`, `network yes|no`, `home no|ro`,
  `timeout`, `remember yes|no` (Never ask again), `script <sha256>`, then `file <name> <sha256>` per file sorted
  by name. **The phone hashes the script and every text file itself, from the text it shows**; only a binary
  file's hash comes from the laptop, and the page says so. A request the phone can't sign as given (a bad
  name, machine or nonce, an odd `home`, a time limit out of 1–3600 s) says why and offers only Deny.
- **The card** (`RootCard` in `ui/RootPage.kt`): "Root step" eyebrow, the title, the agent's reason marked as its
  words, dibs's check ("dibs would approve it: …", or "dibs hasn't checked this one"), Review (opens
  `Page.Root(id)`) and Deny. A root question's line in the chat offers the same (`ChatLook.roots`).
- **The page** (`RootPage`): when it expires; the agent's reason; dibs's check; the phone's own warnings worked out
  from the bytes (`RootMessage.warnings`: network, home folder, downloads, piping into a shell, sudo/login/
  polkit/SSH rules, the helper itself, deleting, users and passwords, setuid, disable/mask, a file it can't show,
  hidden characters, expiring soon); network, home and time limit in one line; the script and text files word for
  word in monospace (wrapping, never cut; every character the helper refuses, i.e. controls but newline and tab,
  every space but the plain one, line and paragraph separators, Unicode Cf/Co/Cn, written out as `⟨U+00A0⟩`, and a
  script with any of them can't be approved); binary files by name, size and the laptop's hash; the request code
  (first 16 hex of the message's sha256, worked out on the phone; it follows the Never ask again tick); "Never ask
  again" (off by default); Approve and Deny. Approve builds the message again from what's on screen, asks for a
  fingerprint or face (platform `BiometricPrompt`, `BIOMETRIC_STRONG` only, a `CryptoObject` around
  `SHA256withECDSA`; face needs a confirm tap), signs only with the signature the prompt's success callback hands
  back, and sends `root-approve` `{item, request, sig: base64 DER, remember}`; the card closes at once. Deny sends
  the card's `d<id>`. An expired request, a missing key or an invalidated one disables Approve with a plain line.
- **The key** (`RootKey.kt`, behind the `RootKey` interface so the screen tests use `FakeKey`): Android Keystore
  alias `dibs-root-v1`, EC P-256, sign/SHA-256, StrongBox (TEE only when StrongBox is missing, reported as
  `strongbox: false`), authentication on every use by a **strong biometric only** (no PIN: dibs knows the PIN and
  can type it over adb), invalidated when a fingerprint or face is enrolled. Invalidated, the page says "set up
  again; the laptop needs one password to trust the new key". Set up (`Page.RootKey`) makes the key when there's
  none (or it was invalidated), shows its code (sha256 of the public key's SubjectPublicKeyInfo, 16 hex in 4
  groups; the laptop's setup popup shows the same) and sends `root-key` `{spki: base64, strongbox, fingerprint}`.
  dibs only stores it; the laptop's setup step, with one password, makes the helper trust it.
- **Release signing.** Once the release key moves to `/var/lib/dibs-root/keys` (root only), `~/.config/tether/
  keystore.properties` is gone, Gradle builds `app-release-unsigned.apk` (zipaligned) and
  `scripts/install-phone.sh` runs `dibs root request "Sign Tether <version> for your phone" --script
  scripts/sign-apk.sh --file app.apk=<unsigned> --wait 1800`, then installs the `app.apk` from the `out: <dir>` it
  prints. `scripts/sign-apk.sh` is the fixed script the user approves (keep its bytes stable, so "Never ask
  again" keeps matching). While `keystore.properties` exists, the build signs as before.
- Tests: `RootTest` (the vector, sorting, binary hashes, refusals, warnings, hidden characters, codes),
  `PayloadTest.aRootStepCarriesItsRequest`, `RootScreensTest` (card, page, approve and what it sends, a closed
  prompt, expired, invalidated, deny, setup, the chat's Review, 320 dp).

## Screen space: one slim bar, one dibs page (task #137, 2026-10-08; app 0.9.0)

**The problem** (the user, words 313 and 314, screenshots of 2026-10-07): on every tab the header (state, name,
mark, two usage lines, ⋮) and the two lend cards took about 290 of the screen's 915 dp before any content. With
a few lines typed and the keyboard closed, the chat got under 40 % of the screen; the Tasks tab added a two-line
"Laptop" block on top of that.

**What good chat apps do** (researched for this task):
- WhatsApp, Signal, Telegram and Google Messages all keep **one slim fixed bar** in a conversation: avatar, name,
  a one-line status under it ("online", "typing"), one or two icons and ⋮. Everything else about the other
  side (settings, media, toggles) is **behind a tap on the name**, on its own page. None of them collapse the
  bar on scroll in a conversation: the reverse-scrolled list and the keyboard make a sliding bar jump, and the
  status line is what you look at while waiting.
- Material 3: a small top app bar is 64 dp; `pinned` is for screens whose bar carries live state and actions,
  `enterAlways` for long reading lists. Nav bars hide while the keyboard is up (this app already does).
- A header holds one or two kinds of things, not logo + status + settings + gauges (common chat UI guidance).
  The keyboard must never cover the box or the newest lines (also already handled here: `imePadding`).

**The decision:**
- **One slim bar on every tab, pinned** (about 52 dp under the status bar): dibs's mark (32 dp), "dibs" with its
  state as a small coloured line under it (the old eyebrow, now a subtitle), then **marks that only appear when
  they matter**: a phone and/or laptop icon in an accent pill while lent to dibs (filled, so it reads without
  colour), and "5h 88%" in amber when a usage window passes 80 %. Then ⋮. It does not hide on scroll or while
  typing: once slim it costs little, and the state is what the user watches while dibs works.
- **The dibs page** (`Page.Status`, opened by a tap on the bar's mark, name or marks, and from ⋮ "Laptop and
  phone"): the state in words, the two lend switches as full cards, Claude usage (every window, its reset time),
  the laptop's load (memory, cores, builds, agents; moved off the Tasks tab) and its room for more tasks, Start
  dibs when the brain is down, Open Tether. This is the one place for all of it; no tab shows lend cards or usage
  lines any more. "Start dibs" also stays in the bar while the brain is down (it blocks everything).
- **Tasks**: each board section shows its count ("Working now · 2", "Queue · 5"); dibs renames "Up next" to
  **Queue** in its board (phone, desktop app and the brain's own words match). When the laptop has no room, the
  board's `room.line` shows above Queue, so a waiting queue explains itself.
- **Ideas** gets a badge with the number of active ideas (not in the trash).
- **Waiting**: a tap on a card's text opens the task it's about (agent questions name their task; dibs now also
  links a brain question that names one, "#104"). Card text is never cut (dibs stopped cutting `why` on
  2026-10-08).
- **Chat** (tasks #135, #154 and Capture note #67 folded in): while the user holds dibs ("dibs waits until
  you're done") the line shows without the animated dots; "Ask dibs about it" puts the task's number in the
  text ("About #144 Agents run while memory allows: "); a long press offers **Select text** (the message whole
  in a sheet, any part selectable) beside Copy and Hide; **swipe right to reply** quotes a message in the box and
  sends dibs which line was answered (plan: Capture note #67's, copied into this task).

**Why not the user's other ideas:** lend cards on one specific tab would still cost that tab the room and split
"what dibs may use" from "how dibs is doing"; a header that collapses on scroll makes the chat jump and hides the
state just when dibs is working. A slim pinned bar gives the chat about 240 dp back on every tab without either.

### As built (app 0.9.0, 2026-10-08)
- Built as decided: `Header` is one slim row (mark 32 dp, "dibs" over the state words); phone and laptop marks are
  filled accent pills with an icon, only while lent; "5h 88%" shows in amber only for the first window past 80 %;
  the whole mark, name and marks area is one button (`dibs, laptop and phone`) opening `Page.Status`
  (`ui/StatusPage.kt`: state, both lend cards stacked, every usage line whole, the laptop's load and the board's
  `room.line`, Start dibs, Open Tether). The Tasks tab's laptop block is gone. On a 412×915 dp screen the
  conversation takes more than 65 % of the window with the keyboard closed (`ScreensTest.theChatKeepsMostOfTheScreen`);
  a 320×568 dp screen has less room because of its 80 dp tab bar, and its keyboard-open cases still show the box.
- Folded in: section counts and "Move to the queue" (the column title is dibs's, never hard-coded), the room line
  above the queue (`Board.room`), an Ideas badge (`ideas.total`: active notes only), a Waiting card's words open its
  task, a held chat shows its line without the dots (`typing-dots` tag), "About #N" in the box and its chip,
  long-press Reply and Select text (sheet with `SelectionContainer` and Copy all), swipe right to reply
  (`SwipeToReply`, `Composer.replyTo`, `ReplyChip`, `ReplyQuote`; one of about and reply at a time, `say` carries
  `reply: {n}`), icons added through `regen-look.sh` (`reply`, `text-cursor`).
- Code in chat lines (task #258): text between triple-backtick fences becomes a small monospace block with a copy
  button at its corner (tick for 1.5 s); the language word is dropped, an unclosed fence runs to the end, the rest
  stays text with its links. `TextParts.kt` is the desktop's `chatText.ts` rule, so both apps agree; Copy and Select
  text still take the line as written. A task line may carry `ask` too (a root step offers Review there).
- Not as planned: the Tasks tab on an older dibs still reads "Up next" (the title is what dibs sends).

## The morning recap (design C, 2026-10-08; app 0.10.0)

The Recap tab is a short **unread list**, like an inbox; a tap opens one **story screen** that merges the recap, the
full story and the Tasks card. Contract with dibs: `CONTRACT.md` of the task (all fields optional; an older dibs sends
none and the old tab shows).

- **Payload.** `recap.items` is a list of `Brief`: `key`, `task`, `tier` (`needs|urgent|asked|talked`), `title`,
  `lede` (one line), the four parts `what`, `why`, `means`, `next` (any may be ""), `answer`? (to a question the user
  asked), `project`?, `kind` (`build|plan|research|design`), `state`, `ts`, `unread`, `writing`? (parts not written
  yet: `what`/`lede` hold the plain summary), `card`? (an open question id in `questions`: its buttons act), `story`?
  (the same Story object as on cards). Also `recap.unread`, `recap.needs`, `recap.small` (`n`, `groups` of
  `project`, `n`, `lines`). `badges.recap` is the unread count. Board task cards (`board.columns[].cards[]`,
  `board.done.cards[]`) may carry `brief` (no `story`, no `unread`); a task in `recap.items` has none on its card and
  `yours[]` carries none (payload size). A task's brief is looked up in `recap.items`, then its board card.
  The tags: needs "Needs you", urgent "Urgent", asked "You asked", talked "You talked it over", with an `answer`
  "Answers your question". `items: []` (the key present) means "You're caught up", not the old tab.
- **Recap tab** (`ui/RecapTab.kt`): a hero "Unread N" with "M need you · K small fixes folded" and "Mark all read"
  (while any is unread); one row per brief (unread dot or blank space, tag chip: needs/urgent amber, asked/talked
  accent, answer blue; the time at the right; the headline; the line in muted; a read row dimmed). Under "The rest":
  the fold "Small fixes · N" (each project and its count; open, the groups with their lines and "n more" when fewer
  lines than `n`), a row "Stories · N" (`recap.stories`, hidden when empty) that opens `Page.Stories`
  (`ui/StoriesPage.kt`: a list of every full story dibs keeps, newest first, title and project · when; a tap opens
  the task's story screen, whose Full story section reads it), and "Decided for you". The tab's badge is the unread number (a read here counts at once).
- **To look over** (task #336, app 0.12.0; `ui/LookSection.kt`, drawn first in both Recap layouts by `lookItems`).
  The user's rule (2026-10-09): plans, designs and choice pages no longer wait for them; dibs goes with its own pick,
  the build starts, and this section is the one place that says so. `looks[]` in the payload: a card per look with a
  chip for its kind (Plan, Design, Research, Answer, Page), where its build is ("Queued to build", "Building now",
  "Built"; none for a read), the title, what dibs picked and why (three lines, a tap opens all), and buttons: "Open page"
  (`link`, https only; else "Open task" for its `task`), **Keep** and **Change** (a read: **Got it** and **Reply**).
  Change and Reply open a box for the user's words (`look/<id>` in `Dibs.fields`). The first line counts the open
  questions ("1 question waits for your answer") and a tap goes to the Waiting tab; four looks show, then "Show all N".
  No `looks` key (an older dibs) and no questions: no section. The Recap tab's number is the unread briefs plus the
  open looks (`Dibs.answered["l<id>"]` hides one at once).
- **Story screen** (`Page.Brief(task, fromRecap)`, `ui/BriefPage.kt`): top bar with back, "k of N" (from Recap, its
  place in `recap.items`) or "Story", and previous/next arrows. A swipe over 60 dp or the arrows replace the top page
  (back still returns to the list). Content: tag, big title, meta ("#id · project · Kind · when", and in amber "since
  yesterday" for an unread one from before today), the parts under eyebrows (empty ones skipped; `writing`: a muted
  "The full write-up is being written" and the summary), then the actions (the named question's buttons as Waiting
  draws them, "Ask dibs about this", "Open the task" for a task of the user's), then the **Full story**
  (`rememberStory`/`fullStoryItems` in `ui/StoryScreen.kt`, shared with `Page.Story`). Nothing paid starts by
  itself: a story never written shows "Read the full story" (it sends `fetch` `{task, what: "story"}`); one being
  written or failed is never fetched. Showing an unread brief sends `recap-seen` once and marks it read here at once
  (`Dibs.answered["rs:<task>"]`). A task with no brief shows its title, state words, "Nothing written up yet", the
  yours task's report if any, the actions and the full story.
- **Tasks tab:** every task card (board card, task row, ticked row) opens `Page.Brief(task, false)`, also a task that
  is not one of the user's; a long press still opens the menu, an idea keeps its menu on a tap.
- **Notification:** tag `recap` (one a morning) opens the Recap tab (`DibsActivity.tabFor`).
- Icon added through `regen-look.sh`: `chevron-left`.

### As built (app 0.10.0, 2026-10-08)
- `Brief`, `RecapSmall` in `Payload.kt` (`DibsView.recapItems`, `recapSmall`, `recapUnread`, `recapNeeds`,
  `recapListed`, `brief(id)`, `storyOf(id)`, `labelOf(id)`); screen tests in `RecapScreensTest`.
- Deviations from the contract text: a brief's `answer` is shown under "The answer" before the four parts (the contract
  gives it no place); `yours[].brief` is still read if a dibs sends one, after the board card's.

## Links (task #261; app 0.11.0, versionCode 42)

`#230` in text is task 230 and `idea 45` is idea 45 (the idea's number in the Ideas tab). The app draws them as
links where it knows the task or idea, opens them on a tap, and offers them while typing.

### Data (dibs → phone)
- **The index.** The payload carries `index_rev`. When it has no file on the phone yet, the app sends `fetch`
  `{"what": "index"}` (once per rev) and dibs answers with the channel file `index-<rev>.json.gz`:
  `{"tasks": [{n, t, s, g, p, a, f}], "ideas": [{n, t, s}]}`. Tasks: `n` number, `t` title, `s` state in words
  ("Queued, 3rd in line"), `g` group (`needs|working|queued|later|done|stopped`), `p` project, `a` the ask's first words,
  `f` when it finished (or null). Ideas: `s` is `active|ticked|deleted`. The newest file read is `Dibs.index`
  (`Refs.kt`, `IndexLoader.kt`); until one is read no text has links. Older dibs: no `index_rev`, nothing asked, no links.
- **A task's page.** `fetch` `{"task": id, "what": "page"}` brings `page-<id>-<rev>.json.gz`: one `yours`-shaped entry
  plus `state_words`, `group`, `links` and `mine` (false: dibs's own work, shown read-only), so any task opens as a page
  (`Page.Task`), not only the user's own.
- **`links`.** Each `yours` entry and each idea note carries `links`: `[{kind: "task"|"idea", n, title, state, why}]`
  (`why`: "Made from", "Waits for"…). A task page shows "Linked ideas" and "Related tasks", an idea page "Linked".

### What the user sees
- **Tap** a link in a chat line (or the ask on a task page) opens that task's page or idea's page (`Dibs.openRef`);
  an idea not in the Ideas tab opens nothing. Back returns to the chat where it was (`Dibs.chatList` outlives the page
  that replaces the tabs).
- **Long press** on a link shows a small card (`RefCard`): `#230 · project` (ideas: `idea 45`), the title, the state in
  words with a dot coloured by group, and **Open**. A long press elsewhere on the line keeps the bubble menu
  (Select text, Reply…). Compose's link overlay eats presses, so `LinkedText` (`ChatParts.kt`) watches them first
  (`PointerEventPass.Initial`) and consumes the rest of a gesture that became a card.
- **The `#` picker.** Typing `#` (after a space or at the start) with the index loaded lists tasks and ideas above the
  box (`pickQuery`, `pick`, at most 30): digits match the number then numbers starting with them, words must each start
  a word of the title or ask. A tap puts `#230 ` (or `idea 45 `) in; × closes the list for that `#`.

### The shared cases
`links-cases.json` (`android/dibs/src/test/resources/`, a copy of dibs's `docs/links-cases.json`) holds the rule for
what is a link: `RefsTest` runs it here, dibs's `refs::find` and the desktop app's `refs.ts` run the same file, so the
three agree. Screen tests: `ScreensTest` (`links-chat`, `links-card`, `links-task-page`, `links-picker`).

## Urgent notifications (task #306; app 0.12.0, versionCode 44)

Things the user asked to hear about at once (a task they called urgent, done or stuck; a promise to tell them urgently)
ring loudly. dibs decides what is loud; the phone only obeys the flag.

- **The flag.** A thread post with `"loud": true` (`tether post --loud`, `dibs-phone post --loud`). An older app ignores
  the field and shows a normal notification.
- **The channel.** `loud`, shown in Android's settings as "Urgent from dibs": high importance, the default alarm sound on
  the alarm stream (so it is heard in silent mode), a long vibration, played once (no looping, no volume change, no
  full-screen intent). It is not under the `app.` prefix, so `appChannels()` never deletes it. The user can tune it in
  Android's notification settings. Channel sound and vibration are frozen once created: a change needs a new id.
- **Behaviour.** A loud post is shown even while its channel's screen is up (`Channels.post`), and always alerts, even
  when it reuses a `--tag` (`Notifier.alertOnce`). Same tag, buttons and tap target as a normal card.
- **Tests.** `LoudTest` (`channelFor`, `alertOnce`); the daemon's `posts_and_thread_lines` covers `--loud`.

## Instant feedback on taps (task #124, app 0.12.1)

Every button shows at once that the tap registered; nothing waits for dibs's round trip to look pressed.
`Taps.kt` is the one place:

- A button that changes nothing on screen by itself (Start dibs, Take it back, Stop, a board card's Start or Park
  and its menu) presses a key in `Taps`: `ActButton(tap = key)` shows a spinner in place of its icon, disabled, for at
  least 0.6 s and until dibs's next view arrives. A card shows the spinner on its face.
- A menu item has no place for a spinner (Open on laptop): its tap has `progress` words and a line over the tabs says
  "Opening on the laptop…".
- A tap whose result already shows (a question answered, a task ticked: `Dibs.answered`) is a *time-only* tap: the
  view that still lists the question does not answer it. If the link has been up 20 s since the tap and the view
  still lists it, `Dibs.sweep` takes the answer back (the question shows again) and a note says "dibs didn't answer …".
- With no link the action is queued (it goes when the link is back); after 2.5 s the note says so instead of spinning.
- Notes (`TapNotes`, over the tabs and over a page) dismiss with a tap. Pure logic: `Taps.judge`, `TapsTest`.
- Root steps (the Root page and the root cards' buttons) and Undo are never taken back (`answer(rollback = false)`): dibs may take longer than 20 s, and a repeat would double the action.

## Ideas v2: the drop box (task #267, app 0.14.0, versionCode 47)

The user drops a rough idea, problem or task, typed or spoken; dibs reads it, asks at most two short questions,
and files it as a parked Backlog task linked to the note (dibs side: `docs/plan/ideas.md` in dibs, "Ideas v2").

### Data (dibs → phone)
- `ideas.notes[].group`: `asks`, `reading`, `notes` or `filed` (Trash stays as before). No `group` (an older dibs):
  every note is in Notes, as before.
- `ideas.notes[].answer`: the key of the note's inline answer box, present while dibs asks; its text is sent as an
  addition to the note (`idea-add`). The status line and buttons already say the question and the state.
- `ideas.box` `{placeholder, hint}`: the drop box's words; absent, the old words show.
- Board: the `backlog` column has `box` (`{placeholder}`, the Backlog box) and starts with one `drop:<note id>` card
  per drop dibs hasn't filed yet: `n` null, no actions, `state_words` ("dibs is reading it", "dibs asks: …") and
  `open` (`idea:<num>`, the idea's page). Once filed, the real `task:N` card takes its place.

### Actions (phone → dibs)
- `idea-new` gets `drop`: `"ideas"` (the Ideas tab's box) or `"backlog"` (the Backlog box: always filed or asked
  about, never kept as a plain note). Drafts keep the field, so a queued or recorded drop keeps it too.
- `drop-answer {note, choice: "file" | "drop"}`: File it as it is, Drop it. `drop-file {note}`: File it anyway (a
  covered or kept note). `drop-retry {note}`: Try again after dibs couldn't read it. All come as the note's buttons.

### What the user sees
- Ideas tab, top to bottom: the drop box (text and mic), then Asks you (each question with an answer box and its
  two buttons), Being read, Notes, and the folds Filed and Ticked off. Filed rows link their task.
- Tasks tab: a one-line box and a mic at the top of the Backlog column; a draft on its way shows "Sending…" under
  it; drop cards show with no number and open the idea's page.
- The idea's page: an Answer box while dibs asks; the Add to it box otherwise.
