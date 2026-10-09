package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject

// The payload dibs publishes in its channel's view (`"dibs": {…}`, docs/DIBS-APP.md), parsed once
// per view. Every field is optional on the wire: an older or newer dibs still draws.

data class Action(val id: String, val label: String, val style: String = "")

data class FileRef(val id: String, val name: String, val size: Long, val image: Boolean)

/**
 * "Read it in full" on a finished plan or research question: the [task] whose page holds the whole
 * thing, or its [url] (https only) when dibs has it published.
 */
data class ReadInFull(val label: String, val task: Long, val url: String?)

/**
 * A question in the chat: its buttons while open (and a box for words, [hint] its placeholder),
 * then how it ended.
 */
data class Ask(
    val q: Long,
    val actions: List<Action>,
    val reply: String?,
    val outcome: String?,
    val hint: String? = null,
    val read: ReadInFull? = null,
) {
    val open: Boolean get() = outcome == null
}

data class TalkLine(
    /** The phone's uid for the user's lines (their pending echo clears on it), else `s<n>`. */
    val id: String,
    /** dibs's line number: `h<n>` hides it. */
    val n: Long,
    val mine: Boolean,
    val text: String,
    /** What a note shows until a tap opens [text]. */
    val short: String?,
    /** dibs's own note (a confirmation), drawn small. */
    val note: Boolean,
    val ts: Long,
    val files: List<FileRef>,
    val ask: Ask?,
    /** dibs's note that a task's full story is ready: its task id, whose story a tap opens. */
    val open: Long? = null,
    /** The line stands for an Ask about conversation (its `about`): the chat draws that conversation's row instead. */
    val thread: String? = null,
    /** Where a line of the user's came from ("From your conversation about the home server"), small under it. */
    val under: String? = null,
    /** The line this one answers (a swipe to reply), quoted at its top; an older dibs sends none. */
    val reply: ReplyRef? = null,
)

/** The line a reply answers: its number, who said it (`dibs` or `user`) and its text, whole (the bubble clamps it). */
data class ReplyRef(val n: Long, val who: String, val text: String)

/** A subject's way into an Ask about conversation: its key (`task:85`, `note:41`) and the button's words. */
data class AskEntry(val about: String, val label: String)

/** An Ask about conversation's row in the main chat: [tone] `new`, `busy` or `plain`. */
data class AskRowWords(val title: String, val words: String, val tone: String)

/** The conversation's overview: Markdown, and the questions it suggests. */
data class AskOverview(val text: String, val chips: List<String>)

/**
 * One line of an Ask about conversation: [who] `user`, `dibs` or `note`; [uid] the phone's for the
 * user's (its echo clears on it); [wait] the words under a line still waiting; [chips] an answer's
 * follow-up questions.
 */
data class AskLine(val id: String, val uid: String?, val who: String, val text: String, val ts: Long, val wait: String?, val chips: List<String>)

/** What the helper is doing now ("Answering 2 questions · reading the research"). */
data class AskStatus(val busy: Boolean, val words: String)

/** How it ended, and the lines kept in dibs's notes. */
data class AskEnded(val words: String, val keptTitle: String?, val kept: List<String>, val foot: String?)

/**
 * A short conversation about one subject (DESIGN.md of task #102; dibs calls it a thread, the user
 * never sees that word). [state] reading | answering | open | ending | ended. [done] the header's
 * button and [more] the one in place of the box once ended, each null when not offered.
 */
data class Asking(
    val about: String,
    /** dibs's id for it (a notification's tag is `ask:<id>`); null when missing, so it never matches one. */
    val id: Long?,
    val title: String,
    val eyebrow: String,
    val state: String,
    val row: AskRowWords?,
    val intro: String?,
    val reading: String?,
    val overview: AskOverview?,
    val lines: List<AskLine>,
    val asked: List<String>,
    val status: AskStatus?,
    val ended: AskEnded?,
    val placeholder: String?,
    val done: String?,
    val more: String?,
    val ts: Long,
    val lastTs: Long,
) {
    val ending: Boolean get() = state == "ending"
    val isEnded: Boolean get() = state == "ended"
}

data class Question(
    val id: Long,
    val title: String,
    val why: String,
    val details: String?,
    val from: String,
    val repo: String?,
    val ts: Long,
    val blocking: Boolean,
    /** question | phone | update | permission | idea | task | root | rootkey */
    val kind: String,
    val actions: List<Action>,
    /** The typed answer's action id (`r<id>`), when it takes one. */
    val reply: String?,
    /** The box's placeholder: an answer in words, or a comment on a question that runs something. */
    val hint: String? = null,
    val phoneSecs: Long?,
    val phoneUnlock: Boolean,
    /** Asked by one of the user's tasks ([YourTask.id]). */
    val task: Long? = null,
    /** A root step's request (kind `root`): what the page shows and the phone signs. */
    val root: RootRequest? = null,
    /** A finished plan or research question: a button to read the whole of it before answering. */
    val read: ReadInFull? = null,
)

/**
 * Something decided for the user. Once dibs's writer has run ([plain]), [text] and [why] are full plain sentences and
 * [project] names what it is about; before that they are the agent's own words. [raw] is always the agent's whole text.
 */
