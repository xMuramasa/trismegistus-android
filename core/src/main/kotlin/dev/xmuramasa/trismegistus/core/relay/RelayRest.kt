package dev.xmuramasa.trismegistus.core.relay

import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.isValidId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The relay's REST API with the device token as Bearer. Failures are [RelayApiException]; a network failure is the
 * [java.io.IOException] OkHttp throws.
 */
public class RelayRest(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val base = baseUrl.trimEnd('/')

    /** This device's name and role. A relay that predates roles answers 404. */
    public suspend fun me(): DeviceInfo = decode(DeviceInfo.serializer(), json(call("GET", url("/api/me"))))

    /** Sessions that don't decode are skipped. */
    public suspend fun listSessions(): List<SessionInfo> =
        lossy(json(call("GET", url("/api/sessions?limit=100")))["data"]) {
            RestJson.decodeFromJsonElement(SessionInfo.serializer(), it)
        }

    /** The relay ignores the body and Hermes names the session itself. */
    public suspend fun createSession(): SessionInfo = session(call("POST", url("/api/sessions"), JsonObject(emptyMap())))

    /** Every source; the title is 1-200 characters. */
    public suspend fun renameSession(id: String, title: String): SessionInfo =
        session(call("PATCH", url(sessionPath(id)), buildJsonObject { put("title", title) }))

    /** Every source. Pinning is Hermes' durable keep flag. */
    public suspend fun setPinned(id: String, pinned: Boolean): SessionInfo =
        session(call("PATCH", url(sessionPath(id)), buildJsonObject { put("pinned", pinned) }))

    /** Only `api_server`, `cron` and `cli` sessions (403 read_only otherwise) with no active run (409 busy). */
    public suspend fun deleteSession(id: String) {
        call("DELETE", url(sessionPath(id)))
    }

    /** Every session's tags by session id; ids of sessions deleted elsewhere may linger. 404 (an older relay): none. */
    public suspend fun tags(): Map<String, List<String>> = orIf404(emptyMap()) {
        decode(MapSerializer(String.serializer(), ListSerializer(String.serializer())), json(call("GET", url("/api/tags")))["sessions"])
    }

    /** Replaces the session's tags (`[]` clears); answers the set as stored. 400 bad_request when a tag breaks the rules. */
    public suspend fun setTags(id: String, tags: List<String>): List<String> {
        val body = buildJsonObject { putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } } }
        return decode(ListSerializer(String.serializer()), json(call("PUT", url(sessionPath(id) + "/tags"), body))["tags"])
    }

    /** The session's Hermes history, oldest first; messages the app doesn't show or can't read are skipped. */
    public suspend fun messages(sessionId: String): List<HistoryMessage> =
        lossy(json(call("GET", url(sessionPath(sessionId) + "/messages")))["data"]) { HistoryMessage.fromJson(it) }

    /** Hermes' connected providers and models; null from a relay that predates the endpoint (404). */
    public suspend fun models(): ModelCatalog? = orIf404(null) {
        decode(ModelCatalog.serializer(), json(call("GET", url("/api/models"))))
    }

    /** The sessions with a model of their own; the others use the catalog's `current`. 404: none. */
    public suspend fun sessionModels(): Map<String, ModelChoice> = orIf404(emptyMap()) {
        decode(MapSerializer(String.serializer(), ModelChoice.serializer()), json(call("GET", url("/api/session-models")))["sessions"])
    }

    /** Only `api_server` sessions (403 read_only otherwise); the pair must be in [models] (400). From the next run. */
    public suspend fun setModel(id: String, choice: ModelChoice): ModelChoice {
        val body = buildJsonObject {
            put("provider", choice.provider)
            put("model", choice.model)
        }
        return decode(ModelChoice.serializer(), json(call("PUT", url(sessionPath(id) + "/model"), body)))
    }

    /**
     * Manager only (403 `forbidden`, read as [DeviceRole.VIEWER_NOTICE]). [mime] is `image/png`, `image/jpeg` or
     * `image/gif`; at most 8 MiB (413 `too_large`). The same bytes again answer the same id.
     */
    public suspend fun uploadAttachment(data: ByteArray, mime: String): AttachmentRef =
        decode(AttachmentRef.serializer(), json(call("POST", url("/api/attachments"), raw = data.toRequestBody(mime.toMediaType()))))

    /** An attachment's bytes (immutable). Every device. */
    public suspend fun attachment(id: String): ByteArray {
        if (!AttachmentRef.isValidId(id)) throw RelayApiException(0, ErrorCode.NOT_FOUND, "Unknown attachment.")
        return call("GET", url("/api/attachments/$id"))
    }

    /**
     * A file of Hermes' that a session's messages name (`MEDIA:<path>`, Telegram's `[Image attached at: <path>]`);
     * 404 `not_found` for any other path, 503 `hermes_unavailable` when the host helper is down. Every device.
     */
    public suspend fun sessionMedia(sessionId: String, path: String): ByteArray {
        if (!path.startsWith("/")) throw RelayApiException(0, ErrorCode.NOT_FOUND, "Unknown file.")
        return call("GET", url(sessionPath(sessionId) + "/media").newBuilder().addQueryParameter("path", path).build())
    }

    public companion object {
        /**
         * Trades a one-time pairing code (`XXXX-XXXX-XXXX`, from `trismegistus-relay pair`) for a device token. A bad,
         * used or expired code is `unauthorized`.
         */
        public suspend fun pair(baseUrl: String, code: String, http: OkHttpClient = OkHttpClient()): Pairing {
            val rest = RelayRest(baseUrl, "", http)
            val body = rest.json(rest.call("POST", rest.url("/api/pair"), buildJsonObject { put("code", code) }))
            val token = body.string("token")
            val device = (body["device"] as? JsonObject)?.string("name")
            if (token == null || device == null || !isDeviceToken(token)) throw RelayApiException.unexpected()
            return Pairing(token, device.take(64))
        }

        /** `trs_` and 43 base64url characters, as relay/internal/pairing makes them. */
        internal fun isDeviceToken(s: String): Boolean =
            s.startsWith("trs_") && s.length == 47 &&
                s.drop(4).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    private suspend fun session(bytes: ByteArray): SessionInfo = decode(SessionInfo.serializer(), json(bytes)["session"])

    /** Checked before an id goes into a URL path: the relay would refuse it anyway, and it must not change the path. */
    private fun sessionPath(id: String): String {
        if (!isValidId(id)) throw RelayApiException(0, ErrorCode.NOT_FOUND, "Unknown session.")
        return "/api/sessions/$id"
    }

    private fun url(path: String): HttpUrl =
        try {
            "$base$path".toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw RelayApiException(0, ErrorCode.BAD_REQUEST, "Bad relay URL.")
        }

    private fun json(bytes: ByteArray): JsonObject =
        try {
            RestJson.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: IllegalArgumentException) {
            throw RelayApiException.unexpected()
        }

    private fun <T> decode(strategy: DeserializationStrategy<T>, element: JsonElement?): T =
        try {
            RestJson.decodeFromJsonElement(strategy, element ?: throw RelayApiException.unexpected())
        } catch (_: IllegalArgumentException) {
            throw RelayApiException.unexpected()
        }

    /** A JSON array whose items that don't read are skipped (Hermes objects pass through the relay unchecked). */
    private fun <T> lossy(array: JsonElement?, read: (JsonElement) -> T?): List<T> {
        val items = array as? JsonArray ?: throw RelayApiException.unexpected()
        return items.mapNotNull { runCatching { read(it) }.getOrNull() }
    }

    private suspend fun <T> orIf404(fallback: T, body: suspend () -> T): T =
        try {
            body()
        } catch (e: RelayApiException) {
            if (e.status == 404) fallback else throw e
        }

    private suspend fun call(method: String, url: HttpUrl, body: JsonObject? = null, raw: RequestBody? = null): ByteArray {
        val request = Request.Builder()
            .url(url)
            .method(method, raw ?: body?.toString()?.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        // shortcut: a blocking call on the IO dispatcher, not cancelled with the coroutine; use enqueue if that matters.
        return withContext(io) {
            http.newCall(request).execute().use { response ->
                val bytes = response.body.bytes()
                if (!response.isSuccessful) throw RelayApiException.from(response.code, bytes)
                bytes
            }
        }
    }
}
