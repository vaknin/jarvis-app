package com.kivan.tether.dibs

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

/** Whether the phone can reach the laptop. */
enum class Link { CONNECTED, OFFLINE, UNPAIRED }

/**
 * Everything the dibs screens need from Tether, which holds the link (docs/DIBS-APP.md). The app
 * implements it and sets [Dibs.host] before any dibs screen starts.
 */
interface DibsHost {
    /** The dibs channel's newest view (the whole JSON), or null before one arrived. */
    val view: StateFlow<JSONObject?>
    val link: StateFlow<Link>
    /**
     * Where a picked file is copied at once (a picker's or a share's read grant ends with the
     * screen); [send] takes it from there, and Tether deletes it once the laptop has it.
     */
    val pickDir: java.io.File

    /** Files being sent: Tether's file id → fraction done. */
    val uploads: StateFlow<Map<String, Float>>

    /**
     * A live message on the dibs channel (`{"typing": true}`): never stored or queued, dropped
     * when there is no link. True when it went.
     */
    fun live(data: JSONObject): Boolean = false

    /** Queues an action on the dibs channel (`{"action", "value"}`), with [uid] if given; returns the uid. */
    fun act(action: String, value: JSONObject? = null, uid: String? = null): String

    /**
     * As [act], but returns only once the action is stored in Tether's queue (so a worker or a
     * screen about to close knows it won't be lost); throws when it couldn't be.
     */
    suspend fun actStored(action: String, value: JSONObject? = null): String = act(action, value)

    /**
     * Sends a message with files: copies each, sends it to the dibs channel, then [action] (`say`,
     * or `task-say` with [extra] naming the task) with [uid], the text and the files' ids.
     * [onFile] tells each file's id as it is queued (for its thumbnail).
     */
    fun send(uid: String, text: String, files: List<Uri>, action: String = "say", extra: JSONObject? = null, onFile: (Uri, String) -> Unit)

    /**
     * The newest file dibs sent on its channel whose name starts with [prefix] (a fetched
     * transcript or report), or null; it emits again when a newer one arrives.
     */
    fun channelFile(prefix: String): Flow<java.io.File?>

    /** The thumbnail of an image sent from here, by Tether's file id; null if there is none. Blocking. */
    fun thumb(fileId: String): ImageBitmap?

    /** Bumped whenever a thumbnail is written, so a line that had none yet looks again. */
    val thumbs: StateFlow<Int>

    /**
     * The image itself for the full-screen view, at most [maxPx] on its longest side, when the
     * phone still has it (a file dibs sent); else its thumbnail.
     */
    suspend fun image(fileId: String, maxPx: Int): ImageBitmap? = thumb(fileId)

    /** A dibs screen is on screen (true) or gone: keep the link up and clear dibs's notifications. */
    fun visible(on: Boolean)

    /**
     * Tether's own screen (the Laptop chat and the other channels); [classic]: Tether's own dibs
     * channel screen, for a dibs that sends no payload yet.
     */
    fun openTether(classic: Boolean = false)

    /** A small thing kept across app restarts (the chat's place), by [key]; null if none. */
    fun note(key: String): String? = null

    /** Keeps [value] under [key] for [note]. */
    fun keepNote(key: String, value: String) {}
}

/**
 * Whether the chat list follows its newest end (docs/DIBS-APP.md, "Chat"). It changes only when the
 * user scrolls or taps the "N new" pill, never when something is sent or arrives. Kept in [Dibs.chatPin]
 * because a page replaces the tabs.
 */
@Stable
class ChatPin {
    var pinned by mutableStateOf(true)
}

/**
 * A message sent from here that no view lists yet (its echo), with the files picked for it; [task]
 * when it went to one of the user's tasks instead of dibs.
 */
data class Pending(
    val uid: String,
    val text: String,
    val files: List<Picked>,
    val tsMs: Long = System.currentTimeMillis(),
    val task: Long? = null,
    /** It went to an Ask about conversation (its `about`) instead of dibs. */
    val ask: String? = null,
)

/** A file picked to send: its copy in [DibsHost.pickDir], its name, whether it's an image, and where it came from. */
data class Picked(val uri: Uri, val name: String, val image: Boolean, val source: Uri = uri)

