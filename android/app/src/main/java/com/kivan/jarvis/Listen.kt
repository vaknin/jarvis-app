package com.kivan.jarvis

import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.util.Log

/**
 * His answer to a speaker ask (t116): Jarvis asked out loud on the speaker at home, and the server
 * sends `/listen/<item>`. The phone opens its mic by itself, screen locked or not, for about 20 s
 * (the server times it); Jarvis answers on the speaker. Nothing rings and nothing shows but the
 * call's own "Jarvis is listening" line.
 *
 * Android gives the mic to an app in the background only with these, granted once ([missing]):
 * battery Unrestricted (so the call's service may start from the background), and the mic in the
 * background, which is the RECORD_AUDIO app op set to allow (adb) or Jarvis as the digital assistant.
 * Without the last the call still tries Telecom's own call service ([CallService]'s mic-later path),
 * and gives up quietly if that lends no mic either.
 */
object Listen {
    private const val TAG = "JarvisListen"

    /** Opens the mic for item `id`. Always null for [Alerts]: a mic that can't open has nothing to show elsewhere. */
    fun open(context: Context, id: String): String? {
        val why = when {
            Voice.ui.value.live -> "already on a call (it hears him there)"
            Dictation.on.value -> "dictating"
            Move.off() -> "the app is still moving in"
            !CallService.micGranted(context) -> "no microphone permission"
            else -> null
        }
        if (why != null) {
            Log.i(TAG, "reply window for $id not opened: $why")
            return null
        }
        val left = missing(context)
        Log.i(TAG, "reply window for $id: opening the mic${if (left.isEmpty()) "" else "; missing: ${left.joinToString()}"}")
        Voice.place.value = Voice.Place()
        Voice.update(context) { Voice.Ui(state = com.kivan.jarvis.core.CallState.Connecting) }
        runCatching {
            context.startForegroundService(Intent(context, CallService::class.java).putExtra(CallService.LISTEN, true))
        }.onFailure {
            Log.w(TAG, "reply window for $id: the call's service can't start from here", it)
            Voice.update(context) { Voice.Ui() }
        }
        return null
    }

    /** May the mic open in the background? The app op at allow, or Jarvis as the assistant. */
    fun backgroundMic(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED ||
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /** What's still missing for a hands-free answer, in plain words; empty when nothing is. */
    fun missing(context: Context): List<String> = listOfNotNull(
        if (!CallService.micGranted(context)) "microphone permission" else null,
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "battery Unrestricted" else null,
        if (!backgroundMic(context)) "mic in the background" else null,
    )

    /** At process start: one line in the log, and [PhoneReport] tells the server. */
    fun check(context: Context) {
        val left = missing(context)
        if (left.isEmpty()) Log.i(TAG, "hands-free answers: ready")
        else Log.w(TAG, "hands-free answers need: ${left.joinToString()}")
    }
}
