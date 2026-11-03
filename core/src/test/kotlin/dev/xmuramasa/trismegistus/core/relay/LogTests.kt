package dev.xmuramasa.trismegistus.core.relay

import dev.xmuramasa.trismegistus.core.FAKE_TOKEN
import dev.xmuramasa.trismegistus.core.FakeRelay
import dev.xmuramasa.trismegistus.core.TestClock
import dev.xmuramasa.trismegistus.core.event
import dev.xmuramasa.trismegistus.core.nextMessage
import dev.xmuramasa.trismegistus.core.nextOutput
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/** The SPEC's logging rule: ids and kinds, never message text, approval commands or tokens. */
class LogTests {
    private val prompt = "What is in my secret diary?"
    private val steer = "Read the diary aloud"
    private val command = "cat ~/private/diary.txt"
    private val answer = "Dear diary, today"
    private val relayMessage = "a run is already active in this session"

    @Test
    fun noSecretsInTheLog(): Unit = runBlocking {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val relay = FakeRelay()
        val clock = TestClock()
        val client = RelayClient(
            relay.url, FAKE_TOKEN, sleep = clock::sleep, jitter = { 0.0 }, now = clock::nowMillis,
            logger = { level, message -> lines += "$level $message" },
        )
        try {
            client.subscribe("s1")
            client.start()
            val c0 = relay.connection(0)
            c0.next()
            c0.push(ServerMessage.Ready("pixel-8", "0.1.0"))
            c0.next()
            while (client.state.value !is ConnectionState.Connected) client.nextOutput()
            client.send(ClientMessage.Prompt("c1", "s1", prompt))
            c0.next()
            val request = EventPayload.ApprovalRequested("req_1", command, "recursive read", listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))
            c0.push(
                ServerMessage.Ack("c1", "run_1", replayed = false),
                event("s1", 1, request),
                event("s1", 2, EventPayload.Answer(answer)),
                ServerMessage.Error(ErrorCode.BUSY, relayMessage, "c2"),
            )
            while (client.nextMessage() !is ServerMessage.Error) continue
            client.send(ClientMessage.Approve("run_1", "req_1", ApprovalChoice.ONCE))
            client.send(ClientMessage.Steer("run_1", steer))
            c0.next()
            c0.next()
            c0.drop() // the steer is pending: the reconnect logs what it holds
            clock.fire(1.seconds)
            val c1 = relay.connection(1)
            c1.next()
            c1.push(ServerMessage.Ready("pixel-8", "0.1.0"))
            c1.next()
        } finally {
            client.stop()
            relay.close()
        }
        val log = lines.joinToString("\n")
        assertTrue(lines.size > 5, log)
        for (secret in listOf(FAKE_TOKEN, "FAKEFAKE", prompt, steer, command, answer, relayMessage)) {
            assertFalse(secret in log, "the log holds '$secret':\n$log")
        }
        assertFalse("FAKEFAKE" in client.toString())
        assertFalse("FAKEFAKE" in Pairing(FAKE_TOKEN, "pixel-8").toString())
    }
}
