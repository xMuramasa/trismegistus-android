package dev.xmuramasa.trismegistus.core.relay

import dev.xmuramasa.trismegistus.core.FAKE_TOKEN
import dev.xmuramasa.trismegistus.core.FakeRelay
import dev.xmuramasa.trismegistus.core.RelayConn
import dev.xmuramasa.trismegistus.core.TestClock
import dev.xmuramasa.trismegistus.core.event
import dev.xmuramasa.trismegistus.core.example
import dev.xmuramasa.trismegistus.core.nextMessage
import dev.xmuramasa.trismegistus.core.nextOutput
import dev.xmuramasa.trismegistus.core.nextState
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.protocol.Wire
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/** RelayClient against the relay on MockWebServer and TestClock: real sockets, no real time. */
class RelayClientTests {
    private val relay = FakeRelay()
    private val clock = TestClock()
    private val client = client()

    private fun client(jitter: Double = 0.0) =
        RelayClient(relay.url, FAKE_TOKEN, sleep = clock::sleep, jitter = { jitter }, now = clock::nowMillis)

    @AfterTest
    fun tearDown() {
        client.stop()
        relay.close()
    }

    /** Waits for the index-th connection's auth and answers ready. */
    private suspend fun connect(index: Int): RelayConn {
        val c = relay.connection(index)
        assertEquals(ClientMessage.Auth(FAKE_TOKEN), c.next())
        c.push(READY)
        return c
    }

    @Test
    fun authThenReady(): Unit = runBlocking {
        client.start()
        val c = relay.connection(0)
        assertEquals(1, relay.requestsTo("/ws"))
        assertEquals(ClientMessage.Auth(FAKE_TOKEN), c.next())
        assertFailsWith<RelayClient.NotConnectedException> { client.send(ClientMessage.Stop("run_1")) }
        c.pushRaw(example("ready.json"))
        assertEquals(RelayClient.Output.State(ConnectionState.Connecting), client.nextOutput())
        assertEquals(RelayClient.Output.Message(READY), client.nextOutput())
        assertEquals(RelayClient.Output.State(ConnectionState.Connected("pixel-8", "0.1.0")), client.nextOutput())
        client.send(ClientMessage.Stop("run_1"))
        assertEquals(ClientMessage.Stop("run_1"), c.next())
    }

    @Test
    fun authRejectedIsSurfacedAndNeverRetried(): Unit = runBlocking {
        client.start()
        val c = relay.connection(0)
        c.next()
        c.pushRaw(example("error-unauthorized-no-ref.json"))
        assertEquals(RelayClient.Output.State(ConnectionState.Connecting), client.nextOutput())
        assertEquals(RelayClient.Output.Message(ServerMessage.Error(ErrorCode.UNAUTHORIZED, "unauthorized")), client.nextOutput())
        assertEquals(RelayClient.Output.State(ConnectionState.AuthFailed("unauthorized")), client.nextOutput())
        assertFalse(client.isRunning)
        clock.awaitNoSleepers() // no backoff pending
        assertEquals(1, relay.connections.value.size)
    }

    @Test
    fun subscribeReplaysThenLiveInOrder(): Unit = runBlocking {
        client.subscribe(SESSION, afterSeq = 2)
        client.start()
        val c = connect(0)
        assertEquals(ClientMessage.Subscribe(SESSION, 2), c.next())
        val frames = listOf(
            "event-tool-started.json", "event-tool-completed.json", "run-running.json", "event-answer.json", "event-run-completed.json",
        ).map(::example)
        frames.forEach { c.pushRaw(it) }
        assertEquals(READY, client.nextMessage())
        for (f in frames) assertEquals(Wire.decodeServer(f), client.nextMessage())

        client.subscribe("s2")
        assertEquals(ClientMessage.Subscribe("s2"), c.next())
    }

