package com.kivan.jarvis.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.jarvis.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** His notes per card, typed on the list or in the dialog: the same words in both, until an answer takes them. */
typealias Notes = SnapshotStateMap<String, String>

@Composable
fun rememberNotes(): Notes = remember { mutableStateMapOf() }

/**
 * The first screen: what waits on him, one tap each, and the digest beside it. The wall is the grid
 * top left. A tap on a button answers with the note under it; a tap on the card opens the dialog.
 */
@Composable
fun QueuePage(
    state: DeckState,
    digest: Boolean,
    onDigest: (Boolean) -> Unit,
    onWall: () -> Unit,
    onOpen: (String) -> Unit,
    text: String,
    onText: (String) -> Unit,
    onStart: () -> Unit,
    onHeard: () -> Unit,
    onAuto: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    asking: String? = null,
    onAsking: (String?) -> Unit = {},
) {
    val deck = LocalDeck.current
    val notes = rememberNotes()
    val card = state.cards.firstOrNull { it.key == asking }
    val answer = { c: CardView, pick: Int? ->
        deck?.answer(c, pick, notes[c.key].orEmpty())
        notes.remove(c.key)
        onAsking(null)
    }
    Box(modifier.fillMaxSize()) {
        Page {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Round(R.drawable.lucide_layout_grid, "Your tabs", onWall)
                Text(
                    if (digest) "Digest" else "Needs you",
                    Modifier.padding(start = 4.dp).semantics { heading() },
                    look.ink,
                    style = Words.title.copy(fontSize = 20.sp, lineHeight = 26.sp),
                )
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Needs you ${state.cards.size}", !digest) { onDigest(false) }
                Chip("Digest ${state.digest.size}", digest) { onDigest(true) }
            }
            if (!state.online) OfflineBar()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (digest) {
                    DigestList(state.digest, state.tiles, onOpen)
                } else {
                    Needs(state, notes, onAsk = { onAsking(it.key) }, onAnswer = answer, onOpen = onOpen)
                }
            }
            state.notice?.let { NoticeBar(it.text) { deck?.noticed() } }
            Dock(
                hint = "Message Jarvis",
                text = text,
                onText = onText,
                onSend = onStart,
                heard = state.heard,
                onHeard = onHeard,
                onAuto = onAuto,
            )
        }
        if (card != null) {
            AskSheet(card, notes[card.key].orEmpty(), { notes[card.key] = it }, { answer(card, it) }, { onAsking(null) }, onOpen)
        }
    }
}

@Composable
private fun Chip(words: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) look.raised else look.surface)
            .then(if (on) Modifier.border(1.5.dp, look.ink, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp),
        Alignment.Center,
    ) {
        Text(words, color = if (on) look.ink else look.soft, style = Words.small.copy(fontWeight = if (on) FontWeight.W600 else FontWeight.Normal))
    }
}

@Composable
private fun Needs(state: DeckState, notes: Notes, onAsk: (CardView) -> Unit, onAnswer: (CardView, Int?) -> Unit, onOpen: (String) -> Unit) {
    val taken = remember(state.digest) { state.digest.filter { it.what == "default" }.take(3) }
    if (state.cards.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.margin), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing needs you.", Modifier.padding(top = 56.dp), look.soft, style = Words.label, textAlign = TextAlign.Center)
            val n = state.digest.size
            Text(
                "Your tabs are working. $n ${if (n == 1) "thing" else "things"} in the digest.",
                Modifier.padding(top = 4.dp),
                look.faint,
                style = Words.small,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("needs"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.cards, key = { it.key }) { c ->
            AskCard(c, notes[c.key].orEmpty(), { notes[c.key] = it }, { onAsk(c) }, { onAnswer(c, it) })
        }
        if (taken.isNotEmpty()) {
            item(key = "taken") { Section("Taken by Jarvis") }
            items(taken, key = { "taken/${it.id}/${it.at}" }) { d -> Taken(d) { onOpen(d.id) } }
        }
    }
}

@Composable
private fun Section(words: String) {
    Text(words.uppercase(), Modifier.padding(start = 2.dp, top = 6.dp), look.faint, style = Words.small.copy(fontSize = 12.sp, letterSpacing = 0.7.sp))
}

