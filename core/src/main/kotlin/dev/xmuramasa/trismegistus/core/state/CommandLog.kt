package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.RestJson
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One tool call as the command transcript shows it: `$ command`, then output, exit code and duration. */
public data class TerminalEntry(
    /** The seq of the call's first event. */
    val id: Long,
    val runId: String,
    val tool: String,
    /** Unix milliseconds of the first event. */
    val time: Long,
    /** The `tool.started` preview; null when the start was missed (replay gap). */
    val command: String? = null,
    /** Terminal tool: the parsed `output`. Other tools and unparsable previews: the raw preview. */
    val output: String? = null,
    val exitCode: Int? = null,
    /** The terminal tool's own `error` text (e.g. "BLOCKED: Command denied by user. ..."). */
    val errorText: String? = null,
    /** The completed event's error flag. */
    val isError: Boolean = false,
    /** The terminal preview was not valid JSON (the relay cuts previews at 4,000 characters); [output] is the raw text. */
    val isTruncated: Boolean = false,
    /** Seconds; null while running. */
    val duration: Double? = null,
    /** Started, not completed. */
    val isRunning: Boolean = false,
) {
    public val isCommand: Boolean get() = tool == CommandLog.TERMINAL_TOOL
    public val failed: Boolean get() = isError || (exitCode ?: 0) != 0
}

/** What a run's chat marker shows: `4 COMMANDS · 6.1 s · 1 failed`. */
public data class RunSummary(val commandCount: Int, val totalDuration: Double, val failedCount: Int) {
    public val label: String
        get() {
            val seconds = BigDecimal(totalDuration).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
            val parts = listOf("$commandCount ${if (commandCount == 1) "COMMAND" else "COMMANDS"}", "$seconds s") +
                listOfNotNull(if (failedCount > 0) "$failedCount failed" else null)
            return parts.joinToString(" · ")
        }
}

/** The per-run command log from a session's live `tool.started` / `tool.completed` events. */
public object CommandLog {
    public const val TERMINAL_TOOL: String = "terminal"

    public enum class Filter { COMMANDS, ALL }

    /**
     * Pairs `tool.started` with `tool.completed` in order. The events carry no call id, so a completion belongs to the
     * oldest unfinished call of the same tool in the same run; a completion without a start (replay gap) is its own entry.
     */
    public fun entries(events: List<ServerMessage.Event>, filter: Filter = Filter.ALL): List<TerminalEntry> {
        val entries = mutableListOf<TerminalEntry>()
        val open = mutableMapOf<String, ArrayDeque<Int>>() // "run|tool" -> indexes of running entries, oldest first
        for (e in events) {
            when (val p = e.payload) {
                is EventPayload.ToolStarted -> {
                    open.getOrPut("${e.runId}|${p.tool}") { ArrayDeque() }.addLast(entries.size)
                    entries += TerminalEntry(e.seq, e.runId, p.tool, e.ts, command = p.preview, isRunning = true)
                }
                is EventPayload.ToolCompleted -> {
                    val i = open["${e.runId}|${p.tool}"]?.removeFirstOrNull()
                        ?: entries.size.also { entries += TerminalEntry(e.seq, e.runId, p.tool, e.ts, isRunning = true) }
                    entries[i] = complete(entries[i], p)
                }
                else -> Unit
            }
        }
        return if (filter == Filter.COMMANDS) entries.filter { it.isCommand } else entries
    }

    /** Commands of one run (or of every run in [entries]). */
    public fun summary(entries: List<TerminalEntry>, runId: String? = null): RunSummary {
        val commands = entries.filter { it.isCommand && (runId == null || it.runId == runId) }
        return RunSummary(commands.size, commands.sumOf { it.duration ?: 0.0 }, commands.count { it.failed })
    }

    private fun complete(entry: TerminalEntry, c: EventPayload.ToolCompleted): TerminalEntry {
        val done = entry.copy(isRunning = false, isError = c.error, duration = c.duration)
        val preview = c.preview ?: return done
        if (!done.isCommand) return done.copy(output = preview)
        val result = runCatching { RestJson.decodeFromString(TerminalResult.serializer(), preview) }.getOrNull()
            ?: return done.copy(output = preview, isTruncated = true)
        return done.copy(output = result.output, exitCode = result.exitCode, errorText = result.error)
    }
}

/** The terminal tool's `tool.completed` preview. */
@Serializable
private data class TerminalResult(
    val output: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val error: String? = null,
)