/**
 * What a message to dibs is about, from a task's full story ([Page.Story]): [kind] `ask` (Ask dibs
 * about this) or `follow` (Start a follow-up), with the paragraph long-pressed ([quote]). It rides
 * in the `say` as `about`, so dibs gives its brain the story with the user's words; [title] is
 * only for the chip over the box.
 */
data class About(val story: Long, val kind: String, val title: String, val quote: String? = null) {
    fun json(): JSONObject = JSONObject().put("story", story).put("kind", kind).apply { quote?.let { put("quote", it) } }
}

/**
 * A message box: its draft and picked files, kept across screens. The dibs chat has one, each of
 * the user's tasks has its own ([task]: its messages go to that task's agent, `task-say`), and so
 * has each Ask about conversation ([thread]: its `about`; words only, `thread-say`).
 */
@Stable
class Composer(val task: Long?, val thread: String? = null) {
    /** The text in the box. */
    var draft by mutableStateOf("")

    /**
     * The user typed in the box: the dibs chat's tells dibs they're typing ([Typing]). Text put
     * there by the app (a share, the follow-up prefix) doesn't.
     */
    fun typed(v: String) {
        draft = v
        if (v.isBlank()) spoken = false
        if (task == null && thread == null) Dibs.typing.edited(v, System.currentTimeMillis())
    }
    /** Files picked for the next message. */
    val picked = mutableStateListOf<Picked>()
    /** What the next message is about (a full story), shown as a chip over the box; the dibs chat's only. */
    var about by mutableStateOf<About?>(null)
    /** The line the next message answers (a swipe or Reply), shown as a chip over the box; the dibs chat's only. */
    var replyTo by mutableStateOf<TalkLine?>(null)
    /** Some of the box's words were said aloud ([Dictation]): its `say` or `task-say` carries `spoken`. */
    var spoken by mutableStateOf(false)

    /** The next message is about [a] (or nothing): one at a time, so it replaces a reply. */
    fun aboutIs(a: About?) {
        about = a
        if (a != null) replyTo = null
    }

    /** The next message answers [l] (or no line): it replaces an about chip. */
    fun replyIs(l: TalkLine?) {
        replyTo = l
        if (l != null) {
            // A follow-up's prefix goes with its chip: bare, it would start a task from nothing.
            if (about?.kind == "follow") draft = draft.removePrefix(Dibs.FOLLOW_UP)
            about = null
        }
    }

    /**
     * There is something to send: words or files. A follow-up's "Follow-up: " alone isn't words
     * (sent bare, dibs would start a task from nothing).
     */
    val canSend: Boolean
        get() = if (thread != null) draft.isNotBlank() else picked.isNotEmpty() ||
            (if (about?.kind == "follow") draft.trim().removePrefix(Dibs.FOLLOW_UP.trim()) else draft).isNotBlank()

    /** Sends the box (text and picked files); it shows as pending until the view lists its uid. */
    fun send() {
        if (!canSend) return
        if (thread != null) {
            Dibs.askSay(thread, draft.trim())
            draft = ""
            spoken = false
            return
        }
        val text = draft.trim()
        val files = picked.toList()
        if (text.isEmpty() && files.isEmpty()) return
        val uid = UUID.randomUUID().toString()
        Dibs.addPending(Pending(uid, text, files, task = task))
        // A line ends a Wait (dibs's side does the same).
        if (task == null) Dibs.holdTap?.shown?.let { Dibs.holdTap = HoldTap(null, it.kind, System.currentTimeMillis()) }
        // The line itself tells dibs the box is empty again.
        draft = ""
        if (task == null) Dibs.typing.sent()
        picked.clear()
        val on = about?.takeIf { task == null }?.json()
        val answers = replyTo?.takeIf { task == null }
        val said = spoken
        about = null
        replyTo = null
        spoken = false
        val action = if (task == null) "say" else "task-say"
        val extra = {
            (if (task == null) JSONObject().apply {
                on?.let { put("about", it) }
                answers?.let { put("reply", JSONObject().put("n", it.n)) }
            } else JSONObject().put("task", task)).apply { if (said) put("spoken", true) }
        }
        if (files.isEmpty()) {
            Dibs.host.act(action, extra().put("text", text), uid)
        } else {
            Dibs.host.send(uid, text, files.map { it.uri }, action, extra()) { uri, id -> Dibs.sentIds[uri] = id }
        }
    }

