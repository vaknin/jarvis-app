package com.kivan.jarvis

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * Apple's accessory protocol to the AirPods, over L2CAP PSM 0x1001: what an iPhone uses for their settings. The bytes
 * are LibrePods' (github.com/librepods-org/librepods, docs/AAP Definitions.md, control_commands.md), which needs no
 * root on a Pixel since Android 16 QPR3. Used for one thing: which bud's mic carries a call ([AirPodsMic]). Everything
 * the buds say goes to the call's log as `aap:` lines, so a deaf evening's state can be read afterwards.
 */
object AapPackets {
    private val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)
    val HANDSHAKE = byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
    val FEATURES = HEADER + byteArrayOf(0x4D, 0x00, 0xD7.toByte(), 0, 0, 0, 0, 0, 0, 0)
    val NOTIFY = HEADER + byteArrayOf(0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    const val MIC_MODE: Byte = 0x01
    private const val CONTROL: Byte = 0x09
    private const val EARS: Byte = 0x06
    private const val BATTERY: Byte = 0x04

    fun control(id: Byte, value: Byte) = HEADER + byteArrayOf(CONTROL, 0x00, id, value, 0, 0, 0)

    /** Mic mode's value: the buds choose (0), always the right (1), always the left (2). */
    fun micMode(bud: Bud?): Byte = when (bud) {
        null -> 0
        Bud.RIGHT -> 1
        Bud.LEFT -> 2
    }

    fun micModeName(v: Byte) = when (v.toInt()) {
        0 -> "automatic"
        1 -> "right"
        2 -> "left"
        else -> "0x%02x".format(v)
    }

    sealed interface Said {
        /** A setting, `id` as in control_commands.md; mic mode is 0x01, listening mode 0x0D, conversation awareness 0x28. */
        data class Control(val id: Byte, val value: Byte) : Said
        data class Other(val line: String) : Said
    }

    /** One read can carry several packets back to back; each starts with the header. */
    fun split(b: ByteArray): List<ByteArray> {
        val starts = (0..b.size - HEADER.size).filter { i -> HEADER.indices.all { b[i + it] == HEADER[it] } }
        if (starts.isEmpty()) return listOf(b)
        // A header's bytes can sit inside a packet's own data: only a start at least a packet's length on counts.
        val cuts = starts.fold(mutableListOf<Int>()) { acc, i -> if (acc.isEmpty() || i - acc.last() >= 8) acc += i; acc }
        return cuts.mapIndexed { k, i -> b.copyOfRange(i, cuts.getOrElse(k + 1) { b.size }) }
    }

    fun parse(p: ByteArray): Said? {
        if (p.size < 6 || !p.copyOfRange(0, 4).contentEquals(HEADER)) return null
        return when (p[4]) {
            CONTROL -> if (p.size >= 8) Said.Control(p[6], p[7]) else null
            EARS -> if (p.size >= 8) Said.Other("ears: primary ${ear(p[6])}, secondary ${ear(p[7])}") else null
            BATTERY -> Said.Other("battery ${hex(p.copyOfRange(6, p.size))}")
            else -> Said.Other("op 0x%02x %s".format(p[4], hex(p.copyOfRange(6, minOf(p.size, 30)))))
        }
    }

    fun describe(c: Said.Control): String = when (c.id.toInt()) {
        0x01 -> "mic mode ${micModeName(c.value)}"
        0x0D -> "listening mode " + when (c.value.toInt()) { 1 -> "off"; 2 -> "noise cancelling"; 3 -> "transparency"; 4 -> "adaptive"; else -> "0x%02x".format(c.value) }
        0x28 -> "conversation awareness " + when (c.value.toInt()) { 1 -> "on"; 2 -> "off"; else -> "0x%02x".format(c.value) }
        else -> "setting 0x%02x = 0x%02x".format(c.id, c.value)
    }

    private fun ear(b: Byte) = when (b.toInt()) { 0 -> "in ear"; 1 -> "out"; 2 -> "in case"; else -> "0x%02x".format(b) }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
}

/**
 * One call's connection to the AirPods. [open] connects on its own thread and never blocks the call; until it's up,
 * [mic] waits in [want] and goes once it is. [close] puts the buds' mic mode back as it found it. Any failure is one
 * `aap:` line and the call goes on with the buds' own choice.
 */
