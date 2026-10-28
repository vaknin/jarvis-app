package com.kivan.tether.forum

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A notification's Reply and its dismissal. Data: forum-app://ACTION/KIND/ID/UPTO, KIND "t" (thread) or "c" (chat). */
class Actions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = intent.data?.pathSegments ?: return
        if (s.size < 3) return
        val (kind, id, upTo) = Triple(s[0], s[1].toLongOrNull() ?: return, s[2].toLongOrNull() ?: return)
        when (intent.action) {
            DISMISS -> Watch.seen(context, kind, id, upTo)
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val done = goAsync()
                Thread {
                    try {
                        Watch.reply(context, kind, id, upTo, text)
                    } finally {
                        done.finish()
                    }
                }.start()
            }
        }
    }

    companion object {
        const val REPLY = "com.kivan.tether.forum.REPLY"
        const val DISMISS = "com.kivan.tether.forum.DISMISS"
        const val TEXT = "text"
    }
}
