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

    /** A reply half written on the piano answer. */
    val draft = ReplyDraft(
        41,
        notes = mapOf(
            1 to Note("", "So the sustain was the whole problem?"),
            9 to Note("a slash chord played very softly", "How soft is very softly?"),
        ),
    )

    fun deck(n: Int = 9, online: Boolean = true, muted: Boolean = false, drafts: Map<String, ReplyDraft> = emptyMap()): DeckState {
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
            muted = muted,
            drafts = drafts,
        )
    }
}

/** The screens with no server: answers are made up here, so everything can be tried by hand. */
class FakeDeck(start: DeckState = Fake.deck()) : Deck {
    private val flow = MutableStateFlow(start)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    override val state: StateFlow<DeckState> = flow
    private var counter = 1000

    override fun show(ids: List<String>) {}

    override fun start(text: String) {
        val id = "t${++counter}"
        val tile = TileView(id, (flow.value.tiles.maxOfOrNull { it.n } ?: 0) + 1, text.take(24), working = true)
        flow.update {
            it.copy(
                tiles = it.tiles + tile,
                tabs = it.tabs + (id to TabView(tile)),
                opened = id,
            )
        }
        say(id, text)
    }

    override fun say(id: String, text: String, quotes: List<Quoted>) {
        val key = "y${++counter}"
        edit(id) { it.copy(entries = it.entries + Entry.Yours(key, text, quotes = quotes, state = Sending.Sending)) }
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

    override fun draft(id: String, draft: ReplyDraft?) = flow.update {
        it.copy(drafts = if (draft == null || draft.empty && draft.notes.isEmpty()) it.drafts - id else it.drafts + (id to draft))
    }

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

    override fun mute(on: Boolean) = flow.update { it.copy(muted = on) }

    override fun openedDone() = flow.update { it.copy(opened = null) }
}
