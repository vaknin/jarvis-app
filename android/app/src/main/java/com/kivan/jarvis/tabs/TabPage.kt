package com.kivan.jarvis.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.launch

/** One tab: the record, what's arriving, and the box. Nothing above the record but its name. */
@Composable
fun TabPage(
    tab: TabView,
    typed: String,
    online: Boolean,
    active: Boolean,
    onWall: () -> Unit,
    onType: (String) -> Unit,
    onSend: (String, List<Quoted>) -> Unit,
    onClose: () -> Unit,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
    heard: String = "",
    onHeard: () -> Unit = {},
    onStop: () -> Unit = {},
    onRelease: () -> Unit = {},
    /** His words, and the quotes they follow, held on the server as the laptop's enter: they wait for send-all. */
    onHoldWords: (String, List<Quoted>) -> Unit = { _, _ -> },
    marks: List<QuoteMark> = emptyList(),
    onMarks: (List<QuoteMark>) -> Unit = {},
    onTaken: () -> Unit = {},
    onMode: (String, String) -> Unit = { _, _ -> },
    /** The server's no to an answer or to Auto here, in its words. */
    notice: String? = null,
) {
    val deck = LocalDeck.current
    val notes = rememberNotes()
    var asking by remember { mutableStateOf<String?>(null) }
    val answer = { c: CardView, pick: Int? ->
        deck?.answer(c, pick, notes[c.key].orEmpty())
        notes.remove(c.key)
        asking = null
    }
    val attached = LocalTalk.current.attached.isNotEmpty()
    val rows = remember(tab.entries) { tab.entries.asReversed() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val buzz = rememberBuzz()
    val question = remember(tab.entries) { tab.entries.lastOrNull { it is Entry.Question && !it.closed } as Entry.Question? }
    val questionHidden by remember(question) {
        derivedStateOf {
            question != null && list.layoutInfo.visibleItemsInfo.none { it.key == question.key }
        }
    }
    val atEnd by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 40 } }
    var seenEnd by remember { mutableStateOf(rows.firstOrNull()?.key) }
    if (atEnd) seenEnd = rows.firstOrNull()?.key
    val hasNew = !atEnd && rows.firstOrNull()?.key != seenEnd
    val hasWork = tab.work != null
    // Coming to a new answer taller than the screen, start at its first line, not its last.
    val wasNew = remember { tab.tile.unread }
    LaunchedEffect(rows.isEmpty()) {
        if (!wasNew) return@LaunchedEffect
        val i = rows.indexOfFirst { it is Entry.Answer }
        if (i < 0) return@LaunchedEffect
        list.scrollToItem(i)
        val info = list.layoutInfo
        val tall = info.visibleItemsInfo.firstOrNull { it.index == i }?.size ?: 0
        val room = info.viewportEndOffset - info.viewportStartOffset
        if (tall > room) list.scrollToItem(i, tall - room)
    }

    // The reply being written: the box writes into it until he sends, holds or drops it. It stays in
    // [marks] as he types, so it's saved like the rest; [replyBefore] is what a cancel goes back to.
    var reply by remember { mutableStateOf<QuoteMark?>(null) }
    var replyBefore by remember { mutableStateOf<QuoteMark?>(null) }
    val writing = reply?.let { r -> marks.firstOrNull { it.same(r) }?.text.orEmpty() }
    val ask = remember { FocusRequester() }
    val open = { m: QuoteMark ->
        val was = marks.firstOrNull { it.same(m) }
        // Another reply left open keeps its words, as the laptop's does.
        reply = was ?: m
        replyBefore = was
        scope.launch { ask.requestFocus() }
        Unit
    }
    fun write(words: String) {
        val r = reply ?: return
        // Being written, it isn't quiet: only Hold makes it go by itself, so a half-done edit never goes.
        onMarks(if (words.isBlank()) marks.without(r) else marks.with(r.copy(text = words, quiet = false)))
    }
    /** Leaves the box. Kept words stay held; a cancel, or nothing written, goes back to what was there. */
    fun closed(keep: Boolean): List<QuoteMark> {
        val r = reply ?: return marks
        val before = replyBefore
        reply = null
        replyBefore = null
        // Emptying a held reply and sending takes it back; only Cancel returns the words.
        if (keep && (writing?.isNotBlank() == true || before?.held == true)) return marks
        return if (before != null) marks.with(before) else marks.without(r)
    }
    val send = {
        val words = said(typed, if (reply == null) heard else "")
        val all = closed(keep = true)
        if (words.isNotEmpty() || attached || all.isNotEmpty()) {
            buzz.sent()
            // As on the laptop: each goes as a quote with its own words, or none; his message after them.
            onSend(words, all.map { Quoted(it.to, it.quote, it.text.trim()) })
            scope.launch { list.animateScrollToItem(0) }
        } else if (tab.waiting) {
            // Nothing typed: what he held goes in now, as the laptop's ctrl+enter.
            buzz.sent()
            onRelease()
        }
    }
    /**
     * Hold: the reply stays here, his to open and edit, and goes quiet by itself at the end of the
     * tab's turn or when he leaves the app, as the laptop's enter; Send takes it along sooner. Held
     * again after an edit, it's the same one: it goes once, with its last words.
     */
    val hold = {
        val r = reply
        val words = writing?.trim().orEmpty()
        if (r != null && words.isNotEmpty()) {
            reply = null
            replyBefore = null
            onMarks(marks.with(r.copy(text = words, quiet = true)))
            buzz.sent()
        }
    }
    /**
     * A long press on Send holds, whatever is in the box: an open reply stays here as Hold keeps it; his own
     * words wait on the server, with what he marked, for send-all or a turn that runs anyway. Before, Hold
     * showed only on an open reply with words in it, so a plain message had no quiet way out (t104).
     */
    val holdAll = {
        if (reply != null) {
            hold()
        } else {
            val words = said(typed, heard)
            if (words.isNotEmpty()) {
                val plain = marks.filterNot { it.held }
                onHoldWords(words, plain.map { Quoted(it.to, it.quote, "") })
                if (plain.isNotEmpty()) onMarks(marks.filter { it.held })
                buzz.sent()
            }
        }
    }
    // With a reply open his spoken words write into it.
    LaunchedEffect(heard, reply) {
        if (heard.isBlank() || reply == null) return@LaunchedEffect
        write(if (writing.isNullOrBlank()) heard else "$writing $heard")
        onTaken()
    }

    val bar = remember(tab.issues) { issuesBar(tab.issues) }
    var issuesOpen by remember { mutableStateOf(false) }
    if (issuesOpen) IssuesSheet(tab.tile.name, tab.issues) { issuesOpen = false }

    Box(modifier.fillMaxSize()) {
    Page {
        Header(tab, onWall, onClose, onMode)
        AnimatedVisibility(!online) { OfflineBar() }
        AnimatedVisibility(
            questionHidden,
            enter = fadeIn(tween(Curves.QUICK)) + slideInVertically(tween(Curves.QUICK)) { -it / 2 },
            exit = fadeOut(tween(Curves.QUICK)) + slideOutVertically(tween(Curves.QUICK)) { -it / 2 },
        ) {
            Strip(question?.text.orEmpty()) {
                val at = rows.indexOfFirst { it.key == question?.key }
                if (at >= 0) scope.launch { list.animateScrollToItem(at + if (hasWork) 1 else 0) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.TopCenter) {
            if (tab.loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Box(Modifier.width(96.dp).height(2.dp).workLine(look.faint, LocalGlide.current))
                }
            } else if (rows.isEmpty() && tab.work == null && tab.jobs.isEmpty() && bar == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Nothing here yet", color = look.faint, style = Words.label)
                }
            } else {
                LazyColumn(
                    Modifier.reading().fillMaxSize().testTag("record"),
                    state = list,
                    reverseLayout = true,
                    contentPadding = PaddingValues(start = Space.margin, end = Space.margin, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.turn, Alignment.Bottom),
                ) {
                    // His open questions sit at the end of the record, the same cards as on Needs you.
                    items(tab.asks.asReversed(), key = { "ask/${it.key}" }, contentType = { "ask" }) { c ->
                        AskCard(c, notes[c.key].orEmpty(), { notes[c.key] = it }, { asking = c.key }, { answer(c, it) })
                    }
                    if (tab.work != null) {
                        item(key = "work", contentType = "work") { WorkRow(tab.work, active && online) }
                    }
                    if (bar != null) {
                        // 2.0: its issues' bar stands where the jobs line was.
                        item(key = "issues", contentType = "issues") { IssuesBarRow(bar, { issuesOpen = true }) }
                    } else if (tab.jobs.isNotEmpty()) {
                        item(key = "jobs", contentType = "jobs") {
                            Text(tab.jobs, Modifier.testTag("jobs"), look.faint, style = Words.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(rows, key = { it.key }, contentType = { it::class }) { entry ->
                        EntryView(
                            tab.tile.id,
                            entry,
                            canMark = !tab.closed,
                            marks = marks,
                            replying = reply,
                            onMarks = onMarks,
                            onOpen = open,
                        )
                    }
                }
            }
            if (list.canScrollForward) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Brush.verticalGradient(listOf(look.canvas, look.canvas.copy(alpha = 0f)))),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                hasNew,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                enter = fadeIn(tween(Curves.QUICK)),
                exit = fadeOut(tween(Curves.QUICK)),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(look.raised)
                        .clickable { scope.launch { list.animateScrollToItem(0) } }
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    Alignment.Center,
                ) { Text("New answer below", color = look.ink, style = Words.small) }
            }
        }
        when {
            tab.closed -> ClosedBar(tab.tile.id, onReopen)
            else -> Dock(
                hint = when {
                    reply != null -> REPLY_HERE
                    question != null -> "Type your answer to this"
                    else -> "Message ${tab.tile.name}".trim()
                },
                text = writing ?: typed,
                onText = { if (reply != null) write(it) else onType(it) },
                onSend = { send() },
                onHold = { holdAll() },
                focus = ask,
                heard = if (reply != null) "" else heard,
                onHeard = onHeard,
                onStop = onStop.takeIf { tab.work != null },
                tab = tab.tile.id,
                ready = reply != null || marks.isNotEmpty() || tab.waiting,
                autopilot = tab.tile.autopilot,
                above = {
                    if (notice != null) NoticeBar(notice) { deck?.noticed() }
                    val r = reply
                    MarkTray(marks.filterNot { r != null && it.same(r) }, onMarks, open)
                    if (r != null) {
                        ReplyStrip(r.quote, typed, canHold = !writing.isNullOrBlank(), onHold = { hold() }, onCancel = { onMarks(closed(keep = false)) })
                    }
                },
            )
        }
    }
    tab.asks.firstOrNull { it.key == asking }?.let { c ->
        AskSheet(c, notes[c.key].orEmpty(), { notes[c.key] = it }, { answer(c, it) }, { asking = null }, {})
    }
    }
}