data class Decision(
    val id: Long,
    val text: String,
    val why: String,
    val from: String,
    val ts: Long,
    val undo: Boolean,
    val ack: String,
    val project: String = "",
    val plain: Boolean = false,
    val raw: String = "",
)

data class Task(
    val id: Long,
    val name: String,
    val state: String,
    val repo: String?,
    /** How long it has run, from its start (dibs sends no clock, so its views stay still). */
    val minutes: Long,
    val text: String,
    val doing: String?,
    /** busy | idle | shell, when its session is live. */
    val status: String?,
    /** Its live session's pid: what a peek asks for. */
    val pid: Long? = null,
    /** dibs started it by itself (not one of the user's tasks). */
    val background: Boolean = false,
)

data class Session(val name: String, val repo: String?, val branch: String?, val status: String, val task: String?, val holds: List<String>)

data class Ship(val repo: String, val who: String, val why: String, val since: Long)

data class Peek(val who: String, val at: Long, val lines: List<String>)

data class Away(val id: Long, val title: String, val lines: List<String>, val since: Long, val until: Long)

/**
 * One piece of work in Recap (dibs folds a task's lines into one): what changed for the user in
 * one line, why (what they asked), and behind a tap the rest in full.
 */
data class FeedItem(
    val ts: Long,
    /** done | stopped | did | closed | update */
    val kind: String,
    val who: String,
    val text: String,
    val repo: String? = null,
    /** The first sentence of what the user asked. */
    val why: String? = null,
    /** All of what they asked, when it's longer than [why]. */
    val asked: String? = null,
    /** Its other lines, oldest first. */
    val more: List<String> = emptyList(),
    /** The task's report, in full. */
    val report: String? = null,
    /** Stays the same while the piece of work goes on (an older dibs sends none). */
    val id: String? = null,
    /** Its saved Claude chat, when dibs can resume it in a tab on the laptop (action `reopen`). */
    val reopen: String? = null,
    /** One of the user's tasks: a tap opens its page (dibs then sends no report or more). */
    val task: Long? = null,
) {
    /** What keeps its row open while new lines arrive. */
    val key: String get() = "feed:" + (id ?: "$ts:$who")

    /** A tap shows more than the folded row does. */
    val opens: Boolean get() = task == null && (asked != null || more.isNotEmpty() || report != null || reopen != null)

    /** Everything, as plain text (Copy). */
    fun full(): String = buildString {
        append(text)
        report?.let { append("\n\nReport:\n").append(it) }
        if (more.isNotEmpty()) append("\n\nAlong the way:\n").append(more.joinToString("\n") { "• $it" })
        (asked ?: why)?.let { append("\n\nYou asked: ").append(it) }
    }
}

data class Lend(val until: Long?, val holder: String?, val text: String)

/** One lend toggle (task #29): lent to dibs or the user's, what it says, and the action that flips it. */
data class LendToggle(val lent: Boolean, val text: String, val action: String, val busy: Int = 0)

/** The phone's and the laptop's toggles; null in a payload from a dibs without them. */
data class Lends(val phone: LendToggle?, val laptop: LendToggle?, val compute: LendToggle? = null)

/** One Claude usage window as dibs last read it: five_hour, seven_day or spend_limit, its percent, when it resets. */
data class Limit(val name: String, val pct: Double, val resets: Long)

/** The laptop as dibs last saw it; any part may be missing. Memory in bytes, [load] the 1-minute load average. */
data class Laptop(
    val memUsed: Long?,
    val memTotal: Long?,
    val load: Double?,
    val cores: Int?,
    val builds: Int?,
    val waiting: Int?,
    val agents: Int?,
    /** What dibs calls the machine the numbers are from: "Server" on a hub, else "Laptop"; null from an older dibs. */
    val name: String? = null,
)

/**
 * The chat's Wait/Go chip (task #69, #224), as dibs words it: [kind] `wait` (dibs owes the user a
 * reply; the button says Wait) or `held` (it keeps the reply back; the button says Go); [note] beside the
 * button; [action] sent on a tap; [tapped] the chip to show at once after it.
 */
data class Hold(val kind: String, val note: String?, val button: String, val action: String, val style: String, val tapped: Hold?)

/**
 * [usage]: the line while dibs is out of usage. [doing] and [words]: what the brain is doing
 * ("working|idle|out|starting|off"; "Out of usage until 12:20"), from a dibs that sends them (older
 * ones don't: [stateWords] falls back to [busy] and [usage]). [limits]: the plan's windows, and [laptop] the
 * laptop's state (a dibs that sends them). [hold]: the chat's chip; null for none.
 */
data class State(
    val brain: String?,
    val busy: Boolean,
    val line: String?,
    val usage: String?,
    val limits: List<Limit> = emptyList(),
    val laptop: Laptop? = null,
    val doing: String? = null,
    val words: String? = null,
    val hold: Hold? = null,
    /** dibs's brain is switched on (it may still be down: [brain] null). */
    val enabled: Boolean = false,
) {
    /** The brain is on but neither running nor starting: the header offers "Start dibs". */
    val brainDown: Boolean get() = brain == null && enabled
}

