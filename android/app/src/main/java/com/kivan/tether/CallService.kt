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
 * 20 ms frames. It is reopened on the new route whenever the call's audio device changes: an
 * AudioRecord opened before the AirPods' call link came up stays on the phone's own mic, which goes
 * silent while Bluetooth carries the call (2026-10-28, Aviv heard by nothing once the AirPods joined); the speaker thread pulls 20 ms at a time from the Rust jitter buffer, and its
 * blocking writes pace it. The earcons come mixed into the same stream.
 */
class CallService : Service() {
    // Per call: a redial can land on this same service before Android has destroyed it.
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var call: VoiceCall? = null
    private var over = CompletableDeferred<Unit>()
    @Volatile private var flushTrack = false
    @Volatile private var running = false
    private var redial = false
    private var net: ConnectivityManager.NetworkCallback? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var telecom = false
    /** Muted by another call taking the line, not by Aviv. */
    @Volatile private var heldMute = false
    private val routes by lazy { AudioRoutes(this) }
    /** When this call started: its pieces in [CallLog] share it. */
    private var started = 0L
    /** The notification's last text, so a state that reads the same isn't posted again. */
    private var shown: Pair<String, Boolean>? = null
    /** The call's audio device changed: the mic reopens on it. */
    @Volatile private var reroute = false
    private var routeWatch: AudioManager.OnCommunicationDeviceChangedListener? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HANGUP -> call?.hangup() ?: stopSelf()
            MUTE -> call?.let { heldMute = false; setMuted(!Voice.ui.value.muted) }
            ROUTE -> if (call != null) routes.next()
            PAUSE -> call?.let { setPaused(!Voice.ui.value.paused) }
            // A redial while the last call's ended earcon still plays starts once that one is gone.
            else -> if (call == null) begin() else if (!running) redial = true
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        over = CompletableDeferred()
        heldMute = false
        started = System.currentTimeMillis()
        shown = null
        val c = VoiceCall(BuildConfig.VOICE_URL, Listener())
        call = c
        running = true
        locks()
        watchNetwork(c)
        watchRoute()
        scope.launch { telecom(c) }
        Thread(::speaker, "jarvis-speaker").apply { priority = Thread.MAX_PRIORITY }.start()
        Thread(::mic, "jarvis-mic").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    private fun setMuted(on: Boolean) {
        call?.setMuted(on)
        Voice.update(this) { it.copy(muted = on) }
        notify(Voice.ui.value.state)
    }

    /** Shown at once; the server's `paused` then keeps it true (it may say there was nothing to pause). */
    private fun setPaused(on: Boolean) {
        Voice.update(this) { it.copy(paused = on) }
        call?.setPaused(on)
        notify(Voice.ui.value.state)
    }

    private inner class Listener : VoiceListener {
        override fun onState(state: CallState) {
            Log.i(TAG, "call: $state")
            val up = state is CallState.Listening || state is CallState.Thinking || state is CallState.Speaking
            Voice.update(this@CallService) {
                it.copy(
                    state = state,
                    connectedAt = it.connectedAt ?: if (up) System.currentTimeMillis() else null,
                    paused = it.paused && state !is CallState.Ended,
                )
            }
            if (state is CallState.Ended) running = false else notify(state)
        }

        override fun onText(who: String, text: String) {
            CallLog.add(this@CallService, started, who, text)
        }

        override fun onError(text: String) {
            Log.w(TAG, "call error: $text")
            Voice.update(this@CallService) { it.copy(error = text) }
        }

        override fun onFlush() {
            flushTrack = true
        }

        override fun onPaused(on: Boolean) {
            Log.i(TAG, "call: paused $on")
            Voice.update(this@CallService) { it.copy(paused = on) }
            notify(Voice.ui.value.state)
        }
    }