    /** Drops a picked file and its copy (✕ in the strip). */
    fun unpick(p: Picked) {
        picked.remove(p)
        p.uri.path?.takeIf { p.uri.scheme == "file" }?.let { java.io.File(it).parentFile?.deleteRecursively() }
    }
}

/**
 * The dibs chat box's typing pings (task #69): while the user types, dibs holds its answer to the
 * lines they already sent. A ping at most every [EVERY_MS] while the text changes, and one "not
 * typing" when the box is emptied or the screen left with a ping out. [send] puts one on the link.
 */
class Typing(private val send: (Boolean) -> Unit) {
    /** When the last ping went; null while none is out. */
    private var last: Long? = null

    fun edited(text: String, nowMs: Long) {
        if (text.isBlank()) return stopped()
        if (last.let { it == null || nowMs - it >= EVERY_MS }) {
            last = nowMs
            send(true)
        }
    }

    /** The box was sent: dibs knows from the line itself. */
    fun sent() {
        last = null
    }

    /** The box emptied, or the screen left. */
    fun stopped() {
        if (last == null) return
        last = null
        send(false)
    }

    companion object {
        /** dibs holds for 15 s after a ping (`hold::TYPING`): a ping every 5 s keeps it held. */
        const val EVERY_MS = 5_000L
    }
}

/** A screen over the tabs (docs/DIBS-APP.md, "Your tasks"): a task's page, its transcript, its report, its full story. */
/** A tap on the chat's chip at [at]: [shown] instead of dibs's chip, else the chip of kind [gone] hidden. */
data class HoldTap(val shown: Hold?, val gone: String, val at: Long)

sealed interface Page {
    data class Task(val id: Long) : Page
    data class Transcript(val id: Long) : Page
    data class Report(val id: Long) : Page
    data class Story(val id: Long) : Page
    /**
     * A task's account (the merged story screen): the recap's four parts, its actions and the full story. [fromRecap]: opened
     * from the Recap list, so it says "k of N" and swiping goes to the next brief; else it says "Story".
     */
    data class Brief(val task: Long, val fromRecap: Boolean) : Page
    /** A note of the Ideas tab, by its id. */
    data class Idea(val id: String) : Page
    /** An Ask about conversation, by its subject (`task:85`, `note:41`): it opens before dibs lists it. */
    data class Ask(val about: String) : Page
    /** Every full story dibs keeps, newest first (opened from Recap's "Stories" row). */
    data object Stories : Page
    /** A root step's request, by its question's id: the script word for word, and Approve with a fingerprint. */
    data class Root(val id: Long) : Page
    /** Setting up the phone's key for root steps: its code, sent to the laptop. */
    data object RootKey : Page
    /** The dibs page: its state, the lend switches, Claude usage and the laptop (opened from the bar). */
    data object Status : Page
}

/** The dibs screens' state that outlives a screen: the host, echoes, what's open. */
object Dibs {
    /** A Wait/Go tap shows at once for this long, unless a view agrees first. */
    const val TAP_MS = 15_000L

    @Volatile lateinit var host: DibsHost

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** Sent files' ids by their picked uri, for the thumbnail of an echo once it's in the view. */
    val sentIds = mutableStateMapOf<Uri, String>()

    /** The dibs chat box's typing pings. */
    val typing = Typing { on -> runCatching { host.live(JSONObject().put("typing", on).put("ts", System.currentTimeMillis())) } }

    /** The chat's chip tapped here: it shows at once, until a view agrees or [TAP_MS] passed. */
    var holdTap by mutableStateOf<HoldTap?>(null)

    /** The chip as shown: dibs's `state.hold`, or what was just tapped here. */
    fun hold(view: DibsView, nowMs: Long = System.currentTimeMillis()): Hold? {
        val tap = holdTap?.takeIf { nowMs - it.at < TAP_MS } ?: return view.state.hold
        return tap.shown ?: view.state.hold?.takeIf { it.kind != tap.gone }
    }

