package com.kivan.jarvis

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.kivan.jarvis.core.Sensed

/**
 * What the phone knows for the rule of `crates/voice/src/rules.rs`, which decides: the Lark, a headset
 * with a mic, and whether this is a call or dictation ([call], fixed for its life). [changed] runs on
 * the main thread when a device comes or goes. Nothing about the screen or music: being on a call
 * decides, and he chose that.
 */
class Senses(private val context: Context, private val call: Boolean, private val changed: (Sensed) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var last: Sensed? = null

    fun sensed(): Sensed {
        val fake = fake
        return Sensed(
            lark = fake?.lark ?: (lark(am) != null),
            airpods = fake?.airpods ?: am.availableCommunicationDevices.any(::headset),
            call = call,
            airpodsMic = AirPodsMic.mode(context).budsMic,
        )
    }

    fun start() {
        last = sensed()
        am.registerAudioDeviceCallback(devices, main)
        one = this
    }

    fun stop() {
        one = null
        main.removeCallbacksAndMessages(null)
        am.unregisterAudioDeviceCallback(devices)
    }

    private fun look() {
        val s = sensed()
        if (s == last) return
        last = s
        changed(s)
    }

    private fun headset(d: AudioDeviceInfo) = d.type in HEADSETS

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = again()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = again()

        // A headset's communication device can show up a moment after its other profiles.
        private fun again() {
            look()
            main.postDelayed(::look, DEVICE_SETTLE)
        }
    }

    /** Tests: stands in for what the emulator hasn't got. Null fields stay real. */
    data class Fake(val lark: Boolean? = null, val airpods: Boolean? = null)

    companion object {
        private const val DEVICE_SETTLE = 500L
        private val USB = listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        private val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        /** The Lark's receiver, as motoparty proved it: a two-channel USB input (`TalkMic.choose` there). */
        fun lark(am: AudioManager): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in USB && (it.channelCounts.isEmpty() || 2 in it.channelCounts) }
            .minWithOrNull(compareBy({ USB.indexOf(it.type) }, { it.id }))

        @Volatile var fake: Fake? = null
            set(f) {
                field = f
                one?.let { s -> s.main.post(s::look) }
            }

        /** Something the rule reads changed outside the devices (the AirPods mic setting): it looks again. */
        fun again() {
            one?.let { s -> s.main.post(s::look) }
        }

        @Volatile private var one: Senses? = null
    }
}