    @Test
    fun approvalIsAnsweredWithItsRequestId(): Unit = runBlocking {
        client.subscribe(SESSION)
        client.start()
        val c = connect(0)
        c.next()
        c.pushRaw(example("event-approval-requested.json"))
        assertEquals(READY, client.nextMessage())
        val e = assertIs<ServerMessage.Event>(client.nextMessage())
        val request = assertIs<EventPayload.ApprovalRequested>(e.payload)
        assertEquals(listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY), request.choices)
        client.send(ClientMessage.Approve(e.runId, request.requestId, ApprovalChoice.ONCE))
        assertEquals(
            ClientMessage.Approve("run_43f98388dbc54cd1ab911657e79cf8af", "a990e19b6d5049798852410f79f88255", ApprovalChoice.ONCE),
            c.next(),
        )
        c.pushRaw(example("event-approval-resolved.json"))
        assertIs<EventPayload.ApprovalResolved>(assertIs<ServerMessage.Event>(client.nextMessage()).payload)
    }

    @Test
    fun pongsKeepTheConnection(): Unit = runBlocking {
        client.start()
        val c = connect(0)
        assertEquals(READY, client.nextMessage())
        repeat(3) {
            clock.fire(RelayClient.PING_INTERVAL)
            assertEquals(ClientMessage.Ping, c.next())
            c.pushRaw(example("pong.json"))
            assertEquals(ServerMessage.Pong, client.nextMessage())
        }
        assertFalse(c.isClosed)
        assertEquals(1, relay.connections.value.size)
    }

    /** trismegistus#6 and #8: a dead socket stops being Connected, and the next one resumes every session from its seq. */
    @Test
    fun missedPongsDropAndReconnectWithBackoffFromTheLastSeq(): Unit = runBlocking {
        client.subscribe("s1")
        client.subscribe("s2", afterSeq = 7)
        client.start()
        val c0 = connect(0)
        c0.next()
        c0.next()
        c0.push(event("s1", 4, EventPayload.SteerQueued), event("s2", 9, EventPayload.RunCompleted))
        repeat(3) { client.nextMessage() } // ready and the two events

        // Pings at 25 s and 50 s go unanswered; at 75 s the second miss drops the socket, like the relay does.
        clock.fire(RelayClient.PING_INTERVAL)
        clock.fire(RelayClient.PING_INTERVAL)
        assertEquals(listOf<ClientMessage>(ClientMessage.Ping, ClientMessage.Ping), listOf(c0.next(), c0.next()))
        assertIs<ConnectionState.Connected>(client.state.value)
        clock.fire(RelayClient.PING_INTERVAL)
        c0.awaitClosed()
        assertEquals(ConnectionState.Reconnecting(1, 76_000), client.nextState())
        assertEquals(ConnectionState.Reconnecting(1, 76_000), client.state.value)

        clock.fire(1.seconds)
        val c1 = connect(1)
        assertEquals(
            setOf<ClientMessage>(ClientMessage.Subscribe("s1", 4), ClientMessage.Subscribe("s2", 9)),
            setOf(c1.next(), c1.next()),
        )

        // Never got a pong: the next wait doubles.
        c1.drop()
        clock.fire(2.seconds)
        val c2 = connect(2)
        c2.next()
        c2.next()
        clock.fire(RelayClient.PING_INTERVAL)
        assertEquals(ClientMessage.Ping, c2.next())
        c2.push(ServerMessage.Pong)
        while (client.nextMessage() != ServerMessage.Pong) continue
        // A pong made the connection stable: back to 1 s.
        c2.drop()
        clock.fire(1.seconds)
        connect(3)
        val backoffs = clock.requestedSleeps().filter { it < RelayClient.READY_TIMEOUT }
        assertEquals(listOf(1.seconds, 2.seconds, 1.seconds), backoffs)
    }

    @Test
    fun serverCloseReconnects(): Unit = runBlocking {
        client.start()
        val c0 = connect(0)
        assertEquals(ConnectionState.Connecting, client.nextState())
        assertEquals(ConnectionState.Connected("pixel-8", "0.1.0"), client.nextState())
        c0.drop()
        assertEquals(ConnectionState.Reconnecting(1, 1_000), client.nextState())
        clock.fire(1.seconds)
        connect(1)
        assertEquals(ConnectionState.Connecting, client.nextState())
        assertEquals(ConnectionState.Connected("pixel-8", "0.1.0"), client.nextState())
    }

    @Test
    fun reconnectingCarriesAttemptAndJitteredTime(): Unit = runBlocking {
        // 0.625 makes the jitter 1/8 of the wait: 1.125 s, 2.25 s, 4.5 s.
        val client = client(jitter = 0.625)
        try {
            client.start()
            var at = 0L
            for ((attempt, wait) in listOf(1 to 1_125L, 2 to 2_250L, 3 to 4_500L)) {
                connect(attempt - 1).drop() // never stable: the wait keeps doubling
                var s = client.nextState()
                while (s !is ConnectionState.Reconnecting) s = client.nextState()
                assertEquals(ConnectionState.Reconnecting(attempt, at + wait), s)
                clock.fire(wait.milliseconds)
                at += wait
            }
            assertEquals(ConnectionState.Connecting, client.nextState())
        } finally {
            client.stop()
        }
    }

    @Test
    fun reconnectNowSkipsTheWaitOnly(): Unit = runBlocking {
        client.subscribe("s1")
        client.start()
        val c0 = connect(0)
        c0.next()
        c0.push(event("s1", 3, EventPayload.SteerQueued))
        repeat(2) { client.nextMessage() }
        client.reconnectNow() // connected: nothing happens
        c0.drop()
        while (client.nextState() != ConnectionState.Reconnecting(1, 1_000)) continue

        client.reconnectNow() // no clock advance
        val c1 = connect(1)
        assertEquals(ClientMessage.Subscribe("s1", 3), c1.next())
        assertFalse(clock.isSleeping(1.seconds)) // the wait was cancelled
        assertEquals(2, relay.connections.value.size)

        // The attempt count goes on: the next wait is 2 s.
        c1.drop()
        while (client.nextState() != ConnectionState.Reconnecting(2, 2_000)) continue
        clock.fire(2.seconds)
        connect(2)
    }

    @Test
    fun reconnectNowAfterAuthFailureDoesNothing(): Unit = runBlocking {
        client.start()
        val c = relay.connection(0)
        c.next()
        c.push(ServerMessage.Error(ErrorCode.UNAUTHORIZED, "unauthorized"))
        while (client.nextState() != ConnectionState.AuthFailed("unauthorized")) continue
        client.reconnectNow()
        assertFalse(client.isRunning)
        assertEquals(1, relay.connections.value.size)
    }

    @Test
    fun noReadyInTimeReconnects(): Unit = runBlocking {
        client.start()
        val c0 = relay.connection(0)
        c0.next()
        clock.fire(RelayClient.READY_TIMEOUT)
        assertEquals(1000, c0.awaitClosed())
        clock.fire(1.seconds)
        connect(1)
    }

    /** trismegistus#10, client side: a refused resume becomes a live subscription; the store reloads the history. */
    @Test
    fun replayGapResubscribesLive(): Unit = runBlocking {
        client.subscribe(GAP_SESSION, afterSeq = 40)
        client.start()
        val c = connect(0)
        assertEquals(ClientMessage.Subscribe(GAP_SESSION, 40), c.next())
        c.pushRaw(example("error-replay-gap.json"))
        assertEquals(ClientMessage.Subscribe(GAP_SESSION), c.next())
        assertEquals(READY, client.nextMessage())
        assertEquals(Wire.decodeServer(example("error-replay-gap.json")), client.nextMessage())
    }

    /** trismegistus#8: what was sent on a socket that died is sent again on the next one, once. */
    @Test
    fun unacknowledgedPromptAndSteerAreResent(): Unit = runBlocking {
        client.subscribe("s1")
        client.start()
        val c0 = connect(0)
        c0.next()
        val prompt = ClientMessage.Prompt("c1", "s1", "What year is it?")
        val steer = ClientMessage.Steer("run_1", "Shorter, please.")
        while (client.state.value !is ConnectionState.Connected) client.nextOutput()
        client.send(prompt)
        client.send(steer)
        assertEquals(prompt, c0.next())
        assertEquals(steer, c0.next())
        c0.drop()

        clock.fire(1.seconds)
        val c1 = connect(1)
        assertEquals(ClientMessage.Subscribe("s1"), c1.next())
        assertEquals(prompt, c1.next()) // same client_msg_id: the relay answers ack replayed if it had it
        c1.assertQuiet() // the steer waits for the run's status
        c1.push(ServerMessage.Run("run_1", "s1", RunStatus.RUNNING))
        assertEquals(steer, c1.next())
        c1.push(ServerMessage.Ack("c1", "run_1", replayed = true), event("s1", 1, EventPayload.SteerQueued))
        while (client.nextMessage() !is ServerMessage.Event) continue
        c1.drop()

        clock.fire(2.seconds)
        val c2 = connect(2)
        assertEquals(ClientMessage.Subscribe("s1", 1), c2.next())
        c2.push(ServerMessage.Run("run_1", "s1", RunStatus.RUNNING))
        c2.assertQuiet() // both were answered: nothing to resend
    }

    @Test
    fun aSteerQueuedInTheReplayIsNotResent(): Unit = runBlocking {
        client.subscribe("s1")
        client.start()
        val c0 = connect(0)
        c0.next()
        while (client.state.value !is ConnectionState.Connected) client.nextOutput()
        client.send(ClientMessage.Steer("run_1", "Shorter, please."))
        c0.next()
        c0.drop()

        clock.fire(1.seconds)
        val c1 = connect(1)
        c1.next()
        // The relay had queued it before the drop: the replay says so, then the run's status follows.
        c1.push(event("s1", 1, EventPayload.SteerQueued), ServerMessage.Run("run_1", "s1", RunStatus.RUNNING))
        c1.assertQuiet()
    }

    @Test
    fun stopClosesAndStaysDown(): Unit = runBlocking {
        client.start()
        val c = connect(0)
        assertEquals(ConnectionState.Connecting, client.nextState())
        assertEquals(ConnectionState.Connected("pixel-8", "0.1.0"), client.nextState())
        client.stop()
        assertEquals(1000, c.awaitClosed())
        assertEquals(ConnectionState.Disconnected, client.nextState())
        assertFalse(client.isRunning)
        assertTrue(relay.connections.value.size == 1)
    }

    @Test
    fun webSocketUrlFollowsTheBase() {
        assertEquals("https://relay.invalid/ws", RelayClient.webSocketUrl("https://relay.invalid/").toString())
        assertEquals("https://relay.invalid/r/ws", RelayClient.webSocketUrl("https://relay.invalid/r").toString())
    }

    private companion object {
        val READY = ServerMessage.Ready("pixel-8", "0.1.0")

        /** The session of the recorded examples. */
        const val SESSION = "api_1792801001_b6235dd4"

        /** error-replay-gap.json's ref. */
        const val GAP_SESSION = "api_1792800970_b3a06956"
    }
}
