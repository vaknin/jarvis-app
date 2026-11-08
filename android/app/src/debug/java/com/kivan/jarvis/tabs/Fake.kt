package com.kivan.jarvis.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Made-up tabs, for screenshots and for trying the screens with no server. Nothing here is real. */
object Fake {
    private val names = listOf(
        "Piano app", "Chord detection", "Dark mode", "Lisbon trip", "Tax return", "Bike computer",
        "Sourdough log", "Server backups", "Reading list", "Garden sensors", "Birthday gift",
        "Home network", "Flight prices", "Language notes", "Recipes", "Guitar pedal",
    )

    fun tiles(n: Int): List<TileView> = names.take(n).mapIndexed { i, name ->
        TileView(
            id = "t${i + 1}",
            n = i + 1,
            name = name,
            needs = i == 1 || (n >= 14 && i == 9),
            unread = i == 3 || i == 7,
            working = i == 0 || i == 1 || i == 3 || i == 12,
            model = if (i == 2) "sonnet" else "opus",
            effort = if (i == 2) "high" else "medium",
        )
    }

    private const val ANSWER = """The chord tests pass now, and the wrong notes came from one place.

The detector reads a frame every 20 ms but the piano's sustain keeps the last chord ringing for about a second, so a fast change looked like both chords at once. I now weigh the newest 80 ms three times as much as the rest.

Three things changed:

- **Attack** is measured per note, not per frame.
- A note below the noise floor is dropped before the match.
- `min_confidence` went from 0.4 to 0.55.

```
chords: 412 frames, 97.6% right (was 88.1%)
timing: 6.1 ms a frame on the Pixel
```

The one case still wrong is a slash chord played very softly, about 2% of frames. I'd leave that for now.

Do you want the fix in today's build, or should it wait for the piano lesson screen?"""

    private val pianoEntries = listOf(
        Entry.Yours("y1", "The chord detection keeps showing the wrong chord when I play fast. Can you look at why?"),
        Entry.Report("r1", "the test run", "Ran the chord tests on 412 recorded frames", "All passed but the slash chords, listed in the run's notes.\n\n- 402 right the first time\n- 10 right after the fix\n"),
        Entry.Answer("a1", 41, ANSWER),
        Entry.Yours(
            "y2",
            "",
            quotes = listOf(
                Quoted(41, "The one case still wrong is a slash chord played very softly, about 2% of frames.", "Fine, leave it."),
                Quoted(41, "Do you want the fix in today's build", "Today's build, please."),
            ),
        ),
        Entry.Answer("a2", 57, "Done. It's in today's build, and the lesson screen picks it up on its own."),
        Entry.Yours("y3", "Now make the keys light up as I play", voice = true),
    )

    /** A time tonight, for "Jarvis takes it at 01:10", the same in every shot. */
    private fun tonight(h: Int, m: Int) =
        java.time.LocalDate.of(2026, 11, 3).atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    val cards = listOf(
        CardView(
            listOf(CardTab("t2", "Phone voice", "q1")),
            "The AirPods mic is deaf on calls. Use the phone's mic instead?",
            why = "On 3 of the last 5 calls the AirPods sent silence for 20 s or more. The phone's mic heard you each time.",
            level = Level.Urgent,
        ),
        CardView(
            listOf(CardTab("t3", "Voice test", "q1"), CardTab("t4", "Reactions", "q2")),
            "Cut replies longer than 30 words on calls?",
            due = tonight(1, 10),
        ),
        CardView(
            listOf(CardTab("t1", "I'm trying to", "q3")),
            "Which model runs the replay test?",
            listOf("Medium", "Fast", "Smart"),
            why = "Medium did the last three replays within 2% of Smart.",
            due = tonight(1, 30),
        ),
        CardView(
            listOf(CardTab("t5", "Tab numbers", "q1")),
            "Keep the old order after the rename?",
            level = Level.Low,
            due = tonight(2, 0),
        ),
    )

    /** An autopilot's intake: four questions and the level, on one card. */
    val intake = listOf(
        Question("How dark should dark mode be?", listOf("True black", "Dark grey", "Follow the phone", ELSE), 1, "Dark grey keeps the key shadows readable."),
        Question("Where does the switch go?", listOf("Settings", "Top bar", "No switch", ELSE), 0),
        Question("Keys in dark mode?", listOf("Keep white keys", "Dim the keys", ELSE), 0),
        Question("Ship to your phone when done?", listOf("Yes", "Ask me first", ELSE), 0),
        Question("Level for this autopilot", listOf("Low", "Default", "Urgent"), 1),
    ).let { qs -> CardView(listOf(CardTab("t6", "Piano app", "q2")), qs[0].text, qs[0].options, qs[0].pick, qs[0].why, items = qs) }

