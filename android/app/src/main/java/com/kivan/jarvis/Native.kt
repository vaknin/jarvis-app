package com.kivan.jarvis

import android.content.Context

/** The one JNI call into the Rust library; everything else goes through uniffi (JNA). */
object Native {
    /** Loads the library through the JVM and hands iroh the application context. Call once, early. */
    fun load(context: Context) {
        System.loadLibrary("jarvis_ffi")
        init(context.applicationContext)
        // The first uniffi call loads JNA's types, here on the main thread before any service or worker can:
        // two threads doing it at once deadlock (FFIType's class init against Structure's layout lock), which
        // froze the app at start (LinkService's Core.start on a worker against Forum.start on main).
        com.kivan.jarvis.core.forumStatus()
    }

    private external fun init(context: Context)
}
