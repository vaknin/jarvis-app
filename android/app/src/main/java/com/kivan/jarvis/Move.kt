package com.kivan.jarvis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import android.webkit.CookieManager
import com.kivan.jarvis.core.forumProbe
import com.kivan.jarvis.core.forumStop
import com.kivan.jarvis.core.moveFix
import com.kivan.jarvis.core.updateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Move"

/**
 * The move in from the app this one replaced, [OLD_APP] (thread #190). Android treats it as another app, and
 * both carry the phone's iroh key and need the same two local ports, so exactly one may hold the link (thread
 * #112: two with one key fight, and calls drop). This app stays in [State.STANDBY], link off, until it has
 * everything. Then: the server answers a throwaway key, the data comes over while the old app works on, the old
 * app stops its link for a lease this side keeps renewing, the last of the data comes over and is checked, and
 * this app starts its link and checks it end to end. Only then is it [State.LIVE] and the old app told to stay
 * off. Any failure, and any death of this process, gives the link back: the lease runs out by itself.
 *
 * "Off" is enforced where the link starts ([Forum.start], [Core.start], [Voice.start]), not by each way in.
 */
object Move {
    /** The old name, kept only to find the old app. Goes when nobody can still have it installed. */
    const val OLD_APP = BuildConfig.OLD_APP
    private const val RENEW_MS = 5_000L
    /** Under the old app's lease of 25 s. */
    private const val RENEW_TRIES = 3
    private const val TMP = "move-tmp"

    enum class State { STANDBY, MOVING, LIVE }

    private lateinit var app: Context
    private val _tick = MutableStateFlow(0)
    /** Goes up each time the app comes back from a settings screen, so the guide looks again. */
    val tick: StateFlow<Int> = _tick
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val booted = CountDownLatch(1)
    private val running = AtomicBoolean()
    /** The old app's link is stopped, so ours may start while it's being checked. */
    @Volatile private var trying = false
    @Volatile private var interrupted = false
    /** The old app's lease ran out mid-move: it has the link, and this try is over. */
    @Volatile private var lost = false
    private var renewing: Job? = null

    private val _state = MutableStateFlow(State.STANDBY)
    val state: StateFlow<State> = _state

    private val _step = MutableStateFlow("")
    /** What the screen says is happening now. */
    val step: StateFlow<String> = _step

    private val _failed = MutableStateFlow("")
    /** Why the last try failed; the old app has the link. */
    val failed: StateFlow<String> = _failed

    private val _done = MutableStateFlow(false)
    /** The guide after the move has been finished. */
    val done: StateFlow<Boolean> = _done

    private fun prefs() = app.getSharedPreferences("move", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val saved = prefs().getString("state", null)
        _done.value = prefs().getBoolean("guided", false)
        when {
            saved == "live" -> _state.value = State.LIVE
            !installed() -> setState(State.LIVE).also { _done.value = true }
            saved == "moving" -> {
                // A cold start mid-move: never carry on from here. What came over goes, the old app gets its link.
                interrupted = true
                setState(State.STANDBY)
            }
            else -> _state.value = State.STANDBY
        }
    }

    /** The app has started; [MoveProvider], which Android brings up first, may answer from here on. */
    fun booted() {
        booted.countDown()
        if (interrupted) {
            interrupted = false
            scope.launch {
                unswap()
                ask("resume")
            }
        } else if (_state.value == State.LIVE && installed() && !prefs().getBoolean("confirmed", false)) {
            // In case the old app missed it. Only until it has answered: this wakes its process.
            scope.launch { confirm() }
        }
    }

    fun awaitBoot() = booted.await(10, TimeUnit.SECONDS)

    fun off() = _state.value != State.LIVE && !trying

    /** Is the old app here to move from? */
    fun installed() = runCatching { app.packageManager.getPackageInfo(OLD_APP, 0) }.isSuccess

    /** An old app that has had its last update has a way in; an older one doesn't. */
    fun ready() = app.packageManager.resolveContentProvider("$OLD_APP.move", 0) != null

    private fun setState(s: State) {
        prefs().edit().putString("state", s.name.lowercase()).commit()
        _state.value = s
    }