    val digest = listOf(
        DigestView("t2", "Phone voice", tonight(0, 40), 1, "done", "Ring on the WiiM works, tested twice."),
        DigestView("t5", "Tab numbers", tonight(0, 35), 1, "done", "Renumbering after alt+x landed."),
        DigestView("t3", "Voice test", tonight(0, 30), 1, "default", "Keep the call log for a week → Yes"),
        DigestView("t4", "Reactions", tonight(0, 20), 1, "assumed", "Used 👍 for \"seen\"."),
        DigestView("t9", "Tab 9", tonight(0, 10), 1, "report", "~/.claude opened in the wrong pane. Fixing."),
        DigestView("t8", "Jarvis", tonight(0, 5), 1, "report", "A turn stalled 6 min, resumed once."),
    )

    /** Jarvis 2.0's Done line, as the jarvis repo's cli/src/done.rs writes it. */
    const val DONE = """✅ Done: The update prompt shows once, on the next app open
- It no longer pops up mid-call
- "Later" really waits a day now
- Your phone has it already

Changed: the prompt's "Remind me" button is gone; "Later" does the same.

*Took 1 h 40 min · Piano board · from this tab*"""

    /** A tab's issues under 2.0, one of each kind of state, in filing order as the server sends them. */
    val issues = listOf(
        IssueView(39, "Keys light up with the detector", "Piano", 4, label = "Done", detail = listOf("Live on your phone since 7:40 p.m.")),
        IssueView(40, "The update prompt shows once", "Piano", 4, label = "Done", detail = listOf("Live on your phone since 8:55 p.m.")),
        IssueView(41, "Dark mode for the piano app", "Piano", 2, 2, "Testing · try 2", eta = "about 40 min", detail = listOf("Try 1 failed: the key shadows were unreadable on true black.", "Now on dark grey, testing on the emulator.")),
        IssueView(42, "Chord colours follow dark mode", "Piano", 1, label = "Building", detail = listOf("Filed from your message at 9:12 p.m.")),
        IssueView(43, "Lesson screen layout for tablets", "Piano", 0, label = "Planning", held = true, detail = listOf("Held: the usage freeze is on until 11:00 p.m.")),
    )

    fun deck(n: Int = 9, online: Boolean = true, v2: Boolean = false): DeckState {
        val tiles = tiles(n)
        val tabs = tiles.associate { t ->
            t.id to when (t.id) {
                "t1" -> TabView(t, pianoEntries, WorkView("Wiring the key lights to the detector", System.currentTimeMillis() - 12_000))
                "t2" -> TabView(
                    t.copy(working = true),
                    listOf(
                        Entry.Yours("y1", "Add the minor chords to the detector"),
                        Entry.Answer("a1", 12, "Minor chords are in, and the tests pass. One thing needs you before I go on."),
                        Entry.Question("q1", "Should a suspended chord count as major or stay its own thing? It changes what the lesson screen shows."),
                    ),
                    WorkView("Waiting on your answer", System.currentTimeMillis() - 2_000).takeIf { false },
                    asks = listOf(cards[0].copy(tabs = listOf(CardTab("t2", t.name, "q1")))),
                )
                "t3" -> if (v2) TabView(
                    t.copy(working = true),
                    listOf(
                        Entry.Yours("y1", "Add dark mode and fix the update prompt"),
                        Entry.Answer("a1", 6, "Both are filed on the Piano board. I'll tell you when each is live."),
                        Entry.Answer("a2", 14, DONE),
                    ),
                    issues = issues,
                ) else TabView(
                    t.copy(working = true),
                    listOf(Entry.Yours("y1", "Add dark mode and fix the update prompt"), Entry.Answer("a1", 6, "Both are running side by side. I'll check them and tell you.")),
                    jobs = "2 jobs running: Dark mode, The update prompt",
                )
                "t4" -> TabView(t, listOf(Entry.Yours("y1", "Find me a quiet place near the river"), Entry.Answer("a1", 9, ANSWER)))
                else -> TabView(t, listOf(Entry.Answer("a1", 3, "Nothing new here since yesterday.")))
            }
        }
        return DeckState(
            tiles = tiles,
            tabs = tabs,
            closed = listOf(
                ClosedTab("c1", "Dark mode for the piano app", "Done, on your phone"),
                ClosedTab("c2", "Update prompt", "Fixed in version 214"),
                ClosedTab("c3", "Chord colours", "You closed this on Tuesday"),
                ClosedTab("c4", "Flight to Lisbon", "Booked"),
                ClosedTab("c5", "Router firmware", "Updated"),
            ),
            online = online,
            cards = cards,
            digest = digest,
        )
    }

