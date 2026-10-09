package com.kivan.tether.dibs.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.AskEnded
import com.kivan.tether.dibs.AskLine
import com.kivan.tether.dibs.Asking
import com.kivan.tether.dibs.ChatPin
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.Pending
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.askReading
import com.kivan.tether.dibs.askSubject
import com.kivan.tether.dibs.markdownBlocks
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay

// An Ask about conversation (task #102, DESIGN.md): a short talk with a helper that knows only one
// subject. The page opens the moment the button is tapped, keyed by the subject, before dibs lists
// it; the box works at once (lines wait as echoes and dibs queues them). Back returns where it came
// from. The user never sees the word "thread".

/** The words under a line typed before the overview, while it waits for it. */
internal const val WAITING_FOR_OVERVIEW = "Waiting for the overview"

/** The words under a line dibs hasn't listed long after it went ([Dibs.unheard]). */
internal const val NOT_TAKEN = "dibs hasn't taken this yet"

/** The page of a conversation dibs never listed ([Dibs.unheard]). */
internal const val COULD_NOT_OPEN = "dibs couldn't open this"

private const val READING_FOOT =
    "A short overview comes first, in about 20 seconds. You can type questions now: they're answered right after it."

@Composable
internal fun AskPage(about: String, view: DibsView) {
    val a = view.asking(about)
    // Once listed, a conversation that leaves the view (over 24 hours old) closes its page; one just
    // asked for isn't listed yet, and waits.
    var listed by rememberSaveable(about) { mutableStateOf(false) }
    LaunchedEffect(a != null) {
        if (a != null) listed = true else if (listed) Dibs.back()
    }
    val all by Dibs.pending.collectAsStateWithLifecycle()
    val pending = remember(all, about) { all.filter { it.ask == about } }
    // On screen: dibs clears "New answer" (once for each newest line, and for the overview).
    val n = a?.lines?.size ?: 0
    val newest = a?.lines?.lastOrNull()?.id
    val hasOverview = a?.overview != null
    LaunchedEffect(about, a != null, newest, hasOverview) {
        if (a != null) Dibs.seenAsk(about, n, newest, hasOverview)
    }
    // Done and Ask more (when each was tapped) show at once, until dibs's view agrees (ending or
    // ended; back from ended), or a tap dibs refused frees them again ([Dibs.TAP_MS] on the clock below).
    var doneAt by rememberSaveable(about) { mutableStateOf<Long?>(null) }
    var moreAt by rememberSaveable(about) { mutableStateOf<Long?>(null) }
    val state = a?.state
    LaunchedEffect(state) {
        if (state == "ending" || state == "ended") doneAt = null
        if (state != null && state != "ended") moreAt = null
    }

    // dibs lists an open and a line at once over a live link: one it still doesn't, well after the
    // link came up, wasn't taken (dibs refused it, or couldn't find the subject). The clock runs only
    // while the link is up.
    val link by Dibs.host.link.collectAsStateWithLifecycle()
    var upSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(link) { upSince = if (link == Link.CONNECTED) Dibs.now() else null }
    // When the page asked dibs to open it (Try again asks anew).
    var opened by remember(about) { mutableStateOf(Dibs.now()) }
    val waits = remember(a != null, pending, opened, doneAt, moreAt) {
        (if (a == null) listOf(opened to Dibs.ASK_WAIT_MS) else emptyList()) + pending.map { it.tsMs to Dibs.ASK_WAIT_MS } +
            listOfNotNull(doneAt, moreAt).map { it to Dibs.TAP_MS }
    }
    val now by produceState(Dibs.now(), upSince, waits) {
        val up = upSince ?: return@produceState
        for (at in waits.map { (since, wait) -> maxOf(since, up) + wait }.sorted()) {
            val left = at - Dibs.now()
            if (left > 0) delay(left)
            value = Dibs.now()
        }
    }
    val failed = a == null && Dibs.unheard(opened, upSince, now)
    val unheard = remember(pending, upSince, now) { pending.filter { Dibs.unheard(it.tsMs, upSince, now) }.mapTo(HashSet()) { it.uid } }
    val doneTapped = doneAt?.let { !Dibs.unheard(it, upSince, now, Dibs.TAP_MS) } == true
    val moreTapped = moreAt?.let { !Dibs.unheard(it, upSince, now, Dibs.TAP_MS) } == true

    val title = a?.title?.takeIf { it.isNotBlank() } ?: Dibs.askTitles[about] ?: askSubject(about)
    val eyebrow = a?.eyebrow?.takeIf { it.isNotBlank() } ?: "Opening".takeIf { !failed }
    Column(Modifier.fillMaxSize()) {
        PageBar(title, eyebrow) {
            val done = a?.done
            if (done != null && !doneTapped) {
                ActButton(done, "", Modifier.padding(end = Space.S)) {
                    doneAt = Dibs.now()
                    Dibs.askDone(about)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AskBody(about, a, pending, ending = doneTapped, failed = failed, unheard = unheard) {
                // Try again: dibs is asked to open it anew, and the wait starts over.
                opened = Dibs.now()
                Dibs.askMore(about)
            }
        }
        val more = a?.more
        when {
            // Nothing to type into: dibs didn't open it (its card has the way back).
            failed -> Unit
            a != null && a.isEnded && !moreTapped -> if (more != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = 10.dp)) {
                    ActButton(more, "primary", icon = R.drawable.lucide_message_circle_question) {
                        moreAt = Dibs.now()
                        Dibs.askMore(about)
                    }
                }
            }
            else -> {
                val ending = a?.ending == true || doneTapped
                InputArea(Dibs.askBox(about), a?.placeholder ?: "Ask a question", attach = false, enabled = !ending)
            }
        }
    }
}

