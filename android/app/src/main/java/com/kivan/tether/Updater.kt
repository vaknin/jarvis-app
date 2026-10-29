package com.kivan.tether

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kivan.tether.core.AppUpdate
import com.kivan.tether.core.updateCheck
import com.kivan.tether.core.updateFetch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "Tether"

/**
 * Updates the app from Jarvis's server, from anywhere (mobile data too): the forum tunnel asks the
 * gate for the newest build Jarvis published (`forum-gate publish`, crates/ffi forum.rs), and a
 * higher versionCode than this one is downloaded, checked, and installed by Android without a
 * question (thread #132, Aviv: installs mid-call "seamlessly, without hanging up, and without me
 * noticing it"). Tether asks to own its updates, so once it is the installer of record no install
 * needs a tap; the first one after an adb install still asks, the old way ([StatusReceiver]).
 *
 * An install kills the process, so it's committed only in a pause ([Voice.quiet]: no call, or Jarvis
 * not speaking, nothing queued to play and Aviv quiet); a call it lands in comes back at once on the
 * restart (MY_PACKAGE_REPLACED, [BootReceiver]), so the gap falls in silence.
 *
 * When: on opening the app (at most every 10 min), from the ⋮ menu, and in the background every 6
 * hours. The background check downloads only on Wi-Fi (an update is ~40 MB) and installs what it has;
 * on mobile data it posts "Update ready", whose tap opens the app, which downloads and installs.
 */
object Updater {
    const val EXTRA_INSTALL = "com.kivan.tether.INSTALL_UPDATE"
    private const val CHANNEL = "updates"
    private const val NOTIFY_ID = 20
    private const val STATUS = "com.kivan.tether.UPDATE_STATUS"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private const val OPEN_EVERY_MS = 10 * 60 * 1000L
    /** After "Cancel" on Android's question, the app doesn't ask again on its own for this long. */
    private const val DECLINED_MS = 12 * 60 * 60 * 1000L
    /** How often a waiting install looks for the call's pause. */
    private const val PAUSE_POLL_MS = 200L

    /** What the ⋮ menu shows: "", "Checking…", "Downloading 1.0.230 (40 MB)…", "Up to date", or an error. */
    private val _state = MutableStateFlow("")
    val state: StateFlow<String> = _state

    private val lock = Mutex()
    @Volatile private var lastCheck = 0L
    @Volatile private var looping = false
    /** The app's screen is in front: Android's question can open straight away. */
    @Volatile var foreground = false

