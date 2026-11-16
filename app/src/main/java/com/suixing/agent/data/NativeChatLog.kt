package com.suixing.agent.data

import androidx.annotation.Keep
import com.suixing.agent.core.NativeCore
import com.suixing.agent.core.NativeJsonTree
import com.suixing.agent.core.decodeCoreObject
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

internal object NativeChatLogJni {
    external fun nativeRead(fd: FileDescriptor, from: Long, end: Long, sink: NativeChatLogSink): Boolean
    external fun nativeLocate(fd: FileDescriptor, from: Long, end: Long, needle: ByteArray): Long
    external fun nativeCut(turns: ByteArray, want: Int, atStart: Boolean): Int
}

internal object NativeChatLog {
    fun read(file: RandomAccessFile, name: String, from: Long, end: Long, json: Json): List<ChatStore.Entry>? {
        if (!NativeCore.available) return null
        val sink = NativeChatLogSink(name, json)
        return if (NativeChatLogJni.nativeRead(file.fd, from, end, sink)) sink.entries else null
    }

    fun locate(file: RandomAccessFile, from: Long, end: Long, needle: ByteArray): Long? =
        if (NativeCore.available) NativeChatLogJni.nativeLocate(file.fd, from, end, needle) else null

    fun nextLine(file: RandomAccessFile, from: Long, end: Long, probe: Long): Long? {
        if (!NativeCore.available) return null
        val found = NativeChatLogJni.nativeLocate(file.fd, from, minOf(end, from + probe), byteArrayOf())
        return if (found < 0) end else found
    }

    fun cut(entries: List<ChatStore.Entry>, want: Int, atStart: Boolean): Int? {
        if (!NativeCore.available) return null
        val turns = ByteArray(entries.size) { if (isTurnStart(entries[it].message)) 1 else 0 }
        return NativeChatLogJni.nativeCut(turns, want, atStart)
    }
}

@Keep
internal class NativeChatLogSink(private val name: String, private val json: Json) {
    val entries = ArrayList<ChatStore.Entry>()

    @Keep
    fun accept(bytes: ByteArray) {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(input.int) {
            val offset = input.long
            val lineBytes = input.int
            val raw = input.int != 0
            val size = input.int
            val next = input.position() + size
            val message = runCatching {
                if (raw) {
                    val line = ByteArray(size)
                    input.get(line)
                    json.decodeCoreObject(StoredMessage.serializer(), line.toString(Charsets.UTF_8))
                } else {
                    json.decodeFromJsonElement(StoredMessage.serializer(), NativeJsonTree.element(input))
                }
            }.getOrElse { cause ->
                throw ConversationLogDamagedException(
                    "chat $name: line $lineBytes bytes at offset $offset does not parse: ${cause.message}",
                )
            }
            input.position(next)
            entries += ChatStore.Entry(message, offset)
        }
    }
}
