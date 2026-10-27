package com.kivan.tether

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallsManager
import com.kivan.tether.core.CallState
import com.kivan.tether.core.VoiceCall
import com.kivan.tether.core.VoiceListener
import com.kivan.tether.core.forumNetworkChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One call with Jarvis, kept up with the screen locked (foreground service, types phoneCall and
 * microphone). It is a self-managed Telecom call (androidx core-telecom), so Android routes it like
 * a real call: AirPods switch to their call profile, a press on them hangs up, and another call
 * coming in puts it on hold. If Telecom refuses, the call still runs, with the audio routed by hand
 * (communication mode, the Bluetooth headset when there is one).
 *
 * Audio: the mic (VOICE_COMMUNICATION, so the phone cancels Jarvis's voice out of it) goes out in
 * 20 ms frames; the speaker thread pulls 20 ms at a time from the Rust jitter buffer, and its
 * blocking writes pace it. The earcons come mixed into the same stream.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var call: VoiceCall? = null
    private val over = CompletableDeferred<Unit>()
    @Volatile private var flushTrack = false
    @Volatile private var running = false
    private var net: ConnectivityManager.NetworkCallback? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var telecom = false
    /** Muted by another call taking the line, not by Aviv. */
    @Volatile private var heldMute = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HANGUP -> call?.hangup() ?: stopSelf()
            MUTE -> call?.let { setMuted(!Voice.ui.value.muted) }
            else -> if (call == null) begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (!micGranted(this)) {
            // Started with startForegroundService, so it must go foreground even to give up.
            startForeground(ID, notification(CallState.Connecting, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            Voice.update(this) { it.copy(state = CallState.Ended("no microphone permission")) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startForeground(
            ID, notification(CallState.Connecting, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        runCatching { Forum.start() }
        val c = VoiceCall(BuildConfig.VOICE_URL, Listener())
        call = c
        running = true
        locks()
        watchNetwork(c)
        scope.launch { telecom(c) }
        Thread(::speaker, "jarvis-speaker").apply { priority = Thread.MAX_PRIORITY }.start()
        Thread(::mic, "jarvis-mic").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    private fun setMuted(on: Boolean) {
        call?.setMuted(on)
        Voice.update(this) { it.copy(muted = on) }
        notify(Voice.ui.value.state)
    }

    private inner class Listener : VoiceListener {
        override fun onState(state: CallState) {
            Log.i(TAG, "call: $state")
            Voice.update(this@CallService) { it.copy(state = state) }
            if (state is CallState.Ended) running = false else notify(state)
        }

        override fun onText(who: String, text: String) {
            Voice.update(this@CallService) { if (who == "jarvis") it.copy(said = text) else it.copy(heard = text) }
        }

        override fun onError(text: String) {
            Log.w(TAG, "call error: $text")
            Voice.update(this@CallService) { it.copy(error = text) }
        }

        override fun onFlush() {
            flushTrack = true
        }
    }

    /** The speaker: until the call is over and its last earcon has played. Then the service ends. */
    private fun speaker() {
        val c = call ?: return
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            while (true) {
                val pcm = c.pullAudio(FRAME_SAMPLES.toUInt())
                if (pcm.isEmpty()) break
                if (flushTrack) {
                    // Jarvis was talked over: what the track still holds goes too.
                    flushTrack = false
                    track.pause()
                    track.flush()
                    track.play()
                }
                if (track.write(pcm, 0, pcm.size) < 0) break
            }
            // Let the last earcon drain out of the track before the route goes back.
            Thread.sleep(150)
        } catch (e: Exception) {
            Log.w(TAG, "speaker", e)
        } finally {
            runCatching { track.stop() }
            track.release()
            over.complete(Unit)
            scope.launch(Dispatchers.Main) { finish() }
        }
    }

    private fun mic() {
        val c = call ?: return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4),
            )
        } catch (e: Exception) {
            Log.w(TAG, "mic", e)
            Voice.update(this) { it.copy(error = "microphone: ${e.message}") }
            return
        }
        // VOICE_COMMUNICATION already cancels echo on most phones; asking again costs nothing.
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
        aec?.enabled = true
        ns?.enabled = true
        try {
            rec.startRecording()
            val buf = ByteArray(FRAME_BYTES)
            var frames = 0L
            val t0 = System.nanoTime()
            while (running) {
                var got = 0
                while (got < FRAME_BYTES && running) {
                    val n = rec.read(buf, got, FRAME_BYTES - got)
                    if (n < 0) throw IllegalStateException("read $n")
                    got += n
                }
                if (got == FRAME_BYTES) c.sendAudio(buf.copyOf())
                if (++frames % 500 == 0L) Log.i(TAG, "mic: $frames frames in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "mic", e)
            Voice.update(this) { it.copy(error = "microphone: ${e.message}") }
        } finally {
            runCatching { rec.stop() }
            aec?.release()
            ns?.release()
            rec.release()
        }
    }

    /** The call as Android's own call, so the headset and the system treat it as one. */
    private suspend fun telecom(c: VoiceCall) {
        try {
            val cm = CallsManager(this)
            cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            val attrs = CallAttributesCompat(
                displayName = "Jarvis",
                address = Uri.parse("jarvis:call"),
                direction = CallAttributesCompat.DIRECTION_OUTGOING,
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            )
            @Suppress("MissingPermission")
            cm.addCall(
                attrs,
                onAnswer = {},
                // The headset's button, or the system ending it.
                onDisconnect = { c.hangup() },
                // Back from the other call: unmuted again, unless Aviv had muted it himself.
                onSetActive = { if (heldMute) { heldMute = false; setMuted(false) } },
                // Another call came in and Aviv took it: Jarvis stops hearing him meanwhile.
                onSetInactive = { if (!Voice.ui.value.muted) { heldMute = true; setMuted(true) } },
            ) {
                telecom = true
                launch {
                    val r = setActive()
                    Log.i(TAG, "telecom active: $r")
                }
                launch { isMuted.drop(1).collect { setMuted(it) } }
                launch { currentCallEndpoint.collect { Log.i(TAG, "route: ${it.name}") } }
                launch {
                    over.await()
                    disconnect(DisconnectCause(DisconnectCause.LOCAL))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "telecom refused the call; routing by hand", e)
            if (!telecom) routeByHand()
        }
    }

    private var routed = false

    private fun routeByHand() {
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val headset = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        headset?.let { am.setCommunicationDevice(it) }
        routed = true
    }

    private fun watchNetwork(c: VoiceCall) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            var current: Network? = null
            override fun onAvailable(network: Network) {
                val changed = current != null && current != network
                current = network
                if (changed) {
                    Log.i(TAG, "network changed")
                    forumNetworkChanged()
                    c.networkChanged()
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        net = cb
    }

    private fun locks() {
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tether:call").apply { acquire(4 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tether:call").apply { acquire() }
    }

    private fun finish() {
        if (call == null) return
        call = null
        net?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        if (routed) {
            val am = getSystemService(AudioManager::class.java)
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        }
        scope.launch {
            // Telecom's disconnect is launched by `over`; give it a moment before the scope goes.
            withTimeoutOrNull(2000) { kotlinx.coroutines.delay(500) }
            scope.cancel()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        call?.hangup()
        super.onDestroy()
    }

    private fun notify(state: CallState?) {
        if (call == null || state == null || state is CallState.Ended) return
        getSystemService(android.app.NotificationManager::class.java).notify(ID, notification(state, Voice.ui.value.muted))
    }

    private fun notification(state: CallState, muted: Boolean): Notification {
        fun pending(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CallService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val jarvis = Person.Builder().setName("Jarvis").setImportant(true).build()
        val mute = Notification.Action.Builder(
            Icon.createWithResource(this, if (muted) R.drawable.lucide_mic else R.drawable.lucide_mic_off),
            if (muted) "Unmute" else "Mute",
            pending(MUTE, 2),
        ).build()
        return Notification.Builder(this, Notifier.CALLS)
            .setSmallIcon(R.drawable.lucide_phone)
            .setContentText(Voice.label(state) + if (muted) " · muted" else "")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setStyle(Notification.CallStyle.forOngoingCall(jarvis, pending(HANGUP, 1)))
            .addAction(mute)
            .build()
    }

    companion object {
        const val HANGUP = "hangup"
        const val MUTE = "mute"
        private const val TAG = "TetherCall"
        private const val ID = 20
        private const val RATE = 16_000
        private const val FRAME_SAMPLES = 320
        private const val FRAME_BYTES = FRAME_SAMPLES * 2

        fun micGranted(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
