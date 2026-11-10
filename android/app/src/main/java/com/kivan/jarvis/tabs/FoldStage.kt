package com.kivan.jarvis.tabs

import android.content.ClipData
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.TextFieldValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.jarvis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the fold's stage is: the main chat, the thread list, a new thread, or thread [id]. */
private sealed interface Where {
    data object Home : Where
    data object List : Where
    data object New : Where
    data class Thread(val id: String, val fromList: Boolean) : Where
}

/** The box of a new thread in [DeckState.typed], beside the main chat's [WALL]. */
private const val NEW = "+new"

/**
 * The library fold's stage (mockups fold-home-tabs A, fold-home-look A3, fold-progress-bars A): the main chat
 * alone, its top line "N working"; Threads (alt+l) lists them; + (alt+n, from anywhere) opens a new thread,
 * its first line names it; alt+1..9 opens thread N. No tab row anywhere.
 */
@Composable
fun FoldStage(deck: Deck, tab: String? = null) {
    val state by deck.state.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    var where by remember { mutableStateOf<Where>(if (tab != null) Where.Thread(tab, false) else Where.Home) }
    // The tab the server opens for words: from a new thread it opens, from the main chat it stays there.
    // Any other (a notification's tap) opens its thread.
    var sentFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val buzz = rememberBuzz()

    LaunchedEffect(state.opened, state.tiles) {
        val id = state.opened ?: return@LaunchedEffect
        when {
            id == WALL || id.startsWith(ASK) -> where = Where.Home
            sentFrom == WALL -> sentFrom = null
            state.tiles.none { it.id == id } -> return@LaunchedEffect
            else -> {
                sentFrom = null
                where = Where.Thread(id, false)
            }
        }
        deck.openedDone()
    }
    LaunchedEffect(where) { if (where !is Where.Thread) deck.here(null) }

    // A new thread's box opens one; the main chat sends through its own reply box, as a thread does.
    fun sendNew() {
        val w = said(state.typed[NEW].orEmpty(), state.heard)
        if (w.isEmpty() && state.attached.isEmpty()) return
        buzz.sent()
        if (state.heard.isNotBlank()) deck.words(false)
        sentFrom = NEW
        deck.send(null, w, emptyList())
        deck.type(NEW, "")
    }

    val anyWork = state.tiles.any { it.working } || state.tabs.values.any { it.work != null || it.loading }
    val glide = rememberGlide(anyWork && state.online)
    CompositionLocalProvider(LocalGlide provides glide, LocalDeck provides deck, LocalTalk provides Talk(state.dictating, state.call, state.attached)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(look.canvas)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || !e.isAltPressed) return@onPreviewKeyEvent false
                    val n = NUMBERS.indexOf(e.key)
                    when {
                        e.key == Key.L -> where = if (where == Where.List) Where.Home else Where.List
                        e.key == Key.N -> where = Where.New
                        // The wall's move key: the subject he's on, from his newest message with one.
                        e.key == Key.S && where == Where.Home -> mainChat(state.tiles)?.let { deck.subject(it, null) } ?: return@onPreviewKeyEvent false
                        n >= 0 -> foldThreads(state.tiles).getOrNull(n)?.let { where = Where.Thread(it.id, false) } ?: return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            when (val w = where) {
                Where.Home -> FoldHome(state, onThreads = { where = Where.List }, onOpen = { where = Where.Thread(it, false) })
                Where.List -> {
                    BackHandler { where = Where.Home }
                    ThreadList(state, onBack = { where = Where.Home }, onNew = { where = Where.New }, onOpen = { where = Where.Thread(it, true) })
                }
                Where.New -> {
                    BackHandler { where = Where.Home }
                    NewThread(state, onBack = { where = Where.Home }, onSend = { sendNew() })
                }
                is Where.Thread -> {
                    val back = { where = if (w.fromList) Where.List else Where.Home }
                    BackHandler(onBack = back)
                    FoldThread(state, deck, w.id, back)
                }
            }
        }
    }
}

