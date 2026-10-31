package com.kivan.jarvis.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kivan.jarvis.R

/** Where each tile is, for the zoom: a tab grows out of its own tile and shrinks back into it. */
class WallMap {
    internal var origin = Offset.Zero
    internal var size = androidx.compose.ui.geometry.Size.Zero
    internal val slots = HashMap<String, Animatable<Rect, AnimationVector4D>>()

    fun rect(id: String): Rect? {
        val s = slots[id]?.value ?: return null
        return Rect(
            origin.x + s.left * size.width,
            origin.y + s.top * size.height,
            origin.x + s.right * size.width,
            origin.y + s.bottom * size.height,
        )
    }
}

private val Gap = 8.dp

private fun padFor(size: TileSize): Dp = when (size) {
    TileSize.Big -> 20.dp
    TileSize.Large -> 16.dp
    else -> 12.dp
}

private fun cornerFor(size: TileSize): Dp = when (size) {
    TileSize.Big -> 28.dp
    TileSize.Large -> 24.dp
    TileSize.Medium -> 20.dp
    TileSize.Small -> 16.dp
}

/**
 * The wall: only open tabs, filling the screen. Words typed here open a new tab, and the magnifier
 * finds closed ones. It's the only screen without a title, because it has nothing to explain.
 */
@Composable
fun Wall(
    state: DeckState,
    map: WallMap,
    active: Boolean,
    text: String,
    onText: (String) -> Unit,
    onStart: () -> Unit,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onClosedTabs: () -> Unit,
    onMute: (Boolean) -> Unit,
    undo: ClosedTab?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(look.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        AnimatedVisibility(!state.online) { OfflineBar() }
        if (state.tiles.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.margin, vertical = 24.dp), Alignment.BottomStart) {
                Text("Type to start your first tab", color = look.faint, style = Words.tile(TileSize.Large))
            }
        } else {
            Tiles(
                state.tiles, map, active, onOpen, onClose, onMove,
                Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
            )
        }
        AnimatedVisibility(undo != null, enter = fadeIn(tween(Curves.QUICK)), exit = fadeOut(tween(Curves.QUICK))) {
            UndoBar(undo, onUndo)
        }
        Dock(
            hint = "Start a new tab",
            text = text,
            onText = onText,
            onSend = onStart,
            muted = state.muted,
            onMute = onMute,
            leading = if (state.closed.isEmpty()) null else {
                { Round(R.drawable.lucide_search, "Search closed tabs", onClosedTabs, fill = look.surface, size = 48.dp) }
            },
        )
    }
}

@Composable
private fun UndoBar(undo: ClosedTab?, onUndo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 8.dp)
            .background(look.raised, RoundedCornerShape(16.dp))
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Closed ${undo?.name.orEmpty()}", Modifier.weight(1f), look.ink, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.height(Space.target).clickable(onClick = onUndo).padding(horizontal = 16.dp), Alignment.Center) {
            Text("Undo", color = look.ink, style = Words.label)
        }
    }
}

