package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.FAKE_TOKEN
import dev.xmuramasa.trismegistus.core.FakeRelay
import dev.xmuramasa.trismegistus.core.TestClock
import dev.xmuramasa.trismegistus.core.event
import dev.xmuramasa.trismegistus.core.eventually
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.core.relay.RelayApiException
import dev.xmuramasa.trismegistus.core.relay.RelayClient
import dev.xmuramasa.trismegistus.core.relay.RelayRest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/** SessionStore with REST and the socket on MockWebServer; most tests feed client outputs through handle(). */
class SessionStoreTests {
    private val relay = FakeRelay { r ->
        when (r.method to r.url.encodedPath) {
            "GET" to "/api/sessions/s1/messages", "GET" to "/api/sessions/s2/messages" -> 200 to HISTORY
            "GET" to "/api/sessions" -> 200 to """{"data":[{"id":"s1","source":"api_server","title":"Dates"}]}"""
            "POST" to "/api/sessions" -> 201 to """{"session":{"id":"s3","source":"api_server"}}"""
            "PATCH" to "/api/sessions/s1" -> if (r.body?.utf8()?.contains("Years") == true) {
                200 to """{"session":{"id":"s1","title":"Years"}}"""
            } else {
                403 to """{"error":{"code":"read_only","message":"this session is read-only here"}}"""
            }
            "DELETE" to "/api/sessions/s1" -> 200 to """{"deleted":true}"""
            else -> 404 to """{"error":{"code":"not_found","message":"not found"}}"""
        }
    }
    private val clock = TestClock() // never advanced: no timer fires
    private val client = RelayClient(relay.url, FAKE_TOKEN, sleep = clock::sleep, now = clock::nowMillis)
    private val store = SessionStore(client, RelayRest(relay.url, FAKE_TOKEN), now = clock::nowMillis)

    @AfterTest
    fun tearDown() {
        store.stop()
        relay.close()
    }

    private fun historyLoads(id: String) = relay.requestsTo("/api/sessions/$id/messages")

    private fun session(id: String) = store.sessions.value.getValue(id)

    /** start() wires the client's outputs into handle(): a replay_gap from the socket reloads the history over REST. */
    @Test
    fun feedWiresTheClientToTheStore(): Unit = runBlocking {
        store.open("s1")
        store.start(this)
        val c = relay.connection(0)
        c.next()
        c.push(ServerMessage.Ready("pixel-8", "0.1.0"))
        assertEquals(ClientMessage.Subscribe("s1"), c.next())
        c.push(ServerMessage.Error(ErrorCode.REPLAY_GAP, "reload the history", "s1"))
        eventually { historyLoads("s1") == 2 }
        eventually { store.connection.value is ConnectionState.Connected }
        store.stop()
    }

    /** trismegistus#10 end to end: replay_gap -> history reloaded over REST -> events from seq 1 shown. */
    @Test
    fun replayGapReloadsHistoryOverRest() = runTest {
        store.open("s1")
        assertEquals(1, historyLoads("s1"))
        assertTrue(session("s1").historyLoaded)
        store.handle(RelayClient.Output.Message(event("s1", 40, EventPayload.RunCompleted)))
        store.handle(RelayClient.Output.Message(ServerMessage.Error(ErrorCode.REPLAY_GAP, "reload the history", "s1")))
        assertEquals(2, historyLoads("s1"))
        assertTrue(session("s1").historyLoaded && session("s1").history.size == 2)
        store.handle(RelayClient.Output.Message(event("s1", 1, EventPayload.ToolStarted("terminal"), run = "run_2")))
        assertEquals(listOf(1L), session("s1").events.map { it.seq })
    }

    @Test
    fun reconnectReloadsSessionsThatCouldNotResume() = runTest {
        store.open("s1")
        store.open("s2")
        val connected = RelayClient.Output.State(ConnectionState.Connected("pixel-8", "0.1.0"))
        store.handle(connected)
        store.handle(RelayClient.Output.Message(event("s2", 5, EventPayload.SteerQueued)))
        store.handle(RelayClient.Output.State(ConnectionState.Disconnected))
        store.handle(connected)
        assertEquals(2, historyLoads("s1")) // no seq: resubscribed live only, so reload
        assertEquals(1, historyLoads("s2")) // resumed from seq 5
        assertEquals(connected.state, store.connection.value)
    }