    private fun ask(method: String): Bundle? = runCatching {
        app.contentResolver.call(Uri.parse("content://$OLD_APP.move"), method, null, null)
    }.onFailure { Log.i(TAG, "the old app didn't answer $method: ${it.message}") }.getOrNull()

    private fun confirm() {
        if (ask("confirm") != null) prefs().edit().putBoolean("confirmed", true).apply()
    }

    private fun uri(vararg path: String) = Uri.parse("content://$OLD_APP.move").buildUpon().apply { path.forEach(::appendPath) }.build()

    /** Tests on the emulator only; gone from a real build. */
    private fun hook(at: String) {
        if (!BuildConfig.EMU_TEST) return
        val v = runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.jarvis.fail")).inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        if (v == at) error("test failure at $at")
        if (v == "hold-$at") Thread.sleep(600_000)
    }

    /** Whether a tap is needed to start: Aviv took the link back, on purpose. */
    fun yielded() = prefs().getBoolean("yielded", false)

    /** The screen asks for this once mic and notifications are allowed, or on Try again. */
    fun start(context: Context) {
        if (_state.value != State.STANDBY || running.get()) return
        if (!installed() || !ready()) return
        prefs().edit().remove("yielded").apply()
        MoveService.start(context)
    }

    /** The whole move, on [MoveService]'s thread. */
    fun run() {
        if (!running.compareAndSet(false, true)) return
        _failed.value = ""
        var paused = false
        try {
            _step.value = "Checking the server"
            runBlocking { forumProbe(Forum.ticket()) }
            hook("probe")

            _step.value = "Copying your data"
            room(null) // Before the old app has to write its listing: on a full disk it can't.
            val first = listing("listing")
            room(first)
            pull(first, check = false)
            hook("copy")

            while (true) {
                setState(State.MOVING)
                val answer = ask("pause") ?: error("the old app didn't answer")
                if (answer.getBoolean("ok")) break
                val why = answer.getString("why").orEmpty()
                if (why != "a call is on") error(why)
                setState(State.STANDBY)
                _step.value = "Waiting for your call to end"
                Thread.sleep(5_000)
            }
            paused = true
            keepLease()

            _step.value = "Copying the last of it"
            val last = listing("hashed")
            room(last)
            pull(last, check = true)
            writePrefs(last.getJSONObject("prefs"), last.getString("dir"))
            cookies(last.getString("cookies"))
            hook("data")
            holding()
            swap()
            val from = File(last.getString("dir")).parent!!
            val fix = moveFix(File(app.filesDir, "state").path, from, app.dataDir.path)
            val messages = fix.messages.toLong()
            Log.i(TAG, "data: $messages messages, ${fix.paths} paths moved")
            check(fix.integrity == "ok") { "the chat database: ${fix.integrity}" }
            check(fix.identity) { "the paired identity didn't arrive" }

            _step.value = "Checking the connection"
            holding()
            trying = true
            Forum.start()
            verify(messages)
            hook("verify")

            holding()
            setState(State.LIVE)
            trying = false
            renewing?.cancel()
            prefs().edit().putLong("messages", messages).remove("swapped").apply()
            confirm()
            _step.value = ""
            LinkService.start(app)
        } catch (e: Throwable) {
            Log.w(TAG, "move failed", e)
            rollback(paused)
            _failed.value = e.message ?: e.toString()
        } finally {
            running.set(false)
        }
    }

    /** The old app's lease, renewed until the move is over; if it ran out there, let go at once. */
    private fun keepLease() {
        lost = false
        renewing = scope.launch {
            var quiet = 0
            while (isActive) {
                delay(RENEW_MS)
                val answer = ask("renew")
                if (answer?.getBoolean("ok") == true) quiet = 0
                // No answer isn't a no, for as long as the lease can still be alive there.
                if (answer?.getBoolean("ok") == true || (answer == null && ++quiet < RENEW_TRIES)) continue
                Log.w(TAG, "the lease is gone: letting go")
                lost = true
                trying = false
                runCatching { forumStop() }
                return@launch
            }
        }
    }

