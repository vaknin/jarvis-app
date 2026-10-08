package com.kivan.tether.dibs.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.ideas.IdeaButton
import com.kivan.tether.dibs.ideas.IdeaNote
import com.kivan.tether.dibs.ideas.Ideas
import com.kivan.tether.dibs.ideas.IdeaRecording
import com.kivan.tether.dibs.ideas.RecorderState
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.HeroNumber
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.launch
import org.json.JSONObject

// What the Ideas tab, a note's page and the tile's screens share: the microphone, the recording
// panel, the typing box, and sending a button dibs worded.

/**
 * Starts a recording ([note]: the note it adds to), asking for the microphone first when it isn't
 * allowed yet; once Android stops asking, the app's settings open instead. [onDenied]: the user
 * said no.
 */
@Composable
internal fun rememberRecord(onDenied: () -> Unit = {}): (String?) -> Unit {
    val context = LocalContext.current
    var asked by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) IdeaRecording.start(context, asked) else onDenied()
    }
    return { note ->
        when {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> IdeaRecording.start(context, note)
            micAsked && !shouldAsk(context) -> {
                onDenied()
                openAppSettings(context)
            }
            else -> {
                asked = note
                micAsked = true
                ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** Android has been asked for the microphone once in this process: a second "no" means its settings. */
private var micAsked = false

private fun shouldAsk(context: Context): Boolean =
    (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: true

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Runs [block] on the process's scope: a draft's save or send outlives the screen that asked for it. */
internal fun later(block: suspend () -> Unit): kotlinx.coroutines.Job = IdeaRecording.scope.launch { block() }

/** "1:41" (or "1:02:03"): a recording's length. */
internal fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A recording under way, calm: what it is, the time in one big number, the last seconds' level as
 * a line, and one Stop.
 */
@Composable
internal fun RecordPanel(state: RecorderState, title: String, modifier: Modifier = Modifier, big: Boolean = false) {
    val context = LocalContext.current
    val rec = state as? RecorderState.Recording
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(rec?.elapsedMs) {
        if (rec == null) return@LaunchedEffect
        levels += rec.level
        while (levels.size > LEVELS) levels.removeAt(0)
    }
    Column(
        modifier.fillMaxWidth().background(Palette.Surface, MaterialTheme.shapes.large).padding(Space.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Eyebrow(title, dot = rec != null, color = Palette.Accent)
        HeroNumber(clock(rec?.elapsedMs ?: 0), style = if (big) AppType.hero else AppType.heroSmall)
        LevelLine(levels, Modifier.fillMaxWidth().height(if (big) 56.dp else 36.dp))
        StopButton(big) { IdeaRecording.stop(context) }
    }
}

private const val LEVELS = 80

/** The level of the last [LEVELS] readings, newest at the right, as one accent line. */
@Composable
private fun LevelLine(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val mid = size.height / 2
        drawLine(Palette.Line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        if (levels.isEmpty()) return@Canvas
        val step = size.width / (LEVELS - 1)
        val start = size.width - step * (levels.size - 1)
        val path = Path()
        levels.forEachIndexed { i, l ->
            val x = start + step * i
            val y = mid - (l.coerceIn(0f, 1f) * mid * 0.9f) * if (i % 2 == 0) 1 else -1
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Palette.Accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The one big round Stop. */
@Composable
private fun StopButton(big: Boolean, onStop: () -> Unit) {
    val size = if (big) 88.dp else 64.dp
    Box(
        Modifier.size(size).clip(Pill).background(Palette.Danger).clickable(onClickLabel = "Stop", onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.lucide_square), "Stop", Modifier.size(size / 3), tint = Palette.OnDanger)
    }
}

/**
 * A recording that has just ended says how ("Saved", "Nothing recorded") for a moment, then the
 * recorder is idle again.
 */
@Composable
internal fun FinishedLine(state: RecorderState, modifier: Modifier = Modifier) {
    val done = state as? RecorderState.Finished ?: return
    LaunchedEffect(done) {
        kotlinx.coroutines.delay(2_500)
        IdeaRecording.clear()
    }
    Text(done.message, modifier.fillMaxWidth(), style = AppType.small, color = Palette.Muted, textAlign = TextAlign.Center)
}

/**
 * A box to type an idea in, several lines, kept in [Dibs.fields] under [key] across tabs. The arrow
 * (or [saveLabel]'s button, below) hands the text to [onSave].
 */
@Composable
internal fun IdeaTextBox(
    key: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    focus: Boolean = false,
    saveLabel: String? = null,
    onSave: (String) -> Unit,
) {
    val text = Dibs.fields[key].orEmpty()
    val save = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSave(t)
            Dibs.fields.remove(key)
        }
    }
    val focuser = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { focuser.requestFocus() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small).padding(start = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { Dibs.fields[key] = it },
                textStyle = AppType.body.merge(TextStyle(color = Palette.Text, textDirection = TextDirection.Content)),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = minLines,
                maxLines = 10,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(focuser),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(placeholder, style = AppType.body, color = Palette.Muted)
                        inner()
                    }
                },
            )
            if (saveLabel == null) {
                IconButton(onClick = save, enabled = text.isNotBlank(), modifier = Modifier.size(44.dp)) {
                    Icon(
                        painterResource(R.drawable.lucide_send_horizontal),
                        "Save",
                        Modifier.size(18.dp),
                        tint = if (text.isNotBlank()) Palette.Accent else Palette.Muted,
                    )
                }
            } else {
                Box(Modifier.size(12.dp))
            }
        }
        if (saveLabel != null) {
            ActButton(saveLabel, "primary", Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = text.isNotBlank(), onClick = save)
        }
    }
}

/** A big round button with a Lucide icon and a word under it: Record, on the tab. */
@Composable
internal fun RoundAction(icon: Int, label: String, modifier: Modifier = Modifier, accent: Boolean = true, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(72.dp).clip(Pill).background(if (accent) Palette.Accent else Palette.SurfaceHigh).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(30.dp), tint = if (accent) Palette.OnAccent else Palette.Text)
        }
        Text(label, style = AppType.label, color = Palette.Text)
    }
}