@Composable
private fun Header(tab: TabView, onWall: () -> Unit, onClose: () -> Unit, onMode: (String, String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Round(R.drawable.lucide_layout_grid, "Back to the wall", onWall)
        Text(
            "${tab.tile.n}",
            Modifier.padding(start = 4.dp, end = 10.dp),
            look.faint,
            style = Words.number,
        )
        Text(
            tab.tile.name,
            Modifier.weight(1f).semantics { heading() },
            look.ink,
            style = Words.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!tab.closed) ModelChip(tab.tile, onMode, Modifier.padding(horizontal = 6.dp))
        if (!tab.closed) {
            Box {
                Round(R.drawable.lucide_ellipsis_vertical, "More", { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = look.raised) {
                    DropdownMenuItem(
                        text = { Text("Close tab", color = look.ink, style = Words.label) },
                        onClick = {
                            menu = false
                            onClose()
                        },
                    )
                }
            }
        }
    }
}

/** The question scrolled away: one amber strip at the top until it's answered. */
@Composable
private fun Strip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(look.urgent)
            .clickable(onClick = onClick)
            .heightIn(min = Space.target)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Triangle(look.onUrgent, 14.dp)
        Text(text, color = look.onUrgent, style = Words.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EntryView(
    tab: String,
    entry: Entry,
    canMark: Boolean,
    marks: List<QuoteMark>,
    replying: QuoteMark?,
    onMarks: (List<QuoteMark>) -> Unit,
    onOpen: (QuoteMark) -> Unit,
) {
    when (entry) {
        is Entry.Yours -> YoursView(tab, entry)
        is Entry.Answer -> AnswerView(tab, entry, canMark, marks, replying, onMarks, onOpen)
        is Entry.Report -> ReportView(entry)
        is Entry.Question -> QuestionView(entry)
        is Entry.Quiet -> Text(entry.text, color = look.faint, style = Words.small)
    }
}

/** Aviv's words sit on a panel to the right; Jarvis's are open prose on the page. */
@Composable
private fun YoursView(tab: String, entry: Entry.Yours) {
    val ink = if (entry.state == Sending.Sending) look.soft else look.ink
    Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
        Column(Modifier.padding(start = 36.dp), horizontalAlignment = Alignment.End) {
            SelectionContainer {
                Column(
                    Modifier
                        .background(look.surface, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (q in entry.quotes) {
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(look.line, RoundedCornerShape(1.dp)))
                            Text(q.quote, Modifier.padding(start = 10.dp), look.soft, style = Words.small, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        if (q.text.isNotEmpty()) Text(q.text, color = ink, style = Words.yours)
                    }
                    if (entry.text.isNotEmpty()) Text(entry.text, color = ink, style = Words.yours)
                }
            }
            for (f in entry.files) FileCard(tab, f, Modifier.padding(top = 8.dp))
            if (entry.voice) {
                Glyph(R.drawable.lucide_mic, look.faint, 13.dp, Modifier.padding(top = 6.dp, end = 6.dp))
            }
            if (entry.state == Sending.Refused) {
                Text(entry.why.ifEmpty { "Didn't send" }, Modifier.padding(top = 5.dp, end = 4.dp), look.urgentText, style = Words.small)
            }
        }
    }
}

@Composable
private fun AnswerView(
    tab: String,
    entry: Entry.Answer,
    canMark: Boolean,
    marks: List<QuoteMark>,
    replying: QuoteMark?,
    onMarks: (List<QuoteMark>) -> Unit,
    onOpen: (QuoteMark) -> Unit,
) {
    val blocks = remember(entry.text) { Prose.parse(entry.text) }
    // 2.0's Done line: its headline bolder, its footer dim. Only that server writes one.
    val done = isDoneLine(entry.text)
    val buzz = rememberBuzz()
    val to = entry.offset
    // A hold that ends where it began is a mark, not the word Compose picked on the way: only a fresh
    // container lets go of it, since a selection has no public way to be cleared.
    var unselect by remember { mutableIntStateOf(0) }
    Column {
        key(unselect) {
            if (!canMark || to == null) {
                SelectionContainer {
                    ProseView(blocks) { i, b, m -> if (done) DoneBlock(i, blocks.lastIndex, b, m) { BlockView(b, modifier = it) } else BlockView(b, modifier = m) }
                }
            } else {
                Quotable({ words -> onOpen(QuoteMark(to, blocks.place(words), words)) }) {
                    ProseView(blocks) { i, block, spaced ->
                        val here = QuoteMark(to, i, block.quoted)
                        val on = marks.firstOrNull { it.same(here) }
                        val open = replying?.same(here) == true
                        MarkableBlock(
                            block,
                            state = when {
                                open -> Paragraph.Replying
                                on == null -> Paragraph.Plain
                                on.held -> Paragraph.Held
                                else -> Paragraph.Marked
                            },
                            onSwipe = { onOpen(here) },
                            onHold = {
                                // Words of his are never lost to a hold: it opens them instead.
                                if (on?.held == true) {
                                    onOpen(on)
                                } else {
                                    buzz.toggle(on == null)
                                    onMarks(marks.toggled(here))
                                }
                                unselect++
                            },
                            onTap = {
                                if (on?.held == true) {
                                    onOpen(on)
                                } else if (on != null) {
                                    buzz.toggle(false)
                                    onMarks(marks.without(on))
                                }
                            },
                            modifier = spaced,
                        ) { if (done) DoneBlock(i, blocks.lastIndex, block, Modifier) { BlockView(block) } else BlockView(block) }
                    }
                }
            }
        }
        for (f in entry.files) FileCard(tab, f, Modifier.padding(top = 12.dp))
    }
}

private enum class Paragraph { Plain, Marked, Held, Replying }

/** What the box says with a reply open. */
private const val REPLY_HERE = "Reply to this paragraph"

/** How far a paragraph travels before letting go of it replies. */
private val SWIPE = 64.dp

/**
 * One paragraph of an answer. A swipe to the right replies to it; a hold marks it and another takes the
 * mark back, as a tap on a marked one does; on a held reply either opens it again. A hold that goes on
 * to drag is still a text selection, and a sideways drag begins only well clear of a vertical one, so
 * only a swipe is ever consumed. Scrolling, links and selection are left be, and so is a swipe left to the
 * next tab; a swipe right over an answer replies instead of going back a tab, which the header still does.
 */
@Composable
private fun MarkableBlock(
    block: Block,
    state: Paragraph,
    onSwipe: () -> Unit,
    onHold: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit = { BlockView(block) },
) {
    val scope = rememberCoroutineScope()
    val buzz = rememberBuzz()
    var holding by remember { mutableStateOf(false) }
    val lit = state != Paragraph.Plain
    val wash by animateFloatAsState(if (lit || holding) 1f else 0f, tween(Curves.QUICK), label = "mark")
    val washed = look.surface
    val bar = look.ink
    val pull = remember { Animatable(0f) }
    val swipe by rememberUpdatedState(onSwipe)
    val hold by rememberUpdatedState(onHold)
    val tap by rememberUpdatedState(onTap)
    val tappable by rememberUpdatedState(lit)
    val reach = with(LocalDensity.current) { SWIPE.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .testTag("paragraph")
            .semantics {
                if (lit) stateDescription = if (state == Paragraph.Marked) "Marked to quote" else "Reply held"
                customActions = listOf(
                    CustomAccessibilityAction("Reply to this paragraph") {
                        onSwipe()
                        true
                    },
                    CustomAccessibilityAction(if (state == Paragraph.Marked) "Take the mark back" else "Mark to quote") {
                        onHold()
                        true
                    },
                )
            }
            .drawBehind {
                if (wash > 0f) {
                    val out = 8.dp.toPx()
                    drawRoundRect(washed.copy(alpha = wash), Offset(-out, -out / 2), Size(size.width + out * 2, size.height + out), CornerRadius(12.dp.toPx()))
                }
                if (lit) drawRoundRect(bar, Offset(-8.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    // Before the hold: a tap, a swipe, or something that isn't ours.
                    var swiping = false
                    val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (!c.pressed) return@withTimeoutOrNull !c.isConsumed
                            if (event.changes.size > 1 || c.isConsumed) return@withTimeoutOrNull false
                            val moved = c.position - down.position
                            if (moved.getDistance() > slop) {
                                if (moved.x > 0 && abs(moved.x) > abs(moved.y) * 2) {
                                    c.consume()
                                    swiping = true
                                }
                                return@withTimeoutOrNull false
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (swiping) {
                        var felt = false
                        var past = false
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            // It follows the finger, then drags behind it.
                            val x = (c.position.x - down.position.x).coerceAtLeast(0f)
                            val shown = if (x < reach) x else reach + (x - reach) / 3
                            scope.launch { pull.snapTo(shown) }
                            past = x >= reach
                            if (past && !felt) {
                                felt = true
                                buzz.tick()
                            }
                        }
                        scope.launch { pull.animateTo(0f, tween(Curves.QUICK)) }
                        // Back under the line before letting go is changing his mind.
                        if (past) swipe()
                        return@awaitEachGesture
                    }
                    if (quick != null) {
                        if (quick && tappable) tap()
                        return@awaitEachGesture
                    }
                    holding = true
                    try {
                        var still = true
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if ((c.position - down.position).getDistance() > slop) {
                                still = false
                                holding = false
                            }
                        }
                        if (still) hold()
                    } finally {
                        holding = false
                    }
                }
            },
    ) {
        Glyph(
            R.drawable.lucide_corner_down_left,
            look.soft,
            18.dp,
            Modifier.graphicsLayer {
                alpha = (pull.value / reach).coerceAtMost(1f)
                translationX = pull.value / 2 - 9.dp.toPx()
            },
        )
        Box(Modifier.graphicsLayer { translationX = pull.value }) { content() }
    }
}

/** What's waiting, on the box: how many of each, like the laptop's line, and each with a way out. */
@Composable
private fun MarkTray(marks: List<QuoteMark>, onMarks: (List<QuoteMark>) -> Unit, onOpen: (QuoteMark) -> Unit) {
    if (marks.isEmpty()) return
    val quiet = marks.count { it.held }
    val line = listOfNotNull(
        "● ${marks.size - quiet} marked".takeIf { quiet < marks.size },
        "↩ $quiet".takeIf { quiet > 0 },
    ).joinToString("  ")
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(line, Modifier.padding(start = 8.dp).testTag("marked"), look.soft, style = Words.small, maxLines = 1)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in marks) key(m.to, m.quote) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(look.surface)
                        .then(if (m.held) Modifier.clickable(onClickLabel = "Edit this reply") { onOpen(m) } else Modifier)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.widthIn(max = 180.dp)) {
                        // Held, it's greyed: waiting, not gone (Aviv, t104).
                        Text(m.quote, color = if (m.held) look.faint else look.soft, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.held) Text(m.text, color = look.soft, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Round(R.drawable.lucide_x, "Remove the quote: ${m.quote.take(40)}", { onMarks(marks.without(m)) }, size = 38.dp)
                }
            }
        }
    }
}

