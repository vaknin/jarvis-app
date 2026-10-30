package com.kivan.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kivan.jarvis.core.forumStart
import com.kivan.jarvis.core.forumStatus
import com.kivan.jarvis.forum.ForumActivity

/**
 * The forum on Jarvis's server, at localhost:8080 on the phone: the Rust tunnel (crates/ffi
 * forum.rs) carries it over iroh to the forum's gate, which lets in only this phone's key.
 */
object Forum {
    const val PORT: UShort = 8080u
    const val HOST_NAME = "jarvis.localhost"
    const val HOST = "$HOST_NAME:8080"
    const val URL = "http://$HOST"

    /** The gate's dumbpipe ticket on dibs (`forum-iroh.service`, fixed key, so it never changes). */
    private const val TICKET =
        "endpointabpo2j2svsu24ydkn4q7kev2yy72j3yf4pleum4v7as6itk73wq3gbqaenuhi5dqom5c6l3fovrtcljrfzzgk3dbpexg4mbonfzg62bonruw42zof4aqblaraaa6ryycaealsa4sogmguaiaycuacz7i4mbacajkadbicfi424aroseny576gkzuq6iqeaibfiamfaivgp4acjjc2dddnkesygdzcaq"

    fun ticket() = BuildConfig.TEST_TICKET.ifEmpty { TICKET }

    /** The one way the link starts; refused while the old app has it ([Move]). */
    fun start() {
        if (Move.off()) return
        forumStart(ticket(), PORT, Voice.PORT, BuildConfig.FORUM_SECRET)
    }

    /** "starting", "on", "on (last error: …)", or why it's down. */
    fun status(): String = forumStatus()

    /** Opens the forum in the app (ForumActivity, the WebView). */
    fun open(context: Context) = context.startActivity(view(context, "/"))

    /** The intent that opens the forum at [path] (`/t/42`) in the app. */
    fun view(context: Context, path: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(URL + path), context, ForumActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
