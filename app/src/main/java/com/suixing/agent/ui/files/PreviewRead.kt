package com.suixing.agent.ui.files

import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read at most limit + one sentinel byte, including files that grow after their size check. */
internal suspend fun boundedPreviewBytes(input: InputStream, limit: Int): ByteArray? {
    require(limit in 1 until Int.MAX_VALUE)
    val buffer = ByteArray(limit + 1)
    var count = 0
    while (count < buffer.size) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer, count, minOf(4096, buffer.size - count))
        if (read < 0) break
        if (read == 0) {
            val byte = input.read()
            if (byte < 0) break
            buffer[count++] = byte.toByte()
        } else count += read
    }
    currentCoroutineContext().ensureActive()
    return if (count > limit) null else buffer.copyOf(count)
}
