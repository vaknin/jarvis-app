package com.kivan.tether

import android.content.Context
import android.util.Log
import com.kivan.tether.core.AlertListener
import com.kivan.tether.core.alertsStart

/**
 * Jarvis's notifications for Aviv (`jarvis notify`): the forum gate dials the phone's tunnel endpoint
 * with each one (crates/ffi alerts.rs), and this shows it with [Notifier.jarvis]. Saying true tells the
 * gate it's on screen; otherwise the sender falls back to the forum's Chrome push, so none shows twice.
 * Started by [LinkService], after the forum tunnel. Battery: the tunnel's endpoint stays up for this
 * (iroh's relay ping every 15 s); nothing else runs between notifications.
 */
class Alerts private constructor(private val context: Context) : AlertListener {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    @Synchronized
    override fun show(id: ULong, title: String, text: String, url: String): Boolean {
        val n = id.toLong()
        val recent = prefs.getString(RECENT, "")!!.split(',').filter { it.isNotEmpty() }
        // The same one again (it came twice): it's already on screen.
        if (n.toString() in recent) return true
        val shown = when {
            url.startsWith(CALLBACK) -> callBack(url.removePrefix(CALLBACK), title, text)
            // Jarvis calls him (reach, thread #36): a regular incoming call from "Jarvis".
            url.startsWith(RING) -> Voice.ui.value.live || Callback.ring(context, null, "Jarvis", text.ifEmpty { "Incoming call" }, reach = url.removePrefix(RING))
            // Jarvis needs him (reach's "wants to talk"): it rings, if the phone may.
            url == "/call" -> Voice.ui.value.live || Callback.ring(context, null, title, text)
            // A tracked task landed or needs him: quiet, and the call is his to place.
            url.startsWith(TASK) -> Notifier.task(context, n, title, text, url.removePrefix(TASK))
            else -> Notifier.jarvis(context, n, title, text, url)
        }
        if (shown) prefs.edit().putString(RECENT, (recent + n.toString()).takeLast(KEEP).joinToString(",")).apply()
        Log.i("Tether", "jarvis alert $n ${if (shown) "shown" else "not shown"}")
        return shown
    }

    /**
     * The server, its call `id` dropped: the app redialing it already says it has it; one Aviv hung up
     * whose hangup never got through sends it now; else Jarvis rings.
     */
    private fun callBack(id: String, title: String, text: String): Boolean = when {
        Voice.ui.value.live -> true
        id in CallStore.owed(context) -> { CallStore.settle(context); true }
        else -> Callback.ring(context, id, title, text)
    }

    companion object {
        private const val CALLBACK = "/callback/"
        private const val RING = "/ring/"
        private const val TASK = "/task/"
        private const val RECENT = "shown"
        private const val KEEP = 30

        /** Tests: an alert as if the gate brought it. */
        fun test(context: Context, title: String, text: String, url: String) =
            Alerts(context.applicationContext).show(System.nanoTime().toULong(), title, text, url)

        fun start(context: Context) {
            runCatching { alertsStart(Alerts(context.applicationContext)) }
                .onFailure { Log.w("Tether", "alerts not started", it) }
        }
    }
}
