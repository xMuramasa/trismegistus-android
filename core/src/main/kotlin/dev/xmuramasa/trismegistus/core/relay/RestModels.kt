package dev.xmuramasa.trismegistus.core.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** REST bodies: Hermes objects pass through the relay with fields the app doesn't use, so unknown keys are fine. */
internal val RestJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A Hermes session as the relay passes it through. Only [id] is required; the rest is whatever Hermes sent. */
@Serializable
public data class SessionInfo(
    val id: String,
    /** `api_server` sessions are writable from the app; others (telegram, cli, ...) are read-only. */
    val source: String? = null,
    val title: String? = null,
    val preview: String? = null,
    /** Unix seconds. */
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("tool_call_count") val toolCallCount: Int? = null,
    /** Hermes' keep flag; absent counts as false. */
    val pinned: Boolean? = null,
    /** The model Hermes ran the session on (an id like `claude-opus-5-5[1m]`, no provider). */
    val model: String? = null,
) {
    public val isPinned: Boolean get() = pinned ?: false
    public val isReadOnly: Boolean get() = source != "api_server"
}

/** An uploaded image (`POST /api/attachments`); [id] is the sha256 of its bytes, 64 lowercase hex characters. */
@Serializable
public data class AttachmentRef(val id: String, val mime: String, val width: Int, val height: Int) {
    public companion object {
        public fun isValidId(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** One message of a session's Hermes history (`GET /api/sessions/{id}/messages`). */
public data class HistoryMessage(
    /** Hermes sends a number; kept as a string. */
    val id: String,
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Unix seconds. */
    val timestamp: Double? = null,
    /** The relay's images on a user message its run sent with them (Hermes keeps only a `[screenshot]` line). */
    val attachments: List<AttachmentRef> = emptyList(),
    /** Hermes' `display_kind`: `steer` for a message sent into a running turn; null for a plain one. */
    val displayKind: String? = null,
) {
    public enum class Role { USER, ASSISTANT, TOOL }

    /** A steer: [content] is the text that was sent, without Hermes' out-of-band wrapper. */
    val isSteer: Boolean get() = displayKind == STEER

    public data class ToolCall(val id: String?, val name: String, val arguments: String?)

    public companion object {
        public const val STEER: String = "steer"
        private const val HIDDEN = "hidden"
        private const val WRAP_OPEN = "[OUT-OF-BAND USER MESSAGE"
        private const val WRAP_CLOSE = "[/OUT-OF-BAND USER MESSAGE]"

        /**
         * The text a steer was sent with: Hermes stores it as `[OUT-OF-BAND USER MESSAGE — ...]`, a newline, the
         * text, a newline and `[/OUT-OF-BAND USER MESSAGE]`. Anything else comes back unchanged.
         */
        public fun unwrapSteer(content: String): String {
            if (!content.startsWith(WRAP_OPEN)) return content
            val start = content.indexOf('\n').takeIf { it >= 0 } ?: return content
            val end = content.lastIndexOf(WRAP_CLOSE).takeIf { it > start } ?: content.length
            return content.substring(start + 1, end).trim()
        }

        /**
         * Null for what the app doesn't show (system messages, Hermes' `hidden` ones) or can't read: Hermes objects
         * pass through unchecked.
         */
        public fun fromJson(element: JsonElement): HistoryMessage? {
            val o = element as? JsonObject ?: return null
            val kind = o.string("display_kind")
            if (kind == HIDDEN) return null
            val idValue = o["id"] as? JsonPrimitive ?: return null
            val id = if (idValue.isString) idValue.content else idValue.longOrNull?.toString() ?: return null
            val role = when (o.string("role")) {
                "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                "tool" -> Role.TOOL
                else -> return null
            }
            val calls = (o["tool_calls"] as? JsonArray).orEmpty().mapNotNull { call ->
                val c = call as? JsonObject
                val f = c?.get("function") as? JsonObject
                f?.string("name")?.let { ToolCall(c.string("id"), it, f.string("arguments")) }
            }
            val attachments = (o["attachments"] as? JsonArray).orEmpty().mapNotNull {
                runCatching { RestJson.decodeFromJsonElement(AttachmentRef.serializer(), it) }.getOrNull()
            }
            val content = o.string("content") ?: ""
            return HistoryMessage(
                id = id,
                role = role,
                content = if (kind == STEER) unwrapSteer(content) else content,
                toolCalls = calls,
                toolCallId = o.string("tool_call_id"),
                toolName = o.string("tool_name"),
                timestamp = (o["timestamp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull,
                attachments = attachments,
                displayKind = kind,
            )
        }
    }
}

/** This device's name and role (`GET /api/me`). */
@Serializable
public data class DeviceInfo(val device: String, val role: String) {
    public val isManager: Boolean get() = role == "manager"
}

/** A paired device's role on the relay; writes a viewer may not make answer 403 `forbidden`. */
public enum class DeviceRole {
    MANAGER,
    VIEWER;

    public companion object {
        /** What every write refused with 403 `forbidden` says. */
        public const val VIEWER_NOTICE: String = "This device can only view: change its role on the relay (`devices set-role`)."
    }
}

/** A provider and one of its models, as the relay names them (`GET /api/models`, `PUT /api/sessions/{id}/model`). */
@Serializable
public data class ModelChoice(val provider: String, val model: String)

/** Hermes' connected providers and their models, plus the model a session without a choice of its own uses. */
@Serializable
public data class ModelCatalog(val current: ModelChoice, val providers: List<Provider>) {
    @Serializable
    public data class Provider(val slug: String, val name: String, val models: List<Model>)

    @Serializable
    public data class Model(val id: String, val reasoning: Boolean? = null)
}

/** The pairing exchange's result: a device token for the Keystore and the name the relay gave the device. */
public data class Pairing(val token: String, val device: String) {
    override fun toString(): String = "Pairing(token=***, device=$device)"
}
