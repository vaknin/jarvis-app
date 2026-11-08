package com.kivan.jarvis.tabs

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.kivan.jarvis.CallActivity
import com.kivan.jarvis.CallService
import com.kivan.jarvis.Dictation
import com.kivan.jarvis.Forum
import com.kivan.jarvis.MainActivity
import com.kivan.jarvis.Voice
import com.kivan.jarvis.core.CardRec
import com.kivan.jarvis.core.ClosedRec
import com.kivan.jarvis.core.DigestRec
import com.kivan.jarvis.core.EntryRec
import com.kivan.jarvis.core.IssueRec
import com.kivan.jarvis.core.Landing
import com.kivan.jarvis.core.OutFile
import com.kivan.jarvis.core.QuoteRec
import com.kivan.jarvis.core.TabRec
import com.kivan.jarvis.core.TabsListener
import com.kivan.jarvis.core.TileRec
import com.kivan.jarvis.core.tabsAnswer
import com.kivan.jarvis.core.tabsAttach
import com.kivan.jarvis.core.tabsClose
import com.kivan.jarvis.core.tabsDetach
import com.kivan.jarvis.core.tabsFile
import com.kivan.jarvis.core.tabsHold
import com.kivan.jarvis.core.tabsMode
import com.kivan.jarvis.core.tabsMove
import com.kivan.jarvis.core.tabsMoveSubject
import com.kivan.jarvis.core.tabsSubject
import com.kivan.jarvis.core.tabsRelease
import com.kivan.jarvis.core.tabsReopen
import com.kivan.jarvis.core.tabsSay
import com.kivan.jarvis.core.tabsSeen
import com.kivan.jarvis.core.tabsShow
import com.kivan.jarvis.core.tabsStart
import com.kivan.jarvis.core.tabsStop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The tabs on the server, through the Rust client (crates/ffi tabs.rs). Everything that decides lives
 * there; this keeps only what is the phone's own: his unsent words, his drafts, what he attached, and
 * the last wall, so the screen opens full and a restart loses no typing. Calls arrive on Rust's threads.
 */
