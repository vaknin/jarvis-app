package com.kivan.jarvis

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.kivan.jarvis.core.ChatMessage
import com.kivan.jarvis.core.Event
import com.kivan.jarvis.core.EventListener
import com.kivan.jarvis.core.MsgKind
import com.kivan.jarvis.core.MsgState
import com.kivan.jarvis.core.Status
import com.kivan.jarvis.core.JarvisNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "Jarvis"
private const val HISTORY = 500u

/**
 * Owns the Rust node. The phone holds no connection while idle (the Oct 1 battery choice): the node
 * runs only while the chat is on screen, something of the phone's is queued or moving, a link is
 * open, or a wake or send has just started it. The laptop wakes it through FCM ([Push]) when it has
 * something; the core closes an idle link after 60 s, and then the node stops.
 */
object Core {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var app: Context
    private val lock = Mutex()
    @Volatile private var node: JarvisNode? = null
    /** Until when ([SystemClock.elapsedRealtime]) a wake or a send keeps the node up regardless. */
    @Volatile private var busyUntil = 0L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** File transfers in flight: message id → (done, total). */
    private val _progress = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val progress: StateFlow<Map<String, Pair<Long, Long>>> = _progress.asStateFlow()
    /** The laptop refused the link: it has paired another phone since. */
    private val _refused = MutableStateFlow(false)
    val refused: StateFlow<Boolean> = _refused.asStateFlow()

    /** The chat is on screen: incoming messages are read, not notified, and the link stays open. */
    @Volatile var chatVisible = false
        private set

