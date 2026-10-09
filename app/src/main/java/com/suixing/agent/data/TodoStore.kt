package com.suixing.agent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

const val TODO_PENDING = "pending"
const val TODO_IN_PROGRESS = "in_progress"
const val TODO_COMPLETED = "completed"

/** One line of the board: what is being done, and how far along it is. */
@Serializable
data class TodoItem(
    val id: String,
    val subject: String,
    val status: String = TODO_PENDING,
)

/**
 * The agent's task board: `filesDir/todos.json`.
 *
 * A long task outlives the model's context window, and the board is what survives it — the
 * user can see what was promised, and the model can re-read it after its own history has
 * been summarised away. Every write is the *whole* list, exactly as the model last stated
 * it: a merge of two half-remembered versions is how a board ends up lying.
 */
class TodoStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * The board is app-wide, so two conversations can write it at the same moment — and every
     * write is the *whole* list. Without the lock the later read-modify-write drops the other
     * one's edit. A plain monitor rather than a coroutine mutex keeps `read`/`write`
     * non-suspending for the callers that already have them.
     */
    private val lock = Any()

    fun read(): List<TodoItem> = synchronized(lock) {
        if (!file.exists()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<TodoItem>>(file.readText()) }.getOrDefault(emptyList())
        }
    }

    /**
     * Replaces the board with [items] and returns what was stored.
     *
     * Entries without an id get `t1`, `t2`… in list order rather than a random one: the model
     * refers to entries by id when it reports progress, and a stable, guessable id is what
     * makes "t2 完成了" mean the same thing on both sides of the conversation.
     */
    fun write(items: List<TodoItem>): List<TodoItem> = synchronized(lock) {
        val stored = items.mapIndexed { index, item ->
            item.copy(id = item.id.ifBlank { "t${index + 1}" })
        }
        file.parentFile?.mkdirs()
        // Temp file then rename, as in ChatRepository and ScheduleStore: a kill mid-write
        // would otherwise leave a truncated board, and a truncated board reads back as
        // "no tasks at all" rather than as a board that lost its last edit.
        val text = json.encodeToString(stored)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
        stored
    }
}
