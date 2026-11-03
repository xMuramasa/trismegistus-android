package dev.xmuramasa.trismegistus.core.relay

import dev.xmuramasa.trismegistus.core.log.Logger
import dev.xmuramasa.trismegistus.core.log.debug
import dev.xmuramasa.trismegistus.core.log.info
import dev.xmuramasa.trismegistus.core.log.warn
import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.protocol.Wire
import dev.xmuramasa.trismegistus.core.protocol.isTerminal
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The one WebSocket to the relay: auth, wait for `ready`, resubscribe every followed session from its last seq,
 * resend what the relay hasn't acknowledged, keepalive, reconnect with backoff. Everything the relay sends comes out
 * of [outputs], in order.
 *
 * [relayUrl] is the relay's https base URL (as in the pairing QR); the socket is `<base>/ws`. [token] is used for
 * `auth` only and never logged. [sleep], [jitter] (0..1) and [now] (unix ms) are injectable so tests control time.
 */
public class RelayClient(
    relayUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() },
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: Logger = Logger.None,
) {
    /** One item of [outputs]: a connection state change or a relay message. */
    public sealed interface Output {
        public data class State(val state: ConnectionState) : Output

        public data class Message(val message: ServerMessage) : Output
    }

    /** [send] while there is no ready socket: nothing was sent. */
    public class NotConnectedException : IllegalStateException("Not connected to the relay.")

    public companion object {
        /** The relay's numbers (relay/internal/server): ping every 25 s, two missed pongs mean a dead socket. */
        public val PING_INTERVAL: Duration = 25.seconds
        public const val MISSED_PONG_LIMIT: Int = 2

        /** The relay closes a socket that hasn't sent auth within 5 s; this is the wait for its `ready`. */
        public val READY_TIMEOUT: Duration = 10.seconds
        public val MAX_BACKOFF: Duration = 30.seconds

        /** The relay refuses more subscriptions per socket (relay/internal/server: MaxSubscriptions). */
        public const val MAX_SUBSCRIPTIONS: Int = 64

        internal fun webSocketUrl(base: String): HttpUrl = base.toHttpUrl().newBuilder().addPathSegment("ws").build()
    }

    private val url = webSocketUrl(relayUrl)
    private val output = Channel<Output>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** For one collector (SessionStore): states and relay messages in the order they happened. */
    public val outputs: ReceiveChannel<Output> get() = output
    public val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    // Everything below is guarded by lock; OkHttp's send only enqueues, so it is called under it too.
    private val lock = Any()

    /** Followed sessions, in subscribe order, and the last seq seen in each (0: none yet, subscribe live). */
    private val lastSeq = LinkedHashMap<String, Long>()

    /** Prompts sent but neither acked nor refused, by client_msg_id: resent after a reconnect (Hermes dedupes them). */
    private val pendingPrompts = LinkedHashMap<String, ClientMessage.Prompt>()

    /** Steers sent but not queued (`steer.queued`) nor refused, oldest first. */
    private val pendingSteers = mutableListOf<PendingSteer>()
    private var socket: Connection? = null
    private var ready = false
    private var loop: Job? = null

    /** The backoff wait in progress; reconnectNow() cancels it. */
    private var backoff: Job? = null

    public val isRunning: Boolean get() = synchronized(lock) { loop != null }

    public fun start() {
        synchronized(lock) {
            if (loop == null) loop = scope.launch { run() }
        }
    }

    /** Closes the socket for good (until start()). */
    public fun stop() {
        val (job, conn) = synchronized(lock) {
            val job = loop
            val conn = socket
            loop = null
            socket = null
            ready = false
            backoff?.cancel()
            job to conn
        }
        job?.cancel()
        conn?.drop(1000)
        synchronized(lock) { setState(ConnectionState.Disconnected) }
        logger.info("relay: stopped")
    }

    /**
     * Ends the backoff wait and connects now (the app calls it on foreground and when the network comes back). The
     * next wait follows on from this attempt. A no-op unless reconnecting.
     */
    public fun reconnectNow() {
        synchronized(lock) { backoff?.cancel() }
    }

    /** Follows a session: subscribes now if connected, and again after every reconnect from the last seq seen. */
    public fun subscribe(sessionId: String, afterSeq: Long = 0) {
        synchronized(lock) {
            lastSeq[sessionId] = afterSeq
            if (ready) socket?.ws?.send(subscribeText(sessionId, afterSeq))
        }
        logger.debug("relay: subscribe $sessionId after $afterSeq")
    }

    public fun unsubscribe(sessionId: String) {
        synchronized(lock) {
            if (lastSeq.remove(sessionId) != null && ready) {
                socket?.ws?.send(Wire.encode(ClientMessage.Unsubscribe(sessionId)))
            }
        }
    }

    /**
     * Follows exactly these sessions, in this order, up to [MAX_SUBSCRIPTIONS] (the rest are left out). Sessions
     * already followed keep their seq; the ones no longer named are unsubscribed first, so the relay's limit holds.
     */
    public fun follow(sessionIds: List<String>) {
        val ids = sessionIds.distinct().take(MAX_SUBSCRIPTIONS)
        synchronized(lock) {
            val keep = ids.toSet()
            lastSeq.keys.filter { it !in keep }.forEach(::unsubscribe)
            ids.filter { it !in lastSeq }.forEach { subscribe(it) }
            val ordered = ids.associateWith { lastSeq.getValue(it) }
            lastSeq.clear()
            lastSeq.putAll(ordered)
        }
    }

    /**
     * prompt, steer, stop, approve (subscribe through [subscribe] so reconnects resume it). Prompts and steers are
     * kept until the relay answers them and sent again after a reconnect. Throws [NotConnectedException].
     */
    public fun send(message: ClientMessage) {
        synchronized(lock) {
            val c = socket?.takeIf { ready } ?: throw NotConnectedException()
            when (message) {
                is ClientMessage.Prompt -> pendingPrompts[message.clientMsgId] = message
                is ClientMessage.Steer -> pendingSteers += PendingSteer(message)
                else -> Unit
            }
            c.ws.send(Wire.encode(message))
        }
        logger.info("relay: sent ${describe(message)}")
    }

    private fun subscribeText(sessionId: String, seq: Long) =
        Wire.encode(ClientMessage.Subscribe(sessionId, seq.takeIf { it > 0 }))

    /** Call under lock. */
    private fun setState(s: ConnectionState) {
        if (mutableState.value == s) return
        mutableState.value = s
        output.trySend(Output.State(s))
    }

    /** Only the current loop speaks: a stopped one may still be unwinding. */
    private fun emit(job: Job, o: Output) {
        synchronized(lock) {
            if (loop !== job) return
            if (o is Output.State) setState(o.state) else output.trySend(o)
        }
    }

    private suspend fun run() {
        val job = currentCoroutineContext().job
        var attempt = 0
        while (true) {
            val wasStable = session(job) ?: return // auth failed
            if (wasStable) attempt = 0
            val base = minOf(MAX_BACKOFF, 1.seconds * (1 shl minOf(attempt, 5)))
            attempt++
            val wait = base + base * (0.2 * jitter())
            emit(job, Output.State(ConnectionState.Reconnecting(attempt, now() + wait.inWholeMilliseconds)))
            logger.info("relay: reconnect attempt $attempt in $wait")
            coroutineScope {
                val waiting = launch { sleep(wait) }
                synchronized(lock) { backoff = waiting }
                waiting.join() // cancelled by reconnectNow() or stop()
            }
            synchronized(lock) { backoff = null }
        }
    }

    /** One connection, from connect to drop. Returns whether it was stable, or null after an auth failure. */
    private suspend fun session(job: Job): Boolean? {
        emit(job, Output.State(ConnectionState.Connecting))
        val c = Connection()
        c.ws = http.newWebSocket(Request.Builder().url(url).build(), c)
        synchronized(lock) { socket = c }
        try {
            return serve(c, job)
        } finally {
            synchronized(lock) {
                if (socket === c) { // else stop() already cleaned up
                    socket = null
                    ready = false
                }
            }
        }
    }

    private suspend fun serve(c: Connection, job: Job): Boolean? {
        c.ws.send(Wire.encode(ClientMessage.Auth(token))) // OkHttp queues it until the socket opens
        val first = coroutineScope {
            val timer = launch {
                sleep(READY_TIMEOUT)
                logger.warn("relay: no ready in $READY_TIMEOUT")
                c.drop(1000)
            }
            c.incoming.receiveCatching().getOrNull().also { timer.cancel() }
        }?.let(Wire::decodeServerOrNull)
        when {
            first is ServerMessage.Ready -> resume(c, first, job)
            first is ServerMessage.Error && first.code == ErrorCode.UNAUTHORIZED -> {
                logger.warn("relay: the token was refused")
                emit(job, Output.Message(first))
                c.drop(1000)
                synchronized(lock) {
                    if (loop === job) {
                        setState(ConnectionState.AuthFailed(first.message))
                        loop = null
                    }
                }
                return null
            }
            else -> {
                c.drop(1000)
                return false
            }
        }
        coroutineScope {
            val keepalive = launch { keepalive(c) }
            for (text in c.incoming) handle(text, c, job)
            keepalive.cancel()
        }
        logger.info("relay: socket closed")
        return synchronized(lock) { c.stable }
    }

    /** Ready: resubscribe from the last seqs and resend unacknowledged prompts, then report Connected. */
    private fun resume(c: Connection, ready: ServerMessage.Ready, job: Job) {
        synchronized(lock) {
            this.ready = true
            for ((id, seq) in lastSeq) c.ws.send(subscribeText(id, seq))
            for (p in pendingPrompts.values) c.ws.send(Wire.encode(p))
            // A steer has no id to dedupe on: wait for the run's status, which the relay sends after the replay
            // (which may hold its steer.queued), and resend only if the run still goes.
            pendingSteers.forEach { it.held = true }
            logger.info(
                "relay: ready (device ${ready.device}, relay ${ready.serverVersion}); " +
                    "resubscribed ${lastSeq.size}, resent ${pendingPrompts.size} prompts, holding ${pendingSteers.size} steers",
            )
        }
        emit(job, Output.Message(ready))
        emit(job, Output.State(ConnectionState.Connected(ready.device, ready.serverVersion)))
    }

    private suspend fun keepalive(c: Connection) {
        while (true) {
            sleep(PING_INTERVAL)
            val dead = synchronized(lock) {
                if (c.awaitingPong) c.missedPongs++ else c.missedPongs = 0
                c.awaitingPong = true
                c.missedPongs >= MISSED_PONG_LIMIT
            }
            if (dead) {
                logger.warn("relay: $MISSED_PONG_LIMIT pongs missed, dropping the socket")
                c.drop(1001) // the read loop ends and the client reconnects
                return
            }
            c.ws.send(Wire.encode(ClientMessage.Ping))
        }
    }

    private fun handle(text: String, c: Connection, job: Job) {
        // Untrusted input: what doesn't match the protocol is dropped.
        val msg = Wire.decodeServerOrNull(text) ?: return logger.warn("relay: dropped a frame off the protocol")
        synchronized(lock) {
            when (msg) {
                ServerMessage.Pong -> {
                    c.awaitingPong = false
                    c.stable = true
                }
                is ServerMessage.Event -> {
                    lastSeq[msg.sessionId]?.let { lastSeq[msg.sessionId] = maxOf(it, msg.seq) }
                    if (msg.payload == EventPayload.SteerQueued) {
                        pendingSteers.firstOrNull { it.steer.runId == msg.runId }?.let(pendingSteers::remove)
                    } else if (msg.payload.isTerminal) {
                        pendingSteers.removeAll { it.steer.runId == msg.runId }
                    }
                }
                is ServerMessage.Ack -> pendingPrompts.remove(msg.clientMsgId)
                is ServerMessage.Run ->
                    if (msg.status.isTerminal) {
                        pendingSteers.removeAll { it.steer.runId == msg.runId }
                    } else {
                        for (s in pendingSteers) {
                            if (s.held && s.steer.runId == msg.runId) {
                                s.held = false
                                c.ws.send(Wire.encode(s.steer))
                            }
                        }
                    }
                is ServerMessage.Error -> {
                    val ref = msg.ref
                    if (msg.code == ErrorCode.REPLAY_GAP && ref != null && ref in lastSeq) {
                        // The relay refused the subscription. Take live events from now on; SessionStore reloads the history.
                        lastSeq[ref] = 0
                        c.ws.send(subscribeText(ref, 0))
                    } else if (ref != null) {
                        pendingPrompts.remove(ref)
                        pendingSteers.firstOrNull { it.steer.runId == ref }?.let(pendingSteers::remove)
                    }
                }
                is ServerMessage.Ready -> Unit
            }
        }
        logger.debug("relay: got ${describe(msg)}")
        emit(job, Output.Message(msg))
    }

    private class PendingSteer(val steer: ClientMessage.Steer) {
        /** Sent on a socket that dropped: resend once the relay reports the run still going. */
        var held = false
    }

    /** One socket's frames as a channel; closed when the socket is. Keepalive fields are guarded by the client's lock. */
    private class Connection : WebSocketListener() {
        val incoming = Channel<String>(Channel.UNLIMITED)
        lateinit var ws: WebSocket
        var awaitingPong = false
        var missedPongs = 0

        /** A pong came back on this connection: it was healthy for a ping interval, so the backoff starts over. */
        var stable = false

        override fun onMessage(webSocket: WebSocket, text: String) {
            incoming.trySend(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            incoming.close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incoming.close()
        }

        /** 1000 closes cleanly; anything else means the socket is dead, so there is no close handshake to wait for. */
        fun drop(code: Int) {
            incoming.close()
            if (code == 1000) ws.close(1000, null) else ws.cancel()
        }
    }
}

/** Ids and kinds only: what a log line may carry. */
private fun describe(m: ClientMessage): String = when (m) {
    is ClientMessage.Auth -> "auth"
    is ClientMessage.Subscribe -> "subscribe ${m.sessionId} after ${m.afterSeq ?: 0}"
    is ClientMessage.Unsubscribe -> "unsubscribe ${m.sessionId}"
    is ClientMessage.Prompt -> "prompt ${m.clientMsgId} to ${m.sessionId} (${m.attachments?.size ?: 0} images)"
    is ClientMessage.Stop -> "stop ${m.runId}"
    is ClientMessage.Steer -> "steer ${m.runId}"
    is ClientMessage.Approve -> "approve ${m.requestId} of ${m.runId}: ${m.choice}"
    ClientMessage.Ping -> "ping"
}

private fun describe(m: ServerMessage): String = when (m) {
    is ServerMessage.Ready -> "ready"
    is ServerMessage.Event -> "event ${m.kind} ${m.sessionId} #${m.seq} of ${m.runId}"
    is ServerMessage.Run -> "run ${m.runId} of ${m.sessionId}: ${m.status}"
    is ServerMessage.Ack -> "ack ${m.clientMsgId}: ${m.runId}${if (m.replayed) " (replayed)" else ""}"
    is ServerMessage.Error -> "error ${m.code} for ${m.ref ?: "-"}"
    ServerMessage.Pong -> "pong"
}
