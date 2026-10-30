package com.kivan.tether

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process

/**
 * How the new app reaches this one for the move ([Move]). Only an app signed with this app's key gets in:
 * the manifest's signature permission, and here too, as Android doesn't enforce a permission on [call].
 * A file's Uri is `content://…move/file/<path under filesDir>`; `/listing` and `/hashed` are [Move.listing]'s.
 */
class MoveProvider : ContentProvider() {
    override fun onCreate() = true

    private fun guard() {
        val pm = context!!.packageManager
        if (pm.checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("not signed with this app's key")
        }
        Move.awaitBoot()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        guard()
        val out = Bundle()
        when (method) {
            "state" -> {
                out.putInt("code", BuildConfig.VERSION_CODE)
                out.putString("state", if (Move.gone.value) "moved" else if (Move.held) "held" else "on")
                out.putBoolean("call", Voice.ui.value.live)
            }
            "pause" -> Move.pause().let { why ->
                out.putBoolean("ok", why == null)
                out.putString("why", why)
            }
            "renew" -> out.putBoolean("ok", Move.renew())
            "resume" -> Move.resume().also { out.putBoolean("ok", true) }
            "confirm" -> Move.confirm().also { out.putBoolean("ok", true) }
            else -> throw IllegalArgumentException(method)
        }
        return out
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        guard()
        require(mode == "r") { "read only" }
        val segments = uri.pathSegments
        val file = when (segments.firstOrNull()) {
            "listing" -> Move.listing(hashed = false)
            "hashed" -> Move.listing(hashed = true)
            "file" -> Move.file(segments.drop(1).joinToString("/")) ?: throw java.io.FileNotFoundException(uri.toString())
            else -> throw java.io.FileNotFoundException(uri.toString())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
