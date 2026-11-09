package com.suixing.agent.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

internal object NativeCoreJni {
    external fun nativeCreate(): Long
    external fun nativeCancel(handle: Long)
    external fun nativeRelease(handle: Long)
    external fun nativeTokens(text: String): Int
    external fun nativeRetry(status: Int, present: Boolean, attempt: Int, max: Int, received: Boolean, cancelled: Boolean, midStream: Boolean): Boolean
    external fun nativeParseValue(handle: Long, text: String): ByteArray?
    external fun nativeTrimChunk(costs: IntArray, users: ByteArray, budget: Int, used: Int, kept: Int): IntArray
    external fun nativeMeasureString(text: String): LongArray
    external fun nativeWriteString(text: String, sink: NativeJsonSink, scratch: ByteArray, quoted: Boolean)
}

/** Shared pure computation/JSON ABI. Kotlin retains Android effects and typed schema decoding. */
internal object NativeCore {
    val available: Boolean by lazy {
        try { System.loadLibrary("suixing_search"); true }
        catch (_: UnsatisfiedLinkError) { false }
        catch (_: SecurityException) { false }
    }
    fun tokens(text: String): Int? = if (available) NativeCoreJni.nativeTokens(text) else null
    fun retry(status: Int?, attempt: Int, max: Int, received: Boolean, cancelled: Boolean, midStream: Boolean): Boolean? =
        if (available) NativeCoreJni.nativeRetry(status ?: 0, status != null, attempt, max, received, cancelled, midStream) else null

    /** Unsupported syntax, lone surrogates and bounded-tree cases request the original parser. */
    fun parseValue(text: String): JsonElement? {
        if (!available || text.length > 1_048_576) return null
        val handle = NativeCoreJni.nativeCreate()
        try {
            return NativeCoreJni.nativeParseValue(handle, text)?.let(NativeJsonTree::envelope)
        } finally { NativeCoreJni.nativeRelease(handle) }
    }
    fun parseObject(text: String): JsonObject? = parseValue(text) as? JsonObject
}

/** The existing serializer keeps defaults, enums and platform schema behavior. */
internal fun <T> Json.decodeCoreObject(serializer: DeserializationStrategy<T>, text: String): T {
    return decodeCoreValue(serializer, text)
}

internal fun <T> Json.decodeCoreValue(serializer: DeserializationStrategy<T>, text: String): T {
    val value = NativeCore.parseValue(text)
    return if (value == null) decodeFromString(serializer, text) else decodeFromJsonElement(serializer, value)
}

internal inline fun <reified T> Json.decodeCoreValue(text: String): T =
    decodeCoreValue(serializer<T>(), text)

internal fun Json.parseCoreValue(text: String): JsonElement =
    NativeCore.parseValue(text) ?: parseToJsonElement(text)