/** What an item of the page is, so the list keys and the newest line can be told. */
private sealed interface AskItem {
    val key: String

    data class Intro(val text: String) : AskItem {
        override val key get() = "_intro"
    }
    data class Reading(val words: String) : AskItem {
        override val key get() = "_reading"
    }
    data class Overview(val text: String) : AskItem {
        override val key get() = "_overview"
    }
    data class Chips(val id: String, val chips: List<String>, val title: String?) : AskItem {
        override val key get() = "_chips:$id"
    }
    data class Line(val line: AskLine) : AskItem {
        override val key get() = line.id
    }
    data class Echo(val p: Pending, val early: Boolean, val unheard: Boolean) : AskItem {
        override val key get() = p.uid
    }
    data class Status(val words: String, val card: Boolean) : AskItem {
        override val key get() = "_status"
    }
    data class Ended(val ended: AskEnded) : AskItem {
        override val key get() = "_ended"
    }
    data object Failed : AskItem {
        override val key get() = "_failed"
    }
}

/**
 * The page's items, oldest first: the intro, the reading card or the overview with its suggested
 * questions, the lines (only the newest answer's chips; the overview's until an answer has some),
 * the echoes not listed yet ([unheard]: by uid, the ones dibs didn't take), what it's doing, how it
 * ended. [failed]: dibs never listed it; that says so in place of the reading card.
 */
private fun askItems(about: String, a: Asking?, pending: List<Pending>, ending: Boolean, failed: Boolean, unheard: Set<String>): List<AskItem> {
    val out = ArrayList<AskItem>()
    a?.intro?.let { out += AskItem.Intro(it) }
    val beforeOverview = a == null || (a.overview == null && (a.state == "reading" || a.state.isEmpty()))
    if (failed) out += AskItem.Failed else if (beforeOverview) out += AskItem.Reading(a?.reading ?: askReading(about))
    a?.overview?.let { o ->
        out += AskItem.Overview(o.text)
        if (o.chips.isNotEmpty() && !a.ending && !a.isEnded && !ending && a.lines.none { it.who == "dibs" && it.chips.isNotEmpty() }) {
            out += AskItem.Chips("overview", o.chips, "Suggested questions")
        }
    }
    val lines = a?.lines.orEmpty()
    // Ending or ended: nothing more can be asked, so no suggestions.
    val open = a == null || (!a.ending && !a.isEnded && !ending)
    val newestDibs = lines.lastOrNull { it.who == "dibs" }.takeIf { open }
    for (l in lines) {
        out += AskItem.Line(l)
        if (l === newestDibs && l.chips.isNotEmpty()) out += AskItem.Chips(l.id, l.chips, null)
    }
    val listed = lines.mapNotNullTo(HashSet()) { it.uid }
    for (p in pending) if (p.uid !in listed) out += AskItem.Echo(p, early = beforeOverview, unheard = p.uid in unheard)
    when {
        a?.status != null -> out += AskItem.Status(a.status.words, card = a.ending || a.isEnded)
        // Done tapped: it says so at once, until dibs's view does.
        ending && a != null && !a.isEnded -> out += AskItem.Status("Ending", card = true)
    }
    a?.ended?.takeIf { a.isEnded }?.let { out += AskItem.Ended(it) }
    return out
}

