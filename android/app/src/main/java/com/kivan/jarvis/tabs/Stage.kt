package com.kivan.jarvis.tabs

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the stage starts: screenshots and tests open it on a given page. */
data class Opening(
    val tab: String? = null,
    val zoom: Float = 1f,
    val reply: String? = null,
    val closed: Boolean = false,
)

/** The tabs open when a tab was: frozen, so tiles don't reorder under his finger mid-swipe. */
private class Open(val order: List<String>, val first: Int)

/** The answer being replied to, and the tab it's in. */
private class Replying(val tab: String, val answer: Entry.Answer)

/**
 * The screens, one on top of the other. A tab is not a new page: it's its tile, grown. One number,
 * [zoom], says how far: 0 is the wall, 1 is the tab, and a tap, a pinch and the back gesture all drive
 * that same number, which is why this isn't a navigation library. Back and a pinch can stop halfway
 * and follow the finger, and a cancelled one returns to where it was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Stage(deck: Deck, opening: Opening = Opening()) {
    // Immediate, so what's typed is back in the field before the next key: the field reads from here.
    val state by deck.state.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    val typing = WindowInsets.isImeVisible
    val map = remember { WallMap() }
    val buzz = rememberBuzz()
    val still = LocalStill.current
    var open by remember {
        val i = state.tiles.indexOfFirst { it.id == opening.tab }
        mutableStateOf(if (opening.tab != null) Open(state.tiles.map { it.id }, i.coerceAtLeast(0)) else null)
    }
    val zoom = remember { Animatable(if (opening.tab != null) opening.zoom else 0f) }
    var closedTab by remember { mutableStateOf<String?>(null) }
    var reopening by remember { mutableStateOf<String?>(null) }
    var replying by remember {
        val answer = state.tabs[opening.tab]?.entries?.firstOrNull { it.key == opening.reply } as? Entry.Answer
        mutableStateOf(answer?.let { Replying(opening.tab.orEmpty(), it) })
    }
    var closedPage by rememberSaveable { mutableStateOf(opening.closed) }
    var undo by remember { mutableStateOf<ClosedTab?>(null) }

    fun go(to: Float, then: () -> Unit = {}) {
        scope.launch {
            if (still) zoom.snapTo(to) else zoom.animateTo(to, Curves.zoom)
            then()
        }
    }

    fun openTab(id: String) {
        val order = state.tiles.map { it.id }
        val at = order.indexOf(id)
        if (at < 0) return
        open = Open(order, at)
        go(1f)
    }

    fun toWall() = go(0f) { open = null }

    LaunchedEffect(state.opened) {
        state.opened?.let {
            openTab(it)
            deck.openedDone()
        }
    }
    // Reopened from its page, a tab stays on screen until the server has it and then is simply open.
    LaunchedEffect(reopening, state.tiles) {
        val id = reopening
        if (id != null && state.tiles.any { it.id == id }) {
            reopening = null
            closedTab = null
            closedPage = false
            openTab(id)
        }
    }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(6000)
            undo = null
        }
    }
    val wallWords = state.typed[WALL].orEmpty()
    val anyWork = state.tiles.any { it.working } || state.tabs.values.any { it.work != null }
    val anyLoading = open != null && state.tabs.values.any { it.loading }
    val glide = rememberGlide((anyWork || anyLoading) && state.online)

    val covered = replying != null || closedPage || closedTab != null

    CompositionLocalProvider(LocalGlide provides glide, LocalDeck provides deck) {
        Box(Modifier.fillMaxSize().background(look.canvas)) {
            Wall(
                state = state,
                map = map,
                active = open == null,
                text = wallWords,
                onText = { deck.type(WALL, it) },
                onStart = {
                    val w = wallWords.trim()
                    if (w.isNotEmpty()) {
                        buzz.sent()
                        deck.start(w)
                        deck.type(WALL, "")
                    }
                },
                onOpen = ::openTab,
                onClose = { id ->
                    undo = ClosedTab(id, state.tiles.firstOrNull { it.id == id }?.name.orEmpty())
                    deck.close(id)
                },
                onMove = deck::move,
                onClosedTabs = { closedPage = true },
                onMute = deck::mute,
                undo = undo,
                onUndo = {
                    undo?.let { deck.reopen(it.id) }
                    undo = null
                },
                modifier = Modifier
                    .graphicsLayer {
                        val p = zoom.value.coerceIn(0f, 1f)
                        alpha = 1f - (p / 0.7f).coerceAtMost(1f)
                    }
                    .drawWithContent { if (zoom.value < 1f) drawContent() }
                    .hiddenWhen(open != null || covered),
            )
            open?.let { o ->
                key(o) {
                    val pager = rememberPagerState(o.first) { o.order.size }
                    TabLayer(
                        state, o, pager, map, zoom,
                        deck = deck,
                        modifier = Modifier.hiddenWhen(covered),
                        onPinched = { wall ->
                            if (wall) {
                                buzz.step()
                                toWall()
                            } else {
                                go(1f)
                            }
                        },
                        onWall = ::toWall,
                        onReply = { id, answer -> replying = Replying(id, answer) },
                        onClose = { id ->
                            undo = ClosedTab(id, state.tabs[id]?.tile?.name.orEmpty())
                            deck.close(id)
                            toWall()
                        },
                    )
                }
            }
            if (closedTab != null) {
                val id = closedTab!!
                state.tabs[id]?.let { tab ->
                    TabPage(
                        tab, "", null, state.muted, state.online, true,
                        onWall = { closedTab = null },
                        onType = {}, onSend = {}, onReply = {}, onClose = {},
                        onReopen = {
                            deck.reopen(id)
                            reopening = id
                        },
                        onMute = deck::mute,
                    )
                }
            }
            AnimatedVisibility(
                closedPage && closedTab == null,
                enter = fadeIn(tween(Curves.QUICK)) + slideInVertically(tween(Curves.PAGE, easing = Curves.out)) { it / 12 },
                exit = fadeOut(tween(Curves.QUICK)) + slideOutVertically(tween(Curves.QUICK)) { it / 12 },
            ) {
                ClosedPage(
                    state.closed,
                    state.muted,
                    onOpen = {
                        closedTab = it.id
                        deck.show(listOf(it.id))
                    },
                    onBack = { closedPage = false },
                    onMute = deck::mute,
                )
            }
            AnimatedVisibility(
                replying != null,
                enter = fadeIn(tween(Curves.QUICK)) + slideInVertically(tween(Curves.PAGE, easing = Curves.out)) { it / 8 },
                exit = fadeOut(tween(Curves.QUICK)) + slideOutVertically(tween(Curves.QUICK)) { it / 8 },
            ) {
                val reply = replying ?: return@AnimatedVisibility
                val id = reply.tab
                val answer = reply.answer
                val draft = state.drafts[id]?.takeIf { it.to == answer.offset } ?: ReplyDraft(answer.offset ?: 0)
                ReplyPage(
                    name = state.tabs[id]?.tile?.name.orEmpty(),
                    answer = answer,
                    draft = draft,
                    muted = state.muted,
                    onDraft = { deck.draft(id, it) },
                    onSend = { _, quotes, end ->
                        deck.say(id, end, quotes)
                        deck.draft(id, null)
                        replying = null
                    },
                    onBack = { replying = null },
                    onMute = deck::mute,
                )
            }
            if (replying != null) PredictiveBackHandler(!typing) { flow ->
                flow.collect {}
                replying = null
            }
            if (closedPage || closedTab != null) PredictiveBackHandler(!typing) { flow ->
                flow.collect {}
                if (closedTab != null) closedTab = null else closedPage = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabLayer(
    state: DeckState,
    open: Open,
    pager: PagerState,
    map: WallMap,
    zoom: Animatable<Float, *>,
    deck: Deck,
    onPinched: (Boolean) -> Unit,
    onWall: () -> Unit,
    onReply: (String, Entry.Answer) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val corner = with(density) { 28.dp.toPx() }
    val page = pager.settledPage
    val rim = look.line
    fun zoomTile(full: Size) = open.order.getOrNull(pager.currentPage)?.let(map::rect)
        ?: Rect(full.width * 0.1f, full.height * 0.3f, full.width * 0.9f, full.height * 0.7f)

    // The wire follows the tab on screen and its neighbours, so a swipe finds them ready.
    LaunchedEffect(page, open) {
        val near = (page - 1..page + 1).mapNotNull { open.order.getOrNull(it) }
        deck.show(near)
    }
    val settled = open.order.getOrNull(page)
    val unread = settled?.let { state.tabs[it]?.tile?.unread } == true
    LaunchedEffect(settled, unread) { if (settled != null) deck.seen(settled) }

    // With the keyboard up, back is the keyboard's, even when it was up before this page came.
    PredictiveBackHandler(!WindowInsets.isImeVisible) { flow ->
        try {
            flow.collect { e -> zoom.snapTo(1f - e.progress * 0.45f) }
            onWall()
        } catch (e: CancellationException) {
            scope.launch { zoom.animateTo(1f, Curves.zoom) }
            throw e
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var from = 0f
                    var pinching = false
                    var last = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val span = event.calculateCentroidSize(useCurrent = true)
                            if (from == 0f) from = span
                            if (!pinching && from - span > 24.dp.toPx()) pinching = true
                            if (pinching) {
                                last = 1f - ((1f - span / from) / 0.55f).coerceIn(0f, 1f)
                                scope.launch { zoom.snapTo(last) }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinching) onPinched(last < 0.82f)
                }
            }
            .graphicsLayer {
                val full = size
                val p = zoom.value.coerceIn(0f, 1f)
                val tile = zoomTile(full)
                val w = tile.width + (full.width - tile.width) * p
                val h = tile.height + (full.height - tile.height) * p
                val s = maxOf(w / full.width, h / full.height)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = s
                scaleY = s
                translationX = tile.left * (1f - p) + (w - full.width * s) / 2
                translationY = tile.top * (1f - p) + (h - full.height * s) / 2
                clip = p < 1f
                shape = ZoomShape(w / s, h / s, corner * cornerLeft(p) / s)
                alpha = (p / 0.14f).coerceAtMost(1f)
            }
            .drawWithContent {
                drawContent()
                // A hairline rim while it grows, so the tab reads as the tile and not as a cut.
                val p = zoom.value.coerceIn(0f, 1f)
                if (p > 0f && p < 1f) {
                    val tile = zoomTile(size)
                    val w = tile.width + (size.width - tile.width) * p
                    val h = tile.height + (size.height - tile.height) * p
                    val s = maxOf(w / size.width, h / size.height)
                    drawRoundRect(
                        rim.copy(alpha = 1f - p),
                        Offset((size.width - w / s) / 2, (size.height - h / s) / 2),
                        Size(w / s, h / s),
                        CornerRadius(corner * cornerLeft(p) / s),
                        Stroke(density.run { 2.dp.toPx() } / s),
                    )
                }
            },
    ) {
        HorizontalPager(
            pager,
            beyondViewportPageCount = 1,
            key = { open.order[it] },
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            val tabId = open.order[i]
            val tab = state.tabs[tabId] ?: state.tiles.firstOrNull { it.id == tabId }?.let { TabView(it, loading = true) }
            if (tab != null) {
                TabPage(
                    tab = tab,
                    typed = state.typed[tabId].orEmpty(),
                    draft = state.drafts[tabId],
                    muted = state.muted,
                    online = state.online,
                    active = i == pager.currentPage,
                    onWall = onWall,
                    onType = { deck.type(tabId, it) },
                    onSend = {
                        deck.say(tabId, it)
                        deck.type(tabId, "")
                    },
                    onReply = { onReply(tabId, it) },
                    onClose = { onClose(tabId) },
                    onReopen = { deck.reopen(tabId) },
                    onMute = deck::mute,
                )
            }
        }
    }
}

/** A page under another page isn't there for a screen reader. */
private fun Modifier.hiddenWhen(hidden: Boolean) = if (hidden) clearAndSetSemantics {} else this

/** The corners stay a tile's for most of the way and square off only near the end. */
private fun cornerLeft(p: Float) = 1f - p * p * p

/** A centred window of the full-size tab, in the tab's own units, with rounded corners. */
private class ZoomShape(private val w: Float, private val h: Float, private val r: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        return Outline.Rounded(RoundRect(Rect(left, top, left + w, top + h), CornerRadius(r)))
    }

    override fun equals(other: Any?) = other is ZoomShape && other.w == w && other.h == h && other.r == r
    override fun hashCode() = 31 * (31 * w.hashCode() + h.hashCode()) + r.hashCode()
}
