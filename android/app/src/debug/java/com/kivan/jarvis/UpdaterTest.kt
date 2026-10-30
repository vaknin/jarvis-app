package com.kivan.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tests (debug builds only): installs an APK through [Updater]'s own path, the wait for a pause
 * included, so a test can time the gap an update leaves in a call. On the emulator:
 *
 *     adb push app-debug.apk /sdcard/Android/data/com.kivan.jarvis.debug/files/update.apk
 *     adb shell am broadcast -n com.kivan.jarvis.debug/com.kivan.jarvis.UpdaterTest
 *
 * (`--es path FILE` for another file the app can read.)
 */
class UpdaterTest : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val apk = intent.getStringExtra("path")?.let(::File) ?: File(context.getExternalFilesDir(null), "update.apk")
        if (!apk.isFile) {
            Log.w("Jarvis", "update test: no $apk")
            return
        }
        // Not held as an async broadcast: the pause can be longer than a receiver may run. The
        // process stays up anyway (the link service, the call).
        Core.scope.launch {
            runCatching {
                // Copied in first: the session reads it, and the pushed file may be replaced meanwhile.
                val copy = File(context.cacheDir, "update-test.apk")
                apk.copyTo(copy, overwrite = true)
                Log.i("Jarvis", "update test: installing $apk")
                Updater.installFile(context, copy)
            }.onFailure { Log.w("Jarvis", "update test failed", it) }
        }
    }
}