/** The reply open in the box: what it quotes, Hold to keep it for the next message, and the way out. */
@Composable
private fun ReplyStrip(quote: String, typed: String, canHold: Boolean, onHold: () -> Unit, onCancel: () -> Unit) {
    // His message waits in the box meanwhile, and goes along if he sends now.
    if (typed.isNotBlank()) {
        Text(typed, Modifier.padding(start = 8.dp, bottom = 4.dp), look.faint, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 8.dp).width(2.dp).fillMaxHeight().background(look.ink, RoundedCornerShape(1.dp)))
        Text(quote, Modifier.weight(1f).padding(start = 10.dp).testTag("replying"), look.soft, style = Words.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (canHold) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(look.surface)
                    .clickable(onClick = onHold)
                    .semantics { role = Role.Button }
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 14.dp),
                Alignment.Center,
            ) { Text("Hold", color = look.ink, style = Words.small) }
        }
        Round(R.drawable.lucide_x, "Cancel this reply", onCancel, size = 38.dp)
    }
}

/** Selecting words offers Quote this, which replies to just those words. */
@Composable
private fun Quotable(onQuote: (String) -> Unit, content: @Composable () -> Unit) {
    val real = LocalClipboard.current
    val catcher = remember(real) { Catcher(real) }
    catcher.onWords = onQuote
    CompositionLocalProvider(LocalClipboard provides catcher) {
        Box(
            Modifier
                .filterTextContextMenuComponents { c ->
                    if (c is TextContextMenuItem && c.key === TextContextMenuKeys.CopyKey) catcher.copy = c
                    true
                }
                .appendTextContextMenuComponents {
                    item(QUOTE, "Quote this") { catcher.catching { catcher.copy?.onClick?.invoke(this) } }
                },
        ) { SelectionContainer(content = content) }
    }
}

