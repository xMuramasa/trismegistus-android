package dev.xmuramasa.trismegistus.data

import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.protocol.Wire
import dev.xmuramasa.trismegistus.core.relay.AttachmentRef
import dev.xmuramasa.trismegistus.core.relay.HistoryMessage
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import dev.xmuramasa.trismegistus.core.state.SessionState
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The session list and each conversation's recent timeline (its last [MAX_ITEMS] history messages and live events) in
 * one JSON file in the app's files dir, shown while the relay can't be reached. Cleared on unpair.
 */
class OfflineCache(private val file: File) {
    data class Timeline(val history: List<HistoryMessage>, val events: List<ServerMessage.Event>)

    data class Snapshot(val sessions: List<SessionInfo>, val timelines: Map<String, Timeline>) {
        /** A read-only stand-in for an open session while offline. */
        fun state(id: String): SessionState? =
            timelines[id]?.let { SessionState(id, history = it.history, historyLoaded = true, events = it.events) }
    }

    fun read(): Snapshot? {
        if (!file.isFile) return null
        return runCatching { decode(Json.parseToJsonElement(file.readText()) as JsonObject) }.getOrNull()
    }

    /** Through a temporary file, so a kill mid-write leaves the old cache. */
    fun write(snapshot: Snapshot) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(snapshot).toString())
        if (!tmp.renameTo(file)) tmp.delete()
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX_ITEMS: Int = 200
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * The next snapshot: the list as it is, the open sessions' timelines as they are now, the ones closed since from
         * [previous]; timelines of sessions no longer listed are dropped.
         */
        fun snapshot(list: List<SessionInfo>, open: Map<String, SessionState>, previous: Snapshot?): Snapshot {
            val ids = list.map { it.id }.toSet()
            val fresh = open.filterValues { it.historyLoaded }
                .mapValues { (_, s) -> Timeline(s.history.takeLast(MAX_ITEMS), s.events.takeLast(MAX_ITEMS)) }
            return Snapshot(list, (previous?.timelines.orEmpty() + fresh).filterKeys { it in ids })
        }

        internal fun encode(s: Snapshot): JsonObject = buildJsonObject {
            put("v", 1)
            put("sessions", JsonArray(s.sessions.map { json.encodeToJsonElement(SessionInfo.serializer(), it) }))
            putJsonObject("timelines") {
                for ((id, t) in s.timelines) {
                    putJsonObject(id) {
                        put("history", JsonArray(t.history.map(::hermesJson)))
                        put("events", JsonArray(t.events.map { Json.parseToJsonElement(Wire.encode(it)) }))
                    }
                }
            }
        }

        internal fun decode(o: JsonObject): Snapshot {
            val sessions = (o["sessions"] as JsonArray).mapNotNull {
                runCatching { json.decodeFromJsonElement(SessionInfo.serializer(), it) }.getOrNull()
            }
            val timelines = (o["timelines"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                val t = v as JsonObject
                Timeline(
                    (t["history"] as? JsonArray).orEmpty().mapNotNull(HistoryMessage::fromJson),
                    (t["events"] as? JsonArray).orEmpty().mapNotNull { Wire.decodeServerOrNull(it.toString()) as? ServerMessage.Event },
                )
            }
            return Snapshot(sessions, timelines)
        }

        /** A history message the way Hermes sends it, so [HistoryMessage.fromJson] reads it back. */
        private fun hermesJson(m: HistoryMessage): JsonElement = buildJsonObject {
            put("id", m.id)
            put("role", m.role.name.lowercase())
            put("content", m.content)
            if (m.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    for (c in m.toolCalls) {
                        addJsonObject {
                            c.id?.let { put("id", it) }
                            putJsonObject("function") {
                                put("name", c.name)
                                c.arguments?.let { put("arguments", it) }
                            }
                        }
                    }
                }
            }
            m.toolCallId?.let { put("tool_call_id", it) }
            m.toolName?.let { put("tool_name", it) }
            m.timestamp?.let { put("timestamp", it) }
            if (m.attachments.isNotEmpty()) {
                putJsonArray("attachments") { m.attachments.forEach { add(json.encodeToJsonElement(AttachmentRef.serializer(), it)) } }
            }
        }
    }
}
