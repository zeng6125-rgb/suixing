package com.suixing.agent.agent

import com.suixing.agent.core.NativeCore

internal object NativeRunLedgerJni {
    external fun nativeCreate(maxRounds: Int, capacity: Int): Long
    external fun nativeRelease(handle: Long)
    external fun nativeRecord(
        handle: Long,
        step: Int,
        toolId: Long,
        polling: Boolean,
        argsHash: ByteArray,
        ok: Boolean,
        output: String,
        imageBase64: String?,
        isJobList: Boolean,
        atMs: Long,
    ): Int
    external fun nativePopWarning(handle: Long): LongArray?
    external fun nativeSummary(handle: Long): LongArray?
}

internal object NativeRunLedger {
    data class Warning(val toolId: Int, val level: Int, val count: Int)
    data class RecentEntry(val step: Int, val toolId: Int, val ok: Boolean)
    data class Summary(val total: Int, val failed: Int, val recent: List<RecentEntry>)

    fun create(maxRounds: Int, capacity: Int): Long {
        if (!NativeCore.available) return 0L
        return try {
            NativeRunLedgerJni.nativeCreate(maxRounds, capacity)
        } catch (_: UnsatisfiedLinkError) {
            0L
        }
    }

    fun release(handle: Long) {
        if (handle <= 0L || !NativeCore.available) return
        try {
            NativeRunLedgerJni.nativeRelease(handle)
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    fun record(
        handle: Long,
        step: Int,
        toolId: Int,
        polling: Boolean,
        argsHash: ByteArray,
        ok: Boolean,
        output: String,
        imageBase64: String?,
        isJobList: Boolean,
        atMs: Long,
    ): Int {
        if (handle <= 0L || !NativeCore.available) return 2
        return try {
            NativeRunLedgerJni.nativeRecord(
                handle = handle,
                step = step,
                toolId = toolId.toLong(),
                polling = polling,
                argsHash = argsHash,
                ok = ok,
                output = output,
                imageBase64 = imageBase64,
                isJobList = isJobList,
                atMs = atMs,
            )
        } catch (_: UnsatisfiedLinkError) {
            2
        }
    }

    fun popWarning(handle: Long): Warning? {
        if (handle <= 0L || !NativeCore.available) return null
        val raw = try {
            NativeRunLedgerJni.nativePopWarning(handle)
        } catch (_: UnsatisfiedLinkError) {
            null
        } ?: return null
        if (raw.size != 3) return null
        return Warning(raw[0].toInt(), raw[1].toInt(), raw[2].toInt())
    }

    fun summary(handle: Long): Summary? {
        if (handle <= 0L || !NativeCore.available) return null
        val raw = try {
            NativeRunLedgerJni.nativeSummary(handle)
        } catch (_: UnsatisfiedLinkError) {
            null
        } ?: return null
        if (raw.size < 3) return null
        val count = raw[2].toInt()
        if (count < 0 || raw.size != 3 + count * 3) return null
        val recent = ArrayList<RecentEntry>(count)
        for (i in 0 until count) {
            val base = 3 + i * 3
            recent.add(RecentEntry(raw[base].toInt(), raw[base + 1].toInt(), raw[base + 2] != 0L))
        }
        return Summary(raw[0].toInt(), raw[1].toInt(), recent)
    }
}