    /**
     * The library fold's main chat and threads over today's data: threads with step blocks (one waits on
     * him, one runs, one is done), report lines, and two cards waiting for his answer, at their times.
     */
    fun fold(n: Int = 6): DeckState {
        val base = deck(n, v2 = true)
        fun steps(done: Int, open: Int) = List(done) { IssueView(100L + it, "Step ${it + 1}", "Memory", 4) } +
            List(open) { IssueView(200L + it, "Step ${done + it + 1}", "Memory", if (it == 0) 2 else 0, label = if (it == 0) "Testing" else "") }
        val issues = mapOf("t1" to steps(1, 3), "t2" to steps(2, 2), "t4" to steps(3, 0), "t5" to steps(1, 2))
        val tabs = base.tabs.mapValues { (id, t) -> issues[id]?.let { t.copy(issues = it) } ?: t }
        return base.copy(
            fold = true,
            tabs = tabs,
            cards = listOf(
                cards[0].copy(at = tonight(0, 42)),
                cards[2].copy(at = tonight(0, 44)),
            ),
            digest = digest + DigestView("t4", "Lisbon trip", tonight(0, 41), 1, "done", "Fixed and landed, screenshot inside."),
        )
    }

    /**
     * The main chat's own talk, two subjects interleaved: Voice, then the phone's dark mode, then Voice again. Each of
     * his messages carries the subject the server tagged it with on arrival ([talkTags], by record offset).
     */
    val talk = listOf(
        Entry.Yours("m1", "the voice call cuts out after a minute", offset = 10, at = tonight(0, 45)),
        Entry.Answer("m2", 20, "The call's buffer fills up and drops. Fixing it now.", at = tonight(0, 45)),
        Entry.Yours("m3", "also, the phone app's dark mode is too grey", offset = 30, at = tonight(0, 47)),
        Entry.Answer("m4", 40, "Dark mode gets a deeper black on the phone.", at = tonight(0, 47)),
        Entry.Yours("m5", "and the voice should sound calmer", offset = 50, at = tonight(0, 49)),
        Entry.Answer("m6", 60, "A calmer voice, slower and lower. Trying two.", at = tonight(0, 49)),
    )
    val talkTags = mapOf(10L to "Voice", 30L to "Phone app", 50L to "Voice")

    /**
     * After [talk], when [bullets]: his message of raw lines (markup he typed, a blank line) and Jarvis's answer of a
     * lead paragraph and three bullets, each line and bullet its own thing to swipe and quote.
     */
    val bulletTalk = listOf(
        Entry.Yours("m7", "- **first** one\nsecond line\n\nthird", offset = 70, at = tonight(0, 51)),
        Entry.Answer("m8", 80, "Two ways to make it calmer:\n- slower, at nine tenths\n- lower, two steps down\n- both together, softer", at = tonight(0, 51)),
    )

    /** The fold with its main chat (the tab called Jarvis) holding [talk]: what the move key works on; [bulletTalk] after it with [bullets]. */
    fun foldTalk(n: Int = 6, bullets: Boolean = false): DeckState {
        val base = fold(n)
        val main = TileView("t0", 0, MAIN_NAME, model = "opus", effort = "medium")
        return base.copy(tiles = listOf(main) + base.tiles, tabs = base.tabs + ("t0" to TabView(main, if (bullets) talk + bulletTalk else talk)))
    }

    /**
     * As on his phone (task 943): his first tab is a subject's, IMAGES BOOK, and the main chat is a later tab the server
     * names, here not even called Jarvis. The main chat shows [talk]; IMAGES BOOK stays a thread with its own words.
     */
    fun foldBookFirst(n: Int = 6): DeckState {
        val base = fold(n)
        val book = base.tiles.first().copy(name = "IMAGES BOOK")
        val main = TileView("t313", 40, "Home", model = "opus", effort = "medium", main = true)
        val bookTalk = TabView(book, listOf(Entry.Yours("b1", "make the cover darker", offset = 5, at = tonight(0, 30)), Entry.Answer("b2", 9, "Darker cover, trying three.", at = tonight(0, 30))))
        return base.copy(
            tiles = listOf(book) + base.tiles.drop(1) + main,
            tabs = base.tabs + (book.id to bookTalk) + ("t313" to TabView(main, talk)),
        )
    }

