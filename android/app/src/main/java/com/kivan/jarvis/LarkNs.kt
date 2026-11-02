package com.kivan.jarvis

import android.content.Context

/**
 * The phone's own noise filter on a Lark call (thread t57, Aviv 2026-11-02: "we need to make sure that this duplication
 * will not occur"). The Lark cancels noise itself, and a second filter on top can blur his consonants, so it's off
 * unless this says on. [PlainMic] holds a handle to it on every Lark open and sets it from here, again every 2 s, so
 * a change reaches a call in progress. The AirPods' and the phone's own mic are untouched.
 *
 * Set from the server by an alert to `/set/lark-ns/<on|off>` ([Alerts]), nothing shown. A mic test may name it for
 * the test alone ([test], from the server's `mictest`).
 */
object LarkNs {
    const val URL = "/set/lark-ns/"
    private const val PREFS = "lark"
    private const val NS = "phone-ns"

    /** A mic test's own pick, over the setting until the test ends. */
    @Volatile var test: Boolean? = null

    /** What the filter is on the open Lark mic, as the phone reports it; null with none open or none to hold. */
    @Volatile var active: Boolean? = null

    fun on(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NS, false)

    fun wanted(context: Context) = test ?: on(context)

    /** From an alert: the new value, or null for one it doesn't know. */
    fun set(context: Context, value: String): Boolean? {
        val on = when (value.trim().lowercase()) {
            "on" -> true
            "off" -> false
            else -> return null
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(NS, on).apply()
        return on
    }
}
