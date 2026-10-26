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
 * Keeps the process up, and with it the link node and the forum tunnel, so the laptop can always
 * reach the phone (no wake pushes). A quiet foreground service, started with the app and at boot.
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
            .setContentText("Reachable from the laptop · forum at localhost:8080")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