/** "01:10", his clock. */
private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/** When the card goes on without him, in a line. */
private fun dueLine(c: CardView): String {
    val pick = c.options.getOrNull(c.pick)
    return when {
        c.level == Level.Urgent -> "Waits for you. The tab keeps working meanwhile."
        c.many && c.due != null -> "Jarvis takes his picks at ${clock(c.due)} if you don't answer."
        c.many -> "Waits for you. The tab keeps working meanwhile."
        c.due != null && pick != null -> "Jarvis takes \"$pick\" at ${clock(c.due)} if you don't answer."
        else -> "Waits for you. The tab keeps working meanwhile."
    }
}

/**
 * A card: the tabs it stands in, the question, when Jarvis takes its pick, a note line and the
 * answers, the recommended one bright. Urgent is the amber border and word; low is faint.
 */
@Composable
fun AskCard(c: CardView, note: String, onNote: (String) -> Unit, onOpen: () -> Unit, onAnswer: (Int?) -> Unit, modifier: Modifier = Modifier) {
    if (c.many) return ManyCard(c, modifier)
    val low = c.level == Level.Low
    val shape = RoundedCornerShape(16.dp)
    val line = look.line
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                when (c.level) {
                    Level.Urgent -> Modifier.background(look.surface).border(2.dp, look.urgent, shape)
                    Level.Low -> Modifier.drawBehind {
                        drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                    }
                    Level.Default -> Modifier.background(look.surface)
                },
            )
            .clickable(onClickLabel = "Open the question", onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("card"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, Modifier.weight(1f), look.soft, style = Words.small.copy(fontWeight = FontWeight.W600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                c.level == Level.Urgent -> Text("Urgent", color = look.urgentText, style = Words.small.copy(fontWeight = FontWeight.W700))
                low -> Text("Low", color = look.faint, style = Words.small)
                c.tabs.size > 1 -> Text("${c.tabs.size} tabs", color = look.faint, style = Words.small)
            }
        }
        Text(
            c.text,
            color = if (low) look.soft else look.ink,
            style = if (low) Words.label else Words.label.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.W600),
        )
        Text(dueLine(c), color = look.faint, style = Words.small)
        val focus = remember { FocusRequester() }
        NoteBox(note, onNote, "Add a note", onSend = { onAnswer(null) }.takeIf { note.isNotBlank() }, focus = focus)
        Answers(c, note.isNotBlank(), elseWrites(c, note, focus, onAnswer), big = false)
    }
}

/** t67: Something else with nothing written opens the note to write it, instead of sending his words lost. */
private fun elseWrites(c: CardView, note: String, focus: FocusRequester, onAnswer: (Int?) -> Unit): (Int?) -> Unit = { i ->
    if (i != null && c.options.getOrNull(i) == ELSE && note.isBlank()) runCatching { focus.requestFocus() } else onAnswer(i)
}

/** The frame of a card, by its level: amber border for urgent, dashed for low. */
@Composable
private fun Modifier.cardFrame(level: Level): Modifier {
    val shape = RoundedCornerShape(16.dp)
    val line = look.line
    return fillMaxWidth().clip(shape).then(
        when (level) {
            Level.Urgent -> Modifier.background(look.surface).border(2.dp, look.urgent, shape)
            Level.Low -> Modifier.drawBehind {
                drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            Level.Default -> Modifier.background(look.surface)
        },
    )
}

/** What he's tapped on a card of several questions so far: a pick each or none, and his words for Something else. */
private class Chosen(n: Int) {
    val picks: SnapshotStateList<Int?> = mutableStateListOf<Int?>().apply { repeat(n) { add(null) } }
    val words: SnapshotStateList<String> = mutableStateListOf<String>().apply { repeat(n) { add("") } }
}

/**
 * A card of several questions, an autopilot's intake (t116): all of them on one card, each a short
 * section with its options. Jarvis's pick is dashed and marked, never chosen until he taps it.
 * One Send sends only what he tapped; the server keeps the rest open.
 */
@Composable
private fun ManyCard(c: CardView, modifier: Modifier = Modifier) {
    val chosen = remember(c.key) { Chosen(c.items.size) }
    Column(
        modifier.cardFrame(c.level).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp).testTag("card"),
    ) {
        ManyHead(c, chosen, look.soft)
        Text(dueLine(c), Modifier.padding(top = 4.dp), look.faint, style = Words.small)
        ManyBody(c, chosen)
    }
}

