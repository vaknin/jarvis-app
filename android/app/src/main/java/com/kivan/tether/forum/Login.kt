package com.kivan.tether.forum

import android.content.Context
import com.kivan.tether.Forum
import android.util.Base64
import android.webkit.CookieManager
import java.net.URLDecoder
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/**
 * Gives the background side a forum session of its own, from the WebView's, with no taps: Discourse's
 * one-time-password flow (the one its own mobile app uses). The WebView's session asks for a one-time
 * password, encrypted to a key made here; a fresh cookie jar spends it and becomes a new session.
 * Aviv sees it, and can log it out, in Preferences > Security as "Forum app (Android)".
 */
object Login {
    private const val REDIRECT = "com.vaknin.forum://auth" // allowed in jarvis-settings.rb; read, never followed

    @Volatile private var running = false
    // After a failed try, the next waits (1 min, doubling to an hour), unless the WebView logs in afresh.
    @Volatile private var notBefore = 0L
    private var failures = 0
    private var lastToken: String? = null

    /**
     * When the WebView is logged in and the background has no session (first login, or its own was logged
     * out): from the WebView's page loads and from the watch loop; the work runs off the calling thread.
     */
    fun maybe(context: Context) {
        val app = context.applicationContext
        if (running || Session.has(app)) return
        val cookies = CookieManager.getInstance().getCookie(Forum.URL)
        val token = Http.parse(cookies)["_t"] ?: return
        synchronized(this) {
            if (token != lastToken) {
                lastToken = token
                failures = 0
                notBefore = 0L
            }
            if (running || System.currentTimeMillis() < notBefore) return
            running = true
        }
        Thread {
            val ok = try {
                fromWebView(app, cookies)
            } catch (e: Exception) {
                Watch.log("login: $e")
                false
            }
            synchronized(this) {
                failures = if (ok) 0 else failures + 1
                notBefore = if (ok) 0L else System.currentTimeMillis() + (60_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(3_600_000L)
                running = false
            }
            if (ok) Watch.kick()
        }.start()
    }

    private fun fromWebView(context: Context, cookies: String): Boolean {
        val web = Http(Http.parse(cookies))
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.encodeToString(keys.public.encoded, Base64.DEFAULT) + "-----END PUBLIC KEY-----\n"
        val asked = web.post(
            "/user-api-key/otp.json",
            mapOf("public_key" to pem, "auth_redirect" to REDIRECT, "application_name" to "Forum app"),
        )
        // Discourse may have rotated the WebView's token on that request: hand the new one back.
        val before = Http.parse(cookies)["_t"]
        web.jar["_t"]?.takeIf { it != before }?.let {
            CookieManager.getInstance().setCookie(Forum.URL, "_t=$it; path=/; Max-Age=5184000; HttpOnly; SameSite=Lax")
        }
        if (!asked.ok) throw IllegalStateException("otp ${asked.code}: ${asked.body.take(200)}")
        val query = asked.json().getString("redirect_url").substringAfter('?')
        val sealed = query.split('&').first { it.startsWith("oneTimePassword=") }.substringAfter('=')
        val otp = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, keys.private)
            doFinal(Base64.decode(URLDecoder.decode(sealed, "UTF-8"), Base64.DEFAULT)).decodeToString()
        }
        val own = Http()
        own.post("/session/otp/$otp", emptyMap())
        if ("_t" !in own.jar) return false
        Session.save(context, own.jar)
        Watch.log("login: the background has its own session")
        return true
    }
}
