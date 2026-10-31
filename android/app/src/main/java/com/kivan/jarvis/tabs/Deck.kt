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
    val readOnly: Boolean = false,
)

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
)

@Immutable
data class ClosedTab(val id: String, val name: String, val line: String = "")

/** Words waiting under a paragraph: the whole paragraph, or only the [quote] he picked. */
@Immutable
data class Note(val quote: String, val text: String)

/** A reply being written. Kept per tab until it's sent, whatever else happens. */
@Immutable
data class ReplyDraft(val to: Long, val notes: Map<Int, Note> = emptyMap(), val end: String = "") {
    val empty get() = end.isBlank() && notes.values.all { it.text.isBlank() }
    val count get() = notes.values.count { it.text.isNotBlank() } + if (end.isBlank()) 0 else 1
}

@Immutable
data class DeckState(
    val tiles: List<TileView> = emptyList(),
    val tabs: Map<String, TabView> = emptyMap(),
    val closed: List<ClosedTab> = emptyList(),
    val online: Boolean = true,
    val muted: Boolean = false,
    /** The box's words per tab, so a typed line survives a swipe or a restart. */
    val typed: Map<String, String> = emptyMap(),
    val drafts: Map<String, ReplyDraft> = emptyMap(),
    /** The tab just opened from words at the wall, for the screen to zoom into, once. */
    val opened: String? = null,
)

/** What the screens can ask for. All of it goes to the server, which answers by changing [state]. */
interface Deck {
    val state: StateFlow<DeckState>
    fun show(ids: List<String>)
    fun start(text: String)
    fun say(id: String, text: String, quotes: List<Quoted> = emptyList())
    fun type(id: String, text: String)
    fun draft(id: String, draft: ReplyDraft?)
    fun seen(id: String)
    fun close(id: String)
    fun reopen(id: String)
    fun move(id: String, to: Int)
    fun mute(on: Boolean)
    fun openedDone()
}
