package com.kivan.jarvis.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Follows the keyboard frame by frame, and sits above the gesture bar when there's none. */
@Composable
fun Modifier.overKeyboard(): Modifier = windowInsetsPadding(
    WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
)

/** The call, the mic and the tray, as the words box shows them. */
@Immutable
data class Talk(val dictating: Boolean = false, val call: OnCall? = null, val attached: List<Attached> = emptyList())

val LocalTalk = compositionLocalOf { Talk() }

/** What Send sends: his typed words, then what he said, as one message. */
internal fun said(typed: String, heard: String) = listOf(typed, heard).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/**
 * The bottom of every screen: the words box with attach and the mic in it, and Call beside it, all
 * under the thumb. [hint] says where the words go; [tab] is where Call calls, a new tab when null.
 * [above] sits on the box; [ready] says there's something to send even with no words.
 * Enter is a new line and Send is a button, since a thought runs over lines. [talk] false: a box for
 * searching, with none of that.
 */
@Composable
fun Dock(
    hint: String,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    focus: FocusRequester? = null,
    showSend: Boolean = true,
    heard: String = "",
    onHeard: () -> Unit = {},
    onStop: (() -> Unit)? = null,
    talk: Boolean = true,
    tab: String? = null,
    ready: Boolean = false,
    above: (@Composable () -> Unit)? = null,
) {
    val on = LocalTalk.current
    Column(modifier.overKeyboard().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
        if (talk) {
            CallStrip(tab)
            Tray(on.attached, tab == null)
        }
        above?.invoke()
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            leading?.invoke(this)
            WordsBox(hint, text, onText, onSend, Modifier.weight(1f), focus, showSend, heard, onHeard, onStop, talk, ready)
            if (talk) CallButton(tab)
        }
    }
}

/** A call in this tab. With one going on elsewhere it comes here; with one here it opens it. */
@Composable
private fun CallButton(tab: String?) {
    val deck = LocalDeck.current
    val call = LocalTalk.current.call
    val here = call != null && call.tab == tab
    Round(
        R.drawable.lucide_phone,
        if (here) "Back to the call" else "Call Jarvis here",
        { deck?.call(tab) },
        fill = if (here) look.ink else look.surface,
        tint = if (here) look.canvas else look.soft,
        size = 48.dp,
    )
}

/** The call going on while he looks around: one tap back to it, from every screen. */
@Composable
fun CallStrip(tab: String? = null, modifier: Modifier = Modifier) {
    val deck = LocalDeck.current
    val call = LocalTalk.current.call ?: return
    val where = call.name.takeIf { call.tab != tab && it.isNotEmpty() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(look.raised)
            .clickable(onClickLabel = "Back to the call") { deck?.openCall() }
            .heightIn(min = Space.target)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(R.drawable.lucide_phone, look.ink, 18.dp)
        Text(listOfNotNull("On a call", where, call.line).joinToString(" · "), color = look.ink, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What goes with his next words. At the wall, picking where is still to come. */
@Composable
private fun Tray(files: List<Attached>, wall: Boolean) {
    if (files.isEmpty()) return
    val deck = LocalDeck.current
    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (wall) Text("Tap a tab, or type to start a new one", Modifier.padding(start = 8.dp), look.soft, style = Words.small)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in files) key(f.path) {
                Row(
                    Modifier.background(look.surface, RoundedCornerShape(16.dp)).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bitmap by produceState<ImageBitmap?>(null, f.path) {
                        if (f.picture) value = withContext(Dispatchers.IO) { runCatching { thumb(f.path) }.getOrNull() }
                    }
                    val shown = bitmap
                    if (shown != null) {
                        Image(shown, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Glyph(R.drawable.lucide_file, look.soft, 18.dp)
                    }
                    Text(f.name, Modifier.padding(start = 8.dp).widthIn(max = 160.dp), look.ink, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Round(R.drawable.lucide_x, "Remove ${f.name}", { deck?.detach(f.path) }, size = 38.dp)
                }
            }
        }
    }
}

/** The mic button: filled while his words are collecting. Not on a call, where the call has the mic. */
@Composable
fun MicButton(modifier: Modifier = Modifier, rest: Color = Color.Transparent, size: Dp = 38.dp) {
    val deck = LocalDeck.current
    val on = LocalTalk.current.dictating
    Round(
        R.drawable.lucide_mic,
        if (on) "Stop dictating" else "Dictate",
        { deck?.dictate() },
        modifier,
        fill = if (on) look.ink else rest,
        tint = if (on) look.canvas else look.soft,
        size = size,
    )
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
    heard: String,
    onHeard: () -> Unit,
    onStop: (() -> Unit)?,
    talk: Boolean,
    ready: Boolean,
) {
    val deck = LocalDeck.current
    val on = LocalTalk.current
    val ink = look.ink
    val hasWords = text.isNotBlank() || heard.isNotBlank()
    val canSend = showSend && (hasWords || ready || talk && on.attached.isNotEmpty())
    Row(
        modifier
            .heightIn(min = Space.target)
            .background(look.surface, RoundedCornerShape(24.dp)),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (talk) Round(R.drawable.lucide_paperclip, "Attach a picture or file", { deck?.pick() }, size = 38.dp)
        Column(Modifier.weight(1f)) {
            if (heard.isNotBlank()) {
                // What he said waits here; a tap sends it.
                Text(
                    heard,
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Send what you said", onClick = onHeard)
                        .padding(start = if (talk) 2.dp else 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    look.soft,
                    style = Words.yours,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .heightIn(min = Space.target)
                    .testTag("box")
                    .semantics { contentDescription = hint },
                textStyle = Words.yours.copy(color = ink),
                cursorBrush = SolidColor(ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                decorationBox = { inner ->
                    Box(Modifier.padding(start = if (talk) 2.dp else 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), Alignment.CenterStart) {
                        if (text.isEmpty() && heard.isBlank()) Text(hint, color = look.faint, style = Words.yours, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
        // Words typed: Send takes the mic's place, unless he's dictating, when it has to stay to stop.
        if (talk && on.call == null && (on.dictating || text.isBlank())) MicButton()
        AnimatedVisibility(
            canSend,
            enter = fadeIn(tween(Curves.QUICK)) + scaleIn(tween(Curves.QUICK), 0.6f),
            exit = fadeOut(tween(Curves.QUICK)) + scaleOut(tween(Curves.QUICK), 0.6f),
        ) {
            Round(R.drawable.lucide_arrow_up, "Send", onSend, fill = ink, tint = look.canvas, size = 38.dp)
        }
        AnimatedVisibility(
            !hasWords && !canSend && onStop != null,
            enter = fadeIn(tween(Curves.QUICK)) + scaleIn(tween(Curves.QUICK), 0.6f),
            exit = fadeOut(tween(Curves.QUICK)) + scaleOut(tween(Curves.QUICK), 0.6f),
        ) {
            Round(R.drawable.lucide_square, "Stop", { onStop?.invoke() }, fill = ink, tint = look.canvas, size = 38.dp)
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
