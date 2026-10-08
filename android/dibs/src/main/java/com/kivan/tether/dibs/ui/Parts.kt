package com.kivan.tether.dibs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.dayWords
import com.kivan.tether.dibs.ui.theme.AppShapes
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.HeroNumber
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max
import kotlin.math.min

// Small pieces the four tabs share: buttons, chips, the answer box, a ticking clock, thumbnails.

/**
 * A button named for what it does. primary: filled accent; plain: quiet text; danger: red outline; else outlined.
 * [icon] (a drawable) goes before its words. [busy]: its tap is on its way to dibs: a spinner in
 * place of the icon, and more taps are ignored (it keeps its colours, since it reads as "sending",
 * not "not possible").
 */
@Composable
internal fun ActButton(
    label: String, style: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null,
    busy: Boolean = false, onClick: () -> Unit,
) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    val mod = modifier.heightIn(min = 36.dp).let { if (busy) it.semantics { stateDescription = "Sending" } else it }
    val shape = MaterialTheme.shapes.medium
    val click = { if (!busy) onClick() }
    val text: @Composable () -> Unit = {
        if (busy) Spinner(Modifier.padding(end = 8.dp)) else if (icon != null) Icon(painterResource(icon), null, Modifier.padding(end = 8.dp).size(18.dp))
        Text(label, style = AppType.label)
    }
    when (style) {
        "primary" -> Button(
            click, mod, enabled, shape = shape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        ) { text() }
        "plain" -> TextButton(click, mod, enabled, shape = shape, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            if (busy) Spinner(Modifier.padding(end = 6.dp))
            Text(label, style = AppType.label, color = Palette.Muted)
        }
        "danger" -> OutlinedButton(
            click, mod, enabled, shape = shape, contentPadding = pad,
            border = ButtonDefaults.outlinedButtonBorder(enabled).copy(brush = SolidColor(Palette.Danger)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
        ) { text() }
        else -> OutlinedButton(
            click, mod, enabled, shape = shape, contentPadding = pad,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
        ) { text() }
    }
}

/** A small spinner in the text colour, for a button whose tap is on its way. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    CircularProgressIndicator(modifier.size(size), color = LocalContentColor.current, strokeWidth = 2.dp)
}

/** A small pill: a repo, a hold. [warn] is amber with a ⚠, for a state that needs a look (never colour alone). */
@Composable
internal fun Chip(text: String, modifier: Modifier = Modifier, accent: Boolean = false, warn: Boolean = false) {
    val (bg, fg) = when {
        warn -> Palette.Warning.copy(alpha = 0.16f) to Palette.Warning
        accent -> Palette.AccentDim to Palette.Accent
        else -> Palette.SurfaceHigh to Palette.Muted
    }
    Row(
        modifier.background(bg, Pill).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (warn) Icon(painterResource(R.drawable.lucide_triangle_alert), null, Modifier.size(12.dp), tint = fg)
        Text(text, style = AppType.mono, color = fg)
    }
}

/** busy / idle as a word with a dot: filled while busy, hollow while idle. */
@Composable
internal fun StateWord(word: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val dot = Modifier.size(7.dp)
        Box(if (busy) dot.background(Palette.Accent, Pill) else dot.border(1.5.dp, Palette.Muted, Pill))
        Text(word, style = AppType.small, color = Palette.Muted)
    }
}

/**
 * A one-line answer box: the typed text goes to [onSend] (Enter or the arrow). The draft is kept in
 * [Dibs.fields] under [key], so it survives a tab change.
 */
@Composable
internal fun AnswerField(key: String, placeholder: String, modifier: Modifier = Modifier, background: Color = Palette.SurfaceLow, onSend: (String) -> Unit) {
    val text = Dibs.fields[key].orEmpty()
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t)
            Dibs.fields.remove(key)
        }
    }
    Row(
        modifier.background(background, MaterialTheme.shapes.small).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { Dibs.fields[key] = it },
            textStyle = AppType.body.merge(TextStyle(color = Palette.Text, textDirection = TextDirection.Content)),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 4,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(placeholder, style = AppType.body, color = Palette.Muted)
                    inner()
                }
            },
        )
        IconButton(onClick = send, enabled = text.isNotBlank(), modifier = Modifier.size(40.dp)) {
            Icon(
                painterResource(R.drawable.lucide_send_horizontal),
                "Send",
                Modifier.size(18.dp),
                tint = if (text.isNotBlank()) Palette.Accent else Palette.Muted,
            )
        }
    }
}

