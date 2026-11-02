package com.kivan.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * The AirPods mic setting (thread #125, Aviv 2026-11-02: "I want this to be solved. I want to use the AirPods
 * microphone"). Off, a call keeps the AirPods as speakers and hears him on the phone's mic (`rules.rs`). The other
 * values put the call back on their mic:
 * - [Mode.FIX]: Jarvis talks to the buds themselves ([Aap], Apple's own protocol, as LibrePods does), logs their state,
 *   and when his voice comes through quiet it moves the mic to the other bud. On the deaf evenings speech arrived 40 dB
 *   down with only its lows, like a blocked mic port, for a whole evening, through every reopen and new link: the bud
 *   that carries the mic stays the same until they go back in the case. The bud that worked is pinned on the next call.
 * - [Mode.LEFT], [Mode.RIGHT]: that bud's mic for the whole call, nothing switched. For a side-by-side test.
 * - [Mode.PLAIN]: their mic as before, nothing sent to them.
 *
 * Set from the server by an alert to `/set/airpods-mic/<value>` ([Alerts]), so Jarvis flips it when he asks, mid-call
 * included: the rule looks again at once.
 */
object AirPodsMic {
    enum class Mode(val key: String, val budsMic: Boolean, val talks: Boolean) {
        OFF("off", false, false),
        FIX("fix", true, true),
        LEFT("left", true, true),
        RIGHT("right", true, true),
        PLAIN("plain", true, false),
    }

    const val URL = "/set/airpods-mic/"
    private const val PREFS = "airpods"
    private const val MODE = "mic"
    private const val GOOD = "good-bud"

    fun mode(context: Context): Mode {
        val k = prefs(context).getString(MODE, null)
        return Mode.entries.firstOrNull { it.key == k } ?: Mode.OFF
    }

    /** From an alert: the new value, or null for one it doesn't know. */
    fun set(context: Context, value: String): Mode? {
        val m = Mode.entries.firstOrNull { it.key == value.trim().lowercase() } ?: return null
        prefs(context).edit().putString(MODE, m.key).apply()
        Senses.again()
        return m
    }

    /** The bud whose mic last carried his voice at the usual level, pinned at the next call's start. */
    fun goodBud(context: Context): Bud? = prefs(context).getString(GOOD, null)?.let { k -> Bud.entries.firstOrNull { it.name == k } }

    fun rememberGood(context: Context, bud: Bud) = prefs(context).edit().putString(GOOD, bud.name).apply()

    /** Talking to the buds needs Bluetooth's connect permission, asked for on the next app open. */
    fun needsPermission(context: Context) =
        mode(context).talks && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    private var asked = false

    /** On the app's open, once per process: Android's own question, only while a mode needs it. */
    fun ask(activity: android.app.Activity) {
        if (asked || !needsPermission(activity)) return
        asked = true
        activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), PERMISSION)
    }

    private const val PERMISSION = 11

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

enum class Bud { LEFT, RIGHT }

/**
 * Which bud's mic, through one call: pure, so it's unit-tested. [start] pins the bud that worked last time, or the
 * mode's own; [quiet] (voice came through quiet) answers the bud to move to, each tried once; [fine] remembers the one
 * that works. Null from [quiet]: nothing left to try, the buds go back to what they had.
 */
class BudChoice(private val mode: AirPodsMic.Mode, private val good: Bud?) {
    var now: Bud? = null
        private set
    private val tried = mutableSetOf<Bud>()

    fun start(): Bud? {
        now = when (mode) {
            AirPodsMic.Mode.LEFT -> Bud.LEFT
            AirPodsMic.Mode.RIGHT -> Bud.RIGHT
            AirPodsMic.Mode.FIX -> good
            else -> null
        }
        now?.let { tried += it }
        return now
    }

    fun quiet(): Bud? {
        if (mode != AirPodsMic.Mode.FIX) return null
        // From the buds' own choice the first try is the other side of the one that worked last, else the right.
        val next = when (val n = now) {
            null -> if (good == Bud.RIGHT) Bud.LEFT else Bud.RIGHT
            else -> other(n)
        }
        if (next in tried) return null
        tried += next
        now = next
        return next
    }

    /** A window at the usual level: the bud in use works. Null when the buds chose, so there's nothing to remember. */
    fun fine(): Bud? = now

    private fun other(b: Bud) = if (b == Bud.LEFT) Bud.RIGHT else Bud.LEFT
}