    fun init(context: Context) {
        app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = networkChanged()
            })
    }

    private fun networkChanged() {
        scope.launch {
            val n = node ?: return@launch
            runCatching { n.networkChange() }
            // Something of mine waiting for the laptop: a new network is a chance to reach it.
            if (runCatching { n.status().queued > 0uL }.getOrDefault(false)) connect()
        }
    }

    /** The laptop's wake (FCM): dial, take what it queued, and stop again once idle. */
    fun wake(reason: String) {
        Log.i(TAG, "wake: $reason")
        busyUntil = SystemClock.elapsedRealtime() + GRACE_MS
        scope.launch { connect() }
    }

    /** A live frame if the node is running; never starts it. */
    fun sendLive(send: (JarvisNode) -> Boolean): Boolean =
        node?.let { runCatching { send(it) }.getOrDefault(false) } ?: false

    val outgoingDir: File get() = File(app.filesDir, "outgoing")

    /** The running node, started on first use; [watch] stops it again once it's idle. */
    suspend fun start(): JarvisNode = lock.withLock {
        check(!Move.off()) { "the old app holds the link" }
        busyUntil = maxOf(busyUntil, SystemClock.elapsedRealtime() + GRACE_MS)
        node ?: com.kivan.jarvis.core.start(
            File(app.filesDir, "state").path,
            File(app.filesDir, "incoming").path,
            "Pixel",
            BuildConfig.VERSION_NAME,
        ).also { n ->
            n.setListener(object : EventListener {
                override fun onEvent(event: Event) = handle(n, event)
            })
            node = n
            refresh(n)
            Log.i(TAG, "node started")
            watch(n)
        }
    }

    /** Stops the node now, when the move gives the link back ([Move]). */
    suspend fun stop() = lock.withLock {
        node?.let {
            it.shutdown()
            it.close()
        }
        node = null
        _connected.value = false
    }

    /** Stops [n] once nothing needs it: checked every few seconds while it runs. */
    private fun watch(n: JarvisNode) = scope.launch {
        while (true) {
            delay(CHECK_MS)
            lock.withLock {
                if (node !== n) return@launch
                if (idle(n)) {
                    node = null
                    n.shutdown()
                    n.close()
                    _connected.value = false
                    Log.i(TAG, "node stopped")
                    return@launch
                }
            }
        }
    }

    private fun idle(n: JarvisNode): Boolean {
        if (chatVisible || SystemClock.elapsedRealtime() < busyUntil || _progress.value.isNotEmpty()) return false
        if (n.isConnected()) return false
        // Unpaired, or nothing of mine queued for the laptop: the laptop's wake brings the rest.
        val s = runCatching { n.status() }.getOrNull() ?: return false
        return s.peer == null || s.queued == 0uL
    }

    fun setVisible(visible: Boolean) {
        chatVisible = visible
        if (Move.off()) return
        scope.launch {
            val n = start()
            n.setStay(visible)
            if (visible) {
                runCatching { n.markRead() }
                Notifier.cancelMessages(app)
                if (n.status().peer != null && !n.isConnected()) connect()
            }
            refresh(n)
        }
    }

    /** Dials the laptop unless connected; false when it couldn't be reached. */
    suspend fun connect(): Boolean = try {
        start().connect()
        true
    } catch (e: Exception) {
        Log.i(TAG, "connect failed: ${e.message}")
        false
    }

    private fun refresh(n: JarvisNode) {
        runCatching {
            _status.value = n.status()
            _messages.value = n.recent(HISTORY)
        }.onFailure { Log.w(TAG, "refresh failed", it) }
    }

    /** Called on a Rust runtime thread. */
    private fun handle(n: JarvisNode, event: Event) {
        when (event) {
            is Event.Connected -> {
                _connected.value = true
                Push.sendToken(n)
            }
            is Event.Disconnected -> _connected.value = false
            is Event.Message -> onMessage(event.msg)
            is Event.Progress -> {
                val p = _progress.value.toMutableMap()
                if (event.done >= event.total) p.remove(event.id) else p[event.id] = event.done.toLong() to event.total.toLong()
                _progress.value = p
                return
            }
            is Event.Paired, is Event.Unpaired -> _refused.value = false
            is Event.Refused -> _refused.value = true
            is Event.SendFailed -> Notifier.sendFailed(app, event.name, event.reason)
            // What the old app did (media, ring, mirrored notifications, channels) is gone.
            else -> return
        }
        refresh(n)
    }

    private fun onMessage(m: ChatMessage) {
        if (m.state != MsgState.QUEUED && m.state != MsgState.INCOMING && m.id in _progress.value) {
            _progress.value = _progress.value - m.id
        }
        if (m.state == MsgState.DELIVERED || m.state == MsgState.RECEIVED) _refused.value = false
        if (m.fromMe) {
            // The copy made for sending is no longer needed once the laptop has it (or never will).
            if (m.state == MsgState.DELIVERED || m.state == MsgState.EXPIRED || m.state == MsgState.CANCELLED) {
                m.path?.let(::File)?.takeIf { it.startsWith(outgoingDir) }?.let {
                    it.delete()
                    it.parentFile?.delete()
                }
            }
            return
        }
        if (m.state != MsgState.RECEIVED || m.channel != null) return
        when (m.kind) {
            MsgKind.FILE -> scope.launch { Downloads.publish(app, m) }
            else -> if (chatVisible) scope.launch { node?.markRead() } else Notifier.message(app, m)
        }
    }

    suspend fun sendText(text: String) {
        start().sendText(text)
        node?.let(::refresh)
    }

    suspend fun sendFile(file: File) {
        start().sendFile(file.path)
        node?.let(::refresh)
    }

    suspend fun pair(code: String) {
        val n = start()
        n.pair(code.trim())
        refresh(n)
        scope.launch { connect() }
    }

    suspend fun unpair() {
        val n = start()
        n.unpair()
        refresh(n)
    }

    suspend fun cancel(id: String) {
        start().cancel(id)
        node?.let(::refresh)
    }

    suspend fun markRead() {
        start().markRead()
        node?.let(::refresh)
    }

    /** How long a wake or a send keeps the node up before it may stop (a dial can take this long). */
    private const val GRACE_MS = 30_000L
    private const val CHECK_MS = 5_000L
}