private val NUMBERS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** The main chat: its lines, the jump chip for cards scrolled away, and the box. */
@Composable
private fun FoldHome(state: DeckState, onThreads: () -> Unit, onOpen: (String) -> Unit) {
    val deck = LocalDeck.current
    val main = mainChat(state.tiles)
    LaunchedEffect(main) { if (main != null) deck?.show(listOf(main)) }
    val lines = remember(state.digest, state.cards, state.tiles, state.tabs) { foldLines(state) }
    val moving = state.moving?.takeIf { it.tab == main }
    val lit = remember(lines, moving) { lit(lines, moving) }
    if (moving != null) BackHandler { deck?.keepSubject() }
    // Newest at the bottom, where he types: the list runs from the bottom up.
    val rows = remember(lines) { lines.asReversed() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The thread's own reply box, on the main tab: a swipe opens a reply in it, Hold and a long press on Send hold,
    // an empty Send lets go of what he held, and the marks wait above it. With no main chat yet nothing goes: his
    // words wait in the box, never in a new thread.
    val focus = remember { FocusRequester() }
    val box = rememberReplyBox(
        typed = state.typed[WALL].orEmpty(),
        heard = state.heard,
        marks = main?.let { state.marks[it] }.orEmpty(),
        waiting = main?.let { state.tabs[it]?.waiting } == true,
        onMarks = { m -> main?.let { deck?.marks(it, m) } },
        onSend = { words, quotes ->
            if (main != null) {
                if (state.heard.isNotBlank()) deck?.words(false)
                deck?.send(main, words, quotes)
                deck?.marks(main, emptyList())
                deck?.type(WALL, "")
            }
        },
        onHoldWords = { words, quotes ->
            if (main != null) {
                if (state.heard.isNotBlank()) deck?.words(false)
                deck?.hold(main, words, quotes)
                deck?.type(WALL, "")
            }
        },
        onRelease = { main?.let { deck?.release(it) } },
        onTaken = { deck?.words(false) },
        focus = focus,
        // Newest at the bottom: his message, sent or held, comes into view as in a thread.
        onSent = { scope.launch { list.animateScrollToItem(0) } },
    )
    val reply = box.reply
    val quote = { m: QuoteMark -> if (main != null) box.open(m) }
    // Quote part: the message he's picking words from, selectable in place; back, a quote or a send ends it.
    var parting by remember { mutableStateOf<String?>(null) }
    if (parting != null) BackHandler { parting = null }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val hidden by remember(rows) {
        derivedStateOf {
            val seen = list.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            rows.withIndex().filter { (_, l) -> l is FoldLine.Card && l.key !in seen }.map { it.index }
        }
    }
    Page {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Space.margin, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Jarvis", Modifier.semantics { heading() }, look.ink, style = Words.title.copy(fontSize = 20.sp, lineHeight = 26.sp))
            Spacer(Modifier.weight(1f))
            workingLine(state)?.let { Text(it, Modifier.padding(end = 10.dp), look.faint, style = Words.small) }
            Pill("Threads", onThreads)
        }
        if (!state.online) OfflineBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text("Nothing here yet. Say what you need; each subject gets its own thread.", Modifier.padding(Space.margin), look.faint, style = Words.small)
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("fold-home"),
                state = list,
                reverseLayout = true,
                // With the jump chip up, the newest line sits above it (chip 34 dp + its 10 dp margin + 8), never under it.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (hidden.isNotEmpty()) 52.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.key }) { l ->
                    when (l) {
                        is FoldLine.Report -> ReportLine(l) { onOpen(l.thread) }
                        is FoldLine.Card -> CardBlock(l.card, onAnswer = { deck?.answer(l.card, it, "") }, onOpen = { l.card.tabs.firstOrNull()?.let { t -> onOpen(t.id) } })
                        is FoldLine.Said -> SaidLine(
                            l,
                            l.key in lit,
                            parting = parting == l.key,
                            onQuote = if (main != null && l.offset != null) ({ part, words ->
                                parting = null
                                quote(QuoteMark(l.offset, part, words))
                            }) else null,
                            onPart = { parting = l.key },
                            onPartDone = { parting = null },
                            onCopy = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Jarvis", saidText(l)))) }
                                copied = true
                            },
                            onMove = if (main != null && l.offset != null && moving == null) ({ deck?.subject(main, l.offset) }) else null,
                        )
                    }
                }
            }
            chipWords(hidden.size)?.let { words ->
                JumpChip(words, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp)) {
                    hidden.minOrNull()?.let { scope.launch { list.animateScrollToItem(it) } }
                }
            }
        }
        state.notice?.let { NoticeBar(it.text) { deck?.noticed() } }
        if (copied) NoticeBar("Copied") { copied = false }
        moving?.let { MoveBar(it, onMove = { deck?.moveSubject() }, onKeep = { deck?.keepSubject() }) }
        Dock(
            hint = if (reply != null) "Reply to this" else "Talk to Jarvis",
            text = box.writing ?: state.typed[WALL].orEmpty(),
            onText = { if (reply != null) box.write(it) else deck?.type(WALL, it) },
            onSend = {
                parting = null
                if (main != null) box.send()
            },
            onHold = { if (main != null) box.holdAll() },
            focus = focus,
            heard = if (reply != null) "" else state.heard,
            onHeard = { deck?.edit() },
            ready = box.ready,
            above = { box.Above() },
        )
    }
}

