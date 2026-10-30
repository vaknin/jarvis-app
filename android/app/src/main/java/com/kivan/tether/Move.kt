package com.kivan.tether

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kivan.tether.core.forumStop
import com.kivan.tether.core.updateCheck
import com.kivan.tether.core.updateFetch
import com.kivan.tether.forum.ForumActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "Move"

/**
 * The move to the renamed app (thread #190), this app's side. Android treats [NEW_APP] as another app, and
 * both carry the phone's iroh key and need the same two local ports, so exactly one may hold the link
 * (thread #112: two with one key fight, and calls drop). The new app runs the move and this one obeys, through
 * [MoveProvider]: it stops its link for a lease the new app keeps renewing, and the link comes back by itself
 * when the lease runs out, new app dead or not. Only [confirm], the new app live and checked, turns it off
 * for good. This app's data stays as it is: the new app takes a copy.
 *
 * "Off" is enforced where the link starts ([Forum.start], [Core.start], [Voice.start]), not by each way in.
 */
object Move {
    const val NEW_APP = BuildConfig.NEW_APP
    const val EXTRA_TAPPED = "com.kivan.tether.MOVE"
    private const val NOTIFY_ID = 21
    private const val LEASE_MS = 25_000L
    private const val EVERY_MS = 60 * 60 * 1000L
    /** How long a question Aviv left alone stays alone. */
    private const val ASK_AGAIN_MS = 24 * 60 * 60 * 1000L

    enum class Link { ON, HELD, OFF }

    private lateinit var app: Context
    private val booted = CountDownLatch(1)
    private val lock = Mutex()
    private val gate = Any()
    /** The link is stopped for a lease that runs until [until] ([SystemClock.elapsedRealtime]). */
    @Volatile var held = false
        private set
    @Volatile private var until = 0L
    @Volatile private var watching = false
    @Volatile private var asked = false
    @Volatile private var looping = false
    /** Android has installed the new app, and it hasn't been opened since. */
    @Volatile private var landing = false

    private val _gone = MutableStateFlow(false)
    /** The new app holds the link for good. */
    val gone: StateFlow<Boolean> = _gone

    private val _offer = MutableStateFlow("")
    /** What home's banner says: "", or its text. */
    val offer: StateFlow<String> = _offer

    private fun prefs() = app.getSharedPreferences("move", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        _gone.value = prefs().getBoolean("moved", false)
    }

    /** The app has started; [MoveProvider], which Android brings up first, may answer from here on. */
    fun booted() = booted.countDown()

    fun awaitBoot() = booted.await(10, TimeUnit.SECONDS)

    fun off() = held || _gone.value

    private fun installed() = runCatching { app.packageManager.getPackageInfo(NEW_APP, 0) }.isSuccess

    /** Asks the new app's own [MoveProvider]; null if it isn't there or doesn't answer. */
    private fun ask(method: String): Bundle? = runCatching {
        app.contentResolver.call(Uri.parse("content://$NEW_APP.move"), method, null, null)
    }.onFailure { Log.i(TAG, "the new app didn't answer $method: ${it.message}") }.getOrNull()

    /** May the link start? Asked first thing at each process start ([TetherApp]) and by [LinkService]. */
    fun link(): Link = synchronized(gate) {
        if (_gone.value) {
            if (installed()) {
                // Once a process: the new app lost what it had (its data cleared), or it stepped down. A new app
                // that doesn't answer stays in charge: this app comes back by Aviv's hand then, not by itself.
                val state = if (asked) null else ask("state")?.getString("state")
                asked = true
                if (state == null || state == "live" || state == "moving") return Link.OFF
            }
            // The new app was uninstalled, or has nothing: this one is all there is.
            setGone(false)
        }
        if (held) return Link.HELD
        if (!asked) {
            // A cold start after a kill: the new app may be mid-move, or live. Its word goes first.
            asked = true
            when (ask("state")?.getString("state")) {
                "live" -> {
                    setGone(true)
                    return Link.OFF
                }
                "moving" -> {
                    hold()
                    return Link.HELD
                }
            }
        }
        Link.ON
    }

    private fun setGone(on: Boolean) {
        prefs().edit().putBoolean("moved", on).commit()
        _gone.value = on
    }