class RealDeck private constructor(
    private val app: Context,
    private val prefs: SharedPreferences,
    private val files: File,
) : Deck, TabsListener {
    private val flow = MutableStateFlow(Saved.read(prefs).let { s -> s.copy(attached = s.attached.filter { File(it.path).exists() }, fold = Fold.on(app)) })
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override val state: StateFlow<DeckState> = flow

    /** What only the screen can ask Android for. */
    enum class Ask { MIC, PICK }

    private val asking = MutableSharedFlow<Ask>(extraBufferCapacity = 1)
    val asks: SharedFlow<Ask> = asking

    /** The tab on screen; null at the wall. */
    private var looking: String? = null
    /** Shared words waiting in the wall's box: they go with him to the tab he picks. */
    private var shared: String? = null
    /** What went with the words that are opening a new tab. */
    @Volatile private var opening: List<Attached> = emptyList()

    init {
        @OptIn(FlowPreview::class)
        flow.map(Saved::of).distinctUntilChanged().debounce(400).onEach { it.write(prefs) }.launchIn(scope)
        Voice.ui.combine(Voice.place) { ui, at -> OnCall(at.tab, at.name, Voice.line(ui)).takeIf { ui.live } }
            .distinctUntilChanged().onEach { call -> flow.update { it.copy(call = call) } }.launchIn(scope)
        Dictation.on.onEach { on -> flow.update { it.copy(dictating = on) } }.launchIn(scope)
        Voice.heard.onEach { words -> flow.update { it.copy(heard = words) } }.launchIn(scope)
        Voice.movedTo.filterNotNull().onEach {
            landed(it)
            Voice.movedTo.value = null
        }.launchIn(scope)
    }

    fun up() {
        // Opened from a notification with nothing else running, or without the mic.
        runCatching { Forum.start() }
        tabsAttach(this)
        scope.launch { Tray.prune(app, flow.value.attached) }
    }

    fun down() {
        // Leaving the app: what he held goes to the server, still held, so it rides along on a turn that
        // runs with the phone away (a job's report), or waits for his send-all. It never starts one.
        var going = emptyMap<String, List<Quoted>>()
        flow.update { s -> s.quietAll().also { going = it.second }.first }
        for ((id, quotes) in going) hold(id, "", quotes)
        tabsDetach()
        Saved.of(flow.value).write(prefs)
    }

    override fun wall(tiles: List<TileRec>, closed: List<ClosedRec>, online: Boolean) = moved { it.walled(tiles, closed, online) }

    override fun tab(tab: TabRec) = moved { it.with(tab) }

    /** A change from the server. A turn's end takes nothing he held: sent then, each reply started a turn (t104). */
    private fun moved(change: (DeckState) -> DeckState) = flow.update(change)

    override fun opened(id: String) {
        val going = opening
        opening = emptyList()
        flow.update { it.copy(opened = id, files = it.files + going.associate { f -> "$id/${f.name}" to f.path }) }
    }

    override fun back(id: String, text: String) = flow.update { it.typedBack(id, text) }

    override fun queue(cards: List<CardRec>, digest: List<DigestRec>) = flow.update { s ->
        s.copy(cards = needsYou(cards.map(::card)), digest = digest.map { DigestView(it.id, it.name, it.at.toLong(), it.offset.toLong(), it.what, it.line) })
    }

    override fun refused(id: String, why: String) = flow.update { it.copy(notice = Notice(id, why)) }

    override fun fold(on: Boolean) {
        Fold.set(app, on)
        flow.update { it.copy(fold = on) }
    }

    // The ffi hides the card at once, everywhere, and brings it back if the server says no.
    override fun answer(card: CardView, pick: Int?, note: String) {
        val tab = card.tabs.firstOrNull() ?: return
        flow.update { s -> s.copy(notice = null, cards = s.cards.filterNot { it.key == card.key }) }
        tabsAnswer(tab.id, tab.q, listOfNotNull(pick?.toUInt()), note.trim())
    }

    override fun answerEach(card: CardView, picks: List<UInt>, text: String) {
        val tab = card.tabs.firstOrNull() ?: return
        flow.update { s -> s.copy(notice = null, cards = s.cards.filterNot { it.key == card.key }) }
        tabsAnswer(tab.id, tab.q, picks, text)
    }

    override fun noticed() = flow.update { it.copy(notice = null) }

    // The move key: the server's answer is the only thing that lights up, on its own short link off the main thread.
    override fun subject(id: String, at: Long?) {
        val m = Moving(id, at)
        flow.update { it.copy(moving = m, notice = null) }
        scope.launch {
            val got = tabsSubject(id, at?.toULong())
            flow.update { s -> if (s.moving != m) s else s.subjected(m, got.name, got.offsets.map { it.toLong() }, got.why) }
        }
    }

    override fun moveSubject() {
        val m = flow.value.moving?.takeIf { it.lit } ?: return
        val sent = m.copy(sent = true)
        flow.update { it.copy(moving = sent) }
        scope.launch {
            val got = tabsMoveSubject(m.tab, m.at?.toULong(), m.offsets.map { it.toULong() })
            flow.update { s -> s.split(m.tab, got.name, got.moved.toInt(), got.why) }
        }
    }

    override fun keepSubject() = flow.update { it.copy(moving = null) }

    override fun show(ids: List<String>) = tabsShow(ids)

    override fun unsent(files: List<OutFile>) = flow.update { s ->
        s.copy(attached = s.attached + files.filter { f -> s.attached.none { it.path == f.path } }.map { Attached(it.path, it.name, Tray.picture(it.name)) })
    }

    override fun send(id: String?, text: String, quotes: List<Quoted>) {
        val going = flow.value.attached
        val out = going.map { OutFile(it.name, it.path) }
        val replies = quotes.map { QuoteRec(it.to.toULong(), it.quote, it.text) }
        if (going.isEmpty()) {
            if (id == null) tabsStart(text, out) else tabsSay(id, text, replies, out)
            return
        }
        // The waiting card shows the phone's own copy until the record has it.
        flow.update { s ->
            s.copy(attached = emptyList(), files = if (id == null) s.files else s.files + going.associate { "$id/${it.name}" to it.path })
        }
        if (id == null) opening = going
        // The files are read here, so off the main thread.
        scope.launch { if (id == null) tabsStart(text, out) else tabsSay(id, text, replies, out) }
    }

    override fun type(id: String, text: String) = flow.update { it.copy(typed = if (text.isEmpty()) it.typed - id else it.typed + (id to text)) }

    override fun marks(id: String, marks: List<QuoteMark>) = flow.update { it.copy(marks = if (marks.isEmpty()) it.marks - id else it.marks + (id to marks)) }

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

    override fun mode(id: String, model: String, effort: String) {
        flow.update { s ->
            s.copy(
                tiles = s.tiles.map { if (it.id == id) it.copy(model = model, effort = effort) else it },
                tabs = s.tabs.mapValues { (k, v) -> if (k == id) v.copy(tile = v.tile.copy(model = model, effort = effort)) else v },
            )
        }
        tabsMode(id, model, effort)
    }

    override fun here(id: String?) {
        looking = id
        if (id != null) carryShared(id)
        val at = Voice.place.value
        val tile = flow.value.tiles.firstOrNull { it.id == id }
        val name = tile?.let { it.name.ifBlank { "Tab ${it.n}" } }.orEmpty()
        if (!at.stay && at.tab == id) {
            // Words at the wall opened it: he's already there, and now it has its name.
            if (name.isNotEmpty() && name != at.name) Voice.place.value = at.copy(name = name)
            return
        }
        // A call stays in its tab while he looks around.
        if (Voice.ui.value.live) return
        // They stay in the box of the tab they were said on, for him to edit and send.
        if (Voice.heard.value.isNotEmpty()) Dictation.stop()
        Voice.place.value = Voice.Place(id, name, stay = false)
    }

    private fun carryShared(id: String) {
        val words = shared ?: return
        shared = null
        if (flow.value.typed[WALL] != words) return
        type(WALL, "")
        kept(id, words)
    }

    override fun words(send: Boolean) {
        (Dictation.call ?: Voice.call)?.sendWords(send)
        if (Dictation.call != null) Voice.heard.value = ""
    }

    // Stopping keeps every word: the line closes after the last ones land, and they go into the box.
    override fun edit() = Dictation.stop()

    /** Words that didn't go stay in that box. */
    fun kept(id: String, words: String) = flow.update {
        val was = it.typed[id].orEmpty()
        it.copy(typed = it.typed + (id to if (was.isBlank()) words else "$was $words"))
    }

    /** Copies these in while the grant lasts; they go with his next words. */
    suspend fun attach(uris: List<Uri>) {
        val more = uris.mapNotNull { Tray.keep(app, it) }
        flow.update { it.copy(attached = it.attached + more) }
    }

    /** Shared words: in the wall's box, and on to the tab he picks. */
    fun share(text: String) {
        val was = flow.value.typed[WALL].orEmpty()
        val now = if (was.isBlank()) text else "$was $text"
        type(WALL, now)
        shared = now
    }

    override fun dictate() {
        if (Voice.ui.value.live) return
        if (!CallService.micGranted(app)) {
            asking.tryEmit(Ask.MIC)
            return
        }
        here(looking)
        Dictation.toggle(app)
    }

    override fun call(id: String?) {
        val name = flow.value.tiles.firstOrNull { it.id == id }?.let { it.name.ifBlank { "Tab ${it.n}" } }.orEmpty()
        app.startActivity(CallActivity.intent(app, start = true, tab = id ?: Voice.NEW_TAB, name = name))
    }

    override fun openCall() = app.startActivity(CallActivity.intent(app, start = false))

    override fun pick() {
        asking.tryEmit(Ask.PICK)
    }

    override fun detach(path: String) {
        flow.update { s -> s.copy(attached = s.attached.filterNot { it.path == path }) }
        File(path).parentFile?.deleteRecursively()
    }

    override fun home() = app.startActivity(MainActivity.home(app))

    override fun forum() = app.startActivity(Forum.view(app, "/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun stop(id: String) = tabsStop(id)

    override fun hold(id: String, text: String, quotes: List<Quoted>) = tabsHold(id, text, quotes.map { QuoteRec(it.to.toULong(), it.quote, it.text) })

    override fun release(id: String) = tabsRelease(id)

    override fun landed(id: String) = flow.update { it.copy(opened = id) }

    override fun file(id: String, name: String) = tabsFile(id, name)

    override fun file(id: String, name: String, data: ByteArray) {
        scope.launch {
            val path = if (data.isEmpty()) "" else fileAt(files, id, name).also { it.parentFile?.mkdirs(); it.writeBytes(data) }.path
            // A file on its way up is on the phone, whatever the server says of it yet.
            flow.update { it.copy(files = if (path.isEmpty() && !it.files["$id/$name"].isNullOrEmpty()) it.files else it.files + ("$id/$name" to path)) }
        }
    }

    override fun openedDone() = flow.update { it.copy(opened = null) }

    companion object {
        @Volatile private var one: RealDeck? = null

        fun get(context: Context): RealDeck = one ?: synchronized(this) {
            val app = context.applicationContext
            one ?: RealDeck(app, app.getSharedPreferences("tabs", Context.MODE_PRIVATE), File(app.cacheDir, TAB_FILES)).also { one = it }
        }
    }
}

/** Where a file from the server lands: its name is the server's, so it can't climb out of [dir]. */
internal fun fileAt(dir: File, id: String, name: String): File {
    val safe = name.replace('/', '_').replace('\\', '_').takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
    return File(File(dir, "%08x".format("$id/$name".hashCode())), safe)
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
        tab.jobs,
        tab.waiting,
        tab.asks.map(::card),
        tab.issues.map(::issue),
    )
    return copy(tabs = tabs + (id to view)).foldDrafts(id)
}

internal fun issue(r: IssueRec) =
    IssueView(r.id, r.title, r.board, r.step.toInt(), r.tryN.toInt(), r.label, r.held, r.eta, r.detail)

/** How much of a whole answer travels as its quote: enough to say which one, the session has the rest. */
private const val WHOLE_ANSWER = 280

/**
 * What a draft from the old reply page sends: the quotes, and the words that follow them. A paragraph
 * he wrote under quotes that paragraph, and words he picked go even with nothing written under them.
 * Words with no quote at all still answer that answer, so they quote it.
 */
internal fun replyPieces(to: Long, blocks: List<Block>, draft: ReplyDraft): Pair<List<Quoted>, String> {
    val end = draft.end.trim()
    val quotes = draft.notes.toSortedMap().filterValues { it.text.isNotBlank() || it.quote.isNotEmpty() }.map { (i, n) ->
        Quoted(to, n.quote.ifEmpty { blocks.getOrNull(i)?.plain.orEmpty() }, n.text.trim())
    }
    val whole = blocks.joinToString(" ") { it.plain }
    return when {
        quotes.isEmpty() && end.isNotEmpty() && whole.isNotEmpty() -> {
            val quote = if (whole.length > WHOLE_ANSWER) whole.take(WHOLE_ANSWER).trimEnd() + "…" else whole
            listOf(Quoted(to, quote, end)) to ""
        }
        else -> quotes to end
    }
}

/** Drafts written on the reply page that's gone become held replies once their answer is here, so none is lost. */
internal fun DeckState.foldDrafts(id: String): DeckState {
    var out = this
    for ((key, draft) in drafts) {
        if (!key.startsWith("$id/")) continue
        val answer = tabs[id]?.entries?.firstOrNull { it is Entry.Answer && it.offset == draft.to } as Entry.Answer? ?: continue
        val blocks = Prose.parse(answer.text)
        val (quotes, end) = replyPieces(draft.to, blocks, draft)
        val held = quotes.fold(out.marks[id].orEmpty()) { all, q ->
            val mark = QuoteMark(q.to, blocks.place(q.quote), q.quote, q.text)
            // Words already held on it stay, with these after them.
            val words = listOfNotNull(all.firstOrNull { it.same(mark) }?.text, q.text).filter { it.isNotBlank() }
            all.with(mark.copy(text = words.joinToString("\n\n")))
        }
        val typed = out.typed[id].orEmpty()
        out = out.copy(
            drafts = out.drafts - key,
            marks = if (held.isEmpty()) out.marks else out.marks + (id to held),
            typed = if (end.isEmpty()) out.typed else out.typed + (id to if (typed.isBlank()) end else "$typed\n\n$end"),
        )
    }
    return out
}

/** Every quiet reply he holds, in every tab: leaving the app hands them to the server, still held. One open for editing isn't quiet until he holds it again. */
internal fun DeckState.quietAll() = quietTaken(marks.keys)

private fun DeckState.quietTaken(ids: Collection<String>): Pair<DeckState, Map<String, List<Quoted>>> {
    val going = ids.associateWith { id -> marks[id].orEmpty().filter { it.quiet && it.held } }.filterValues { it.isNotEmpty() }
    if (going.isEmpty()) return this to emptyMap()
    val left = going.entries.fold(marks) { all, (id, gone) ->
        val rest = all[id].orEmpty() - gone.toSet()
        if (rest.isEmpty()) all - id else all + (id to rest)
    }
    return copy(marks = left) to going.mapValues { (_, l) -> l.map { Quoted(it.to, it.quote, it.text.trim()) } }
}

/** Words that didn't land go back in the box, above anything typed since. */
internal fun DeckState.typedBack(id: String, text: String): DeckState {
    val now = typed[id].orEmpty()
    return copy(typed = typed + (id to if (now.isBlank()) text else "$text\n\n$now"))
}

private fun view(t: TileRec) = TileView(t.id, t.n.toInt(), t.name, t.needs, t.unread, t.working, t.mode, t.model, t.effort, t.autopilot)

internal fun card(c: CardRec) = CardView(
    c.tabs.map { CardTab(it.id, it.name, it.q) },
    c.text,
    c.options,
    c.pick.toInt(),
    c.why,
    when (c.level) {
        com.kivan.jarvis.core.Level.LOW -> Level.Low
        com.kivan.jarvis.core.Level.URGENT -> Level.Urgent
        else -> Level.Default
    },
    c.due?.toLong(),
    c.at.toLong(),
    c.items.map { Question(it.text, it.options, it.pick.toInt(), it.why) }.ifEmpty { listOf(Question(c.text, c.options, c.pick.toInt(), c.why)) },
)

/** Urgent first, then by when Jarvis takes its pick (those waiting on him before), low last. */
internal fun needsYou(cards: List<CardView>) = cards.sortedWith(
    compareBy({ if (it.level == Level.Urgent) 0 else if (it.level == Level.Low) 2 else 1 }, { it.due ?: 0L }, { it.at }),
)

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
        e.offset?.toLong(),
        e.at.toLong(),
    )
    is EntryRec.Answer -> Entry.Answer(e.key, e.offset?.toLong(), e.text, e.files, e.at.toLong())
    is EntryRec.Report -> Entry.Report(e.key, e.from, e.line, e.text)
    is EntryRec.Question -> Entry.Question(e.key, e.text, e.closed)
    is EntryRec.Quiet -> Entry.Quiet(e.key, e.text)
}