private val QUOTE = Any()

/**
 * Compose has no public way to ask a selection for its words, but its Copy has them. So Quote this
 * presses Copy with this clipboard in the way: the words land here and the real clipboard stays as it
 * was. Copy pressed by hand passes straight through.
 */
private class Catcher(private val real: Clipboard) : Clipboard {
    var copy: TextContextMenuItem? = null
    var onWords: (String) -> Unit = {}
    private var catching = false

    fun catching(press: () -> Unit) {
        catching = true
        press()
    }

    override val nativeClipboard: NativeClipboard get() = real.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (!catching) return real.setClipEntry(clipEntry)
        catching = false
        clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { onWords(it.toString()) }
    }
}

/** A report is one soft line that opens. */
@Composable
private fun ReportView(entry: Entry.Report) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(Curves.QUICK))
    val blocks = remember(entry.text) { Prose.parse(entry.text) }
    Column(Modifier.animateContentSize(tween(Curves.PAGE, easing = Curves.out))) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Fold the report" else "Open the report") { open = !open }
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(R.drawable.lucide_chevron_right, look.faint, 16.dp, Modifier.rotate(turn))
            Text(
                entry.line,
                Modifier.weight(1f),
                look.soft,
                style = Words.small.copy(fontSize = Words.label.fontSize, lineHeight = Words.label.lineHeight),
                maxLines = if (open) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (open) {
            SelectionContainer {
                ProseView(blocks, Modifier.padding(start = 24.dp, top = 6.dp, bottom = 4.dp), look.soft)
            }
        }
    }
}

