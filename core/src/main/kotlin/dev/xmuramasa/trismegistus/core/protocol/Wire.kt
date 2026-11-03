package dev.xmuramasa.trismegistus.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The protocol's JSON: strict on input (unknown fields fail), absent optionals are left out, never sent as null. */
public object Wire {
    public val json: Json = Json { explicitNulls = false }

    public fun encode(message: ClientMessage): String = json.encodeToString(ClientMessageSerializer, message)

    public fun encode(message: ServerMessage): String = json.encodeToString(ServerMessageSerializer, message)

    /** Throws [IllegalArgumentException] (a [SerializationException] for JSON problems) on anything off the schema. */
    public fun decodeClient(text: String): ClientMessage = json.decodeFromString(ClientMessageSerializer, text)

    /** Throws [IllegalArgumentException] (a [SerializationException] for JSON problems) on anything off the schema. */
    public fun decodeServer(text: String): ServerMessage = json.decodeFromString(ServerMessageSerializer, text)

    /** Relay input is untrusted: what doesn't match the protocol is null. */
    public fun decodeServerOrNull(text: String): ServerMessage? =
        try {
            decodeServer(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** Splits `{"<key>": "<tag>", ...rest}` into the tag and the rest; the tag must be a JSON string. */
internal fun JsonElement.splitTag(key: String): Pair<String, JsonObject> {
    val obj = this as? JsonObject ?: throw SerializationException("not an object")
    val tag = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("missing or non-string $key")
    return tag to JsonObject(obj - key)
}

/** `{"<key>": "<tag>"}` followed by the fields of [body]. */
internal fun tagged(key: String, tag: String, body: JsonElement): JsonObject =
    JsonObject(mapOf(key to JsonPrimitive(tag)) + (body as JsonObject))

/**
 * Session, run, request and client message ids: 1-128 ASCII letters, digits and `_ . : -`, the first alphanumeric.
 * Decoding doesn't check this (the relay does); RelayRest checks it before putting an id in a URL path.
 */
public fun isValidId(s: String): Boolean {
    fun alnum(c: Char) = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'
    return s.length in 1..128 && alnum(s[0]) && s.all { alnum(it) || it in "_.:-" }
}
