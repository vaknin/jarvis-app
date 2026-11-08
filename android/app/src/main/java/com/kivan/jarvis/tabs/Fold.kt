package com.kivan.jarvis.tabs

import android.content.Context
import androidx.compose.runtime.Immutable

/**
 * The library fold on the phone (the jarvis repo's notes/library-fold-plan.md, stages 5c and 6): one main
 * chat, a thread per subject, no tab row. Until 5b's binding the main chat and threads read today's tabs:
 * a thread is a tab, the main chat is what the tabs report and ask. Off until the switch stage.
 */
object Fold {
    private const val PREFS = "fold"
    private const val ON = "threads-on"

    /** The switch as the server last said it (`~/.jarvis/fold/threads-on` there), so the app opens on the right screen. */
    fun on(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON, on).apply()
    }
}

/** A step block's state. Each has its colour and its word, never colour alone. */
enum class StepState { Done, Running, Needs, Todo }

/** A thread's bar and the words beside it: "needs you", "2 of 4 done", "working", or nothing. */
@Immutable
data class ThreadSteps(val steps: List<StepState>, val words: String, val needs: Boolean)

/**
 * Iris's step blocks for a thread, from today's data: a block per issue it filed, in filing order. Done
 * ones are grey; the first open one runs (sky blue), or waits on him (orange); the rest are still to come.
 */
fun threadSteps(tile: TileView, issues: List<IssueView>): ThreadSteps {
    val shown = if (issues.size <= MAX_SEGMENTS) issues else issues.filter { it.open }.takeLast(MAX_SEGMENTS)
    val current = shown.indexOfFirst { it.open }
    val steps = shown.mapIndexed { i, issue ->
        when {
            !issue.open -> StepState.Done
            i == current && tile.needs -> StepState.Needs
            i == current && tile.working -> StepState.Running
            else -> StepState.Todo
        }
    }
    val words = when {
        tile.needs -> "needs you"
        issues.isNotEmpty() -> "${issues.count { !it.open }} of ${issues.size} done"
        tile.working -> "working"
        else -> ""
    }
    return ThreadSteps(steps, words, tile.needs)
}

/** The current step's state, for the line above the box: waiting on him, running, or done. */
fun threadNow(tile: TileView, issues: List<IssueView>): StepState = when {
    tile.needs -> StepState.Needs
    tile.working || issues.any { it.open } -> StepState.Running
    else -> StepState.Done
}

/** Where a thread is, for its top: needs you, its open issue's step, working, or Done once all are. */
fun threadPhase(tile: TileView, issues: List<IssueView>): String = when {
    tile.needs -> "needs you"
    issues.any { it.open } -> issues.first { it.open }.where()
    issues.isNotEmpty() -> "Done"
    tile.working -> "working"
    else -> ""
}

/** The board a thread's work is filed on, from its open issue (else its last); empty with none. */
fun threadBoard(issues: List<IssueView>): String = (issues.firstOrNull { it.open } ?: issues.lastOrNull())?.board.orEmpty()

/** What the main chat shows, oldest first: a calm line per thread's latest report, and every card waiting on him. */
@Immutable
sealed interface FoldLine {
    val key: String
    val at: Long

    /** A report: one grey line. [unread] keeps a small dot until he's seen its thread; it never reminds. */
    data class Report(override val key: String, override val at: Long, val thread: String, val name: String, val line: String, val done: Boolean, val unread: Boolean) : FoldLine

    /** A card still waiting for his answer: the orange-edged block. */
    data class Card(override val key: String, override val at: Long, val card: CardView) : FoldLine

    /** A message of the main chat's own talk: his words or Jarvis's reply. [offset] is where it is on the record. */
    data class Said(override val key: String, override val at: Long, val entry: Entry, val offset: Long?) : FoldLine
}

/**
 * The main chat's lines from today's data: only the latest report per thread, so lines never pile up,
 * and every card waiting for his answer. Oldest first, as a chat reads.
 */
