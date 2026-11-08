package com.suixing.agent.agent

import android.util.AtomicFile
import androidx.compose.runtime.Immutable
import com.suixing.agent.core.Core
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TranscriptRedaction
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Immutable
@Serializable
internal data class ChildRunRecord(
    val id: String,
    val parentChatId: String,
    val parentRunId: String,
    val name: String,
    val task: String,
    val context: String,
    val config: ChildRunConfig,
    val status: SubagentStatus = SubagentStatus.QUEUED,
    val messages: List<StoredMessage> = emptyList(),
    val liveText: String = "",
    val liveReasoning: String = "",
    val currentTool: String = "",
    val currentToolArgs: String = "",
    val statusLine: String = "",
    val result: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val finishedAt: Long? = null,
    val saveError: String = "",
)

@Immutable
internal data class ChildRunSummary(
    val id: String,
    val parentChatId: String,
    val parentRunId: String,
    val name: String,
    val status: SubagentStatus,
    val result: String,
    val config: ChildRunConfig,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ChildUsageSource(val key: String, val stamp: String, val messages: List<StoredMessage>?)
internal data class ChildUsageRead(val sources: List<ChildUsageSource>, val issues: List<String>)

/** Persistent independent child conversations. Only the selected record emits token progress. */
internal object SubagentRecords {
    private class Entry(initial: ChildRunRecord, val flow: MutableStateFlow<ChildRunRecord?>,
        var runnerActive: Boolean = false) {
        val lock = Any()
        val writeLock = Mutex()
        var value = initial
        val messages = LinkedHashMap<String, StoredMessage>().apply { initial.messages.forEach { put(it.id, it) } }
        var cachedMessages: List<StoredMessage> = messages.values.toList()
        var messagesChanged = false
        var transcriptVersion = 0L
        val usageIdentity = java.util.UUID.randomUUID().toString()
        val text = StringBuilder(initial.liveText)
        val reasoning = StringBuilder(initial.liveReasoning)
        var publishedAt = 0L
        var publishJob: Job? = null
        var saveJob: Job? = null
        var dirty = false
        var saving = false
        var lastAccessAt = System.nanoTime()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<ChildRunRecord?>>()
    private val flowWatchers = ConcurrentHashMap<String, Job>()
    private val loadingRecords = ConcurrentHashMap.newKeySet<String>()
    private val loadedChats = ConcurrentHashMap.newKeySet<String>()
    private val deletedChats = ConcurrentHashMap.newKeySet<String>()
    private val deletedRecords = ConcurrentHashMap.newKeySet<String>()
    // Retained for deleted parents too: late writers must meet the same lock and tombstone.
    private val parentFileLocks = ConcurrentHashMap<String, Mutex>()
    private val summaryLock = Any()
    private val summaryValues = LinkedHashMap<String, ChildRunSummary>()
    private val summariesByChat = LinkedHashMap<String, LinkedHashMap<String, ChildRunSummary>>()
    /**
     * Summaries are consumed by one parent chat at a time. Keeping one flow per parent avoids
     * rebuilding and filtering the complete cross-chat list whenever an unrelated child changes.
     */
    private val summaryFlows = ConcurrentHashMap<String, MutableStateFlow<List<ChildRunSummary>>>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val COMPLETED_CACHE_SIZE = 6
    private val missingRecord = MutableStateFlow<ChildRunRecord?>(null).asStateFlow()

    fun summaries(chatId: String): Flow<List<ChildRunSummary>> = synchronized(summaryLock) {
        summaryFlows.computeIfAbsent(chatId) {
            MutableStateFlow(summariesForChatLocked(chatId))
        }.asStateFlow()
    }

    fun record(id: String): StateFlow<ChildRunRecord?> {
        val flow = synchronized(summaryLock) {
            if (id in deletedRecords) return missingRecord
            flows.computeIfAbsent(id) { MutableStateFlow(null) }.also { flow ->
                flowWatchers.computeIfAbsent(id) {
                    scope.launch {
                        flow.subscriptionCount.collect { count ->
                            if (count > 0) ensureLoaded(id) else trimCache()
                        }
                    }
                }
            }
        }
        entries[id]?.let { synchronized(it.lock) { it.lastAccessAt = System.nanoTime() }; publish(it) }
            ?: scope.launch { ensureLoaded(id) }
        return flow.asStateFlow()
    }

    fun create(parentChatId: String, parentRunId: String, task: SubagentTask, config: ChildRunConfig = task.config): ChildRunRecord {
        val input = buildString {
            append("Task:\n").append(task.task)
            if (task.context.isNotBlank()) append("\n\nRelevant context:\n").append(task.context)
        }
        val value = ChildRunRecord(task.id, parentChatId, parentRunId, task.name, task.task, task.context, config,
            messages = listOf(StoredMessage(id = task.id + "_input", role = "user", text = input)))
        val entry = synchronized(summaryLock) {
            check(parentChatId !in deletedChats) { "父会话已删除，无法创建子任务。" }
            Entry(value, flows.computeIfAbsent(task.id) { MutableStateFlow(null) }, runnerActive = true).also {
                check(entries.putIfAbsent(task.id, it) == null) { "子任务记录已存在" }
            }
        }
        publish(entry)
        summary(entry)
        saveSoon(entry)
        return value
    }

    fun onEvent(id: String, event: AgentEvent) {
        val entry = entries[id] ?: return
        val streaming = event is AgentEvent.TextDelta || event is AgentEvent.ReasoningDelta
        synchronized(entry.lock) {
            if (!retained(entry)) return
            when (event) {
                is AgentEvent.TextDelta -> entry.text.append(event.text)
                is AgentEvent.ReasoningDelta -> entry.reasoning.append(event.text)
                is AgentEvent.AssistantMessage -> {
                    entry.messages[event.message.id] = event.message
                    entry.messagesChanged = true
                    entry.transcriptVersion++
                    entry.text.setLength(0)
                    entry.reasoning.setLength(0)
                }
                is AgentEvent.ToolMessage -> {
                    entry.messages[event.message.id] = event.message
                    entry.messagesChanged = true
                    entry.transcriptVersion++
                }
                is AgentEvent.ToolStarted -> entry.value = entry.value.copy(
                    currentTool = event.name,
                    currentToolArgs = TranscriptRedaction.argumentsForTranscript(event.name, event.args),
                    statusLine = "正在调用 ${event.name}")
                is AgentEvent.ToolFinished -> entry.value = entry.value.copy(currentTool = "", currentToolArgs = "", statusLine = "")
                is AgentEvent.Failed -> entry.value = entry.value.copy(statusLine = event.message)
                is AgentEvent.Finished -> entry.value = entry.value.copy(currentTool = "", currentToolArgs = "", statusLine = "")
                is AgentEvent.Steered -> {
                    entry.messages[event.id] = StoredMessage(
                        id = event.id, role = "user", text = event.text, imagesBase64 = event.imagesBase64, steer = true)
                    entry.messagesChanged = true
                    entry.transcriptVersion++
                }
                is AgentEvent.Retrying -> entry.value = entry.value.copy(statusLine = "正在重试：${event.reason}")
                AgentEvent.RoundRestarted -> { entry.text.setLength(0); entry.reasoning.setLength(0) }
                is AgentEvent.Compressing -> entry.value = entry.value.copy(statusLine = if (event.on) "正在压缩上下文" else "")
            }
            entry.dirty = true
        }
        if (streaming) publishSoon(entry) else { publish(entry); summary(entry) }
        saveSoon(entry)
    }

    /** Exact engine messages replace matching event messages, including the initial input. */
    fun complete(id: String, messages: List<StoredMessage>) {
        val entry = entries[id] ?: return
        synchronized(entry.lock) {
            if (!retained(entry)) return
            messages.forEach { entry.messages[it.id] = it }
            if (messages.isNotEmpty()) {
                entry.messagesChanged = true
                entry.transcriptVersion++
            }
            entry.text.setLength(0)
            entry.reasoning.setLength(0)
            entry.dirty = true
        }
        publish(entry)
        saveSoon(entry)
    }

    fun update(id: String, status: SubagentStatus, result: String = "") {
        val entry = entries[id] ?: return
        synchronized(entry.lock) {
            if (!retained(entry)) return
            val terminal = status !in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING)
            entry.value = entry.value.copy(status = status, result = result,
                currentTool = if (terminal) "" else entry.value.currentTool,
                currentToolArgs = if (terminal) "" else entry.value.currentToolArgs,
                finishedAt = if (terminal) System.currentTimeMillis() else null)
            entry.dirty = true
        }
        publish(entry)
        summary(entry)
        saveSoon(entry)
    }

    /** A cancelled status can arrive before the runner emits its final transcript events. */
    fun terminated(id: String) {
        val entry = entries[id] ?: return
        synchronized(entry.lock) {
            if (!retained(entry)) return
            entry.runnerActive = false
        }
        trimCache()
    }

    /** Immediately hide/tombstone records; serialize disk removal with any in-flight save. */
    suspend fun delete(chatId: String) {
        val removed = synchronized(summaryLock) {
            deletedChats.add(chatId)
            loadedChats.remove(chatId)
            val matching = entries.values.filter { it.value.parentChatId == chatId }
            val ids = summaryValues.values.filter { it.parentChatId == chatId }.map { it.id }.toSet() +
                matching.map { it.value.id }
            deletedRecords.addAll(ids)
            ids.forEach { id ->
                entries.remove(id)
                summaryValues.remove(id)
                loadingRecords.remove(id)
                flowWatchers.remove(id)?.cancel()
                flows.remove(id)?.value = null
            }
            summariesByChat.remove(chatId)
            summaryFlows.remove(chatId)?.value = emptyList()
            matching
        }
        removed.forEach { entry -> synchronized(entry.lock) {
            entry.dirty = false
            entry.publishJob?.cancel()
            entry.saveJob?.cancel()
            entry.flow.value = null
        } }
        withContext(NonCancellable + Dispatchers.IO) {
            parentFileLock(chatId).withLock {
                val directory = parentDirectory(chatId)
                if (directory.exists() && !directory.deleteRecursively()) {
                    throw IOException("无法删除此会话的子任务记录。")
                }
            }
        }
    }

    suspend fun load(chatId: String) = withContext(Dispatchers.IO) {
        val admitted = synchronized(summaryLock) { chatId !in deletedChats && loadedChats.add(chatId) }
        if (!admitted) return@withContext
        try {
            parentDirectory(chatId).listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
                val loaded = parentFileLock(chatId).withLock {
                    if (chatId in deletedChats) null else readRecord(file)
                } ?: return@forEach
                if (loaded.parentChatId != chatId || loaded.id != file.nameWithoutExtension) return@forEach
                val interrupted = loaded.status in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING)
                val value = if (interrupted) loaded.copy(status = SubagentStatus.CANCELLED,
                    statusLine = "应用已重启，子任务未继续运行。", currentTool = "", currentToolArgs = "",
                    finishedAt = System.currentTimeMillis()) else loaded
                val restored = synchronized(summaryLock) {
                    if (chatId in deletedChats || entries.containsKey(value.id)) return@forEach
                    summary(value)
                    // Completed histories contribute only metadata until the user opens one.
                    if (interrupted) {
                        Entry(value, flows.computeIfAbsent(value.id) { MutableStateFlow(null) }).also {
                            it.dirty = true
                            entries[value.id] = it
                        }
                    } else null
                }
                if (restored != null) {
                    publish(restored)
                    saveSoon(restored)
                }
            }
        } catch (failure: Exception) {
            loadedChats.remove(chatId)
            throw failure
        }
    }

    /** Called after parent cleanup so the last status/transcript is on disk before returning. */
    suspend fun flush(chatId: String? = null) {
        val selected = entries.values.filter { chatId == null || it.value.parentChatId == chatId }
        selected.mapNotNull { synchronized(it.lock) { it.saveJob } }.joinAll()
        selected.forEach { writeLatest(it) }
    }

    private fun snapshot(entry: Entry): ChildRunRecord = synchronized(entry.lock) {
        if (entry.messagesChanged) {
            entry.cachedMessages = entry.messages.values.toList()
            entry.messagesChanged = false
        }
        entry.value.copy(messages = entry.cachedMessages, liveText = entry.text.toString(),
            liveReasoning = entry.reasoning.toString(), updatedAt = System.currentTimeMillis())
    }

    private fun publish(entry: Entry) {
        synchronized(entry.lock) {
            if (!retained(entry)) return
            entry.publishedAt = System.nanoTime()
            entry.flow.value = snapshot(entry)
        }
    }

    private fun publishSoon(entry: Entry) {
        synchronized(entry.lock) {
            if (!retained(entry) || entry.flow.subscriptionCount.value == 0 || entry.publishJob?.isActive == true) return
            val elapsedMs = (System.nanoTime() - entry.publishedAt) / 1_000_000L
            entry.publishJob = scope.launch {
                delay((80L - elapsedMs).coerceAtLeast(0L))
                publish(entry)
            }
        }
    }

    private fun summary(entry: Entry) {
        val value = synchronized(entry.lock) { entry.value.copy(updatedAt = System.currentTimeMillis()) }
        summary(value)
    }

    private fun summary(value: ChildRunRecord) {
        val summary = ChildRunSummary(value.id, value.parentChatId, value.parentRunId, value.name, value.status,
            value.result.take(240), value.config, value.createdAt, value.updatedAt)
        synchronized(summaryLock) {
            if (value.parentChatId in deletedChats) return
            // Tool status updates and repeated terminal events can arrive with identical
            // user-visible metadata. Avoid allocating/sorting/emitting when the summary did not
            // actually change; the selected record has its own throttled stream for live text.
            if (summaryValues[value.id]?.sameVisibleContentAs(summary) == true) {
                // Keep bookkeeping current without invalidating a UI that cannot observe this
                // timestamp-only change.
                summaryValues[value.id] = summary
                summariesByChat.getOrPut(value.parentChatId) { LinkedHashMap() }[value.id] = summary
                return
            }
            summaryValues[value.id] = summary
            summariesByChat.getOrPut(value.parentChatId) { LinkedHashMap() }[value.id] = summary
            summaryFlows[value.parentChatId]?.value = summariesForChatLocked(value.parentChatId)
        }
    }

    /** `updatedAt` is bookkeeping; it must not turn an identical UI summary into a new emission. */
    private fun ChildRunSummary.sameVisibleContentAs(other: ChildRunSummary): Boolean =
        id == other.id && parentChatId == other.parentChatId && parentRunId == other.parentRunId &&
            name == other.name && status == other.status && result == other.result &&
            config == other.config && createdAt == other.createdAt

    /** Must be called while holding [summaryLock]. */
    private fun summariesForChatLocked(chatId: String): List<ChildRunSummary> =
        summariesByChat[chatId].orEmpty().values.asSequence()
            .sortedBy { it.createdAt }
            .toList()

    private fun saveSoon(entry: Entry) {
        synchronized(entry.lock) {
            if (!retained(entry)) return
            entry.dirty = true
            if (entry.saveJob?.isActive == true) return
            val job = scope.launch { delay(1_000L); writeLatest(entry) }
            entry.saveJob = job
            job.invokeOnCompletion {
                val more = synchronized(entry.lock) { retained(entry) && entry.dirty && entry.value.saveError.isBlank() }
                if (more) saveSoon(entry)
            }
        }
    }

    private suspend fun writeLatest(entry: Entry) = entry.writeLock.withLock write@{
        val value = synchronized(entry.lock) {
            if (!retained(entry) || !entry.dirty) return@write
            entry.dirty = false
            entry.saving = true
            snapshot(entry).copy(saveError = "")
        }
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                parentFileLock(value.parentChatId).withLock disk@{
                    if (!retained(entry)) return@disk
                    val atomic = try {
                        val directory = parentDirectory(value.parentChatId)
                        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建子任务记录目录。")
                        AtomicFile(File(directory, value.id + ".json"))
                    } catch (failure: Exception) { saveFailed(entry, failure); return@disk }
                    val output = try { atomic.startWrite() } catch (failure: Exception) {
                        saveFailed(entry, failure)
                        return@disk
                    }
                    try {
                        output.write(json.encodeToString(ChildRunRecord.serializer(), value).toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(output)
                        committed = true
                        val recovered = synchronized(entry.lock) {
                            val hadError = entry.value.saveError.isNotBlank()
                            entry.value = entry.value.copy(saveError = "")
                            hadError
                        }
                        if (recovered) publish(entry)
                    } catch (failure: Exception) {
                        atomic.failWrite(output)
                        saveFailed(entry, failure)
                    }
                }
            }
        } finally {
            synchronized(entry.lock) {
                entry.saving = false
                if (!committed && retained(entry)) entry.dirty = true
            }
            trimCache()
        }
    }

    private fun saveFailed(entry: Entry, failure: Exception) {
        synchronized(entry.lock) {
            if (!retained(entry)) return
            entry.dirty = true
            entry.value = entry.value.copy(saveError = "记录保存失败：${failure.message ?: failure.javaClass.simpleName}")
        }
        publish(entry)
    }

    private suspend fun ensureLoaded(id: String) {
        if (id in deletedRecords) return
        entries[id]?.let { entry ->
            synchronized(entry.lock) { entry.lastAccessAt = System.nanoTime() }
            publish(entry)
            return
        }
        if (!loadingRecords.add(id)) return
        try {
            val summary = synchronized(summaryLock) { summaryValues[id] } ?: return
            val loaded = withContext(Dispatchers.IO) {
                parentFileLock(summary.parentChatId).withLock {
                    if (summary.parentChatId in deletedChats) null else
                        runCatching { readRecord(File(parentDirectory(summary.parentChatId), id + ".json")) }.getOrNull()
                }
            }
            val kept = synchronized(summaryLock) {
                if (id in deletedRecords || summary.parentChatId in deletedChats) return
                val flow = flows.computeIfAbsent(id) { MutableStateFlow(null) }
                if (loaded == null || loaded.id != id || loaded.parentChatId != summary.parentChatId) {
                    flow.value = ChildRunRecord(id, summary.parentChatId, summary.parentRunId, summary.name, "", "", summary.config,
                        status = summary.status, result = summary.result, createdAt = summary.createdAt,
                        saveError = "无法读取此子任务的完整记录。")
                    return
                }
                val value = if (summary.status == SubagentStatus.CANCELLED &&
                    loaded.status in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING)) {
                    loaded.copy(status = summary.status, statusLine = "应用已重启，子任务未继续运行。")
                } else loaded
                val entry = Entry(value, flow)
                entries.putIfAbsent(id, entry) ?: entry
            }
            synchronized(kept.lock) { kept.lastAccessAt = System.nanoTime() }
            publish(kept)
            trimCache()
        } finally { loadingRecords.remove(id) }
    }

    /** Only persisted, unselected terminal records may leave the full-record cache. */
    private fun trimCache() {
        val eligible = entries.values.filter { entry -> synchronized(entry.lock) {
            entry.value.status !in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING) &&
                !entry.runnerActive && !entry.saving && !entry.dirty && entry.value.saveError.isBlank() &&
                entry.flow.subscriptionCount.value == 0
        } }.sortedBy { synchronized(it.lock) { it.lastAccessAt } }
        eligible.take((eligible.size - COMPLETED_CACHE_SIZE).coerceAtLeast(0)).forEach { entry ->
            synchronized(entry.lock) {
                if (entry.flow.subscriptionCount.value == 0 && !entry.dirty && entry.value.saveError.isBlank() &&
                    !entry.runnerActive && !entry.saving && entry.value.status !in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING) &&
                    entries.remove(entry.value.id, entry)) {
                    entry.flow.value = null
                }
            }
        }
    }

    private fun readRecord(file: File): ChildRunRecord? = runCatching {
        AtomicFile(file).openRead().use { input ->
            json.decodeFromString(ChildRunRecord.serializer(), input.readBytes().toString(Charsets.UTF_8))
        }
    }.getOrNull()

    /** Read committed child messages without mounting their UI flows or flushing their files. */
    suspend fun usageSources(filesDir: File, parentIds: Set<String>, stamps: Map<String, String>): ChildUsageRead =
        withContext(Dispatchers.IO) {
            val sources = LinkedHashMap<String, ChildUsageSource>()
            val issues = mutableListOf<String>()
            parentIds.forEach { parentId ->
                val directory = parentDirectory(parentId, filesDir)
                val files = directory.listFiles { file -> file.extension == "json" }
                if (files == null && directory.exists()) {
                    issues += "部分子对话未能刷新，已保留此前统计。"
                    stamps.filterKeys { it.startsWith("child/$parentId/") }.forEach { (key, stamp) ->
                        sources[key] = ChildUsageSource(key, stamp, null)
                    }
                }
                files.orEmpty().forEach { file ->
                    val id = file.nameWithoutExtension
                    if (entries.containsKey(id) || parentId in deletedChats) return@forEach
                    val key = "child/$parentId/$id"
                    val stamp = "disk:${file.length()}:${file.lastModified()}"
                    if (stamps[key] == stamp) sources[key] = ChildUsageSource(key, stamp, null)
                    else {
                        val record = parentFileLock(parentId).withLock { readRecord(file) }
                        if (record?.id == id && record.parentChatId == parentId) {
                            sources[key] = ChildUsageSource(key, stamp, record.messages)
                        } else {
                            sources[key] = ChildUsageSource(key, stamps[key].orEmpty(), null)
                            issues += "部分子对话无法读取，统计可能不完整。"
                        }
                    }
                }
            }
            entries.values.forEach { entry -> synchronized(entry.lock) {
                val parent = entry.value.parentChatId
                if (parent in parentIds && retained(entry)) {
                    val key = "child/$parent/${entry.value.id}"
                    val stamp = "live:${entry.usageIdentity}:${entry.transcriptVersion}"
                    sources[key] = ChildUsageSource(key, stamp,
                        if (stamps[key] == stamp) null else snapshot(entry).messages)
                }
            } }
            ChildUsageRead(sources.values.toList(), issues.distinct())
        }

    private fun parentDirectory(chatId: String, filesDir: File = Core.appContext.filesDir): File {
        val key = MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(filesDir, "subagent_records/$key")
    }

    private fun parentFileLock(chatId: String): Mutex = parentFileLocks.computeIfAbsent(chatId) { Mutex() }

    private fun retained(entry: Entry): Boolean = entry.value.parentChatId !in deletedChats &&
        entries[entry.value.id] === entry
}
