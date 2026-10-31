package com.kivan.jarvis.tabs

import android.content.Context
import android.content.SharedPreferences
import com.kivan.jarvis.core.ClosedRec
import com.kivan.jarvis.core.EntryRec
import com.kivan.jarvis.core.Landing
import com.kivan.jarvis.core.QuoteRec
import com.kivan.jarvis.core.TabRec
import com.kivan.jarvis.core.TabsListener
import com.kivan.jarvis.core.TileRec
import com.kivan.jarvis.core.tabsAttach
import com.kivan.jarvis.core.tabsClose
import com.kivan.jarvis.core.tabsDetach
import com.kivan.jarvis.core.tabsMove
import com.kivan.jarvis.core.tabsReopen
import com.kivan.jarvis.core.tabsSay
import com.kivan.jarvis.core.tabsSeen
import com.kivan.jarvis.core.tabsShow
import com.kivan.jarvis.core.tabsStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * The tabs on the server, through the Rust client (crates/ffi tabs.rs). Everything that decides lives
 * there; this keeps only what is the phone's own: his unsent words, his drafts, the mute, and the last
 * wall, so the screen opens full and a restart loses no typing. Calls arrive on Rust's threads.
 */
class RealDeck private constructor(private val prefs: SharedPreferences) : Deck, TabsListener {
    private val flow = MutableStateFlow(Saved.read(prefs))
    override val state: StateFlow<DeckState> = flow

    init {
        @OptIn(FlowPreview::class)
        flow.map(Saved::of).distinctUntilChanged().debounce(400).onEach { it.write(prefs) }
            .launchIn(CoroutineScope(Dispatchers.IO + SupervisorJob()))
    }

    fun up() = tabsAttach(this)

    fun down() {
        tabsDetach()
        Saved.of(flow.value).write(prefs)
    }

    override fun wall(tiles: List<TileRec>, closed: List<ClosedRec>, online: Boolean) = flow.update { it.walled(tiles, closed, online) }

    override fun tab(tab: TabRec) = flow.update { it.with(tab) }

    override fun opened(id: String) = flow.update { it.copy(opened = id) }

    override fun back(id: String, text: String) = flow.update { it.typedBack(id, text) }

    override fun show(ids: List<String>) = tabsShow(ids)

    override fun start(text: String) = tabsStart(text)

    override fun say(id: String, text: String, quotes: List<Quoted>) =
        tabsSay(id, text, quotes.map { QuoteRec(it.to.toULong(), it.quote, it.text) })

    override fun type(id: String, text: String) = flow.update { it.copy(typed = if (text.isEmpty()) it.typed - id else it.typed + (id to text)) }

    override fun draft(id: String, draft: ReplyDraft?) = flow.update {
        it.copy(drafts = if (draft == null || draft.empty && draft.notes.isEmpty()) it.drafts - id else it.drafts + (id to draft))
    }

    // Seen, close and move show at once and go to the server behind; its next wall has the last word.
    override fun seen(id: String) {
        flow.update { s -> s.copy(tiles = s.tiles.map { if (it.id == id) it.copy(unread = false) else it }) }
        tabsSeen(id)
    }

    override fun close(id: String) {
        flow.update { s -> s.copy(tiles = s.tiles.filterNot { it.id == id }) }
        tabsClose(id)
    }

    override fun reopen(id: String) = tabsReopen(id)

    override fun move(id: String, to: Int) {
        flow.update { s ->
            val t = s.tiles.firstOrNull { it.id == id } ?: return@update s
            val rest = s.tiles - t
            s.copy(tiles = rest.take(to) + t + rest.drop(to))
        }
        tabsMove(id, to.toUInt())
    }

    // The mic is step 5: until then the mute is only the button's state.
    override fun mute(on: Boolean) = flow.update { it.copy(muted = on) }

    override fun openedDone() = flow.update { it.copy(opened = null) }

    companion object {
        @Volatile private var one: RealDeck? = null

        fun get(context: Context): RealDeck = one ?: synchronized(this) {
            one ?: RealDeck(context.applicationContext.getSharedPreferences("tabs", Context.MODE_PRIVATE)).also { one = it }
        }
    }
}

internal fun DeckState.walled(tiles: List<TileRec>, closed: List<ClosedRec>, online: Boolean): DeckState {
    // An empty wall with no link says nothing about the server: the last wall stays up.
    if (tiles.isEmpty() && !online) return copy(online = false)
    val views = tiles.map(::view)
    val byId = views.associateBy { it.id }
    return copy(
        tiles = views,
        closed = closed.map { ClosedTab(it.id, it.name, it.line) },
        online = online,
        tabs = tabs.mapValues { (id, t) -> byId[id]?.let { t.copy(tile = it) } ?: t },
    )
}

