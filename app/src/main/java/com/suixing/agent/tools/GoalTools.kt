package com.suixing.agent.tools

import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.GoalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal val GOAL_TOOL_NAMES = setOf("goal", "goal_read", "goal_resume", "goal_update")

/**
 * Goal lifecycle tools. The user starts or resumes work and the owning run records outcomes.
 */
class GoalTools(private val goals: GoalStore) : ToolGroup() {
    override val group: String = "目标"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "goal",
            "Start or replace an objective in this conversation. User command only.",
            Schema.objectOf(
                properties = listOf(
                    "text" to Schema.string("The goal. Empty clears the current goal."),
                ),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "goal_read",
            "Read the objective, progress and execution status for this conversation.",
            Schema.objectOf(properties = emptyList()),
        ),
        ToolSpec("goal_resume", "Resume a paused or blocked goal. User command only.",
            Schema.objectOf(properties = emptyList())),
        ToolSpec(
            "goal_update",
            "Report concrete progress on the active goal, or mark it completed with evidence or blocked with a reason.",
            Schema.objectOf(
                properties = listOf(
                    "status" to Schema.string("", listOf("progress", "completed", "blocked")),
                    "note" to Schema.string("Result achieved, verified completion, or what is blocking the work."),
                    "wait_seconds" to Schema.integer("Optional delay before continuing, for a pending result.", minimum = 0, maximum = 60),
                ),
                required = listOf("status", "note"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "goal" -> write(args, chatId = null)
        "goal_read" -> read(chatId = null)
        else -> ToolResult.failure("Unknown tool: $name")
    }

    internal suspend fun executeForRun(
        name: String,
        args: JsonObject,
        chatId: String,
        activeId: String,
        userInvoked: Boolean,
        onActivated: (String) -> Unit,
    ): ToolResult = when (name) {
        "goal_read" -> read(chatId)
        "goal" -> if (userInvoked) write(args, chatId, onActivated) else ToolResult.failure("Use goal_update for the user's active goal.")
        "goal_resume" -> if (userInvoked) withContext(Dispatchers.IO) {
            goals.resume(chatId)?.let {
                onActivated(it.id)
                ToolResult.success("Goal resumed:\n${it.text}" +
                    it.progress.takeIf(String::isNotBlank)?.let { note -> "\nSaved progress:\n$note" }.orEmpty())
            }
                ?: ToolResult.failure("这个会话没有可继续的目标")
        } else ToolResult.failure("Only the user can resume a goal.")
        "goal_update" -> update(args, activeId)
        else -> ToolResult.failure("Unknown tool: $name")
    }

    private suspend fun write(
        args: JsonObject,
        chatId: String?,
        onActivated: (String) -> Unit = {},
    ): ToolResult = withContext(Dispatchers.IO) {
        val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: return@withContext ToolResult.failure("text must be a string.")
        val stored = when {
            chatId == null -> goals.write(text)
            text.isEmpty() -> goals.clearFor(chatId)
            else -> goals.start(chatId, text)
        } ?: return@withContext ToolResult.failure("另一个会话拥有当前目标，请先在那条会话中停止或清除。")
        if (stored.isSet) {
            if (stored.id.isNotEmpty()) onActivated(stored.id)
            ToolResult.success("Goal set:\n${stored.text}")
        } else ToolResult.success("Goal cleared.")
    }

    private suspend fun read(chatId: String?): ToolResult = withContext(Dispatchers.IO) {
        val goal = goals.read()
        if (goal.isSet && (chatId == null || goal.visibleIn(chatId))) {
            ToolResult.success(listOfNotNull("Current goal: ${goal.text}", "Status: ${goal.status.name.lowercase()}",
                goal.progress.takeIf { it.isNotBlank() }, goal.detail.takeIf { it.isNotBlank() }).joinToString("\n"))
        } else ToolResult.success("No goal is set.")
    }

    private suspend fun update(args: JsonObject, activeId: String): ToolResult = withContext(Dispatchers.IO) {
        val status = when (args.str("status")) {
            "progress" -> GoalStatus.ACTIVE
            "completed" -> GoalStatus.COMPLETED
            "blocked" -> GoalStatus.BLOCKED
            else -> return@withContext ToolResult.failure("status must be progress, completed or blocked.")
        }
        val note = (args["note"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (note.isNullOrEmpty()) return@withContext ToolResult.failure("A progress, evidence, or blocker note is required.")
        val wait = args["wait_seconds"]?.let {
            (it as? JsonPrimitive)?.takeUnless { number -> number.isString }?.intOrNull
                ?: return@withContext ToolResult.failure("wait_seconds must be an integer.")
        } ?: 0
        if (wait !in 0..60 || (wait > 0 && status != GoalStatus.ACTIVE)) {
            return@withContext ToolResult.failure("wait_seconds must be 0–60, and only progress can request a delay.")
        }
        val wakeAt = if (wait > 0) System.currentTimeMillis() + wait * 1000L else 0L
        val written = goals.report(activeId, status, note.take(2_000), wakeAt)
            ?: return@withContext ToolResult.failure("No active goal belongs to this run.")
        ToolResult.success("Goal ${written.status.name.lowercase()}: ${written.progress}")
    }
}
