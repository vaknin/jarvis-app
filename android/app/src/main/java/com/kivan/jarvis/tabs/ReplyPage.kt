package com.kivan.jarvis.tabs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** The hint under a paragraph, and what the box says inside. */
private const val WRITE_HERE = "Answer this paragraph"
private const val TYPE_HERE = "Type your answer to this"

/**
 * The reply box, the forum's successor. Jarvis's answer opens as a page and he writes under the
 * paragraphs he has something to say about, in any order. Skipped ones aren't sent; each answered one
 * goes as a quote with his words under it. Selecting words quotes only those. The draft stays until
 * it's sent, whatever else happens.
 */
@Composable
fun ReplyPage(
    name: String,
    answer: Entry.Answer,
    draft: ReplyDraft,
    muted: Boolean,
    onDraft: (ReplyDraft) -> Unit,
    onSend: (ReplyDraft, List<Quoted>, String) -> Unit,
    onBack: () -> Unit,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(answer.text) { Prose.parse(answer.text) }
    var writing by remember { mutableStateOf<Int?>(null) }
    val buzz = rememberBuzz()
    val list = rememberLazyListState()
    val write = { i: Int ->
        if (draft.notes[i] == null) onDraft(draft.copy(notes = draft.notes + (i to Note("", ""))))
        writing = i
    }
    val quoteFor = { i: Int, n: Note -> n.quote.ifEmpty { blocks.getOrNull(i)?.plain.orEmpty() } }
    val send = {
        val quotes = draft.notes.toSortedMap().filterValues { it.text.isNotBlank() }.map { (i, n) ->
            Quoted(answer.offset ?: 0, quoteFor(i, n), n.text.trim())
        }
        buzz.sent()
        onSend(draft, quotes, draft.end.trim())
    }
    Page(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Round(R.drawable.lucide_arrow_left, "Back, the draft is kept", onBack)
            Text("Reply", Modifier.padding(start = 4.dp, end = 10.dp), look.ink, style = Words.title)
            Text(name, Modifier.weight(1f), look.faint, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("reply"),
                state = list,
                contentPadding = PaddingValues(start = Space.margin, end = Space.margin, top = 8.dp, bottom = 24.dp),
            ) {
                itemsIndexed(blocks, contentType = { _, b -> b::class }) { i, block ->
                    val note = draft.notes[i]
                    Paragraph(
                        block,
                        note,
                        focused = writing == i,
                        onWrite = { write(i) },
                        onNote = { onDraft(draft.copy(notes = draft.notes + (i to it))) },
                        onDone = {
                            if (writing == i) writing = null
                            if (draft.notes[i]?.text.isNullOrBlank()) onDraft(draft.copy(notes = draft.notes - i))
                        },
                        onQuote = { words ->
                            onDraft(draft.copy(notes = draft.notes + (i to (draft.notes[i] ?: Note("", "")).copy(quote = words))))
                            writing = i
                        },
                    )
                }
                item(key = "end") {
                    NoteField(
                        draft.end,
                        "Anything else",
                        { onDraft(draft.copy(end = it)) },
                        Modifier.padding(top = 20.dp),
                    )
                }
            }
            // The page runs out under the bar instead of being cut by it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(look.canvas.copy(alpha = 0f), look.canvas))),
            )
        }
        Row(
            Modifier.overKeyboard().padding(start = Space.margin, end = 10.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (draft.count == 0) "Tap a paragraph to answer it" else "Skipped ones aren't sent",
                Modifier.weight(1f),
                look.faint,
                style = Words.small,
                maxLines = 2,
            )
            SendPill(draft.count, send)
            MuteButton(muted, onMute)
        }
    }
}

/** A button says what it does: how many answers go. */
@Composable
private fun SendPill(count: Int, onClick: () -> Unit) {
    val on = count > 0
    Box(
        Modifier
            .height(Space.target)
            .clip(RoundedCornerShape(24.dp))
            .background(if (on) look.ink else look.surface)
            .clickable(enabled = on, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 22.dp),
        Alignment.Center,
    ) {
        Text(
            when (count) {
                0 -> "Send"
                1 -> "Send 1 answer"
                else -> "Send $count answers"
            },
            color = if (on) look.canvas else look.faint,
            style = Words.label,
        )
    }
}

