package com.suixing.agent.terminal

import java.io.File
import java.io.FileOutputStream

/** Opt-in raw PTY history, excluded from backup, two 1 MiB segments per session. */
internal class TerminalLog(private val directory: File, private val id: String) {
    private val current get() = File(directory, "$id.log")
    private val previous get() = File(directory, "$id.previous.log")
    @Volatile var enabled = false
        private set
    @Volatile var error: String? = null
        private set
    private var stream: FileOutputStream? = null
    private var bytes = 0L

    @Synchronized fun setEnabled(value: Boolean) {
        close()
        enabled = value
        if (value) runCatching {
            directory.mkdirs()
            // Global quota and expiry also cover sessions removed from the current process.
            val files = directory.listFiles().orEmpty().filter { it.isFile }
                .sortedByDescending { it.lastModified() }
            var total = 0L
            for (file in files) {
                total += file.length()
                if (total > 18 * 1024 * 1024 || System.currentTimeMillis() - file.lastModified() > 7L * 86400000) file.delete()
            }
            bytes = current.length()
            stream = FileOutputStream(current, true)
            error = null
        }.onFailure { enabled = false; error = it.message }
    }

    @Synchronized fun append(data: ByteArray) {
        if (!enabled) return
        runCatching {
            if (bytes + data.size > 1024 * 1024) {
                stream?.close()
                previous.delete()
                check(!current.exists() || current.renameTo(previous)) { "日志轮换失败" }
                stream = FileOutputStream(current)
                bytes = 0
            }
            stream?.write(data)
            bytes += data.size
        }.onFailure { error = it.message; enabled = false; close() }
    }

    @Synchronized fun clear() { close(); current.delete(); previous.delete(); enabled = false }
    @Synchronized fun close() {
        runCatching { stream?.close() }.onFailure { error = it.message }
        stream = null
    }
}
