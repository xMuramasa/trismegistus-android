package dev.xmuramasa.trismegistus.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// kotlinx.serialization reads "5" as the integer 5 even when not lenient; the schema says integer, so a string fails.

internal object StrictLong : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val p = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
        return p?.takeIf { !it.isString }?.longOrNull ?: throw SerializationException("expected an integer")
    }

    override fun serialize(encoder: Encoder, value: Long): Unit = encoder.encodeLong(value)
}

internal object StrictLongOrNull : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictLongOrNull", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        if (e is JsonNull) return null
        return (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw SerializationException("expected an integer")
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}
