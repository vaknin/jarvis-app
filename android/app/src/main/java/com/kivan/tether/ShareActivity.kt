package com.kivan.tether

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of the "Laptop" Direct Share tile and the share-sheet entry. Files are copied in
 * ([Outgoing]) and queued; text without a file becomes a chat message. The always-on node
 * delivers them as soon as the laptop is reachable.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) return finish()
        val app = applicationContext
        Core.scope.launch {
            val msg = try {
                // The read grant on the shared URIs ends with this screen: copy first, then close it.
                val files = try {
                    withContext(Dispatchers.IO) { uris.map { Outgoing.copyIn(app, it) } }
                } finally {
                    withContext(Dispatchers.Main) { finish() }
                }
                if (Core.start().status().peer == null) {
                    "Pair with the laptop first"
                } else {
                    files.forEach { Core.sendFile(it) }
                    if (files.isEmpty()) Core.sendText(text!!)
                    Shortcuts.used(app)
                    if (files.isEmpty()) "Sent to Laptop" else "Sending ${files.size} to Laptop"
                }
            } catch (e: Exception) {
                Log.w("Tether", "share failed", e)
                "Couldn't share: ${e.message}"
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // Only other apps' content URIs: Tether reads them with its own rights, so a file: URI or one of
    // Tether's own would let any app have Tether's private files (the paired key) sent off.
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.filter { it.scheme == "content" && it.authority?.startsWith(packageName) != true }
}
