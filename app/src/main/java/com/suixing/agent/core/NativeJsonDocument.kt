package com.suixing.agent.core

import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.*

internal object NativeJsonDocumentJni {
    external fun nativeMeasure(protocol: ByteArray, strings: NativeJsonStrings, count: Int): LongArray?
    external fun nativeWrite(protocol: ByteArray, strings: NativeJsonStrings, count: Int,
                             bytes: Long, chars: Long, sink: NativeJsonSink, scratch: ByteArray)
}

/** JNI resolves one immutable String reference at a time, keeping no batch of char pointers. */
@Keep
internal class NativeJsonStrings(private val values: Array<String>, private val context: CoroutineContext) {
    @Keep
    fun get(index: Int): String {
        context.ensureIoActive()
        if (index !in values.indices) throw IOException("原生 JSON 字符串引用无效")
        return values[index]
    }
}

internal class NativeJsonDocument private constructor(
    private val protocol: ByteArray,
    private val strings: NativeJsonStrings,
    private val stringCount: Int,
    val byteLength: Long,
    val characterLength: Long,
) {
    fun write(sink: NativeJsonSink, scratch: ByteArray) =
        NativeJsonDocumentJni.nativeWrite(protocol, strings, stringCount, byteLength, characterLength, sink, scratch)

    companion object {
        private const val MAGIC = 0x314A5853
        private const val MAX_PROTOCOL_BYTES = 4 * 1024 * 1024
        private const val MAX_NODES = 131_072
        private const val MAX_DEPTH = 128

        /** The protocol contains structure and pool indices, never encoded string/base64 contents. */
        fun prepare(document: JsonElement, parts: WireStringParts, context: CoroutineContext): NativeJsonDocument? {
            context.ensureIoActive()
            if (!NativeCore.available) return null
            val builder = Builder(parts, context)
            if (!builder.node(document, 0)) return null
            val protocol = builder.output.toByteArray()
            ByteBuffer.wrap(protocol).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC).putInt(builder.pool.size).putInt(builder.nodes)
            val strings = NativeJsonStrings(builder.pool.toTypedArray(), context)
            val measured = try {
                NativeJsonDocumentJni.nativeMeasure(protocol, strings, builder.pool.size)
            } catch (_: UnsatisfiedLinkError) {
                return null
            } ?: return null
            context.ensureIoActive()
            if (measured.size != 2 || measured[0] < 0 || measured[1] < 0) {
                throw IOException("原生 JSON 文档长度无效")
            }
            return NativeJsonDocument(protocol, strings, builder.pool.size, measured[0], measured[1])
        }

        private class Builder(private val parts: WireStringParts, private val context: CoroutineContext) {
            val output = ByteArrayOutputStream()
            val pool = ArrayList<String>()
            private val identities = IdentityHashMap<String, Int>()
            var nodes = 0
            init { repeat(12) { output.write(0) } }

            private fun integer(value: Int): Boolean {
                if (output.size() > MAX_PROTOCOL_BYTES - 4) return false
                repeat(4) { output.write(value ushr (it * 8) and 0xff) }
                return true
            }
            private fun opcode(value: Int): Boolean {
                if (output.size() >= MAX_PROTOCOL_BYTES) return false
                output.write(value)
                return true
            }
            private fun string(value: String): Boolean {
                val index = identities[value] ?: run {
                    if (pool.size >= MAX_NODES) return false
                    val next = pool.size
                    pool += value
                    identities[value] = next
                    next
                }
                return integer(index)
            }
            fun node(value: JsonElement, depth: Int): Boolean {
                context.ensureIoActive()
                if (depth > MAX_DEPTH || ++nodes > MAX_NODES) return false
                return when (value) {
                    is JsonPrimitive -> {
                        if (!value.isString) opcode(1) && string(value.toString()) else {
                            val fragments = parts.get(value)
                            if (fragments == null) opcode(2) && string(value.content)
                            else opcode(3) && integer(fragments.size) && fragments.all { string(it) }
                        }
                    }
                    is JsonArray -> opcode(4) && integer(value.size) && value.all { node(it, depth + 1) }
                    is JsonObject -> opcode(5) && integer(value.size) &&
                        value.entries.all { (key, child) -> string(key) && node(child, depth + 1) }
                }
            }
        }
    }
}