@Composable
private fun AskBody(
    about: String,
    a: Asking?,
    pending: List<Pending>,
    ending: Boolean,
    failed: Boolean,
    unheard: Set<String>,
    retry: () -> Unit,
) {
    val items = remember(about, a, pending, ending, failed, unheard) { askItems(about, a, pending, ending, failed, unheard) }
    val reversed = remember(items) { items.asReversed() }
    val state = rememberLazyListState()
    // The conversation reads from the top until dibs lists a line, then sits at its newest. It never
    // moves on its own after that: pinned (kept by the user's scrolling only) it follows the newest end.
    val talking = items.any { it is AskItem.Line }
    val newest = items.lastOrNull()?.key
    val pin = remember { ChatPin() }
    TrackPin(state, pin, reversed = true)
    SideEffect { if (talking && pin.pinned && !state.isScrollInProgress) state.requestScrollToItem(0) }
    LaunchedEffect(newest, talking) {
        // Before the first line: the reading card or the overview from its top.
        if (newest != null && !talking) {
            state.scrollToItem(reversed.lastIndex)
            pin.pinned = true
        }
    }
    val asked = remember(a?.asked, pending) { (a?.asked.orEmpty() + pending.map { it.text }).toSet() }
    val canAsk = a == null || (!a.ending && !a.isEnded && !ending)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp, if (talking) Alignment.Bottom else Alignment.Top),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.XS, bottom = Space.S),
    ) {
        items(reversed.size, key = { reversed[it].key }) { i ->
            when (val item = reversed[i]) {
                is AskItem.Intro -> AskNote(item.text, icon = false)
                is AskItem.Reading -> ReadingCard(item.words)
                is AskItem.Overview -> OverviewCard(item.text)
                is AskItem.Chips -> AskChips(item.chips, item.title, asked, canAsk) { Dibs.askSay(about, it) }
                is AskItem.Line -> AskLineView(item.line)
                is AskItem.Echo -> MyLine(
                    item.p.text,
                    wait = when {
                        item.unheard -> NOT_TAKEN
                        item.early -> WAITING_FOR_OVERVIEW
                        else -> null
                    },
                    sending = true,
                )
                is AskItem.Status -> StatusRow(item.words, item.card)
                is AskItem.Ended -> EndedCard(item.ended)
                AskItem.Failed -> FailedCard(retry)
            }
        }
    }
}

/** A line of the conversation: the user's on the right, dibs's as text on the left, a note small and centred. */
@Composable
private fun AskLineView(l: AskLine) {
    when (l.who) {
        "user" -> MyLine(l.text, l.wait, sending = false)
        "note" -> AskNote(l.text, icon = true)
        else -> Answer(l.text)
    }
}

/**
 * One of the user's lines, on the right in the accent's dim; one still waiting is dashed with [wait]
 * under it ("Answered together with the line above"). [sending]: not listed by dibs yet.
 */
@Composable
private fun MyLine(text: String, wait: String?, sending: Boolean) {
    val shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.fillMaxWidth(0.8f), contentAlignment = Alignment.CenterEnd) {
            Column(
                (if (wait != null) Modifier.dashed(Palette.Outline, 14.dp) else Modifier.background(Palette.AccentDim, shape))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(rememberLinked(text), style = bodyStyle, color = Palette.Text)
                when {
                    wait != null -> Text(wait, style = AppType.small, color = Palette.Muted)
                    sending -> Icon(
                        painterResource(R.drawable.lucide_clock),
                        "Sending",
                        Modifier.align(Alignment.End).size(14.dp),
                        tint = MineMeta,
                    )
                }
            }
        }
    }
}

/** dibs's answer: plain text with bold and lists, on the ground, selectable. */
@Composable
private fun Answer(text: String) {
    val blocks = remember(text) { runCatching { markdownBlocks(text) }.getOrNull() }
    Box(Modifier.fillMaxWidth(0.92f)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                if (blocks == null) Text(text, style = bodyStyle, color = Palette.Text) else blocks.forEach { Block(it, ReportLook) }
            }
        }
    }
}

/** A small centred line: the intro, or a note (a line passed to dibs, with its arrow). */
@Composable
private fun AskNote(text: String, icon: Boolean) {
    val words = remember(text, icon) {
        buildAnnotatedString {
            if (icon) {
                appendInlineContent("icon", "→")
                append(' ')
            }
            append(text)
        }
    }
    val inline = if (!icon) emptyMap() else mapOf(
        "icon" to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(painterResource(R.drawable.lucide_corner_up_right), null, Modifier.fillMaxSize(), tint = Palette.Accent)
        },
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            words,
            Modifier.fillMaxWidth(0.88f),
            style = AppType.small.copy(lineHeight = AppType.small.lineHeight * 1.06f),
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            inlineContent = inline,
        )
    }
}

