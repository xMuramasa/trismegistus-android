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

/** App → relay, one JSON object per text frame, discriminated by `type`. */
@Serializable(with = ClientMessageSerializer::class)
public sealed interface ClientMessage {
    /** First message on a socket, within 5 s of connecting. */
    @Serializable
    public data class Auth(val token: String) : ClientMessage {
        override fun toString(): String = "Auth(token=***)"
    }

    /** With [afterSeq], stored events with a larger seq are replayed first (or `error replay_gap`). */
    @Serializable
    public data class Subscribe(
        @SerialName("session_id") val sessionId: String,
        @SerialName("after_seq") @Serializable(with = StrictLongOrNull::class) val afterSeq: Long? = null,
    ) : ClientMessage {
        init {
            require((afterSeq ?: 0) >= 0) { "after_seq must be >= 0" }
        }
    }

    @Serializable
    public data class Unsubscribe(@SerialName("session_id") val sessionId: String) : ClientMessage

    /**
     * [clientMsgId] (a UUID) makes resends safe. [attachments]: 1-4 unique ids from `POST /api/attachments`, or null;
     * [text] may be empty only with attachments.
     */
    @Serializable
    public data class Prompt(
        @SerialName("client_msg_id") val clientMsgId: String,
        @SerialName("session_id") val sessionId: String,
        val text: String,
        val attachments: List<String>? = null,
    ) : ClientMessage {
        init {
            require(attachments == null || (attachments.size in 1..4 && attachments.toSet().size == attachments.size)) {
                "attachments must hold 1-4 unique ids"
            }
            require(text.isNotEmpty() || attachments != null) { "text may be empty only with attachments" }
        }
    }

    @Serializable
    public data class Stop(@SerialName("run_id") val runId: String) : ClientMessage

    @Serializable
    public data class Steer(@SerialName("run_id") val runId: String, val text: String) : ClientMessage

    @Serializable
    public data class Approve(
        @SerialName("run_id") val runId: String,
        @SerialName("request_id") val requestId: String,
        val choice: ApprovalChoice,
    ) : ClientMessage

    @Serializable
    public data object Ping : ClientMessage
}

/** The schema's `type` for this message. */
public val ClientMessage.type: String
    get() = when (this) {
        is ClientMessage.Auth -> "auth"
        is ClientMessage.Subscribe -> "subscribe"
        is ClientMessage.Unsubscribe -> "unsubscribe"
        is ClientMessage.Prompt -> "prompt"
        is ClientMessage.Stop -> "stop"
        is ClientMessage.Steer -> "steer"
        is ClientMessage.Approve -> "approve"
        ClientMessage.Ping -> "ping"
    }

internal object ClientMessageSerializer : KSerializer<ClientMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.xmuramasa.trismegistus.ClientMessage")

    override fun deserialize(decoder: Decoder): ClientMessage {
        val json = (decoder as JsonDecoder).json
        val (type, body) = decoder.decodeJsonElement().splitTag("type")
        return when (type) {
            "auth" -> json.decodeFromJsonElement(ClientMessage.Auth.serializer(), body)
            "subscribe" -> json.decodeFromJsonElement(ClientMessage.Subscribe.serializer(), body)
            "unsubscribe" -> json.decodeFromJsonElement(ClientMessage.Unsubscribe.serializer(), body)
            "prompt" -> json.decodeFromJsonElement(ClientMessage.Prompt.serializer(), body)
            "stop" -> json.decodeFromJsonElement(ClientMessage.Stop.serializer(), body)
            "steer" -> json.decodeFromJsonElement(ClientMessage.Steer.serializer(), body)
            "approve" -> json.decodeFromJsonElement(ClientMessage.Approve.serializer(), body)
            "ping" -> json.decodeFromJsonElement(ClientMessage.Ping.serializer(), body)
            else -> throw SerializationException("not an app message type")
        }
    }

    override fun serialize(encoder: Encoder, value: ClientMessage) {
        val json = (encoder as JsonEncoder).json
        val body = when (value) {
            is ClientMessage.Auth -> json.encodeToJsonElement(ClientMessage.Auth.serializer(), value)
            is ClientMessage.Subscribe -> json.encodeToJsonElement(ClientMessage.Subscribe.serializer(), value)
            is ClientMessage.Unsubscribe -> json.encodeToJsonElement(ClientMessage.Unsubscribe.serializer(), value)
            is ClientMessage.Prompt -> json.encodeToJsonElement(ClientMessage.Prompt.serializer(), value)
            is ClientMessage.Stop -> json.encodeToJsonElement(ClientMessage.Stop.serializer(), value)
            is ClientMessage.Steer -> json.encodeToJsonElement(ClientMessage.Steer.serializer(), value)
            is ClientMessage.Approve -> json.encodeToJsonElement(ClientMessage.Approve.serializer(), value)
            ClientMessage.Ping -> json.encodeToJsonElement(ClientMessage.Ping.serializer(), ClientMessage.Ping)
        }
        encoder.encodeJsonElement(tagged("type", value.type, body))
    }
}
