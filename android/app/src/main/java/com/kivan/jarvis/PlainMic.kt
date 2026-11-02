package com.kivan.jarvis

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import com.kivan.jarvis.core.LarkFilter
import com.kivan.jarvis.core.micNote

/** How a stretch on a mic ended. */
internal enum class MicEnd { OVER, REROUTE, FAILED }

/**
 * A mic off the call route, shared by a Lark call and dictation: nothing of Telecom, so the AirPods
 * keep their music quality. [rule] names it in the log; [over] says the whole thing ended and [moved]
 * that the rule asks for another mic; [send] takes 16 kHz frames.
 */
internal class PlainMic(
    private val am: AudioManager,
    private val rule: () -> String,
    private val over: () -> Boolean,
    private val moved: () -> Boolean,
    private val send: (ByteArray) -> Unit,
    /** The phone's noise filter on the Lark ([LarkNs]): asked at the open and every 2 s. */
    private val ns: () -> Boolean = { false },
    /** Each raw Lark read, before our filters (a mic test's). */
    private val tee: (ByteArray) -> Unit = {},
) {
    private var lastReopen = Long.MIN_VALUE / 2

    /** The path motoparty proved (its research/MIC.md): 48 kHz stereo from the USB device, no effects, normal mode. */
    fun lark(): MicEnd {
        val lark = Senses.lark(am)
        if (lark == null && Senses.fake?.lark != true) {
            // Just unplugged: the rule is about to say so.
            Thread.sleep(200)
            return MicEnd.REROUTE
        }
        val filter = LarkFilter()
        return try {
            record(LARK_RATE, AudioFormat.CHANNEL_IN_STEREO, lark, LARK_FRAME_BYTES, ns) { tee(it); filter.feed(it) }
        } finally {
            filter.close()
        }
    }

    /** The phone's own mic, with no echo to cancel: Jarvis isn't speaking, or only in the AirPods. */
    fun phone(): MicEnd {
        val phone = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return record(RATE, AudioFormat.CHANNEL_IN_MONO, phone, FRAME_BYTES, null) { it }
    }

    /** [nsOn]: hold the phone's noise filter on this open and keep it as that says; null leaves it alone. */
    private fun record(rate: Int, channels: Int, want: AudioDeviceInfo?, size: Int, nsOn: (() -> Boolean)?, convert: (ByteArray) -> ByteArray): MicEnd {
        val min = AudioRecord.getMinBufferSize(rate, channels, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, channels, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, size * 4))
        } catch (e: Exception) {
            Log.w(TAG, "mic", e)
            return MicEnd.FAILED
        }
        if (want != null && !rec.setPreferredDevice(want)) Log.w(TAG, "mic: ${CallService.name(want)} refused as the preferred input")
        // Our own handle, so it's ours to say: off unless asked, whatever the system attached (thread t57).
        val ns = if (nsOn != null && NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId) }.getOrNull() else null
        fun applyNs() {
            if (ns == null || nsOn == null) return
            val want = nsOn()
            if (ns.enabled != want) {
                runCatching { ns.enabled = want }
                micNote("phone noise filter ${if (ns.enabled) "on" else "off"}")
            }
            LarkNs.active = ns.enabled
        }
        if (nsOn != null) {
            if (ns == null) micNote("phone noise filter: none on this mic") else micNote("phone noise filter was ${if (ns.enabled) "on" else "off"} at the open")
            applyNs()
        }
        try {
            rec.startRecording()
            micNote("open: ${rule()}: wanted ${CallService.name(want)}, on ${CallService.name(rec.routedDevice)}, $rate Hz")
            val buf = ByteArray(size)
            var reads = 0L
            var loudest = 0
            var quiet = 0
            while (!over() && !moved()) {
                var got = 0
                while (got < size && !over()) {
                    val n = rec.read(buf, got, size - got)
                    if (n < 0) throw IllegalStateException("read $n")
                    got += n
                }
                if (got != size) continue
                val out = convert(buf.copyOf())
                if (out.isNotEmpty()) send(out)
                val p = CallService.peakOf(out)
                if (p > CallService.VOICE_PEAK) Voice.voiceAt = SystemClock.elapsedRealtime()
                loudest = maxOf(loudest, p)
                quiet = if (p < SILENT_PEAK) quiet + 1 else 0
                if (++reads % 100 == 0L) {
                    applyNs()
                    micNote("peak $loudest on ${CallService.name(rec.routedDevice)}")
                    loudest = 0
                    if (want != null && rec.routedDevice?.id != want.id && System.nanoTime() - lastReopen > 10_000_000_000L) {
                        lastReopen = System.nanoTime()
                        micNote("not on ${CallService.name(want)}; reopening")
                        return MicEnd.REROUTE
                    }
                }
                // The Lark's receiver gives exact zeros with its transmitter off: worth a line, not a reopen.
                if (quiet == QUIET_NOTE_READS) micNote("silent for 10 s on ${CallService.name(rec.routedDevice)}")
            }
            return if (over()) MicEnd.OVER else MicEnd.REROUTE
        } catch (e: Exception) {
            Log.w(TAG, "mic", e)
            return MicEnd.FAILED
        } finally {
            runCatching { rec.stop() }
            ns?.release()
            if (nsOn != null) LarkNs.active = null
            rec.release()
        }
    }

    private companion object {
        const val TAG = "JarvisCall"
        const val RATE = 16_000
        const val FRAME_BYTES = 640
        const val LARK_RATE = 48_000
        /** 20 ms of the Lark's stereo: `crates/voice/src/lark.rs`. */
        const val LARK_FRAME_BYTES = 3840
        /** Under this a read holds nothing (motoparty's `SILENT_PEAK`). */
        const val SILENT_PEAK = 32
        /** 10 s of reads at 20 ms. */
        const val QUIET_NOTE_READS = 500
    }
}