    private fun hold() {
        held = true
        until = SystemClock.elapsedRealtime() + LEASE_MS
        if (watching) return
        watching = true
        Core.scope.launch {
            while (held) {
                delay(2000)
                if (SystemClock.elapsedRealtime() > until) expired()
            }
            watching = false
        }
    }

    /** The new app stopped renewing. If it went live and its word was lost, that stands; otherwise the link comes back. */
    private fun expired() {
        if (ask("state")?.getString("state") == "live") confirm() else resume()
    }

    /** The new app asks for the link: null once it's stopped, or why not. */
    fun pause(): String? = synchronized(gate) {
        if (held) {
            until = SystemClock.elapsedRealtime() + LEASE_MS
            return null
        }
        hold() // First, so a call that's starting either shows below or is refused by [Voice.start].
        if (Voice.ui.value.live || CallStore.active(app) != null) {
            held = false
            return "a call is on"
        }
        Log.i(TAG, "lease: stopping the link for the new app")
        runCatching { forumStop() }.onFailure { Log.w(TAG, "forum stop", it) }
        runCatching { runBlocking { Core.stop() } }.onFailure { Log.w(TAG, "node stop", it) }
        LinkService.status(app, "Moving to the new Jarvis")
        null
    }

    /** The lease goes on; false if it ran out, and then the new app must let go at once. */
    fun renew(): Boolean = synchronized(gate) {
        if (held) until = SystemClock.elapsedRealtime() + LEASE_MS
        held
    }

    /** The new app failed or gave up: the link is this app's again. */
    fun resume() = synchronized(gate) {
        Log.i(TAG, "lease over: the link comes back here")
        held = false
        if (_gone.value) setGone(false)
        LinkService.status(app, "Connecting")
        relink()
    }

    private fun relink() {
        runCatching { Forum.start() }.onFailure { Log.w(TAG, "forum start", it) }
        LinkService.start(app)
    }

    /** The new app is live and checked: off for good. Sent again on its starts, in case one was lost. */
    fun confirm(): Unit = synchronized(gate) {
        if (_gone.value && !held) return
        Log.i(TAG, "the new app is live: this one stops")
        setGone(true)
        held = false
        NotificationManagerCompat.from(app).cancel(NOTIFY_ID)
        Core.scope.launch {
            runCatching { forumStop() }
            runCatching { Core.stop() }
            app.stopService(Intent(app, LinkService::class.java))
            delay(1000)
            if (!Voice.ui.value.live && !Updater.foreground) Process.killProcess(Process.myPid())
        }
    }

    /** "Use this one again" on the moved screen: the new app steps down first. */
    fun takeBack(context: Context) {
        Core.scope.launch {
            val answer = ask("yield")
            if (answer?.getBoolean("ok") != true && installed()) {
                val why = answer?.getString("why") ?: "the new app didn't answer"
                Log.w(TAG, "take back refused: $why")
                android.os.Handler(context.mainLooper).post { Toast.makeText(context, "Not now: $why", Toast.LENGTH_LONG).show() }
                return@launch
            }
            synchronized(gate) {
                asked = true
                setGone(false)
            }
            relink()
        }
    }

