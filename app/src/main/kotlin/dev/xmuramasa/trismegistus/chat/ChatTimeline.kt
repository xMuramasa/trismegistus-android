package dev.xmuramasa.trismegistus.chat

import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.HistoryMessage
import dev.xmuramasa.trismegistus.core.state.CommandLog
import dev.xmuramasa.trismegistus.core.state.OutgoingPrompt
import dev.xmuramasa.trismegistus.core.state.RunSummary
import dev.xmuramasa.trismegistus.core.state.SessionState
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An image on a user message: uploaded to the relay ([Remote]), or still the bytes the app sends ([Local]). */
sealed interface ChatImage {
    data class Remote(val id: String) : ChatImage

    class Local(val bytes: ByteArray) : ChatImage
}

/** One row of a conversation. [key] is stable across recompositions. Times are unix ms. */
sealed interface ChatItem {
    val key: String

    /** A message of yours; [status] is set while it is a prompt the history doesn't have yet. */
    data class User(
        override val key: String,
        val text: String,
        val time: Long?,
        val images: List<ChatImage> = emptyList(),
        val status: OutgoingPrompt.Status? = null,
        val promptId: String? = null,
    ) : ChatItem

    data class Answer(override val key: String, val text: String, val time: Long?) : ChatItem

    /** A tool call, folded: [summary] is its command or main argument, [output] what came back. */
    data class Tool(
        override val key: String,
        val tool: String,
        val summary: String?,
        val output: String?,
        val duration: Double?,
        val isError: Boolean,
        val isRunning: Boolean = false,
    ) : ChatItem

    data class Subagent(override val key: String, val goal: String, val duration: Double?, val status: String?) : ChatItem

    /** One per run with commands: opens the transcript. */
    data class Marker(override val key: String, val runId: String, val summary: RunSummary) : ChatItem

    data class Note(override val key: String, val text: String, val isError: Boolean) : ChatItem
}

/**
 * A session's rows: the history, then the prompts the history doesn't have yet, each followed by its run's live
 * events, then the live events of other runs. A run's commands are summed up in one marker (the transcript has them);
 * other tools are folded rows.
 */
object ChatTimeline {
    /** What a folded tool row shows of its output. */
    const val OUTPUT_LIMIT: Int = 4000

    fun items(state: SessionState): List<ChatItem> {
        val items = history(state.history).toMutableList()
        val runs = state.events.groupBy { it.runId }
        val placed = mutableSetOf<String>()
        for (p in state.outbox) {
            items += ChatItem.User("p${p.id}", p.text, null, p.images.map { ChatImage.Local(it.data) }, p.status, p.id)
            val runId = (p.status as? OutgoingPrompt.Status.Sent)?.runId ?: continue
            runs[runId]?.let { items += run(runId, it) }
            placed += runId
        }
        for ((runId, events) in runs) if (runId !in placed) items += run(runId, events)
        return items
    }

    /** Tools started in a run, for the WORKING line. */
    fun toolCount(state: SessionState, runId: String): Int =
        state.events.count { it.runId == runId && it.payload is EventPayload.ToolStarted }

    private fun history(messages: List<HistoryMessage>): List<ChatItem> {
        val results = messages.filter { it.role == HistoryMessage.Role.TOOL && it.toolCallId != null }.associateBy { it.toolCallId }
        return messages.flatMap { m ->
            val time = m.timestamp?.let { (it * 1000).roundToLong() }
            when (m.role) {
                HistoryMessage.Role.USER -> listOf(
                    ChatItem.User("h${m.id}", m.content, time, m.attachments.map { ChatImage.Remote(it.id) }),
                )
                HistoryMessage.Role.ASSISTANT ->
                    listOfNotNull(m.content.takeIf { it.isNotBlank() }?.let { ChatItem.Answer("h${m.id}", it, time) }) +
                        m.toolCalls.mapIndexed { i, c ->
                            ChatItem.Tool(
                                "h${m.id}.$i", c.name, argumentSummary(c.arguments),
                                c.id?.let { results[it] }?.content?.take(OUTPUT_LIMIT), duration = null, isError = false,
                            )
                        }
                HistoryMessage.Role.TOOL -> emptyList() // shown in its call's row
            }
        }
    }

    private fun run(runId: String, events: List<ServerMessage.Event>): List<ChatItem> {
        val entries = CommandLog.entries(events)
        val rows = mutableListOf<Pair<Long, ChatItem>>()
        val commands = entries.filter { it.isCommand }
        if (commands.isNotEmpty()) rows += commands.first().id to ChatItem.Marker("m$runId", runId, CommandLog.summary(entries, runId))
        for (t in entries) {
            if (t.isCommand) continue
            rows += t.id to ChatItem.Tool("t$runId.${t.id}", t.tool, t.command, t.output?.take(OUTPUT_LIMIT), t.duration, t.isError, t.isRunning)
        }
        val subagents = mutableMapOf<String, Int>()
        for (e in events) {
            val key = "e$runId.${e.seq}"
            when (val p = e.payload) {
                is EventPayload.Answer -> rows += e.seq to ChatItem.Answer(key, p.text, e.ts)
                is EventPayload.SubagentStarted -> {
                    subagents[p.subagentId] = rows.size
                    rows += e.seq to ChatItem.Subagent(key, p.goal ?: "Subagent", null, null)
                }
                is EventPayload.SubagentCompleted -> {
                    val i = subagents[p.subagentId]
                    if (i != null) {
                        val (seq, row) = rows[i]
                        rows[i] = seq to (row as ChatItem.Subagent).copy(duration = p.duration, status = p.status)
                    } else {
                        rows += e.seq to ChatItem.Subagent(key, p.summary ?: "Subagent", p.duration, p.status)
                    }
                }
                is EventPayload.RunFailed -> rows += e.seq to ChatItem.Note(key, p.error ?: "The run failed.", isError = true)
                is EventPayload.RunCancelled -> rows += e.seq to ChatItem.Note(key, "Stopped.", isError = false)
                is EventPayload.Reconnected ->
                    rows += e.seq to ChatItem.Note(key, "The relay lost Hermes' stream for a moment: some steps may be missing.", isError = false)
                else -> Unit
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    /** A tool call's main argument: `command`, `path`, `query` or the first string; else the raw text, cut. */
    internal fun argumentSummary(arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        val o = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() ?: return arguments.take(120)
        fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return str("command") ?: str("path") ?: str("query")
            ?: o.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: arguments.take(120)
    }
}
