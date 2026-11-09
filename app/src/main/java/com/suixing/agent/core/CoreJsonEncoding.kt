package com.suixing.agent.core

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import okio.Buffer

/**
 * Persisted JSON keeps its original serializer, defaults, enum spelling and property order.
 * Compact documents use the shared streaming UTF-8 writer, without a complete intermediate JSON
 * String. Its UTF-16 encoder preserves the final UTF-8 '?' replacement for unpaired surrogates.
 * Pretty printing and library absence keep the exact original String-to-bytes serialization path.
 */
internal fun <T> Json.encodeCoreBytes(serializer: SerializationStrategy<T>, value: T): ByteArray {
    if (configuration.prettyPrint || !NativeCore.available) {
        return encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
    }
    val document = encodeToJsonElement(serializer, value)
    val buffer = Buffer()
    JsonStreamingBody(document).writeTo(buffer)
    return buffer.readByteArray()
}

internal inline fun <reified T> Json.encodeCoreBytes(value: T): ByteArray =
    encodeCoreBytes(serializer<T>(), value)
