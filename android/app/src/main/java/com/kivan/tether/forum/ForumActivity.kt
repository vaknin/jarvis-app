package com.kivan.tether.forum

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import com.kivan.tether.Forum
import com.kivan.tether.LinkService

/**
 * The forum itself, in a WebView at jarvis.localhost:8080 through the app's tunnel ([Forum]), so every theme and
 * plugin change on the server applies here as it does in a browser. Back goes back through the forum's
 * history, and at its start leaves the page alive in its own task; a file input opens the phone's picker,
 * and links off the forum open in the browser. One tap from home (MainActivity).
 */
class ForumActivity : Activity() {
    private lateinit var web: WebView
    private var upload: ValueCallback<Array<Uri>>? = null
    private var loaded = false // a forum page has loaded, so the forum's own router can take a path
    private var pendingJs: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(BACKGROUND)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " ForumApp"
            webViewClient = Client()
            webChromeClient = Chrome()
            setDownloadListener { url, _, _, _, _ -> outside(Uri.parse(url)) }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(web)
            // Edge to edge: the bars stay the forum's dark, and the page sits between them and above the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (!handle(intent) && savedInstanceState == null) web.loadUrl(Forum.URL)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        Watch.foreground = true
        LinkService.start(this) // here, not in onCreate: also brings back a service killed while the app was open
    }

    override fun onPause() {
        super.onPause()
        Watch.foreground = false
        CookieManager.getInstance().flush()
    }

    /**
     * A tapped notification (forum-app://open/KIND/ID/UPTO), a tap in home's What's new (forum-app://go/KIND/ID/0,
     * which leaves the notification be) or a forum link from elsewhere. A thread opens the
     * inbox at that thread's card, not the thread: Discourse reads a thread's posts as you look at them, and
     * opening a notification mustn't take a card off (thread #103). A chat opens its channel.
     */
    private fun handle(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme == "http" && data.host == Forum.HOST_NAME) {
            web.loadUrl(data.toString())
            return true
        }
        if (data.scheme != "forum-app" || data.host !in setOf("open", "go")) return false
        val s = data.pathSegments
        val id = s.getOrNull(1)?.toLongOrNull() ?: return false
        val upTo = s.getOrNull(2)?.toLongOrNull() ?: 0L
        if (data.host == "open") Watch.seen(this, s[0], id, upTo)
        if (s[0] == "c") go("/chat/c/-/$id", 0) else go("/inbox", id)
        intent.data = null
        return true
    }

    /** To `path` inside the running forum (no reload), then scrolls the inbox to `topic`'s card once it's drawn. */
    private fun go(path: String, topic: Long) {
        val js = """
            (function (path, topic) {
              try { Discourse.__container__.lookup("service:router").transitionTo(path); }
              catch (e) { if (location.pathname !== path) { location.assign(path); return; } }
              if (!topic) return;
              let n = 0;
              const t = setInterval(() => {
                const card = document.querySelector('.jarvis-inbox-slot[data-topic-id="' + topic + '"]');
                if (card || ++n > 60) {
                  clearInterval(t);
                  if (!card) return;
                  // Below the forum's sticky header, not under it.
                  const header = document.querySelector(".d-header-wrap, .d-header");
                  card.style.scrollMarginTop = ((header ? header.offsetHeight : 0) + 8) + "px";
                  card.scrollIntoView({ block: "start" });
                }
              }, 250);
            })("$path", $topic);
        """.trimIndent()
        if (loaded) {
            web.evaluateJavascript(js, null)
        } else {
            pendingJs = js
            web.loadUrl(Forum.URL + path)
        }
    }

    /** Opens a link off the forum in whatever the phone uses for it. */
    private fun outside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.host == Forum.HOST_NAME || uri.scheme == "about" || uri.scheme == "data") return false
            outside(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (Uri.parse(url).host == Forum.HOST_NAME) loaded = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (Uri.parse(url).host != Forum.HOST_NAME) return
            loaded = true
            CookieManager.getInstance().flush()
            pendingJs?.let { view.evaluateJavascript(it, null) }
            pendingJs = null
            Login.maybe(this@ForumActivity)
        }

        /** The tunnel isn't up yet (just started, or no network): a dark page that says so, and a retry. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val url = request.url.toString()
            val why = Forum.status().ifEmpty { "starting" }
            view.loadDataWithBaseURL(
                null,
                """<html><body style="background:#1a1a1a;color:#aaa;font:16px sans-serif;display:flex;
                   align-items:center;justify-content:center;height:90vh;text-align:center">
                   Connecting to the forum…<br><small>${android.text.Html.escapeHtml(why)}</small></body></html>""",
                "text/html", "utf-8", null,
            )
            view.postDelayed({ view.loadUrl(url) }, 2_000)
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            upload?.onReceiveValue(null)
            upload = callback
            val pick = params.createIntent()
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            return try {
                startActivityForResult(pick, PICK)
                true
            } catch (_: ActivityNotFoundException) {
                upload = null
                false
            }
        }
    }

    @Deprecated("Activity's own; no AndroidX here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK) return super.onActivityResult(requestCode, resultCode, data)
        val clips = data?.clipData
        val uris = when {
            resultCode != RESULT_OK -> null
            clips != null -> Array(clips.itemCount) { clips.getItemAt(it).uri }
            else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        upload?.onReceiveValue(uris)
        upload = null
    }

    companion object {
        private const val BACKGROUND = 0xFF1A1A1A.toInt() // the forum's dark palette, so nothing flashes white
        private const val PICK = 2

        /** Opens the forum at a thread's inbox card ([kind] "t") or a chat channel ("c"), marking nothing. */
        fun go(context: Context, kind: String, id: Long): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("forum-app://go/$kind/$id/0"), context, ForumActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
