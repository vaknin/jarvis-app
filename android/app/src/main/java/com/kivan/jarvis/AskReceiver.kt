package com.kivan.jarvis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.kivan.jarvis.core.tabsAnswerNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * An answer from a question's notification, without opening the app: a button is its option, Note
 * is his own words (one that is an option's name is that option). It goes on its own stream to the
 * tab server; landed, the notification goes; refused, it says why and a tap opens the dialog.
 */
class AskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tab = intent.getStringExtra(TAB) ?: return
        val q = intent.getStringExtra(Q) ?: return
        val tag = intent.getIntExtra(TAG, 0)
        val options = intent.getStringArrayListExtra(OPTIONS).orEmpty()
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_NOTE)?.toString()?.trim().orEmpty()
        val chosen = options.indexOfFirst { it.equals(typed, ignoreCase = true) }.takeIf { it >= 0 }
        val pick = intent.getIntExtra(PICK, -1).takeIf { it >= 0 } ?: chosen
        val note = if (chosen != null) "" else typed
        if (pick == null && note.isEmpty()) return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { Forum.start() }
                val why = runCatching { tabsAnswerNow(tab, q, listOfNotNull(pick?.toUInt()), note) }.getOrElse { it.message ?: "it threw" }
                Log.i("Jarvis", "answer from the notification, $tab/$q: ${why ?: "landed"}")
                if (why == null) NotificationManagerCompat.from(context).cancel(Notifier.ASK, tag)
                else Notifier.askFailed(context, tag, tab, q, why)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        private const val ANSWER = "com.kivan.jarvis.ANSWER"
        const val KEY_NOTE = "note"
        private const val TAB = "tab"
        private const val Q = "q"
        private const val TAG = "tag"
        private const val PICK = "pick"
        private const val OPTIONS = "options"

        /** Option [pick] (or -1 for his typed words) of question [q] in [tab]; [typed] makes it take his words. */
        fun pending(context: Context, tag: Int, tab: String, q: String, options: List<String>, pick: Int, typed: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context,
            tag * 8 + pick + 1,
            Intent(context, AskReceiver::class.java).setAction("$ANSWER/$tag/$pick")
                .putExtra(TAB, tab).putExtra(Q, q).putExtra(TAG, tag).putExtra(PICK, pick)
                .putStringArrayListExtra(OPTIONS, ArrayList(options)),
            if (typed) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
