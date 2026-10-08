package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsActivity
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.EchoRow
import com.kivan.tether.dibs.LineRow
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.YourTask
import com.kivan.tether.dibs.duration
import com.kivan.tether.dibs.ranMinutes
import com.kivan.tether.dibs.storyWords
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.flow.first

// A task's page (docs/DIBS-APP.md, "Your tasks"): its state and times, its open questions, what it
// did, its result, its full story, what was asked, the transcript, and the earlier messages with its
// agent, read-only.
// The user talks only to dibs (word 221): no box here; "Ask dibs about it" opens the dibs chat with the
// task named, and dibs passes on what's for the agent.

@Composable
internal fun TaskPage(id: Long, view: DibsView) {
    val t = view.task(id) ?: return Gone()
    val now by rememberNow()
    val armed = rememberArmed()
    // Open: it's read, and its ping goes (again whenever new lines arrive while it's open).
    val newest = t.talk.lastOrNull()?.id
    LaunchedEffect(id, newest) { Dibs.seenTask(t) }

    val all by Dibs.pending.collectAsStateWithLifecycle()
    val pending = remember(all, id) { all.filter { it.task == id } }
    val look = remember(id, t.label) { ChatLook(name = t.label, hide = false, days = "day:t$id:") }
    val rows = rememberChatRows(t.talk, pending, look)
    val echoes = remember(pending) { pending.associateBy { it.uid } }

    val state = rememberLazyListState()
    val last = rows.lastOrNull()
    val lastMine = last is EchoRow || (last is LineRow && last.line.mine)
    var placed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(last?.key, t.busy) {
        // The first run comes before the list's first measure: wait for its items.
        val total = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        val nearEnd = (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 3
        // First: at the top (the report), unless the agent has replied since the page was last open.
        val follow = if (!placed) {
            val line = t.talk.lastOrNull()
            line != null && !line.mine && !line.note && t.talk.any { it.mine } && Dibs.chatSeen[id] != line.id
        } else {
            nearEnd || lastMine
        }
        placed = true
        if (follow) state.animateScrollToItem(total - 1)
    }
    val newestNow by rememberUpdatedState(newest)
    DisposableEffect(id) { onDispose { newestNow?.let { Dibs.chatSeen[id] = it } } }

    SelectHost {
    Column(Modifier.fillMaxSize()) {
        PageBar(t.label, t.project) { TaskActions(t, view, armed) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = state,
            contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.S),
        ) {
            summary(t, view, now)
            if (rows.isNotEmpty()) item(key = "_chat") { Section("Earlier messages") }
            chatItems(rows, echoes, look)
            if (t.busy) item(key = "_busy") { Typing("${t.label} is on it") }
        }
        AskDibs(t, view)
    }
    }
}

/** The way to say something about this task: to dibs, in its chat, with the task named. */
@Composable
private fun AskDibs(t: YourTask, view: DibsView) {
    // A dibs that offers Ask about: the conversation about this task (its own page, back returns here).
    view.askFor(t)?.let { ask ->
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = 10.dp)) {
            ActButton(ask.label, "primary", icon = R.drawable.lucide_message_circle_question) { Dibs.askAbout(view, ask.about, t.label) }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Text("Questions or changes go through dibs.", Modifier.weight(1f), style = AppType.small, color = Palette.Muted)
        ActButton("Ask dibs about it", "primary") {
            val about = "About #${t.id} ${t.label}: "
            Dibs.dropAbout()
            if (!Dibs.chat.draft.startsWith(about)) Dibs.chat.draft = about + Dibs.chat.draft
            Dibs.pages.clear()
            Dibs.tab = DibsActivity.TAB_CHAT
        }
    }
}

/** Tick off once it's finished (Untick once ticked), Stop while it runs, and ⋮ Open on laptop. */
@Composable
private fun TaskActions(t: YourTask, view: DibsView, armed: Armed) {
    var menu by remember { mutableStateOf(false) }
    when {
        t.finishedState && Dibs.ticked(t) -> IconButton(onClick = { Dibs.untick(t) }) {
            Icon(painterResource(R.drawable.lucide_undo_2), "Untick", Modifier.size(22.dp), tint = Palette.Muted)
        }
        t.finishedState -> IconButton(onClick = {
            Dibs.tick(t)
            Dibs.back()
        }) { Icon(painterResource(R.drawable.lucide_circle_check), "Tick off", Modifier.size(22.dp), tint = Palette.Accent) }
        else -> {
            val k = "stop-task${t.id}"
            val busy = Dibs.busy("stop:${t.id}")
            ActButton(if (busy) "Stopping" else if (armed.key == k) "Stop it?" else "Stop", "danger", busy = busy) { armed.press(k) { Dibs.stop(t.id) } }
        }
    }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More", tint = Palette.Muted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Open on laptop") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(18.dp)) },
                onClick = {
                    menu = false
                    Dibs.openOnLaptop(view, t.id)
                },
            )
        }
    }
}