    /** Opens the new app. */
    fun open(context: Context) {
        val i = context.packageManager.getLaunchIntentForPackage(NEW_APP) ?: return
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** After the move, this app's ways in lead to the new one (the assistant role and the tile still point here). */
    fun forward(a: Activity): Boolean {
        if (!_gone.value || !installed()) return false
        val i = when (a) {
            is CallActivity -> Intent(a.intent).setComponent(ComponentName(NEW_APP, "com.kivan.jarvis.CallActivity"))
                .apply { if (action == CallActivity.START) action = "com.kivan.jarvis.CALL" }
            is ForumActivity -> Intent(a.intent).setComponent(ComponentName(NEW_APP, "com.kivan.jarvis.forum.ForumActivity"))
            else -> a.packageManager.getLaunchIntentForPackage(NEW_APP)
        }
        runCatching { a.startActivity(i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Log.w(TAG, "forward", it) }
        a.finish()
        return true
    }

    /** The background offer, hourly until the new app is here; quiet if it isn't published yet. */
    fun loop() {
        if (looping) return
        looping = true
        Core.scope.launch {
            delay(30_000)
            while (!_gone.value) {
                runCatching { offer(tapped = false) }.onFailure { Log.i(TAG, "offer: ${it.message}") }
                delay(EVERY_MS)
            }
        }
    }

    /** Home came to the front; [tapped] from the offer's notification or the banner. */
    fun onOpen(context: Context, tapped: Boolean) {
        if (landing && installed()) {
            landing = false
            open(context)
        }
        Core.scope.launch {
            runCatching { offer(tapped) }.onFailure { Log.i(TAG, "offer: ${it.message}") }
        }
    }

    /** The banner's tap. */
    fun act(context: Context) {
        if (installed()) open(context) else onOpen(context, tapped = true)
    }

    private suspend fun offer(tapped: Boolean) = lock.withLock {
        if (off()) return@withLock
        // A newer build of the new app also comes through here: until it's live, it has no link to update itself.
        val have = runCatching { app.packageManager.getPackageInfo(NEW_APP, 0).longVersionCode }.getOrNull()
        val u = updateCheck(NEW_APP)
        if (u == null || (have != null && u.versionCode <= have)) {
            _offer.value = if (have != null) "Finish the move: open the new Jarvis" else ""
            return@withLock
        }
        _offer.value = "Move to the new Jarvis"
        val p = prefs()
        if (!tapped && System.currentTimeMillis() - p.getLong("offered", 0L) < ASK_AGAIN_MS) return@withLock
        val dir = File(app.cacheDir, "move").apply { mkdirs() }
        val apk = File(dir, "${u.versionCode}.apk")
        if (!apk.isFile) {
            if (!tapped && !Updater.foreground && !Updater.unmetered(app)) {
                p.edit().putLong("offered", System.currentTimeMillis()).apply()
                Updater.moveNotice(app, u)
                return@withLock
            }
            dir.listFiles()?.forEach { it.delete() }
            updateFetch(NEW_APP, apk.path)
        }
        p.edit().putLong("offered", System.currentTimeMillis()).apply()
        Updater.installNew(app, apk, u)
    }

    /** Android installed the new app: open it, or say so if Android keeps a background app from opening things. */
    fun landed(context: Context) {
        landing = true
        Log.i(TAG, "the new app is installed")
        File(context.cacheDir, "move").deleteRecursively()
        _offer.value = "Finish the move: open the new Jarvis"
        open(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val i = context.packageManager.getLaunchIntentForPackage(NEW_APP) ?: return
        val n = NotificationCompat.Builder(context, Updater.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Open the new Jarvis")
            .setContentText("It takes everything over from here")
            .setContentIntent(PendingIntent.getActivity(context, NOTIFY_ID, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    /** What the new app reads first and last: every file, the preferences and the WebView's cookies. */
    fun listing(hashed: Boolean): File {
        val root = app.filesDir
        val files = JSONArray()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            files.put(
                JSONObject().put("path", f.relativeTo(root).path).put("size", f.length()).put("mtime", f.lastModified())
                    .apply { if (hashed) put("sha256", sha256(f)) },
            )
        }
        val prefs = JSONObject()
        File(app.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().map { it.nameWithoutExtension }
            .filter { it != "move" && !it.startsWith("WebView") && !it.startsWith("com.google") }
            .forEach { name -> prefs.put(name, JSONObject().apply {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) -> typed(v)?.let { put(k, it) } }
            }) }
        val cookies = CookieManager.getInstance().getCookie(Forum.URL).orEmpty() // A failure here fails the move: no silent logout.
        val out = File(app.cacheDir, "move-listing.json")
        out.writeText(
            JSONObject().put("version", 1).put("code", BuildConfig.VERSION_CODE).put("dir", root.path)
                .put("files", files).put("prefs", prefs).put("cookies", cookies).toString(),
        )
        return out
    }

    private fun typed(v: Any?): JSONObject? = when (v) {
        is Boolean -> JSONObject().put("t", "boolean").put("v", v)
        is Int -> JSONObject().put("t", "int").put("v", v)
        is Long -> JSONObject().put("t", "long").put("v", v)
        is Float -> JSONObject().put("t", "float").put("v", v.toDouble())
        is String -> JSONObject().put("t", "string").put("v", v)
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
        else -> null
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A file the new app may read: anything under filesDir, nothing outside it. */
    fun file(relative: String): File? {
        val root = app.filesDir.canonicalFile
        return File(root, relative).canonicalFile.takeIf { it.startsWith(root) && it.isFile }
    }
}
