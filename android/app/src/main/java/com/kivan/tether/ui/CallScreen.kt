package com.kivan.tether.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kivan.tether.R
import com.kivan.tether.Voice

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)
private val Go = Color(0xFF3FB97A)
private val Stop = Color(0xFFE5534B)

/** The call: who, where it is, the last lines, and buttons big enough to hit without looking. */
@Composable
fun CallScreen(onCall: () -> Unit, onMute: () -> Unit, onHangup: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        val ui by Voice.ui.collectAsState()
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Text("Jarvis", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ui.live) Voice.label(ui.state) + if (ui.muted) " · muted" else "" else Voice.label(ui.state),
                    color = if (ui.live) Accent else Muted,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (ui.heard.isNotBlank()) Line("You", ui.heard)
                    if (ui.said.isNotBlank()) Line("Jarvis", ui.said)
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
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
                    }
                } else {
                    Round(R.drawable.lucide_phone, "Call", Go, Color.White, 144.dp, onCall)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun Line(who: String, text: String) {
    Column {
        Text(who, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
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
        Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
    }
}