data class Badges(val waiting: Int, val work: Int, val recap: Int, val tasks: Int = 0, val looks: Int = 0)

/**
 * One thing to look over, Recap's "To look over": a plan, design or choice dibs went ahead with (Keep or Change), or a
 * result to read ([read]: Got it or Reply). [pick] is what dibs picked and why, or what it found; [link] its page (https
 * only); [follow] the build dibs started, [followState] where it is (`queued`, `running`, `done`).
 */
data class Look(
    val id: Long,
    val kind: String,
    val title: String,
    val pick: String,
    val link: String?,
    val task: Long?,
    val follow: Long?,
    val followState: String?,
    val ts: Long,
    val read: Boolean,
)

/** Where a task's work went: "Shipped to tether · 3 changes" and their For you lines. */
data class Shipped(val repo: String, val changes: Int, val forYou: List<String>)

data class TaskResult(
    /** The task wrote a REPORT.md (the phone asks for it with `fetch`). */
    val reportMd: Boolean,
    val shipped: List<Shipped>,
)

/**
 * A task's full story (a long account written only when the user asks, docs/DIBS-APP.md "Full
 * story"): [state] writing | ready | failed; [ts] when the kept one was written; [stale] the task
 * moved on since; [have] one is kept (readable while a new one is written); [since] when writing
 * began; [by] agent | writer.
 */
data class Story(
    val state: String,
    val ts: Long? = null,
    val stale: Boolean = false,
    val have: Boolean = false,
    val since: Long? = null,
    val by: String? = null,
) {
    val writing: Boolean get() = state == "writing"
}

/**
 * One piece of work the morning recap tells the user about (docs/DIBS-APP.md, "The morning recap"): a headline
 * and a line, the four parts of its story ([what], [why], [means], [next]; any may be empty) and what to do about
 * it. [tier] needs | urgent | asked | talked. [writing]: the parts aren't written yet ([what] and [lede] hold the
 * plain summary). [card]: an open Waiting card of it (a question id). Every board card and task of the user's may carry one.
 */
data class Brief(
    val key: String,
    val task: Long,
    val tier: String,
    val title: String,
    val lede: String = "",
    val what: String = "",
    val why: String = "",
    val means: String = "",
    val next: String = "",
    /** The answer to a question the user asked. */
    val answer: String? = null,
    val project: String? = null,
    /** build | plan | research | design */
    val kind: String? = null,
    /** done | stopped | failed | running | waiting */
    val state: String? = null,
    val ts: Long = 0,
    val unread: Boolean = false,
    val writing: Boolean = false,
    val card: Long? = null,
    val story: Story? = null,
) {
    /** It needs the user: "Needs you" or "Urgent". */
    val needsYou: Boolean get() = tier == "needs" || tier == "urgent"

    /** Its tag, in the words the recap uses ("Answers your question" when it holds an answer to one). */
    val tag: String
        get() = when {
            answer != null && (tier == "asked" || tier == "talked") -> "Answers your question"
            tier == "needs" -> "Needs you"
            tier == "urgent" -> "Urgent"
            tier == "talked" -> "You talked it over"
            else -> "You asked"
        }

    /** At least one of the four parts is written. */
    val hasParts: Boolean get() = what.isNotBlank() || why.isNotBlank() || means.isNotBlank() || next.isNotBlank()
}

/** Small fixes folded in the recap: one project's count and its first lines (fewer lines than [n]: "n more"). */
data class SmallGroup(val project: String, val n: Int, val lines: List<String>)

data class RecapSmall(val n: Int, val groups: List<SmallGroup>)

/** One full story dibs keeps (the Stories list under Recap): its task, the task's title, when it was written. */
data class StoryEntry(val task: Long, val title: String, val ts: Long, val project: String? = null)

/**
 * One of the user's own tasks (docs/DIBS-APP.md, "Your tasks"): it waits in the Tasks tab from its
 * start until they tick it off. Its [talk] is the chat with its own agent (who: user, agent, note).
 */
data class YourTask(
    val id: Long,
    val title: String,
    val name: String,
    /** The repo it worked in, or "Other". */
    val project: String,
    /** needs | working | paused | done | stopped | failed */
    val state: String,
    /** Its newest activity. */
    val ts: Long,
    val started: Long,
    val finished: Long? = null,
    /** How long it ran, once finished. */
    val minutes: Long? = null,
    /** What changed, or what it's doing now. */
    val line: String = "",
    /** The user's words, whole. */
    val asked: String = "",
    /** Its report, whole, once finished. */
    val report: String? = null,
    val result: TaskResult? = null,
    /** Its open questions' ids (the same as [DibsView.questions]). */
    val questions: List<Long> = emptyList(),
    /** Its session is working now. */
    val busy: Boolean = false,
    /** It has a live session. */
    val live: Boolean = false,
    /** Finished and not opened yet. */
    val unread: Boolean = false,
    /** When the user ticked it off. */
    val ticked: Long? = null,
    /** The chat with its agent, the last 30 lines, oldest first. */
    val talk: List<TalkLine> = emptyList(),
    /** Its full story, once asked for; null when it never was. */
    val story: Story? = null,
    /** Ask about it (a dibs that offers it). */
    val ask: AskEntry? = null,
    /** The recap's account of it (a dibs that sends them; the current one sends none here: the recap's items and the board card carry it). */
    val brief: Brief? = null,
    /** Its state in words ("Queued, 3rd in line"); empty from an older dibs. */
    val stateWords: String = "",
    /** needs | working | queued | later | done | stopped (a page fetched for a task), else empty. */
    val group: String = "",
    /** The tasks and ideas it is linked to. */
    val links: List<RefLink> = emptyList(),
    /** One of the user's own tasks (false: dibs's own work, shown on a fetched page read-only). */
    val mine: Boolean = true,
) {
    /** Its name as the screens show it. */
    val label: String get() = plainTitle(title, asked).ifBlank { name }.ifBlank { "Task $id" }

    /** It has ended one way or another: done, stopped or failed. */
    val finishedState: Boolean get() = state == "done" || state == "stopped" || state == "failed"
}

