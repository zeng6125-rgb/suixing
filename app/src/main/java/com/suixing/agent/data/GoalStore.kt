package com.suixing.agent.data

import com.suixing.agent.core.decodeCoreObject
import com.suixing.agent.core.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class GoalStatus { ACTIVE, PAUSED, BLOCKED, COMPLETED }

/** The persistent objective the agent should keep in view across runs. */
@Serializable
data class Goal(
    val text: String = "",
    val updatedAt: Long = 0L,
    val id: String = "",
    val chatId: String = "",
    val status: GoalStatus = GoalStatus.PAUSED,
    val progress: String = "",
    val detail: String = "",
    val revision: Int = 0,
    val wakeAt: Long = 0L,
) {
    val isSet: Boolean get() = text.isNotBlank()
    fun visibleIn(chat: String): Boolean = isSet && (chatId.isEmpty() || chatId == chat)
    fun showInComposer(chat: String, latestUserAt: Long): Boolean =
        visibleIn(chat) && (status != GoalStatus.COMPLETED || latestUserAt <= updatedAt)
    fun objectiveFor(chat: String): String =
        text.takeIf { visibleIn(chat) && (chatId.isEmpty() || status == GoalStatus.ACTIVE) }.orEmpty()
}

/**
 * One persisted objective in `filesDir/goal.json`. Managed runs own it by conversation and id.
 *
 * A goal is not a task list: it is the sentence that tells the model what the work is
 * for. Keeping it outside the transcript means a compressed or reopened conversation
 * still starts from the same objective. Writes are whole-value replacements.
 */
class GoalStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    @Volatile
    private var cached: Goal? = null

    /** Null until loaded. The composer observes this independently of the conversation snapshot. */
    private val _changes = MutableStateFlow<Goal?>(null)
    val changes: StateFlow<Goal?> = _changes.asStateFlow()

    fun read(): Goal = cached ?: synchronized(lock) {
        cached ?: load().let {
            if (it.status == GoalStatus.ACTIVE) {
                it.copy(status = GoalStatus.PAUSED, detail = "上次执行已中断，可以继续", wakeAt = 0L)
            } else it
        }.also { publish(it) }
    }

    /** Empty text clears the goal; the caller gets the exact stored value. */
    fun write(text: String): Goal = synchronized(lock) {
        save(Goal(text = text.trim()))
    }

    fun clear(): Goal = write("")

    internal fun start(chatId: String, text: String): Goal? = synchronized(lock) {
        val current = read()
        if (current.chatId.isNotEmpty() && current.chatId != chatId &&
            current.status != GoalStatus.COMPLETED
        ) return null
        save(Goal(text.trim(), id = UUID.randomUUID().toString(), chatId = chatId, status = GoalStatus.ACTIVE))
    }

    internal fun resume(chatId: String): Goal? = synchronized(lock) {
        val current = read()
        if (!current.visibleIn(chatId) || current.status == GoalStatus.COMPLETED ||
            current.status == GoalStatus.ACTIVE
        ) return null
        // A fresh id revokes any callbacks still unwinding from the previous run.
        save(current.copy(id = UUID.randomUUID().toString(), chatId = chatId,
            status = GoalStatus.ACTIVE, detail = "", wakeAt = 0L))
    }

    internal fun clearFor(chatId: String, expectedId: String? = null): Goal? = synchronized(lock) {
        val current = read()
        if (current.chatId.isNotEmpty() && current.chatId != chatId) return null
        if (expectedId != null && current.id != expectedId) return null
        save(Goal())
    }

    internal fun report(id: String, status: GoalStatus, note: String, wakeAt: Long): Goal? = synchronized(lock) {
        val current = read()
        if (id.isEmpty() || current.id != id || current.status != GoalStatus.ACTIVE) return null
        save(current.copy(status = status, progress = note, detail = "",
            revision = current.revision + if (note == current.progress) 0 else 1, wakeAt = wakeAt))
    }

    internal fun pause(id: String, reason: String, blocked: Boolean = false) = synchronized(lock) {
        val current = read()
        if (id.isNotEmpty() && current.id == id && current.status == GoalStatus.ACTIVE) {
            save(current.copy(status = if (blocked) GoalStatus.BLOCKED else GoalStatus.PAUSED,
                detail = reason, wakeAt = 0L))
        }
    }

    internal fun consumeWake(id: String, wakeAt: Long) = synchronized(lock) {
        val current = read()
        if (current.id == id && current.status == GoalStatus.ACTIVE && current.wakeAt == wakeAt) {
            save(current.copy(wakeAt = 0L))
        }
    }

    private fun save(goal: Goal): Goal {
        val stored = goal.copy(updatedAt = System.currentTimeMillis())
        file.parentFile?.mkdirs()
        AtomicFiles.writeText(file, json.encodeToString(stored))
        publish(stored)
        return stored
    }

    private fun publish(goal: Goal) {
        cached = goal
        _changes.value = goal
    }

    private fun load(): Goal {
        if (!file.exists()) return Goal()
        return runCatching { json.decodeCoreObject(Goal.serializer(), file.readText()) }.getOrElse {
            // A corrupt goal must not silently become a different objective: keep the bad
            // file for diagnosis and start clean rather than guessing at its text.
            runCatching {
                java.nio.file.Files.move(
                    file.toPath(),
                    File(file.parentFile, "goal.corrupt.json").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Goal()
        }
    }
}
