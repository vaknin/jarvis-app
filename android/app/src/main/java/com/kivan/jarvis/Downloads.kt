package com.kivan.jarvis

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.kivan.jarvis.core.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Received files move from the app's private incoming dir into Downloads/Jarvis through
 * MediaStore (no storage permission needed for our own inserts). The resulting content URI is
 * remembered per message id so the chat can open it later.
 */
object Downloads {
    private const val PREFS = "downloads"

    fun uriFor(context: Context, id: String): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null)?.let(Uri::parse)

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    suspend fun publish(context: Context, m: ChatMessage) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(m.id)) return@withContext
        val src = m.path?.let(::File)?.takeIf { it.isFile } ?: return@withContext
        val name = m.fileName ?: src.name
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Jarvis")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.w("Jarvis", "MediaStore refused $name")
            return@withContext
        }
        try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.w("Jarvis", "saving $name failed", e)
            return@withContext
        }
        prefs.edit().putString(m.id, uri.toString()).apply()
        src.delete()
        if (Core.chatVisible) Core.markRead() else Notifier.file(context, m, uri)
    }
}
