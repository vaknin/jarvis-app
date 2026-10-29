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
import kotlinx.coroutines.delay
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
 * silent while Bluetooth carries the call (2026-10-28, Aviv heard by nothing once the AirPods joined). A headset
 * whose own mic gives near-silence while the phone's mic hears speech is given up on: the phone's mic hears Aviv
 * while the headset still plays the call (2026-10-29, the AirPods' mic dead on their call link); the speaker thread pulls 20 ms at a time from the Rust jitter buffer, and its
 * blocking writes pace it. The earcons come mixed into the same stream.
 */
class CallService : Service() {
    // Per call: a redial can land on this same service before Android has destroyed it.
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var call: VoiceCall? = null
    private var over = CompletableDeferred<Unit>()
    @Volatile private var flushTrack = false
    @Volatile private var running = false
    private var redial: Intent? = null
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
    /** The headset whose mic went silent on this call: the phone's mic hears Aviv while it carries the call. */
    @Volatile private var deadMic: Int? = null
    /** The phone's mic, under that headset, refused as VOICE_COMMUNICATION's input: opened as a plain mic instead. */
    @Volatile private var plainMic = false
    private var routeWatch: AudioManager.OnCommunicationDeviceChangedListener? = null
    /** Held while the call's audio plays, so media keys come to Tether after it ([MediaKeys]). */
    private var keys: android.media.session.MediaSession? = null
    /** The phone's own recognizer hearing Aviv's turns (thread #118), when on-device en-US is installed. */
    private var stt: PhoneStt? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            HANGUP -> call?.hangup() ?: stopSelf()
            MUTE -> call?.let { heldMute = false; setMuted(!Voice.ui.value.muted) }
            ROUTE -> if (call != null) routes.next()
            PAUSE -> call?.let { setPaused(!Voice.ui.value.paused) }
            // A redial while the last call's ended earcon still plays starts once that one is gone.
            else -> if (call == null) begin(intent) else if (!running) redial = intent ?: Intent()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        // A dropped call to continue: the one Aviv answered, or the one this process died in.
        val rejoin = intent?.getStringExtra(REJOIN) ?: CallStore.active(this)
        val auto = intent?.getBooleanExtra(AUTO, false) == true
        if (!micGranted(this)) {
            // Started with startForegroundService, so it must go foreground even to give up.
            startForeground(ID, notification(CallState.Connecting, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            Voice.update(this) { it.copy(state = CallState.Ended("no microphone permission")) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        micLater = false
        try {
            startForeground(
                ID, notification(CallState.Connecting, false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Started from the background (the app restarting after an update): Android keeps the mic
            // from it until the call is Telecom's again, which lends the app its own call service with
            // the mic ([telecom] takes it then). Without even that, Jarvis rings and Aviv's answer
            // brings the call back.
            val ok = rejoin != null && runCatching {
                startForeground(ID, notification(CallState.Connecting, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            }.onFailure { Log.w(TAG, "the call can't start from here", it) }.isSuccess
            if (!ok) {
                Voice.update(this) { Voice.Ui() }
                stopSelf()
                if (rejoin != null) ringBack(this, rejoin)
                return
            }
            Log.i(TAG, "started in the background: the mic comes with the Telecom call (${e.message?.take(80)})")
            micLater = true
        }
        runCatching { Forum.start() }
        keys?.release()
        keys = MediaKeys.session(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        over = CompletableDeferred()
        heldMute = false
        deadMic = null
        plainMic = false
        started = System.currentTimeMillis()
        shown = null
        stt?.stop()
        stt = if (PhoneStt.available(this)) PhoneStt(this) else null
        val listener = Listener(stt, rejoin.takeIf { auto })
        val c = if (rejoin != null) {
            Log.i(TAG, "rejoining call $rejoin${if (auto) " (the app restarted)" else ""}")
            CallStore.joined(this, rejoin)
            VoiceCall.rejoin(BuildConfig.VOICE_URL, listener, rejoin, stt != null, !auto)
        } else {
            VoiceCall(BuildConfig.VOICE_URL, listener, stt != null)
        }
        stt?.call = c
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

    /** `resumed`: the call the app restarted into, which Aviv didn't ask for: one the server no longer has isn't started. */
    private inner class Listener(private val stt: PhoneStt?, private val resumed: String?) : VoiceListener {
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
            if (state is CallState.Ended) {
                CallStore.ended(this@CallService)
                running = false
            } else notify(state)
        }

        override fun onJoined(id: String) {
            if (resumed != null) {
                // The server didn't have it any more: that call is over, and nobody asked for a new one.
                Log.i(TAG, "call $resumed is over on the server; not starting $id")
                call?.hangup()
                return
            }
            CallStore.joined(this@CallService, id)
        }

        override fun onHangupOwed(id: String) {
            Log.i(TAG, "hangup for $id not confirmed; sent again later")
            CallStore.owe(this@CallService, id)
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

        override fun onSttOpen(id: ULong) {
            stt?.open(id.toLong())
        }

        override fun onSttAudio(id: ULong, pcm: ByteArray) {
            stt?.audio(id.toLong(), pcm)
        }

        override fun onSttClose(id: ULong) {
            stt?.close(id.toLong())
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
                if (peakOf(pcm) > 0) Voice.soundAt = android.os.SystemClock.elapsedRealtime()
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
        val fallback = want != null && want.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        val source = if (fallback && plainMic) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
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
        // Beside a headset's mic, the phone's (is that headset's mic dead?); beside the phone's mic standing in for a
        // dead headset's, the headset's (has it come back?).
        var probe: Probe? = null
        try {
            rec.startRecording()
            Log.i(TAG, "mic open: wanted ${name(want)}${if (source == MediaRecorder.AudioSource.MIC) " as a plain mic" else ""}, on ${name(rec.routedDevice)}, call on ${name(am.communicationDevice)}")
            if (fallback) probe = headsetMic(am)?.let { openProbe(it, BACK, "the headset's mic listens alongside, for it to come back") }
            val buf = ByteArray(FRAME_BYTES)
            var frames = 0L
            var peak = 0
            var zeros = 0
            // Frames in a row the headset's mic stayed under QUIET.
            var quiet = 0
            var unchecked = false
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
                if (p > VOICE_PEAK) Voice.voiceAt = android.os.SystemClock.elapsedRealtime()
                peak = maxOf(peak, p)
                // A mic left on a device that went quiet gives exact zeros; a live one never does
                // for 2 s. So does one that isn't on the headset it was asked for after a second.
                // Reopened at most every 10 s (a mute by the system zeroes it too).
                zeros = if (p == 0) zeros + 1 else 0
                val astray = ++frames % 50 == 0L && want != null && rec.routedDevice?.id != want.id
                if (headset(want)) {
                    // A headset's mic that stays near-silent while the phone's own mic hears two bursts of speech,
                    // seconds apart, is dead: the call hears Aviv through the phone's mic.
                    if (p < QUIET && !Voice.ui.value.muted) {
                        if (++quiet == CHECK_AFTER && probe == null && !unchecked) {
                            probe = phoneMic(am)?.let { openProbe(it, LOUD, "the headset is quiet; the phone's mic listens alongside") }
                            if (probe == null) unchecked = true
                        }
                    } else {
                        quiet = 0
                    }
                    probe?.let { pr ->
                        pr.drain()
                        if (quiet == 0) pr.reset()
                        if (pr.bursts >= 2) {
                            return deadHeadset(am, want!!, "the phone's mic heard ${pr.bursts} bursts of speech it didn't")
                        }
                        if (pr.useless(AudioDeviceInfo.TYPE_BUILTIN_MIC)) {
                            Log.w(TAG, "mic check: the phone's mic gives ${if (pr.live) "audio" else "zeros"} on ${name(pr.rec.routedDevice)}: no check on this headset")
                            pr.close()
                            probe = null
                            unchecked = true
                        }
                    }
                } else if (fallback) {
                    probe?.let { pr ->
                        pr.drain()
                        if (pr.bursts >= 1) {
                            Log.i(TAG, "mic: ${name(pr.rec.routedDevice)} hears speech again; back on it")
                            deadMic = null
                            plainMic = false
                            clearDeadNotice()
                            return Mic.REROUTE
                        }
                        if (pr.useless(headsetMic(am)?.type ?: -1)) {
                            Log.w(TAG, "mic check: the headset's mic gives ${if (pr.live) "audio" else "zeros"} on ${name(pr.rec.routedDevice)}: the route button brings it back")
                            pr.close()
                            probe = null
                        }
                    }
                }
                if (astray && fallback) {
                    if (!plainMic) {
                        // Under a Bluetooth call, VOICE_COMMUNICATION can be held to the headset's input.
                        Log.w(TAG, "mic: the phone's mic refused under ${name(am.communicationDevice)}; trying it as a plain mic")
                        plainMic = true
                        return Mic.REROUTE
                    }
                    if (frames == 50L) {
                        Log.w(TAG, "mic: the phone's mic refused as a plain mic too, on ${name(rec.routedDevice)}")
                        Voice.update(this) { it.copy(error = "${am.communicationDevice?.productName}'s mic is silent and the phone's can't stand in: tap the route button for the speaker") }
                    }
                }
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
            probe?.close()
            runCatching { rec.stop() }
            aec?.release()
            ns?.release()
            rec.release()
        }
    }

    /** Gives up on [mic], a headset's own mic, while the call stays on that headset, and says so on the call screen. */
    private fun deadHeadset(am: AudioManager, mic: AudioDeviceInfo, why: String): Mic {
        deadMic = am.communicationDevice?.id
        Log.w(TAG, "mic: ${name(mic)} hears nothing ($why); the phone's mic hears Aviv while ${name(am.communicationDevice)} plays the call")
        Voice.update(this) { it.copy(error = "${mic.productName}'s mic is silent: Jarvis hears you through the phone") }
        return Mic.REROUTE
    }

    private fun clearDeadNotice() =
        Voice.update(this) { if (it.error?.contains("mic is silent") == true) it.copy(error = null) else it }

    /** The input that goes with the call's output device: a headset's own mic (the phone's, if that one is dead),
     *  or null (the system's choice, the phone's mics) for the earpiece and the speaker. */
    private fun micFor(am: AudioManager): AudioDeviceInfo? {
        if (deadMic != null && am.communicationDevice?.id == deadMic) return phoneMic(am)
        return headsetMic(am)
    }

    private fun headsetMic(am: AudioManager): AudioDeviceInfo? {
        val out = am.communicationDevice?.takeIf(::headset) ?: return null
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == out.type }
        return ins.firstOrNull { it.address == out.address } ?: ins.firstOrNull()
    }

    private fun phoneMic(am: AudioManager) =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /**
     * A second mic, recording alongside the call's. Beside a headset's mic that has gone quiet, the phone's: speech
     * there that the headset doesn't carry means the headset's mic is dead. The AirPods' call link once came up with
     * their mic giving near-silence (peaks of 11 to 214 where speech is thousands; the phone's input from them at a
     * flat -81 dB), the AudioRecord on the AirPods and fed in real time (2026-10-29). Quiet on both is just Aviv
     * listening; a rustle in a pocket is one burst, not two seconds apart.
     */
    private class Probe(val rec: AudioRecord, private val loud: Int) {
        private val buf = ByteArray(FRAME_BYTES)
        /** How much came in, and whether any of it wasn't zeros. */
        var ms = 0
        var live = false
        /** Runs of sound at [loud] or more for BURST_MS, at least BURST_GAP_MS apart, since the last [reset]. */
        var bursts = 0
        private var run = 0
        private var lastBurst = Int.MIN_VALUE / 2

        /** What came in since the last call, without waiting for more. */
        fun drain() {
            while (true) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                if (n <= 0) return
                val p = peakOf(buf, n)
                ms += n / 32
                if (p > 0) live = true
                if (p < loud) { run = 0; continue }
                val was = run
                run += n / 32
                if (was < BURST_MS && run >= BURST_MS && ms - lastBurst >= BURST_GAP_MS) {
                    bursts++
                    lastBurst = ms
                }
            }
        }

        fun reset() {
            bursts = 0
            run = 0
            lastBurst = Int.MIN_VALUE / 2
        }

        /** After 2 s: only zeros, or on another device than [type]. */
        fun useless(type: Int) = ms >= 2000 && (!live || rec.routedDevice?.type != type)

        fun close() {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun openProbe(device: AudioDeviceInfo, loud: Int, why: String): Probe? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 8))
        } catch (e: Exception) {
            Log.w(TAG, "mic check: no second recording", e)
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        rec.setPreferredDevice(device)
        return try {
            rec.startRecording()
            Log.i(TAG, "mic check: $why, asked for ${name(device)}, on ${name(rec.routedDevice)}")
            Probe(rec, loud)
        } catch (e: Exception) {
            Log.w(TAG, "mic check: ${name(device)} didn't start", e)
            rec.release()
            null
        }
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
            // Moved off the headset whose mic was dead: back on it, its mic gets another chance.
            if (deadMic != null && it?.id != deadMic) {
                deadMic = null
                plainMic = false
                clearDeadNotice()
            }
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
                    if (micLater) takeMic()
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

    /** Started in the background: the mic waits for Telecom's call service (see [begin]). */
    @Volatile private var micLater = false

    /** The mic, once Telecom has lent the call its service; else the call hangs and Jarvis rings. */
    private suspend fun takeMic() {
        val since = System.currentTimeMillis()
        while (System.currentTimeMillis() - since < MIC_WAIT_MS) {
            val got = runCatching {
                startForeground(
                    ID, notification(Voice.ui.value.state ?: CallState.Connecting, Voice.ui.value.muted),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }.isSuccess
            if (got) {
                micLater = false
                Log.i(TAG, "mic back after ${System.currentTimeMillis() - since} ms of Telecom's call")
                return
            }
            delay(250)
        }
        // Aviv would hear Jarvis and never be heard: ring instead, and his answer brings the call back.
        Log.w(TAG, "no mic from the background after $MIC_WAIT_MS ms; ringing instead")
        // The process goes, so the line drops without a hangup and the server keeps the call; the
        // ring stays on the phone (the notification is the system's).
        CallStore.active(this)?.let { ringBack(this, it) }
        android.os.Process.killProcess(android.os.Process.myPid())
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
            var offline = false
            override fun onAvailable(network: Network) {
                val changed = current != null && current != network
                current = network
                if (changed || offline) {
                    Log.i(TAG, if (offline) "network back" else "network changed")
                    offline = false
                    forumNetworkChanged()
                    // Redials at once if the call is down; else checks the line is still good.
                    c.setOnline(true)
                }
            }

            override fun onLost(network: Network) {
                // No network at all: the call waits for one instead of dialing into nothing.
                if (cm.activeNetwork == null) {
                    Log.i(TAG, "no network")
                    offline = true
                    current = null
                    c.setOnline(false)
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
        stt?.stop()
        stt = null
        PhoneStt.check(this)
        routes.release()
        keys?.release()
        keys = null
        val scope = scope
        scope.launch {
            // Telecom's disconnect is launched by `over`; give it a moment before the scope goes.
            withTimeoutOrNull(2000) { kotlinx.coroutines.delay(500) }
            scope.cancel()
        }
        redial?.let {
            redial = null
            begin(it)
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        call?.hangup()
        keys?.release()
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
        /** The dropped call to continue (a String extra). */
        const val REJOIN = "rejoin"
        /** Started by the app itself after a restart, not by Aviv (a Boolean extra). */
        const val AUTO = "auto"
        /** A mic frame this loud is Aviv talking: an update waits for a pause ([Voice.quiet]). */
        private const val VOICE_PEAK = 2000
        /** How long a call started in the background waits for Telecom to lend it the mic. */
        private const val MIC_WAIT_MS = 5_000L

        fun ringBack(context: Context, id: String) =
            Callback.ring(context, id, "Jarvis calling back", "Our call dropped. Answer to go on where we were.")
        private const val TAG = "TetherCall"
        private const val ID = 20
        private const val RATE = 16_000
        private const val FRAME_SAMPLES = 320
        private const val FRAME_BYTES = FRAME_SAMPLES * 2

        private fun name(d: AudioDeviceInfo?) = d?.let { "${it.productName} (type ${it.type})" } ?: "default"

        /** A headset's mic under this, frame after frame, while Aviv isn't muted: it may be dead ([Probe]). */
        private const val QUIET = 400
        /** Speech, or anything as loud, at the phone's mic. */
        private const val LOUD = 3000
        /** Speech at a headset's mic that was given up on: it hears again. A dead one gave 214 at most. */
        private const val BACK = 1500
        /** 3 s of a quiet headset before the phone's mic listens alongside it. */
        private const val CHECK_AFTER = 150
        /** A burst of speech at the other mic: this long at LOUD or more, frame after frame. */
        private const val BURST_MS = 300
        /** Two bursts this far apart, start to start, are two; a rustle in a pocket is one. */
        private const val BURST_GAP_MS = 2000

        private fun peakOf(pcm: ByteArray, len: Int = pcm.size): Int {
            var m = 0
            for (i in 0 until len - 1 step 2) {
                val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                m = maxOf(m, kotlin.math.abs(v.toShort().toInt()))
            }
            return m
        }

        fun micGranted(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
