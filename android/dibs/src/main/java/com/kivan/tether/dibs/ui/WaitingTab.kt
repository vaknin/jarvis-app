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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Decision
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Question
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.age
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import org.json.JSONObject

// The Waiting tab: every open question as a full card, then what dibs decided for you. A card
// answered here goes at once; the next view drops it for good (or shows it again if it didn't take).

@Composable
internal fun WaitingTab(view: DibsView) {
    val now by rememberNow()
    val armed = rememberArmed()
    val questions = view.questions.filter { "q${it.id}" !in Dibs.answered }
    val decided = view.decided.filter { it.ack !in Dibs.answered }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "_hero") { Hero("Waiting on you", "${questions.size}") }
        items(questions, key = { "q${it.id}" }) { q ->
            QuestionCard(q, now, Modifier.animateItem())
        }
        if (decided.isNotEmpty()) {
            item(key = "_decided") {
                Row(Modifier.fillMaxWidth().padding(top = Space.L - 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Decided for you", Modifier.weight(1f))
                    if (decided.size > 1) ActButton("Got it to all", "plain") { Dibs.ackAll(decided) }
                }
            }
            items(decided, key = { "d${it.id}" }) { d -> DecisionRow(d, now, armed, Modifier.animateItem()) }
        }
        if (questions.isEmpty() && decided.isEmpty()) item(key = "_empty") { Quiet("Nothing waiting.") }
    }
}

/** A question: who asks and how long ago, the question, why, Details behind a tap, its buttons and answer box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(q: Question, now: Long, modifier: Modifier) {
    val key = "q${q.id}"
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (q.kind == "phone") Icon(painterResource(R.drawable.lucide_smartphone), "Phone request", Modifier.size(14.dp), tint = Palette.Muted)
            if (q.kind == "laptop") Icon(painterResource(R.drawable.lucide_laptop), "Laptop request", Modifier.size(14.dp), tint = Palette.Muted)
            val who = if (q.kind == "update") "Update" else q.from
            Eyebrow(listOfNotNull(who.ifBlank { null }, q.repo, age(now - q.ts)).joinToString(" · "))
        }
        Text(q.title, style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
        if (q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        q.details?.let { Details(q.id, it) }
        if (q.actions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in q.actions) ActButton(a.label, a.style) { Dibs.answer(key, a.label, a.id) }
            }
        }
        q.reply?.let { reply ->
            AnswerField("q/${q.id}", "Answer…", Modifier.fillMaxWidth().padding(top = 2.dp)) { text ->
                Dibs.answer(key, text, reply, JSONObject().put("item", q.id.toString()).put("text", text))
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

/** Something dibs decided: Got it clears it; Undo asks dibs to undo it (a second press, within 4 s). */
@Composable
private fun DecisionRow(d: Decision, now: Long, armed: Armed, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(16.dp), tint = Palette.Success)
        Column(Modifier.weight(1f)) {
            TapFold(d.text, "dec:${d.id}", 2)
            val meta = listOfNotNull(d.why.ifBlank { null }, d.from.ifBlank { null }, age(now - d.ts)).joinToString(" · ")
            TapFold(meta, "dec:${d.id}", 1, style = AppType.small, color = Palette.Muted)
        }
        if (d.undo) {
            val k = "undo${d.id}"
            ActButton(if (armed.key == k) "Undo it?" else "Undo", if (armed.key == k) "" else "plain") {
                armed.press(k) { Dibs.answer(d.ack, "Undo", "undo", JSONObject().put("item", d.id)) }
            }
        }
        ActButton("Got it", "") { Dibs.answer(d.ack, "Got it", d.ack) }
    }
}
