package com.kivan.jarvis.tabs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One issue a tab filed (Jarvis 2.0, the jarvis repo's notes/jarvis2-plan.md section 10), as the server
 * sends it: [step] 0 Planning to 4 Done, never back. Without 2.0 the server sends none, and the tab
 * shows its jobs line as before.
 */
@Immutable
data class IssueView(
    val id: Long,
    val title: String,
    val board: String,
    val step: Int,
    val tryN: Int = 1,
    val label: String = "",
    val held: Boolean = false,
    val eta: String = "",
    val detail: List<String> = emptyList(),
) {
    val open: Boolean get() = step < 4
}

/** The most segments the bar draws; the words still count every issue (the jarvis repo's tabs/wire `SEGMENTS`). */
const val MAX_SEGMENTS = 8

/**
 * The tab's bar, the wall's (tabs/wire `issues_bar`): one segment per issue in filing order, each filled by its
 * own step, so a new issue adds a segment instead of pulling the rest back. Past [MAX_SEGMENTS], the most
 * recent open ones.
 */
@Immutable
data class IssuesBar(val segments: List<IssueView>, val done: Int, val total: Int, val held: Boolean) {
    /** "2 of 5 done · held", the words beside the bar. */
    val words: String get() = "$done of $total done" + if (held) " · held" else ""
}

/** The bar: null with no issues. Done ones stay on it for the day they went live, as the server sends them. */
fun issuesBar(issues: List<IssueView>): IssuesBar? {
    if (issues.isEmpty()) return null
    val shown = if (issues.size <= MAX_SEGMENTS) issues else issues.filter { it.open }.takeLast(MAX_SEGMENTS)
    return IssuesBar(shown, issues.count { !it.open }, issues.size, issues.any { it.held })
}

private val STEPS = listOf("Planning", "Building", "Testing", "Shipping", "Done")

/** Where an issue is, in words: the server's label, else its step, with the try from 2 on. */
fun IssueView.where(): String = label.ifEmpty { STEPS[step.coerceIn(0, 4)] + if (tryN > 1) " · try $tryN" else "" }

/** The bar where the jobs line stood: a tap opens the overview, the phone's alt+j. */
@Composable
fun IssuesBarRow(bar: IssuesBar, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "See this tab's issues") { onOpen() }
            .heightIn(min = 40.dp)
            .semantics { contentDescription = bar.words }
            .testTag("issues"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.width(112.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in bar.segments) {
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(look.line)) {
                    if (i.step > 0) {
                        Box(Modifier.fillMaxWidth(i.step.coerceIn(0, 4) / 4f).fillMaxHeight().clip(CircleShape).background(look.working))
                    }
                }
            }
        }
        Text(bar.words, color = look.soft, style = Words.small, maxLines = 1)
    }
}

/** Four dots, one a step past Planning: filled as far as it got. */
@Composable
private fun Stepper(step: Int, held: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..4) {
            val on = step >= i
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (!on) look.line else if (held) look.faint else look.working))
        }
    }
}

/**
 * The overview, the phone's alt+j: one row per issue, its title, board, stepper and where it is. Why it's
 * there and the rest show only when he opens a row ("reason and any other data only if I want the extra
 * information").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesSheet(tab: String, issues: List<IssueView>, onClose: () -> Unit) {
    var opened by remember { mutableLongStateOf(-1L) }
    ModalBottomSheet(onClose, containerColor = look.raised) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .testTag("issue-sheet"),
        ) {
            Text("Issues from $tab", Modifier.padding(start = 4.dp, bottom = 10.dp), look.ink, style = Words.title)
            // Open ones first, as they stand; the ones done today after them.
            for (i in issues.sortedBy { !it.open }) IssueRow(i, opened == i.id) { opened = if (opened == i.id) -1L else i.id }
        }
    }
}

@Composable
private fun IssueRow(i: IssueView, open: Boolean, onTap: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = if (open) "Fold" else "Why it's here") { onTap() }
            .animateContentSize(tween(Curves.QUICK))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(i.title, color = if (i.open) look.ink else look.soft, style = Words.label, maxLines = if (open) 4 else 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(i.step, i.held)
            val words = listOfNotNull(i.where(), "held".takeIf { i.held && i.open }, i.eta.takeIf { it.isNotEmpty() && i.open }, i.board.takeIf { it.isNotEmpty() }?.let { "$it board" })
            Text(words.joinToString(" · "), color = look.faint, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) {
            val more = i.detail.ifEmpty { listOf("Nothing more to say yet.") }
            for (d in more) Text(d, Modifier.padding(top = 2.dp), look.soft, style = Words.small)
        }
    }
}

/** A Done line from 2.0's code (`✅ Done: …`): today's "Done: TITLE" never starts so. */
fun isDoneLine(text: String): Boolean = text.startsWith("✅ Done: ")

/** How a Done line's blocks read: its headline bolder, its last italic paragraph a dim footer. */
@Composable
fun DoneBlock(i: Int, last: Int, block: Block, modifier: Modifier, plain: @Composable (Modifier) -> Unit) {
    when {
        i == 0 && block is Block.Para -> Text(block.shown, modifier, look.ink, style = Words.prose.copy(fontWeight = FontWeight.W600))
        i == last && i > 0 && block is Block.Para && block.spans.all { it.kind == Kind.Italic || it.text.isBlank() } ->
            Text(block.shown, modifier.padding(top = 2.dp), look.faint, style = Words.small)
        else -> plain(modifier)
    }
}
