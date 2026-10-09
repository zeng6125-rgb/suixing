package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** One thing the agent decided to remember, plus when it learned it. */
data class MemoryEntry(val value: String = "", val updatedAt: Long = 0L)

/**
 * The agent's long-term notes, backed by `filesDir/memory.json`.
 *
 * The file is the single source of truth and both the tool layer and the UI go
 * through this class, so what the model writes is exactly what the user sees.
 * Entries written by 0.7.0 and earlier were bare strings (`{"key": "value"}`)
 * and are upgraded on read.
 */
class MemoryStore(private val context: Context) {

    companion object {
        /**
         * Cap on a single note. The whole file is rewritten on every change and the
         * notes are injected into the prompt, so one runaway note slows down both.
         */
        const val MAX_VALUE_LENGTH = 4000
    }

    private val file: File get() = File(context.filesDir, "memory.json")

    /**
     * Serialises the read-modify-write in [put] / [remove] / [clear]: the tool layer and
     * the notes UI both write, and without it the later writer starts from a stale read
     * and silently drops the other's key.
     */
    private val lock = Mutex()

    private val _entries = MutableStateFlow<Map<String, MemoryEntry>>(emptyMap())
    val entries: StateFlow<Map<String, MemoryEntry>> = _entries.asStateFlow()

    /** Reads the file into [entries]; the UI calls this once on start. */
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { _entries.value = read() } }

    suspend fun put(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        // Refused, never truncated: a note the model cannot read back in full is worse
        // than a note it has to shorten. Callers can compare against [MAX_VALUE_LENGTH]
        // to say which limit was hit.
        if (value.length > MAX_VALUE_LENGTH) return@withContext false
        lock.withLock {
            val next = read().toMutableMap()
            next[key] = MemoryEntry(value, System.currentTimeMillis())
            val ok = write(next)
            if (ok) _entries.value = next
            ok
        }
    }

    suspend fun remove(key: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = read().toMutableMap()
            next.remove(key)
            val ok = write(next)
            if (ok) _entries.value = next
            ok
        }
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val ok = write(emptyMap())
            if (ok) _entries.value = emptyMap()
            ok
        }
    }

    private fun read(): Map<String, MemoryEntry> {
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root == null) {
            // Unreadable notes used to read as "no notes at all", and the next put then
            // rewrote the file with only its own key, so everything older was gone for
            // good. Keep the bytes under a name that can still be recovered by hand.
            quarantine()
            return emptyMap()
        }
        return root.entries.mapNotNull { (key, element) ->
            when (element) {
                is JsonObject -> {
                    val value = (element["value"] as? JsonPrimitive)?.content
                        ?: return@mapNotNull null
                    val at = (element["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    key to MemoryEntry(value, at)
                }
                // Written by 0.7.0 and earlier: {"key": "value"}.
                is JsonPrimitive -> key to MemoryEntry(element.content, 0L)
                else -> null
            }
        }.toMap()
    }

    private fun write(map: Map<String, MemoryEntry>): Boolean {
        val json = buildJsonObject {
            map.forEach { (key, entry) ->
                put(
                    key,
                    buildJsonObject {
                        put("value", JsonPrimitive(entry.value))
                        put("updatedAt", JsonPrimitive(entry.updatedAt))
                    },
                )
            }
        }
        return runCatching {
            file.parentFile?.mkdirs()
            // Write-then-rename, so a kill mid-save cannot leave a half-written file
            // that [read] would quarantine — the old notes survive instead.
            val temp = File(file.parentFile, "${file.name}.tmp")
            val text = json.toString()
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                // renameTo reports failure by returning false, so fall back to a direct
                // write; its exception has to stay visible rather than lose the note.
                try {
                    file.writeText(text)
                } finally {
                    temp.delete()
                }
            }
        }.isSuccess
    }

    /**
     * Keeps an unreadable file next to its own name (the same call McpStore makes for
     * mcp.corrupt.json) instead of letting the next write bury it.
     */
    private fun quarantine() {
        runCatching { file.renameTo(File(file.parentFile, "memory.corrupt.json")) }
    }
}