    private fun holding() = check(!lost) { "the old app took the link back" }

    private fun rollback(paused: Boolean) {
        renewing?.cancel()
        trying = false
        runCatching { forumStop() }.onFailure { Log.w(TAG, "forum stop", it) }
        runCatching { runBlocking { Core.stop() } }.onFailure { Log.w(TAG, "node stop", it) }
        unswap()
        setState(State.STANDBY)
        if (paused) ask("resume")
        _step.value = ""
    }

    private fun listing(which: String): JSONObject =
        app.contentResolver.openInputStream(uri(which))!!.bufferedReader().use { JSONObject(it.readText()) }

    private fun room(listing: JSONObject?) {
        val files = listing?.getJSONArray("files")
        val need = (0 until (files?.length() ?: 0)).sumOf { files!!.getJSONObject(it).getLong("size") }
        val free = StatFs(app.filesDir.path).availableBytes
        check(free > need + 64L * 1024 * 1024) { "not enough space: ${need / 1_000_000} MB to copy, ${free / 1_000_000} MB free" }
    }

    private fun inside(root: File, relative: String): File {
        val f = File(root, relative).canonicalFile
        check(f.startsWith(root.canonicalFile)) { "odd path $relative" }
        return f
    }

    /** Brings the listed files into [TMP]: those that changed, and with [check], each checked against its hash. */
    private fun pull(listing: JSONObject, check: Boolean) {
        // Canonical, as [inside] gives: filesDir goes through a symlink, and [keep] is compared by path below.
        val tmp = File(app.filesDir, TMP).canonicalFile.apply { mkdirs() }
        val files = listing.getJSONArray("files")
        val keep = HashSet<File>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val path = f.getString("path")
            if (path.startsWith("profileinstaller") || path == "profileInstalled") continue // Android's, this install's own
            val dst = inside(tmp, path)
            keep += dst
            val same = dst.length() == f.getLong("size") && dst.lastModified() == f.getLong("mtime")
            val hash = f.optString("sha256")
            if (same && (!check || sha256(dst) == hash)) continue
            dst.parentFile!!.mkdirs()
            app.contentResolver.openInputStream(uri("file", *path.split('/').toTypedArray()))!!.use { from ->
                dst.outputStream().use { to ->
                    from.copyTo(to)
                    to.fd.sync()
                }
            }
            dst.setLastModified(f.getLong("mtime"))
            if (check) check(sha256(dst) == hash) { "$path didn't arrive whole" }
        }
        // Gone at the old app since the first pass.
        tmp.walkBottomUp().filter { it.isFile && it !in keep }.forEach { it.delete() }
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

    /** The preferences, each value with its type, read back after writing. What belongs to one install stays out. */
    private fun writePrefs(all: JSONObject, oldDir: String) {
        val oldData = File(oldDir).parent!!
        for (name in all.keys()) {
            val values = all.getJSONObject(name)
            val p = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val e = p.edit().clear()
            for (k in values.keys()) {
                if (name == "app" && (k == "askedBattery" || k == "notified" || k.startsWith("declined"))) continue
                val o = values.getJSONObject(k)
                when (o.getString("t")) {
                    "boolean" -> e.putBoolean(k, o.getBoolean("v"))
                    "int" -> e.putInt(k, o.getInt("v"))
                    "long" -> e.putLong(k, o.getLong("v"))
                    "float" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "string" -> e.putString(k, o.getString("v").replace(oldData, app.dataDir.path))
                    "set" -> e.putStringSet(k, o.getJSONArray("v").let { a -> (0 until a.length()).map(a::getString).toSet() })
                }
            }
            check(e.commit()) { "couldn't save $name" }
        }
    }

