package com.kivan.tether.dibs

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dibs's own app (docs/DIBS-APP.md): its own launcher icon and Recents card. While it is on screen
 * the host keeps the link up and dibs's notifications clear. An intent may name a tab
 * ([EXTRA_TAB]) and carry text and files shared from another app.
 */
class DibsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (the design language has no light theme), so the bar icons stay light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) take(intent)
        setContent { WithBrowserLinks { AppTheme { DibsApp() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onStart() {
        super.onStart()
        Dibs.host.visible(true)
    }

    override fun onStop() {
        Dibs.typing.stopped()
        Dibs.forgetAsk()
        Dibs.host.visible(false)
        super.onStop()
    }

    // A tab or a task's page to show, and text or files shared to dibs (they wait in the box until sent).
    private fun take(intent: Intent) {
        // Reopened from Recents (after the process went): the intent that first opened it, already
        // taken; its page or share again would be stale.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        Dibs.forgetAsk()
        intent.getStringExtra(EXTRA_TAB)?.let {
            Dibs.tab = it
            Dibs.pages.clear()
        }
        // A conversation's notification (`ask:<id>`): its page, by the subject the notification
        // carried; else by the thread's id, in the view now or once one lists it (a cold start).
        val ask = intent.getLongExtra(EXTRA_ASK, -1)
        if (ask >= 0) {
            Dibs.openAsk(ask, Dibs.askSubjectKey(intent.getStringExtra(EXTRA_ASK_ABOUT)), runCatching { DibsView.ofView(Dibs.host.view.value) }.getOrNull())
        }
        val task = intent.getLongExtra(EXTRA_TASK, -1)
        if (task >= 0) {
            Dibs.tab = TAB_TASKS
            Dibs.pages.clear()
            Dibs.open(Page.Task(task))
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
            Dibs.pages.clear()
            Dibs.chat.draft = listOf(Dibs.chat.draft, text).filter { it.isNotBlank() }.joinToString("\n")
            Dibs.tab = TAB_CHAT
        }
        // Other apps' content URIs only (see ShareActivity): never a file: URI or one of ours.
        val uris = (IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)))
            .filter { it.scheme == "content" && it.authority?.startsWith(packageName) != true }
        if (uris.isEmpty()) return
        Dibs.tab = TAB_CHAT
        Dibs.pages.clear()
        lifecycleScope.launch {
            val box = Dibs.chat
            val picked = withContext(Dispatchers.IO) { uris.filter { u -> box.picked.none { it.source == u } }.mapNotNull { picked(this@DibsActivity, it) } }
            box.picked += picked
        }
    }

    companion object {
        /** "chat" | "ideas" | "waiting" | "tasks" (or "work") | "recap". */
        const val EXTRA_TAB = "com.kivan.tether.dibs.TAB"
        /** One of the user's tasks (a Long): its page opens over the Tasks tab. */
        const val EXTRA_TASK = "com.kivan.tether.dibs.TASK"
        /** An Ask about conversation, by its thread id (a Long): its page opens over the chat. */
        const val EXTRA_ASK = "com.kivan.tether.dibs.ASK"
        /** With [EXTRA_ASK]: its subject (`about`, a String), when the notification knew it. */
        const val EXTRA_ASK_ABOUT = "com.kivan.tether.dibs.ASK_ABOUT"
        const val TAB_CHAT = "chat"
        const val TAB_IDEAS = "ideas"
        const val TAB_WAITING = "waiting"
        const val TAB_TASKS = "tasks"
        const val TAB_RECAP = "recap"

        /** The morning recap's notification tag: a tap opens the Recap tab. */
        const val RECAP_TAG = "recap"

        /** The tab a dibs notification (by its tag) opens: Recap for the morning recap, else the chat. */
        fun tabFor(tag: String?): String = if (tag == RECAP_TAG) TAB_RECAP else TAB_CHAT

        /** Opens dibs, on [tab] if given. */
        fun intent(context: Context, tab: String? = null): Intent =
            Intent(Intent.ACTION_VIEW, null, context, DibsActivity::class.java).apply {
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }

        /** Opens dibs on an Ask about conversation, by its thread id and, when known, its [about]. */
        fun ask(context: Context, id: Long, about: String?): Intent =
            Intent(Intent.ACTION_VIEW, null, context, DibsActivity::class.java).putExtra(EXTRA_ASK, id)
                .apply { if (about != null) putExtra(EXTRA_ASK_ABOUT, about) }

        /** An Ask about notification's thread id, from its tag (`ask:<id>`); null for any other tag. */
        fun askId(tag: String?): Long? =
            tag?.takeIf { it.startsWith("ask:") }?.removePrefix("ask:")?.toLongOrNull()?.takeIf { it >= 0 }

        /** Opens dibs on one of the user's tasks. */
        fun task(context: Context, id: Long): Intent =
            Intent(Intent.ACTION_VIEW, null, context, DibsActivity::class.java).putExtra(EXTRA_TASK, id)
    }
}

/**
 * A file to send, copied into [DibsHost.pickDir] now, while the grant to read it holds: its
 * display name, and whether it's an image (by its type). Null when it can't be read. Blocking.
 */
internal fun picked(context: Context, uri: Uri): Picked? = runCatching {
    val r = context.contentResolver
    val name = runCatching {
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
    val image = runCatching { r.getType(uri) }.getOrNull()?.startsWith("image/") == true
    val dir = java.io.File(Dibs.host.pickDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
    val copy = java.io.File(dir, name.replace('/', '_'))
    r.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
    Picked(Uri.fromFile(copy), name, image, source = uri)
}.onFailure { android.util.Log.w("dibs", "couldn't read $uri", it) }.getOrNull()
