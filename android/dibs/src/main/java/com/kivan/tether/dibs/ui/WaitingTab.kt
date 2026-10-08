package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Action
import com.kivan.tether.dibs.Decision
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.Question
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.age
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import org.json.JSONObject

// The Waiting tab: only what needs the user, every open question as a full card (what dibs
// decided for them is in Recap: the user, 2026-10-06, word 127). A card answered here goes at
// once; the next view drops it for good (or shows it again if it didn't take).

@Composable
internal fun WaitingTab(view: DibsView) {
    val now by rememberNow()
    val questions = view.questions.filter { "q${it.id}" !in Dibs.answered }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "_hero") { Hero("Waiting on you", "${questions.size}") }
        items(questions, key = { "q${it.id}" }) { q ->
            QuestionCard(q, now, Modifier.animateItem(), task = q.task?.takeIf { view.task(it) != null })
        }
        if (questions.isEmpty()) item(key = "_empty") { Quiet("Nothing waiting on you.") }
    }
}

/**
 * A question: who asks and how long ago, the question, why, Details behind a tap, its buttons and
 * answer box; "Open task" when one of the user's tasks asks it ([task]). The task's page shows the
 * same card, so answering in either place closes it in both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(q: Question, now: Long, modifier: Modifier, task: Long? = null) {
    // A root step opens its own page to approve; setting up the key is the phone's own button.
    if (q.kind == "root" && q.root != null) return RootCard(q, q.root, now, modifier)
    if (q.kind == "rootkey") return RootKeyCard(q, now, modifier)
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A tap on the card's words opens the task it is about (not the buttons or the answer box).
        Column(
            (if (task != null) Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = "Open task") { Dibs.open(Page.Task(task)) } else Modifier),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (q.kind == "phone") Icon(painterResource(R.drawable.lucide_smartphone), "Phone request", Modifier.size(14.dp), tint = Palette.Muted)
            if (q.kind == "laptop") Icon(painterResource(R.drawable.lucide_laptop), "Laptop request", Modifier.size(14.dp), tint = Palette.Muted)
            val who = if (q.kind == "update") "Update" else q.from
            Eyebrow(listOfNotNull(who.ifBlank { null }, q.repo, age(now - q.ts)).joinToString(" · "))
        }
        Text(q.title, style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
        if (q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        q.details?.let { Details(q.id, it) }
        QuestionControls(q.id, "q/${q.id}", q.actions, q.reply, q.hint, Modifier.padding(top = 4.dp))
        if (task != null) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.open(Page.Task(task)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("Open task", style = AppType.label, color = Palette.Accent)
                Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = Palette.Accent)
            }
        }
    }
}

/**
 * A question's buttons and its box for words, the same in Waiting and the chat ([field] keeps
 * each place's draft). Sent alone, the words are the answer (on a question that runs something,
 * dibs reads them and settles it); typed before a tap, they go with it as a comment (the user's
 * rule: a yes/no question also takes a free comment). Either place answers the one question, so
 * both close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionControls(
    id: Long,
    field: String,
    actions: List<Action>,
    reply: String?,
    hint: String?,
    modifier: Modifier = Modifier,
    background: Color = Palette.SurfaceLow,
) {
    val key = "q$id"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (actions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in actions) {
                    ActButton(a.label, a.style) {
                        // A close (x…) takes no words: it's "no answer".
                        val comment = Dibs.fields[field]?.trim().orEmpty().takeIf { reply != null && !a.id.startsWith("x") && it.isNotEmpty() }
                        Dibs.answer(key, a.label, a.id, comment?.let { JSONObject().put("comment", it) })
                        if (comment != null) Dibs.fields.remove(field)
                    }
                }
            }
        }
        reply?.let { r ->
            // Words alone on a question whose buttons run something (approve/deny) go to dibs and the
            // question stays open: its buttons stay, with a note that dibs has the words.
            val approval = actions.any { it.id.startsWith("a") }
            val sent = "sent:$id"
            if (approval && Dibs.open[sent] == true && !Dibs.busy("say:$id")) Text("Sent to dibs. It acts on your words, or asks.", style = AppType.small, color = Palette.Muted)
            AnswerField(field, hint ?: "Answer", Modifier.fillMaxWidth(), background = background) { text ->
                val value = JSONObject().put("item", id.toString()).put("text", text)
                if (approval) {
                    Dibs.open[sent] = true
                    Dibs.tap("say:$id", "Sending your words", "your words", r, value)
                } else {
                    Dibs.answer(key, text, r, value)
                }
            }
        }
    }
}

/** "Details" folds the question's long text; open, it can be selected and copied. */
@Composable
private fun Details(id: Long, details: String) {
    val k = "details:$id"
    val open = Dibs.open[k] == true
    Row(
        Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.toggle(k) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Details", style = AppType.label, color = Palette.Muted)
        Icon(
            painterResource(if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
            null,
            Modifier.size(16.dp),
            tint = Palette.Muted,
        )
    }
    if (open) {
        SelectionContainer {
            Text(
                details,
                Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small).padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
            )
        }
    }
}

/** Something decided for the user, in Recap: Undo asks dibs to undo it (a second press, within 4 s). Nothing to clear. */
@Composable
internal fun DecisionRow(d: Decision, now: Long, armed: Armed, modifier: Modifier) {
    // At a glance: which project, what was decided and why, in full (the user, 2026-10-06, word 155). The agent's own
    // words fold behind a tap; until dibs has rewritten them, they are what shows, marked as theirs.
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.lucide_check), null, Modifier.padding(top = 2.dp).size(16.dp), tint = Palette.Success)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                if (d.project.isNotBlank()) {
                    Text(d.project, Modifier.weight(1f, fill = false), style = AppType.label, color = Palette.Accent)
                }
                Text(
                    listOfNotNull(if (d.plain) null else "in the agent's words", age(now - d.ts)).joinToString(" · "),
                    style = AppType.small,
                    color = Palette.Muted,
                )
            }
            Text(d.text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            if (d.why.isNotBlank()) Text("Why: ${d.why}", style = AppType.small, color = Palette.Muted)
            // Not rewritten yet, the lines above are the agent's: the fold only when it holds more (its details).
            if (d.raw.isNotBlank() && (d.plain || d.raw.length > d.text.length + d.why.length + 4)) RawWords(d)
        }
        if (d.undo && Dibs.answered[d.ack] == "Undo") {
            Text("Undo asked", style = AppType.small, color = Palette.Muted)
        } else if (d.undo) {
            val k = "undo${d.id}"
            ActButton(if (armed.key == k) "Undo it?" else "Undo", if (armed.key == k) "" else "plain") {
                armed.press(k) { Dibs.answer(d.ack, "Undo", "undo", JSONObject().put("item", d.id)) }
            }
        }
    }
}

/** The agent's whole text behind "Agent's words", signed with its session's name; selectable. */
@Composable
private fun RawWords(d: Decision) {
    val k = "dec:${d.id}"
    val open = Dibs.open[k] == true
    Row(
        Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.toggle(k) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Agent's words", style = AppType.small, color = Palette.Muted)
        Icon(painterResource(if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down), null, Modifier.size(14.dp), tint = Palette.Muted)
    }
    if (open) {
        SelectionContainer {
            Text(
                if (d.from.isBlank()) d.raw else "${d.raw}\n\n— ${d.from}",
                Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small).padding(10.dp),
                style = AppType.small,
                color = Palette.Text,
            )
        }
    }
}