internal fun DeckState.with(tab: TabRec): DeckState {
    val id = tab.tile.id
    val kept = tabs[id]?.entries.orEmpty().take(tab.from.toInt())
    val view = TabView(
        view(tab.tile),
        kept + tab.entries.map(::entry),
        if (tab.working) WorkView(tab.what, tab.since.toLong()) else null,
        tab.closed,
        tab.loading,
    )
    return copy(tabs = tabs + (id to view))
}

/** Words that didn't land go back in the box, above anything typed since. */
internal fun DeckState.typedBack(id: String, text: String): DeckState {
    val now = typed[id].orEmpty()
    return copy(typed = typed + (id to if (now.isBlank()) text else "$text\n\n$now"))
}

private fun view(t: TileRec) = TileView(t.id, t.n.toInt(), t.name, t.needs, t.unread, t.working, t.readOnly)

private fun entry(e: EntryRec): Entry = when (e) {
    is EntryRec.Yours -> Entry.Yours(
        e.key,
        e.text,
        e.voice,
        e.quotes.map { Quoted(it.to.toLong(), it.quote, it.text) },
        e.files,
        when (e.state) {
            Landing.SENT -> Sending.Sent
            Landing.SENDING -> Sending.Sending
            Landing.REFUSED -> Sending.Refused
        },
        e.why,
    )
    is EntryRec.Answer -> Entry.Answer(e.key, e.offset?.toLong(), e.text, e.files)
    is EntryRec.Report -> Entry.Report(e.key, e.from, e.line, e.text)
    is EntryRec.Question -> Entry.Question(e.key, e.text, e.closed)
    is EntryRec.Quiet -> Entry.Quiet(e.key, e.text)
}

/** What outlives the process, as one JSON value in the prefs. */
internal data class Saved(
    val tiles: List<TileView>,
    val typed: Map<String, String>,
    val drafts: Map<String, ReplyDraft>,
    val muted: Boolean,
) {
    fun json(): String = JSONObject().apply {
        put("tiles", tiles.fold(org.json.JSONArray()) { a, t ->
            a.put(JSONObject().put("id", t.id).put("n", t.n).put("name", t.name).put("needs", t.needs)
                .put("unread", t.unread).put("readOnly", t.readOnly))
        })
        put("typed", JSONObject(typed))
        put("drafts", JSONObject().also { all ->
            for ((id, d) in drafts) {
                val notes = JSONObject()
                for ((i, n) in d.notes) notes.put("$i", JSONObject().put("quote", n.quote).put("text", n.text))
                all.put(id, JSONObject().put("to", d.to).put("end", d.end).put("notes", notes))
            }
        })
        put("muted", muted)
    }.toString()

    fun write(prefs: SharedPreferences) = prefs.edit().putString(KEY, json()).apply()

    companion object {
        private const val KEY = "deck"

        fun of(s: DeckState) = Saved(s.tiles, s.typed, s.drafts, s.muted)

        fun read(prefs: SharedPreferences): DeckState = prefs.getString(KEY, null)?.let(::parse) ?: DeckState()

        /** A value that doesn't parse costs the saved words, never the screen. */
        fun parse(text: String): DeckState? = runCatching {
            val o = JSONObject(text)
            val tiles = o.getJSONArray("tiles")
            val typed = o.getJSONObject("typed")
            val drafts = o.getJSONObject("drafts")
            DeckState(
                tiles = (0 until tiles.length()).map { i ->
                    val t = tiles.getJSONObject(i)
                    // A saved tile never shows work: it would glide with nothing behind it.
                    TileView(t.getString("id"), t.getInt("n"), t.getString("name"), t.getBoolean("needs"), t.getBoolean("unread"), false, t.getBoolean("readOnly"))
                },
                typed = typed.keys().asSequence().associateWith { typed.getString(it) },
                drafts = drafts.keys().asSequence().associateWith { id ->
                    val d = drafts.getJSONObject(id)
                    val notes = d.getJSONObject("notes")
                    ReplyDraft(
                        d.getLong("to"),
                        notes.keys().asSequence().associate { i ->
                            val n = notes.getJSONObject(i)
                            i.toInt() to Note(n.getString("quote"), n.getString("text"))
                        },
                        d.getString("end"),
                    )
                },
                muted = o.getBoolean("muted"),
            )
        }.getOrNull()
    }
}
