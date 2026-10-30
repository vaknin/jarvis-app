package com.kivan.jarvis

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch

/** Reply and Mark as read on the message notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        val done = goAsync()
        Core.scope.launch {
            try {
                if (intent.action == REPLY && !text.isNullOrEmpty()) Core.sendText(text)
                Core.markRead()
            } catch (e: Exception) {
                Log.w("Jarvis", "reply failed", e)
            } finally {
                Notifier.cancelMessages(context)
                done.finish()
            }
        }
    }

    companion object {
        const val REPLY = "com.kivan.jarvis.REPLY"
        const val READ = "com.kivan.jarvis.READ"
        const val KEY_TEXT = "text"

        fun pending(context: Context, action: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, ReplyReceiver::class.java).setAction(action),
            if (mutable) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