    /**
     * A tap on the chip: Wait (dibs keeps its reply back until Go, the next line, or 10 min) or Go
     * (dibs shows the reply it kept; it starts no work).
     */
    fun tapHold(chip: Hold) {
        holdTap = HoldTap(chip.tapped, chip.kind, System.currentTimeMillis())
        host.act(chip.action)
    }

    /** The dibs chat's box. */
    val chat = Composer(null)

    /**
     * Every task and idea in a few words (the newest `index-<rev>.json.gz` dibs sent), read by the
     * links in text and the `#` picker; null until one is fetched (then no text has links).
     */
    var index by mutableStateOf<RefIndex?>(null)

    /** The rev of the index asked for last, so one view change asks once. */
    private var indexAsked: String? = null

    /**
     * The view's [rev] (`index_rev`) has no file yet ([have]: the name of the newest one on the phone): asks dibs for
     * it, once per rev. No rev (an older dibs) asks for nothing.
     */
    fun wantIndex(rev: String?, have: String?) {
        if (rev.isNullOrEmpty() || have?.contains(rev) == true || indexAsked == rev) return
        indexAsked = rev
        host.act("fetch", JSONObject().put("what", "index"))
    }

    /** Opens the task or idea a link names: a task's page, an idea's page (found by its number in the Ideas tab; nothing if it isn't there). */
    fun openRef(view: DibsView?, kind: RefKind, n: Int) {
        if (kind == RefKind.TASK) return open(Page.Task(n.toLong()))
        view?.ideas?.notes?.firstOrNull { it.num == n.toLong() }?.let { open(Page.Idea(it.id)) }
    }

    /** [openRef] with the newest view the laptop sent (for a link in text, which has no view in hand). */
    fun openRef(kind: RefKind, n: Int) = openRef(DibsView.ofView(host.view.value), kind, n)

    /** The dibs chat's list position: a page replaces the tabs, so it is kept here to land on the same line on return. */
    var chatList = androidx.compose.foundation.lazy.LazyListState()
        private set

    /** Whether the chat follows its newest end (remembered across pages, like [chatList]). */
    var chatPin = ChatPin()
        private set

    /** The chat's place was restored from [DibsHost.note] already in this process. */
    var placeRestored by mutableStateOf(false)

    /** The [DibsHost.note] key the chat's place is kept under. */
    const val PLACE_NOTE = "chat-place"

    /** For tests: a fresh chat list, pinned, with its place not yet restored (as at a process start). */
    internal fun resetChat() {
        chatList = androidx.compose.foundation.lazy.LazyListState()
        chatPin = ChatPin()
        placeRestored = false
    }

    /** Typed answers and the like, by field, kept across tabs. */
    val fields = mutableStateMapOf<String, String>()
    /** Lines (by id) opened to their full text, earlier days unfolded, cards opened. */
    val open = mutableStateMapOf<String, Boolean>()
    /**
     * Questions and lines answered here (`q<id>`, `k<id>`, `w<id>`) that a view may still list: they
     * show as answered at once, until the next view drops them.
     */
    val answered = mutableStateMapOf<String, String>()
    /** Lines hidden here (long-press, Hide) that a view may still list, by line id. */
    val hidden = mutableStateMapOf<String, Boolean>()
    /** A tab asked for by an intent (a notification, a shortcut), taken by the screen. */
    var tab by mutableStateOf<String?>(null)

    /** Each task's newest chat line when its page was last open: a reply since opens the page at the chat. */
    val chatSeen = HashMap<Long, String>()

    /** The pages over the tabs, the top one last; system back pops it. */
    val pages = mutableStateListOf<Page>()

    fun open(page: Page) {
        if (pages.lastOrNull() != page) pages += page
    }

    /** Pops the top page; false when the tabs were showing already. */
    fun back(): Boolean = pages.removeLastOrNull() != null

    /** It's ticked off: by the view, or by a tap here the view hasn't caught up with. */
    fun ticked(t: YourTask): Boolean = if (t.ticked == null) "tick:${t.id}" in answered else "untick:${t.id}" !in answered

