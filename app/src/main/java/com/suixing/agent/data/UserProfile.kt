package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the agent knows about the user, backed by `filesDir/profile.md`.
 *
 * Markdown rather than JSON on purpose: the text is injected into the system prompt
 * verbatim and the user is expected to read and edit it by hand (it also turns up in
 * 会话文件), so a format that survives both matters more than structure. The file is the
 * single source of truth — the tool layer and the settings UI both go through this class,
 * so what the model writes is exactly what the user sees.
 */
class UserProfileStore(private val context: Context) {

    companion object {
        /**
         * Cap on the whole profile. The file is rewritten on every save and the text is
         * injected into every request, so one runaway profile slows down both.
         */
        const val MAX_LENGTH = 8000
    }

    private val file: File get() = File(context.filesDir, "profile.md")

    /**
     * Serialises load and write: the tool layer and the settings UI both write, and without
     * it the later writer starts from a stale read and silently drops the other's text.
     */
    private val lock = Mutex()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /** Reads the file into [text]; the UI calls this once on start, the engine once at startup. */
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { _text.value = read() } }

    /** Replaces the whole profile. Refused — never truncated — past [MAX_LENGTH]. */
    suspend fun write(value: String): Boolean = withContext(Dispatchers.IO) {
        // A profile the user cannot read back in full is worse than one they have to
        // shorten. Callers can compare against [MAX_LENGTH] to say which limit was hit.
        if (value.length > MAX_LENGTH) return@withContext false
        lock.withLock {
            val ok = writeFile(value)
            if (ok) _text.value = value
            ok
        }
    }

    private fun read(): String {
        if (!file.exists()) return ""
        val text = runCatching { file.readText() }.getOrNull()
        // A plain-text file has no schema to fail against, so the two ways it can stop being
        // text are the two ways it can be unreadable: the bytes cannot be read at all, or
        // they are not UTF-8 (readText then hands back U+FFFD). Both used to read as "no
        // profile", and the next save then buried the original — keep it under a name that
        // can still be recovered by hand instead.
        if (text == null || text.contains(REPLACEMENT_CHAR)) {
            quarantine()
            return ""
        }
        return text
    }

    private fun writeFile(value: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        // Write-then-rename, so a kill mid-save cannot leave a half-written file that [read]
        // would quarantine — the old profile survives instead.
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(value)
        if (!temp.renameTo(file)) {
            // renameTo reports failure by returning false, so fall back to a direct write;
            // its exception has to stay visible rather than lose the text.
            try {
                file.writeText(value)
            } finally {
                temp.delete()
            }
        }
    }.isSuccess

    /**
     * Keeps an unreadable file next to its own name (the same call [MemoryStore] makes for
     * memory.corrupt.json) instead of letting the next write bury it.
     */
    private fun quarantine() {
        runCatching { file.renameTo(File(file.parentFile, "profile.corrupt.md")) }
    }
}

/** What `File.readText()` leaves behind for bytes it cannot decode. */
private const val REPLACEMENT_CHAR = '\uFFFD'
