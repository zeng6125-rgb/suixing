package com.suixing.agent.data

import com.suixing.agent.core.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The persistent objective the agent should keep in view across runs. */
@Serializable
data class Goal(
    val text: String = "",
    val updatedAt: Long = 0L,
) {
    val isSet: Boolean get() = text.isNotBlank()
}

/**
 * One goal for the whole app, in `filesDir/goal.json`.
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

    /** Latest written text, for the composer chip; null until the first write this process. */
    private val _changes = MutableStateFlow<String?>(null)
    val changes: StateFlow<String?> = _changes.asStateFlow()

    fun read(): Goal = cached ?: synchronized(lock) {
        cached ?: load().also { cached = it }
    }

    /** Empty text clears the goal; the caller gets the exact stored value. */
    fun write(text: String): Goal = synchronized(lock) {
        val goal = Goal(text = text.trim(), updatedAt = System.currentTimeMillis())
        file.parentFile?.mkdirs()
        AtomicFiles.writeText(file, json.encodeToString(goal))
        cached = goal
        _changes.value = goal.text
        goal
    }

    fun clear(): Goal = write("")

    private fun load(): Goal {
        if (!file.exists()) return Goal()
        return runCatching { json.decodeFromString<Goal>(file.readText()) }.getOrElse {
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