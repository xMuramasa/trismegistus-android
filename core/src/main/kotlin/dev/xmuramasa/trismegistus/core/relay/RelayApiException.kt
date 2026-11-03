package dev.xmuramasa.trismegistus.core.relay

import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A failed REST call as the relay reports it: `{"error": {code, message}}` with the matching HTTP status. [status] is
 * 0 when the request never got the relay's answer (a bad id caught before sending, an unreadable response).
 */
public class RelayApiException(
    public val status: Int,
    public val code: ErrorCode,
    override val message: String,
) : Exception(message) {
    internal companion object {
        /** relay/internal/server statusOf, reversed: for error bodies that aren't the relay's JSON. */
        private val codeOfStatus = mapOf(
            400 to ErrorCode.BAD_REQUEST, 401 to ErrorCode.UNAUTHORIZED, 403 to ErrorCode.FORBIDDEN,
            404 to ErrorCode.NOT_FOUND, 409 to ErrorCode.BUSY, 410 to ErrorCode.REPLAY_GAP, 413 to ErrorCode.TOO_LARGE,
            429 to ErrorCode.RATE_LIMITED, 502 to ErrorCode.HERMES_ERROR, 503 to ErrorCode.HERMES_UNAVAILABLE,
        )

        fun from(status: Int, body: ByteArray): RelayApiException {
            val inner = runCatching { RestJson.parseToJsonElement(body.decodeToString()).jsonObject["error"] as? JsonObject }
                .getOrNull()
            val code = inner?.string("code")
                ?.let { runCatching { RestJson.decodeFromJsonElement(ErrorCode.serializer(), JsonPrimitive(it)) }.getOrNull() }
                ?: codeOfStatus[status] ?: ErrorCode.INTERNAL
            val message = if (code == ErrorCode.FORBIDDEN) {
                DeviceRole.VIEWER_NOTICE
            } else {
                (inner?.string("message") ?: "The relay answered $status.").take(512)
            }
            return RelayApiException(status, code, message)
        }

        fun unexpected(): RelayApiException = RelayApiException(0, ErrorCode.HERMES_ERROR, "The relay sent an unexpected response.")
    }
}
