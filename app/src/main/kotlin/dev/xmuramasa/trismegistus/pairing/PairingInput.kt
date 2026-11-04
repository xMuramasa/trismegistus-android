package dev.xmuramasa.trismegistus.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What `trismegistus-relay pair` hands over: the relay's https base URL and a one-time code. */
data class PairingOffer(val url: String, val code: String)

/** Reads the QR payload and the pasted fields. Untrusted input: anything off the format is null. */
object PairingInput {
    private val codeFormat = Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}")

    /** `{"v":1,"url":…,"code":…}`. */
    fun fromQr(text: String): PairingOffer? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if ((o["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != 1) return null
        val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::url) ?: return null
        val code = (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::code) ?: return null
        return PairingOffer(url, code)
    }

    /** `XXXX-XXXX-XXXX`, spaces dropped; twelve characters without the dashes get them. Case is kept. */
    fun code(text: String): String? {
        val s = text.filterNot(Char::isWhitespace)
        val dashed = if (s.length == 12 && '-' !in s) s.chunked(4).joinToString("-") else s
        return dashed.takeIf(codeFormat::matches)
    }

    /** An https base URL without a trailing slash; a bare host gets `https://`. Cleartext is refused. */
    fun url(text: String): String? {
        val s = text.trim().trimEnd('/')
        val withScheme = if ("://" in s) s else "https://$s"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.host.isEmpty() || parsed.query != null) return null
        return withScheme
    }
}
