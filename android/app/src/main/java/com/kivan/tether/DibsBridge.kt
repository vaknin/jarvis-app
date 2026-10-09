package com.kivan.tether

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.edit
import androidx.compose.ui.graphics.asImageBitmap
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsHost
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.fetchedOf
import com.kivan.tether.dibs.ideaFileOf
import com.kivan.tether.core.MsgState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * dibs's screens (the `:dibs` module, docs/DIBS-APP.md) over Tether's link: the dibs channel's
 * view, its actions, files sent to the channel, and the link held while the screen is up.
 */
class DibsBridge(context: Context) : DibsHost {
    private val app = context.applicationContext

    override val view: StateFlow<JSONObject?> = Channels.views.map { it[Channels.DIBS] }
        .stateIn(Core.scope, SharingStarted.Eagerly, Channels.views.value[Channels.DIBS])

    override val link: StateFlow<Link> = combine(Core.connected, Core.refused, Core.status) { up, refused, status ->
        when {
            refused || (status != null && status.peer == null) -> Link.UNPAIRED
            up -> Link.CONNECTED
            else -> Link.OFFLINE
        }
    }.stateIn(Core.scope, SharingStarted.Eagerly, Link.OFFLINE)

    override val pickDir: java.io.File get() = Core.outgoingDir

    override val uploads: StateFlow<Map<String, Float>> = Core.progress
        .map { p -> p.mapValues { (_, v) -> if (v.second > 0) v.first.toFloat() / v.second else 0f } }
        .stateIn(Core.scope, SharingStarted.Eagerly, emptyMap())

    override fun live(data: JSONObject): Boolean = Core.sendLive { it.sendAppLive(Channels.DIBS, data.toString()) }

    override fun act(action: String, value: JSONObject?, uid: String?): String {
        val obj = JSONObject().put("action", action)
        if (value != null) obj.put("value", value)
        if (uid != null) obj.put("uid", uid)
        return Channels.act(Channels.DIBS, obj)
    }

    override suspend fun actStored(action: String, value: JSONObject?): String {
        val obj = JSONObject().put("action", action)
        if (value != null) obj.put("value", value)
        return Channels.actStored(Channels.DIBS, obj)
    }

    /**
     * Each file goes to the dibs channel as a file of its own (resume, checksum and progress as any
     * transfer), then one `say` names them with the text; dibs waits until they've all arrived.
     */
    override fun send(uid: String, text: String, files: List<Uri>, action: String, extra: JSONObject?, onFile: (Uri, String) -> Unit) {
        Core.scope.launch {
            val hold = "dibs-send:$uid"
            val ids = JSONArray()
            try {
                val node = Core.acquire(hold)
                // One file that fails (gone, unreadable) doesn't stop the others.
                for (uri in files) {
                    try {
                        // Picked files are copied into outgoing already (`pickDir`); anything else is copied now.
                        val f = uri.path?.let(::File)?.takeIf { uri.scheme == "file" && it.startsWith(Core.outgoingDir) }
                            ?: withContext(Dispatchers.IO) { Outgoing.copyIn(app, uri) }
                        val m = node.sendChannelFile(Channels.DIBS, f.path)
                        Transfers.sentToChannel(m.id, f.name)
                        withContext(Dispatchers.IO) { Thumbs.save(app, m.id, f) }
                        withContext(Dispatchers.Main) { onFile(uri, m.id) }
                        ids.put(m.id)
                    } catch (e: Exception) {
                        Log.w("Tether", "a file for dibs couldn't be sent", e)
                    }
                }
                // Keeps the link up past this screen until the files are through.
                if (ids.length() > 0) SyncWorker.start(app, "dibs")
            } catch (e: Exception) {
                Log.w("Tether", "sending files to dibs failed", e)
            } finally {
                Core.release(hold)
            }
            // The words go even when a file couldn't (it was gone, say); the files that made it go along.
            if (text.isNotEmpty() || ids.length() > 0) {
                val value = if (extra != null) JSONObject(extra.toString()) else JSONObject()
                act(action, value.put("text", text).put("files", ids), uid)
            } else {
                withContext(Dispatchers.Main) { Dibs.dropPending(uid) }
            }
        }
    }

    override fun note(key: String): String? = app.getSharedPreferences(NOTES, Context.MODE_PRIVATE).getString(key, null)

    override fun keepNote(key: String, value: String) {
        app.getSharedPreferences(NOTES, Context.MODE_PRIVATE).edit { putString(key, value) }
    }

    override fun thumb(fileId: String): ImageBitmap? = Thumbs.byId(app, fileId)

    override val thumbs: StateFlow<Int> = Thumbs.version

