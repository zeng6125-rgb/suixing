package com.suixing.agent.tools

import com.suixing.agent.core.NativeCore
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeWebHtmlJni {
    external fun nativeHtmlToText(html: String): String?
    external fun nativeDecodeEntities(text: String): String?
    external fun nativeParseDuckDuckGo(html: String, count: Int): ByteArray?
}

internal object NativeWebHtml {
    private const val MAX_UNITS = 4 * 1024 * 1024

    fun htmlToText(html: String): String? {
        if (!NativeCore.available || html.length > MAX_UNITS) return null
        return try {
            NativeWebHtmlJni.nativeHtmlToText(html)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    fun decodeEntities(text: String): String? {
        if (!NativeCore.available || text.length > MAX_UNITS || !text.contains('&')) return null
        return try {
            NativeWebHtmlJni.nativeDecodeEntities(text)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    fun parseDuckDuckGo(html: String, count: Int): List<String>? {
        if (!NativeCore.available || count < 0 || html.length > MAX_UNITS) return null
        val bytes = try {
            NativeWebHtmlJni.nativeParseDuckDuckGo(html, count)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        if (bytes.size < 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val hitCount = buf.int
        if (hitCount < 0) return null
        val hits = ArrayList<String>(hitCount)
        repeat(hitCount) {
            if (buf.remaining() < 4) return null
            val unitLen = buf.int
            if (unitLen < 0 || buf.remaining() < unitLen * 2) return null
            hits += CharArray(unitLen) { buf.short.toInt().and(0xffff).toChar() }.concatToString()
        }
        return if (buf.hasRemaining()) null else hits
    }
}
