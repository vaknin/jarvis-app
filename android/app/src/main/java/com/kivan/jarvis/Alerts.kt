package com.kivan.jarvis

import android.content.Context
import android.util.Log
import com.kivan.jarvis.core.AlertListener
import com.kivan.jarvis.core.alertsStart

/**
 * Jarvis's notifications for Aviv (`jarvis notify`): the forum gate dials the phone's tunnel endpoint
 * with each one (crates/ffi alerts.rs), and this shows it with [Notifier.jarvis]. Saying nothing back
 * tells the gate it's on screen; a reason sends the sender to its other route, with that reason, so
 * none shows twice and a setting that keeps these off gets named. Started with the process
 * ([JarvisApp]), so no tunnel is ever up without it. Battery: the tunnel's endpoint stays up for this
 * (iroh's relay ping every 15 s); nothing else runs between notifications.
 */
class Alerts private constructor(private val context: Context) : AlertListener {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    /** Null once it's on screen, else why not. Nothing thrown gets past here: the gate hears it. */
    @Synchronized
    override fun show(id: ULong, title: String, text: String, url: String): String? {
        val n = id.toLong()
        val why = try {
            display(n, title, text, url)
        } catch (t: Throwable) {
            Log.w("Jarvis", "jarvis alert $n threw", t)
            threw(t)
        }
        Log.i("Jarvis", "jarvis alert $n ${why?.let { "not shown: $it" } ?: "shown"}")
        return why
    }

    private fun display(n: Long, title: String, text: String, url: String): String? {
        val recent = prefs.getString(RECENT, "")!!.split(',').filter { it.isNotEmpty() }
        // The same one again (it came twice): it's already on screen.
        if (n.toString() in recent) return null
        val why = when {
            url.startsWith(CALLBACK) -> callBack(url.removePrefix(CALLBACK), title, text)
            // Jarvis calls him (reach, thread #36): a regular incoming call from "Jarvis".
            url.startsWith(RING) -> Callback.ring(context, null, "Jarvis", text.ifEmpty { "Incoming call" }, reach = url.removePrefix(RING))
            // Jarvis needs him (reach's "wants to talk"): it rings, if the phone may.
            url == "/call" -> Callback.ring(context, null, title, text)
            // A tracked task landed or needs him: quiet, and the call is his to place.
            url.startsWith(TASK) -> Notifier.task(context, n, title, text, url.removePrefix(TASK))
            else -> Notifier.jarvis(context, n, title, text, url)
        }
        if (why == null) prefs.edit().putString(RECENT, (recent + n.toString()).takeLast(KEEP).joinToString(",")).apply()
        return why
    }

    /**
     * The server, its call `id` dropped: the app redialing it already says it has it; one Aviv hung up
     * whose hangup never got through sends it now; else Jarvis rings.
     */
    private fun callBack(id: String, title: String, text: String): String? = when {
        Voice.ui.value.live -> null
        id in CallStore.owed(context) -> { CallStore.settle(context); null }
        else -> Callback.ring(context, id, title, text)
    }

    companion object {
        private const val CALLBACK = "/callback/"
        private const val RING = "/ring/"
        private const val TASK = "/task/"
        private const val RECENT = "shown"
        private const val KEEP = 30

        /** The reason for one that threw: short, it's one line on the wire. */
        fun threw(t: Throwable) = "threw ${t.javaClass.simpleName}: ${t.message.orEmpty()}".trim().removeSuffix(":")

        /** Tests: an alert as if the gate brought it. */
        fun test(context: Context, title: String, text: String, url: String) =
            Alerts(context.applicationContext).show(System.nanoTime().toULong(), title, text, url)

        fun start(context: Context) {
            runCatching { alertsStart(Alerts(context.applicationContext)) }
                .onFailure { Log.w("Jarvis", "alerts not started", it) }
        }
    }
}