    /** The forum login. `getCookie` gives names and values only, so the rest is the forum's own. */
    private fun cookies(all: String) {
        if (all.isBlank()) return
        val jar = CookieManager.getInstance()
        all.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            jar.setCookie(Forum.URL, "$pair; path=/; Max-Age=5184000; SameSite=Lax; HttpOnly")
        }
        jar.flush()
        val now = jar.getCookie(Forum.URL).orEmpty()
        val want = all.split(";").map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
        val missing = want.filter { n -> now.split(";").none { it.trim().substringBefore('=') == n } }
        check(missing.isEmpty()) { "the forum login didn't arrive: $missing" }
    }

    /** Puts what arrived in place. Anything already here (an earlier move) is set aside, not lost. */
    private fun swap() {
        val tmp = File(app.filesDir, TMP)
        val names = tmp.list().orEmpty().toSet()
        prefs().edit().putStringSet("swapped", names).commit()
        val aside = File(app.filesDir, "before-move-${System.currentTimeMillis()}")
        for (name in names) {
            val dst = File(app.filesDir, name)
            if (dst.exists()) {
                aside.mkdirs()
                check(dst.renameTo(File(aside, name))) { "couldn't set $name aside" }
            }
            check(File(tmp, name).renameTo(dst)) { "couldn't put $name in place" }
        }
        tmp.delete()
    }

    /** Takes back what [swap] put in place, and restores what it set aside. The old app has the data. */
    private fun unswap() {
        val tmp = File(app.filesDir, TMP)
        val names = prefs().getStringSet("swapped", emptySet()).orEmpty()
        names.filter { !File(tmp, it).exists() }.forEach { File(app.filesDir, it).deleteRecursively() }
        val aside = app.filesDir.listFiles().orEmpty().filter { it.name.startsWith("before-move-") }.maxByOrNull { it.name }
        if (names.isNotEmpty()) aside?.let { a ->
            a.listFiles().orEmpty().forEach { it.renameTo(File(app.filesDir, it.name)) }
            a.delete()
        }
        prefs().edit().remove("swapped").commit()
    }

    private fun get(url: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        return try {
            c.responseCode.also { c.inputStream.close() }
        } finally {
            c.disconnect()
        }
    }

    private fun retry(what: String, seconds: Int, attempt: () -> Unit) {
        var last: Throwable? = null
        repeat(seconds / 2) {
            try {
                return attempt()
            } catch (e: Exception) {
                last = e
                Thread.sleep(2_000)
            }
        }
        error("$what didn't answer: ${last?.message}")
    }

    /** The link, end to end with the phone's own key: the forum, the build store, the voice pipe, the chat node. */
    private fun verify(messages: Long) {
        retry("the forum", 60) { check(get("http://127.0.0.1:${Forum.PORT}/") in 200..399) { "bad answer" } }
        runBlocking { updateCheck(app.packageName) }
        retry("the voice server", 30) { check(get("http://127.0.0.1:${Voice.PORT}/avail") == 200) { "bad answer" } }
        val node = runBlocking { Core.start() }
        val status = node.status()
        Log.i(TAG, "node: ${status.id} peer ${status.peer}")
        check(status.id.isNotEmpty()) { "the chat identity didn't arrive" }
        val seen = node.messageCount().toLong()
        check(seen == messages) { "$seen messages, expected $messages" }
    }

    /** "Use this one again" on the old app: the link goes back there. */
    fun yieldLink(): String? {
        if (Voice.ui.value.live || CallStore.active(app) != null) return "a call is on"
        if (running.get()) return "the move is running"
        Log.i(TAG, "stepping down for the old app")
        prefs().edit().putBoolean("yielded", true).remove("confirmed").commit()
        setState(State.STANDBY)
        runCatching { forumStop() }.onFailure { Log.w(TAG, "forum stop", it) }
        runCatching { runBlocking { Core.stop() } }.onFailure { Log.w(TAG, "node stop", it) }
        app.stopService(Intent(app, LinkService::class.java))
        return null
    }

    /** Until the move is done, every way into the app leads to the screen that does it. */
    fun forward(a: Activity): Boolean {
        if (!off()) return false
        runCatching { a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        a.finish()
        return true
    }

    /** The guide is finished, or put away for later. */
    fun guided() {
        prefs().edit().putBoolean("guided", true).apply()
        _done.value = true
    }

    fun resumed() {
        _tick.value += 1
    }

    /** Opens the guide again, from home's ⋮. */
    fun guide() {
        _done.value = false
    }

    fun messages() = prefs().getLong("messages", -1)
}
