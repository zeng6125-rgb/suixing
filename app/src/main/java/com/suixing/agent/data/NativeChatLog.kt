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
    external fun nativeRead(
        fd: FileDescriptor, from: Long, end: Long, sink: NativeChatLogSink, checkpoint: ChatLogCheckpoint?,
    ): Boolean
    external fun nativeLocate(fd: FileDescriptor, from: Long, end: Long, needle: ByteArray): Long
    external fun nativeLocatePair(fd: FileDescriptor, end: Long, first: ByteArray, last: ByteArray): LongArray
    external fun nativeCut(turns: ByteArray, want: Int, atStart: Boolean): Int
    external fun nativeWindowStart(fd: FileDescriptor, end: Long, turns: Int, checkpoint: ChatLogCheckpoint?): Long
}

internal object NativeChatLog {
    fun read(
        file: RandomAccessFile, name: String, from: Long, end: Long, json: Json,
        checkpoint: ChatLogCheckpoint? = null,
    ): List<ChatStore.Entry>? {
        if (!NativeCore.available) return null
        val sink = NativeChatLogSink(name, json, checkpoint)
        return if (NativeChatLogJni.nativeRead(file.fd, from, end, sink, checkpoint)) sink.entries else null
    }

    fun locate(file: RandomAccessFile, from: Long, end: Long, needle: ByteArray): Long? =
        if (NativeCore.available) NativeChatLogJni.nativeLocate(file.fd, from, end, needle) else null

    fun windowStart(file: RandomAccessFile, end: Long, turns: Int, checkpoint: ChatLogCheckpoint? = null): Long? =
        if (NativeCore.available) NativeChatLogJni.nativeWindowStart(file.fd, end, turns, checkpoint).takeIf { it >= 0 }
        else null

    fun locatePair(file: RandomAccessFile, end: Long, first: ByteArray, last: ByteArray): LongArray? =
        if (NativeCore.available) NativeChatLogJni.nativeLocatePair(file.fd, end, first, last) else null

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
internal class ChatLogCheckpoint(private val ensureActive: () -> Unit) {
    @Keep fun check() = ensureActive()
}

@Keep
internal class NativeChatLogSink(
    private val name: String, private val json: Json, private val checkpoint: ChatLogCheckpoint?,
) {
    val entries = ArrayList<ChatStore.Entry>()

    /** The native batch is borrowed until this method returns. Entries own their decoded data. */
    @Keep
    fun accept(input: ByteBuffer) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        repeat(input.int) {
            checkpoint?.check()
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
