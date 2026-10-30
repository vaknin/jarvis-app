package com.kivan.jarvis.forum

import android.content.Context
import com.kivan.jarvis.Forum
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Requests to the forum through the tunnel, with a cookie jar of their own. The app's background side
 * (notifications, a reply from one) uses its own forum session, not the WebView's: Discourse rotates a
 * session's token every ten minutes, and two clients sharing one would knock each other out. A cookie
 * session also has no daily request cap, where a User API key has 4000 a day and the bus alone spends
 * ~3500 (a poll every 25 s).
 */
class Http(val jar: MutableMap<String, String> = mutableMapOf(), private val saved: (() -> Unit)? = null) {
    class Response(val code: Int, val body: String, val location: String?) {
        val ok get() = code in 200..299
        fun json() = JSONObject(body)
    }

    private var csrf: String? = null

    fun get(path: String, timeoutMs: Int = 20_000) = request("GET", path, null, timeoutMs)

    /** A form POST; Rails wants the session's CSRF token, fetched once and again when it goes stale. */
    fun post(path: String, form: Map<String, String>, csrf: Boolean = true): Response {
        if (!csrf) return request("POST", path, form)
        val r = request("POST", path, form, token = token())
        if (r.code != 403 || !r.body.contains("BAD CSRF")) return r
        this.csrf = null
        return request("POST", path, form, token = token())
    }

    private fun token() = csrf ?: get("/session/csrf.json").let { r ->
        r.json().optString("csrf").ifEmpty { throw java.io.IOException("csrf ${r.code}: ${r.body.take(160)}") }
    }.also { csrf = it }

    fun request(method: String, path: String, form: Map<String, String>? = null, timeoutMs: Int = 20_000, token: String? = null): Response {
        val conn = URL("http://127.0.0.1:${Forum.PORT}$path").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("Host", Forum.HOST)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // An XHR doesn't count as Aviv being seen, so the app doesn't keep him "online" around the clock.
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Dont-Chunk", "true")
            synchronized(jar) {
                if (jar.isNotEmpty()) conn.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            token?.let { conn.setRequestProperty("X-CSRF-Token", it) }
            if (form != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().decodeToString() } ?: ""
            val cookies = conn.headerFields.filterKeys { it != null && it.equals("Set-Cookie", true) }.values.flatten()
            if (cookies.isNotEmpty()) {
                synchronized(jar) { cookies.forEach { take(jar, it) } }
                saved?.invoke()
            }
            return Response(code, body, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "Forum app (Android)"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** One Set-Cookie header into the jar; an empty or expired one takes the cookie out. */
        fun take(jar: MutableMap<String, String>, header: String) {
            val parts = header.split(';').map { it.trim() }
            val (name, value) = parts.first().split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val gone = value.isEmpty() || parts.any {
                it.equals("max-age=0", true) || (it.startsWith("expires=", true) && it.contains("1970"))
            }
            if (gone) jar.remove(name) else jar[name] = value
        }

        /** A Cookie header ("a=1; b=2"), as CookieManager gives it, as a jar. */
        fun parse(header: String?): MutableMap<String, String> =
            header.orEmpty().split(';').mapNotNull { p ->
                p.trim().split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] }
            }.toMap(mutableMapOf())
    }
}

/** The background side's own forum session, kept in the app's private storage (no backup). */
object Session {
    private const val PREFS = "session"

    fun load(context: Context): Http {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jar = Http.parse(prefs.getString("cookies", null))
        lateinit var http: Http
        http = Http(jar) { save(context, http.jar) }
        return http
    }

    fun save(context: Context, jar: Map<String, String>) {
        val s = synchronized(jar) { jar.entries.joinToString("; ") { "${it.key}=${it.value}" } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cookies", s).apply()
    }

    fun has(context: Context) = "_t" in Http.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cookies", null))

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