/** A second press within 4 s confirms (Stop, Undo): the first only arms the button. */
@Stable
internal class Armed {
    var key by mutableStateOf<String?>(null)

    /** Runs [action] if [k] is armed, else arms it. */
    fun press(k: String, action: () -> Unit) {
        if (key == k) {
            key = null
            action()
        } else {
            key = k
        }
    }
}

@Composable
internal fun rememberArmed(): Armed {
    val armed = remember { Armed() }
    LaunchedEffect(armed.key) {
        if (armed.key != null) {
            delay(4_000)
            armed.key = null
        }
    }
    return armed
}

/** The phone's clock in seconds, ticking every 30 s so ages ("12 min") stay true. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis() / 1000
    }
}

/** The thumbnail of a file sent from here, by Tether's file id, loaded off the main thread. */
@Composable
internal fun rememberThumb(fileId: String): ImageBitmap? {
    // A file dibs sent gets its thumbnail just after it arrives: look again when one is written.
    val written by Dibs.host.thumbs.collectAsStateWithLifecycle()
    val bmp by produceState<ImageBitmap?>(null, fileId, written) {
        val got = withContext(Dispatchers.IO) { runCatching { Dibs.host.thumb(fileId) }.getOrNull() }
        if (got != null || value == null) value = got
    }
    return bmp
}

/** The sharpest copy of an image in a line, by Tether's file id, for the full-screen view; null while it loads. */
@Composable
internal fun rememberFullImage(fileId: String): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, fileId) {
        value = withContext(Dispatchers.IO) { runCatching { Dibs.host.image(fileId, FULL_PX) }.getOrNull() }
    }
    return bmp
}

/** How big (longest side, px) a picture is decoded for the full-screen view. */
internal const val FULL_PX = 2560

/** A picked image, decoded small off the main thread (it isn't sent yet, so there is no thumbnail). */
@Composable
internal fun rememberPicked(uri: Uri, maxPx: Int = 480): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val s = info.size
                    val scale = min(1f, maxPx.toFloat() / max(s.width, s.height))
                    d.setTargetSize(max(1, (s.width * scale).toInt()), max(1, (s.height * scale).toInt()))
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

internal fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dibs", text))
}

/** A section's eyebrow on a scrolling tab, with room above it. */
@Composable
internal fun Section(text: String) {
    Eyebrow(text, Modifier.padding(top = Space.L, bottom = Space.XS))
}

/** The tab's signature: an eyebrow over one big tabular number, with an optional word after it. */
@Composable
internal fun Hero(label: String, value: String, after: String? = null, style: TextStyle = AppType.hero) {
    Column(Modifier.fillMaxWidth().padding(top = Space.XS, bottom = Space.S), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        Row {
            HeroNumber(value, Modifier.alignByBaseline(), style = style)
            if (after != null) {
                Text(after, Modifier.alignByBaseline().padding(start = Space.S), style = AppType.body, color = Palette.Muted)
            }
        }
    }
}

/** A card on the ground: the medium radius on the surface colour. */
internal fun Modifier.card() = fillMaxWidth().background(Palette.Surface, AppShapes.medium)

/** Short text with no room to spare, quiet: an empty tab's line. */
@Composable
internal fun Quiet(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = Space.L), style = AppType.body, color = Palette.Muted)
}

internal val Bold = FontWeight.W500

/** A page's top bar (over the tabs): back, an eyebrow over its title, and its own actions. */
@Composable
internal fun PageBar(title: String, eyebrow: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.XS, end = Space.XS, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { Dibs.back() }) {
            Icon(painterResource(R.drawable.lucide_arrow_left), "Back", Modifier.size(22.dp), tint = Palette.Text)
        }
        Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!eyebrow.isNullOrBlank()) Eyebrow(eyebrow)
            Text(title, style = AppType.heading, color = Palette.Text)
        }
        actions()
    }
}

/** When something happened: its time today, else its day and time ("Sun 4 Oct 20:01"). */
@Composable
internal fun whenWords(ts: Long): String {
    val ctx = LocalContext.current
    return remember(ts) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ts * 1000))
        if (day == today) time else "${dayWords(day, today)} $time"
    }
}
