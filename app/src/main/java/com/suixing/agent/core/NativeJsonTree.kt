package com.suixing.agent.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Shared bounded binary JSON tree decoder; no native stream or Android resource is created. */
internal object NativeJsonTree {
    fun envelope(bytes: ByteArray): JsonElement {
        valid(bytes.size in 20..(8 * 1_048_576 + 20))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        valid(integer(input) == 0x31535853)
        valid(integer(input) == 0 && integer(input) == 0 && integer(input) == 1)
        valid(integer(input) == input.remaining())
        val result = element(input)
        valid(!input.hasRemaining())
        return result
    }
    fun valid(ok: Boolean) { if (!ok) throw IOException("原生 JSON 树响应无效") }
    fun integer(input: ByteBuffer): Int { valid(input.remaining() >= 4); return input.int }
    fun string(input: ByteBuffer, maxUnits: Int = 1_048_576): String {
        val length = integer(input)
        valid(length in 0..maxUnits && length.toLong() * 2 <= input.remaining())
        return CharArray(length) { input.short.toInt().and(0xffff).toChar() }.concatToString()
    }
    @OptIn(ExperimentalSerializationApi::class)
    fun element(input: ByteBuffer): JsonElement {
        var nodes = 0
        fun value(depth: Int): JsonElement {
            nodes++
            valid(depth <= 128 && nodes <= 131_072 && input.hasRemaining())
            return when (input.get().toInt().and(0xff)) {
                0 -> JsonNull
                1 -> JsonPrimitive(false)
                2 -> JsonPrimitive(true)
                3 -> JsonPrimitive(string(input))
                4 -> {
                    val number = string(input)
                    valid(validNumber(number))
                    JsonUnquotedLiteral(number)
                }
                5 -> {
                    val count = integer(input)
                    valid(count in 0..131_072 - nodes && count <= input.remaining())
                    JsonArray(List(count) { value(depth + 1) })
                }
                6 -> {
                    val count = integer(input)
                    valid(count in 0..131_072 - nodes && count.toLong() * 5 <= input.remaining())
                    val values = LinkedHashMap<String, JsonElement>(count)
                    repeat(count) {
                        val key = string(input)
                        valid(!values.containsKey(key))
                        values[key] = value(depth + 1)
                    }
                    JsonObject(values)
                }
                else -> throw IOException("原生 JSON 节点类型无效")
            }
        }
        return value(0)
    }
    private fun validNumber(number: String): Boolean {
        var index = if (number.startsWith('-')) 1 else 0
        if (index >= number.length) return false
        if (number[index] == '0') index++ else {
            if (number[index] !in '1'..'9') return false
            do { index++ } while (index < number.length && number[index] in '0'..'9')
        }
        if (index < number.length && number[index] == '.') {
            index++
            val start = index
            while (index < number.length && number[index] in '0'..'9') index++
            if (start == index) return false
        }
        if (index < number.length && (number[index] == 'e' || number[index] == 'E')) {
            index++
            if (index < number.length && (number[index] == '+' || number[index] == '-')) index++
            val start = index
            while (index < number.length && number[index] in '0'..'9') index++
            if (start == index) return false
        }
        return index == number.length
    }
}
