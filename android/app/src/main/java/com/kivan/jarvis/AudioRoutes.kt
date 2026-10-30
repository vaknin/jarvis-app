package com.kivan.jarvis

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Where the call sounds: the earpiece, the loudspeaker, a Bluetooth or a wired headset. */
data class AudioRoute(val kind: Kind, val name: String) {
    enum class Kind { Earpiece, Speaker, Bluetooth, Wired }
}

/**
 * The route button, as a phone's own call screen has it: each tap moves the call to the next place
 * it can sound, and the screen shows where it is. Under Telecom the route is Telecom's, so a change
 * goes through it (it would put its own choice back over AudioManager's); routed by hand, it is
 * AudioManager's communication device.
 */
class AudioRoutes(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    @Volatile private var telecom: CallControlScope? = null
    @Volatile private var endpoints: List<CallEndpointCompat> = emptyList()
    @Volatile private var current: CallEndpointCompat? = null
    private var byHand = false
    private val main = Handler(Looper.getMainLooper())

    /** Follows Telecom's endpoints for this call, until the call's scope ends. */
    suspend fun follow(call: CallControlScope) {
        telecom = call
        call.availableEndpoints.combine(call.currentCallEndpoint) { all, now -> all to now }.collect { (all, now) ->
            // A redial's call is the one followed now; this one's last words don't count.
            if (telecom !== call) return@collect
            endpoints = all
            current = now
            Log.i(TAG, "route: ${now.name} of ${all.map { it.name }}")
            publish(now.route(), all.mapNotNull { it.route() })
        }
    }

    /** Follows AudioManager's communication device, for a call Telecom refused. */
    fun followByHand() {
        if (byHand) return
        byHand = true
        am.addOnCommunicationDeviceChangedListener(context.mainExecutor, deviceChanged)
        am.registerAudioDeviceCallback(devicesChanged, main)
        publishByHand()
    }

    /** The tap: the next route along, wrapping round. One route only: nothing to do. */
    fun next() {
        val call = telecom
        if (call != null) {
            val all = endpoints.filter { it.route() != null }.sortedBy { it.route()!!.kind }
            val to = all.after(all.indexOfFirst { it.identifier == current?.identifier }) ?: return
            call.launch { Log.i(TAG, "route to ${to.name}: ${call.requestEndpointChange(to)}") }
        } else if (byHand) {
            val all = handDevices()
            val to = all.after(all.indexOfFirst { it.id == am.communicationDevice?.id }) ?: return
            Log.i(TAG, "route to ${to.productName} (${to.type}): ${am.setCommunicationDevice(to)}")
        }
    }

    fun release() {
        telecom = null
        endpoints = emptyList()
        current = null
        if (byHand) {
            byHand = false
            am.removeOnCommunicationDeviceChangedListener(deviceChanged)
            am.unregisterAudioDeviceCallback(devicesChanged)
        }
    }

    private val deviceChanged = AudioManager.OnCommunicationDeviceChangedListener { publishByHand() }

    private val devicesChanged = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = publishByHand()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = publishByHand()
    }

    private fun publishByHand() {
        if (byHand) publish(am.communicationDevice?.route(), handDevices().mapNotNull { it.route() })
    }

    private fun handDevices() = am.availableCommunicationDevices.filter { it.route() != null }.sortedBy { it.route()!!.kind }

    private fun publish(route: AudioRoute?, routes: List<AudioRoute>) {
        Voice.update(context) { it.copy(route = route, routes = routes.sortedBy { r -> r.kind }) }
    }

    private fun <T> List<T>.after(i: Int): T? = if (size < 2) null else this[(i + 1).mod(size)]

    private fun CallEndpointCompat.route(): AudioRoute? = when (type) {
        CallEndpointCompat.TYPE_EARPIECE -> AudioRoute(AudioRoute.Kind.Earpiece, "Earpiece")
        CallEndpointCompat.TYPE_SPEAKER -> AudioRoute(AudioRoute.Kind.Speaker, "Speaker")
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute(AudioRoute.Kind.Bluetooth, name.toString().ifBlank { "Bluetooth" })
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute(AudioRoute.Kind.Wired, "Headset")
        else -> null
    }

    private fun AudioDeviceInfo.route(): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute(AudioRoute.Kind.Earpiece, "Earpiece")
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute(AudioRoute.Kind.Speaker, "Speaker")
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> AudioRoute(AudioRoute.Kind.Bluetooth, productName.toString().ifBlank { "Bluetooth" })
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute(AudioRoute.Kind.Wired, "Headset")
        else -> null
    }

    private companion object {
        const val TAG = "JarvisCall"
    }
}
