package com.kivan.jarvis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Files to send are copied into the app first: a content URI grant doesn't outlive the screen that
 * received it, and a queued file must stay readable until the laptop acks it (Core deletes the
 * copy then).
 */
object Outgoing {
    /** Copies into outgoing/<uuid>/<display name>, keeping the name the laptop will see. */
    fun copyIn(context: Context, uri: Uri): File {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val dir = File(Core.outgoingDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val dst = File(dir, name.replace('/', '_'))
        resolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
        return dst
    }
}
