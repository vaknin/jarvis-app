package com.kivan.jarvis.ui

import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import com.kivan.jarvis.JarvisTile
import com.kivan.jarvis.Move
import com.kivan.jarvis.R

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Muted = Color(0xFF8B93A1)
private val Raised = Color(0xFF1C1F24)

@Composable
private fun Page(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        Surface(Modifier.fillMaxSize(), color = Bg) { content() }
    }
}

/** Until the move is done: what's happening, and the one tap, if any, that it needs. */
@Composable
fun MoveInScreen(allowed: Boolean, allow: () -> Unit) {
    val ctx = LocalContext.current
    val step by Move.step.collectAsState()
    val failed by Move.failed.collectAsState()
    Page {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Moving Jarvis here", style = MaterialTheme.typography.headlineSmall)
            when {
                failed.isNotEmpty() -> {
                    Text("It didn't work, and nothing is lost. The old app kept working.", color = Muted, textAlign = TextAlign.Center)
                    Text(failed, color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { Move.start(ctx) }) { Text("Try again") }
                }
                step.isNotEmpty() -> {
                    CircularProgressIndicator(color = Accent)
                    Text(step, color = Muted, textAlign = TextAlign.Center)
                    Text("The old app keeps working until this one is ready.", color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                !Move.ready() -> Text("Update the old Jarvis app first, then come back here.", color = Muted, textAlign = TextAlign.Center)
                !allowed -> {
                    Text("Calls need the microphone and notifications. Allow both, then everything comes over by itself.", color = Muted, textAlign = TextAlign.Center)
                    Button(onClick = allow) { Text("Allow") }
                }
                Move.yielded() -> {
                    Text("Jarvis is on the old app now, as you asked.", color = Muted, textAlign = TextAlign.Center)
                    Button(onClick = { Move.start(ctx) }) { Text("Move here again") }
                }
                else -> CircularProgressIndicator(color = Accent)
            }
        }
    }
}

private class Setup(val title: String, val line: String, val done: (Context) -> Boolean, val go: (Activity) -> Unit)

private fun settings(a: Activity, action: String, data: String? = null) {
    val i = Intent(action)
    if (data != null) i.data = data.toUri()
    runCatching { a.startActivity(i) }
}

private fun pkg(a: Context) = "package:${a.packageName}"

private fun addTile(a: Activity) {
    if (Build.VERSION.SDK_INT < 33) return
    a.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(a, JarvisTile::class.java), "Jarvis", Icon.createWithResource(a, R.drawable.lucide_phone), a.mainExecutor,
    ) { result ->
        if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
            a.getSharedPreferences("move", Context.MODE_PRIVATE).edit().putBoolean("tile", true).apply()
            Move.resumed()
        }
    }
}

private fun addShortcut(a: Activity) {
    val call = ShortcutManagerCompat.getShortcuts(a, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).firstOrNull { it.id == "call" } ?: return
    if (ShortcutManagerCompat.requestPinShortcut(a, call, null)) {
        a.getSharedPreferences("move", Context.MODE_PRIVATE).edit().putBoolean("shortcut", true).apply()
        Move.resumed()
    }
}

private fun flag(name: String): (Context) -> Boolean =
    { it.getSharedPreferences("move", Context.MODE_PRIVATE).getBoolean(name, false) }

/** What Android won't let move, each one tap, in the order that matters for calls. */
private val setups = listOf(
    Setup("Digital assistant", "Hold the power button to call Jarvis. Pick Jarvis on the next screen.", { c ->
        c.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }, { settings(it, Settings.ACTION_VOICE_INPUT_SETTINGS) }),
    Setup("Stay on in the background", "So calls and notifications always reach you.", { c ->
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    }, { settings(it, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(it)) }),
    Setup("Updates", "So Jarvis can install its own updates.", { c ->
        c.packageManager.canRequestPackageInstalls()
    }, { settings(it, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg(it)) }),
    Setup("Full-screen calls", "So a call from Jarvis rings over the lock screen.", { c ->
        Build.VERSION.SDK_INT < 34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }, { settings(it, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(it)) }),
    Setup("Quick Settings tile", "The Jarvis tile, one swipe from anywhere.", flag("tile"), ::addTile),
    Setup("Home screen shortcut", "One tap to call Jarvis.", flag("shortcut"), ::addShortcut),
)

/** Is something from the guide still to do? Home's ⋮ offers it until nothing is. */
fun setupLeft(context: Context) = setups.any { !it.done(context) } || Move.installed()

/** After the move: what couldn't come over, one tap each, and at the end the old app, whose removal is Aviv's tap. */
@Composable
fun GuideScreen() {
    val ctx = LocalContext.current
    val a = ctx as Activity
    val tick by Move.tick.collectAsState()
    Page {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Everything's here", style = MaterialTheme.typography.headlineSmall)
            val n = Move.messages()
            Text(
                (if (n >= 0) "Your $n messages, your calls, your login. " else "") + "Android keeps these with the old app, so each needs a tap here.",
                color = Muted,
            )
            setups.forEach { s ->
                val done = remember(tick) { s.done(ctx) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Raised)
                        .clickable(enabled = !done) { s.go(a) }.padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, Modifier.weight(1f), color = if (done) Muted else Accent, style = MaterialTheme.typography.titleSmall)
                        if (done) Text("Done", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!done) Text(s.line, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (remember(tick) { Move.installed() }) {
                TextButton(onClick = { settings(a, Intent.ACTION_DELETE, "package:${Move.OLD_APP}") }) { Text("Remove the old app") }
                Text("Only when you've checked this one. It's safe to wait.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { Move.guided() }, Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