/** The tab, how far he is (a dot per question, "2 of 5"), and urgent if it is. */
@Composable
private fun ManyHead(c: CardView, chosen: Chosen, name: Color, onName: (() -> Unit)? = null) {
    val done = c.items.indices.map { c.answered(it, chosen.picks, chosen.words) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            c.name,
            Modifier.weight(1f).then(if (onName != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Open the tab", onClick = onName) else Modifier),
            name,
            style = Words.small.copy(fontWeight = FontWeight.W600),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (c.level == Level.Urgent) Text("Urgent", color = look.urgentText, style = Words.small.copy(fontWeight = FontWeight.W700))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            done.forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(if (it) look.ink else look.line)) }
        }
        Text("${done.count { it }} of ${done.size}", color = look.faint, style = Words.small)
    }
}

/** The questions and the one Send. [onSent] after it went. */
@Composable
private fun ManyBody(c: CardView, chosen: Chosen, onSent: () -> Unit = {}) {
    val deck = LocalDeck.current
    val buzz = rememberBuzz()
    c.items.forEachIndexed { i, q ->
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 1.dp).background(look.line))
        Asked(
            i, q, chosen.picks[i], chosen.words[i], c.answered(i, chosen.picks, chosen.words),
            onPick = { p -> chosen.picks[i] = if (chosen.picks[i] == p) null else p },
            onWords = { chosen.words[i] = it },
        )
    }
    val done = c.items.indices.count { c.answered(it, chosen.picks, chosen.words) }
    val ready = done > 0
    Box(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (ready) look.ink else look.raised)
            .clickable(enabled = ready, onClickLabel = "Send your answers") {
                val (picks, text) = c.reply(chosen.picks, chosen.words) ?: return@clickable
                buzz.sent()
                deck?.answerEach(c, picks, text)
                onSent()
            }
            .testTag("send-all"),
        Alignment.Center,
    ) {
        Text(
            when (done) {
                0 -> "Tap an answer to send"
                c.items.size -> "Send all ${c.items.size}"
                else -> "Send $done · the rest stay open"
            },
            color = if (ready) look.canvas else look.faint,
            style = Words.label.copy(fontWeight = FontWeight.W600),
        )
    }
}

