package com.kivan.jarvis.tabs

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/** A tile: a name and its marks. The server decides all of it; a screen only draws. */
@Immutable
data class TileView(
    val id: String,
    val n: Int,
    val name: String,
    val needs: Boolean = false,
    val unread: Boolean = false,
    val working: Boolean = false,
    /** His mode for the tab, and the model and effort of it ("opus", "medium"); empty until known. */
    val mode: String = "",
    val model: String = "",
    val effort: String = "",
    /** Its autopilot runs: the Auto button is on. */
    val autopilot: Boolean = false,
)

/** How hard a question reaches him. Urgent is the only amber left; low never reaches his phone's notifications. */
enum class Level { Low, Default, Urgent }

/** One tab a card's question stands in: what an answer names. */
@Immutable
data class CardTab(val id: String, val name: String, val q: String)

/**
 * A question of his: a card on the Needs you list, or one still open in its own tab. [options] has
 * Jarvis's [pick] highlighted; [due] (ms) is when silence takes it, null when it waits for him.
 */
@Immutable
data class CardView(
    val tabs: List<CardTab>,
    val text: String,
    val options: List<String> = listOf("Yes", "No"),
    val pick: Int = 0,
    val why: String = "",
    val level: Level = Level.Default,
    val due: Long? = null,
    val at: Long = 0,
) {
    val key get() = tabs.firstOrNull()?.let { "${it.id}/${it.q}" }.orEmpty()
    val name get() = tabs.joinToString(" · ") { it.name }
}

/** A line of the digest: [what] is done, default, assumed, report, notify or quiet. */
@Immutable
data class DigestView(val id: String, val name: String, val at: Long, val offset: Long, val what: String, val line: String)

/** The server's no, in its words, to an answer or the Auto button in tab [id]. */
@Immutable
data class Notice(val id: String, val text: String)

enum class Sending { Sent, Sending, Refused }

@Immutable
data class Quoted(val to: Long, val quote: String, val text: String)

/** Something in a tab's record, oldest first. [key] stays the same as it grows or lands. */
@Immutable
sealed interface Entry {
    val key: String

    data class Yours(
        override val key: String,
        val text: String,
        val voice: Boolean = false,
        val quotes: List<Quoted> = emptyList(),
        val files: List<String> = emptyList(),
        val state: Sending = Sending.Sent,
        val why: String = "",
    ) : Entry

    /** [offset] is where a reply points; null while it's still arriving. */
    data class Answer(
        override val key: String,
        val offset: Long?,
        val text: String,
        val files: List<String> = emptyList(),
    ) : Entry

    data class Report(
        override val key: String,
        val from: String,
        val line: String,
        val text: String,
    ) : Entry

    data class Question(override val key: String, val text: String, val closed: Boolean = false) : Entry

    /** A line from the app itself: work that failed, say. */
    data class Quiet(override val key: String, val text: String) : Entry
}

/** What a tab is doing now: [what] in words, [since] in ms of the clock, for "12s". */
@Immutable
data class WorkView(val what: String, val since: Long)

@Immutable
data class TabView(
    val tile: TileView,
    val entries: List<Entry> = emptyList(),
    val work: WorkView? = null,
    val closed: Boolean = false,
    /** Waiting for the record to arrive for the first time. */
    val loading: Boolean = false,
    /** The one small line about the jobs it started, empty with none. They run on their own: there's nothing to tap. */
    val jobs: String = "",
    /** His held words wait in it, for the turn's end or for Send with nothing typed. */
    val waiting: Boolean = false,
    /** Its own questions still open, the same cards as on Needs you: answering either clears both. */
    val asks: List<CardView> = emptyList(),
)

@Immutable
data class ClosedTab(val id: String, val name: String, val line: String = "")

/** Words waiting under a paragraph in an old draft: the whole paragraph, or only the [quote] he picked. */
@Immutable
data class Note(val quote: String, val text: String)

/** Where an old reply to the answer at offset [to] was kept. */
fun draftKey(id: String, to: Long) = "$id/$to"

/** A reply from the old reply page, kept only until [foldDrafts] turns it into held replies. */
@Immutable
data class ReplyDraft(val to: Long, val notes: Map<Int, Note> = emptyMap(), val end: String = "")

/**
 * A paragraph or words of the answer at offset [to], waiting to go as a quote with his next message.
 * With no [text] it's a mark; with [text] it's a held reply, words of its own under the quote. [block]
 * keeps them in the order they were said. What it says is kept here, as the laptop keeps it, so it
 * outlives the record it came from.
 */
@Immutable
data class QuoteMark(val to: Long, val block: Int, val quote: String, val text: String = "") {
    fun same(o: QuoteMark) = to == o.to && block == o.block && quote == o.quote
    val held get() = text.isNotBlank()
}