/** What outlives the process, as one JSON value in the prefs. */
internal data class Saved(
    val tiles: List<TileView>,
    val typed: Map<String, String>,
    val drafts: Map<String, ReplyDraft>,
    val attached: List<Attached>,
    val marks: Map<String, List<QuoteMark>> = emptyMap(),
) {
    fun json(): String = JSONObject().apply {
        put("tiles", tiles.fold(org.json.JSONArray()) { a, t ->
            a.put(JSONObject().put("id", t.id).put("n", t.n).put("name", t.name).put("needs", t.needs)
                .put("unread", t.unread))
        })
        put("typed", JSONObject(typed))
        put("drafts", JSONObject().also { all ->
            for ((id, d) in drafts) {
                val notes = JSONObject()
                for ((i, n) in d.notes) notes.put("$i", JSONObject().put("quote", n.quote).put("text", n.text))
                all.put(id, JSONObject().put("to", d.to).put("end", d.end).put("notes", notes))
            }
        })
        put("attached", attached.fold(org.json.JSONArray()) { a, f ->
            a.put(JSONObject().put("path", f.path).put("name", f.name).put("picture", f.picture))
        })
        put("marks", JSONObject().also { all ->
            for ((id, list) in marks) {
                all.put(id, list.fold(org.json.JSONArray()) { a, m ->
                    a.put(JSONObject().put("to", m.to).put("block", m.block).put("quote", m.quote).put("text", m.text).put("quiet", m.quiet))
                })
            }
        })
        // Gone as a concept, but an older build refuses a value without it: rolling back keeps the words.
        put("muted", false)
    }.toString()

    fun write(prefs: SharedPreferences) = prefs.edit().putString(KEY, json()).apply()

    companion object {
        private const val KEY = "deck"

        fun of(s: DeckState) = Saved(s.tiles, s.typed, s.drafts, s.attached, s.marks)

        fun read(prefs: SharedPreferences): DeckState = prefs.getString(KEY, null)?.let(::parse) ?: DeckState()

        /** A value that doesn't parse costs the saved words, never the screen. */
        fun parse(text: String): DeckState? = runCatching {
            val o = JSONObject(text)
            val tiles = o.getJSONArray("tiles")
            val typed = o.getJSONObject("typed")
            val drafts = o.getJSONObject("drafts")
            val attached = o.optJSONArray("attached")
            val marks = o.optJSONObject("marks")
            DeckState(
                tiles = (0 until tiles.length()).map { i ->
                    val t = tiles.getJSONObject(i)
                    // A saved tile never shows work: it would glide with nothing behind it.
                    TileView(t.getString("id"), t.getInt("n"), t.getString("name"), t.getBoolean("needs"), t.getBoolean("unread"), false)
                },
                typed = typed.keys().asSequence().associateWith { typed.getString(it) },
                drafts = drafts.keys().asSequence().associate { key ->
                    val d = drafts.getJSONObject(key)
                    val notes = d.getJSONObject("notes")
                    // Older builds kept one draft a tab, under the tab's id alone.
                    (if ('/' in key) key else draftKey(key, d.getLong("to"))) to ReplyDraft(
                        d.getLong("to"),
                        notes.keys().asSequence().associate { i ->
                            val n = notes.getJSONObject(i)
                            i.toInt() to Note(n.getString("quote"), n.getString("text"))
                        },
                        d.getString("end"),
                    )
                },
                attached = (0 until (attached?.length() ?: 0)).map { i ->
                    val f = attached!!.getJSONObject(i)
                    Attached(f.getString("path"), f.getString("name"), f.getBoolean("picture"))
                },
                marks = marks?.keys()?.asSequence().orEmpty().associateWith { id ->
                    val list = marks!!.getJSONArray(id)
                    (0 until list.length()).map { i ->
                        val m = list.getJSONObject(i)
                        QuoteMark(m.getLong("to"), m.getInt("block"), m.getString("quote"), m.optString("text"), m.optBoolean("quiet"))
                    }
                },
            )
        }.getOrNull()
    }
}