/** One question of several: its number, words and why, its options, and a box under Something else. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Asked(i: Int, q: Question, pick: Int?, words: String, answered: Boolean, onPick: (Int) -> Unit, onWords: (String) -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${i + 1}", Modifier.width(14.dp), look.faint, style = Words.label.copy(fontWeight = FontWeight.W500))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(q.text, color = look.ink, style = Words.label.copy(fontWeight = FontWeight.W600))
                if (q.why.isNotBlank()) Text(q.why, color = look.faint, style = Words.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (answered) Text("✓", color = look.soft, style = Words.label)
        }
        FlowRow(Modifier.padding(start = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.options.forEachIndexed { j, o -> Chip(if (o == ELSE) "Something else" else o, rec = j == q.pick, on = pick == j) { onPick(j) } }
        }
        if (pick != null && q.options.getOrNull(pick) == ELSE) {
            val focus = remember { FocusRequester() }
            Box(Modifier.padding(start = 22.dp)) { NoteBox(words, onWords, "Write your answer", onSend = null, focus = focus) }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

/** An option of one of several questions: filled once he taps it; Jarvis's pick dashed, with his name on it. */
@Composable
private fun Chip(words: String, rec: Boolean, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val soft = look.soft
    Row(
        Modifier
            .clip(shape)
            .then(
                when {
                    on -> Modifier.background(look.ink)
                    rec -> Modifier.drawBehind {
                        drawRoundRect(soft, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
                    }
                    else -> Modifier.border(1.5.dp, look.line, shape)
                },
            )
            .clickable(onClickLabel = if (on) "Take back $words" else words, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (on) look.canvas else look.ink
        Text(words, color = ink, style = Words.label.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.W700 else FontWeight.W500))
        if (rec) Text("Jarvis", color = ink.copy(alpha = 0.6f), style = Words.small.copy(fontSize = 11.sp, lineHeight = 14.sp))
    }
}

/** The answers: Jarvis's pick bright and first, the rest plain. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answers(c: CardView, noted: Boolean, onAnswer: (Int?) -> Unit, big: Boolean) {
    val order = remember(c.options, c.pick) { c.options.indices.sortedBy { if (it == c.pick) -1 else it } }
    val two = big && c.options.size == 2
    @Composable
    fun answer(i: Int, m: Modifier) {
        val rec = i == c.pick
        val small = when {
            noted -> "with your note"
            rec -> "recommended"
            else -> null
        }
        Box(
            m
                .clip(RoundedCornerShape(12.dp))
                .background(if (rec) look.ink else if (big) look.canvas else look.raised)
                .then(if (!rec && big) Modifier.border(1.5.dp, look.line, RoundedCornerShape(12.dp)) else Modifier)
                .clickable(onClickLabel = c.options[i]) { onAnswer(i) }
                .heightIn(min = if (big) 52.dp else 40.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            if (two) Alignment.Center else Alignment.CenterStart,
        ) {
            val ink = if (rec) look.canvas else look.ink
            if (two) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.options[i], color = ink, style = Words.label.copy(fontSize = 16.sp, fontWeight = if (rec) FontWeight.W700 else FontWeight.W500))
                    if (small != null) Text(small, color = ink.copy(alpha = 0.75f), style = Words.small.copy(fontSize = 11.sp, lineHeight = 14.sp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.options[i], color = ink, style = Words.label.copy(fontSize = 14.sp, fontWeight = if (rec) FontWeight.W700 else FontWeight.W500))
                    if (small != null) Text(small, color = ink.copy(alpha = 0.75f), style = Words.small.copy(fontSize = 11.sp, lineHeight = 14.sp))
                }
            }
        }
    }
    when {
        two -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { order.forEach { answer(it, Modifier.weight(1f)) } }
        big -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { order.forEach { answer(it, Modifier.fillMaxWidth()) } }
        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { order.forEach { answer(it, Modifier) } }
    }
}

/** A line for his own words, sent with whichever answer he presses; [onSend] sends them alone. */
@Composable
private fun NoteBox(note: String, onNote: (String) -> Unit, hint: String, onSend: (() -> Unit)?, big: Boolean = false, focus: FocusRequester? = null) {
    val ink = look.ink
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (big) 14.dp else 12.dp))
            .background(if (big) look.canvas else look.canvas.copy(alpha = 0.5f))
            .border(if (big) 1.5.dp else 1.dp, if (note.isEmpty()) look.line else look.soft, RoundedCornerShape(if (big) 14.dp else 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = note,
            onValueChange = onNote,
            modifier = Modifier.weight(1f).heightIn(min = if (big) 46.dp else 40.dp).then(if (focus != null) Modifier.focusRequester(focus) else Modifier).semantics { contentDescription = hint }.testTag("note"),
            textStyle = Words.small.copy(fontSize = 14.sp, lineHeight = 19.sp, color = ink),
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 4,
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), Alignment.CenterStart) {
                    if (note.isEmpty()) Text(hint, color = look.faint, style = Words.small.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (onSend != null) Round(R.drawable.lucide_send_horizontal, "Send only the note", onSend, size = 36.dp)
    }
}

/** A pick Jarvis took on his own, faint: a tap opens its tab, where he can say otherwise. */
@Composable
private fun Taken(d: DigestView, onOpen: () -> Unit) {
    val line = look.line
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Change it in its tab", onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row {
            Text(d.name, Modifier.weight(1f), look.soft, style = Words.small.copy(fontWeight = FontWeight.W600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("change", color = look.faint, style = Words.small)
        }
        Text(d.line, color = look.soft, style = Words.small.copy(fontSize = 14.sp, lineHeight = 19.sp))
    }
}

/**
 * The dialog: a sheet from the bottom over a dim page, with the why, the note box and the answers,
 * Jarvis's pick bright. The note goes with whichever answer he presses, or alone.
 */
