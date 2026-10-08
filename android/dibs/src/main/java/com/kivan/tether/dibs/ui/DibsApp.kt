package com.kivan.tether.dibs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Badges
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Lend
import com.kivan.tether.dibs.LendToggle
import com.kivan.tether.dibs.Lends
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.State
import com.kivan.tether.dibs.stateWords
import com.kivan.tether.dibs.Limit
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.limitWords
import com.kivan.tether.dibs.wantsYou
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
 * The five tabs, by the key an intent names them with. The fourth is Tasks (the user's tasks), or
 * Work for a dibs that doesn't send them yet.
 */
internal enum class Tab(val key: String, val label: String, val icon: Int) {
    CHAT("chat", "Chat", R.drawable.lucide_message_circle),
    IDEAS("ideas", "Ideas", R.drawable.lucide_lightbulb),
    WAITING("waiting", "Waiting", R.drawable.lucide_inbox),
    TASKS("tasks", "Tasks", R.drawable.lucide_list_checks),
    RECAP("recap", "Recap", R.drawable.lucide_history),
}

/** dibs's screen: the slim bar, a tab, and the tabs below. The lend switches and usage live on the dibs page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DibsApp() {
    val host = Dibs.host
    val json by host.view.collectAsStateWithLifecycle()
    val link by host.link.collectAsStateWithLifecycle()
    val view = remember(json) { runCatching { DibsView.ofView(json) }.getOrNull() }
    val context = LocalContext.current
    LaunchedEffect(view) {
        Dibs.seen(view)
        Drafts.seen(context, view?.ideas)
        // A conversation's notification tapped before the views were loaded opens once one lists it.
        Dibs.resolveAsk(view)
    }

    // The taps' clock counts from when the link came up (a tap sent while it is down waits).
    LaunchedEffect(link) { Dibs.upSince = if (link == Link.CONNECTED) Dibs.now() else null }

    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val tabs = rememberSaveableStateHolder()
    val asked = Dibs.tab
    LaunchedEffect(asked) {
        if (asked == null) return@LaunchedEffect
        (if (asked == "work") Tab.TASKS else Tab.entries.firstOrNull { it.key == asked })?.let { tab = it }
        Dibs.tab = null
    }
    // The keyboard needs the room; the tabs come back when it closes.
    val typing = WindowInsets.isImeVisible

    // A task's page (and its transcript, report or full story) over the tabs; back pops it.
    val page = Dibs.pages.lastOrNull()
    BackHandler(enabled = page != null) { Dibs.back() }
    if (page != null && view != null) {
        Column(
            Modifier.fillMaxSize().background(Palette.Bg)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Each page its own state (its scroll, its taps), also when one replaces another of its kind.
                key(page) {
                    when (page) {
                        is Page.Task -> TaskPage(page.id, view)
                        is Page.Transcript -> TranscriptScreen(page.id, view)
                        is Page.Report -> ReportScreen(page.id, view)
                        is Page.Story -> StoryScreen(page.id, view)
                        is Page.Idea -> IdeaPage(page.id, view)
                        is Page.Ask -> AskPage(page.about, view)
                        is Page.Root -> RootPage(page.id, view)
                        Page.RootKey -> RootKeyPage()
                        Page.Status -> StatusPage(view, link)
                    }
                }
            }
            TapLine()
        }
        return
    }

    Scaffold(
        containerColor = Palette.Bg,
        contentColor = Palette.Text,
        // The header (in the column, not the Scaffold's topBar), the tab bar and imePadding pad for
        // the bars and the keyboard themselves; the Scaffold's topBar slot left a status bar's
        // height of gap under the header, room the chat needs with the keyboard open.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (view != null && !typing) {
                // The Tasks badge counts what the tab says it does, and drops as soon as one is opened here.
                val forYou = view.yours?.count { wantsYou(it, Dibs.ticked(it), Dibs.unread(it)) } ?: view.badges.work
                NavBar(tab, view.badges, forYou, view.yours != null, view.ideas?.total ?: 0) {
                    tab = it
                    // Gone elsewhere: a notification's conversation not listed yet no longer opens.
                    Dibs.forgetAsk()
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                // Sideways (landscape: a cutout or a side navigation bar), then the keyboard.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding(),
        ) {
            Header(link, view?.state, view?.lends, view?.lend)
            if (view == null) {
                Empty(hasView = json != null)
                return@Column
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The other tabs keep their place (scroll, folds) under a page and across tab
                // changes; the chat opens at its newest line, as always.
                if (tab == Tab.CHAT) {
                    ChatTab(view)
                } else {
                    tabs.SaveableStateProvider(tab.key) {
                        when (tab) {
                            Tab.IDEAS -> IdeasTab(view)
                            Tab.WAITING -> WaitingTab(view)
                            Tab.TASKS -> if (view.yours != null) TasksTab(view) else WorkTab(view)
                            else -> RecapTab(view)
                        }
                    }
                }
            }
            TapLine()
        }
    }
}

/**
 * The slim bar, pinned over every tab (about 52 dp): the mark, "dibs" with its state under it, marks
 * that show only when they matter (the phone or laptop lent to dibs, a usage window past its warning),
 * and ⋮. A tap on the mark, name or marks opens the dibs page; nothing hides on scroll or typing.
 */
@Composable
private fun Header(link: Link, state: State?, lends: Lends?, oldLend: Lend?) {
    var menu by remember { mutableStateOf(false) }
    val words = stateWords(link, state)
    // Red only with words: the link is lost. Muted while it can't do anything for you.
    val color = when {
        link == Link.UNPAIRED -> Palette.Danger
        link == Link.OFFLINE || state == null -> Palette.Muted
        state.doing == "out" -> Palette.Warning
        state.doing == null && !state.busy && !state.usage.isNullOrBlank() -> Palette.Warning
        else -> Palette.Accent
    }
    val ctx = LocalContext.current
    val now by rememberNow()
    val warn = remember(state?.limits, now / 60) { usageWarning(state?.limits.orEmpty(), now, ctx) }
    val phone = lends?.phone?.lent == true || (lends?.phone == null && oldLend != null)
    val laptop = lends?.laptop?.lent == true
    Column(Modifier.fillMaxWidth().background(Palette.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.L, end = Space.XS, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button, onClickLabel = "Open the dibs page") { Dibs.open(Page.Status) }
                    .semantics {
                        // One reading for the whole button: its state and marks, since a description replaces the texts.
                        contentDescription = listOfNotNull(
                            "dibs, laptop and phone", words,
                            "phone lent to dibs".takeIf { phone }, "laptop lent to dibs".takeIf { laptop },
                            warn?.let { "Claude usage $it" },
                        ).joinToString(", ")
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.Tile), contentAlignment = Alignment.Center) {
                    // The launcher's monochrome layer: its mark fills 46 of 108 dp, so draw it larger than the tile.
                    Icon(painterResource(R.drawable.ic_dibs_monochrome), null, Modifier.requiredSize(42.dp), tint = Palette.Text)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("dibs", style = AppType.heading, color = Palette.Text)
                    Text(words, style = AppType.small, color = color)
                }
                if (phone) LentMark(R.drawable.lucide_smartphone, "Phone lent to dibs")
                if (laptop) LentMark(R.drawable.lucide_laptop, "Laptop lent to dibs")
                if (warn != null) {
                    Text(
                        warn.replace(' ', NBSP),
                        Modifier.padding(start = 6.dp).semantics { contentDescription = "Claude usage $warn" },
                        style = AppType.mono, color = Palette.Warning,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More", tint = Palette.Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Laptop and phone") },
                        leadingIcon = { Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(18.dp)) },
                        onClick = {
                            menu = false
                            Dibs.open(Page.Status)
                        },
                    )
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
        // dibs's brain is on but down: one tap starts it (your start: no limit holds it back).
        if (link == Link.CONNECTED && state?.brainDown == true) {
            Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.L, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                StartDibs()
            }
        }
    }
}

