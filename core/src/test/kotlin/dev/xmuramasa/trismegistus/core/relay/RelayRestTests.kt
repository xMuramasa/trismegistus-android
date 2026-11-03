package dev.xmuramasa.trismegistus.core.relay

import dev.xmuramasa.trismegistus.core.FAKE_TOKEN
import dev.xmuramasa.trismegistus.core.FakeRelay
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.RecordedRequest

/** RelayRest against MockWebServer, with the bodies and statuses relay/internal/server returns. */
class RelayRestTests {
    private val relays = mutableListOf<FakeRelay>()

    /** The relay of the latest [rest] call. */
    private val relay: FakeRelay? get() = relays.lastOrNull()

    @AfterTest
    fun tearDown() {
        relays.forEach { it.close() }
    }

    private fun rest(token: String = FAKE_TOKEN, respond: (RecordedRequest) -> Pair<Int, String>): RelayRest {
        val relay = FakeRelay(respond).also { relays += it }
        return RelayRest(relay.url, token)
    }

    private val requests get() = relay!!.requests

    private fun body(r: RecordedRequest): JsonElement? = r.body?.utf8()?.let(Json::parseToJsonElement)

    private suspend fun assertApiError(status: Int, code: ErrorCode, message: String, call: suspend () -> Unit) {
        val e = assertFailsWith<RelayApiException> { call() }
        assertEquals(Triple(status, code, message), Triple(e.status, e.code, e.message))
    }

    @Test
    fun listSessions() = runTest {
        val api = rest {
            200 to """{"object":"list","has_more":false,"data":[{"id":"api_1","source":"api_server","title":"Dates","message_count":4,"last_active":1792801004.5},{"id":7},{"id":"cli_2","source":"cli","title":null}]}"""
        }
        val sessions = api.listSessions()
        assertEquals(listOf("api_1", "cli_2"), sessions.map { it.id }) // the malformed one is skipped
        assertEquals("Dates", sessions[0].title)
        assertEquals(4, sessions[0].messageCount)
        assertTrue(!sessions[0].isReadOnly && sessions[1].isReadOnly)
        val r = requests.single()
        assertEquals("GET", r.method)
        assertEquals("/api/sessions?limit=100", r.url.encodedPath + "?" + r.url.encodedQuery)
        assertEquals("Bearer $FAKE_TOKEN", r.headers["Authorization"])
    }

    @Test
    fun unauthorized() = runTest {
        val api = rest { UNAUTHORIZED }
        assertApiError(401, ErrorCode.UNAUTHORIZED, "unauthorized") { api.listSessions() }
        assertApiError(401, ErrorCode.UNAUTHORIZED, "unauthorized") { api.createSession() }
    }

    @Test
    fun createSession() = runTest {
        val api = rest { 201 to """{"object":"hermes.session","session":{"id":"api_9","source":"api_server"}}""" }
        assertEquals("api_9", api.createSession().id)
        val r = requests.single()
        assertEquals("POST" to "/api/sessions", r.method to r.url.encodedPath)
        assertEquals(Json.parseToJsonElement("{}"), body(r))
    }

    @Test
    fun renameAndPin() = runTest {
        val api = rest { 200 to """{"object":"hermes.session","session":{"id":"api_9","title":"Taxes","pinned":true}}""" }
        assertEquals("Taxes", api.renameSession("api_9", "Taxes").title)
        assertTrue(api.setPinned("api_9", true).isPinned)
        assertEquals(listOf("PATCH", "PATCH"), requests.map { it.method })
        assertEquals("/api/sessions/api_9", requests[0].url.encodedPath)
        assertEquals(Json.parseToJsonElement("""{"title":"Taxes"}"""), body(requests[0]))
        assertEquals(Json.parseToJsonElement("""{"pinned":true}"""), body(requests[1]))
    }

    @Test
    fun renameErrors() = runTest {
        val missing = rest { NOT_FOUND }
        assertApiError(404, ErrorCode.NOT_FOUND, "not found") { missing.renameSession("api_404", "x") }
        val readOnly = rest { 403 to """{"error":{"code":"read_only","message":"this session is read-only here"}}""" }
        assertApiError(403, ErrorCode.READ_ONLY, "this session is read-only here") { readOnly.renameSession("cli_1", "x") }
    }