    /**
     * As the tab server's library answers the move key (the jarvis repo's `tabs/src/server/library.rs` subject): the
     * subject of his message at [at] (a reply: his message before it), else his newest tagged one; his messages with
     * that tag and Jarvis's replies to each. By the tags alone.
     */
    fun subjectOf(entries: List<Entry>, tags: Map<Long, String>, at: Long?): Pair<String, List<Long>> {
        val said = entries.mapNotNull { e ->
            when (e) {
                is Entry.Yours -> e.offset?.let { Triple(it, true, e) }
                is Entry.Answer -> e.offset?.let { Triple(it, false, e) }
                else -> null
            }
        }
        val anchor = said.filter { (o, his) -> his && (at == null || o <= at) }.asReversed().firstNotNullOfOrNull { tags[it.first] } ?: return "" to emptyList()
        var on = false
        val offsets = said.mapNotNull { (o, his) ->
            if (his) on = tags[o] == anchor
            o.takeIf { on }
        }
        return anchor to offsets
    }
}

/** The screens with no server: answers are made up here, so everything can be tried by hand. */
class FakeDeck(start: DeckState = Fake.deck()) : Deck {
    private val flow = MutableStateFlow(start)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    override val state: StateFlow<DeckState> = flow
    private var counter = 1000

    override fun show(ids: List<String>) {}

    private var looking: String? = null

    override fun here(id: String?) {
        looking = id
    }

    override fun words(send: Boolean) = flow.update { it.copy(heard = "") }

    /** As the server, his words collecting while he dictates. */
    fun heard(words: String) = flow.update { it.copy(heard = words) }

    override fun edit() = flow.update { s ->
        val id = looking ?: WALL
        val was = s.typed[id].orEmpty()
        s.copy(heard = "", dictating = false, typed = s.typed + (id to listOf(was, s.heard).filter { it.isNotBlank() }.joinToString(" ")))
    }

    override fun stop(id: String) {}

    /** As the server: held words wait, marked, until a send-now. */
    override fun hold(id: String, text: String, quotes: List<Quoted>) {
        edit(id) { t -> t.copy(entries = t.entries + Entry.Yours("y${++counter}", text, quotes = quotes, state = Sending.Sent), waiting = true) }
    }

    override fun release(id: String) {
        edit(id) { it.copy(waiting = false) }
    }

    override fun file(id: String, name: String) {}

    override fun send(id: String?, text: String, quotes: List<Quoted>) {
        flow.update { it.copy(attached = emptyList()) }
        if (id != null) say(id, text, quotes) else start(text)
    }

    private fun start(text: String) {
        val id = "t${++counter}"
        val tile = TileView(id, (flow.value.tiles.maxOfOrNull { it.n } ?: 0) + 1, text.take(24), working = true, model = "opus", effort = "medium")
        flow.update {
            it.copy(
                tiles = it.tiles + tile,
                tabs = it.tabs + (id to TabView(tile)),
                opened = id,
            )
        }
        say(id, text)
    }

    private fun say(id: String, text: String, quotes: List<Quoted> = emptyList()) {
        val key = "y${++counter}"
        edit(id) { t ->
            val asked = t.entries.map { if (it is Entry.Question) it.copy(closed = true) else it }
            t.copy(tile = t.tile.copy(needs = false), entries = asked + Entry.Yours(key, text, quotes = quotes, state = Sending.Sending))
        }
        flow.update { s -> s.copy(tiles = s.tiles.map { if (it.id == id) it.copy(needs = false) else it }) }
        scope.launch {
            delay(300)
            edit(id) { t ->
                t.copy(
                    entries = t.entries.map { if (it.key == key) (it as Entry.Yours).copy(state = Sending.Sent) else it },
                    work = WorkView("Thinking it over", System.currentTimeMillis()),
                )
            }
            delay(1500)
            val answer = "Understood. This is a made-up answer, arriving a few words at a time, so the page can be tried with nothing behind it."
            val akey = "a${++counter}"
            val words = answer.split(' ')
            for (i in 1..words.size) {
                edit(id) { t ->
                    val live = Entry.Answer(akey, null, words.take(i).joinToString(" "))
                    t.copy(entries = t.entries.filterNot { it.key == akey } + live, work = null)
                }
                delay(45)
            }
            edit(id) { t -> t.copy(entries = t.entries.map { if (it.key == akey) (it as Entry.Answer).copy(offset = counter.toLong()) else it }) }
        }
    }

    private fun edit(id: String, f: (TabView) -> TabView) = flow.update { s ->
        val tab = s.tabs[id] ?: return@update s
        s.copy(tabs = s.tabs + (id to f(tab)))
    }

