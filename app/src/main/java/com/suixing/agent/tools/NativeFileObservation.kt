package com.suixing.agent.tools

import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.FileActivityKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeFileObservationJni {
    external fun nativeBegin(request: ByteArray, maxEntries: Int, forceLimited: Boolean): Long
    external fun nativeRelease(handle: Long)
    external fun nativeFinish(handle: Long, overlapping: Boolean): ByteArray?
}

internal object NativeFileObservation {
    private const val MAGIC = 0x314F5853

    fun begin(roots: List<File>, maxEntries: Int, forceLimited: Boolean): Long {
        if (!NativeCore.available || maxEntries <= 0 || roots.size > 12) return 0L
        val output = ByteArrayOutputStream(64 + roots.size * 64)
        fun putInt(value: Int) {
            repeat(4) { output.write(value ushr (it * 8) and 0xff) }
        }
        putInt(MAGIC)
        putInt(roots.size)
        for (root in roots) {
            val path = root.path
            putInt(path.length)
            for (i in 0 until path.length) {
                val unit = path[i].code
                output.write(unit and 0xff)
                output.write((unit ushr 8) and 0xff)
            }
        }
        return try {
            NativeFileObservationJni.nativeBegin(output.toByteArray(), maxEntries, forceLimited)
        } catch (_: UnsatisfiedLinkError) {
            0L
        }
    }

    fun release(handle: Long) {
        if (handle <= 0L || !NativeCore.available) return
        try {
            NativeFileObservationJni.nativeRelease(handle)
        } catch (_: UnsatisfiedLinkError) {
            // Ignored when library is absent.
        }
    }

    fun finish(handle: Long, overlapping: Boolean): ObservedFileChanges? {
        if (handle <= 0L || !NativeCore.available) return null
        val bytes = try {
            NativeFileObservationJni.nativeFinish(handle, overlapping)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        if (bytes.size < 12) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != MAGIC) return null
        val noticeCode = buf.int
        val count = buf.int
        if (count < 0) return null
        val activities = ArrayList<FileActivity>(count)
        fun readString(required: Boolean): String? {
            if (buf.remaining() < 4) return null
            val unitLen = buf.int
            if (unitLen == -1) return if (required) null else ""
            if (unitLen < 0 || buf.remaining() < unitLen * 2L) return null
            return CharArray(unitLen) { buf.short.toInt().and(0xffff).toChar() }.concatToString()
        }
        repeat(count) {
            if (buf.remaining() < 20) return null
            val kind = when (buf.int) {
                0 -> FileActivityKind.CREATED
                1 -> FileActivityKind.MODIFIED
                2 -> FileActivityKind.DELETED
                3 -> FileActivityKind.MOVED
                else -> return null
            }
            val added = buf.int.takeIf { it >= 0 }
            val removed = buf.int.takeIf { it >= 0 }
            val size = buf.long.takeIf { it >= 0L }
            val path = readString(required = true) ?: return null
            val previousLen = if (buf.remaining() >= 4) buf.int else return null
            val previous = if (previousLen == -1) null else {
                if (previousLen < 0 || buf.remaining() < previousLen * 2L) return null
                CharArray(previousLen) { buf.short.toInt().and(0xffff).toChar() }.concatToString()
            }
            activities += FileActivity(
                path = path,
                kind = kind,
                addedLines = added,
                removedLines = removed,
                sizeBytes = size,
                previousPath = previous,
            )
        }
        if (buf.hasRemaining()) return null
        val notice = when (noticeCode) {
            1 -> "文件变更统计不完整：部分路径、内容或扫描范围无法验证"
            2 -> "文件变更归属未知：观测期间有其它文件操作"
            else -> ""
        }
        return ObservedFileChanges(activities, notice)
    }
}