/** Start dibs: its brain is on but down; the button spins until dibs has heard the tap. */
@Composable
internal fun StartDibs() {
    val busy = Dibs.busy("brain-start")
    ActButton(if (busy) "Starting" else "Start dibs", "primary", busy = busy) {
        Dibs.tap("brain-start", "Starting dibs", "start dibs", "brain-start")
    }
}

/** A filled accent pill with an icon: lent to dibs, readable without colour (the fill and the icon say it). */
@Composable
private fun LentMark(icon: Int, description: String) {
    Box(
        Modifier.padding(start = 6.dp).size(28.dp).background(Palette.AccentDim, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), null, Modifier.size(16.dp), tint = Palette.Accent) }
}

/** The first usage window past its warning as "5h 88%" (no reset time), or null when none is. */
internal fun usageWarning(limits: List<Limit>, now: Long, ctx: android.content.Context): String? =
    limitWords(
        limits, now,
        clock = { android.text.format.DateFormat.getTimeFormat(ctx).format(Date(it * 1000)) },
        day = { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
    ).firstOrNull { it.warn }?.text?.substringBefore(" · ")

@Composable
private fun NavBar(tab: Tab, badges: Badges, tasks: Int, yours: Boolean, ideas: Int, onTab: (Tab) -> Unit) {
    NavigationBar(containerColor = Palette.SurfaceLow, tonalElevation = 0.dp) {
        for (t in Tab.entries) {
            val count = when (t) {
                Tab.WAITING -> badges.waiting
                Tab.TASKS -> tasks
                Tab.IDEAS -> ideas
                else -> 0
            }
            // An older dibs: the old Work tab.
            val (label, icon) = if (t == Tab.TASKS && !yours) "Work" to R.drawable.lucide_hammer else t.label to t.icon
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
                    }) { Icon(painterResource(icon), null, Modifier.size(22.dp)) }
                },
                label = { Text(label, style = AppType.label) },
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
        Text(if (hasView) "dibs needs an update to fill these screens" else "Waiting for dibs", style = AppType.heading, textAlign = TextAlign.Center)
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
