package com.kivan.jarvis.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One tab: the record, what's arriving, and the box. Nothing above the record but its name. */
@Composable
fun TabPage(
    tab: TabView,
    typed: String,
    draft: ReplyDraft?,
    muted: Boolean,
    online: Boolean,
    active: Boolean,
    onWall: () -> Unit,
    onType: (String) -> Unit,
    onSend: (String) -> Unit,
    onReply: (Entry.Answer) -> Unit,
    onClose: () -> Unit,
    onReopen: () -> Unit,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(tab.entries) { tab.entries.asReversed() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val buzz = rememberBuzz()
    val question = remember(tab.entries) { tab.entries.lastOrNull { it is Entry.Question && !it.closed } as Entry.Question? }
    val lastAnswer = remember(tab.entries) { tab.entries.lastOrNull { it is Entry.Answer && it.offset != null }?.key }
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

    Page(modifier) {
        Header(tab, onWall, onClose)
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
            } else if (rows.isEmpty() && tab.work == null) {
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
                    if (tab.work != null) {
                        item(key = "work", contentType = "work") { WorkRow(tab.work, active && online) }
                    }
                    items(rows, key = { it.key }, contentType = { it::class }) { entry ->
                        EntryView(
                            tab.tile.id,
                            entry,
                            canReply = !tab.closed && !tab.tile.readOnly && !(question != null && entry.key == lastAnswer),
                            newest = entry.key == lastAnswer,
                            drafted = draft != null && entry is Entry.Answer && draft.to == entry.offset && !draft.empty,
                            onReply = onReply,
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
            tab.closed -> ClosedBar(onReopen, muted, onMute)
            tab.tile.readOnly -> Note("Read only, this came from a voice call", muted, onMute)
            else -> Dock(
                hint = if (question != null) "Type your answer to this" else "Message ${tab.tile.name}",
                text = typed,
                onText = onType,
                onSend = {
                    val words = typed.trim()
                    if (words.isNotEmpty()) {
                        buzz.sent()
                        onSend(words)
                        scope.launch { list.animateScrollToItem(0) }
                    }
                },
                muted = muted,
                onMute = onMute,
            )
        }
    }
}

@Composable
private fun Header(tab: TabView, onWall: () -> Unit, onClose: () -> Unit) {
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
    canReply: Boolean,
    newest: Boolean,
    drafted: Boolean,
    onReply: (Entry.Answer) -> Unit,
) {
    when (entry) {
        is Entry.Yours -> YoursView(tab, entry)
        is Entry.Answer -> AnswerView(tab, entry, canReply, newest, drafted, onReply)
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
                        Text(q.text, color = ink, style = Words.yours)
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
    canReply: Boolean,
    newest: Boolean,
    drafted: Boolean,
    onReply: (Entry.Answer) -> Unit,
) {
    val blocks = remember(entry.text) { Prose.parse(entry.text) }
    Column {
        SelectionContainer { ProseView(blocks) }
        for (f in entry.files) FileCard(tab, f, Modifier.padding(top = 12.dp))
        if (canReply && entry.offset != null) {
            Row(
                Modifier
                    .padding(top = 2.dp)
                    .offsetStart()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onReply(entry) }
                    .semantics { role = Role.Button }
                    .heightIn(min = Space.target)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val tone = if (newest || drafted) look.soft else look.faint
                Glyph(if (drafted) R.drawable.lucide_pencil else R.drawable.lucide_corner_down_left, tone, 15.dp)
                Text(if (drafted) "Continue reply" else "Reply", color = tone, style = Words.small)
            }
        }
    }
}

/** Pulls a text button's padding back, so its words line up with the prose above. */
private fun Modifier.offsetStart(): Modifier = offset(x = (-10).dp)

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
        if (!still) Box(Modifier.fillMaxWidth().height(2.dp).workLine(look.soft, LocalGlide.current, segment = 0.18f))
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
private fun ClosedBar(onReopen: () -> Unit, muted: Boolean, onMute: (Boolean) -> Unit) {
    Row(
        Modifier.overKeyboard().padding(start = Space.margin, end = 10.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("This tab is closed", Modifier.weight(1f), look.faint, style = Words.label)
        Box(
            Modifier
                .padding(end = 6.dp)
                .height(Space.target)
                .clip(RoundedCornerShape(24.dp))
                .background(look.surface)
                .clickable(onClick = onReopen)
                .padding(horizontal = 20.dp),
            Alignment.Center,
        ) { Text("Reopen", color = look.ink, style = Words.label) }
        MuteButton(muted, onMute)
    }
}

@Composable
private fun Note(text: String, muted: Boolean, onMute: (Boolean) -> Unit) {
    Row(
        Modifier.overKeyboard().padding(start = Space.margin, end = 10.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), look.faint, style = Words.label)
        MuteButton(muted, onMute)
    }
}
