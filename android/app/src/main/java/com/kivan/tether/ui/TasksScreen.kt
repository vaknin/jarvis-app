package com.kivan.tether.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kivan.tether.CallActivity
import com.kivan.tether.R
import com.kivan.tether.Tasks
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)
private val Go = Color(0xFF3FB97A)
private val Building = Color(0xFFE5B567)

private fun label(done: Boolean) = if (done) "Done" else "Being built"

@Composable
private fun Dot(done: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (done) Go else Building))
}

/**
 * What Aviv named: each task with a build/done dot. A tap reads it, and calling about it is its own
 * button, here and on the task. [open]: the task on screen, null for the list.
 */
@Composable
fun TasksScreen(open: MutableState<String?>, onClose: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            val slug = open.value
            if (slug == null) TaskList(onRead = { open.value = it }) else Read(slug, onBack = { open.value = null })
        }
    }
}

private fun call(ctx: android.content.Context, slug: String) = ctx.startActivity(CallActivity.intent(ctx, start = true, task = slug))

@Composable
private fun TaskList(onRead: (String) -> Unit) {
    val ctx = LocalContext.current
    var tasks by remember { mutableStateOf<List<Tasks.Task>?>(null) }
    var tried by remember { mutableStateOf(false) }
    // While it's on screen, a minute at a time, as home reads its brief.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                Tasks.list().let { if (it != null) tasks = it }
                tried = true
                delay(60_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text("Tasks", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall)
        val list = tasks
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list.isNullOrEmpty()) item {
                Text(
                    when {
                        list != null -> "Nothing tracked. Give a task a name to see it here."
                        tried -> "Can't reach Jarvis right now"
                        else -> ""
                    },
                    Modifier.fillMaxWidth().padding(top = 32.dp), color = Muted, textAlign = TextAlign.Center,
                )
            }
            items(list.orEmpty(), key = { it.slug }) { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Raised)
                        .clickable(onClickLabel = "Read ${t.name}") { onRead(t.slug) }.padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(t.done)
                    Spacer(Modifier.width(12.dp))
                    Text(t.name, Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { call(ctx, t.slug) }) {
                        Icon(painterResource(R.drawable.lucide_phone), "Call Jarvis about ${t.name}", tint = Go)
                    }
                }
            }
        }
    }
}

@Composable
private fun Read(slug: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var task by remember(slug) { mutableStateOf<Tasks.Detail?>(null) }
    var tried by remember(slug) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(slug) {
        task = Tasks.read(slug)
        tried = true
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val t = task
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (t == null) {
                Text(if (tried) "Can't read this task right now" else "", Modifier.fillMaxWidth().padding(top = 32.dp), color = Muted, textAlign = TextAlign.Center)
            } else {
                Text(t.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(t.done)
                    Spacer(Modifier.width(8.dp))
                    Text(label(t.done), color = Muted, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.size(16.dp))
                Text(t.text.replace(Regex("(?m)^## "), ""), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (t != null) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp), contentAlignment = Alignment.Center) {
                Round(R.drawable.lucide_phone, "Call Jarvis", Go, Color.White, 72.dp, { call(ctx, t.slug) })
            }
        }
    }
}
