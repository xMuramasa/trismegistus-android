package dev.xmuramasa.trismegistus.core

import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.protocol.Wire
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.core.relay.RelayClient
import java.io.File
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

const val FAKE_TOKEN = "trs_FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAK"

val protocolDir = File(System.getProperty("trismegistus.protocolDir") ?: error("run through Gradle: trismegistus.protocolDir is unset"))

/** A valid protocol example, as the relay would send it. */
fun example(name: String): String = File(protocolDir, "examples/valid/$name").readText()

fun event(session: String, seq: Long, payload: EventPayload, run: String = "run_1"): ServerMessage.Event =
    ServerMessage.Event(session, run, seq, 1_792_801_004_583 + seq, payload)

/** Real-time bound on every wait, so a broken test fails instead of hanging. */
suspend fun <T> within(block: suspend () -> T): T = withTimeout(5.seconds) { block() }

suspend fun eventually(check: () -> Boolean) = within { while (!check()) delay(5.milliseconds) }

/** Time for RelayClient: sleeps end only when the test advances the clock. */
class TestClock {
    private class Sleeper(val deadline: Duration, val duration: Duration, val done: CompletableDeferred<Unit>)

    private val lock = Any()
    private var now = Duration.ZERO
    private val sleepers = mutableListOf<Sleeper>()
    private val requested = mutableListOf<Duration>()
    private val changes = MutableStateFlow(0L)

    suspend fun sleep(duration: Duration) {
        val s = synchronized(lock) {
            Sleeper(now + duration, duration, CompletableDeferred()).also {
                sleepers += it
                requested += duration
            }
        }
        changes.update { it + 1 }
        try {
            s.done.await()
        } finally {
            synchronized(lock) { sleepers.remove(s) }
            changes.update { it + 1 }
        }
    }

    fun nowMillis(): Long = synchronized(lock) { now.inWholeMilliseconds }

    /** Every sleep asked for, in order (backoff assertions). */
    fun requestedSleeps(): List<Duration> = synchronized(lock) { requested.toList() }

    fun isSleeping(duration: Duration): Boolean = synchronized(lock) { sleepers.any { it.duration == duration } }

    suspend fun awaitNoSleepers() = within { changes.first { synchronized(lock) { sleepers.isEmpty() } } }

    /** Waits until something sleeps for [duration], then moves time forward by it. */
    suspend fun fire(duration: Duration) {
        within { changes.first { isSleeping(duration) } }
        synchronized(lock) {
            now += duration
            val due = sleepers.filter { it.deadline <= now }
            sleepers.removeAll(due)
            due.forEach { it.done.complete(Unit) }
        }
        changes.update { it + 1 }
    }
}

/** One relay socket, server side: the test reads what the client sent and pushes relay frames. */
class RelayConn : WebSocketListener() {
    private val opened = CompletableDeferred<WebSocket>()
    private val received = Channel<ClientMessage>(Channel.UNLIMITED)
    private val closed = CompletableDeferred<Int>()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        received.trySend(Wire.decodeClient(text))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closed.complete(code)
        webSocket.close(code, null)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        closed.complete(-1)
    }

    suspend fun awaitOpen(): RelayConn = also { within { opened.await() } }

    /** The client's next message. */
    suspend fun next(): ClientMessage = within { received.receive() }

    /** Nothing more was sent (after giving the client a moment). */
    suspend fun assertQuiet() {
        delay(200.milliseconds)
        val extra = received.tryReceive().getOrNull()
        check(extra == null) { "unexpected $extra" }
    }

    suspend fun push(vararg messages: ServerMessage) = messages.forEach { pushRaw(Wire.encode(it)) }

    suspend fun pushRaw(text: String) {
        within { opened.await() }.send(text)
    }

    /** The relay goes away (1001). A server-side socket can't be cancelled without a close frame in MockWebServer. */
    suspend fun drop() {
        within { opened.await() }.close(1001, null)
    }

    /** The close code the client sent, or -1 when the socket just went away. */
    suspend fun awaitClosed(): Int = within { closed.await() }

    val isClosed: Boolean get() = closed.isCompleted
}

/**
 * The relay on MockWebServer: `/ws` upgrades to a new [RelayConn] per connection; anything else is REST, answered by
 * [rest] with (status, JSON body). Every request is recorded.
 */
class FakeRelay(private val rest: (RecordedRequest) -> Pair<Int, String> = { 404 to """{"error":{"code":"not_found","message":"not found"}}""" }) :
    AutoCloseable {
    private val server = MockWebServer()
    val connections = MutableStateFlow<List<RelayConn>>(emptyList())
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.url.encodedPath == "/ws") {
                    val c = RelayConn()
                    connections.update { it + c }
                    return MockResponse.Builder().webSocketUpgrade(c).build()
                }
                val (status, body) = rest(request)
                return MockResponse.Builder().code(status).body(body).build()
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    /** The index-th connection the client opened, once it is open. */
    suspend fun connection(index: Int): RelayConn = within { connections.first { it.size > index } }[index].awaitOpen()

    fun requestsTo(path: String): Int = synchronized(requests) { requests.count { it.url.encodedPath == path } }

    override fun close() = server.close()
}

suspend fun RelayClient.nextOutput(): RelayClient.Output = within { outputs.receive() }

/** The next connection state, skipping messages. */
suspend fun RelayClient.nextState(): ConnectionState {
    while (true) (nextOutput() as? RelayClient.Output.State)?.let { return it.state }
}

/** The next relay message, skipping states. */
suspend fun RelayClient.nextMessage(): ServerMessage {
    while (true) (nextOutput() as? RelayClient.Output.Message)?.let { return it.message }
}