    /** The speaker: until the call is over and its last earcon has played. Then the service ends. */
    private fun speaker() {
        val c = call ?: return
        val (over, scope) = over to scope
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

    /** The mic, reopened when the route changes and brought back if it fails (another app grabbed
     *  it); a call that can't hear Aviv after three failures in a row hangs up rather than leave him
     *  talking to nobody. */
    private fun mic() {
        val c = call ?: return
        var failures = 0
        while (running) {
            when (micOnce(c)) {
                Mic.OVER -> return
                Mic.REROUTE -> failures = 0
                Mic.FAILED -> {
                    if (++failures >= 3) break
                    Log.w(TAG, "mic failed, try $failures")
                    Thread.sleep(500)
                }
            }
        }
        if (running) {
            Voice.update(this) { it.copy(error = "the microphone stopped") }
            c.hangup()
        }
    }

    private enum class Mic { OVER, REROUTE, FAILED }

    private var lastWatchReopen = Long.MIN_VALUE / 2

    private fun micOnce(c: VoiceCall): Mic {
        reroute = false
        val am = getSystemService(AudioManager::class.java)
        // On the call's own device: the AirPods' mic while they carry the call, else the phone's.
        // The headset's input can show up a moment after the call moved to it.
        var want = micFor(am)
        for (i in 1..5) {
            if (want != null || !headset(am.communicationDevice)) break
            Thread.sleep(200)
            want = micFor(am)
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4),
            )
        } catch (e: Exception) {
            Log.w(TAG, "mic", e)
            return Mic.FAILED
        }
        if (!rec.setPreferredDevice(want)) Log.w(TAG, "mic: ${name(want)} refused as the preferred input")
        // VOICE_COMMUNICATION already cancels echo on most phones; asking again costs nothing.
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
        aec?.enabled = true
        ns?.enabled = true
        try {
            rec.startRecording()
            Log.i(TAG, "mic open: wanted ${name(want)}, on ${name(rec.routedDevice)}, call on ${name(am.communicationDevice)}")
            val buf = ByteArray(FRAME_BYTES)
            var frames = 0L
            var peak = 0
            var zeros = 0
            val t0 = System.nanoTime()
            while (running && !reroute) {
                var got = 0
                while (got < FRAME_BYTES && running) {
                    val n = rec.read(buf, got, FRAME_BYTES - got)
                    if (n < 0) throw IllegalStateException("read $n")
                    got += n
                }
                if (got != FRAME_BYTES) continue
                c.sendAudio(buf.copyOf())
                val p = peakOf(buf)
                peak = maxOf(peak, p)
                // A mic left on a device that went quiet gives exact zeros; a live one never does
                // for 2 s. So does one that isn't on the headset it was asked for after a second.
                // Reopened at most every 10 s (a mute by the system zeroes it too).
                zeros = if (p == 0) zeros + 1 else 0
                val astray = ++frames % 50 == 0L && want != null && rec.routedDevice?.id != want.id
                if ((astray || zeros >= 100 && !Voice.ui.value.muted) && System.nanoTime() - lastWatchReopen > 10_000_000_000L) {
                    lastWatchReopen = System.nanoTime()
                    Log.w(TAG, "mic: ${if (astray) "not on ${name(want)}" else "2 s of digital silence"} on ${name(rec.routedDevice)}; reopening")
                    return Mic.REROUTE
                }
                if (frames % 500 == 0L) {
                    Log.i(TAG, "mic: $frames frames in ${(System.nanoTime() - t0) / 1_000_000} ms, peak $peak, on ${name(rec.routedDevice)}")
                    peak = 0
                }
            }
            if (!running) return Mic.OVER
            Log.i(TAG, "mic: route changed to ${name(am.communicationDevice)}; reopening")
            return Mic.REROUTE
        } catch (e: Exception) {
            Log.w(TAG, "mic", e)
            return Mic.FAILED
        } finally {
            runCatching { rec.stop() }
            aec?.release()
            ns?.release()
            rec.release()
        }
    }

    /** The input that goes with the call's output device: a headset's own mic, or null (the
     *  system's choice, the phone's mics) for the earpiece and the speaker. */
    private fun micFor(am: AudioManager): AudioDeviceInfo? {
        val out = am.communicationDevice?.takeIf(::headset) ?: return null
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == out.type }
        return ins.firstOrNull { it.address == out.address } ?: ins.firstOrNull()
    }

    /** An output whose input of the same type is its own mic. */
    private fun headset(d: AudioDeviceInfo?) = d != null && d.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    /** Telecom (or [routeByHand]) moving the call to another device: the mic follows. */
    private fun watchRoute() {
        val am = getSystemService(AudioManager::class.java)
        val l = AudioManager.OnCommunicationDeviceChangedListener {
            Log.i(TAG, "call device: ${name(it)}")
            reroute = true
        }
        am.addOnCommunicationDeviceChangedListener(mainExecutor, l)
        routeWatch = l
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
                // The system's own mute (a headset, the call panel) is Aviv's.
                launch { isMuted.drop(1).collect { heldMute = false; setMuted(it) } }
                launch { routes.follow(this@addCall) }
                val over = over
                launch {
                    over.await()
                    disconnect(DisconnectCause(DisconnectCause.LOCAL))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "telecom refused the call; routing by hand", e)
            if (!telecom) { routeByHand(); routes.followByHand() }
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
        routeWatch?.let { runCatching { getSystemService(AudioManager::class.java).removeOnCommunicationDeviceChangedListener(it) } }
        routeWatch = null
        if (routed) {
            val am = getSystemService(AudioManager::class.java)
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
            routed = false
        }
        telecom = false
        routes.release()
        val scope = scope
        scope.launch {
            // Telecom's disconnect is launched by `over`; give it a moment before the scope goes.
            withTimeoutOrNull(2000) { kotlinx.coroutines.delay(500) }
            scope.cancel()
        }
        if (redial) {
            redial = false
            begin()
            return
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
        val muted = Voice.ui.value.muted
        if (key(state, muted) == shown) return
        getSystemService(android.app.NotificationManager::class.java).notify(ID, notification(state, muted))
    }

    private fun label(state: CallState, muted: Boolean) =
        Voice.label(Voice.ui.value.copy(state = state)) + if (muted) " · muted" else ""

    /** What the notification shows: its text, and whether it has the pause action. */
    private fun key(state: CallState, muted: Boolean) = label(state, muted) to Voice.ui.value.copy(state = state).pausable

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
        // From the lock screen mid-workout: Jarvis stops and continues without unlocking.
        val ui = Voice.ui.value.copy(state = state)
        val pause = if (ui.pausable) Notification.Action.Builder(
            Icon.createWithResource(this, if (ui.paused) R.drawable.lucide_play else R.drawable.lucide_pause),
            if (ui.paused) "Continue" else "Pause",
            pending(PAUSE, 3),
        ).build() else null
        return Notification.Builder(this, Notifier.CALLS)
            .setSmallIcon(R.drawable.lucide_phone)
            .setContentText(label(state, muted).also { shown = key(state, muted) })
            // Updated at every turn: only the first post may alert, or each one can wake the screen.
            .setOnlyAlertOnce(true)
            // The call's length from when it connected; without this each update started it again.
            .apply { Voice.ui.value.connectedAt?.let { setWhen(it).setShowWhen(true).setUsesChronometer(true) } }
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setStyle(Notification.CallStyle.forOngoingCall(jarvis, pending(HANGUP, 1)))
            .apply { pause?.let { addAction(it) } }
            .addAction(mute)
            .build()
    }

    companion object {
        const val HANGUP = "hangup"
        const val MUTE = "mute"
        const val ROUTE = "route"
        const val PAUSE = "pause"
        private const val TAG = "TetherCall"
        private const val ID = 20
        private const val RATE = 16_000
        private const val FRAME_SAMPLES = 320
        private const val FRAME_BYTES = FRAME_SAMPLES * 2

        private fun name(d: AudioDeviceInfo?) = d?.let { "${it.productName} (type ${it.type})" } ?: "default"

        private fun peakOf(pcm: ByteArray): Int {
            var m = 0
            for (i in pcm.indices step 2) {
                val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                m = maxOf(m, kotlin.math.abs(v.toShort().toInt()))
            }
            return m
        }

        fun micGranted(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
