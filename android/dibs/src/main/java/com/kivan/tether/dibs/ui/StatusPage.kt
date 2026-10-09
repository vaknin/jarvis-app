package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Lend
import com.kivan.tether.dibs.LendToggle
import com.kivan.tether.dibs.Lends
import com.kivan.tether.dibs.Limit
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.laptopWords
import com.kivan.tether.dibs.limitWords
import com.kivan.tether.dibs.stateWords
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * The dibs page (opened from the slim bar): the state in words, the two lend switches as full cards,
 * Claude's usage (every window, when it resets), the laptop's load and room, Start dibs when the brain
 * is down, and Open Tether. The one place for all of it; no tab shows lend cards or usage.
 */
@Composable
internal fun StatusPage(view: DibsView, link: Link) {
    val state = view.state
    val words = stateWords(link, state)
    Column(Modifier.fillMaxSize()) {
        PageBar("dibs", "Laptop and phone")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.L),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Section("State")
            Text(words, style = AppType.body, color = Palette.Text)
            if (state.busy && !state.line.isNullOrBlank()) Text(state.line, style = AppType.small, color = Palette.Muted)
            if (link == Link.CONNECTED && state.brainDown) {
                ActButton("Start dibs", "primary", tap = "brain-start") { Dibs.host.act("brain-start") }
            }

            Section("Lend to dibs")
            val lends = view.lends
            if (lends != null && (lends.phone != null || lends.laptop != null || lends.compute != null)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    lends.phone?.let { LendToggleCard(it, "Phone", R.drawable.lucide_smartphone, Modifier.fillMaxWidth()) }
                    lends.laptop?.let { LendToggleCard(it, "Laptop", R.drawable.lucide_laptop, Modifier.fillMaxWidth()) }
                    lends.compute?.let { LendToggleCard(it, "Laptop's memory and processor", R.drawable.lucide_cpu, Modifier.fillMaxWidth()) }
                }
            } else if (view.lend != null) {
                LendBar(view.lend)
            } else {
                Text("This dibs sends no switches.", style = AppType.small, color = Palette.Muted)
            }

            if (state.limits.isNotEmpty()) {
                Section("Claude usage")
                Usage(state.limits)
            }

            val laptop = state.laptop?.let(::laptopWords)
            val room = view.board?.room
            if (laptop != null || room != null) {
                Section(state.laptop?.name ?: "Laptop")
                if (laptop != null) Text(laptop, style = AppType.small, color = Palette.Muted)
                // Amber with the clock when there is no room for another task; plain otherwise.
                if (room != null) RoomLine(room.line, warn = room.n == 0)
            }

            Section("Tether")
            ActButton("Open Tether", "default", icon = R.drawable.lucide_external_link) { Dibs.host.openTether() }
            Column(Modifier.height(Space.L)) {}
        }
    }
}

/**
 * Claude's usage on the dibs page: each window's percent and when it resets, amber near the end.
 * Never cut: a line too long for its room breaks only after its "·" ("7d 93% ·" over "resets Sun
 * 10:00"), as each half is held together.
 */
@Composable
internal fun Usage(limits: List<Limit>) {
    val ctx = LocalContext.current
    val now by rememberNow()
    val lines = remember(limits, now / 60) {
        val zone = ZoneId.systemDefault()
        limitWords(
            limits, now,
            clock = { android.text.format.DateFormat.getTimeFormat(ctx).format(Date(it * 1000)) },
            day = { Instant.ofEpochSecond(it).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
        )
    }
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (l in lines) {
            Text(
                remember(l.text) { l.text.replace(' ', NBSP).replace("$NBSP·$NBSP", "$NBSP· ") },
                style = AppType.small,
                color = if (l.warn) Palette.Warning else Palette.Muted,
            )
        }
    }
}

internal const val NBSP = '\u00A0'

/** "dibs has your phone": above every tab while dibs has it, Take it back in one tap. */
@Composable
internal fun LendBar(lend: Lend) {
    Row(
        Modifier.fillMaxWidth()
            .background(Palette.AccentDim, MaterialTheme.shapes.medium)
            .padding(start = Space.M, end = Space.S, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.lucide_smartphone), null, Modifier.size(18.dp), tint = Palette.Accent)
        Column(Modifier.weight(1f)) {
            Text("dibs has your phone", style = AppType.label, color = Palette.Text)
            if (lend.text.isNotBlank()) Text(lend.text, style = AppType.small, color = Palette.Text)
        }
        ActButton("Take it back", "primary", tap = "phone-back") { Dibs.host.act("phone-back") }
    }
}

@Composable
private fun LendToggleCard(t: LendToggle, title: String, icon: Int, modifier: Modifier) {
    // Between the tap and dibs's next view: say so, and don't send it twice.
    var sent by remember(t.lent, t.action) { mutableStateOf(false) }
    // A tap dibs refused (a locked laptop, a phone out of reach) changes nothing: free it again.
    LaunchedEffect(sent) {
        if (sent) {
            delay(15_000)
            sent = false
        }
    }
    val sub = when {
        sent -> if (t.lent) "Taking it back" else "Lending"
        t.lent -> t.text.ifBlank { "Lent to dibs" }
        else -> "Yours"
    }
    Row(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(if (t.lent) Palette.AccentDim else Palette.SurfaceLow)
            .toggleable(value = t.lent, enabled = !sent, role = Role.Switch) {
                sent = true
                Dibs.host.act(t.action)
            }
            .semantics { stateDescription = if (t.lent) "lent to dibs" else "yours" }
            .padding(horizontal = Space.M, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = if (t.lent) Palette.Accent else Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(if (t.lent) "$title lent to dibs" else title, style = AppType.label, color = Palette.Text)
            Text(sub, style = AppType.small, color = if (t.lent) Palette.Text else Palette.Muted)
        }
    }
}

/** The laptop's room for more tasks, in dibs's words: amber with a clock when there is none (never colour alone). */
@Composable
internal fun RoomLine(line: String, warn: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (warn) Icon(painterResource(R.drawable.lucide_clock), null, Modifier.padding(top = 2.dp).size(14.dp), tint = Palette.Warning)
        Text(line, style = AppType.small, color = if (warn) Palette.Warning else Palette.Muted)
    }
}