/** A main chat message's words as he'd quote or copy them: his as typed, Jarvis's as read, without the marks. */
private fun saidText(l: FoldLine.Said): String = when (val e = l.entry) {
    // His quote-replies keep their words in each quote (the message's own text is often empty): each reply, then the rest.
    is Entry.Yours -> (e.quotes.map { it.text } + e.text).filter { it.isNotEmpty() }.joinToString("\n")
    is Entry.Answer -> Prose.parse(e.text).joinToString("\n\n") { it.quoted }
    else -> ""
}

/**
 * A message of the main chat's talk: his words on a panel to the right, Jarvis's open on the page. A swipe to the
 * right quotes the part under the finger in a reply ([onQuote], with that part's place): each of Jarvis's blocks (a
 * paragraph, a bullet) as a thread's are, each of his lines as he typed it. A long-press on the whole message opens
 * the small menu: Quote part, Copy, Open thread. Quote
 * part ([parting]) makes it selectable in place, and Quote this quotes only the words he picked. Open thread moves
 * its subject into a thread ([onMove]; none while one is under way). Lit (the server named it as moving), it takes
 * the wall's look: a sky-blue edge and a raised fill.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SaidLine(
    l: FoldLine.Said,
    lit: Boolean,
    parting: Boolean,
    onQuote: ((Int, String) -> Unit)?,
    onPart: () -> Unit,
    onPartDone: () -> Unit,
    onCopy: () -> Unit,
    onMove: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val edge = look.stepRun
    val mine = l.entry is Entry.Yours
    val text = when (val e = l.entry) {
        is Entry.Yours -> e.text
        is Entry.Answer -> e.text
        else -> ""
    }
    val press = if (parting) Modifier else Modifier.combinedClickable(
        onLongClickLabel = "Quote, copy or open a thread",
        onLongClick = { menu = true },
        onClick = {},
    )
    val shape = RoundedCornerShape(if (mine) 18.dp else 10.dp)
    val panel = lit || mine || parting
    val fill = when {
        lit -> look.raised
        mine || parting -> look.surface
        else -> Color.Transparent
    }
    val swipes = onQuote != null && !parting
    val bubble = @Composable {
        Box(
            Modifier
                .clip(shape)
                .then(press)
                .background(fill, shape)
                .then(if (lit) Modifier.drawBehind { drawRect(edge, Offset.Zero, size.copy(width = 3.dp.toPx())) } else Modifier)
                .padding(start = if (panel) 16.dp else 4.dp, end = if (panel) 14.dp else 4.dp, top = if (panel) 10.dp else 2.dp, bottom = if (panel) 10.dp else 2.dp)
                .semantics { if (lit) contentDescription = "Moves with the subject" },
        ) {
            when {
                parting && onQuote != null -> QuotePart(remember(l) { saidText(l) }, { onQuote(0, it) }, onPartDone)
                // His lines as typed, never parsed: each its own Text, stacked as one Text would draw them.
                // Each quote of a quote-reply first, as a thread draws it (the bar isn't a swipe target), then his words to it;
                // his lines are numbered in one run, as Copy and Quote part read them.
                mine -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val quotes = (l.entry as Entry.Yours).quotes
                    var first = 0
                    for (q in quotes) {
                        QuoteBar(q)
                        if (q.text.isNotEmpty()) {
                            HisLines(q.text, first, swipes, onQuote)
                            first += q.text.split('\n').size
                        }
                    }
                    if (quotes.isEmpty() || text.isNotEmpty()) HisLines(text, first, swipes, onQuote)
                }
                swipes -> ProseView(remember(text) { Prose.parse(text) }) { i, b, m ->
                    SwipeReply({ onQuote?.invoke(i, b.quoted) }, m) { BlockView(b) }
                }
                else -> ProseView(remember(text) { Prose.parse(text) })
            }
        }
    }
    Box(Modifier.fillMaxWidth().testTag(if (lit) "lit" else "said"), if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(start = if (mine) 36.dp else 0.dp)) {
            bubble()
            DropdownMenu(menu, { menu = false }, containerColor = look.raised) {
                if (onQuote != null) MenuItem("Quote part") {
                    menu = false
                    onPart()
                }
                MenuItem("Copy") {
                    menu = false
                    onCopy()
                }
                if (onMove != null) MenuItem("Open thread") {
                    menu = false
                    onMove()
                }
            }
        }
    }
}

/** His lines as typed, never parsed: each its own Text, stacked as one Text would draw them, each swipeable on its own. */
@Composable
private fun HisLines(text: String, first: Int, swipes: Boolean, onQuote: ((Int, String) -> Unit)?) {
    Column {
        remember(text) { text.split('\n') }.forEachIndexed { i, line ->
            val drawn = @Composable { Text(line, color = look.ink, style = Words.yours) }
            if (swipes && line.isNotBlank()) SwipeReply({ onQuote?.invoke(first + i, line) }) { drawn() } else drawn()
        }
    }
}