    @Test
    fun deleteSessionAndItsErrors() = runTest {
        val api = rest { 200 to """{"deleted":true}""" }
        api.deleteSession("api_9")
        assertEquals("DELETE" to "/api/sessions/api_9", requests.single().let { it.method to it.url.encodedPath })
        val busy = rest { 409 to """{"error":{"code":"busy","message":"a run is still writing to this session"}}""" }
        assertApiError(409, ErrorCode.BUSY, "a run is still writing to this session") { busy.deleteSession("api_9") }
    }

    @Test
    fun badIdsNeverLeaveTheApp() = runTest {
        val api = rest { 200 to "{}" }
        assertFailsWith<RelayApiException> { api.deleteSession("../admin") }
        assertFailsWith<RelayApiException> { api.messages("api_1/messages") }
        assertFailsWith<RelayApiException> { api.attachment("../etc/passwd") }
        assertFailsWith<RelayApiException> { api.sessionMedia("api_1", "relative/path.png") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun serverErrorsWithoutJsonMapByStatus() = runTest {
        assertApiError(503, ErrorCode.HERMES_UNAVAILABLE, "The relay answered 503.") { rest { 503 to "unavailable" }.listSessions() }
        assertApiError(500, ErrorCode.INTERNAL, "The relay answered 500.") { rest { 500 to "" }.listSessions() }
        assertApiError(0, ErrorCode.HERMES_ERROR, "The relay sent an unexpected response.") { rest { 200 to "[]" }.me() }
    }

    /** A viewer's write: whatever the relay says, the app shows the same notice. */
    @Test
    fun forbiddenReadsAsViewOnly() = runTest {
        val api = rest { 403 to """{"error":{"code":"forbidden","message":"this endpoint needs a manager device"}}""" }
        assertApiError(403, ErrorCode.FORBIDDEN, DeviceRole.VIEWER_NOTICE) { api.uploadAttachment(byteArrayOf(1, 2, 3), "image/png") }
        assertApiError(403, ErrorCode.FORBIDDEN, DeviceRole.VIEWER_NOTICE) { rest { 403 to "" }.uploadAttachment(byteArrayOf(1), "image/png") }
    }

    @Test
    fun uploadSendsTheBytesWithTheirType() = runTest {
        val id = "3f786850e387550fdab836ed7e6dc881de23001b6b9c2ed4a5e1e0b2e6c4c1a1"
        val api = rest { 201 to """{"id":"$id","mime":"image/png","width":2,"height":1,"bytes":3}""" }
        assertEquals(AttachmentRef(id, "image/png", 2, 1), api.uploadAttachment(byteArrayOf(1, 2, 3), "image/png"))
        val r = requests.single()
        assertEquals("POST" to "/api/attachments", r.method to r.url.encodedPath)
        assertEquals("image/png", r.headers["Content-Type"])
        assertEquals(3, r.body?.size)
    }

    @Test
    fun meAndAMissingEndpoint() = runTest {
        val api = rest { r ->
            if (r.url.encodedPath == "/api/me") 200 to """{"device":"s21","role":"manager"}""" else NOT_FOUND
        }
        assertEquals(DeviceInfo("s21", "manager"), api.me())
        assertTrue(api.me().isManager)
        assertEquals(emptyMap(), api.tags()) // a relay that predates tags
        assertNull(api.models())
        assertEquals(emptyMap(), api.sessionModels())
    }

    @Test
    fun tagsAndModels() = runTest {
        val api = rest { r ->
            when (r.url.encodedPath) {
                "/api/tags" -> 200 to """{"sessions":{"api_1":["work","taxes"]}}"""
                "/api/sessions/api_1/tags" -> 200 to """{"tags":["work"]}"""
                "/api/models" -> 200 to """{"current":{"provider":"anthropic","model":"claude-opus-5-5"},"providers":[{"slug":"anthropic","name":"Anthropic","models":[{"id":"claude-opus-5-5","reasoning":true}]}]}"""
                "/api/sessions/api_1/model" -> 200 to """{"provider":"anthropic","model":"claude-opus-5-5"}"""
                else -> NOT_FOUND
            }
        }
        assertEquals(mapOf("api_1" to listOf("work", "taxes")), api.tags())
        assertEquals(listOf("work"), api.setTags("api_1", listOf("work", "Work")))
        assertEquals(Json.parseToJsonElement("""{"tags":["work","Work"]}"""), body(requests[1]))
        val catalog = api.models()
        assertEquals(ModelChoice("anthropic", "claude-opus-5-5"), catalog?.current)
        assertEquals(ModelChoice("anthropic", "claude-opus-5-5"), api.setModel("api_1", ModelChoice("anthropic", "claude-opus-5-5")))
        assertEquals("PUT", requests.last().method)
    }

    @Test
    fun messages() = runTest {
        val api = rest {
            200 to """
                {"object":"list","session_id":"api_1","data":[
                  {"id":1,"role":"user","content":"What year is it?","timestamp":1792801001.0},
                  {"id":2,"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"terminal","arguments":"{\"command\":\"date +%Y\"}"}}]},
                  {"id":3,"role":"tool","content":"2026","tool_call_id":"call_1","tool_name":"terminal"},
                  {"id":4,"role":"system","content":"skipped"},
                  {"id":5,"role":"assistant","content":"It is 2026."}
                ]}
            """.trimIndent()
        }
        val history = api.messages("api_1")
        assertEquals(listOf("1", "2", "3", "5"), history.map { it.id })
        assertEquals("terminal", history[1].toolCalls.single().name)
        assertEquals("", history[1].content)
        assertEquals("call_1", history[2].toolCallId)
        assertEquals("/api/sessions/api_1/messages", requests.single().url.encodedPath)
    }

    @Test
    fun mediaPathIsOneQueryParameter() = runTest {
        val api = rest { 200 to "PNG" }
        assertEquals("PNG", api.sessionMedia("api_1", "/home/u/.hermes/cache/images/a+b c.png").decodeToString())
        val r = requests.single()
        assertEquals("/api/sessions/api_1/media", r.url.encodedPath)
        assertEquals("/home/u/.hermes/cache/images/a+b c.png", r.url.queryParameter("path"))
        assertTrue("%2B" in r.url.encodedQuery!!) // a raw + would read as a space
    }

    @Test
    fun pair() = runTest {
        rest(token = "") { 200 to """{"token":"$FAKE_TOKEN","device":{"name":"s21","created_at":"2026-11-02T12:00:00Z"}}""" }
        val pairing = RelayRest.pair(relay!!.url, "ABCD-EFGH-JKMN")
        assertEquals(Pairing(FAKE_TOKEN, "s21"), pairing)
        val r = requests.single()
        assertEquals("POST" to "/api/pair", r.method to r.url.encodedPath)
        assertNull(r.headers["Authorization"])
        assertEquals(Json.parseToJsonElement("""{"code":"ABCD-EFGH-JKMN"}"""), body(r))
    }

    @Test
    fun pairRejectsBadCodesAndBadTokens() = runTest {
        rest { 401 to """{"error":{"code":"unauthorized","message":"invalid, used or expired pairing code"}}""" }
        assertApiError(401, ErrorCode.UNAUTHORIZED, "invalid, used or expired pairing code") { RelayRest.pair(relay!!.url, "ABCD-EFGH-JKMN") }
        rest { 200 to """{"token":"trs_short","device":{"name":"s21"}}""" }
        assertApiError(0, ErrorCode.HERMES_ERROR, "The relay sent an unexpected response.") { RelayRest.pair(relay!!.url, "ABCD-EFGH-JKMN") }
    }

    private companion object {
        val UNAUTHORIZED = 401 to """{"error":{"code":"unauthorized","message":"unauthorized"}}"""
        val NOT_FOUND = 404 to """{"error":{"code":"not_found","message":"not found"}}"""
    }
}