/** A question: a filled amber panel, above the box. Once answered it goes faint. */
@Composable
private fun QuestionView(entry: Entry.Question) {
    val blocks = remember(entry.text) { Prose.parse(entry.text) }
    if (entry.closed) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(look.surface, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Answered", color = look.faint, style = Words.small)
            ProseView(blocks, color = look.soft)
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(look.urgent, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Triangle(look.onUrgent, 15.dp, Modifier.padding(top = 5.dp))
        SelectionContainer { ProseView(blocks, color = look.onUrgent) }
    }
}

/** Working: the line, what it's on, and after three seconds how long. */
@Composable
private fun WorkRow(work: WorkView, active: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(work.since, active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secs = ((now - work.since) / 1000).coerceAtLeast(0)
    val still = LocalStill.current
    // A turn with no step named yet is him writing, the first thing after a message lands.
    val said = work.what.ifEmpty { "Jarvis is writing" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!still) Box(Modifier.fillMaxWidth().height(2.dp).workLine(look.working, LocalGlide.current, segment = 0.18f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                said,
                Modifier.weight(1f),
                look.soft,
                style = Words.small,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (secs >= 3) Text(lasted(secs), Modifier.padding(start = 12.dp), look.faint, style = Words.number)
        }
    }
}

fun lasted(secs: Long): String = when {
    secs < 60 -> "${secs}s"
    secs < 3600 -> "${secs / 60}m ${secs % 60}s"
    else -> "${secs / 3600}h ${secs / 60 % 60}m"
}

@Composable
private fun ClosedBar(tab: String, onReopen: () -> Unit) {
    Column(Modifier.overKeyboard().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
    CallStrip(tab)
    Row(
        Modifier.padding(start = Space.margin - 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("This tab is closed", Modifier.weight(1f), look.faint, style = Words.label)
        Box(
            Modifier
                .height(Space.target)
                .clip(RoundedCornerShape(24.dp))
                .background(look.surface)
                .clickable(onClick = onReopen)
                .padding(horizontal = 20.dp),
            Alignment.Center,
        ) { Text("Reopen", color = look.ink, style = Words.label) }
    }
    }
}