@Composable
private fun MenuItem(words: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(words, color = look.ink, style = Words.label) }, onClick = onClick)
}

/**
 * Quote part: the message as words he can select in place (it reads, it doesn't type). With words picked, Quote this
 * quotes just those; until then a line says what to do, and the x puts the message back.
 */
@Composable
private fun QuotePart(words: String, onQuote: (String) -> Unit, onDone: () -> Unit) {
    var value by remember(words) { mutableStateOf(TextFieldValue(words)) }
    val picked = value.selection.let { if (it.collapsed) "" else words.substring(it.min, it.max).trim() }
    Column {
        BasicTextField(
            value,
            { value = it.copy(text = words) },
            Modifier.testTag("quote-part"),
            readOnly = true,
            textStyle = Words.yours.copy(color = look.ink),
            cursorBrush = SolidColor(Color.Transparent),
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picked.isEmpty()) {
                Text("Select the words to quote", Modifier.weight(1f), look.faint, style = Words.small)
            } else {
                Box(Modifier.weight(1f)) { Pill("Quote this") { onQuote(picked) } }
            }
            Round(R.drawable.lucide_x, "Stop quoting part", onDone, size = 38.dp)
        }
    }
}

/**
 * The move key's question, as the wall's bar asks it: how many messages on which subject, Keep or Move. Asking the
 * server and moving, it says so and waits; nothing lights up until the server has named them.
 */
@Composable
private fun MoveBar(m: Moving, onMove: () -> Unit, onKeep: () -> Unit) {
    val edge = look.stepRun
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(look.surface)
            .drawBehind { drawRect(edge, Offset.Zero, size.copy(width = 3.dp.toPx())) }
            .padding(start = 15.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("move-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val words = when {
            m.sent -> "Moving them into a new thread…"
            m.lit -> moveAsk(m)
            else -> "Finding the subject…"
        }
        Text(words, Modifier.weight(1f), if (m.lit) look.ink else look.soft, style = Words.small.copy(fontWeight = if (m.lit) FontWeight.W600 else FontWeight.Normal))
        if (!m.sent) Pill("Keep", onKeep)
        if (m.lit) {
            Box(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(edge).clickable(onClickLabel = "Move them into a new thread", onClick = onMove)
                    .heightIn(min = 36.dp).padding(horizontal = 14.dp).testTag("move"),
                Alignment.Center,
            ) { Text("Move", color = look.canvas, style = Words.small.copy(fontWeight = FontWeight.W600)) }
        }
    }
}

/** A report: one calm grey line. Unread, a small dot before it until he's seen the thread; it never reminds. */
@Composable
private fun ReportLine(l: FoldLine.Report, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Open ${l.name}", onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 4.dp, vertical = 6.dp).testTag("report"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp), Alignment.CenterStart) {
            if (l.unread) Box(Modifier.size(6.dp).background(look.ink, CircleShape).semantics { contentDescription = "Unread" })
            else Text(if (l.done) "✓" else "·", color = look.faint, style = Words.small)
        }
        Text(l.name, color = look.soft, style = Words.small.copy(fontWeight = FontWeight.W500), maxLines = 1)
        Text(
            l.line,
            Modifier.weight(1f).padding(start = 8.dp),
            look.faint,
            style = Words.small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(clock(l.at), Modifier.padding(start = 8.dp), look.faint, style = Words.number)
    }
}