    /**
     * What the Waiting tab lists: the view's questions less those answered here. Its badge counts
     * this same list, so the number and the tab always agree.
     */
    fun waiting(view: DibsView): List<Question> = view.questions.filter { "q${it.id}" !in answered }

    /** Done and not opened yet (an open here counts at once). */
    fun unread(t: YourTask): Boolean = t.unread && "seen:${t.id}" !in answered

    /** Ticks a finished task off (it moves to "Ticked"); only the user does this. */
    fun tick(t: YourTask) {
        answered.remove("untick:${t.id}")
        Taps.clear("untick:${t.id}")
        answered["tick:${t.id}"] = "ticked"
        Taps.press("tick:${t.id}", "Tick off", timeOnly = true)
        host.act("tick", JSONObject().put("task", t.id))
    }

    fun untick(t: YourTask) {
        answered.remove("tick:${t.id}")
        Taps.clear("tick:${t.id}")
        answered["untick:${t.id}"] = "unticked"
        Taps.press("untick:${t.id}", "Untick", timeOnly = true)
        host.act("untick", JSONObject().put("task", t.id))
    }

    /** A brief is unread: dibs says so, and neither a tap here nor "Mark all read" has shown it read yet. */
    fun briefUnread(b: Brief): Boolean = b.unread && "rs:${b.task}" !in answered

    /** The recap's brief for [task] is on screen: it's read (shown at once, until the view agrees), and dibs is told once. */
    fun briefSeen(task: Long) {
        answered["rs:$task"] = "seen"
        host.act(RECAP_SEEN, JSONObject().put("task", task))
    }

    /** "Mark all read" in Recap: the briefs unread now show read at once; one that arrives later is not masked. */
    fun recapSeenAll(items: List<Brief>) {
        items.forEach { if (briefUnread(it)) answered["rs:${it.task}"] = "seen" }
        host.act(RECAP_SEEN, JSONObject().put("all", true))
    }

    /** The phone action that marks recap briefs read (docs/DIBS-APP.md, "The morning recap"). */
    const val RECAP_SEEN = "recap-seen"

    /** Its page is open: it's read, and its ping goes. */
    fun seenTask(t: YourTask) {
        if (t.unread) answered["seen:${t.id}"] = "seen"
        host.act("seen", JSONObject().put("task", t.id))
    }

    /**
     * A board card's action ([BoardCard.actions]: hold, resume, stop, delete, up, down, to_next,
     * to_later) for the card [key]; [before] the card a move goes before, when it says. [confirm]: the
     * confirm step was shown and taken (dibs applies a delete only with it).
     */
    fun taskAct(key: String, act: String, before: String? = null, confirm: Boolean = false) {
        Taps.press("card:$key", TASK_ACT_WORDS[act] ?: act)
        host.act(
            TASK_ACT,
            JSONObject().put("key", key).put("act", act).apply {
                before?.let { put("before", it) }
                if (confirm) put("confirm", true)
            },
        )
    }

    /** The phone action a board card's menu sends (docs/DIBS-APP.md, "The board"); its name may still change. */
    const val TASK_ACT = "task-act"

    /** A board action in the words a note uses. */
    private val TASK_ACT_WORDS = mapOf(
        "start" to "Start", "park" to "Park", "start_now" to "Start now", "hold" to "Put on hold", "resume" to "Resume",
        "stop" to "Stop", "delete" to "Delete", "up" to "Move up", "down" to "Move down",
        "to_next" to "Move to the queue", "to_later" to "Move to Backlog",
    )

    fun stop(task: Long) {
        Taps.press("stop:$task", "Stop")
        host.act("stop", JSONObject().put("task", task))
    }

    /** Resumes its chat in a tab on the laptop: by its Recap entry's saved chat, else by the task's number. */
    fun openOnLaptop(view: DibsView, task: Long) {
        val key = view.feed.firstOrNull { it.task == task && it.reopen != null }?.reopen ?: task.toString()
        Taps.press("reopen:$task", "Open on laptop", progress = "Opening on the laptop…")
        host.act("reopen", JSONObject().put("reopen", key))
    }