/** Top to bottom: state and times, open questions, what it did, result, the full story, what was asked, the transcript. */
private fun LazyListScope.summary(t: YourTask, view: DibsView, now: Long) {
    item(key = "_state") { StateBlock(t, now) }
    val questions = view.questions.filter { it.id in t.questions && "q${it.id}" !in Dibs.answered }
    items(questions, key = { "q${it.id}" }) { q -> QuestionCard(q, now, Modifier.animateItem().padding(top = Space.S)) }

    item(key = "_did") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Section(if (t.finishedState) "What it did" else "What it's doing")
            val text = t.report ?: t.line
            if (text.isNotBlank()) {
                SelectionContainer { Text(remember(text) { linkified(text) }, style = bodyStyle, color = Palette.Text) }
            } else {
                Text("Nothing to say yet.", style = AppType.body, color = Palette.Muted)
            }
        }
    }
    val result = t.result
    if (result != null && (result.reportMd || result.shipped.isNotEmpty())) {
        item(key = "_result") { ResultBlock(t) }
    }
    item(key = "_story") { StoryLink(t) }
    if (t.asked.isNotBlank()) {
        item(key = "_asked") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Section("You asked")
                Text(t.asked, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        }
    }
    item(key = "_transcript") { TranscriptLink(t) }
}

/** Its state in words, then when it started, how long it ran, when it finished. */
@Composable
private fun StateBlock(t: YourTask, now: Long) {
    Column(Modifier.padding(top = Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        TaskState(t, now)
        val ran = ranMinutes(t, now)
        val times = listOfNotNull(
            if (t.started > 0) "Started ${whenWords(t.started)}" else null,
            ran?.let { if (t.finishedState) "ran ${duration(it)}" else "running ${duration(it)}" },
            t.finished?.let { "finished ${whenWords(it)}" },
        ).joinToString(" · ")
        if (times.isNotEmpty()) Text(times, style = AppType.small, color = Palette.Muted)
    }
}

/** "Report" (its REPORT.md, in the reader) and where it shipped, with the For you lines. */
@Composable
private fun ResultBlock(t: YourTask) {
    val result = t.result ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Section("Result")
        if (result.reportMd) {
            Row(
                Modifier.clip(Pill).background(Palette.AccentDim).clickable { Dibs.open(Page.Report(t.id)) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(R.drawable.lucide_file_text), null, Modifier.size(16.dp), tint = Palette.Accent)
                Text("Report", style = AppType.label, color = Palette.Accent)
            }
        }
        for (s in result.shipped) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val changes = if (s.changes == 1) "1 change" else "${s.changes} changes"
                Text(if (s.changes > 0) "Shipped to ${s.repo} · $changes" else "Shipped to ${s.repo}", style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
                for (line in s.forYou) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(4.dp).background(Palette.Muted, CircleShape))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                    }
                }
            }
        }
    }
}

/** "Transcript": everything it did and said, from the top, on its own screen. */
@Composable
private fun TranscriptLink(t: YourTask) {
    LinkRow(R.drawable.lucide_scroll_text, "Transcript", "Everything it did and said", Modifier.padding(top = Space.S)) {
        Dibs.open(Page.Transcript(t.id))
    }
}

/**
 * "Full story": a long read of the task, written only when asked (a tap asks, the first time). Its
 * second line says where it stands.
 */
@Composable
private fun StoryLink(t: YourTask) {
    val written = t.story?.ts?.let { whenWords(it) }
    LinkRow(R.drawable.lucide_book_open, "Full story", storyWords(t.story, written), Modifier.padding(top = Space.L)) { Dibs.open(Page.Story(t.id)) }
}

/** A row that opens a screen of the task's: its icon, a title over one muted line, and ›. */
@Composable
private fun LinkRow(icon: Int, title: String, line: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.card().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, color = Palette.Text)
            Text(line, style = AppType.small, color = Palette.Muted)
        }
        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(18.dp), tint = Palette.Muted)
    }
}

/** A page whose task the view no longer lists. */
@Composable
internal fun Gone() {
    Column(Modifier.fillMaxSize()) {
        PageBar("Not listed any more")
        Box(Modifier.padding(horizontal = Space.L)) { Quiet("dibs doesn't list this task any more.") }
    }
}
