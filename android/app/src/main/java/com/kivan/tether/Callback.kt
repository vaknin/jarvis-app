package com.kivan.tether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Jarvis ringing Aviv (thread #132): calling back a call that dropped (the server's `/callback/<id>`
 * alert, while the app isn't redialing it itself), and calling him when he's needed (thread #36: the
 * `/ring/<item>` alert, reach ringing him while he's out; the older `/call` too). It looks like a
 * regular incoming call from "Jarvis". Answering (the notification, the full-screen call screen, or the
 * AirPods' stem press through [MediaKeys.Receiver]) continues the dropped call, or starts one Jarvis
 * opens with why he called. Declining a ring tells the server, which sends what it rang for as text.
 *
 * It rings only with the ringer on and Do Not Disturb off ([loud]); otherwise the same call shows as
 * a silent notification. Unanswered after [RING_MS] it becomes a missed call with "Call back" (a ring
 * for an item stops after [REACH_RING_MS] without one: the server texts the item instead);
 * Decline, or dismissing the missed call, hangs the dropped call up for good.
 */
object Callback {
    private const val TAG = "TetherCall"
    const val CHANNEL = "ring"
    private const val RING_ID = 40
    private const val MISSED_ID = 41
    private const val RING_MS = 45_000L
    /** Reach's ring: a minute, then the server sends it as text. */
    private const val REACH_RING_MS = 60_000L
    /** The dropped call an answer continues (absent: a new call). */
    const val CALL = "call"
    /** The reach item a ring is for (`reach-...`): Decline sends it to the server. */
    private const val RING = "ring"
    private const val DECLINE = "com.kivan.tether.DECLINE"

    /** What's ringing now: the dropped call it continues, or "" for a new call. */
    @Volatile var ringing: String? = null
        private set
    /** The reach item ringing now, if it's a ring for one. */
    @Volatile private var ringFor: String? = null
    /** A dropped call whose callback went unanswered: the next call Aviv starts (a press too) continues it. */
    @Volatile private var missedCall: String? = null
    private val main = Handler(Looper.getMainLooper())

    /** The dropped call a call Aviv starts now continues: the one ringing, or the one he missed. */
    fun pending(): String? = ringing?.ifEmpty { null } ?: missedCall

    fun channel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis calling", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Jarvis calling you back after a call dropped, or when he needs you"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 800)
            },
        )
    }

    /** The phone may ring: the ringer is on and Do Not Disturb is off. */
    fun loud(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /**
     * Rings for the dropped call `call`, or for a new one (null); `reach`: the item the server rings
     * for. True once it's on the phone.
     */
    fun ring(context: Context, call: String?, title: String, text: String, reach: String? = null): Boolean {
        if (Voice.ui.value.live) return true
        val ms = if (reach != null) REACH_RING_MS else RING_MS
        val loud = loud(context)
        val jarvis = Person.Builder().setName("Jarvis").setImportant(true).build()
        val answer = answerIntent(context, call, 50)
        // The ringing screen (full screen on a locked phone): its Call button answers, nothing before it.
        val screen = PendingIntent.getActivity(
            context, 54, CallActivity.intent(context, start = false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context, 51, Intent(context, Action::class.java).setAction(DECLINE).putExtra(CALL, call).putExtra(RING, reach),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, if (loud) CHANNEL else Notifier.JARVIS)
            .setSmallIcon(R.drawable.lucide_phone)
            .setContentTitle(title.ifEmpty { "Jarvis" })
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(screen)
            .setOngoing(loud)
            .setTimeoutAfter(ms + 5_000)
        if (loud) {
            // Full screen on a locked phone, a heads-up otherwise; the ringtone loops until answered.
            b.setFullScreenIntent(screen, true)
                .setStyle(Notification.CallStyle.forIncomingCall(jarvis, decline, answer))
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(text))
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.lucide_phone), "Answer", answer).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.lucide_phone_off), "Decline", decline).build())
        }
        val n = b.build()
        if (loud) n.flags = n.flags or Notification.FLAG_INSISTENT
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val posted = runCatching { nm.notify(RING_ID, n) }.onFailure { Log.w(TAG, "ring not shown", it) }.isSuccess
        if (!posted) return false
        nm.cancel(MISSED_ID)
        ringing = call ?: ""
        ringFor = reach
        Voice.update(context) { it.copy(ringing = true) }
        Log.i(TAG, "ringing (${if (loud) "loud" else "silent"}) for ${call ?: "a new call"}")
        // The AirPods' press answers: the keys come to Tether's receiver while nothing else plays.
        MediaKeys.claim(context, "ringing")
        main.removeCallbacksAndMessages(this)
        main.postAtTime({ missed(context, call, title, reach) }, this, android.os.SystemClock.uptimeMillis() + ms)
        return true
    }

    /** What an answer continues: the call it rang for, or null for a new call. Stops the ringing. */
    fun answered(context: Context, intent: Intent?): String? {
        val call = intent?.getStringExtra(CALL) ?: pending()
        missedCall = null
        stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(MISSED_ID)
        return call
    }

    private fun stop(context: Context) {
        ringing = null
        ringFor = null
        Voice.update(context) { it.copy(ringing = false) }
        main.removeCallbacksAndMessages(this)
        context.getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    /** Opens the call screen answering: the stem press, the notification, the full-screen intent. */
    fun answerIntent(context: Context, call: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code, CallActivity.intent(context, start = true).putExtra(CALL, call),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun missed(context: Context, call: String?, title: String, reach: String? = null) {
        if (ringing == null) return
        stop(context)
        // A ring for an item: the server's text of it follows, one notice for one thing.
        if (reach != null) {
            Log.i(TAG, "missed the ring for $reach: the server sends it as text")
            return
        }
        missedCall = call
        val back = answerIntent(context, call, 52)
        // On the quiet channel: the ringing was the alert.
        val n = Notification.Builder(context, Notifier.CALLS)
            .setSmallIcon(R.drawable.lucide_phone)
            .setContentTitle("Missed call from Jarvis")
            .setContentText(if (call != null) "Call back to go on where you were." else "He wanted to talk. Call back when you can.")
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(back)
            .setAutoCancel(true)
            .setShowWhen(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.lucide_phone), "Call back", back).build())
            // Swiping a missed callback away hangs that call up: the server keeps it until then.
            .apply {
                if (call != null) setDeleteIntent(
                    PendingIntent.getBroadcast(
                        context, 53, Intent(context, Action::class.java).setAction(DECLINE).putExtra(CALL, call),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(MISSED_ID, n)
        }
        Log.i(TAG, "missed: ${call ?: "a new call"}")
    }

    /** Decline on the ringing screen: as the notification's. */
    fun decline(context: Context) {
        decline(context, ringing?.ifEmpty { null }, ringFor, null)
    }

    /** Declined: a dropped call is hung up; a ring for a reach item tells the server, which texts it. */
    private fun decline(context: Context, call: String?, reach: String?, done: BroadcastReceiver.PendingResult?) {
        if (call == missedCall) missedCall = null
        stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(MISSED_ID)
        Log.i(TAG, "declined: ${call ?: reach ?: "a new call"}")
        if (call == null && reach == null) {
            done?.finish()
            return
        }
        val app = context.applicationContext
        Thread({
            try {
                if (call != null) CallStore.end(app, call)
                // The same hangup frame, for the item: the server sends it as text now.
                if (reach != null) Log.i(TAG, "decline for $reach: ${if (runCatching { com.kivan.tether.core.voiceEnd(BuildConfig.VOICE_URL, reach) }.getOrDefault(false)) "the server has it" else "not delivered (texted after the minute)"}")
            } finally { done?.finish() }
        }, "tether-call-decline").start()
    }

    /** Decline, or the missed callback dismissed: that dropped call is hung up. */
    class Action : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DECLINE) return
            decline(context, intent.getStringExtra(CALL), intent.getStringExtra(RING), goAsync())
        }
    }
}