@Composable
fun AskSheet(c: CardView, note: String, onNote: (String) -> Unit, onAnswer: (Int?) -> Unit, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onDismiss)
    val chosen = remember(c.key) { Chosen(c.items.size) }
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(MutableInteractionSource(), null, onClickLabel = "Close", onClick = onDismiss),
        Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .reading()
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(look.raised)
                .clickable(MutableInteractionSource(), null) {}
                .overKeyboard()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 20.dp)
                .testTag("ask"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (c.many) {
                ManyHead(c, chosen, look.faint) { c.tabs.firstOrNull()?.let { onDismiss(); onOpen(it.id) } }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { ManyBody(c, chosen, onSent = onDismiss) }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.name,
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Open the tab") { c.tabs.firstOrNull()?.let { onDismiss(); onOpen(it.id) } },
                    look.faint,
                    style = Words.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    c.level == Level.Urgent -> Text(" · Urgent", color = look.urgentText, style = Words.small.copy(fontWeight = FontWeight.W600))
                    c.due != null && c.options.getOrNull(c.pick) != null -> Text(" · takes ${c.options[c.pick]} at ${clock(c.due)}", color = look.faint, style = Words.small, maxLines = 1)
                }
            }
            Text(c.text, color = look.ink, style = Words.title.copy(fontSize = 19.sp, lineHeight = 25.sp))
            if (c.why.isNotBlank() && note.isEmpty()) Text(c.why, color = look.soft, style = Words.small.copy(fontSize = 14.sp, lineHeight = 20.sp))
            NoteBox(
                note, onNote,
                if (c.options.isEmpty()) "Your answer" else "Add a note (optional), sent with your answer",
                onSend = { onAnswer(null) }.takeIf { note.isNotBlank() },
                big = true,
                focus = focus,
            )
            if (c.options.isNotEmpty()) Answers(c, note.isNotBlank(), elseWrites(c, note, focus, onAnswer), big = true)
            Text(
                if (note.isNotBlank() && c.options.isNotEmpty()) "Your note goes with whichever you press." else "The tab keeps working meanwhile.",
                color = look.faint,
                style = Words.small.copy(fontSize = 12.sp),
            )
        }
    }
}

/** The digest: what went on without him, by kind. Nothing here pings him. */
@Composable
private fun DigestList(lines: List<DigestView>, tiles: List<TileView>, onOpen: (String) -> Unit) {
    if (lines.isEmpty()) {
        Text("Nothing yet.", Modifier.fillMaxWidth().padding(top = 56.dp), look.faint, style = Words.label, textAlign = TextAlign.Center)
        return
    }
    val groups = remember(lines) { KINDS.mapNotNull { (what, title) -> lines.filter { kindOf(it.what) == what }.takeIf { it.isNotEmpty() }?.let { title to it } } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("digest"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        for ((title, rows) in groups) {
            item(key = "s/$title") { Section(title) }
            items(rows, key = { "${it.what}/${it.id}/${it.at}/${it.offset}" }) { d ->
                val open = tiles.any { it.id == d.id }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (open) Modifier.clickable(onClickLabel = "Open ${d.name}") { onOpen(d.id) } else Modifier)
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(d.name.ifBlank { "Jarvis" }, Modifier.widthIn(min = 84.dp, max = 110.dp), look.faint, style = Words.small.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.line, Modifier.weight(1f), look.soft, style = Words.small.copy(fontSize = 14.sp, lineHeight = 19.sp))
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).heightIn(min = 1.dp).background(look.surface))
            }
        }
    }
}

private val KINDS = listOf(
    "done" to "Finished",
    "autopilot" to "Autopilot",
    "default" to "Taken by Jarvis",
    "assumed" to "Assumed",
    "report" to "Bug reports",
    "notify" to "Sent to your phone",
    "other" to "Other",
)

private fun kindOf(what: String) = if (KINDS.any { it.first == what }) what else if (what.startsWith("autopilot")) "autopilot" else "other"

/** The server's no, in its words, where he pressed: a tap puts it away. */
@Composable
fun NoticeBar(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(8000)
        onDone()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(look.raised)
            .clickable(onClickLabel = "Dismiss", onClick = onDone)
            .heightIn(min = Space.target)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), look.ink, style = Words.small)
    }
}
