package com.suixing.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One decoder per PTY; incomplete UTF-8 sequences survive arbitrary native read boundaries. */
internal class TerminalUtf8Decoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray, end: Boolean = false): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, end)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        if (end) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}

internal data class TerminalRead(val output: String, val cursor: Long, val truncated: Boolean)

/** Opaque monotonic UTF-16 cursor, independent of UI attachment, with a bounded output ring. */
internal class TerminalOutputRing(private val limit: Int = 256 * 1024) {
    private val text = StringBuilder()
    private var base = 0L
    val end: Long @Synchronized get() = base + text.length
    val truncated: Boolean @Synchronized get() = base > 0

    @Synchronized fun append(chunk: String) {
        text.append(chunk)
        if (text.length > limit) {
            var drop = text.length - limit
            if (drop < text.length && Character.isLowSurrogate(text[drop])) drop++
            text.delete(0, drop)
            base += drop
        }
    }

    @Synchronized fun clear() {
        base += text.length
        text.clear()
    }

    @Synchronized fun read(cursor: Long? = null, maxBytes: Int = 16 * 1024): TerminalRead {
        val budget = maxBytes.coerceIn(1, 16 * 1024)
        var start = cursor?.coerceIn(base, end)?.minus(base)?.toInt() ?: text.length
        if (cursor == null) {
            var bytes = 0
            while (start > 0) {
                val cp = Character.codePointBefore(text, start)
                val size = utf8Size(cp)
                if (bytes + size > budget) break
                bytes += size
                start -= Character.charCount(cp)
            }
        } else if (start < text.length && Character.isLowSurrogate(text[start])) start++
        var stop = start
        var bytes = 0
        while (stop < text.length) {
            val cp = Character.codePointAt(text, stop)
            val size = utf8Size(cp)
            if (bytes + size > budget) break
            bytes += size
            stop += Character.charCount(cp)
        }
        return TerminalRead(text.substring(start, stop), base + stop,
            if (cursor == null) base > 0 || start > 0 else cursor < base)
    }

    private fun utf8Size(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
