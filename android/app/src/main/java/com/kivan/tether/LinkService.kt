package com.kivan.tether

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Keeps the process up so the forum tunnel's localhost port always answers (its iroh endpoint
 * starts only when the forum is used). Costs nothing while idle: no network, no wakelock. The link
 * node isn't kept up: the laptop wakes it through FCM ([Push]). Started with the app and at boot.
 */
class LinkService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, Notifier.LINK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tether")
            .setContentText("Forum at localhost:8080")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // After a boot or an update, deliver anything left queued; the node stops again once idle.
        Core.scope.launch { runCatching { Core.start() }.onFailure { Log.w("Tether", "node start failed", it) } }
        runCatching { Forum.start() }.onFailure { Log.w("Tether", "forum tunnel failed", it) }
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, LinkService::class.java)) }
                .onFailure { Log.w("Tether", "link service not started", it) }
        }
    }
}
