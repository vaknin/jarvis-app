package com.kivan.tether

import android.app.Application

class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.load(this)
        Notifier.channels(this)
        Push.init(this)
        Core.init(this)
        Shortcuts.publish(this)
        LinkService.start(this)
    }
}
