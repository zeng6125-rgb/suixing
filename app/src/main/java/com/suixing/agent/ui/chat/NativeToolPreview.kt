package com.suixing.agent.ui.chat

import com.suixing.agent.core.NativeCore

internal object NativeToolPreviewJni {
    external fun nativePreview(stored: String, outStatus: IntArray): String?
}

internal sealed interface NativePreviewOutcome {
    data class Ready(val text: String) : NativePreviewOutcome
    data object Empty : NativePreviewOutcome
}

internal object NativeToolPreview {
    private const val MAX_INPUT_UNITS = 4 * 1024 * 1024
    private const val STATUS_OK = 0
    private const val STATUS_EMPTY = 1

    fun preview(stored: String): NativePreviewOutcome? {
        if (!NativeCore.available || stored.length > MAX_INPUT_UNITS) return null
        val status = IntArray(1) { 4 }
        val result = try {
            NativeToolPreviewJni.nativePreview(stored, status)
        } catch (_: UnsatisfiedLinkError) {
            return null
        }
        return when (status[0]) {
            STATUS_OK -> result?.let(NativePreviewOutcome::Ready)
            STATUS_EMPTY -> NativePreviewOutcome.Empty
            else -> null
        }
    }
}
