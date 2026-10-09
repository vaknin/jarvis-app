package com.kivan.tether

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.kivan.tether.dibs.DibsActivity
import com.kivan.tether.dibs.WithBrowserLinks
import com.kivan.tether.ui.TetherScreen
import com.kivan.tether.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dibs's channel has its own screen: an intent for it goes there, and this one was only the way.
        if (savedInstanceState == null && route(intent)) return finish()
        // Always dark (the design language has no light theme), so the bar icons stay light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { WithBrowserLinks { AppTheme { TetherScreen() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /**
     * A notification, shortcut or share names the screen; a plain launch reopens the last one. The
     * dibs channel opens dibs's own screen unless the intent asks for this one ([CLASSIC]); returns
     * true when it went there.
     */
    private fun route(intent: Intent): Boolean {
        val uri = intent.data?.takeIf { it.scheme == SCHEME } ?: return false
        val name = if (uri.host == CHAT) Channels.CHAT else uri.lastPathSegment ?: return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (name == Channels.DIBS && !intent.getBooleanExtra(CLASSIC, false)) {
            startActivity(DibsActivity.intent(this).apply { text?.let { putExtra(Intent.EXTRA_TEXT, it) } })
            return true
        }
        text?.let { Channels.shared[name] = it }
        Channels.show(name)
        return false
    }

    // On screen: keep the link open (the core's StayConnected) and check in with the laptop.
    override fun onStart() {
        super.onStart()
        Channels.foreground(true)
        lifecycleScope.launch {
            val node = Core.acquire(Core.UI)
            if (node.status().peer != null) {
                Core.connect()
                if (Channels.showing(Channels.CHAT)) runCatching { node.markRead() }
            }
        }
    }

    override fun onStop() {
        Channels.foreground(false)
        Core.release(Core.UI)
        super.onStop()
    }

    companion object {
        private const val SCHEME = "tether"
        private const val CHAT = "chat"

        /** Extra: open Tether's own screen of the dibs channel, not dibs's app (a dibs without its payload). */
        const val CLASSIC = "com.kivan.tether.CLASSIC"

        /** Opens the app on a channel, or the chat for [Channels.CHAT]; dibs's channel opens dibs's own screen. */
        fun open(context: Context, name: String): Intent =
            if (name == Channels.DIBS) DibsActivity.intent(context) else here(context, name)

        /** Tether's own screen of a channel, dibs's included. */
        fun classic(context: Context, name: String): Intent = here(context, name).putExtra(CLASSIC, true)

        private fun here(context: Context, name: String): Intent {
            val uri = if (name == Channels.CHAT) Uri.parse("$SCHEME://$CHAT") else Uri.parse("$SCHEME://channel/$name")
            return Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
        }
    }
}
