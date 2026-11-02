package com.kivan.jarvis

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * What Jarvis needs to know about the phone, without adb (which reaches it only on home Wi-Fi): the app's
 * version and what decides whether a ring or a notification shows (thread t81, 2026-11-02). A plain GET on
 * the voice port, as [Tasks]; the server keeps the last one and `jarvis phone` reads it. Sent when the
 * process starts (until the server hears), on every app open and with every ring.
 */
object PhoneReport {
    private const val TAG = "JarvisPhone"
    private val base = BuildConfig.VOICE_URL.replace("ws://", "http://").removeSuffix("/call")

    /** Name and value, in the order `jarvis phone` shows them. -1: Android wouldn't say. */
    fun read(context: Context): List<Pair<String, String>> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)
        val cr = context.contentResolver
        fun b(v: Boolean) = if (v) "1" else "0"
        fun channel(id: String) = nm.getNotificationChannel(id)?.importance?.toString() ?: "none"
        return listOfNotNull(
            "version" to BuildConfig.VERSION_NAME,
            "code" to BuildConfig.VERSION_CODE.toString(),
            if (BuildConfig.DEBUG) "emu" to "1" else null,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
            "heads_up" to runCatching { Settings.Global.getInt(cr, "heads_up_notifications_enabled") }.getOrDefault(-1).toString(),
            "notifications" to b(nm.areNotificationsEnabled()),
            "full_screen" to b(nm.canUseFullScreenIntent()),
            "ring_channel" to channel(Callback.CHANNEL),
            "jarvis_channel" to channel(Notifier.JARVIS),
            "dnd" to when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total silence"
                else -> "unknown"
            },
            "ringer" to when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "silent"
            },
            "battery_unrestricted" to b(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)),
            // Reading the calling account's switch takes a phone permission: the last ring says which way it went.
            "last_ring" to RingLine.lastRing(context),
        )
    }

    /** One report; false when the server didn't take it. */
    fun send(context: Context): Boolean = runCatching {
        val q = read(context).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL("$base/phone?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.onFailure { Log.i(TAG, "report not sent: ${it.message}") }.getOrDefault(false)

    /** Started with the process, so after an update too: tries until the server has heard, backing off. */
    fun start(context: Context, scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        var wait = 10_000L
        while (!send(context)) {
            delay(wait)
            wait = (wait * 2).coerceAtMost(600_000L)
        }
    }

    /** Once, now, off the main thread: an app open, a ring. */
    fun now(context: Context) {
        val app = context.applicationContext
        Core.scope.launch(Dispatchers.IO) { send(app) }
    }
}
