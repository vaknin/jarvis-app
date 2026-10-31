package com.kivan.jarvis.tabs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R

/** The hint under a paragraph, and what the box says inside. */
private const val WRITE_HERE = "Answer this"
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
    Column(modifier.fillMaxSize().background(look.canvas).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Round(R.drawable.lucide_arrow_left, "Back, the draft is kept", onBack)
            Text("Reply", Modifier.padding(start = 4.dp, end = 10.dp), look.ink, style = Words.title)
            Text(name, Modifier.weight(1f), look.faint, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.TopCenter) {
            LazyColumn(
                Modifier.reading().fillMaxSize().testTag("reply"),
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
        }
        Row(
            Modifier.overKeyboard().padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Only what you answer is sent",
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
) {
    Column(Modifier.animateContentSize(tween(Curves.PAGE, easing = Curves.out))) {
        SelectionContainer { BlockView(block) }
        if (note == null) {
            // The whole gap under a paragraph is the target, since "under it" is the only instruction.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = WRITE_HERE, onClick = onWrite)
                    .heightIn(min = 44.dp)
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Glyph(R.drawable.lucide_corner_down_left, look.faint, 14.dp)
                Text(WRITE_HERE, color = look.faint, style = Words.small)
            }
        } else {
            val ask = remember { FocusRequester() }
            if (note.quote.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxSize().background(look.ink, RoundedCornerShape(1.dp)))
                    Text(note.quote, Modifier.padding(start = 10.dp), look.soft, style = Words.small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            NoteField(
                note.text,
                TYPE_HERE,
                { onNote(note.copy(text = it)) },
                Modifier.padding(top = 10.dp, bottom = 16.dp),
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
    BasicTextField(
        value = text,
        onValueChange = onText,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(see)
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
                    .background(look.surface, RoundedCornerShape(18.dp))
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