@Composable
private fun Tiles(
    tiles: List<TileView>,
    map: WallMap,
    active: Boolean,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val buzz = rememberBuzz()
    // The order on screen follows the server, except under a finger.
    val order = remember { mutableStateListOf<String>() }
    var lifted by remember { mutableStateOf<String?>(null) }
    if (lifted == null && order.toList() != tiles.map { it.id }) {
        order.clear()
        order.addAll(tiles.map { it.id })
    }
    val byId = remember(tiles) { tiles.associateBy { it.id } }

    BoxWithConstraints(
        modifier
            .testTag("wall")
            .onGloballyPositioned { c ->
                map.origin = c.positionInRoot()
                map.size = androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat())
            },
    ) {
        // The window's height, not this box's: the keyboard squeezes the wall, it doesn't reshuffle it.
        val w = constraints.maxWidth.toFloat()
        val h = maxOf(constraints.maxHeight, LocalWindowInfo.current.containerSize.height * 3 / 4).toFloat()
        val gap = with(density) { Gap.toPx() }
        val n = order.size
        val grid = remember(n, w, h) { Grid.of(n, w, h, gap) }
        val type = remember(grid) {
            grid.rects.firstOrNull()?.let {
                with(density) { Grid.size(it.width.toDp().value, it.height.toDp().value) }
            } ?: TileSize.Small
        }
        // One name size for the whole wall, set by the name that would otherwise split a word. A wall of
        // mixed sizes looks broken. Each tile still steps down alone if it can't fit its height.
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val base = Words.tile(type)
        val names = order.map { byId[it]?.name.orEmpty() }
        val biggest = remember(names, grid, base) {
            val pad = with(density) { padFor(type).toPx() * 2 }
            val floor = with(density) { 12.dp.toSp().value }
            var size = base.fontSize.value
            while (size > floor) {
                val fits = names.zip(grid.rects).all { (name, rect) ->
                    val room = Constraints(maxWidth = (rect.width - pad).toInt().coerceAtLeast(1))
                    !measurer.measure(name, base.copy(fontSize = size.sp), constraints = room).splitsWord(name)
                }
                if (fits) break
                size *= 0.92f
            }
            size.coerceAtLeast(floor).sp
        }
        val fractions = remember(grid) { grid.rects.map { Rect(it.left / w, it.top / h, it.right / w, it.bottom / h) } }
        val ids = order.toList()
        map.slots.keys.retainAll(ids.toSet())
        Layout(
            content = {
                for (i in ids.indices) {
                    val id = ids[i]
                    val tile = byId[id]
                    val to = fractions.getOrNull(i)
                    if (tile != null && to != null) {
                        key(id) {
                            TileSlot(
                                tile, to, type, biggest, active, map,
                                up = lifted == id,
                                onLift = { lifted = if (it) id else null },
                                onOver = { spot ->
                                    val into = fractions.indexOfFirst { it.contains(spot) }
                                    val from = order.indexOf(id)
                                    if (into >= 0 && into != from) {
                                        order.removeAt(from)
                                        order.add(into, id)
                                        buzz.tick()
                                    }
                                },
                                onDrop = { onMove(id, order.indexOf(id)) },
                                onOpen = { onOpen(id) },
                                onClose = { onClose(id) },
                                onStep = { by ->
                                    val at = order.indexOf(id) + by
                                    if (at in order.indices) onMove(id, at)
                                },
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { ms, c ->
            val fullW = c.maxWidth
            val fullH = c.maxHeight
            val placed = ms.map { m ->
                val id = m.layoutId as String
                val s = map.slots[id]?.value ?: Rect.Zero
                val width = (s.width * fullW).toInt().coerceAtLeast(1)
                val height = (s.height * fullH).toInt().coerceAtLeast(1)
                Triple(id, s, m.measure(Constraints.fixed(width, height)))
            }
            layout(fullW, fullH) {
                for ((id, s, p) in placed) {
                    p.place((s.left * fullW).toInt(), (s.top * fullH).toInt(), if (id == lifted) 1f else 0f)
                }
            }
        }
    }
}

/**
 * One tile in its place. It glides when the grid changes. Held down it lifts: a drag moves it, and
 * letting go without moving offers to close it.
 */
@Composable
private fun TileSlot(
    tile: TileView,
    to: Rect,
    type: TileSize,
    biggest: TextUnit,
    active: Boolean,
    map: WallMap,
    up: Boolean,
    onLift: (Boolean) -> Unit,
    onOver: (Offset) -> Unit,
    onDrop: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onStep: (Int) -> Unit,
) {
    val buzz = rememberBuzz()
    val slot = remember { map.slots.getOrPut(tile.id) { Animatable(to, Rect.VectorConverter) } }
    if (map.slots[tile.id] == null) map.slots[tile.id] = slot
    LaunchedEffect(to) {
        if (slot.targetValue != to) slot.animateTo(to, spring(Spring.DampingRatioNoBouncy, Curves.settle))
    }
    var menu by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val lift by animateFloatAsState(if (up) 1.04f else 1f, Curves.press)
    val slop = with(LocalDensity.current) { 10.dp.toPx() }
    Box(
        Modifier
            .layoutId(tile.id)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
                if (up) {
                    translationX = finger.x - slot.value.center.x * map.size.width
                    translationY = finger.y - slot.value.center.y * map.size.height
                }
            }
            .pointerInput(tile.id) {
                var travel = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        val s = slot.value
                        finger = Offset(s.center.x * map.size.width, s.center.y * map.size.height)
                        travel = 0f
                        onLift(true)
                        buzz.lift()
                    },
                    onDrag = { change, by ->
                        change.consume()
                        finger += by
                        travel += by.getDistance()
                        onOver(Offset(finger.x / map.size.width, finger.y / map.size.height))
                    },
                    onDragEnd = {
                        onLift(false)
                        if (travel > slop) onDrop() else menu = true
                    },
                    onDragCancel = { onLift(false) },
                )
            },
    ) {
        TileCell(tile, type, biggest, active, onOpen, onClose, onStep)
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

@Composable
private fun TileCell(
    tile: TileView,
    size: TileSize,
    biggest: TextUnit,
    active: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) 0.965f else 1f, Curves.press)
    val shape = RoundedCornerShape(cornerFor(size))
    // Amber arrives with one half-second flash and is then still. It never pulses.
    val flash = remember { Animatable(0f) }
    var wasNeeds by remember { mutableStateOf(tile.needs) }
    LaunchedEffect(tile.needs) {
        if (tile.needs && !wasNeeds) {
            flash.snapTo(0.55f)
            flash.animateTo(0f, tween(500))
        }
        wasNeeds = tile.needs
    }
    val fill = if (tile.needs) look.urgent else look.surface
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .clickable(press, ripple(color = if (tile.needs) look.onUrgent else look.ink), onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = describe(tile)
                onClick("Open") { onOpen(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Close tab") { onClose(); true },
                    CustomAccessibilityAction("Move earlier") { onMove(-1); true },
                    CustomAccessibilityAction("Move later") { onMove(1); true },
                )
            },
    ) {
        TileFace(tile, size, active, biggest = biggest)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
    }
}

fun describe(tile: TileView): String = buildList {
    add("Tab ${tile.n}, ${tile.name}")
    if (tile.needs) add("needs you")
    if (tile.unread) add("new to read")
    if (tile.working) add("working")
}.joinToString(", ")

/** A name plus one mark, and the moving line beside either. Number top left, name bottom left. */
@Composable
fun TileFace(tile: TileView, size: TileSize, active: Boolean, modifier: Modifier = Modifier, biggest: TextUnit = Words.tile(size).fontSize) {
    val pad = padFor(size)
    val ink = if (tile.needs) look.onUrgent else look.ink
    val number = if (tile.needs) look.onUrgent.copy(alpha = 0.72f) else look.faint
    val style = Words.tile(size)
    Box(modifier.fillMaxSize().padding(start = pad, end = pad, top = pad - 2.dp, bottom = pad - 2.dp)) {
        Text("${tile.n}", color = number, style = Words.number)
        Box(Modifier.align(Alignment.TopEnd)) {
            when {
                tile.needs -> Triangle(look.onUrgent, if (size >= TileSize.Medium) 15.dp else 18.dp)
                tile.unread -> Dot(size = 10.dp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Bottom),
        ) {
            BasicText(
                tile.name,
                style = style.copy(color = ink, lineHeight = 1.2.em),
                maxLines = if (size == TileSize.Small) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                autoSize = WholeWords(biggest),
            )
            if (tile.working) {
                val glide = LocalGlide.current
                val line = if (tile.needs) look.onUrgent else look.soft
                if (LocalStill.current) {
                    Text("Working", color = number, style = Words.small, maxLines = 1)
                } else {
                    Box(Modifier.fillMaxWidth().height(2.dp).workLine(line, glide))
                }
            } else {
                Box(Modifier.height(2.dp))
            }
        }
    }
}

private fun TextLayoutResult.splitsWord(text: String) = (0 until lineCount - 1).any { i ->
    val end = getLineEnd(i)
    end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
}

/**
 * A tile's name stays at its size unless that would split a word or spill out of the tile, as it would
 * with large text in a small tile. Then it steps down: a smaller name beats "Sourdo-ugh".
 */
private data class WholeWords(val biggest: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        // The floor is on the glass, not in sp: at double text size 10 sp is still a comfortable 20 dp.
        val floor = 12.dp.toSp().value
        var size = biggest.value
        while (size > floor) {
            val r = performLayout(constraints, text, size.sp)
            val spilled = r.hasVisualOverflow || (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1))
            if (!spilled && !r.splitsWord(text.text)) break
            size *= 0.9f
        }
        return size.coerceAtLeast(floor).sp
    }
}
