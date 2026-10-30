package com.kivan.jarvis

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * What was said on calls with Jarvis, kept on the phone so the call screen can scroll back through
 * this call and the ones before. One piece per turn: a `text` frame is the speaker's whole line so far
 * (Aviv's words since Jarvis last spoke, Jarvis's growing a sentence at a time), so a frame from the
 * same speaker as the call's last piece replaces it. The file gets one JSON line per frame, in arrival
 * order, and is folded the same way when loaded. The newest [KEEP] pieces stay.
 */
object CallLog {
    /** [call] is when the call started (ms), shared by its pieces; [who] is "aviv" or "jarvis". */
    data class Piece(val call: Long, val who: String, val text: String, val at: Long)

    private const val KEEP = 2000
    private val _pieces = MutableStateFlow<List<Piece>?>(null)

    /** Every piece kept, oldest first; loaded on first use. */
    fun pieces(context: Context): StateFlow<List<Piece>?> {
        if (_pieces.value == null) synchronized(this) { if (_pieces.value == null) _pieces.value = load(context) }
        return _pieces
    }

    fun add(context: Context, call: Long, who: String, text: String) {
        val p = Piece(call, who, text, System.currentTimeMillis())
        pieces(context)
        synchronized(this) {
            file(context).appendText(json(p) + "\n")
            _pieces.update { fold(it.orEmpty(), p).takeLast(KEEP) }
        }
    }

    /** [all] with [p] added: it replaces the last piece when that one is the same call and speaker (keeping its time). */
    fun fold(all: List<Piece>, p: Piece): List<Piece> {
        val last = all.lastOrNull()
        return if (last != null && last.call == p.call && last.who == p.who) all.dropLast(1) + p.copy(at = last.at) else all + p
    }

    private fun load(context: Context): List<Piece> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        val all = lines.mapNotNull { l ->
            runCatching { JSONObject(l).run { Piece(getLong("call"), getString("who"), getString("text"), getLong("at")) } }.getOrNull()
        }.fold(emptyList<Piece>(), ::fold)
        // Folded and trimmed here, once per process, rather than on every write.
        if (lines.size > all.size || all.size > KEEP) f.writeText(all.takeLast(KEEP).joinToString("") { json(it) + "\n" })
        return all.takeLast(KEEP)
    }

    private fun json(p: Piece) =
        JSONObject().put("call", p.call).put("who", p.who).put("text", p.text).put("at", p.at).toString()

    private fun file(context: Context) = File(context.filesDir, "call-log.jsonl")
}
