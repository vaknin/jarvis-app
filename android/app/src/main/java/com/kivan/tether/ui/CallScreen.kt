package com.kivan.tether.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kivan.tether.AudioRoute
import com.kivan.tether.Avail
import com.kivan.tether.CallLog
import com.kivan.tether.R
import com.kivan.tether.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)
private val Mine = Color(0xFF243447)
private val Go = Color(0xFF3FB97A)
private val Stop = Color(0xFFE5534B)

/** The call: who, where it is, what was said (this call and the ones before), and buttons big enough to hit without looking. */
@Composable
fun CallScreen(onCall: () -> Unit, onMute: () -> Unit, onHangup: () -> Unit, onRoute: () -> Unit, onPause: () -> Unit, onDecline: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        val ui by Voice.ui.collectAsState()
        val pieces by CallLog.pieces(LocalContext.current.applicationContext).collectAsState()
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (ui.ringing && !ui.live) return@Surface Incoming(onCall, onDecline)
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Duration(if (ui.live) ui.connectedAt else null)
                Text("Jarvis", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ui.live) Voice.line(ui) else if (ui.ringing) "Jarvis calling" else Voice.label(ui.state),
                    color = if (ui.live) Accent else Muted,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                AvailChip()
                Spacer(Modifier.height(12.dp))
                Transcript(pieces.orEmpty(), Modifier.weight(1f).fillMaxWidth())
                ui.error?.let {
                    Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                if (ui.live) {
                    // Stop Jarvis mid-reply and read; continue from the sentence he stopped in.
                    // The same height whether shown or not, so the buttons below never move.
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        if (ui.pausable) PauseButton(ui.paused, onPause)
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        Round(
                            if (ui.micOff) R.drawable.lucide_mic_off else R.drawable.lucide_mic,
                            if (ui.micOff) "Unmute" else "Mute",
                            if (ui.micOff) Color.White else Raised,
                            if (ui.micOff) Bg else Color.White,
                            96.dp,
                            onMute,
                        )
                        Round(R.drawable.lucide_phone_off, "Hang up", Stop, Color.White, 112.dp, onHangup)
                        // Where the call sounds; a tap moves it on, as a phone's own call screen does.
                        val route = ui.route ?: AudioRoute(AudioRoute.Kind.Earpiece, "Earpiece")
                        val loud = route.kind == AudioRoute.Kind.Speaker
                        Round(
                            when (route.kind) {
                                AudioRoute.Kind.Earpiece -> R.drawable.lucide_ear
                                AudioRoute.Kind.Speaker -> R.drawable.lucide_volume_2
                                AudioRoute.Kind.Bluetooth -> R.drawable.lucide_bluetooth
                                AudioRoute.Kind.Wired -> R.drawable.lucide_headphones
                            },
                            route.name,
                            if (loud) Color.White else Raised,
                            if (loud) Bg else Color.White,
                            96.dp,
                            onRoute,
                        )
                    }
                } else {
                    Round(R.drawable.lucide_phone, if (ui.ringing) "Answer" else "Call", Go, Color.White, 144.dp, onCall)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** Jarvis ringing (thread #36): a regular incoming call, "Jarvis" on the screen, Decline and Answer. */
@Composable
private fun Incoming(onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Incoming call", color = Muted, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(120.dp).clip(CircleShape).background(Mine), contentAlignment = Alignment.Center) {
            Text("J", color = Accent, style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(24.dp))
        Text("Jarvis", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            Round(R.drawable.lucide_phone_off, "Decline", Stop, Color.White, 88.dp, onDecline)
            Round(R.drawable.lucide_phone, "Answer", Go, Color.White, 88.dp, onAnswer)
        }
        Spacer(Modifier.height(56.dp))
    }
}

/** How Jarvis may reach Aviv off a call; a tap moves it on: available -> unavailable. Hidden while jarvis-voice can't be reached. */
@Composable
private fun AvailChip() {
    var mode by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { mode = Avail.get() }
    val m = mode ?: return
    Text(
        Avail.label(m) + if (busy) " …" else "",
        Modifier.clip(RoundedCornerShape(50)).background(if (m == "available") Raised else Mine)
            .clickable(enabled = !busy, onClickLabel = "Change how Jarvis reaches you") {
                busy = true
                scope.launch {
                    Avail.get(set = Avail.next(m))?.let { mode = it }
                    busy = false
                }
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = if (m == "available") Color.White else Accent,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** How long the call has been up, ticking each second; an empty line of the same height before it connects. */
@Composable
private fun Duration(since: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = System.currentTimeMillis()
            delay(1000 - (now - since) % 1000)
        }
    }
    Text(
        since?.let { Voice.clock(now - it) } ?: "",
        color = Muted,
        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
    )
}

/**
 * The chat of the calls, oldest at the top, a call's start between them. It follows new lines only
 * while it's at the bottom, so scrolling back up to read stays put.
 */
@Composable
private fun Transcript(pieces: List<CallLog.Piece>, modifier: Modifier) {
    val ctx = LocalContext.current
    // One piece per turn already (CallLog keeps each speaker's whole line as one piece).
    val turns = pieces
    val list = rememberLazyListState()
    // The end is the empty item after the last turn; a growing last bubble can push it off, which still counts.
    val atEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    val end = turns.size + turns.map { it.call }.distinct().size
    LaunchedEffect(Unit) { list.scrollToItem(end) }
    LaunchedEffect(pieces.lastOrNull()) { if (atEnd) list.animateScrollToItem(end) }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turns.forEachIndexed { i, t ->
            if (i == 0 || turns[i - 1].call != t.call) {
                item(key = "call ${t.call}") {
                    Text(
                        "Call · " + DateFormat.getMediumDateFormat(ctx).format(t.call) + " " + DateFormat.getTimeFormat(ctx).format(t.call),
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        color = Muted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "turn ${t.call} ${t.at} $i") { Bubble(t) }
        }
        item(key = "end") { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
private fun Bubble(t: CallLog.Piece) {
    val aviv = t.who != "jarvis"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (aviv) Arrangement.End else Arrangement.Start) {
        Text(
            t.text,
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(if (aviv) Mine else Raised)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PauseButton(paused: Boolean, onClick: () -> Unit) {
    val label = if (paused) "Continue" else "Pause"
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
            .background(if (paused) Accent else Raised).clickable(onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (paused) Bg else Color.White
        Icon(painterResource(if (paused) R.drawable.lucide_play else R.drawable.lucide_pause), null, Modifier.size(24.dp), tint = fg)
        Spacer(Modifier.size(12.dp))
        Text(label, color = fg, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun Round(icon: Int, label: String, bg: Color, fg: Color, size: Dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), label, Modifier.size(size * 0.36f), tint = fg)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label, Modifier.widthIn(max = size + 16.dp), color = Muted, style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
