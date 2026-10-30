package com.kivan.jarvis

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * How the old app reaches this one during the move ([Move]). Only an app signed with this app's key gets in:
 * the manifest's signature permission, and here too, as Android doesn't enforce a permission on [call].
 */
class MoveProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val pm = context!!.packageManager
        if (pm.checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("not signed with this app's key")
        }
        Move.awaitBoot()
        val out = Bundle()
        when (method) {
            "state" -> out.putString("state", Move.state.value.name.lowercase())
            "yield" -> Move.yieldLink().let { why ->
                out.putBoolean("ok", why == null)
                out.putString("why", why)
            }
            else -> throw IllegalArgumentException(method)
        }
        return out
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
