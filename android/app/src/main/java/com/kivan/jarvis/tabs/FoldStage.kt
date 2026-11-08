package com.kivan.jarvis.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

    fun send(box: String) {
        val w = said(state.typed[box].orEmpty(), state.heard)
        if (w.isEmpty() && state.attached.isEmpty()) return
        buzz.sent()
        if (state.heard.isNotBlank()) deck.words(false)
        sentFrom = box
        deck.send(null, w)
        deck.type(box, "")
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
                        n >= 0 -> state.tiles.getOrNull(n)?.let { where = Where.Thread(it.id, false) } ?: return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            when (val w = where) {
                Where.Home -> FoldHome(state, onThreads = { where = Where.List }, onOpen = { where = Where.Thread(it, false) }, onSend = { send(WALL) })
                Where.List -> {
                    BackHandler { where = Where.Home }
                    ThreadList(state, onBack = { where = Where.Home }, onNew = { where = Where.New }, onOpen = { where = Where.Thread(it, true) })
                }
                Where.New -> {
                    BackHandler { where = Where.Home }
                    NewThread(state, onBack = { where = Where.Home }, onSend = { send(NEW) })
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
private fun FoldHome(state: DeckState, onThreads: () -> Unit, onOpen: (String) -> Unit, onSend: () -> Unit) {
    val deck = LocalDeck.current
    val lines = remember(state.digest, state.cards, state.tiles) { foldLines(state) }
    // Newest at the bottom, where he types: the list runs from the bottom up.
    val rows = remember(lines) { lines.asReversed() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.key }) { l ->
                    when (l) {
                        is FoldLine.Report -> ReportLine(l) { onOpen(l.thread) }
                        is FoldLine.Card -> CardBlock(l.card, onAnswer = { deck?.answer(l.card, it, "") }, onOpen = { l.card.tabs.firstOrNull()?.let { t -> onOpen(t.id) } })
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
        Dock(
            hint = "Talk to Jarvis",
            text = state.typed[WALL].orEmpty(),
            onText = { deck?.type(WALL, it) },
            onSend = onSend,
            heard = state.heard,
            onHeard = { deck?.edit() },
        )
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
            if (state.tiles.isEmpty()) item { Text("No threads yet.", Modifier.padding(Space.margin), look.faint, style = Words.small) }
            itemsIndexed(state.tiles, key = { _, t -> t.id }) { i, t ->
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
