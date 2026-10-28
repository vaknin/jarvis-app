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
        val shown = Notifier.jarvis(context, n, title, text, url)
        if (shown) prefs.edit().putString(RECENT, (recent + n.toString()).takeLast(KEEP).joinToString(",")).apply()
        Log.i("Tether", "jarvis alert $n ${if (shown) "shown" else "not shown"}")
        return shown
    }

    companion object {
        private const val RECENT = "shown"
        private const val KEEP = 30

        fun start(context: Context) {
            runCatching { alertsStart(Alerts(context.applicationContext)) }
                .onFailure { Log.w("Tether", "alerts not started", it) }
        }
    }
}
