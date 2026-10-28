package com.kivan.tether

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * What was said on calls with Jarvis, kept on the phone so the call screen can scroll back through
 * this call and the ones before. One JSON line per piece the server sends (`text` frames: Aviv's
 * utterance, Jarvis one sentence at a time), in arrival order; the screen joins a speaker's pieces
 * into one bubble. The newest [KEEP] pieces stay.
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
            _pieces.update { (it.orEmpty() + p).takeLast(KEEP) }
        }
    }

    private fun load(context: Context): List<Piece> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        val all = lines.mapNotNull { l ->
            runCatching { JSONObject(l).run { Piece(getLong("call"), getString("who"), getString("text"), getLong("at")) } }.getOrNull()
        }
        // Trimmed here, once per process, rather than on every write.
        if (lines.size > KEEP) f.writeText(all.takeLast(KEEP).joinToString("") { json(it) + "\n" })
        return all.takeLast(KEEP)
    }

    private fun json(p: Piece) =
        JSONObject().put("call", p.call).put("who", p.who).put("text", p.text).put("at", p.at).toString()

    private fun file(context: Context) = File(context.filesDir, "call-log.jsonl")
}