    @Test
    fun historyErrorIsKeptAndNotRetriedInALoop() = runTest {
        store.open("s9") // 404
        assertEquals("not found", session("s9").historyError)
        store.handle(RelayClient.Output.Message(ServerMessage.Pong))
        assertEquals(1, historyLoads("s9"))
    }

    @Test
    fun promptWhileDisconnectedFails() = runTest {
        store.open("s1")
        val id = store.sendPrompt("s1", "hi")
        assertEquals(
            listOf(OutgoingPrompt(id, "hi", OutgoingPrompt.Status.Failed(null, "Not connected to the relay."))),
            session("s1").outbox,
        )
        val again = store.retryPrompt("s1", id) // in place of the old entry: still one, failed again
        assertEquals(listOf(again), session("s1").outbox.map { it.id })
        assertNull(store.retryPrompt("s1", "not-a-prompt"))
    }

    @Test
    fun approvalsAcrossSessions() = runTest {
        store.open("s1")
        val request = EventPayload.ApprovalRequested("req_1", "rm -rf x", choices = listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY))
        store.handle(RelayClient.Output.Message(event("s1", 1, request)))
        assertEquals(listOf("req_1"), store.pendingApprovals.value.map { it.id })
        assertTrue(store.isWorking)
    }

    /** The list is followed whole, so an approval in a conversation that isn't open still shows. */
    @Test
    fun approvalInAListedSessionThatIsNotOpen() = runTest {
        store.refreshSessions()
        assertTrue(store.sessions.value.isEmpty())
        val request = EventPayload.ApprovalRequested("req_7", "rm -rf y", choices = listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))
        store.handle(RelayClient.Output.Message(event("s1", 1, request, run = "run_7")))
        assertEquals(listOf("req_7"), store.pendingApprovals.value.map { it.id })
        assertTrue(store.overview.value.getValue("s1").isWaitingForApproval)
        store.handle(RelayClient.Output.Message(event("s1", 2, EventPayload.ApprovalResolved("req_7", "once"), run = "run_7")))
        assertTrue(store.pendingApprovals.value.isEmpty())
    }

    @Test
    fun sessionListFollowsRest() = runTest {
        store.refreshSessions()
        assertEquals(listOf("s1"), store.sessionList.value.map { it.id })
        assertEquals(listOf("s1"), store.overview.value.keys.toList())
        store.createSession()
        assertEquals(listOf("s3", "s1"), store.sessionList.value.map { it.id })
        store.renameSession("s1", "Years")
        assertEquals("Years", store.sessionList.value.last().title)
        assertEquals("Years", store.overview.value.getValue("s1").info?.title)
        store.open("s1")
        store.deleteSession("s1")
        assertEquals(listOf("s3"), store.sessionList.value.map { it.id })
        assertNull(store.sessions.value["s1"])
    }

    @Test
    fun aRefusedRenameOrPinGoesBack() = runTest {
        store.refreshSessions()
        val rename = runCatching { store.renameSession("s1", "nope") }.exceptionOrNull()
        assertEquals(ErrorCode.READ_ONLY, (rename as RelayApiException).code)
        assertEquals("Dates", store.sessionList.value.single().title)
        assertEquals("Dates", store.overview.value.getValue("s1").info?.title)
        val pin = runCatching { store.setPinned("s1", true) }.exceptionOrNull()
        assertTrue(pin is RelayApiException)
        assertNull(store.sessionList.value.single().pinned)
    }

    private companion object {
        const val HISTORY =
            """{"object":"list","data":[{"id":1,"role":"user","content":"What year is it?"},{"id":2,"role":"assistant","content":"It is 2026."}]}"""
    }
}
