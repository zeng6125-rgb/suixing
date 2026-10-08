package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val file: File get() = File(context.filesDir, "memory.json")

    private val _entries = MutableStateFlow<Map<String, MemoryEntry>>(emptyMap())
    val entries: StateFlow<Map<String, MemoryEntry>> = _entries.asStateFlow()

    /** Reads the file into [entries]; the UI calls this once on start. */
    suspend fun load() = withContext(Dispatchers.IO) { _entries.value = read() }

    suspend fun put(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val next = read().toMutableMap()
        next[key] = MemoryEntry(value, System.currentTimeMillis())
        val ok = write(next)
        if (ok) _entries.value = next
        ok
    }

    suspend fun remove(key: String): Boolean = withContext(Dispatchers.IO) {
        val next = read().toMutableMap()
        next.remove(key)
        val ok = write(next)
        if (ok) _entries.value = next
        ok
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        val ok = write(emptyMap())
        if (ok) _entries.value = emptyMap()
        ok
    }

    private fun read(): Map<String, MemoryEntry> {
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyMap()
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
            file.writeText(json.toString())
        }.isSuccess
    }
}
