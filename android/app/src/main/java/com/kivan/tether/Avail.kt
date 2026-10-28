package com.kivan.tether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * How Jarvis may reach Aviv off a call (jarvis voice/src/avail.rs): talking (at home it asks on the
 * speaker, away a notification opens a call), quiet (text only) or away. A plain GET on the voice
 * port, which the forum tunnel pipes to jarvis-voice.
 */
object Avail {
    val modes = listOf("talking", "quiet", "away")

    fun next(mode: String) = modes[(modes.indexOf(mode) + 1) % modes.size]

    fun label(mode: String) = when (mode) {
        "talking" -> "Talking"
        "quiet" -> "Quiet: text only"
        "away" -> "Away"
        else -> mode
    }

    /** The mode now, or after setting [set]; null when jarvis-voice can't be reached. */
    suspend fun get(set: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val q = set?.let { "?set=$it" } ?: ""
            val c = URL("http://127.0.0.1:${Voice.PORT}/avail$q").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 8000
            try {
                if (c.responseCode != 200) return@runCatching null
                JSONObject(c.inputStream.bufferedReader().readText()).getString("mode")
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
