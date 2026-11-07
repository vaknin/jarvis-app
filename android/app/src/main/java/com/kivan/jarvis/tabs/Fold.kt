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

    /** The off switch: false until the switch stage turns it on (`am start ... --ez fold true`). */
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
    return (reports + cards).sortedBy { it.at }
}

/** How many threads are working, for the main chat's top line; null with none. */
fun workingLine(state: DeckState): String? = state.tiles.count { it.working }.takeIf { it > 0 }?.let { "$it working" }

/** The jump chip's words: only cards still waiting for his answer count; reports never do. Null with none. */
fun chipWords(waiting: Int): String? = when {
    waiting <= 0 -> null
    waiting == 1 -> "1 question"
    else -> "$waiting questions"
}
