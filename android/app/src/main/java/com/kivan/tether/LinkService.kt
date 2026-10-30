package com.kivan.tether

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import com.kivan.tether.forum.Watch
import kotlinx.coroutines.launch

/**
 * Keeps the process up so the forum tunnel's localhost port always answers, Jarvis's notifications
 * arrive ([Alerts], which keeps the tunnel's iroh endpoint up: its relay ping every 15 s) and the forum's
 * own notifications come ([Watch], its long poll; its status is this notification's line), and headphones that
 * connect give the media keys to Tether ([MediaKeys.claim]). No wakelock.
 * The link node isn't kept up: the laptop wakes it through FCM ([Push]). Started with the app and at boot.
 */
class LinkService : Service() {
    private var headphones: android.media.AudioDeviceCallback? = null

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        headphones?.let { getSystemService(android.media.AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, notification(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // Started in the background where Android refuses a foreground service: stop rather than crash;
            // the next open of the app or a reboot starts it again.
            Log.w("Tether", "link service not foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // After a boot or an update, deliver anything left queued; the node stops again once idle.
        Core.scope.launch { runCatching { Core.start() }.onFailure { Log.w("Tether", "node start failed", it) } }
        runCatching { Forum.start() }.onFailure { Log.w("Tether", "forum tunnel failed", it) }
        Alerts.start(this)
        Watch.start(this)
        Updater.loop(this)
        if (reported.compareAndSet(false, true)) Tasks.report(Core.scope)
        CallStore.settle(this)
        Voice.resume(this)
        // Tests: `am startservice -a com.kivan.tether.ALERT --es url /callback/ID` stands in for a Jarvis alert (the server calling back).
        if (BuildConfig.DEBUG && intent?.action == "com.kivan.tether.ALERT") {
            Alerts.test(this, intent.getStringExtra("title") ?: "Jarvis calling back", intent.getStringExtra("text") ?: "Our call dropped.", intent.getStringExtra("url") ?: "/call")
        }
        // Tests: on the emulator (root) `am startservice -a com.kivan.tether.CLAIM_KEYS` stands in for AirPods leaving.
        if (BuildConfig.DEBUG && intent?.action == "com.kivan.tether.CLAIM_KEYS") MediaKeys.claim(this, "test")
        if (headphones == null) headphones = runCatching { MediaKeys.watchHeadphones(this) }.getOrNull()
        return START_STICKY
    }

    companion object {
        private val reported = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile private var text = "Connecting"

        /**
         * A plain start first: startForegroundService gives the service 10 s on the main thread to call
         * startForeground, and an activity making its WebView can hold that thread longer on a busy phone
         * (it crashed the forum app, 2026-10-28). The service still goes foreground. Where Android refuses
         * a plain start (the app in the background), the foreground start.
         */
        fun start(context: Context) {
            val i = Intent(context, LinkService::class.java)
            runCatching { context.startService(i) }
                .recoverCatching { context.startForegroundService(i) }
                .onFailure { Log.w("Tether", "link service not started", it) }
        }

        /** The quiet notification's line ([Watch]'s: whether forum notifications are on, or what they wait for). */
        fun status(context: Context, line: String) {
            if (line == text) return
            text = line
            runCatching { context.getSystemService(NotificationManager::class.java).notify(1, notification(context, line)) }
        }

        private fun notification(context: Context, line: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, Notifier.LINK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Jarvis")
                .setContentText(line)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
