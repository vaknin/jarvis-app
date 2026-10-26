package com.kivan.tether

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.kivan.tether.core.ChatMessage

/** The app's notifications: messages from the laptop (with Reply), received files, and failures. */
object Notifier {
    const val LINK = "link"
    private const val MESSAGES = "messages"
    private const val FILES = "files"
    private const val ALERTS = "alerts"

    private const val MESSAGE_ID = 10
    private const val FAILED_ID = 11
    private var fileId = 100

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(LINK, "Always on", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Tether keeps the laptop link and the forum reachable"
                    setShowBadge(false)
                },
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(ALERTS, "Problems", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun laptop(context: Context): Person = Person.Builder()
        .setName("Laptop")
        .setKey("laptop")
        .setIcon(IconCompat.createWithResource(context, R.drawable.lucide_laptop))
        .build()

    private fun openChat(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** A text from the laptop, added to the conversation notification, with Reply and Mark as read. */
    fun message(context: Context, m: ChatMessage) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val person = laptop(context)
        val existing = nm.activeNotifications.firstOrNull { it.id == MESSAGE_ID }?.notification
        val style = existing?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        style.addMessage(m.text ?: "", m.tsMs, person)
        val reply = NotificationCompat.Action.Builder(
            R.drawable.lucide_send_horizontal,
            "Reply",
            ReplyReceiver.pending(context, ReplyReceiver.REPLY, mutable = true),
        ).addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply").build())
            .setAllowGeneratedReplies(true)
            .build()
        val read = NotificationCompat.Action.Builder(
            R.drawable.lucide_check, "Mark as read", ReplyReceiver.pending(context, ReplyReceiver.READ, mutable = false),
        ).build()
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setShortcutId(Shortcuts.ID)
            .setContentIntent(openChat(context))
            .setAutoCancel(true)
            .addAction(reply)
            .addAction(read)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { nm.notify(MESSAGE_ID, n) }
    }

    /** A file from the laptop, now in Downloads/Tether: a tap opens it. */
    fun file(context: Context, m: ChatMessage, uri: Uri) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val name = m.fileName ?: "a file"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Downloads.mimeOf(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(context, FILES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("From the laptop")
            .setContentText(name)
            .setContentIntent(PendingIntent.getActivity(context, fileId, view, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(fileId++, n) }
    }

    fun sendFailed(context: Context, name: String, reason: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Couldn't send $name")
            .setContentText(reason)
            .setContentIntent(openChat(context))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(FAILED_ID, n) }
    }

    fun cancelMessages(context: Context) = NotificationManagerCompat.from(context).cancel(MESSAGE_ID)
}