/** Puts this one in, or swaps the one it's on. */
internal fun List<QuoteMark>.with(mark: QuoteMark) = (without(mark) + mark).sortedWith(compareBy({ it.to }, { it.block }))

internal fun List<QuoteMark>.without(mark: QuoteMark) = filterNot { it.same(mark) }

/** Marks it, or takes the mark back. */
internal fun List<QuoteMark>.toggled(mark: QuoteMark) = if (any { it.same(mark) }) without(mark) else with(mark)

/** The call going on, for the strip that leads back to it: its tab, that tab's name, the call's line. */
@Immutable
data class OnCall(val tab: String?, val name: String, val line: String)

/** A picture or file waiting to go with the next words: a copy on the phone. */
@Immutable
data class Attached(val path: String, val name: String, val picture: Boolean)

@Immutable
data class DeckState(
    val tiles: List<TileView> = emptyList(),
    val tabs: Map<String, TabView> = emptyMap(),
    val closed: List<ClosedTab> = emptyList(),
    val online: Boolean = true,
    /** The mic button is on: his words are collecting. */
    val dictating: Boolean = false,
    val call: OnCall? = null,
    /** What goes with his next words, whichever tab he sends them from. */
    val attached: List<Attached> = emptyList(),
    /** What he said that waits for a tap, a jump or Send; with the reply box open it goes into the box. */
    val heard: String = "",
    /** The box's words per tab, so a typed line survives a swipe or a restart. */
    val typed: Map<String, String> = emptyMap(),
    /** Files fetched so far, "tab/name" to a path; an empty path means the server doesn't have it. */
    val files: Map<String, String> = emptyMap(),
    val drafts: Map<String, ReplyDraft> = emptyMap(),
    /** What's marked and the replies held per tab, waiting to go with his next message. */
    val marks: Map<String, List<QuoteMark>> = emptyMap(),
    /** The tab just opened from words at the wall, for the screen to zoom into, once; [WALL] zooms out to it. */
    val opened: String? = null,
    /** Needs you, urgent first, then by when Jarvis takes its pick. */
    val cards: List<CardView> = emptyList(),
    val digest: List<DigestView> = emptyList(),
    val notice: Notice? = null,
)

/** The key of the wall's own box in [DeckState.typed]. */
const val WALL = ""

/** [DeckState.opened] for a question's dialog on Needs you: this, then "<tab>/<q>" (a notification's tap). */
const val ASK = "?"

/** What the screens can ask for. All of it goes to the server, which answers by changing [state]. */
interface Deck {
    val state: StateFlow<DeckState>
    fun show(ids: List<String>)
    /** Words, what's attached, and the [quotes] they follow: to tab [id], or to a new one (null). */
    fun send(id: String?, text: String, quotes: List<Quoted> = emptyList())
    fun type(id: String, text: String)
    /** What's marked and held in tab [id] is now this. */
    fun marks(id: String, marks: List<QuoteMark>)
    fun seen(id: String)
    fun close(id: String)
    fun reopen(id: String)
    fun move(id: String, to: Int)
    /** Sets the tab's model and effort, from its next message. */
    fun mode(id: String, model: String, effort: String)
    /** He's on this tab now, or at the wall (null). A jump sends what waits. */
    fun here(id: String?)
    /** What he said on screen: `send` sends it, otherwise it was taken into a box. */
    fun words(send: Boolean)
    /** Stops what the tab is writing; what's written stays. */
    fun stop(id: String)
    /** A reply he holds: the server keeps it for the turn's end, as the laptop's enter. */
    fun hold(id: String, quote: Quoted)
    /** What he holds in tab [id] goes in now, as the laptop's ctrl+enter. */
    fun release(id: String)
    /** Asks for a file beside the record; it lands in [DeckState.files]. */
    fun file(id: String, name: String)
    fun openedDone()
    /** Opens this tab, or goes out to the wall with [WALL], now or when the screen next comes up. */
    fun landed(id: String)
    /** The mic button: starts or stops collecting his words. */
    fun dictate()
    /** The call button: a call in tab [id], or a new one (null). */
    fun call(id: String?)
    /** Back to the call going on. */
    fun openCall()
    /** The attach button: he picks pictures or files. */
    fun pick()
    fun detach(path: String)
    /** The old home, from the wall's menu. */
    fun home()
    fun forum()
    /** His answer to [card]: option [pick] (null for only words) and his [note], to every tab it stands in. */
    fun answer(card: CardView, pick: Int?, note: String)
    /** The Auto button: tab [id]'s autopilot on or off. */
    fun autopilot(id: String, on: Boolean)
    /** He read the [DeckState.notice]. */
    fun noticed()
}
