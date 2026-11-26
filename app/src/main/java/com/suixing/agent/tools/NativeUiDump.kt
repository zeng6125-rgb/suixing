package com.suixing.agent.tools

import com.suixing.agent.core.NativeCore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeUiDumpJni {
    external fun nativeParse(xml: String): ByteArray?
    external fun nativeFocusedPackage(dump: String): ByteArray?
}

internal object NativeUiDump {
    private const val MAGIC = 0x31555853
    private const val MAX_UNITS = 4 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    fun parse(xml: String): List<UiNode>? {
        if (!NativeCore.available || xml.length > MAX_UNITS) return null
        val bytes = try {
            NativeUiDumpJni.nativeParse(xml)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        return decodeNodes(bytes)
    }

    internal fun decodeNodes(bytes: ByteArray): List<UiNode> {
        fun valid(ok: Boolean) {
            if (!ok) throw IOException("原生 UI 树响应格式无效")
        }
        valid(bytes.size in 8..MAX_RESPONSE_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int {
            valid(input.remaining() >= 4)
            return input.int
        }
        fun utf16String(): String {
            val units = integer()
            valid(units >= 0 && units.toLong() * 2L <= input.remaining())
            if (units == 0) return ""
            val chars = CharArray(units)
            for (i in 0 until units) {
                chars[i] = input.char
            }
            return String(chars)
        }
        valid(integer() == MAGIC)
        val count = integer()
        valid(count >= 0 && count.toLong() * 36L <= input.remaining())
        val nodes = ArrayList<UiNode>(count)
        repeat(count) {
            val left = integer()
            val top = integer()
            val right = integer()
            val bottom = integer()
            val flags = integer()
            val cls = utf16String()
            val text = utf16String()
            val desc = utf16String()
            val id = utf16String()
            nodes += UiNode(
                cls = cls,
                text = text,
                desc = desc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                clickable = (flags and 1) != 0,
                scrollable = (flags and 2) != 0,
                editable = (flags and 4) != 0,
                id = id,
            )
        }
        valid(!input.hasRemaining())
        return nodes
    }

    internal sealed interface FocusOutcome {
        data class Found(val pkg: String) : FocusOutcome
        data object Empty : FocusOutcome
    }

    fun focusedPackage(dump: String): FocusOutcome? {
        if (!NativeCore.available || dump.length > MAX_UNITS) return null
        val bytes = try {
            NativeUiDumpJni.nativeFocusedPackage(dump)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        return decodeFocus(bytes)
    }

    internal fun decodeFocus(bytes: ByteArray): FocusOutcome {
        fun valid(ok: Boolean) {
            if (!ok) throw IOException("原生焦点窗口响应无效")
        }
        valid(bytes.size in 8..MAX_RESPONSE_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int {
            valid(input.remaining() >= 4)
            return input.int
        }
        valid(integer() == MAGIC)
        val present = integer()
        if (present == 0) {
            valid(!input.hasRemaining())
            return FocusOutcome.Empty
        }
        valid(present == 1)
        val units = integer()
        valid(units >= 0 && units.toLong() * 2L == input.remaining().toLong())
        val chars = CharArray(units)
        for (i in 0 until units) {
            chars[i] = input.char
        }
        valid(!input.hasRemaining())
        return FocusOutcome.Found(String(chars))
    }
}
