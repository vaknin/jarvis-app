package com.kivan.tether

import android.app.Application
import android.util.Log
import com.kivan.tether.forum.Watch
import java.io.File

class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.load(this)
        Notifier.channels(this)
        Push.init(this)
        Core.init(this)
        Shortcuts.publish(this)
        background()
        LinkService.start(this)
        PhoneStt.check(this)
    }

    /**
     * What the process does for as long as it lives, whatever started it: Android can refuse
     * [LinkService] in the background, and a call or a tile can bring the process up without it. Before
     * the tunnel, so it's never up without someone to show Jarvis's notifications (thread #179).
     */
    private fun background() {
        // Tests: `run-as … touch files/no-alerts` keeps the listener away, to see what the gate hears.
        if (!BuildConfig.DEBUG || !File(filesDir, "no-alerts").exists()) Alerts.start(this)
        runCatching { Forum.start() }.onFailure { Log.w("Tether", "forum tunnel failed", it) }
        Watch.start(this)
        Updater.loop(this)
        Tasks.report(Core.scope)
    }
}