/** While the helper reads: what it reads, a few skeleton lines, and that the box works already. */
@Composable
private fun ReadingCard(words: String) {
    Column(Modifier.card().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Dots()
            Text(
                words,
                Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                style = AppType.small.copy(fontSize = AppType.label.fontSize),
                color = Palette.Muted,
            )
        }
        Column(Modifier.padding(top = Space.S)) {
            for (w in listOf(0.92f, 0.78f, 0.85f, 0.4f)) {
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth(w).height(10.dp).background(Palette.SurfaceHigh, Pill))
            }
        }
        Text(READING_FOOT, Modifier.padding(top = 10.dp), style = AppType.small, color = Palette.Muted)
    }
}

/** dibs never listed the conversation: say so calmly, a way to ask again ([retry]) and the way back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailedCard(retry: () -> Unit) {
    Column(
        Modifier.card().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Text(COULD_NOT_OPEN, style = AppType.body, color = Palette.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            ActButton("Try again", "primary", onClick = retry)
            ActButton("Back", "") { Dibs.back() }
        }
    }
}

/** The overview: a card, not a bubble, so it reads as the starting point. */
@Composable
private fun OverviewCard(text: String) {
    val blocks = remember(text) { runCatching { markdownBlocks(text) }.getOrNull() }
    Column(Modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Overview")
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                if (blocks == null) Text(text, style = bodyStyle, color = Palette.Text) else blocks.forEach { Block(it, ReportLook) }
            }
        }
    }
}

/**
 * Suggested questions as chips that wrap (never cut): a tap sends one as the user's line. One
 * already asked shows a check, dashed and dimmed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskChips(chips: List<String>, title: String?, asked: Set<String>, enabled: Boolean, onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (title != null) Text(title, style = AppType.small, color = Palette.Muted)
        // Each chip takes a 48 dp touch target (its look stays 34 dp), which spaces the rows apart.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            for (c in chips) {
                val done = c in asked
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier.minimumInteractiveComponentSize()
                        .then(if (done) Modifier.dashed(Palette.Outline, 18.dp) else Modifier.background(Palette.SurfaceLow, shape).border(1.dp, Palette.Outline, shape))
                        .then(
                            if (!done && enabled) {
                                Modifier.clip(shape).clickable(role = Role.Button, onClickLabel = "Ask it") { onAsk(c) }
                            } else {
                                Modifier
                            },
                        )
                        // Read once, as asked (not its words again).
                        .then(if (done) Modifier.clearAndSetSemantics { contentDescription = "Asked: $c" } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (done) Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(16.dp), tint = Palette.Accent)
                    Text(c, style = AppType.label, color = if (done) Palette.Muted else Palette.Text)
                }
            }
        }
    }
}

/** What it's doing now: calm dots and its words; a card while it ends. */
@Composable
private fun StatusRow(words: String, card: Boolean) {
    Row(
        (if (card) Modifier.card().padding(14.dp) else Modifier.fillMaxWidth().padding(vertical = 2.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Dots()
        Text(
            words,
            Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
            style = AppType.small.copy(fontSize = AppType.label.fontSize),
            color = Palette.Muted,
        )
    }
}

/** How it ended, the lines kept in dibs's notes, and that the subject itself wasn't changed. */
@Composable
private fun EndedCard(e: AskEnded) {
    Column(
        Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.medium)
            .border(1.dp, Palette.Line, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (e.words.isNotBlank()) Eyebrow(e.words)
        if (e.kept.isNotEmpty()) {
            e.keptTitle?.let { Text(it, style = AppType.body, color = Palette.Text) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (k in e.kept) {
                    Row {
                        Text("•", Modifier.width(18.dp), style = bodyStyle, color = Palette.Text)
                        Text(remember(k) { inline(k) }, Modifier.weight(1f), style = bodyStyle, color = Palette.Text)
                    }
                }
            }
        }
        e.foot?.let { Text(it, style = AppType.small, color = Palette.Muted) }
    }
}

/** Three calm dots in the accent, pulsing in turn. */
@Composable
internal fun Dots(size: Dp = 6.dp) {
    val pulse = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 3) {
            val a by pulse.animateFloat(
                0.4f, 1f,
                infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(size).graphicsLayer { alpha = a }.background(Palette.Accent, CircleShape))
        }
    }
}

/** A dashed outline with rounded corners (a line still waiting, a question already asked). */
internal fun Modifier.dashed(color: Color, radius: Dp): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