@Composable
private fun Paragraph(
    block: Block,
    note: Note?,
    focused: Boolean,
    onWrite: () -> Unit,
    onNote: (Note) -> Unit,
    onDone: () -> Unit,
    onQuote: (String) -> Unit,
) {
    // Tapping the paragraph itself or the gap under it writes there, so the target is the whole block
    // and the page reads like a page, with no marks down its side. A long press still selects words.
    val tap = remember { MutableInteractionSource() }
    val pressed by tap.collectIsPressedAsState()
    val wash by animateFloatAsState(if (pressed) 1f else 0f, tween(Curves.QUICK), label = "press")
    val washed = look.surface
    Column(
        Modifier
            .animateContentSize(tween(Curves.PAGE, easing = Curves.out))
            .drawBehind {
                if (wash > 0f) {
                    val out = 8.dp.toPx()
                    drawRoundRect(
                        washed.copy(alpha = wash),
                        Offset(-out, -out / 2),
                        Size(size.width + out * 2, size.height - Space.para.toPx() + out),
                        CornerRadius(12.dp.toPx()),
                    )
                }
            }
            .then(if (note == null) Modifier.clickable(tap, null, onClickLabel = WRITE_HERE, onClick = onWrite) else Modifier),
    ) {
        // Words he picked light up where they stand; only what can't be lit there is repeated under it.
        val lit = note != null && block !is Block.Code && block.shown.contains(note.quote)
        Quotable(onQuote) { BlockView(block, mark = if (lit) note?.quote.orEmpty() else "") }
        if (note == null) {
            Spacer(Modifier.fillMaxWidth().height(Space.para))
        } else {
            val ask = remember { FocusRequester() }
            if (note.quote.isNotEmpty() && !lit) {
                Row(Modifier.padding(top = 10.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(look.ink, RoundedCornerShape(1.dp)))
                    Text(note.quote, Modifier.padding(start = 10.dp), look.soft, style = Words.small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            NoteField(
                note.text,
                TYPE_HERE,
                { onNote(note.copy(text = it)) },
                Modifier.padding(start = 20.dp, top = 10.dp, bottom = 16.dp),
                ask,
                onBlur = onDone,
            )
            LaunchedEffect(focused) { if (focused) ask.requestFocus() }
        }
    }
}

@Composable
private fun NoteField(
    text: String,
    hint: String,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onBlur: (() -> Unit)? = null,
) {
    val ink = look.ink
    val see = remember { BringIntoViewRequester() }
    var had by remember { mutableStateOf(false) }
    var high by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // The whole box clears the bar once the keyboard has settled, not only the cursor.
    LaunchedEffect(had) {
        if (had) {
            snapshotFlow { ime.getBottom(density) }.collectLatest {
                delay(80)
                val margin = with(density) { 40.dp.toPx() }
                see.bringIntoView(Rect(0f, 0f, 1f, high + margin))
            }
        }
    }
    BasicTextField(
        value = text,
        onValueChange = onText,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(see)
            .onSizeChanged { high = it.height }
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged {
                if (had && !it.isFocused) onBlur?.invoke()
                had = it.isFocused
            }
            .semantics { contentDescription = hint },
        textStyle = Words.yours.copy(color = ink),
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box(
                Modifier
                    // Outlined, since a filled panel is what his words look like once they're sent.
                    .border(1.dp, if (had) look.faint else look.line, RoundedCornerShape(18.dp))
                    .heightIn(min = Space.target)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(hint, color = look.faint, style = Words.yours)
                inner()
            }
        },
    )
}

/** Selecting words offers Quote this; each paragraph is its own selection, so a quote knows its paragraph. */
@Composable
private fun Quotable(onQuote: (String) -> Unit, content: @Composable () -> Unit) {
    val real = LocalClipboard.current
    val catcher = remember(real) { Catcher(real) }
    catcher.onWords = onQuote
    CompositionLocalProvider(LocalClipboard provides catcher) {
        Box(
            Modifier
                .filterTextContextMenuComponents { c ->
                    if (c is TextContextMenuItem && c.key === TextContextMenuKeys.CopyKey) catcher.copy = c
                    c.key === TextContextMenuKeys.CopyKey || c.key === QUOTE
                }
                .appendTextContextMenuComponents {
                    item(QUOTE, "Quote this") { catcher.catching { catcher.copy?.onClick?.invoke(this) } }
                },
        ) { SelectionContainer(content = content) }
    }
}

private val QUOTE = Any()

/**
 * Compose has no public way to ask a selection for its words, but its Copy has them. So Quote this
 * presses Copy with this clipboard in the way: the words land here and the real clipboard stays as it
 * was. Copy pressed by hand passes straight through.
 */
private class Catcher(private val real: Clipboard) : Clipboard {
    var copy: TextContextMenuItem? = null
    var onWords: (String) -> Unit = {}
    private var catching = false

    fun catching(press: () -> Unit) {
        catching = true
        press()
    }

    override val nativeClipboard: NativeClipboard get() = real.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (!catching) return real.setClipEntry(clipEntry)
        catching = false
        clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { onWords(it.toString()) }
    }
}