    /**
     * Asks dibs for a task's transcript, report or full story ([what]); it comes as a file on the
     * channel. A story none was written of yet is written first (a few minutes).
     */
    fun fetch(task: Long, what: String) {
        host.act("fetch", JSONObject().put("task", task).put("what", what))
    }

    /** Asks dibs to write a task's full story ([again]: anew, though one is kept); the file follows when done. */
    fun story(task: Long, again: Boolean) {
        host.act("story", JSONObject().put("task", task).put("again", again))
    }

    /**
     * Opens the dibs chat about a task's full story: the chip over the box says so, and the next
     * message carries it ([About]). A follow-up starts its draft with "Follow-up: ", so the line
     * reads right in the chat later.
     */
    fun chatAboutStory(t: YourTask, kind: String, quote: String? = null) = chatAboutStory(t.id, t.label, kind, quote)

    /** As above for any task by its number and name (a board card's, a brief's). */
    fun chatAboutStory(id: Long, label: String, kind: String, quote: String? = null) {
        chat.draft = chat.draft.removePrefix(FOLLOW_UP)
        if (kind == "follow") chat.draft = FOLLOW_UP + chat.draft
        chat.aboutIs(About(id, kind, label, quote))
        pages.clear()
        tab = DibsActivity.TAB_CHAT
    }

    /** ✕ on the chip: the next message is about nothing in particular (and loses "Follow-up: "). */
    fun dropAbout() {
        if (chat.about?.kind == "follow") chat.draft = chat.draft.removePrefix(FOLLOW_UP)
        chat.aboutIs(null)
    }

    internal const val FOLLOW_UP = "Follow-up: "

    /** The subject's title from the button tapped, for the page until dibs lists the conversation. */
    val askTitles = mutableStateMapOf<String, String>()
    /** Each Ask about conversation's box, by its `about`. */
    private val askBoxes = HashMap<String, Composer>()
    /** What each conversation was last told seen at (`thread-seen`): its newest line, and whether it had its overview. */
    private val askSeen = HashMap<String, String>()
    /**
     * A conversation's notification tapped before the view listed it (a cold start): its thread id,
     * until a view names it ([resolveAsk]).
     */
    private var askWanted: Long? = null
    /** When [askWanted] was asked for: past [ASK_OPEN_MS] it no longer opens (the user moved on). */
    private var askWantedAt = 0L

    fun askBox(about: String): Composer = askBoxes.getOrPut(about) { Composer(null, about) }

    /**
     * Ask about [about] (a subject's button, [title] its name): dibs opens the conversation, or
     * brings an ended one back, unless one is open already; the page opens at once either way.
     * [draft] goes before what its box holds (a paragraph of a full story's quote), unless it's there
     * already or the conversation is ending (the box is closed); nothing sent until the user sends.
     */
    fun askAbout(view: DibsView?, about: String, title: String, draft: String? = null) {
        val a = view?.asking(about)
        if (a == null || a.isEnded) host.act("thread-open", JSONObject().put("about", about))
        askTitles[about] = title
        if (draft != null && a?.ending != true) {
            val box = askBox(about)
            if (!box.draft.contains(draft.trim())) box.draft = draft + box.draft
        }
        open(Page.Ask(about))
    }

    /**
     * A conversation's notification (tag `ask:<id>`): its page over the chat, by the [about] the
     * notification carried, else by the view; when neither knows it yet (a cold start, before the
     * views are loaded), as soon as a view lists it ([resolveAsk]).
     */
    fun openAsk(id: Long, about: String?, view: DibsView?) {
        pages.clear()
        tab = DibsActivity.TAB_CHAT
        askWanted = null
        val known = about ?: view?.asks?.firstOrNull { it.id == id }?.about
        if (known != null) {
            open(Page.Ask(known))
        } else {
            askWanted = id
            askWantedAt = now()
        }
    }

    /**
     * A view arrived: the conversation a notification asked for opens once it's listed, unless the
     * user went elsewhere first or it took longer than [ASK_OPEN_MS].
     */
    fun resolveAsk(view: DibsView?) {
        val id = askWanted ?: return
        if (pages.isNotEmpty() || now() - askWantedAt > ASK_OPEN_MS) {
            askWanted = null
            return
        }
        view?.asks?.firstOrNull { it.id == id }?.let {
            askWanted = null
            open(Page.Ask(it.about))
        }
    }

