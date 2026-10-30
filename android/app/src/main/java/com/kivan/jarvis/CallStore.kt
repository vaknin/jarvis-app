package com.kivan.jarvis

import android.content.Context
import android.util.Log
import com.kivan.jarvis.core.voiceEnd

/**
 * What outlives the process about calls (thread #132): the call the app is in, so one its process
 * dies in (an update, the system) is rejoined on the next start, and hangups the server never
 * confirmed, sent again until it does (the server keeps a dropped call until Aviv hangs up).
 */
object CallStore {
    private const val TAG = "JarvisCall"
    private const val ACTIVE = "active"
    private const val OWED = "owed"

    private fun prefs(context: Context) = context.getSharedPreferences("call", Context.MODE_PRIVATE)

    /** The call the app is in, until it ends (a hangup the server confirmed, or Jarvis's). */
    fun active(context: Context): String? = prefs(context).getString(ACTIVE, null)

    fun joined(context: Context, id: String) {
        prefs(context).edit().putString(ACTIVE, id).commit()
    }

    fun ended(context: Context) {
        prefs(context).edit().remove(ACTIVE).commit()
    }

    fun owed(context: Context): Set<String> = prefs(context).getStringSet(OWED, emptySet())!!.toSet()

    /** Aviv hung up and the line didn't carry it: the call is over for him; the server hears it later. */
    fun owe(context: Context, id: String) {
        val p = prefs(context)
        p.edit().putStringSet(OWED, owed(context) + id).remove(ACTIVE).commit()
    }

    private fun paid(context: Context, id: String) {
        prefs(context).edit().putStringSet(OWED, owed(context) - id).commit()
    }

    /** Sends the hangup for the call `id` now (off the main thread); true once the server has it. */
    fun end(context: Context, id: String): Boolean {
        runCatching { Forum.start() }
        val ok = runCatching { voiceEnd(BuildConfig.VOICE_URL, id) }.getOrDefault(false)
        Log.i(TAG, "hangup for $id: ${if (ok) "the server has it" else "not delivered yet"}")
        if (ok) paid(context, id) else if (id !in owed(context)) owe(context, id)
        return ok
    }

    /** Every owed hangup, sent again (the app started; the server rings for one, which also brings it). */
    fun settle(context: Context) {
        val ids = owed(context)
        if (ids.isEmpty()) return
        Thread({ ids.forEach { end(context, it) } }, "jarvis-call-settle").start()
    }
}
