package dev.xmuramasa.trismegistus.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

/** Relay → app, one JSON object per text frame, discriminated by `type` (and `kind` for events). */
@Serializable(with = ServerMessageSerializer::class)
public sealed interface ServerMessage {
    /** After a successful auth. */
    @Serializable
    public data class Ready(val device: String, @SerialName("server_version") val serverVersion: String) : ServerMessage

    /** One numbered, stored event. [seq] counts up per session across runs, from 1; [ts] is unix milliseconds. */
    @Serializable(with = EventSerializer::class)
    public data class Event(
        val sessionId: String,
        val runId: String,
        val seq: Long,
        val ts: Long,
        val payload: EventPayload,
    ) : ServerMessage {
        init {
            require(seq >= 1) { "seq must be >= 1" }
            require(ts >= 0) { "ts must be >= 0" }
        }

        public val kind: String get() = payload.kind
    }

    /** A run's status changed (live only, and after a subscribe replay for the active run). */
    @Serializable
    public data class Run(
        @SerialName("run_id") val runId: String,
        @SerialName("session_id") val sessionId: String,
        val status: RunStatus,
    ) : ServerMessage

    /** A prompt was accepted; [replayed] is true when client_msg_id was seen before (no new run). */
    @Serializable
    public data class Ack(
        @SerialName("client_msg_id") val clientMsgId: String,
        @SerialName("run_id") val runId: String,
        val replayed: Boolean,
    ) : ServerMessage

    /** [ref] names what failed: a prompt's client_msg_id, the run_id of stop/steer/approve, a subscribe's session_id. */
    @Serializable
    public data class Error(val code: ErrorCode, val message: String, val ref: String? = null) : ServerMessage

    @Serializable
    public data object Pong : ServerMessage
}

/** The schema's `type` for this message. */
public val ServerMessage.type: String
    get() = when (this) {
        is ServerMessage.Ready -> "ready"
        is ServerMessage.Event -> "event"
        is ServerMessage.Run -> "run"
        is ServerMessage.Ack -> "ack"
        is ServerMessage.Error -> "error"
        ServerMessage.Pong -> "pong"
    }

internal object ServerMessageSerializer : KSerializer<ServerMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.xmuramasa.trismegistus.ServerMessage")

    override fun deserialize(decoder: Decoder): ServerMessage {
        val json = (decoder as JsonDecoder).json
        val (type, body) = decoder.decodeJsonElement().splitTag("type")
        return when (type) {
            "ready" -> json.decodeFromJsonElement(ServerMessage.Ready.serializer(), body)
            "event" -> json.decodeFromJsonElement(EventSerializer, body)
            "run" -> json.decodeFromJsonElement(ServerMessage.Run.serializer(), body)
            "ack" -> json.decodeFromJsonElement(ServerMessage.Ack.serializer(), body)
            "error" -> json.decodeFromJsonElement(ServerMessage.Error.serializer(), body)
            "pong" -> json.decodeFromJsonElement(ServerMessage.Pong.serializer(), body)
            else -> throw SerializationException("not a relay message type")
        }
    }

    override fun serialize(encoder: Encoder, value: ServerMessage) {
        val json = (encoder as JsonEncoder).json
        val body = when (value) {
            is ServerMessage.Ready -> json.encodeToJsonElement(ServerMessage.Ready.serializer(), value)
            is ServerMessage.Event -> json.encodeToJsonElement(EventSerializer, value)
            is ServerMessage.Run -> json.encodeToJsonElement(ServerMessage.Run.serializer(), value)
            is ServerMessage.Ack -> json.encodeToJsonElement(ServerMessage.Ack.serializer(), value)
            is ServerMessage.Error -> json.encodeToJsonElement(ServerMessage.Error.serializer(), value)
            ServerMessage.Pong -> json.encodeToJsonElement(ServerMessage.Pong.serializer(), ServerMessage.Pong)
        }
        encoder.encodeJsonElement(tagged("type", value.type, body))
    }
}