    /**
     * The user went elsewhere (another intent, another tab, the screen left): a notification's
     * conversation no longer waits to open.
     */
    fun forgetAsk() {
        askWanted = null
    }

    /** A line to the conversation (typed, or a suggested question tapped): it shows as an echo until dibs lists its uid. */
    fun askSay(about: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val uid = UUID.randomUUID().toString()
        addPending(Pending(uid, t, emptyList(), tsMs = now(), ask = about))
        host.act("thread-say", JSONObject().put("about", about).put("text", t), uid)
    }

    /** Done in the header: it ends (no confirm; Ask more brings it back). */
    fun askDone(about: String) {
        host.act("thread-done", JSONObject().put("about", about))
    }

    /** Ask more, in place of the box once it ended. */
    fun askMore(about: String) {
        host.act("thread-open", JSONObject().put("about", about))
    }

    private var chatSent = 0L

    /**
     * The Chat tab is on screen with its newest line [newest] (dibs's line number): dibs clears the
     * count it shows on the laptop's bar. Sent once for each newer line.
     */
    fun chatSeen(newest: Long) {
        if (newest <= chatSent) return
        chatSent = newest
        host.act("chat-seen", JSONObject().put("n", newest))
    }

    /**
     * The page shows [n] lines, the newest [newest] (and the overview, [overview]): dibs clears "New
     * answer", once for each. Keyed on the newest line, not the count: dibs sends at most 40 lines, so
     * the count stops changing in a long conversation.
     */
    fun seenAsk(about: String, n: Int, newest: String?, overview: Boolean) {
        val key = "$newest:$overview"
        if (askSeen[about] == key) return
        askSeen[about] = key
        host.act("thread-seen", JSONObject().put("about", about).put("n", n))
    }

    /**
     * A line sent at [sentMs] (or a page opened then) that dibs hasn't listed is taken as not heard
     * once the link has been up ([upSinceMs], null while it's down) for [waitMs] since. A tap (Done,
     * Ask more) frees itself on the same clock, after [TAP_MS].
     */
    fun unheard(sentMs: Long, upSinceMs: Long?, nowMs: Long, waitMs: Long = ASK_WAIT_MS): Boolean =
        upSinceMs != null && nowMs - maxOf(sentMs, upSinceMs) >= waitMs

    /** dibs lists an open or a line within a second or two over a live link; this is far past that. */
    const val ASK_WAIT_MS = 20_000L

    /** A notification's conversation not listed by then doesn't open any more ([resolveAsk]). */
    const val ASK_OPEN_MS = 30_000L

    /** The clock the Ask about page's waits run on; the screen tests set the test clock. */
    internal var clock: () -> Long = System::currentTimeMillis

    fun now(): Long = clock()

    /** A subject an intent may name (the screen is exported): `task:`, `note:`, `project:` or `file:` and more. */
    fun askSubjectKey(about: String?): String? =
        about?.takeIf { a -> SUBJECTS.any { a.startsWith(it) && a.length > it.length } }

    private val SUBJECTS = listOf("task:", "note:", "project:", "file:")

    /** For tests: forget the boxes and what was told seen. */
    internal fun resetAsks() {
        askBoxes.clear()
        askSeen.clear()
        chatSent = 0L
        askTitles.clear()
        askWanted = null
        clock = System::currentTimeMillis
        _pending.value = emptyList()
    }

    internal fun addPending(p: Pending) {
        _pending.update { it + p }
    }

    /** Drops an echo that will never be listed (nothing could be sent). */
    fun dropPending(uid: String) {
        _pending.update { list -> list.filter { it.uid != uid } }
    }

