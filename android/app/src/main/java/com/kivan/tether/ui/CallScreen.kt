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
import com.kivan.tether.CallLog
import com.kivan.tether.R
import com.kivan.tether.Voice
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)
private val Mine = Color(0xFF243447)
private val Go = Color(0xFF3FB97A)
private val Stop = Color(0xFFE5534B)

/** The call: who, where it is, what was said (this call and the ones before), and buttons big enough to hit without looking. */
@Composable
fun CallScreen(onCall: () -> Unit, onMute: () -> Unit, onHangup: () -> Unit, onRoute: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        val ui by Voice.ui.collectAsState()
        val pieces by CallLog.pieces(LocalContext.current.applicationContext).collectAsState()
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Duration(if (ui.live) ui.connectedAt else null)
                Text("Jarvis", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ui.live) Voice.label(ui.state) + if (ui.muted) " · muted" else "" else Voice.label(ui.state),
                    color = if (ui.live) Accent else Muted,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Transcript(pieces.orEmpty(), Modifier.weight(1f).fillMaxWidth())
                ui.error?.let {
                    Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                if (ui.live) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        Round(
                            if (ui.muted) R.drawable.lucide_mic_off else R.drawable.lucide_mic,
                            if (ui.muted) "Unmute" else "Mute",
                            if (ui.muted) Color.White else Raised,
                            if (ui.muted) Bg else Color.White,
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
                    Round(R.drawable.lucide_phone, "Call", Go, Color.White, 144.dp, onCall)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
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
private fun Round(icon: Int, label: String, bg: Color, fg: Color, size: Dp, onClick: () -> Unit) {
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