class Aap(private val context: Context, private val address: String?, private val log: (String) -> Unit) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    /** The mic mode the buds had when we came, to leave them as they were. */
    @Volatile private var found: Byte? = null
    @Volatile private var want: Byte? = null
    private var lines = 0

    fun open() {
        Thread(::run, "jarvis-aap").apply { isDaemon = true }.start()
    }

    /** The bud to carry the mic; null gives the choice back to the buds. */
    fun mic(bud: Bud?) {
        val v = AapPackets.micMode(bud)
        want = v
        if (socket != null) send(AapPackets.control(AapPackets.MIC_MODE, v), "mic mode ${AapPackets.micModeName(v)}")
    }

    fun close() {
        if (closed) return
        closed = true
        val s = socket
        val back = found
        if (s != null && back != null && want != null && want != back) {
            send(AapPackets.control(AapPackets.MIC_MODE, back), "mic mode back to ${AapPackets.micModeName(back)}")
        }
        runCatching { s?.close() }
        socket = null
    }

    @SuppressLint("MissingPermission")
    private fun run() {
        try {
            val device = device() ?: run { log("aap: no AirPods among the paired devices"); return }
            val s = createSocket(device)
            if (closed) return
            s.connect()
            if (closed) { runCatching { s.close() }; return }
            socket = s
            log("aap: connected to ${device.name}")
            val out = s.outputStream
            for (p in listOf(AapPackets.HANDSHAKE, AapPackets.FEATURES, AapPackets.NOTIFY)) {
                out.write(p)
                Thread.sleep(150)
            }
            want?.let { send(AapPackets.control(AapPackets.MIC_MODE, it), "mic mode ${AapPackets.micModeName(it)}") }
            val buf = ByteArray(1024)
            val input = s.inputStream
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                AapPackets.split(buf.copyOf(n)).forEach(::heard)
            }
            if (!closed) log("aap: the AirPods closed the connection")
        } catch (e: SecurityException) {
            log("aap: no Bluetooth permission (open the app once and allow it)")
        } catch (e: Exception) {
            if (!closed) log("aap: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } finally {
            socket = null
        }
    }

    private fun heard(p: ByteArray) {
        when (val said = AapPackets.parse(p)) {
            is AapPackets.Said.Control -> {
                if (said.id == AapPackets.MIC_MODE && found == null) found = said.value
                log("aap: ${AapPackets.describe(said)}")
            }
            is AapPackets.Said.Other -> if (++lines <= MAX_LINES) log("aap: ${said.line}")
            null -> {}
        }
    }

    private fun send(p: ByteArray, what: String) {
        val s = socket ?: return
        try {
            synchronized(this) { s.outputStream.write(p) }
            log("aap: set $what")
        } catch (e: Exception) {
            log("aap: couldn't set $what: ${e.message?.take(80)}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun device(): BluetoothDevice? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        address?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }?.let { a ->
            adapter.bondedDevices.firstOrNull { it.address == a }?.let { return it }
        }
        return adapter.bondedDevices.firstOrNull { d -> d.uuids?.any { it == UUID } == true }
    }

    /** The hidden L2CAP constructor, as LibrePods reaches it: Android has no public classic L2CAP socket to a PSM. */
    private fun createSocket(device: BluetoothDevice): BluetoothSocket {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val specs = listOf(
            arrayOf<Any>(adapter, device, L2CAP, true, true, PSM, UUID),
            arrayOf<Any>(device, L2CAP, true, true, PSM, UUID),
            arrayOf<Any>(device, L2CAP, 1, true, true, PSM, UUID),
        )
        var last: Exception? = null
        for (args in specs) {
            try {
                val types = args.map { it::class.javaPrimitiveType ?: it::class.java }.toTypedArray()
                types.indices.forEach { i -> if (args[i] is BluetoothAdapter) types[i] = BluetoothAdapter::class.java }
                types.indices.forEach { i -> if (args[i] is BluetoothDevice) types[i] = BluetoothDevice::class.java }
                val c = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                c.isAccessible = true
                return c.newInstance(*args) as BluetoothSocket
            } catch (e: Exception) {
                last = e
            }
        }
        Log.w("JarvisAap", "no L2CAP socket constructor", last)
        throw last ?: IllegalStateException("no L2CAP socket")
    }

    private companion object {
        const val L2CAP = 3
        const val PSM = 0x1001
        val UUID: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        /** Lines besides settings, so a chatty connection can't flood the call's log. */
        const val MAX_LINES = 40
    }
}
