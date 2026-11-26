package com.suixing.agent.voice

import com.suixing.agent.core.NativeCore

internal object NativeSpeechCleanJni {
    external fun nativeClean(text: String): String?
}

internal object NativeSpeechClean {
    private const val MAX_INPUT_UNITS = 4 * 1024 * 1024

    fun clean(text: String): String? {
        if (!NativeCore.available || text.length > MAX_INPUT_UNITS) return null
        return try {
            NativeSpeechCleanJni.nativeClean(text)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }
}