    /** A file dibs sent is kept in its channel folder: decode that. The user's own copies are gone once sent. */
    override suspend fun image(fileId: String, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = Core.withNode { n -> runCatching { n.appFiles(Channels.DIBS, 500u) }.getOrNull() }
            ?.firstOrNull { it.id == fileId && !it.fromMe }?.path
        val f = path?.let(::File)?.takeIf { it.isFile } ?: return@withContext thumb(fileId)
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                val s = info.size
                val scale = min(1f, maxPx.toFloat() / max(s.width, s.height))
                d.setTargetSize(max(1, (s.width * scale).toInt()), max(1, (s.height * scale).toInt()))
            }.asImageBitmap()
        }.getOrNull() ?: thumb(fileId)
    }

    /**
     * The newest received dibs file named [prefix]…, looked up again whenever a channel file
     * arrives. The core's listing knows each file's name as dibs sent it (the kept file may carry a
     * suffix); with no node running, the folder it keeps them in is read instead.
     */
    override fun channelFile(prefix: String): Flow<File?> =
        Core.channelFiles.map { newest(prefix) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun newest(prefix: String): File? {
        val listed = Core.withNode { n -> runCatching { n.appFiles(Channels.DIBS, 500u) }.getOrNull() }
            ?: return dibsDir().listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.maxByOrNull { it.lastModified() }
        return listed.asSequence()
            .filter { !it.fromMe && it.state == MsgState.RECEIVED && it.fileName?.startsWith(prefix) == true }
            .mapNotNull { m -> m.path?.let(::File)?.takeIf { it.isFile }?.let { m.tsMs to it } }
            .maxByOrNull { it.first }?.second
    }

    private fun dibsDir() = Core.channelDir(Channels.DIBS)

    /**
     * Keeps the newest transcript, report and full story per task and drops the rest; the newest goes too
     * after 14 days, or 7 days after its task was ticked off (while the view still lists it). A
     * note's loaded transcript the same: the newest per note, for 14 days.
     */
    private fun prune() {
        val files = dibsDir().listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        val ticked = HashMap<Long, Long>()
        Channels.views.value[Channels.DIBS]?.optJSONObject("dibs")?.optJSONArray("yours")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                if (t.has("ticked") && !t.isNull("ticked")) ticked[t.optLong("id")] = t.optLong("ticked")
            }
        }
        for ((key, list) in files.groupBy { fetchedOf(it.name) }) {
            if (key == null) continue
            val sorted = list.sortedByDescending { it.lastModified() }
            val newest = sorted.first()
            val tick = ticked[key.second]
            val stale = now - newest.lastModified() > KEEP_MS || (tick != null && now / 1000 - tick > TICKED_KEEP_S)
            for (f in if (stale) sorted else sorted.drop(1)) {
                if (!f.delete()) Log.w("Tether", "couldn't prune ${f.name}")
            }
        }
        // The index of tasks and ideas: only the newest.
        files.filter { it.name.startsWith("index-") }.sortedByDescending { it.lastModified() }.drop(1).forEach { if (!it.delete()) Log.w("Tether", "couldn't prune ${it.name}") }
        for ((note, list) in files.groupBy { ideaFileOf(it.name) }) {
            if (note == null) continue
            val sorted = list.sortedByDescending { it.lastModified() }
            val stale = now - sorted.first().lastModified() > KEEP_MS
            for (f in if (stale) sorted else sorted.drop(1)) {
                if (!f.delete()) Log.w("Tether", "couldn't prune ${f.name}")
            }
        }
    }

    init {
        // At start, and each time dibs sends a file.
        Core.scope.launch(Dispatchers.IO) { Core.channelFiles.collect { runCatching { prune() } } }
    }

    // Like MainActivity on screen: the link stays open, and what dibs posts is read, not notified.
    override fun visible(on: Boolean) {
        shown = on
        Channels.dibsShowing(on)
        if (on) {
            Core.scope.launch {
                val node = Core.acquire(Core.DIBS_UI)
                // The screen left while this waited (its release came first and found nothing):
                // let go, or the link would be held for good.
                if (!shown) return@launch Core.release(Core.DIBS_UI)
                if (runCatching { node.status().peer }.getOrNull() != null) Core.connect()
            }
        } else {
            Core.release(Core.DIBS_UI)
        }
    }

    @Volatile private var shown = false

    override fun openTether(classic: Boolean) {
        val intent = if (classic) {
            MainActivity.classic(app, Channels.DIBS)
        } else {
            // Tether's own screens: the channel list, not the dibs channel it may have been left on.
            if (Channels.open.value == Channels.DIBS) Channels.show(null)
            Intent(app, MainActivity::class.java)
        }
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** A fetched transcript, report or full story is kept at most this long. */
private const val KEEP_MS = 14 * 24 * 3600_000L
/** And this long after its task was ticked off. */
private const val TICKED_KEEP_S = 7 * 24 * 3600L

/** The SharedPreferences file for the dibs screens' small notes ([DibsHost.note]). */
private const val NOTES = "dibs-notes"
