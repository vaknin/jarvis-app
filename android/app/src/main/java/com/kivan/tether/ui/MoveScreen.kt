package com.kivan.tether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kivan.tether.Move

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)

/** On home, until the move is done: one line, one tap. */
@Composable
fun MoveBanner() {
    val ctx = LocalContext.current
    val offer by Move.offer.collectAsState()
    if (offer.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Raised)
            .clickable { Move.act(ctx) }.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(offer, color = Accent, style = MaterialTheme.typography.titleSmall)
        Text("Everything comes with you. This app keeps working until the new one is ready.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** What's left of this app once the new one holds the link: a way there, and a way back. */
@Composable
fun MovedScreen() {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Jarvis moved", style = MaterialTheme.typography.headlineSmall)
                Text("This is the old app. It's switched off, so the two can't get in each other's way.", color = Muted, textAlign = TextAlign.Center)
                Button(onClick = { Move.open(ctx) }) { Text("Open Jarvis") }
                TextButton(onClick = { Move.takeBack(ctx) }) { Text("Use this one again") }
                Text("Going back leaves anything new in the new app.", color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
