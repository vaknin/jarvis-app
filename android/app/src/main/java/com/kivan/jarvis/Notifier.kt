package com.kivan.jarvis

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
import com.kivan.jarvis.core.ChatMessage

/** The app's notifications: messages from the laptop (with Reply), received files, and failures. */
object Notifier {
    const val LINK = "link"
    const val CALLS = "calls"
    const val JARVIS = "jarvis"
    private const val MESSAGES = "messages"
    private const val FILES = "files"
    private const val ALERTS = "alerts"
    private const val TASKS = "tasks"

    private const val MESSAGE_ID = 10
    private const val FAILED_ID = 11
    private var fileId = 100

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(LINK, "Always on", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Jarvis keeps the laptop link and the forum reachable"
                    setShowBadge(false)
                },
                // Low: quiet and never peeking or lighting the screen (an app may lower a channel it made).
                NotificationChannel(CALLS, "Calls with Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The ongoing call, with Hang up and Mute"
                    setSound(null, null)
                },
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(JARVIS, "Jarvis", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "What Jarvis sends you: a job done, a question, a list you asked for"
                },
                // Low, as calls: no sound and no peek. A tracked task's end waits in the shade.
                NotificationChannel(TASKS, "Tracked tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A task you named is on your phone, with Call Jarvis"
                    setSound(null, null)
                },
                NotificationChannel(FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(ALERTS, "Problems", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
        Updater.channel(nm)
        Callback.channel(nm)
    }

    fun laptop(context: Context): Person = Person.Builder()
        .setName("Laptop")
        .setKey("laptop")
        .setIcon(IconCompat.createWithResource(context, R.drawable.lucide_laptop))
        .build()

    private fun openChat(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, LaptopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    /** A file from the laptop, now in Downloads/Jarvis: a tap opens it. */
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

    /**
     * A notification from Jarvis ([Alerts]): its own, one per [id], expandable to the whole text. A tap
     * opens the forum at [url] (a path) in the app, or calls Jarvis when [url] is "/call". Null once it's
     * shown; else why Android won't show it, which the sender passes on as it takes its other route.
     */
    fun jarvis(context: Context, id: Long, title: String, text: String, url: String): String? {
        val nm = NotificationManagerCompat.from(context)
        off(nm.areNotificationsEnabled(), nm.getNotificationChannel(JARVIS)?.importance, "Jarvis")?.let { return it }
        val tag = id.toInt()
        // "/call": Jarvis wants to talk; the tap opens the call screen and calls, and Jarvis speaks first.
        val call = url == "/call"
        val callIntent = PendingIntent.getActivity(
            context, tag, CallActivity.intent(context, start = true), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = if (call) callIntent else PendingIntent.getActivity(
            context, tag, Forum.view(context, url.ifEmpty { "/" }), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, JARVIS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { "Jarvis" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .apply { if (call) addAction(R.drawable.lucide_phone, "Call", callIntent).setCategory(NotificationCompat.CATEGORY_CALL) }
            .build()
        return runCatching { nm.notify("jarvis", tag, n) }.exceptionOrNull()?.let { Alerts.threw(it) }
    }

    /**
     * A tracked task's notice ([Alerts]): quiet. A tap reads the task; Call Jarvis is its own button, and
     * nothing calls until he presses it. One per task, so a newer notice replaces the last. Null once
     * it's shown; else why Android won't show it, as [jarvis].
     */
    fun task(context: Context, id: Long, title: String, text: String, slug: String): String? {
        val nm = NotificationManagerCompat.from(context)
        off(nm.areNotificationsEnabled(), nm.getNotificationChannel(TASKS)?.importance, "Tracked tasks")?.let { return it }
        val tag = id.toInt()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val call = PendingIntent.getActivity(context, tag, CallActivity.intent(context, start = true, task = slug), flags)
        val open = PendingIntent.getActivity(context, tag, TasksActivity.intent(context, slug), flags)
        val n = NotificationCompat.Builder(context, TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { "Jarvis" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(R.drawable.lucide_phone, "Call Jarvis", call)
            .build()
        return runCatching { nm.notify("task:$slug", 0, n) }.exceptionOrNull()?.let { Alerts.threw(it) }
    }

    /** The phone setting that keeps a notification off, or null. `jarvis notify` knows these words and tells Aviv what to turn on. */
    fun off(appEnabled: Boolean, importance: Int?, channel: String): String? = when {
        !appEnabled -> "app notifications off"
        importance == NotificationManager.IMPORTANCE_NONE -> "$channel channel off"
        else -> null
    }

    fun cancelMessages(context: Context) = NotificationManagerCompat.from(context).cancel(MESSAGE_ID)
}
