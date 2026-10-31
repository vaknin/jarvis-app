package com.kivan.jarvis.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R

/** Follows the keyboard frame by frame, and sits above the gesture bar when there's none. */
@Composable
fun Modifier.overKeyboard(): Modifier = windowInsetsPadding(
    WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
)

/**
 * The bottom of every screen: the words box, with mute beside it, both under the thumb. [hint] says
 * where the words go. Enter is a new line and Send is a button, since a thought runs over lines.
 */
@Composable
fun Dock(
    hint: String,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    muted: Boolean,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    focus: FocusRequester? = null,
    showSend: Boolean = true,
) {
    Row(
        modifier
            .overKeyboard()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        leading?.invoke(this)
        WordsBox(hint, text, onText, onSend, Modifier.weight(1f), focus, showSend)
        MuteButton(muted, onMute)
    }
}

@Composable
private fun WordsBox(
    hint: String,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier,
    focus: FocusRequester?,
    showSend: Boolean,
) {
    val ink = look.ink
    val canSend = showSend && text.isNotBlank()
    Row(
        modifier
            .heightIn(min = Space.target)
            .background(look.surface, RoundedCornerShape(24.dp)),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier
                .weight(1f)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .heightIn(min = Space.target)
                .testTag("box")
                .semantics { contentDescription = hint },
            textStyle = Words.yours.copy(color = ink),
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 6,
            decorationBox = { inner ->
                Box(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), Alignment.CenterStart) {
                    if (text.isEmpty()) Text(hint, color = look.faint, style = Words.yours, maxLines = 1)
                    inner()
                }
            },
        )
        AnimatedVisibility(
            canSend,
            enter = fadeIn(tween(Curves.QUICK)) + scaleIn(tween(Curves.QUICK), 0.6f),
            exit = fadeOut(tween(Curves.QUICK)) + scaleOut(tween(Curves.QUICK), 0.6f),
        ) {
            Round(R.drawable.lucide_arrow_up, "Send", onSend, fill = ink, tint = look.canvas, size = 38.dp)
        }
    }
}

/** A strip under the status bar: what's wrong, and typing goes on behind it. */
@Composable
fun OfflineBar(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(look.raised).padding(horizontal = Space.margin, vertical = 7.dp)) {
        Text("Offline, reconnecting…", color = look.soft, style = Words.small)
    }
}

/** Text in a page keeps its reading width on a tablet. */
fun Modifier.reading(): Modifier = widthIn(max = Space.reading)

/** A page: on a tablet it stands in the middle at reading width, its header and box with it. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(look.canvas).windowInsetsPadding(WindowInsets.statusBars), Alignment.TopCenter) {
        Column(Modifier.reading().fillMaxSize(), content = content)
    }
}