/** A card waiting on him: an orange edge, its question and its answers as buttons, Jarvis's pick outlined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardBlock(c: CardView, onAnswer: (Int) -> Unit, onOpen: () -> Unit) {
    val need = look.stepNeed
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(look.surface)
            .drawBehind { drawRect(need, Offset.Zero, size.copy(width = 3.dp.toPx())) }
            .padding(start = 15.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .testTag("card"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name.uppercase(), color = need, style = Words.small.copy(fontWeight = FontWeight.W600, letterSpacing = 0.4.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("needs you", Modifier.padding(start = 6.dp), look.faint, style = Words.small, maxLines = 1)
            }
            if (c.at > 0) Text(clock(c.at), color = look.faint, style = Words.number)
        }
        Text(if (c.many) "${c.items.size} questions: ${c.text}" else c.text, Modifier.padding(top = 4.dp), look.ink, style = Words.yours)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!c.many) c.options.forEachIndexed { i, o -> if (o != ELSE) Answer(o, i == c.pick) { onAnswer(i) } }
            Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen).heightIn(min = 36.dp).padding(horizontal = 12.dp), Alignment.Center) {
                Text("Open", color = look.soft, style = Words.small)
            }
        }
    }
}

@Composable
private fun Answer(words: String, pick: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(look.raised)
            .then(if (pick) Modifier.border(1.5.dp, look.ink, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClickLabel = if (pick) "Answer $words, Jarvis's pick" else "Answer $words", onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp),
        Alignment.Center,
    ) { Text(words, color = look.ink, style = Words.small.copy(fontWeight = FontWeight.W500)) }
}

/** The messenger's jump button: counts only cards still waiting for his answer, a tap goes up to the first. */
@Composable
private fun JumpChip(words: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(look.raised)
            .clickable(onClickLabel = "Jump to it", onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp)
            .testTag("jump"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(look.stepNeed, CircleShape))
        Text("↑ $words", Modifier.padding(start = 7.dp), look.ink, style = Words.small)
    }
}

/** The threads, one row each: its number (alt+N), name, step blocks and words. + opens a new one. */
@Composable
private fun ThreadList(state: DeckState, onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit) {
    Page {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Round(R.drawable.lucide_arrow_left, "Back to the main chat", onBack)
            Text("Threads", Modifier.weight(1f).semantics { heading() }, look.ink, style = Words.title.copy(fontSize = 20.sp, lineHeight = 26.sp))
            Round(R.drawable.lucide_pencil, "New thread", onNew)
        }
        if (!state.online) OfflineBar()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("threads"), contentPadding = PaddingValues(bottom = 16.dp)) {
            val threads = foldThreads(state.tiles)
            if (threads.isEmpty()) item { Text("No threads yet.", Modifier.padding(Space.margin), look.faint, style = Words.small) }
            itemsIndexed(threads, key = { _, t -> t.id }) { i, t ->
                val s = threadSteps(t, state.tabs[t.id]?.issues.orEmpty())
                Column(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${t.name}") { onOpen(t.id) }
                        .padding(horizontal = Space.margin, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(22.dp), if (s.needs) look.stepNeed else look.faint, style = Words.number)
                        Text(t.name, Modifier.weight(1f), look.ink, style = Words.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.unread) Box(Modifier.size(6.dp).background(look.ink, CircleShape).semantics { contentDescription = "Unread" })
                    }
                    if (s.steps.isNotEmpty() || s.words.isNotEmpty()) {
                        Row(Modifier.padding(start = 22.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StepBar(s.steps)
                            if (s.words.isNotEmpty()) {
                                Text(
                                    s.words,
                                    Modifier.padding(start = if (s.steps.isEmpty()) 0.dp else 10.dp),
                                    if (s.needs) look.stepNeed else look.faint,
                                    style = Words.small,
                                )
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = Space.margin).height(1.dp).background(look.line.copy(alpha = 0.5f)))
            }
        }
    }
}

/**
 * Iris's step blocks, protan-safe: grey done, sky blue running, orange needs him, a hollow outline still to
 * come. Running and needing stand taller than the rest, so the shape says it too, and words always sit beside.
 */