fun foldLines(state: DeckState): List<FoldLine> {
    val unread = state.tiles.filter { it.unread }.map { it.id }.toSet()
    val reports = state.digest
        .filter { it.what == "done" || it.what == "report" }
        .groupBy { it.id }
        .map { (id, lines) ->
            val d = lines.maxBy { it.at }
            FoldLine.Report("r/$id", d.at, id, d.name, d.line, d.what == "done", id in unread)
        }
    val cards = state.cards.map { FoldLine.Card("c/${it.key}", it.at, it) }
    // What's still on its way has no time yet: it's the newest.
    val said = mainChat(state.tiles)?.let { state.tabs[it] }?.entries.orEmpty().mapNotNull { e ->
        when (e) {
            is Entry.Yours -> FoldLine.Said("m/${e.key}", e.at.takeIf { it > 0 } ?: Long.MAX_VALUE, e, e.offset)
            is Entry.Answer -> FoldLine.Said("m/${e.key}", e.at.takeIf { it > 0 } ?: Long.MAX_VALUE, e, e.offset)
            else -> null
        }
    }
    return (reports + cards + said).sortedBy { it.at }
}

/** How many threads are working, for the main chat's top line; null with none. */
fun workingLine(state: DeckState): String? = state.tiles.count { it.working }.takeIf { it > 0 }?.let { "$it working" }

/** The jump chip's words: only cards still waiting for his answer count; reports never do. Null with none. */
fun chipWords(waiting: Int): String? = when {
    waiting <= 0 -> null
    waiting == 1 -> "1 question"
    else -> "$waiting questions"
}

/** The main chat's name until Home is bound, as on the wall (the jarvis repo's `tabs/tui/src/fold.rs`). */
const val MAIN_NAME = "Jarvis"

/**
 * The main chat: the tab the server names (or, from a server that doesn't, the tab called Jarvis), never his first
 * tab: that old rule made a subject's tab the main chat. Null while it isn't on the wall: the link asks the server
 * for it, which brings a parked one back.
 */
fun mainChat(tiles: List<TileView>): String? =
    (tiles.firstOrNull { it.main } ?: tiles.filter { it.name.trim().equals(MAIN_NAME, ignoreCase = true) }.minByOrNull { it.n })?.id

/** Every tab but the main chat: the threads. */
fun foldThreads(tiles: List<TileView>): List<TileView> = mainChat(tiles).let { main -> tiles.filter { it.id != main } }

/**
 * The fold's move key under way (stage 5e) in the main chat [tab], pressed on the message at record offset [at]: the
 * server's subject [name] and the record [offsets] of the messages that would move. Empty [name]: still asking.
 * [sent]: he confirmed and the server is moving them.
 */
@Immutable
data class Moving(val tab: String, val at: Long?, val name: String = "", val offsets: List<Long> = emptyList(), val sent: Boolean = false) {
    /** The server answered with something to move, and he hasn't confirmed yet: the bar asks. */
    val lit get() = name.isNotEmpty() && offsets.isNotEmpty() && !sent
}

/** What lights up: exactly the lines at the offsets the server named, never guessed from where a line sits. */
fun lit(lines: List<FoldLine>, moving: Moving?): Set<String> {
    val at = moving?.takeIf { it.name.isNotEmpty() }?.offsets?.toSet() ?: return emptySet()
    return lines.filterIsInstance<FoldLine.Said>().filter { it.offset != null && it.offset in at }.map { it.key }.toSet()
}

/** The bar's question, as the wall asks it. */
fun moveAsk(m: Moving): String = "Move ${m.offsets.size} message${if (m.offsets.size == 1) "" else "s"} on ${m.name} into a new thread?"

/** The server's answer to the move key: lit up, or nothing to move, or its no, said as a notice. Stale answers change nothing. */
fun DeckState.subjected(m: Moving, name: String, offsets: List<Long>, why: String): DeckState = when {
    moving != m -> this
    why.isNotEmpty() -> copy(moving = null, notice = Notice(m.tab, why))
    offsets.isEmpty() -> copy(moving = null, notice = Notice(m.tab, "Nothing here has a subject to move yet."))
    else -> copy(moving = m.copy(name = name, offsets = offsets))
}

/** The move went through, or the server said no: either way nothing stays lit, and a line says which. */
fun DeckState.split(tab: String, name: String, moved: Int, why: String): DeckState = copy(
    moving = null,
    notice = Notice(tab, why.ifEmpty { "$moved message${if (moved == 1) "" else "s"} moved into the thread \"$name\"." }),
)
