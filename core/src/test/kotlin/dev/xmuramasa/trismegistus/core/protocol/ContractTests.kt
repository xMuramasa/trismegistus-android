package dev.xmuramasa.trismegistus.core.protocol

import java.io.File
import java.security.MessageDigest
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** protocol/ (a copy of the relay repo's) against the Kotlin types: every example, both ways. */
class ContractTests {
    private val protocolDir = File(
        System.getProperty("trismegistus.protocolDir") ?: error("run through Gradle: trismegistus.protocolDir is unset"),
    )

    private fun examples(kind: String): List<File> =
        File(protocolDir, "examples/$kind").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    /** `<file>: <constraint>` lines of relay-enforced.txt. */
    private val relayEnforced: Map<String, String> =
        javaClass.getResource("/relay-enforced.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore(":").trim() to it.substringAfter(":", "").trim() }

    private val clientTypes = setOf("auth", "subscribe", "unsubscribe", "prompt", "stop", "steer", "approve", "ping")

    @Test
    fun examplesExist() {
        assertTrue(examples("valid").isNotEmpty())
        assertTrue(examples("invalid").isNotEmpty())
    }

    /** Decodes as the type its name starts with; decode -> encode -> decode gives the same value and the same JSON tree. */
    @Test
    fun validExamplesRoundTrip() {
        val failures = examples("valid").mapNotNull { f ->
            val text = f.readText()
            val type = f.name.removeSuffix(".json").substringBefore("-")
            runCatching {
                val encoded = if (type in clientTypes) {
                    val msg = Wire.decodeClient(text)
                    check(msg.type == type) { "decoded as ${msg.type}" }
                    Wire.encode(msg).also { check(Wire.decodeClient(it) == msg) { "second decode differs" } }
                } else {
                    val msg = Wire.decodeServer(text)
                    check(msg.type == type) { "decoded as ${msg.type}" }
                    Wire.encode(msg).also { check(Wire.decodeServer(it) == msg) { "second decode differs" } }
                }
                check(Json.parseToJsonElement(encoded) == Json.parseToJsonElement(text)) { "re-encoded as $encoded" }
            }.exceptionOrNull()?.let { "${f.name}: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Rejected by both message types, or listed as a rule only the relay checks. */
    @Test
    fun invalidExamplesAreRejectedOrRelayEnforced() {
        val failures = examples("invalid").mapNotNull { f ->
            val text = f.readText()
            val decodes = runCatching { Wire.decodeClient(text) }.isSuccess || runCatching { Wire.decodeServer(text) }.isSuccess
            val rule = relayEnforced[f.name]
            when {
                rule == null && decodes -> "${f.name} decodes; reject it in the Kotlin types or list it in relay-enforced.txt"
                rule != null && !decodes -> "${f.name} is rejected in Kotlin now; drop it from relay-enforced.txt"
                rule != null && rule.isEmpty() -> "${f.name} needs the constraint the relay enforces"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun relayEnforcedListsOnlyExistingExamples() {
        val invalid = examples("invalid").map { it.name }.toSet()
        assertEquals(emptySet(), relayEnforced.keys - invalid, "relay-enforced.txt lists files not in examples/invalid")
    }

    /** protocol/SOURCE is what scripts/protocol-hash.sh printed at the last sync; editing the copy breaks this. */
    @Test
    fun copyMatchesSource() {
        assertEquals(File(protocolDir, "SOURCE").readText(), protocolHash(protocolDir), "protocol/ changed without scripts/sync-protocol.sh")
    }

    @Test
    fun alwaysCannotBeSentOrDecoded() {
        assertFalse(ApprovalChoice.entries.any { it.name.equals("always", ignoreCase = true) })
        assertEquals(
            """{"type":"approve","run_id":"run_1","request_id":"req_1","choice":"session"}""",
            Wire.encode(ClientMessage.Approve("run_1", "req_1", ApprovalChoice.SESSION)),
        )
        assertTrue(runCatching { Wire.decodeClient("""{"type":"approve","run_id":"r","request_id":"q","choice":"always"}""") }.isFailure)
    }

    @Test
    fun theTokenIsNotInToString() {
        assertFalse("FAKEFAKE" in ClientMessage.Auth("trs_FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAK").toString())
    }
}

/** scripts/protocol-hash.sh in Kotlin: schema.json's sha256, then the sha256 of `sha256sum` lines of every example. */
internal fun protocolHash(dir: File): String {
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val paths = File(dir, "examples").walkTopDown().filter { it.isFile && it.name.endsWith(".json") }
        .map { it.relativeTo(dir).invariantSeparatorsPath }
        .sortedWith { a, b -> Arrays.compareUnsigned(a.toByteArray(), b.toByteArray()) } // LC_ALL=C sort
        .toList()
    val listing = paths.joinToString("") { "${sha(File(dir, it).readBytes())}  $it\n" }
    return "schema.json ${sha(File(dir, "schema.json").readBytes())}\nexamples ${sha(listing.toByteArray())}\n"
}
