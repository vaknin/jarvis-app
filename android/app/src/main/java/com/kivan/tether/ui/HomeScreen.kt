package com.kivan.tether.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kivan.tether.Brief
import com.kivan.tether.BuildConfig
import com.kivan.tether.CallActivity
import com.kivan.tether.Forum
import com.kivan.tether.LaptopActivity
import com.kivan.tether.MediaKeys
import com.kivan.tether.R
import com.kivan.tether.TasksActivity
import com.kivan.tether.Updater
import com.kivan.tether.Voice
import com.kivan.tether.forum.ForumActivity
import com.kivan.tether.forum.Watch
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)
private val Go = Color(0xFF3FB97A)

/** A line of What's new, from Jarvis's brief or the forum's own unread list. */
private class Line(val title: String, val text: String, val tag: String?, val time: Long, val open: (android.content.Context) -> Unit)

/**
 * Home, voice first: the big Call button, and What's new above it. The tasks, the forum and the laptop chat
 * are one tap away in the top bar; the ⋮ has the tunnel's state, the update check
 * and the double-press switch.
 */
@Composable
fun HomeScreen() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        val ctx = LocalContext.current
        val ui by Voice.ui.collectAsState()
        val waiting by Watch.waiting.collectAsState()
        val watching by Watch.status.collectAsState()
        val update by Updater.state.collectAsState()
        var brief by remember { mutableStateOf<List<Brief.Item>?>(null) }
        var menu by remember { mutableStateOf(false) }
        var doublePress by remember { mutableStateOf(MediaKeys.enabled(ctx)) }
        // While home is on screen, Jarvis's brief every minute; until it answers, the forum's own unread list.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    brief = Brief.get()
                    delay(60_000)
                }
            }
        }
        val b = brief
        val lines = b?.map { i ->
            Line(i.title, i.text, if (i.kind == "needs-you") "NEEDS YOU" else null, 0L) { c ->
                when (i.topic) {
                    null -> Forum.open(c)
                    0L -> c.startActivity(Forum.view(c, "/chat"))
                    else -> c.startActivity(ForumActivity.go(c, "t", i.topic))
                }
            }
        } ?: waiting.map { w ->
            Line(w.title, w.text, if (w.needsYou) "NEEDS YOU" else null, w.time) { c -> c.startActivity(ForumActivity.go(c, w.kind, w.id)) }
        }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Jarvis", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { ctx.startActivity(TasksActivity.intent(ctx)) }) {
                        Icon(painterResource(R.drawable.lucide_list_checks), "Tasks", tint = Accent)
                    }
                    IconButton(onClick = { Forum.open(ctx) }) {
                        Icon(painterResource(R.drawable.lucide_messages_square), "Forum", tint = Accent)
                    }
                    IconButton(onClick = { ctx.startActivity(android.content.Intent(ctx, LaptopActivity::class.java)) }) {
                        Icon(painterResource(R.drawable.lucide_laptop), "Laptop", tint = Accent)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Forum tunnel: ${Forum.status()}") }, onClick = { menu = false })
                            DropdownMenuItem(
                                text = { Text(update.ifEmpty { "Check for update (${BuildConfig.VERSION_NAME})" }) },
                                onClick = { menu = false; Updater.checkNow(ctx) },
                            )
                            // The AirPods' stem double-press ("next track") calls Jarvis.
                            DropdownMenuItem(
                                text = { Text("Double-press to call Jarvis") },
                                trailingIcon = { Switch(checked = doublePress, onCheckedChange = null) },
                                onClick = { doublePress = !doublePress; MediaKeys.setEnabled(ctx, doublePress) },
                            )
                        }
                    }
                }
                MoveBanner()
                Text(
                    "WHAT'S NEW · " + if (b != null) "FROM JARVIS" else "UNREAD ON THE FORUM",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = Muted, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (lines.isEmpty()) item {
                        // Nothing waiting, or the forum session isn't there yet (Watch says what it waits for).
                        Text(
                            if (b == null && watching.isNotEmpty() && watching != "Notifications on") watching else "Nothing new",
                            Modifier.fillMaxWidth().padding(top = 32.dp), color = Muted, textAlign = TextAlign.Center,
                        )
                    }
                    items(lines) { l -> Card(l) }
                }
                Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 40.dp), contentAlignment = Alignment.Center) {
                    Round(R.drawable.lucide_phone, if (ui.live) Voice.label(ui) else "Call Jarvis", Go, Color.White, 144.dp, {
                        ctx.startActivity(CallActivity.intent(ctx, start = true))
                    })
                }
            }
        }
    }
}

@Composable
private fun Card(l: Line) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Raised)
            .clickable { l.open(ctx) }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (l.tag != null || l.time > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                l.tag?.let {
                    Text(it, color = Accent, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp))
                    Spacer(Modifier.padding(start = 8.dp))
                }
                if (l.time > 0) {
                    Text(
                        DateUtils.getRelativeTimeSpanString(l.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        color = Muted, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Text(l.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (l.text.isNotEmpty()) {
            Text(l.text, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