/** A link from a task or idea to another ([kind] task | idea, number [n]): its [title], [state] in words and [why] ("Made from", "Waits for"). */
data class RefLink(val kind: RefKind, val n: Int, val title: String, val state: String, val why: String)

/**
 * One card on dibs's board (docs/DIBS-APP.md, "The board"): a task (`task:<id>`, [n] its number) or an
 * idea (`idea:<id>`, no number). Drawn as dibs sends it: [title] whole, [stateWords] its state in plain
 * words, [now] what it's doing (may be empty), [tags] "On hold" and "work saved", and [actions] what its
 * long press offers (start, park, start_now, up, down, stop, delete, story; an old dibs: hold, resume, to_next, to_later).
 */
data class BoardCard(
    val key: String,
    val n: Long?,
    val title: String,
    val stateWords: String,
    val now: String = "",
    val tags: List<String> = emptyList(),
    val story: Story? = null,
    val actions: List<String> = emptyList(),
    /** What Delete loses, in plain words ("Delete removes this idea. No work is lost."), shown at its confirm step. */
    val deleteText: String? = null,
    /** The one action dibs puts on the card's face: "park" (Active cards) or "start" (Backlog cards); null on Done and from an old dibs. */
    val primary: String? = null,
    /** An Active card's lane: "running" or "waiting"; null elsewhere and from an old dibs. */
    val lane: String? = null,
    /** Ask about it (tasks only, a dibs that offers it). */
    val ask: AskEntry? = null,
    /** The recap's account of it (a dibs that sends them). */
    val brief: Brief? = null,
    /** A running task's stage and rough time left ([progressLine], [barFill]). */
    val progress: Progress? = null,
    /** Where a tap goes instead of a task's page: `idea:<n>` (an idea's or a drop's card); null on task cards. */
    val open: String? = null,
) {
    /** Its task's id, for a task card; null for an idea. */
    val task: Long? get() = key.removePrefix("task:").takeIf { key.startsWith("task:") }?.toLongOrNull()
}

/**
 * A column of the board: active or backlog (an old dibs: now, next or later), its title ("Active") and its cards, in dibs's order.
 * [box]: the placeholder of the box that drops a new idea into the Backlog (`backlog_box`); null in every other column and from a dibs without drops.
 */
data class BoardColumn(val key: String, val title: String, val cards: List<BoardCard>, val box: String? = null)

/**
 * dibs's board for the Tasks tab: its columns in order, and what's done ([doneCount], [done] its cards).
 * The phone draws it unchanged: no grouping or sorting of its own (the user, 2026-10-06).
 * [hold]: why nothing new starts (a move, a used-up plan), in dibs's words; null while the queue runs.
 */
data class Board(val columns: List<BoardColumn>, val doneCount: Int, val done: List<BoardCard>, val room: BoardRoom? = null, val hold: String? = null)

/** How many more tasks the laptop has room to start ([n], 0 = none) and why, in dibs's words ([line]). */
data class BoardRoom(val n: Int, val line: String)

