package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Peek
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.Session
import com.kivan.tether.dibs.Ship
import com.kivan.tether.dibs.Task
import com.kivan.tether.dibs.age
import com.kivan.tether.dibs.duration
import com.kivan.tether.dibs.holdWords
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import org.json.JSONObject

// The Work tab: tasks (what each does now, for how long), the live sessions, ships under way. A
// tap opens a task or session with its last lines (dibs reads them on `peek`). With a dibs that
// sends the user's tasks, this is "dibs's own work" folded at the bottom of the Tasks tab.

@Composable
internal fun WorkTab(view: DibsView) {
    val now by rememberNow()
    val armed = rememberArmed()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "_hero") {
            val n = view.tasks.size
            val sessions = view.sessions.size
            Hero("At work", "$n", "${if (n == 1) "task" else "tasks"} · $sessions ${if (sessions == 1) "session" else "sessions"}")
        }
        workItems(view, now, armed)
    }
}

/**
 * The Work tab's content, without its hero: tasks, sessions, ships. Folded under the Tasks tab
 * ([ownOnly]) it holds only dibs's background tasks: the user's own are rows above it.
 */
internal fun LazyListScope.workItems(view: DibsView, now: Long, armed: Armed, ownOnly: Boolean = false) {
    val tasks = if (ownOnly) view.tasks.filter { it.background } else view.tasks
    items(tasks, key = { "t${it.id}" }) { t -> TaskCard(t, view.peek, armed, Modifier.animateItem()) }
    if (tasks.isEmpty()) item(key = "_work_none") { Quiet("No tasks running.") }
    if (view.sessions.isNotEmpty()) {
        item(key = "_sessions") { Section("Sessions") }
        items(view.sessions, key = { "s${it.name}" }) { s -> SessionRow(s, view.peek, Modifier.animateItem()) }
    }
    if (view.ships.isNotEmpty()) {
        item(key = "_ships") { Section("Ships") }
        items(view.ships, key = { "ship${it.repo}" }) { s -> ShipRow(s, now) }
    }
}

/** Who a task's peek asks for: its session's pid (a task's chat may have another name), else its name. */
private val com.kivan.tether.dibs.Task.peekWho: String get() = pid?.toString() ?: name

/** Opens or closes [key]; opening asks dibs for [who]'s last lines. */
private fun openPeek(key: String, who: String) {
    val opening = Dibs.open[key] != true
    Dibs.open[key] = opening
    if (opening) Dibs.host.act("peek", JSONObject().put("who", who))
}

/** A task: its name, state in words, repo and time, what it's doing; open, its last lines, Tell it… and Stop. */
@Composable
private fun TaskCard(t: Task, peek: Peek?, armed: Armed, modifier: Modifier) {
    val key = "task:${t.id}"
    val open = Dibs.open[key] == true
    Column(
        modifier.card().clip(MaterialTheme.shapes.medium).clickable { openPeek(key, t.peekWho) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(t.name, Modifier.weight(1f), style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
            TaskState(t)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            t.repo?.let { Chip(it) }
            Text(duration(t.minutes), style = AppType.mono, color = Palette.Muted)
        }
        val doing = t.doing ?: t.text
        if (doing.isNotBlank()) {
            Text(doing, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        if (open) {
            PeekBox(peek?.takeIf { it.who == t.peekWho })
            TaskButtons(t, armed)
        }
    }
}

/** Running: busy or idle as a word with a dot. Anything else (stuck, paused) is a ⚠ chip with its word. */
@Composable
private fun TaskState(t: Task) {
    if (t.state.isEmpty() || t.state == "running") {
        val s = t.status ?: "running"
        StateWord(s, busy = s != "idle")
    } else {
        Chip(t.state, warn = true)
    }
}

/** Stop it (a second press, within 4 s). No "Tell it…": the user talks only to dibs (word 221). */
@Composable
private fun TaskButtons(t: Task, armed: Armed) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        val stopKey = "stop${t.id}"
        val busy = Dibs.busy("stop:${t.id}")
        ActButton(if (busy) "Stopping" else if (armed.key == stopKey) "Stop it?" else "Stop", "danger", busy = busy) {
            armed.press(stopKey) { Dibs.stop(t.id) }
        }
    }
}

/** A task's or session's last lines, as dibs read them. */
@Composable
private fun PeekBox(peek: Peek?) {
    Text(
        peek?.lines?.joinToString("\n")?.ifBlank { null } ?: if (peek == null) "Reading its last lines" else "Nothing to show yet.",
        Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 8.dp),
        style = AppType.mono,
        color = Palette.Muted,
    )
}

/** A live session: its name, repo and branch, what it holds, busy or idle; a tap shows its last lines. */
@Composable
private fun SessionRow(s: Session, peek: Peek?, modifier: Modifier) {
    val key = "session:${s.name}"
    val open = Dibs.open[key] == true
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { openPeek(key, s.name) }.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                val where = listOfNotNull(s.repo, s.branch?.takeIf { it != s.name }).joinToString(" · ")
                if (where.isNotEmpty()) Text(where, style = AppType.mono, color = Palette.Muted)
            }
            for (h in s.holds.take(2)) Chip(holdWords(h), accent = true)
            StateWord(s.status.ifEmpty { "live" }, busy = s.status == "busy")
        }
        if (open) PeekBox(peek?.takeIf { it.who == s.name })
    }
}

/** A ship under way: which repo, who runs it, for how long. */
@Composable
private fun ShipRow(s: Ship, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.lucide_arrow_down), null, Modifier.size(16.dp), tint = Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text("Shipping ${s.repo}", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            Text(listOfNotNull(s.who.ifBlank { null }, if (s.since > 0) age(now - s.since) else null).joinToString(" · "), style = AppType.small, color = Palette.Muted)
        }
    }
}
