package com.suixing.agent.tools

import com.suixing.agent.data.GoalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The persistent objective, for work that spans more than one run.
 *
 * It is intentionally not another todo list: a goal says what the work is for, while
 * todo_write says what to do next. One sentence is enough, and replacing it is the only
 * write — a merge of two goals would be a third goal nobody asked for.
 */
class GoalTools(private val goals: GoalStore) : ToolGroup() {
    override val group: String = "目标"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "goal",
            "Set or replace the persistent goal for this app. Write one sentence that says what " +
                "the ongoing work is for; pass an empty string to clear it. Read it with goal_read " +
                "when resuming work whose objective is not in the current conversation.",
            Schema.objectOf(
                properties = listOf(
                    "text" to Schema.string("The goal. Empty clears the current goal."),
                ),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "goal_read",
            "Read the persistent goal. Use it when starting or resuming work whose objective " +
                "is not obvious from the current conversation.",
            Schema.objectOf(properties = emptyList()),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "goal" -> write(args)
        "goal_read" -> read()
        else -> ToolResult.failure("Unknown tool: $name")
    }

    private suspend fun write(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val text = args.str("text")?.trim().orEmpty()
        val stored = goals.write(text)
        if (stored.isSet) ToolResult.success("Goal set:\n${stored.text}")
        else ToolResult.success("Goal cleared.")
    }

    private suspend fun read(): ToolResult = withContext(Dispatchers.IO) {
        val goal = goals.read()
        if (goal.isSet) ToolResult.success("Current goal:\n${goal.text}")
        else ToolResult.success("No goal is set.")
    }
}