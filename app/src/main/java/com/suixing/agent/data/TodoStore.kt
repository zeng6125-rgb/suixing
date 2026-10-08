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

    fun read(): List<TodoItem> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<TodoItem>>(file.readText()) }.getOrDefault(emptyList())
    }

    /**
     * Replaces the board with [items] and returns what was stored.
     *
     * Entries without an id get `t1`, `t2`… in list order rather than a random one: the model
     * refers to entries by id when it reports progress, and a stable, guessable id is what
     * makes "t2 完成了" mean the same thing on both sides of the conversation.
     */
    fun write(items: List<TodoItem>): List<TodoItem> {
        val stored = items.mapIndexed { index, item ->
            item.copy(id = item.id.ifBlank { "t${index + 1}" })
        }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(stored))
        return stored
    }
}