@Composable
fun StepBar(steps: List<StepState>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val words = steps.groupingBy { it }.eachCount().entries.joinToString(", ") { (s, n) -> "$n ${s.name.lowercase()}" }
    Row(modifier.semantics { contentDescription = "Steps: $words" }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEach { s ->
            val shape = RoundedCornerShape(2.dp)
            val block = Modifier.width(18.dp)
            when (s) {
                StepState.Done -> Box(block.height(4.dp).background(look.stepDone, shape))
                StepState.Running -> Box(block.height(6.dp).background(look.stepRun, shape))
                StepState.Needs -> Box(block.height(6.dp).background(look.stepNeed, shape))
                StepState.Todo -> Box(block.height(4.dp).border(1.dp, look.line, shape))
            }
        }
    }
}

/** A new thread (alt+n): an empty page whose first line names it; it reports back to the main chat. */
@Composable
private fun NewThread(state: DeckState, onBack: () -> Unit, onSend: () -> Unit) {
    val deck = LocalDeck.current
    Page {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Round(R.drawable.lucide_arrow_left, "Back to the main chat", onBack)
            Text("New thread", Modifier.weight(1f).semantics { heading() }, look.ink, style = Words.title)
        }
        Box(Modifier.weight(1f))
        Dock(
            hint = "What is it about? Your first line names it",
            text = state.typed[NEW].orEmpty(),
            onText = { deck?.type(NEW, it) },
            onSend = onSend,
            heard = state.heard,
            onHeard = { deck?.edit() },
        )
    }
}

/** A thread: today's tab page under the fold's top (back, its name; its board, step blocks and where it is). */
@Composable
private fun FoldThread(state: DeckState, deck: Deck, id: String, onBack: () -> Unit) {
    val tab = state.tabs[id] ?: state.tiles.firstOrNull { it.id == id }?.let { TabView(it, loading = true) }
    val unread = tab?.tile?.unread == true
    LaunchedEffect(id) {
        deck.show(listOf(id))
        deck.here(id)
    }
    LaunchedEffect(id, unread) { deck.seen(id) }
    if (tab == null) {
        Page { ThreadTop("", emptyList(), "", "", onBack) }
        return
    }
    TabPage(
        tab = tab,
        typed = state.typed[id].orEmpty(),
        online = state.online,
        active = true,
        onWall = onBack,
        onType = { deck.type(id, it) },
        onSend = { words, quotes ->
            if (state.heard.isNotBlank()) deck.words(false)
            deck.send(id, words, quotes)
            deck.marks(id, emptyList())
            deck.type(id, "")
        },
        marks = state.marks[id].orEmpty(),
        onMarks = { deck.marks(id, it) },
        onTaken = { deck.words(false) },
        onClose = { deck.close(id); onBack() },
        onReopen = { deck.reopen(id) },
        heard = state.heard,
        onHeard = { deck.edit() },
        onStop = { deck.stop(id) },
        onRelease = { deck.release(id) },
        onHoldWords = { words, quotes ->
            if (state.heard.isNotBlank()) deck.words(false)
            deck.hold(id, words, quotes)
            deck.type(id, "")
        },
        onMode = { m, e -> deck.mode(id, m, e) },
        notice = state.notice?.takeIf { it.id == id }?.text,
        now = threadNow(tab.tile, tab.issues),
        top = {
            ThreadTop(tab.tile.name, threadSteps(tab.tile, tab.issues).steps, threadBoard(tab.issues), threadPhase(tab.tile, tab.issues), onBack, tab.tile.needs)
        },
    )
}

@Composable
private fun ThreadTop(name: String, steps: List<StepState>, board: String, phase: String, onBack: () -> Unit, needs: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 6.dp).testTag("thread-top")) {
        Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Round(R.drawable.lucide_arrow_left, "Back", onBack)
            Text(name, Modifier.weight(1f).semantics { heading() }, look.ink, style = Words.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (board.isNotEmpty() || steps.isNotEmpty() || phase.isNotEmpty()) {
            Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (board.isNotEmpty()) Text(board.uppercase(), Modifier.padding(end = 10.dp), look.faint, style = Words.small.copy(fontSize = 12.sp, letterSpacing = 0.7.sp))
                StepBar(steps)
                if (phase.isNotEmpty()) Text(phase, Modifier.padding(start = if (steps.isEmpty()) 0.dp else 10.dp), if (needs) look.stepNeed else look.soft, style = Words.small)
            }
        }
    }
}

@Composable
private fun Pill(words: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(look.raised).clickable(onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 14.dp),
        Alignment.Center,
    ) { Text(words, color = look.ink, style = Words.small.copy(fontWeight = FontWeight.W500)) }
}

@Composable
private fun Spacer(modifier: Modifier) = Box(modifier)

/** "01:10", his clock. */
private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
