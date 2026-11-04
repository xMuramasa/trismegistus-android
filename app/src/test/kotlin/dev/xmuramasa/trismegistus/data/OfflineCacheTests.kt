package dev.xmuramasa.trismegistus.data

import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.AttachmentRef
import dev.xmuramasa.trismegistus.core.relay.HistoryMessage
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import dev.xmuramasa.trismegistus.core.state.SessionState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineCacheTests {
    private val dir = createTempDirectory("offline").toFile()
    private val file = File(dir, "offline.json")
    private val sessions = listOf(
        SessionInfo("a", source = "api_server", title = "Runbook", lastActive = 1.5, pinned = true, model = "claude-opus-5-5"),
        SessionInfo("b", source = "telegram"),
    )
    private val history = listOf(
        HistoryMessage("1", HistoryMessage.Role.USER, "Look", timestamp = 12.5, attachments = listOf(AttachmentRef("a".repeat(64), "image/jpeg", 4, 3))),
        HistoryMessage("2", HistoryMessage.Role.ASSISTANT, "", toolCalls = listOf(HistoryMessage.ToolCall("c1", "terminal", """{"command":"ls"}"""))),
        HistoryMessage("3", HistoryMessage.Role.TOOL, "out", toolCallId = "c1", toolName = "terminal"),
        HistoryMessage("4", HistoryMessage.Role.USER, "with the camera", displayKind = HistoryMessage.STEER),
    )
    private val events = listOf(
        ServerMessage.Event("a", "r1", 1, 1000, EventPayload.ToolStarted("terminal", "ls")),
        ServerMessage.Event("a", "r1", 2, 2000, EventPayload.ApprovalRequested("q1", "rm -rf build", "Clean", listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))),
        ServerMessage.Event("a", "r1", 3, 3000, EventPayload.RunCompleted),
    )

    @Test
    fun roundTrip() {
        val cache = OfflineCache(file)
        val open = mapOf("a" to SessionState("a", history = history, historyLoaded = true, events = events))
        val snapshot = OfflineCache.snapshot(sessions, open, previous = null)
        cache.write(snapshot)
        assertEquals(snapshot, cache.read())
        assertEquals(SessionState("a", history = history, historyLoaded = true, events = events), cache.read()?.state("a"))
    }

    @Test
    fun keepsClosedTimelinesDropsUnlistedOnesAndCapsEach() {
        val many = (1..250L).map { ServerMessage.Event("a", "r", it, it, EventPayload.Answer("$it")) }
        val first = OfflineCache.snapshot(sessions, mapOf("a" to SessionState("a", historyLoaded = true, events = many)), null)
        assertEquals(OfflineCache.MAX_ITEMS, first.timelines.getValue("a").events.size)
        assertEquals(51L, first.timelines.getValue("a").events.first().seq)
        val closed = OfflineCache.snapshot(sessions, emptyMap(), first)
        assertEquals(first.timelines, closed.timelines)
        val unlisted = OfflineCache.snapshot(sessions.drop(1), emptyMap(), first)
        assertEquals(emptyMap(), unlisted.timelines)
    }

    @Test
    fun anUnloadedSessionIsNotCachedAndClearRemovesTheFile() {
        val cache = OfflineCache(file)
        val snapshot = OfflineCache.snapshot(sessions, mapOf("a" to SessionState("a")), null)
        assertEquals(emptyMap(), snapshot.timelines)
        cache.write(snapshot)
        cache.clear()
        assertNull(cache.read())
    }

    @Test
    fun aDamagedFileReadsAsNoCache() {
        file.writeText("{not json")
        assertNull(OfflineCache(file).read())
    }
}
