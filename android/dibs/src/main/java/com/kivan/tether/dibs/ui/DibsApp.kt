package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Badges
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Lend
import com.kivan.tether.dibs.LendToggle
import com.kivan.tether.dibs.Lends
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.State
import com.kivan.tether.dibs.stateWords
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space

/** The four tabs, by the key an intent names them with. */
internal enum class Tab(val key: String, val label: String, val icon: Int) {
    CHAT("chat", "Chat", R.drawable.lucide_message_circle),
    WAITING("waiting", "Waiting", R.drawable.lucide_inbox),
    WORK("work", "Work", R.drawable.lucide_hammer),
    RECAP("recap", "Recap", R.drawable.lucide_history),
}

/** dibs's screen: the header, the lend bar when dibs has the phone, a tab, and the tabs below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DibsApp() {
    val host = Dibs.host
    val json by host.view.collectAsStateWithLifecycle()
    val link by host.link.collectAsStateWithLifecycle()
    val view = remember(json) { runCatching { DibsView.ofView(json) }.getOrNull() }
    LaunchedEffect(view) { Dibs.seen(view) }

    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val asked = Dibs.tab
    LaunchedEffect(asked) {
        if (asked == null) return@LaunchedEffect
        Tab.entries.firstOrNull { it.key == asked }?.let { tab = it }
        Dibs.tab = null
    }
    // The keyboard needs the room; the tabs come back when it closes.
    val typing = WindowInsets.isImeVisible

    Scaffold(
        containerColor = Palette.Bg,
        contentColor = Palette.Text,
        topBar = { Header(link, view?.state) },
        bottomBar = { if (view != null && !typing) NavBar(tab, view.badges) { tab = it } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            if (view == null) {
                Empty(hasView = json != null)
                return@Column
            }
            // Two toggles from a dibs that sends them; the older "dibs has your phone" bar otherwise.
            val lends = view.lends
            if (lends != null && (lends.phone != null || lends.laptop != null)) LendToggles(lends) else view.lend?.let { LendBar(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.CHAT -> ChatTab(view)
                    Tab.WAITING -> WaitingTab(view)
                    Tab.WORK -> WorkTab(view)
                    Tab.RECAP -> RecapTab(view)
                }
            }
        }
    }
}

/** The mark on dibs's tile, the state in an eyebrow above the name, and ⋮. */
@Composable
private fun Header(link: Link, state: State?) {
    var menu by remember { mutableStateOf(false) }
    val words = stateWords(link, state)
    // Red only with words: the link is lost. Muted while it can't do anything for you.
    val color = when {
        link == Link.UNPAIRED -> Palette.Danger
        link == Link.OFFLINE || state == null -> Palette.Muted
        !state.busy && !state.usage.isNullOrBlank() -> Palette.Warning
        else -> Palette.Accent
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.Bg).statusBarsPadding().padding(start = Space.L, end = Space.XS, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Tile), contentAlignment = Alignment.Center) {
            // The launcher's monochrome layer: its mark fills 46 of 108 dp, so draw it larger than the tile.
            Icon(painterResource(R.drawable.ic_dibs_monochrome), null, Modifier.requiredSize(48.dp), tint = Palette.Text)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(words, color = color)
            Text("dibs", style = AppType.heading, color = Palette.Text)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More", tint = Palette.Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Open Tether") },
                    leadingIcon = { Icon(painterResource(R.drawable.lucide_external_link), null, Modifier.size(18.dp)) },
                    onClick = {
                        menu = false
                        Dibs.host.openTether()
                    },
                )
            }
        }
    }
}

/** "dibs has your phone": above every tab while dibs has it, Take it back in one tap. */
@Composable
private fun LendBar(lend: Lend) {
    Row(
        Modifier.padding(horizontal = Space.L).padding(bottom = Space.S).fillMaxWidth()
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
        ActButton("Take it back", "primary") { Dibs.host.act("phone-back") }
    }
}

/** Lend the phone and the laptop to dibs, or take them back (task #29): one tap each, above every tab. */
@Composable
private fun LendToggles(lends: Lends) {
    Row(
        Modifier.padding(horizontal = Space.L).padding(bottom = Space.S).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        lends.phone?.let { LendToggleCard(it, "Phone", R.drawable.lucide_smartphone, Modifier.weight(1f)) }
        lends.laptop?.let { LendToggleCard(it, "Laptop", R.drawable.lucide_laptop, Modifier.weight(1f)) }
    }
}

@Composable
private fun LendToggleCard(t: LendToggle, title: String, icon: Int, modifier: Modifier) {
    // Between the tap and dibs's next view: say so, and don't send it twice.
    var sent by remember(t.lent, t.action) { mutableStateOf(false) }
    val sub = when {
        sent -> if (t.lent) "Taking it back…" else "Lending…"
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
            Text(if (t.lent) "$title lent to dibs" else title, style = AppType.label, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = AppType.small, color = if (t.lent) Palette.Text else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NavBar(tab: Tab, badges: Badges, onTab: (Tab) -> Unit) {
    NavigationBar(containerColor = Palette.SurfaceLow, tonalElevation = 0.dp) {
        for (t in Tab.entries) {
            val count = when (t) {
                Tab.WAITING -> badges.waiting
                Tab.WORK -> badges.work
                else -> 0
            }
            val dot = t == Tab.RECAP && badges.recap > 0
            NavigationBarItem(
                selected = t == tab,
                onClick = { onTab(t) },
                icon = {
                    BadgedBox(badge = {
                        when {
                            count > 0 -> Badge(containerColor = Palette.Accent, contentColor = Palette.OnAccent) {
                                Text("$count", style = AppType.mono)
                            }
                            dot -> Badge(containerColor = Palette.Accent)
                        }
                    }) { Icon(painterResource(t.icon), null, Modifier.size(22.dp)) }
                },
                label = { Text(t.label, style = AppType.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.Accent,
                    selectedTextColor = Palette.Text,
                    indicatorColor = Palette.AccentDim,
                    unselectedIconColor = Palette.Muted,
                    unselectedTextColor = Palette.Muted,
                ),
            )
        }
    }
}

/** Before dibs's first view, or a view from a dibs too old to fill these screens. */
@Composable
private fun Empty(hasView: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.XL),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (hasView) "dibs needs an update to fill these screens" else "Waiting for dibs…", style = AppType.heading, textAlign = TextAlign.Center)
        if (hasView) {
            Text(
                "Its questions and chat are in Tether meanwhile.",
                style = AppType.body,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.S),
            )
            Spacer(Modifier.height(Space.L))
            ActButton("Open in Tether", "primary") { Dibs.host.openTether(classic = true) }
        } else {
            Text(
                "Its chat, questions and work show here once the laptop sends them.",
                style = AppType.body,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.S),
            )
        }
    }
}
