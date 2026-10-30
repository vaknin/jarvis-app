package com.kivan.jarvis

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Keeps the process and the CPU up while [Move] runs, screen off or not. */
class MoveService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = NotificationCompat.Builder(this, Notifier.LINK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Moving Jarvis")
            .setOngoing(true)
            .build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Thread({
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:move")
            lock.acquire(10 * 60 * 1000L)
            try {
                Move.run()
            } finally {
                if (lock.isHeld) lock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "jarvis-move").start()
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MoveService::class.java))
        }
    }
}
