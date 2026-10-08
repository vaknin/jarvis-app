package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.Dibs.TapState
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * One line at the bottom of the screen for the taps dibs hasn't answered: what is being sent, that it
 * waits for the laptop, a short "done", or plainly that dibs didn't answer, with Try again. It reads
 * [Dibs.taps], so it is the same on every screen, and ticks itself for the clock the taps run on.
 */
@Composable
internal fun TapLine(modifier: Modifier = Modifier) {
    var tick by remember { mutableLongStateOf(0L) }
    val taps = Dibs.taps.toList()
    // The clock only runs while a tap waits on it: a failed tap stays until the user dismisses it.
    val ticking = taps.any { it.failedAt == null }
    LaunchedEffect(ticking, Dibs.upSince, Dibs.heardKnown, taps.size) {
        if (!ticking) return@LaunchedEffect
        while (Dibs.expire()) {
            delay(1_000)
            tick++
        }
    }
    if (taps.isEmpty()) return
    // Keyed on the tick, so the line recomposes with the clock.
    val now = remember(tick, taps) { Dibs.now() }
    val states = taps.associateWith { Dibs.tapState(it, now) }
    // A failure first, then what is on its way (newest first), then a finished tap's words.
    val shown = states.entries.sortedWith(
        compareBy<Map.Entry<Dibs.Tap, TapState>> { rank(it.value) }.thenByDescending { it.key.at },
    )
    val (t, state) = shown.first().let { it.key to it.value }
    val more = shown.size - 1
    val failed = state == TapState.UNHEARD
    val muted = state == TapState.SENDING || state == TapState.WAITING
    val color = when (state) {
        TapState.UNHEARD -> Palette.Warning
        TapState.HEARD -> Palette.Accent
        else -> Palette.Muted
    }
    val words = when (state) {
        TapState.SENDING -> t.doing
        TapState.WAITING -> "Waits for the laptop: ${t.what}"
        TapState.HEARD -> t.done ?: t.doing
        TapState.UNHEARD -> "dibs didn't answer: ${t.what} wasn't done."
    }
    Row(
        modifier.fillMaxWidth()
            .background(Palette.SurfaceHigh)
            .padding(horizontal = Space.L, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            when (state) {
                TapState.SENDING -> Spinner(size = 14.dp)
                TapState.WAITING -> Icon(painterResource(R.drawable.lucide_clock), null, Modifier.size(16.dp))
                TapState.HEARD -> Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(16.dp))
                TapState.UNHEARD -> Icon(painterResource(R.drawable.lucide_triangle_alert), null, Modifier.size(16.dp))
            }
        }
        Text(
            words,
            Modifier.weight(1f),
            style = AppType.small,
            color = if (muted) Palette.Muted else if (failed) Palette.Text else color,
        )
        if (more > 0) Text("+$more more", style = AppType.mono, color = Palette.Muted)
        if (failed) {
            // A signed approval is one-shot: the card comes back to approve again instead.
            if (t.action != "root-approve") ActButton("Try again", "plain") { Dibs.retry(t) }
            IconButton(onClick = { Dibs.dismiss(t) }, Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.lucide_x), "Dismiss", Modifier.size(16.dp), tint = Palette.Muted)
            }
        }
    }
}

private fun rank(s: TapState): Int = when (s) {
    TapState.UNHEARD -> 0
    TapState.SENDING, TapState.WAITING -> 1
    TapState.HEARD -> 2
}
