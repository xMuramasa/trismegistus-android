package dev.xmuramasa.trismegistus.core.protocol

import dev.xmuramasa.trismegistus.core.relay.AttachmentRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

/** An event's `kind` with its typed `data`: every kind in schema.json's `event_kind`. */
public sealed interface EventPayload {
    @Serializable
    public data class ToolStarted(val tool: String, val preview: String? = null) : EventPayload

    /** [duration] is in seconds. */
    @Serializable
    public data class ToolCompleted(
        val tool: String,
        val duration: Double? = null,
        val error: Boolean,
        val preview: String? = null,
    ) : EventPayload

    @Serializable
    public data class SubagentStarted(
        @SerialName("subagent_id") val subagentId: String,
        val goal: String? = null,
        val model: String? = null,
        @SerialName("child_session_id") val childSessionId: String? = null,
        @SerialName("task_index") @Serializable(with = StrictLongOrNull::class) val taskIndex: Long? = null,
        @SerialName("task_count") @Serializable(with = StrictLongOrNull::class) val taskCount: Long? = null,
    ) : EventPayload

    @Serializable
    public data class SubagentCompleted(
        @SerialName("subagent_id") val subagentId: String,
        val status: String? = null,
        val summary: String? = null,
        val duration: Double? = null,
    ) : EventPayload

    /** Hermes asks before running a risky command. [choices] never contains `always`. */
    @Serializable
    public data class ApprovalRequested(
        @SerialName("request_id") val requestId: String,
        val command: String,
        val description: String? = null,
        val choices: List<ApprovalChoice>,
    ) : EventPayload

    /** [choice] is whatever answered it (another surface may have chosen `always`), so a plain string. */
    @Serializable
    public data class ApprovalResolved(@SerialName("request_id") val requestId: String, val choice: String) : EventPayload

    /** The final answer; over 256 KiB it's cut and marked truncated (the full text is in the history). */
    @Serializable
    public data class Answer(val text: String, val truncated: Boolean? = null) : EventPayload

    @Serializable
    public data object RunCompleted : EventPayload

    @Serializable
    public data class RunFailed(val error: String? = null) : EventPayload

    @Serializable
    public data class RunCancelled(val reason: String? = null) : EventPayload

    /** Hermes accepted a steer. */
    @Serializable
    public data object SteerQueued : EventPayload

    /** The relay lost Hermes' stream or restarted: some steps may be missing. */
    @Serializable
    public data class Reconnected(val reason: ReconnectReason) : EventPayload

    /** The `unknown` kind: a Hermes event the relay doesn't map, its frame kept raw in [data]; safe to ignore. */
    @Serializable
    public data class Unknown(val event: String, val data: JsonObject, val truncated: Boolean? = null) : EventPayload

    /**
     * A user's message as the relay accepted it (trismegistus#30), so every device shows it at once: a prompt (its
     * run's first event; [clientMsgId] is the prompt's) or a steer (right before `steer.queued`). [device] sent it.
     */
    @Serializable
    public data class UserMessage(
        val kind: Kind,
        val text: String,
        val device: String,
        @SerialName("client_msg_id") val clientMsgId: String? = null,
        val attachments: List<AttachmentRef>? = null,
    ) : EventPayload {
        @Serializable
        public enum class Kind {
            @SerialName("prompt") PROMPT,
            @SerialName("steer") STEER,
        }
    }
}

public val EventPayload.kind: String
    get() = when (this) {
        is EventPayload.ToolStarted -> "tool.started"
        is EventPayload.ToolCompleted -> "tool.completed"
        is EventPayload.SubagentStarted -> "subagent.started"
        is EventPayload.SubagentCompleted -> "subagent.completed"
        is EventPayload.ApprovalRequested -> "approval.requested"
        is EventPayload.ApprovalResolved -> "approval.resolved"
        is EventPayload.Answer -> "answer"
        EventPayload.RunCompleted -> "run.completed"
        is EventPayload.RunFailed -> "run.failed"
        is EventPayload.RunCancelled -> "run.cancelled"
        EventPayload.SteerQueued -> "steer.queued"
        is EventPayload.Reconnected -> "reconnected"
        is EventPayload.Unknown -> "unknown"
        is EventPayload.UserMessage -> "user.message"
    }

/** Ends the run it belongs to. */
public val EventPayload.isTerminal: Boolean
    get() = this == EventPayload.RunCompleted || this is EventPayload.RunFailed || this is EventPayload.RunCancelled

/** The event's fields as they are on the wire, `type` aside. */
@Serializable
private class Envelope(
    @SerialName("session_id") val sessionId: String,
    @SerialName("run_id") val runId: String,
    @Serializable(with = StrictLong::class) val seq: Long,
    val kind: String,
    val data: JsonObject,
    @Serializable(with = StrictLong::class) val ts: Long,
)