/** A note's state in dibs's words, coloured by its tone (and marked, never by colour alone). */
@Composable
internal fun IdeaStatusLine(text: String, tone: String) {
    when (tone) {
        "work" -> StateWord(text, busy = true)
        "problem" -> Chip(text, warn = true)
        else -> Text(text, style = AppType.small, color = if (tone == "ask") Palette.Accent else Palette.Muted)
    }
}

/** dibs's buttons, as it worded them; [onTap] sends one. */
@Composable
internal fun IdeaButtons(buttons: List<IdeaButton>, modifier: Modifier = Modifier, onTap: (IdeaButton) -> Unit) {
    if (buttons.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        for (b in buttons) ActButton(b.label, b.style, busy = Dibs.busy(IdeaTaps.key(b))) { onTap(b) }
    }
}

/**
 * The phone's echo of a tap on a note ([IdeaTaps.tap]): what was sent, shown at once, until a view
 * shows it applied: Done's note gone from the list, Restore's back on it, an answer's buttons or
 * state changed. Tether rebuilds its views when its node restarts, so a view arriving is no sign
 * dibs has had the tap; the laptop may be asleep for hours with it queued. Only while the link is up
 * does an echo give way after [HOLD_MS] (dibs had it and didn't apply it).
 */
internal object IdeaTaps {
    private data class Tap(val action: String, val label: String, val atMs: Long, val before: String?)

    private const val HOLD_MS = 5 * 60_000L

    private val taps = androidx.compose.runtime.mutableStateMapOf<String, Tap>()

    /** Taps a view has shown applied: they stay over even if the note moves back later. */
    private val over = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Tap, Boolean>())

    /** The tracker key of a note's button. */
    fun key(b: IdeaButton): String = "idea:${b.value.optString("note")}:${b.action}"

    /** Sends [b] with the tap's time (`at`, ms), and remembers it for its note as [ideas] showed it. */
    fun tap(b: IdeaButton, ideas: Ideas?) {
        val now = System.currentTimeMillis()
        val value = JSONObject(b.value.toString()).put("at", now)
        val note = b.value.optString("note")
        if (b.action == "fetch") Dibs.host.act(b.action, value) else Dibs.tap(key(b), "Sending “${b.label}”", "“${b.label}”", b.action, value)
        if (note.isNotEmpty() && b.action != "fetch") taps[note] = Tap(b.action, b.label, now, ideas?.note(note)?.let(::shape))
    }

    /** What an answer changes: the note's buttons and its state. */
    private fun shape(n: IdeaNote): String = n.actions.joinToString { "${it.action}${it.value}" } + "|" + n.status

    private fun live(ideas: Ideas?, note: String): Tap? {
        val t = taps[note]?.takeIf { it !in over } ?: return null
        val applied = ideas != null && when (t.action) {
            "idea-done" -> ideas.note(note) == null
            "idea-restore" -> ideas.note(note) != null
            else -> ideas.note(note)?.let(::shape) != t.before
        }
        val stale = Dibs.host.link.value == Link.CONNECTED && System.currentTimeMillis() - t.atMs > HOLD_MS
        if (applied || stale) over += t
        return t.takeIf { !applied && !stale }
    }

    /** Done or Restore was tapped on [note] and [ideas] doesn't show it yet: it has moved, as far as this screen goes. */
    fun moved(ideas: Ideas?, note: String, action: String): Boolean = live(ideas, note)?.action == action

    /** The label of the question's answer tapped on [note] ("Research"), while [ideas] doesn't show it yet. */
    fun answered(ideas: Ideas?, note: String): String? = live(ideas, note)?.takeIf { it.action == "idea-answer" }?.label

    internal fun reset() {
        taps.clear()
        over.clear()
    }
}
