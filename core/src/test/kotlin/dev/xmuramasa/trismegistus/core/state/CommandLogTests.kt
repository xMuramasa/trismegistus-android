package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Hermes SSE frames recorded by the relay's probe (fixtures/<name>.jsonl) as the events the relay would send. Only the
 * tool kinds are mapped; the rest are dropped. seq counts from 1.
 */
private fun probeEvents(name: String): List<ServerMessage.Event> {
    val text = CommandLogTests::class.java.getResource("/fixtures/$name.jsonl")!!.readText()
    return text.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.mapNotNull { f ->
        fun str(k: String) = (f[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun num(k: String) = (f[k] as? JsonPrimitive)?.doubleOrNull
        val tool = str("tool") ?: ""
        when (str("event")) {
            "tool.started" -> EventPayload.ToolStarted(tool, str("preview"))
            "tool.completed" -> EventPayload.ToolCompleted(tool, num("duration"), (f["error"] as? JsonPrimitive)?.booleanOrNull ?: false, str("preview"))
            else -> null
        }?.let { f to it }
    }.mapIndexed { i, (f, payload) ->
        val ts = ((f["timestamp"] as JsonPrimitive).doubleOrNull!! * 1000).toLong()
        ServerMessage.Event("s1", (f["run_id"] as JsonPrimitive).content, i + 1L, ts, payload)
    }
}

private fun started(seq: Long, tool: String = "terminal", preview: String? = null, run: String = "r1") =
    ServerMessage.Event("s1", run, seq, seq * 1000, EventPayload.ToolStarted(tool, preview))

private fun completed(seq: Long, preview: String? = null, tool: String = "terminal", duration: Double? = 1.0, error: Boolean = false, run: String = "r1") =
    ServerMessage.Event("s1", run, seq, seq * 1000, EventPayload.ToolCompleted(tool, duration, error, preview))

private const val OK = """{"output":"","exit_code":0,"error":null}"""

class CommandLogTests {
    @Test
    fun recordedTerminalCall() {
        val e = CommandLog.entries(probeEvents("tool")).single()
        assertEquals("date +%Y", e.command)
        assertEquals("2026", e.output)
        assertEquals(0, e.exitCode)
        assertNull(e.errorText)
        assertEquals(0.15, e.duration)
        assertTrue(!e.isRunning && !e.isTruncated && !e.failed)
        assertEquals(1_792_801_004_583, e.time)
    }

    @Test
    fun blockedCommandIsAFailureWithItsReason() {
        val e = CommandLog.entries(probeEvents("approval")).first()
        assertTrue(e.command!!.startsWith("rm -rf"))
        assertTrue(e.isError && e.failed)
        assertEquals(-1, e.exitCode)
        assertEquals("", e.output)
        assertTrue(e.errorText!!.startsWith("BLOCKED: Command denied by user."))
    }

    @Test
    fun subagentDelegationIsAToolRowNotACommand() {
        val events = probeEvents("subagent")
        val all = CommandLog.entries(events, CommandLog.Filter.ALL)
        assertEquals(listOf("delegate_task"), all.map { it.tool })
        assertTrue("dispatched" in all[0].output!!)
        assertTrue(all[0].exitCode == null && !all[0].isTruncated)
        assertTrue(CommandLog.entries(events, CommandLog.Filter.COMMANDS).isEmpty())
    }

    @Test
    fun cutPreviewIsKeptRawAndMarkedTruncated() {
        val cut = """{"output": "line 1\nline 2\nlin"""
        val e = CommandLog.entries(listOf(started(1, preview = "cat big"), completed(2, cut))).single()
        assertEquals(cut, e.output)
        assertTrue(e.isTruncated && e.exitCode == null && !e.isRunning)
    }

    @Test
    fun startedWithoutCompletedIsRunning() {
        val e = CommandLog.entries(listOf(started(1, preview = "sleep 9"))).single()
        assertTrue(e.isRunning && e.duration == null && e.output == null)
    }

    @Test
    fun completionsPairWithTheOldestOpenCallOfTheSameToolAndRun() {
        val e = CommandLog.entries(
            listOf(
                started(1, preview = "a"), started(2, preview = "b"), started(3, "web_search", "q"),
                completed(4, """{"output":"A","exit_code":0,"error":null}""", duration = 1.0),
                completed(5, """{"output":"B","exit_code":2,"error":null}""", duration = 2.0, error = true),
            ),
        )
        assertEquals(listOf("a", "b", "q"), e.map { it.command })
        assertEquals(listOf("A", "B", null), e.map { it.output })
        assertEquals(listOf(false, false, true), e.map { it.isRunning })
        assertEquals(listOf(1L, 2, 3), e.map { it.id })
    }

    @Test
    fun completionWithoutStartKeepsItsOutput() {
        val e = CommandLog.entries(listOf(completed(7, """{"output":"x","exit_code":0,"error":null}"""))).single()
        assertTrue(e.command == null && e.output == "x" && !e.isRunning)
    }

    @Test
    fun commandsFilterKeepsOnlyTheTerminalTool() {
        val events = listOf(started(1, preview = "ls"), completed(2, OK), started(3, "read_file", "a.txt"), completed(4, "text", tool = "read_file"))
        assertEquals(listOf("terminal"), CommandLog.entries(events, CommandLog.Filter.COMMANDS).map { it.tool })
        assertEquals(listOf("terminal", "read_file"), CommandLog.entries(events, CommandLog.Filter.ALL).map { it.tool })
    }

    @Test
    fun summaryCountsCommandsDurationAndFailures() {
        val entries = CommandLog.entries(
            listOf(
                started(1, preview = "a"), completed(2, OK, duration = 1.5),
                started(3, preview = "b"), completed(4, """{"output":"","exit_code":1,"error":null}""", duration = 2.0),
                started(5, preview = "c"), completed(6, OK, duration = 2.6, error = true),
                started(7, preview = "d"), // still running: counted, no duration
                started(8, "read_file", "f"), completed(9, "x", tool = "read_file", duration = 50.0),
                started(10, preview = "other run", run = "r2"), completed(11, OK, duration = 9.0, run = "r2"),
            ),
        )
        val s = CommandLog.summary(entries, runId = "r1")
        assertEquals(RunSummary(4, 6.1, 2), s)
        assertEquals("4 COMMANDS · 6.1 s · 2 failed", s.label)
        assertEquals(5, CommandLog.summary(entries).commandCount)
        assertEquals("1 COMMAND · 0.1 s", RunSummary(1, 0.15, 0).label)
    }
}