    /**
     * Forgets the echoes a view now lists (in the dibs chat or a task's), and the answers to what
     * it no longer asks.
     */
    fun seen(view: DibsView?, source: Any? = null) {
        val ids = view?.talk?.mapTo(HashSet()) { it.id } ?: return
        Taps.viewArrived(source)
        // The view agrees with the chip tapped here.
        holdTap?.let { tap -> if (tap.shown?.let { view.state.hold?.kind == it.kind } ?: (view.state.hold?.kind != tap.gone)) holdTap = null }
        val listed = HashSet(ids)
        view.yours?.forEach { t -> t.talk.forEach { listed += it.id } }
        view.asks.forEach { a -> a.lines.forEach { l -> l.uid?.let { listed += it } } }
        _pending.update { list -> list.filter { it.uid !in listed } }
        val asked = HashSet<String>()
        // A tick, untick or open here shows at once, until the view agrees.
        view.yours?.forEach { t ->
            asked += if (t.ticked == null) "tick:${t.id}" else "untick:${t.id}"
            if (t.unread) asked += "seen:${t.id}"
        }
        view.questions.forEach { asked += "q${it.id}" }
        view.talk.forEach { l -> l.ask?.takeIf { it.open }?.let { asked += "q${it.q}" } }
        view.decided.forEach { asked += it.ack }
        // A Keep, Change or Got it here hides the look until the view agrees.
        view.looks.forEach { asked += "l${it.id}" }
        // Recap's Undo shows "Undo asked" until dibs lists it without Undo.
        view.recapDecided.forEach { if (it.undo) asked += it.ack }
        view.away?.let { asked += "w${it.id}" }
        // A brief read here (also by Mark all read) shows read until the view agrees.
        listOf(view.recapItems, view.yours.orEmpty().mapNotNull { it.brief }, view.board?.let { b -> (b.columns.flatMap { it.cards } + b.done).mapNotNull { it.brief } }.orEmpty())
            .forEach { l -> l.forEach { b -> if (b.unread) asked += "rs:${b.task}" } }
        // A key dibs dropped is answered: its tap is done.
        answered.keys.filter { it !in asked }.forEach { Taps.clear(it) }
        answered.keys.retainAll(asked)
        // A rolled-back answer (its note still up) that dibs has now dropped after all: no note.
        Taps.rolledBack().filter { it !in asked }.forEach { Taps.clear(it) }
        hidden.keys.retainAll(ids)
    }

    /**
     * An answer shown at once that the view still lists [Taps.FAIL_MS] after the link was up is dibs not
     * taking it: it comes back (the question shows again) and [Taps.notes] says so.
     */
    fun sweep(nowMs: Long = now()) {
        answered.keys.filter { Taps.tap(it)?.timeOnly == true && Taps.state(it, nowMs) == TapState.FAILED }.forEach {
            answered.remove(it)
            // A note the user dismissed already stays dismissed.
            if (Taps.tap(it)?.quiet == true) Taps.clear(it) else Taps.rolledBack += it
        }
    }

    /** Hides a line at once; dibs drops it from the next view (`h<n>`). */
    fun hide(line: TalkLine) {
        hidden[line.id] = true
        host.act("h${line.n}")
    }

    /** Answers [key] (`q<id>`, an ack) with [action], shown as [label] until the view drops it. */
    fun answer(key: String, label: String, action: String, value: JSONObject? = null, rollback: Boolean = true) {
        answered[key] = label
        // [rollback] false: the answer stays until dibs drops it, however long it takes (a root step's signature).
        if (rollback) {
            Taps.press(key, label, timeOnly = true)
        } else {
            Taps.clear(key)
        }
        host.act(action, value)
    }

    /**
     * A root step approved: the signature (of the message built with [remember]) goes to dibs, which
     * hands it to the root helper; the card shows as answered until the view drops it.
     */
    fun approveRoot(q: Question, request: Long, sig: ByteArray, remember: Boolean) {
        answer(
            "q${q.id}", "Approved", "root-approve",
            JSONObject().put("item", q.id).put("request", request)
                .put("sig", java.util.Base64.getEncoder().encodeToString(sig)).put("remember", remember),
            rollback = false,
        )
    }

    /** Deny: the card's own `d<id>` (dibs then cancels the request). */
    fun denyRoot(q: Question) {
        val deny = q.actions.firstOrNull { it.id.startsWith("d") } ?: Action("d${q.id}", "Deny")
        answer("q${q.id}", deny.label, deny.id, rollback = false)
    }

    fun toggle(key: String) {
        open[key] = open[key] != true
    }
}
