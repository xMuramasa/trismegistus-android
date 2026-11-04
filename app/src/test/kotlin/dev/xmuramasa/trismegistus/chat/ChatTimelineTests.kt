package dev.xmuramasa.trismegistus.chat

import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.HistoryMessage
import dev.xmuramasa.trismegistus.core.state.OutgoingPrompt
import dev.xmuramasa.trismegistus.core.state.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatTimelineTests {
    private fun event(run: String, seq: Long, payload: EventPayload) = ServerMessage.Event("s", run, seq, 1_000L * seq, payload)

    private val history = listOf(
        HistoryMessage("1", HistoryMessage.Role.USER, "List the files", timestamp = 100.0),
        HistoryMessage("2", HistoryMessage.Role.ASSISTANT, "", toolCalls = listOf(HistoryMessage.ToolCall("c1", "terminal", """{"command":"ls"}"""))),
        HistoryMessage("3", HistoryMessage.Role.TOOL, """{"output":"a.txt","exit_code":0}""", toolCallId = "c1"),
        HistoryMessage("4", HistoryMessage.Role.ASSISTANT, "There is **one** file."),
    )

    @Test
    fun historyFoldsToolResultsIntoTheirCalls() {
        val items = ChatTimeline.items(SessionState("s", history = history, historyLoaded = true))
        assertEquals(listOf("h1", "h2.0", "h4"), items.map { it.key })
        val tool = assertIs<ChatItem.Tool>(items[1])
        assertEquals("terminal", tool.tool)
        assertEquals("ls", tool.summary)
        assertEquals("""{"output":"a.txt","exit_code":0}""", tool.output)
        assertEquals(100_000L, (items[0] as ChatItem.User).time)
    }

    @Test
    fun aPromptIsFollowedByItsRunWithOneMarkerForTheCommands() {
        val events = listOf(
            event("r1", 1, EventPayload.ToolStarted("terminal", "ls")),
            event("r1", 2, EventPayload.ToolCompleted("terminal", 0.4, false, """{"output":"a","exit_code":0}""")),
            event("r1", 3, EventPayload.ToolStarted("read_file", "a.txt")),
            event("r1", 4, EventPayload.ToolCompleted("read_file", 0.1, false, "contents")),
            event("r1", 5, EventPayload.ToolStarted("terminal", "false")),
            event("r1", 6, EventPayload.ToolCompleted("terminal", 1.0, false, """{"output":"","exit_code":1}""")),
            event("r1", 7, EventPayload.Answer("Done.")),
            event("r2", 8, EventPayload.SubagentStarted("sa", goal = "Check the build")),
            event("r2", 9, EventPayload.SubagentCompleted("sa", status = "ok", duration = 2.0)),
        )
        val outbox = listOf(OutgoingPrompt("p1", "Look", OutgoingPrompt.Status.Sent("r1")))
        val items = ChatTimeline.items(SessionState("s", history = history, historyLoaded = true, events = events, outbox = outbox))
        assertEquals(listOf("h1", "h2.0", "h4", "pp1", "mr1", "tr1.3", "er1.7", "er2.8"), items.map { it.key })
        val marker = assertIs<ChatItem.Marker>(items[4])
        assertEquals("2 COMMANDS · 1.4 s · 1 failed", marker.summary.label)
        assertEquals("a.txt", (items[5] as ChatItem.Tool).summary)
        assertEquals(ChatItem.Subagent("er2.8", "Check the build", 2.0, "ok"), items[7])
    }

    @Test
    fun failedPromptsKeepTheirStatusAndEndingsAreNotes() {
        val outbox = listOf(OutgoingPrompt("p2", "Again", OutgoingPrompt.Status.Failed(null, "Not connected to the relay.")))
        val events = listOf(event("r3", 1, EventPayload.RunFailed("model error")))
        val items = ChatTimeline.items(SessionState("s", historyLoaded = true, events = events, outbox = outbox))
        assertIs<OutgoingPrompt.Status.Failed>((items[0] as ChatItem.User).status)
        assertEquals(ChatItem.Note("er3.1", "model error", isError = true), items[1])
    }

    @Test
    fun argumentSummaryPrefersTheCommand() {
        assertEquals("git status", ChatTimeline.argumentSummary("""{"cwd":"/x","command":"git status"}"""))
        assertEquals("/etc/hosts", ChatTimeline.argumentSummary("""{"path":"/etc/hosts"}"""))
        assertEquals("not json", ChatTimeline.argumentSummary("not json"))
        assertEquals(null, ChatTimeline.argumentSummary(null))
    }
}
