package com.kivan.tether

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kivan.tether.core.forumStart
import com.kivan.tether.core.forumStatus

/**
 * The forum on Jarvis's server, at localhost:8080 on the phone: the Rust tunnel (crates/ffi
 * forum.rs) carries it over iroh to the forum's gate, which lets in only this phone's key.
 */
object Forum {
    const val URL = "http://localhost:8080"
    private const val PORT: UShort = 8080u

    /** The gate's dumbpipe ticket on dibs (`forum-iroh.service`, fixed key, so it never changes). */
    private const val TICKET =
        "endpointabpo2j2svsu24ydkn4q7kev2yy72j3yf4pleum4v7as6itk73wq3gbqaenuhi5dqom5c6l3fovrtcljrfzzgk3dbpexg4mbonfzg62bonruw42zof4aqblaraaa6ryycaealsa4sogmguaiaycuacz7i4mbacajkadbicfi424aroseny576gkzuq6iqeaibfiamfaivgp4acjjc2dddnkesygdzcaq"

    fun start() = forumStart(TICKET, PORT, Voice.PORT, BuildConfig.FORUM_SECRET)

    /** "starting", "on", "on (last error: …)", or why it's down. */
    fun status(): String = forumStatus()

    /** Opens the forum in Chrome, where it can be added to the home screen as an app. */
    fun open(context: Context) = context.startActivity(view(context, "/"))

    /** The intent that opens the forum at [path] (`/t/42`), in Chrome when it's there. */
    fun view(context: Context, path: String): Intent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(URL + path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chrome = Intent(view).setPackage("com.android.chrome")
        return if (chrome.resolveActivity(context.packageManager) != null) chrome else view
    }
}
