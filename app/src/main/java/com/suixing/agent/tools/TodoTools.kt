package com.suixing.agent.tools

import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TODO_PENDING
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.TodoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The task board, for work that takes more than a couple of steps.
 *
 * It exists because the agent's own history is not a reliable place to keep a plan: the
 * context gets compressed when it grows, and a compressed plan is a plan the model has to
 * guess at. The board is written to disk instead, so it survives both the summariser and
 * the app being closed — and the user can read it.
 */
class TodoTools(private val todos: TodoStore) : ToolGroup() {

    override val group: String = "任务清单"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "todo_write",
            "Write the whole task list, replacing the old one (not appending). Write it once at the " +
                "start of a multi-step task, then rewrite it after every step and set that row to " +
                "completed. The list is persisted, so it is still readable after the context is " +
                "compressed or the app is restarted.",
            Schema.objectOf(
                properties = listOf(
                    "items" to Schema.arrayOf(
                        "The complete list, in execution order",
                        Schema.objectOf(
                            properties = listOf(
                                "subject" to Schema.string("What this step does, one sentence"),
                                "status" to Schema.string(
                                    "Status: pending / in_progress / completed",
                                    enumValues = listOf(TODO_PENDING, TODO_IN_PROGRESS, TODO_COMPLETED),
                                    default = TODO_PENDING,
                                ),
                                "id" to Schema.string("Optional; when empty, rows are numbered t1, t2… in order"),
                            ),
                            required = listOf("subject"),
                        ),
                    ),
                ),
                required = listOf("items"),
            ),
        ),
        ToolSpec(
            "todo_read",
            "Read the current task list. Read it once when picking up older work or when unsure " +
                "what is still left.",
            Schema.objectOf(properties = emptyList()),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "todo_write" -> write(args)
        "todo_read" -> read()
        else -> ToolResult.failure("Unknown tool: $name")
    }

    private suspend fun write(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = args["items"] as? JsonArray
            ?: return@withContext ToolResult.failure(
                "Missing argument items (pass the whole list; an empty array clears it)",
            )
        val items = mutableListOf<TodoItem>()
        for ((index, element) in raw.withIndex()) {
            val row = runCatching { element.jsonObject }.getOrNull()
                ?: return@withContext ToolResult.failure("items[$index] is not an object; expected {subject, status}")
            val subject = row.str("subject")?.trim().orEmpty()
            if (subject.isEmpty()) return@withContext ToolResult.failure("items[$index] is missing subject")
            val status = row.str("status")?.trim()?.lowercase().orEmpty().ifBlank { TODO_PENDING }
            if (status != TODO_PENDING && status != TODO_IN_PROGRESS && status != TODO_COMPLETED) {
                return@withContext ToolResult.failure(
                    "items[$index] status must be one of $TODO_PENDING / $TODO_IN_PROGRESS / " +
                        "$TODO_COMPLETED, got \"$status\"",
                )
            }
            items += TodoItem(
                id = row.str("id")?.trim().orEmpty(),
                subject = subject,
                status = status,
            )
        }
        val stored = todos.write(items)
        ToolResult.success("Task list written (${stored.size} rows):\n" + render(stored))
    }

    private suspend fun read(): ToolResult = withContext(Dispatchers.IO) {
        val items = todos.read()
        if (items.isEmpty()) return@withContext ToolResult.success("The task list is empty.")
        ToolResult.success("Current task list (${items.size} rows):\n" + render(items))
    }

    private fun render(items: List<TodoItem>): String = items.joinToString("\n") { item ->
        "- [${item.status}] ${item.id} ${item.subject}"
    }
}