internal object EventSerializer : KSerializer<ServerMessage.Event> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.xmuramasa.trismegistus.Event")

    override fun deserialize(decoder: Decoder): ServerMessage.Event {
        val json = (decoder as JsonDecoder).json
        val e = json.decodeFromJsonElement(Envelope.serializer(), decoder.decodeJsonElement())
        return ServerMessage.Event(e.sessionId, e.runId, e.seq, e.ts, decodePayload(json, e.kind, e.data))
    }

    override fun serialize(encoder: Encoder, value: ServerMessage.Event) {
        val json = (encoder as JsonEncoder).json
        val data = encodePayload(json, value.payload) as JsonObject
        val envelope = Envelope(value.sessionId, value.runId, value.seq, value.kind, data, value.ts)
        encoder.encodeJsonElement(json.encodeToJsonElement(Envelope.serializer(), envelope))
    }

    private fun decodePayload(json: Json, kind: String, data: JsonObject): EventPayload = when (kind) {
        "tool.started" -> json.decodeFromJsonElement(EventPayload.ToolStarted.serializer(), data)
        "tool.completed" -> json.decodeFromJsonElement(EventPayload.ToolCompleted.serializer(), data)
        "subagent.started" -> json.decodeFromJsonElement(EventPayload.SubagentStarted.serializer(), data)
        "subagent.completed" -> json.decodeFromJsonElement(EventPayload.SubagentCompleted.serializer(), data)
        "approval.requested" -> json.decodeFromJsonElement(EventPayload.ApprovalRequested.serializer(), data)
        "approval.resolved" -> json.decodeFromJsonElement(EventPayload.ApprovalResolved.serializer(), data)
        "answer" -> json.decodeFromJsonElement(EventPayload.Answer.serializer(), data)
        "run.completed" -> json.decodeFromJsonElement(EventPayload.RunCompleted.serializer(), data)
        "run.failed" -> json.decodeFromJsonElement(EventPayload.RunFailed.serializer(), data)
        "run.cancelled" -> json.decodeFromJsonElement(EventPayload.RunCancelled.serializer(), data)
        "steer.queued" -> json.decodeFromJsonElement(EventPayload.SteerQueued.serializer(), data)
        "reconnected" -> json.decodeFromJsonElement(EventPayload.Reconnected.serializer(), data)
        "unknown" -> json.decodeFromJsonElement(EventPayload.Unknown.serializer(), data)
        "user.message" -> json.decodeFromJsonElement(EventPayload.UserMessage.serializer(), data)
        // schema.json's event_kind is a closed enum: anything new arrives as kind `unknown`.
        else -> throw SerializationException("unknown event kind")
    }

    private fun encodePayload(json: Json, p: EventPayload): JsonElement = when (p) {
        is EventPayload.ToolStarted -> json.encodeToJsonElement(EventPayload.ToolStarted.serializer(), p)
        is EventPayload.ToolCompleted -> json.encodeToJsonElement(EventPayload.ToolCompleted.serializer(), p)
        is EventPayload.SubagentStarted -> json.encodeToJsonElement(EventPayload.SubagentStarted.serializer(), p)
        is EventPayload.SubagentCompleted -> json.encodeToJsonElement(EventPayload.SubagentCompleted.serializer(), p)
        is EventPayload.ApprovalRequested -> json.encodeToJsonElement(EventPayload.ApprovalRequested.serializer(), p)
        is EventPayload.ApprovalResolved -> json.encodeToJsonElement(EventPayload.ApprovalResolved.serializer(), p)
        is EventPayload.Answer -> json.encodeToJsonElement(EventPayload.Answer.serializer(), p)
        EventPayload.RunCompleted -> json.encodeToJsonElement(EventPayload.RunCompleted.serializer(), EventPayload.RunCompleted)
        is EventPayload.RunFailed -> json.encodeToJsonElement(EventPayload.RunFailed.serializer(), p)
        is EventPayload.RunCancelled -> json.encodeToJsonElement(EventPayload.RunCancelled.serializer(), p)
        EventPayload.SteerQueued -> json.encodeToJsonElement(EventPayload.SteerQueued.serializer(), EventPayload.SteerQueued)
        is EventPayload.Reconnected -> json.encodeToJsonElement(EventPayload.Reconnected.serializer(), p)
        is EventPayload.Unknown -> json.encodeToJsonElement(EventPayload.Unknown.serializer(), p)
        is EventPayload.UserMessage -> json.encodeToJsonElement(EventPayload.UserMessage.serializer(), p)
    }
}