data class DibsView(
    /** dibs's clock when it published. */
    val now: Long,
    val state: State,
    val talk: List<TalkLine>,
    val questions: List<Question>,
    /** What waits for a look, newest first (`looks`); empty from a dibs that doesn't send it. */
    val looks: List<Look> = emptyList(),
    val decided: List<Decision>,
    /** What was decided for the user lately, read or not: Recap's "Decided for you" (an older dibs: [decided]). */
    val recapDecided: List<Decision>,
    val tasks: List<Task>,
    val sessions: List<Session>,
    val ships: List<Ship>,
    val peek: Peek?,
    val away: Away?,
    val feed: List<FeedItem>,
    val lend: Lend?,
    val lends: Lends?,
    val badges: Badges,
    /** The user's own tasks; null from a dibs that doesn't send them (it shows the old Work tab). */
    val yours: List<YourTask>? = null,
    /** dibs's board; null from a dibs that doesn't send one (the Tasks tab groups [yours] itself then). */
    val board: Board? = null,
    /** The Ideas tab; null from a dibs that doesn't send it (the tab shows only what's made here). */
    val ideas: com.kivan.tether.dibs.ideas.Ideas? = null,
    /** The Ask about conversations of the last 24 hours, newest first (a dibs that sends them). */
    val asks: List<Asking> = emptyList(),
    /** The morning recap's unread list, in order (`recap.items`); empty from a dibs without one. */
    val recapItems: List<Brief> = emptyList(),
    /** Folded small fixes (`recap.small`). */
    val recapSmall: RecapSmall? = null,
    val recapUnread: Int = 0,
    val recapNeeds: Int = 0,
    /** The payload has `recap.items` (even an empty list): Recap draws the unread list, not the old tab. */
    val recapListed: Boolean = false,
    /** Every full story kept, newest first (`recap.stories`). */
    val recapStories: List<StoryEntry> = emptyList(),
    /** Changes when a task or idea is added or renamed or moves on: the phone fetches the index (`what: index`) then. Null from an older dibs. */
    val indexRev: String? = null,
) {
    fun task(id: Long): YourTask? = yours?.firstOrNull { it.id == id }

    /** Recap draws the unread list (a dibs that sends one), else the old tab. */
    val recapList: Boolean get() = recapListed || recapItems.isNotEmpty()

    /** The board card of a task. */
    fun card(id: Long): BoardCard? = board?.let { b -> (b.columns.flatMap { it.cards } + b.done).firstOrNull { it.task == id } }

    /** A task's full story state: its own task's, else its board card's, else its brief's; null when it was never asked for. */
    fun storyOf(id: Long): Story? = task(id)?.story ?: card(id)?.story ?: brief(id)?.story

    /** A task's name as the screens show it: its own task's, else its board card's, else its brief's title. */
    fun labelOf(id: Long): String? = task(id)?.label ?: card(id)?.title?.takeIf { it.isNotBlank() } ?: brief(id)?.title?.takeIf { it.isNotBlank() }

    /** The Ask about button of a task: its own, else its board card's (a dibs that offers it). */
    fun askFor(id: Long): AskEntry? = task(id)?.ask ?: card(id)?.ask

    /** A task's account: the recap's, else its board card's (dibs sends none on a task of the user's, but one that does is read). */
    fun brief(id: Long): Brief? = recapItems.firstOrNull { it.task == id } ?: card(id)?.brief ?: task(id)?.brief

    /** A task's Ask about button: its own, else its board card's (a dibs that offers it). */
    fun askFor(t: YourTask): AskEntry? = t.ask ?: board?.let { b -> (b.columns.flatMap { it.cards } + b.done).firstOrNull { it.task == t.id }?.ask }

    /** The conversation about [about], if dibs lists it. */
    fun asking(about: String): Asking? = asks.firstOrNull { it.about == about }

    companion object {
        /** The payload of a whole channel view, or null when dibs sent none (an older dibs). */
        fun ofView(view: JSONObject?): DibsView? = view?.optJSONObject("dibs")?.let(::parse)

        /**
         * The `about` of the Ask about conversation [id] in a whole channel view, if it lists it: a
         * notification's tap (tag `ask:<id>`) opens its page by it.
         */
        fun askAbout(view: JSONObject?, id: Long): String? = runCatching {
            view?.optJSONObject("dibs")?.optJSONArray("threads").objects().firstOrNull { it.long("id") == id }?.str("about")
        }.getOrNull()

        fun parse(o: JSONObject): DibsView {
            val st = o.optJSONObject("state") ?: JSONObject()
            val recap = o.optJSONObject("recap") ?: JSONObject()
            val b = o.optJSONObject("badges") ?: JSONObject()
            return DibsView(
                now = o.optLong("now"),
                state = State(
                    st.str("brain"), st.optBoolean("busy"), st.str("line"), st.str("usage"),
                    st.optJSONObject("limits")?.optJSONArray("windows").objects().map { Limit(it.optString("name"), it.optDouble("pct", 0.0), it.optLong("resets")) },
                    st.optJSONObject("laptop")?.let { l ->
                        Laptop(l.long("mem_used"), l.long("mem_total"), l.double("load"), l.long("cores")?.toInt(), l.long("builds")?.toInt(), l.long("waiting")?.toInt(), l.long("agents")?.toInt(), l.str("name"))
                    },
                    st.str("doing"), st.str("words"),
                    st.optJSONObject("hold")?.let(::hold),
                    enabled = st.optBoolean("enabled"),
                ),
                talk = o.optJSONArray("talk").objects().map(::talkLine),
                questions = o.optJSONArray("questions").objects().map(::question),
                looks = o.optJSONArray("looks").objects().map(::look),
                decided = o.optJSONArray("decided").objects().map(::decision),
                recapDecided = (if (recap.has("decided")) recap.optJSONArray("decided") else o.optJSONArray("decided")).objects().map(::decision),
                tasks = o.optJSONArray("tasks").objects().map {
                    val started = it.optLong("started")
                    val minutes = if (started > 0) (System.currentTimeMillis() / 1000 - started) / 60 else it.optLong("minutes")
                    Task(
                        it.optLong("id"), it.optString("name"), it.optString("state"), it.str("repo"), minutes, it.optString("text"), it.str("doing"),
                        it.str("status"), it.optLong("pid").takeIf { p -> p > 0 }, it.optBoolean("background"),
                    )
                },
                sessions = o.optJSONArray("sessions").objects().map {
                    Session(it.optString("name"), it.str("repo"), it.str("branch"), it.optString("status"), it.str("task"), it.optJSONArray("holds").strings())
                },
                ships = o.optJSONArray("ships").objects().map { Ship(it.optString("repo"), it.optString("who"), it.optString("why"), it.optLong("since")) },
                peek = o.optJSONObject("peek")?.let { Peek(it.optString("who"), it.optLong("at"), it.optJSONArray("lines").strings()) },
                away = recap.optJSONObject("away")?.let {
                    Away(it.optLong("id"), it.optString("title"), it.optJSONArray("lines").strings(), it.optLong("since"), it.optLong("until"))
                },
                feed = recap.optJSONArray("feed").objects().map {
                    FeedItem(
                        it.optLong("ts"), it.optString("kind"), it.optString("who"), it.optString("text"),
                        it.str("repo"), it.str("why"), it.str("asked"), it.optJSONArray("more").strings(), it.str("report"), it.str("id"), it.str("reopen"),
                        it.long("task"),
                    )
                },
                lend = o.optJSONObject("lend")?.let { Lend(it.optLong("until").takeIf { u -> u > 0 }, it.str("holder"), it.optString("text")) },
                lends = o.optJSONObject("lends")?.let { l -> Lends(l.optJSONObject("phone")?.let(::lendToggle), l.optJSONObject("laptop")?.let(::lendToggle), l.optJSONObject("compute")?.let(::lendToggle)) },
                badges = Badges(b.optInt("waiting"), b.optInt("work"), b.optInt("recap"), b.optInt("tasks"), b.optInt("looks")),
                yours = if (o.has("yours")) o.optJSONArray("yours").objects().map(::yourTask) else null,
                board = o.optJSONObject("board")?.let(::board)?.let { b -> if (b.room != null) b else b.copy(room = queueRoom(o.optJSONObject("queue"))) },
                ideas = o.optJSONObject("ideas")?.let(com.kivan.tether.dibs.ideas.Ideas::parse),
                asks = o.optJSONArray("threads").objects().mapNotNull(::asking).distinctBy { it.about },
                recapItems = recap.optJSONArray("items").objects().mapNotNull { brief(it) }.distinctBy { it.task },
                recapSmall = recap.optJSONObject("small")?.let { sm ->
                    RecapSmall(
                        sm.optInt("n"),
                        sm.optJSONArray("groups").objects().map { g -> SmallGroup(g.str("project").orEmpty(), g.optInt("n"), g.optJSONArray("lines").strings()) },
                    )
                },
                recapUnread = recap.optInt("unread"),
                recapNeeds = recap.optInt("needs"),
                recapListed = recap.has("items") && !recap.isNull("items"),
                recapStories = recap.optJSONArray("stories").objects().mapNotNull { e ->
                    e.long("task")?.let { StoryEntry(it, e.str("title").orEmpty(), e.optLong("ts"), e.str("project")) }
                }.distinctBy { it.task },
                indexRev = o.str("index_rev"),
            )
        }

        /** One conversation; none without its `about`, the key everything finds it by. A JSON null reads as missing. */
        private fun asking(o: JSONObject): Asking? {
            val about = o.str("about") ?: return null
            return Asking(
                about = about,
                id = o.long("id"),
                title = o.str("title").orEmpty(),
                eyebrow = o.str("eyebrow").orEmpty(),
                state = o.str("state").orEmpty(),
                row = o.optJSONObject("row")?.let { AskRowWords(it.str("title").orEmpty(), it.str("words").orEmpty(), it.str("tone").orEmpty()) },
                intro = o.str("intro"),
                reading = o.str("reading"),
                overview = o.optJSONObject("overview")?.let { AskOverview(it.str("text").orEmpty(), it.optJSONArray("chips").strings().filter(String::isNotBlank)) },
                lines = o.optJSONArray("lines").objects().map {
                    AskLine(
                        it.str("id").orEmpty(), it.str("uid"), it.str("who").orEmpty(), it.str("text").orEmpty(), it.optLong("ts"), it.str("wait"),
                        it.optJSONArray("chips").strings().filter(String::isNotBlank),
                    )
                }.filter { it.id.isNotEmpty() }.distinctBy { it.id },
                asked = o.optJSONArray("asked").strings(),
                status = o.optJSONObject("status")?.let { st -> st.str("words")?.let { AskStatus(st.optBoolean("busy"), it) } },
                ended = o.optJSONObject("ended")?.let {
                    AskEnded(it.str("words").orEmpty(), it.str("kept_title"), it.optJSONArray("kept").strings().filter(String::isNotBlank), it.str("foot"))
                },
                placeholder = o.str("placeholder"),
                done = o.str("done"),
                more = o.str("more"),
                ts = o.optLong("ts"),
                lastTs = o.optLong("last_ts"),
            )
        }

        private fun askEntry(o: JSONObject?): AskEntry? {
            o ?: return null
            val about = o.str("about") ?: return null
            val label = o.str("label") ?: return null
            return AskEntry(about, label)
        }

        /** A task of the user's as the view lists it; also a fetched page (`page-<id>-<rev>.json.gz`), which adds `group` and `mine`. */
        fun yourTask(o: JSONObject): YourTask {
            val r = o.optJSONObject("result")
            return YourTask(
                id = o.optLong("id"),
                title = o.optString("title"),
                name = o.optString("name"),
                project = o.str("project") ?: "Other",
                state = o.optString("state"),
                ts = o.optLong("ts"),
                started = o.optLong("started"),
                finished = o.long("finished"),
                minutes = if (o.has("minutes") && !o.isNull("minutes")) o.optLong("minutes") else null,
                line = o.optString("line"),
                asked = o.optString("asked"),
                report = o.str("report"),
                result = r?.let {
                    TaskResult(
                        it.optBoolean("report_md"),
                        it.optJSONArray("shipped").objects().map { s -> Shipped(s.optString("repo"), s.optInt("changes"), s.optJSONArray("for_you").strings()) },
                    )
                },
                questions = o.optJSONArray("questions").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.optLong(it) } },
                busy = o.optBoolean("busy"),
                live = o.optBoolean("live"),
                unread = o.optBoolean("unread"),
                ticked = o.long("ticked"),
                talk = o.optJSONArray("talk").objects().map { l ->
                    val who = l.optString("who")
                    TalkLine(
                        id = l.optString("id"), n = 0, mine = who == "user", text = l.optString("text"), short = l.str("short"),
                        note = who == "note", ts = l.optLong("ts"), files = files(l.optJSONArray("files")), ask = ask(l.optJSONObject("ask")),
                    )
                },
                story = o.optJSONObject("story")?.let(::story),
                ask = askEntry(o.optJSONObject("ask")),
                brief = o.optJSONObject("brief")?.let { brief(it, o.optLong("id")) },
                stateWords = o.str("state_words").orEmpty(),
                group = o.str("group").orEmpty(),
                links = links(o.optJSONArray("links")),
                mine = if (o.has("mine") && !o.isNull("mine")) o.optBoolean("mine") else true,
            )
        }

        /** The `links` of a task or an idea note; one without a number is dropped. */
        fun links(a: JSONArray?): List<RefLink> = a.objects().mapNotNull {
            val n = it.optInt("n").takeIf { n -> n > 0 } ?: return@mapNotNull null
            RefLink(if (it.optString("kind") == "idea") RefKind.IDEA else RefKind.TASK, n, it.str("title").orEmpty(), it.str("state").orEmpty(), it.str("why").orEmpty())
        }

        /** One brief; none without its task (a board card's or a task's own names it when the brief doesn't). */
        private fun brief(o: JSONObject, task: Long? = null): Brief? {
            val t = o.long("task") ?: task ?: return null
            return Brief(
                key = o.str("key") ?: "t$t",
                task = t,
                tier = o.str("tier").orEmpty(),
                title = o.str("title").orEmpty(),
                lede = o.str("lede").orEmpty(),
                what = o.str("what").orEmpty(),
                why = o.str("why").orEmpty(),
                means = o.str("means").orEmpty(),
                next = o.str("next").orEmpty(),
                answer = o.str("answer"),
                project = o.str("project"),
                kind = o.str("kind"),
                state = o.str("state"),
                ts = o.optLong("ts"),
                unread = o.optBoolean("unread"),
                writing = o.optBoolean("writing"),
                card = o.long("card"),
                story = o.optJSONObject("story")?.let(::story),
            )
        }

        private fun story(s: JSONObject) = Story(s.optString("state"), s.long("ts"), s.optBoolean("stale"), s.optBoolean("have"), s.long("since"), s.str("by"))

        private fun board(o: JSONObject): Board {
            val done = o.optJSONObject("done") ?: JSONObject()
            val doneCards = cards(done.optJSONArray("cards"))
            return Board(
                // A column or card without a key is dropped and a repeated key kept once: the list keys on them.
                columns = o.optJSONArray("columns").objects()
                    .map { BoardColumn(it.str("key").orEmpty(), it.str("title").orEmpty(), cards(it.optJSONArray("cards")), it.optJSONObject("backlog_box")?.str("placeholder")?.takeIf { p -> p.isNotBlank() }) }
                    .filter { it.key.isNotEmpty() }.distinctBy { it.key },
                doneCount = done.long("count")?.toInt() ?: doneCards.size,
                done = doneCards,
                room = o.optJSONObject("room")?.let { r -> r.str("line")?.takeIf { it.isNotBlank() }?.let { BoardRoom(r.optInt("room", -1), it) } },
                hold = o.optJSONObject("hold")?.str("line")?.takeIf { it.isNotBlank() },
            )
        }

        /** The phone view's cached `queue` block: its room count and the reason, when the board carries no `room` of its own. */
        private fun queueRoom(q: JSONObject?): BoardRoom? {
            val n = q?.takeIf { it.has("room") }?.optInt("room", -1)?.takeIf { it >= 0 } ?: return null
            val why = q.str("why") ?: return null
            return BoardRoom(n, if (n == 0) "No room for another task now: $why" else why)
        }

        private fun cards(a: JSONArray?) = a.objects().map(::card).filter { it.key.isNotEmpty() }.distinctBy { it.key }

        private fun card(o: JSONObject) = BoardCard(
            key = o.str("key").orEmpty(),
            n = o.long("n"),
            title = o.str("title").orEmpty(),
            stateWords = o.str("state_words").orEmpty(),
            now = o.str("now").orEmpty(),
            tags = o.optJSONArray("tags").strings(),
            story = o.optJSONObject("story")?.let(::story),
            actions = o.optJSONArray("actions").strings(),
            deleteText = o.str("delete_text"),
            primary = o.str("primary"),
            lane = o.str("lane"),
            ask = askEntry(o.optJSONObject("ask")),
            brief = o.optJSONObject("brief")?.let { brief(it, o.long("n")) },
            progress = o.optJSONObject("progress")?.let(::progress),
            open = o.str("open"),
        )

        private fun progress(o: JSONObject): Progress? {
            val from = o.long("from") ?: return null
            return Progress(o.str("stage").orEmpty(), o.str("words").orEmpty(), from, o.long("eta"), o.long("lo"), o.long("hi"), o.optBoolean("guess"))
        }

        private fun decision(o: JSONObject) =
            Decision(
                o.optLong("id"), o.optString("text"), o.optString("why"), o.optString("from"), o.optLong("ts"), o.optBoolean("undo"), o.optString("ack"),
                o.optString("project"), o.optBoolean("plain"), o.optString("raw"),
            )

        private fun files(a: JSONArray?) = a.objects().map { FileRef(it.optString("id"), it.optString("name"), it.optLong("size"), it.optBoolean("image")) }

        private fun lendToggle(o: JSONObject): LendToggle? =
            o.optString("action").takeIf { it.isNotBlank() }?.let { LendToggle(o.optBoolean("lent"), o.optString("text"), it, o.optInt("busy")) }

        private fun hold(o: JSONObject): Hold? {
            val kind = o.str("kind") ?: return null
            val button = o.str("button") ?: return null
            return Hold(kind, o.str("note"), button, o.optString("action"), o.optString("style"), o.optJSONObject("tapped")?.let(::hold))
        }

        /** A line's question (its buttons, or how it ended), in the dibs chat and in a task's. */
        private fun ask(a: JSONObject?) = a?.let { Ask(it.optLong("q"), actions(it.optJSONArray("actions")), it.str("reply"), it.str("outcome"), it.str("hint"), readInFull(it.optJSONObject("read"))) }

        private fun talkLine(o: JSONObject) = TalkLine(
            id = o.optString("id"),
            n = o.optLong("n"),
            mine = o.optString("who") == "user",
            text = o.optString("text"),
            short = o.str("short"),
            note = o.optBoolean("note"),
            ts = o.optLong("ts"),
            files = files(o.optJSONArray("files")),
            ask = ask(o.optJSONObject("ask")),
            open = o.optJSONObject("open")?.long("story"),
            thread = o.str("thread"),
            under = o.str("under"),
            reply = o.optJSONObject("reply")?.let { r -> r.long("n")?.takeIf { it > 0 }?.let { n -> ReplyRef(n, r.str("who").orEmpty(), r.str("text").orEmpty()) } },
        )

        private fun question(o: JSONObject): Question {
            val phone = o.optJSONObject("phone")
            return Question(
                id = o.optLong("id"),
                title = o.optString("title"),
                why = o.optString("why"),
                details = o.str("details"),
                from = o.optString("from"),
                repo = o.str("repo"),
                ts = o.optLong("ts"),
                blocking = o.optBoolean("blocking"),
                kind = o.optString("kind", "question"),
                actions = actions(o.optJSONArray("actions")),
                reply = o.str("reply"),
                hint = o.str("hint"),
                phoneSecs = phone?.optLong("secs"),
                phoneUnlock = phone?.optBoolean("unlock") == true,
                task = o.long("task"),
                root = o.optJSONObject("root")?.let(RootRequest::parse),
                read = readInFull(o.optJSONObject("read")),
            )
        }

        private fun look(o: JSONObject) = Look(
            id = o.optLong("id"),
            kind = o.optString("kind"),
            title = o.optString("title"),
            pick = o.optString("pick"),
            link = o.str("link")?.takeIf { it.startsWith("https://") },
            task = o.long("task")?.takeIf { it > 0 },
            follow = o.long("follow")?.takeIf { it > 0 },
            followState = o.str("follow_state"),
            ts = o.optLong("ts"),
            read = o.optBoolean("read"),
        )

        private fun readInFull(o: JSONObject?): ReadInFull? {
            val task = o?.long("task")?.takeIf { it > 0 } ?: return null
            return ReadInFull(o.str("label") ?: "Read it in full", task, o.str("url")?.takeIf { it.startsWith("https://") })
        }

        private fun actions(a: JSONArray?) = a.objects().map { Action(it.optString("id"), it.optString("label"), it.optString("style")) }
    }
}

/** A string field, or null when it's missing, null or empty. */
private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

/** A number field, or null when it's missing or null. */
private fun JSONObject.long(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)

private fun JSONObject.double(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() }
