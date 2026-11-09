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
import java.security.MessageDigest
import java.util.Collections
import com.suixing.agent.core.NativeCore
import com.suixing.agent.core.encodeCoreBytes

/** One thing the agent decided to remember, plus when it learned it. */
data class MemoryEntry(val value: String = "", val updatedAt: Long = 0L)

/** Owned immutable snapshot; its prompt fragment is reused until the notes change. */
internal class MemorySnapshot(source: Map<String, MemoryEntry>) : AbstractMap<String, MemoryEntry>() {
    override val entries: Set<Map.Entry<String, MemoryEntry>> =
        Collections.unmodifiableMap(LinkedHashMap(source)).entries
    val promptBody: String by lazy {
        NativeMemoryPrompt.build(entries) ?: kotlinMemoryPromptBody(entries)
    }
}

/** Ordinary caller maps are copied; no mutable map reference is retained as a native cache key. */
internal fun memoryPromptBody(memory: Map<String, MemoryEntry>): String =
    (memory as? MemorySnapshot ?: MemorySnapshot(memory)).promptBody

private fun kotlinMemoryPromptBody(entries: Set<Map.Entry<String, MemoryEntry>>): String =
    entries.sortedBy { it.key }.take(40).joinToString("\n") { (key, entry) ->
        "- $key: ${entry.value.take(400)}"
    }

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
    private data class ParsedMemory(val digest: ByteArray, val entries: MemorySnapshot)
    private var parsed: ParsedMemory? = null // Accessed only under lock.

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
            if (ok) _entries.value = parsed?.entries ?: MemorySnapshot(next)
            ok
        }
    }

    suspend fun remove(key: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = read().toMutableMap()
            next.remove(key)
            val ok = write(next)
            if (ok) _entries.value = parsed?.entries ?: MemorySnapshot(next)
            ok
        }
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val ok = write(emptyMap())
            if (ok) _entries.value = parsed?.entries ?: MemorySnapshot(emptyMap())
            ok
        }
    }

    private fun read(): Map<String, MemoryEntry> {
        if (!file.exists()) { parsed = null; return emptyMap() }
        // Check actual bytes, not mtime/size: same-length replacements must invalidate the cache.
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: run { parsed = null; return emptyMap() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        parsed?.let { if (it.digest.contentEquals(digest)) return it.entries }
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) { parsed = null; return emptyMap() }
        val root = NativeCore.parseObject(text) ?: runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root == null) {
            // Unreadable notes used to read as "no notes at all", and the next put then
            // rewrote the file with only its own key, so everything older was gone for
            // good. Keep the bytes under a name that can still be recovered by hand.
            quarantine()
            parsed = null
            return emptyMap()
        }
        val snapshot = MemorySnapshot(root.entries.mapNotNull { (key, element) ->
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
        }.toMap())
        parsed = ParsedMemory(digest, snapshot)
        return snapshot
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
        val snapshot = MemorySnapshot(map)
        val bytes = Json.encodeCoreBytes(JsonObject.serializer(), json)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return runCatching {
            // 公共原子写（issue #69 P1-B）：写 .tmp → fsync → 原子替换。
            // 旧实现在 rename 失败时回退成「原地覆盖」，那会在写失败时毁掉旧笔记；
            // 现在失败即抛，由外层 runCatching 报 false，旧文件保持不动。
            com.suixing.agent.core.AtomicFiles.writeBytes(file, bytes)
            // UTF-8 replacement can change lone-surrogate input. Publish the original snapshot
            // as before, but force a real disk decode on the next read instead of caching it.
            parsed = if (map.any { (key, entry) ->
                hasUnpairedSurrogate(key) || hasUnpairedSurrogate(entry.value)
            }) null else ParsedMemory(digest, snapshot)
        }.isSuccess
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch in '\uD800'..'\uDBFF') {
                if (index + 1 >= text.length || text[index + 1] !in '\uDC00'..'\uDFFF') return true
                index += 2
            } else {
                if (ch in '\uDC00'..'\uDFFF') return true
                index++
            }
        }
        return false
    }

    /**
     * Keeps an unreadable file next to its own name (the same call McpStore makes for
     * mcp.corrupt.json) instead of letting the next write bury it.
     */
    private fun quarantine() {
        runCatching { file.renameTo(File(file.parentFile, "memory.corrupt.json")) }
    }
}