    override fun type(id: String, text: String) = flow.update { it.copy(typed = it.typed + (id to text)) }

    override fun marks(id: String, marks: List<QuoteMark>) = flow.update { it.copy(marks = if (marks.isEmpty()) it.marks - id else it.marks + (id to marks)) }

    override fun seen(id: String) = flow.update { s ->
        val tile = s.tiles.firstOrNull { it.id == id } ?: return@update s
        if (!tile.unread) return@update s
        s.copy(tiles = s.tiles.map { if (it.id == id) it.copy(unread = false) else it })
    }

    override fun close(id: String) = flow.update { s ->
        val t = s.tiles.firstOrNull { it.id == id } ?: return@update s
        s.copy(tiles = s.tiles - t, closed = listOf(ClosedTab(id, t.name, "Just closed")) + s.closed)
    }

    override fun reopen(id: String) = flow.update { s ->
        val c = s.closed.firstOrNull { it.id == id } ?: return@update s
        val tile = s.tabs[id]?.tile ?: TileView(id, (s.tiles.maxOfOrNull { it.n } ?: 0) + 1, c.name)
        s.copy(tiles = s.tiles + tile, closed = s.closed - c, tabs = s.tabs + (id to (s.tabs[id] ?: TabView(tile))))
    }

    override fun move(id: String, to: Int) = flow.update { s ->
        val t = s.tiles.firstOrNull { it.id == id } ?: return@update s
        val rest = s.tiles - t
        s.copy(tiles = rest.take(to) + t + rest.drop(to))
    }

    override fun mode(id: String, model: String, effort: String) = flow.update { s ->
        s.copy(
            tiles = s.tiles.map { if (it.id == id) it.copy(model = model, effort = effort) else it },
            tabs = s.tabs.mapValues { (k, v) -> if (k == id) v.copy(tile = v.tile.copy(model = model, effort = effort)) else v },
        )
    }

    override fun openedDone() = flow.update { it.copy(opened = null) }

    override fun landed(id: String) = flow.update { it.copy(opened = id) }

    override fun dictate() = flow.update { it.copy(dictating = !it.dictating) }

    /** The tab each press of Call asked for, null for a new one. */
    val calls = mutableListOf<String?>()

    override fun call(id: String?) {
        calls += id
    }

    override fun openCall() {}

    override fun pick() {}

    override fun detach(path: String) = flow.update { s -> s.copy(attached = s.attached.filterNot { it.path == path }) }

    override fun home() {}

    override fun forum() {}

    /** Each answer given, as the server would get it: the card's key, the pick, the note. */
    val answers = mutableListOf<Triple<String, Int?, String>>()

    /** Each answer to a card of several questions, as sent: the card's key, the picks, the words. */
    val each = mutableListOf<Triple<String, List<UInt>, String>>()

    override fun answerEach(card: CardView, picks: List<UInt>, text: String) {
        each += Triple(card.key, picks, text)
        answer(card, picks.firstOrNull()?.takeIf { it != UInt.MAX_VALUE }?.toInt(), text)
    }

    override fun answer(card: CardView, pick: Int?, note: String) {
        answers += Triple(card.key, pick, note)
        val keys = card.tabs.map { "${it.id}/${it.q}" }.toSet()
        flow.update { s ->
            s.copy(
                cards = s.cards.filterNot { it.key == card.key },
                tabs = s.tabs.mapValues { (_, t) -> t.copy(asks = t.asks.filterNot { a -> a.tabs.any { "${it.id}/${it.q}" in keys } }) },
            )
        }
    }


    override fun noticed() = flow.update { it.copy(notice = null) }

    /** As the server: the subject by the tags of [Fake.talkTags]. */
    override fun subject(id: String, at: Long?) {
        val m = Moving(id, at)
        val (name, offsets) = Fake.subjectOf(flow.value.tabs[id]?.entries.orEmpty(), Fake.talkTags, at)
        flow.update { it.copy(moving = m).subjected(m, name, offsets, "") }
    }

    /** As the server: a new thread on the subject; the messages stay in the main chat's scrollback. */
    override fun moveSubject() {
        val m = flow.value.moving?.takeIf { it.lit } ?: return
        val id = "t${++counter}"
        val tile = TileView(id, (flow.value.tiles.maxOfOrNull { it.n } ?: 0) + 1, m.name, model = "opus", effort = "medium")
        flow.update { s -> s.copy(tiles = s.tiles + tile, tabs = s.tabs + (id to TabView(tile))).split(m.tab, m.name, m.offsets.size, "") }
    }

    override fun keepSubject() = flow.update { it.copy(moving = null) }
}
