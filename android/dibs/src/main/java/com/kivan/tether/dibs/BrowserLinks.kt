package com.kivan.tether.dibs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/** True for claude.ai and claude.com addresses (and their subdomains), which Android would hand to the Claude app. */
fun isClaudeLink(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
    return listOf("claude.ai", "claude.com").any { host == it || host.endsWith(".$it") }
}

/**
 * Links to Claude web pages open in the default browser, never in the claude.ai app (which has a verified
 * app link for them); every other link keeps Compose's plain ACTION_VIEW. Throws when nothing can open the
 * address, as the default handler does (the Waiting and Recap buttons fall back to the task page on that).
 */
private class BrowserLinks(private val context: Context, private val plain: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        if (!isClaudeLink(uri)) return plain.openUri(uri)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setSelector(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            plain.openUri(uri)
        }
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Wraps [content] so the links in it open as [BrowserLinks] says. */
@Composable
fun WithBrowserLinks(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val plain = LocalUriHandler.current
    val handler = remember(context, plain) { BrowserLinks(context, plain) }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}
