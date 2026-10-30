package com.kivan.tether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aviv's tracked tasks (jarvis tasks/, one file each on the server): plain GETs on the voice port, as [Brief].
 * Each list read also says the build this app runs: a task counts as done once that build has its work,
 * so an update reports itself ([report]). A debug build is the emulator, and its word moves nothing.
 */
object Tasks {
    class Task(val name: String, val slug: String, val done: Boolean)
    class Detail(val name: String, val slug: String, val done: Boolean, val text: String)

    private val base = BuildConfig.VOICE_URL.replace("ws://", "http://").removeSuffix("/call")
    private val build = "app=${BuildConfig.VERSION_CODE}" + if (BuildConfig.DEBUG) "&emu=1" else ""

    private fun get(path: String): JSONObject? = runCatching {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) return@runCatching null
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** The list, or null while jarvis-voice doesn't answer. */
    suspend fun list(): List<Task>? = withContext(Dispatchers.IO) {
        get("/tasks?$build")?.getJSONArray("tasks")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Task(it.getString("name"), it.getString("slug"), it.optString("status") == "done") }
        }
    }

    suspend fun read(slug: String): Detail? = withContext(Dispatchers.IO) {
        get("/task/$slug")?.let { Detail(it.getString("name"), it.getString("slug"), it.optString("status") == "done", it.optString("text")) }
    }

    /** Started with the process, so after an update too: tries until the server has heard, backing off. */
    fun report(scope: CoroutineScope) = scope.launch {
        var wait = 10_000L
        while (list() == null) {
            delay(wait)
            wait = (wait * 2).coerceAtMost(600_000L)
        }
    }
}
