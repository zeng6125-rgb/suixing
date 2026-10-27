package com.suixing.agent.ui.chat

/** ByteBuffer has no default Coil keyer; use the actual image content for cache identity. */
internal fun imageCacheKey(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef"
    return buildString(75) {
        append("screenshot:")
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0xf])
        }
    }
}
