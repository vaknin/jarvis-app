package com.kivan.jarvis.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.util.UUID

/**
 * Pictures and files on their way into a tab: copied in while the grant lasts, kept until they're
 * sent. A photo is made a sensible size here, so a tap doesn't send 12 MB over the tunnel.
 */
internal object Tray {
    private const val DIR = "tabattach"
    private const val TAG = "JarvisTray"
    /** The tab server's own limit. */
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val LONGEST = 2560
    private const val PLAIN_BYTES = 2L * 1024 * 1024
    private const val KEEP_MS = 24 * 60 * 60 * 1000L
    private val READABLE = setOf("png", "jpg", "jpeg", "webp", "gif")

    fun picture(name: String) = name.substringAfterLast('.').lowercase() in PICTURES

    /** A copy of this, or null with a word to him if it can't be sent. */
    fun keep(app: Context, uri: Uri): Attached? = try {
        val name = nameOf(app, uri)
        val dir = File(File(app.cacheDir, DIR), UUID.randomUUID().toString()).also { it.mkdirs() }
        val copy = File(dir, name)
        app.contentResolver.openInputStream(uri)!!.use { from -> copy.outputStream().use { from.copyTo(it) } }
        val small = if (picture(name)) smaller(copy) else copy
        if (small.length() > MAX_BYTES) {
            dir.deleteRecursively()
            tell(app, "$name is too big to send")
            null
        } else {
            Attached(small.path, small.name, picture(small.name))
        }
    } catch (e: Exception) {
        Log.w(TAG, "can't keep $uri", e)
        tell(app, "Couldn't attach that")
        null
    }

    /** Copies nobody sent, once they're a day old. */
    fun prune(app: Context, kept: List<Attached>) {
        val old = System.currentTimeMillis() - KEEP_MS
        val holding = kept.mapNotNull { File(it.path).parent }.toSet()
        File(app.cacheDir, DIR).listFiles().orEmpty().filter { it.lastModified() < old && it.path !in holding }.forEach { it.deleteRecursively() }
    }

    private fun tell(app: Context, words: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(app, words, Toast.LENGTH_LONG).show() }

    private fun nameOf(app: Context, uri: Uri): String {
        val shown = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val name = (shown ?: uri.lastPathSegment.orEmpty()).substringAfterLast('/').replace('\\', '_').takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
        if ('.' in name) return name
        val ext = app.contentResolver.getType(uri)?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
        return if (ext == null) name else "$name.$ext"
    }

    /** Big photos shrink, and a kind Jarvis can't read turns into one he can. On any failure, the original. */
    private fun smaller(file: File): File = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val ext = file.extension.lowercase()
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        val must = ext !in READABLE
        val big = edge > LONGEST || file.length() > PLAIN_BYTES
        // A gif would lose its moving; it goes as it is.
        if (edge <= 0 || ext == "gif" || !(must || big)) file else redraw(file, edge)
    } catch (e: Exception) {
        Log.w(TAG, "keeping ${file.name} as it came", e)
        file
    }

    private fun redraw(file: File, edge: Int): File {
        var sample = 1
        while (edge / (sample * 2) >= LONGEST) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return file
        val at = LONGEST.toFloat() / maxOf(decoded.width, decoded.height)
        val turn = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val shape = Matrix().apply {
            if (at < 1f) postScale(at, at)
            postRotate(turn)
        }
        val drawn = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, shape, true)
        val out = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        val tmp = File(file.parentFile, ".shrinking")
        tmp.outputStream().use { drawn.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        decoded.recycle()
        if (drawn !== decoded) drawn.recycle()
        file.delete()
        tmp.renameTo(out)
        return out
    }
}