    fun channel(nm: NotificationManager) {
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A new Tether from Jarvis is ready to install"
            },
        )
    }

    /** The background check, every 6 hours while the process lives (started by [LinkService]). */
    fun loop(context: Context) {
        if (looping) return
        looping = true
        val app = context.applicationContext
        Core.scope.launch {
            delay(2 * 60 * 1000L)
            while (true) {
                runCatching { check(app, ask = false) }.onFailure { Log.w(TAG, "update check failed", it) }
                delay(EVERY_MS)
            }
        }
    }

    /** The app came to the front; [tapped] when it came from the "Update ready" notification. */
    fun onOpen(context: Context, tapped: Boolean) {
        val now = System.currentTimeMillis()
        if (!tapped && now - lastCheck < OPEN_EVERY_MS) return
        Core.scope.launch {
            runCatching { check(context.applicationContext, ask = true, force = tapped) }
                .onFailure { Log.w(TAG, "update check failed", it); _state.value = "Update check failed: ${it.message}" }
        }
    }

    /** The ⋮ menu's "Check for update". */
    fun checkNow(context: Context) = onOpen(context, tapped = true)

    /**
     * Asks the server. [ask]: the app is on screen, so download and open Android's question now.
     * Otherwise it only prepares: downloads on Wi-Fi, then (either way) posts "Update ready".
     */
    private suspend fun check(context: Context, ask: Boolean, force: Boolean = false) = lock.withLock {
        lastCheck = System.currentTimeMillis()
        if (ask) _state.value = "Checking…"
        val u = updateCheck(context.packageName)
        val mine = BuildConfig.VERSION_CODE.toLong()
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        if (u == null || u.versionCode <= mine) {
            dir.listFiles()?.forEach { it.delete() }
            cancelNotice(context)
            if (ask) _state.value = "Up to date (${BuildConfig.VERSION_NAME})"
            return@withLock
        }
        dir.listFiles()?.filter { it.name != "${u.versionCode}.apk" }?.forEach { it.delete() }
        val apk = File(dir, "${u.versionCode}.apk")
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val declined = prefs.getLong("declined${u.versionCode}", 0L)
        if (!ask) {
            if (!apk.isFile && !unmetered(context)) {
                notice(context, u, downloaded = false)
                return@withLock
            }
            val (file, got) = if (apk.isFile) apk to u else fetch(apk)
            install(context, file, got)
            return@withLock
        }
        if (!force && System.currentTimeMillis() - declined < DECLINED_MS) {
            _state.value = "${u.versionName} is ready: Check for update installs it"
            return@withLock
        }
        var (file, got) = apk to u
        if (!apk.isFile) {
            _state.value = "Downloading ${u.versionName} (${mb(u)} MB)…"
            fetch(apk).let { file = it.first; got = it.second }
        }
        _state.value = "Installing ${got.versionName}…"
        install(context, file, got)
    }

    /** Downloads the newest build next to [apk], named by its own versionCode (a publish since the check is newer still). */
    private suspend fun fetch(apk: File): Pair<File, AppUpdate> {
        val got = updateFetch(BuildConfig.APPLICATION_ID, apk.path)
        val file = File(apk.parentFile, "${got.versionCode}.apk")
        if (file != apk) apk.renameTo(file)
        return file to got
    }

    private fun mb(u: AppUpdate) = (u.size.toLong() + 500_000) / 1_000_000

    private fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    /**
     * Hands the APK to Android's installer, without a question where Android allows it (Tether is the
     * installer of record), else the one tap ([StatusReceiver]). Committed in the first pause of a call.
     */
    private suspend fun install(context: Context, apk: File, u: AppUpdate) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            // Tether owns its updates from here on: the next ones need no tap either.
            setRequestUpdateOwnership(true)
        }
        // A question left unanswered (the app went away before it opened) is replaced by this one.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("tether.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val status = Intent(context, StatusReceiver::class.java).setAction(STATUS)
                .putExtra("code", u.versionCode).putExtra("name", u.versionName)
            val pi = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // The process dies with the install: never while Jarvis speaks or Aviv talks.
            if (!Voice.quiet()) {
                _state.value = "${u.versionName} installs in the call's next pause"
                while (!Voice.quiet()) delay(PAUSE_POLL_MS)
            }
            Log.i(TAG, "update: installing ${u.versionName} in a pause (call ${if (Voice.ui.value.live) "on" else "off"}) at ${System.currentTimeMillis()}")
            s.commit(pi.intentSender)
        }
    }

    /**
     * Tests (debug builds, [UpdaterTest]): installs [apk] through the same path as an update, the
     * pause wait included. The version is whatever the file is; the installer allows the same one again.
     */
    suspend fun installFile(context: Context, apk: File) = lock.withLock {
        _state.value = "Installing ${apk.name}…"
        install(context.applicationContext, apk, AppUpdate(BuildConfig.VERSION_CODE.toLong(), apk.name, apk.length().toULong(), "", ""))
    }

    /**
     * "Tether 1.0.N is ready", once per version (a dismissed one isn't posted again every 6 hours).
     * Its tap opens the app, which asks; or, given [confirm], Android's question itself.
     */
    private fun notice(context: Context, u: AppUpdate, downloaded: Boolean, confirm: Intent? = null) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (confirm == null && prefs.getLong("notified", 0L) == u.versionCode) return
        prefs.edit().putLong("notified", u.versionCode).apply()
        val open = confirm ?: Intent(context, MainActivity::class.java).putExtra(EXTRA_INSTALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val size = if (downloaded) "" else " (${mb(u)} MB)"
        val text = u.notes.lines().map { it.removePrefix("For you:").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis ${u.versionName} is ready$size")
            .setContentText(text.ifEmpty { "Tap to install" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifEmpty { "Tap to install" }))
            .setContentIntent(PendingIntent.getActivity(context, NOTIFY_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    private fun cancelNotice(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFY_ID)

    /** The installer's answers: its question (opened at once while the app is in front), done, or why not. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.getStringExtra("name") ?: ""
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                    // Tether isn't the installer of record yet (an adb install): this once, the tap.
                    Log.i(TAG, "update: $name needs a tap (Tether isn't its installer of record yet)")
                    _state.value = "Waiting for your tap to install $name"
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val shown = foreground && runCatching { context.startActivity(confirm) }
                        .onFailure { Log.w(TAG, "installer", it) }.isSuccess
                    // Aviv left the app while it downloaded: the question waits in a notification.
                    if (!shown) {
                        val u = AppUpdate(intent.getLongExtra("code", 0L), name, 0uL, "", "")
                        notice(context, u, downloaded = true, confirm = confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> _state.value = "Updated to $name"
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    _state.value = "$name is ready: Check for update installs it"
                    context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                        .putLong("declined${intent.getLongExtra("code", 0L)}", System.currentTimeMillis()).apply()
                }
                else -> {
                    val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                    Log.w(TAG, "update $name failed: $why")
                    _state.value = "Couldn't install $name: $why"
                }
            }
        }
    }
}
