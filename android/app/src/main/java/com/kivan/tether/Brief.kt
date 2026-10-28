package com.kivan.tether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home's What's new from Jarvis (jarvis-voice `GET /brief`, needs-you first): a plain GET on the voice
 * port, which the forum tunnel pipes to jarvis-voice, as [Avail] does.
 */
object Brief {
    /** [topic]: a thread; 0 a direct chat message; null nothing to open. */
    class Item(val kind: String, val title: String, val text: String, val topic: Long?)

    /** The items, or null while jarvis-voice doesn't answer it (no endpoint yet, or no link). */
    suspend fun get(): List<Item>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://127.0.0.1:${Voice.PORT}/brief").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 8000
            try {
                if (c.responseCode != 200) return@runCatching null
                val items = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
                (0 until items.length()).map { items.getJSONObject(it) }.map {
                    Item(
                        it.optString("kind"), it.optString("title"), it.optString("text"),
                        if (it.isNull("topic")) null else it.optLong("topic"),
                    )
                }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
