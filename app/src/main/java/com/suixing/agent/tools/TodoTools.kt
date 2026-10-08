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

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "todo_write",
            "写下整个任务清单，覆盖旧清单（不是追加）。多步任务开始时先写一遍，每完成一步就重写一遍" +
                "并把该条改成 completed。清单会持久保存，压缩上下文或重开应用后仍能读到。",
            Schema.objectOf(
                properties = listOf(
                    "items" to Schema.arrayOf(
                        "完整清单，按执行顺序排列",
                        Schema.objectOf(
                            properties = listOf(
                                "subject" to Schema.string("这一步要做什么，一句话"),
                                "status" to Schema.string(
                                    "状态：pending（待办）/ in_progress（进行中）/ completed（已完成）",
                                    enumValues = listOf(TODO_PENDING, TODO_IN_PROGRESS, TODO_COMPLETED),
                                    default = TODO_PENDING,
                                ),
                                "id" to Schema.string("可留空；留空时按顺序自动编号为 t1、t2…"),
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
            "读取当前任务清单。接手一件旧事、或不确定还剩哪些没做时先读一次。",
            Schema.objectOf(properties = emptyList()),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "todo_write" -> write(args)
        "todo_read" -> read()
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun write(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = args["items"] as? JsonArray
            ?: return@withContext ToolResult.failure("缺少参数 items（要传完整清单，可以是空数组来清空）")
        val items = mutableListOf<TodoItem>()
        for ((index, element) in raw.withIndex()) {
            val row = runCatching { element.jsonObject }.getOrNull()
                ?: return@withContext ToolResult.failure("items[$index] 不是对象，应为 {subject, status}")
            val subject = row.str("subject")?.trim().orEmpty()
            if (subject.isEmpty()) return@withContext ToolResult.failure("items[$index] 缺少 subject")
            val status = row.str("status")?.trim()?.lowercase().orEmpty().ifBlank { TODO_PENDING }
            if (status != TODO_PENDING && status != TODO_IN_PROGRESS && status != TODO_COMPLETED) {
                return@withContext ToolResult.failure(
                    "items[$index] 的 status 只能是 $TODO_PENDING / $TODO_IN_PROGRESS / $TODO_COMPLETED，收到的是「$status」",
                )
            }
            items += TodoItem(
                id = row.str("id")?.trim().orEmpty(),
                subject = subject,
                status = status,
            )
        }
        val stored = todos.write(items)
        ToolResult.success("已写入任务清单（${stored.size} 条）：\n" + render(stored))
    }

    private suspend fun read(): ToolResult = withContext(Dispatchers.IO) {
        val items = todos.read()
        if (items.isEmpty()) return@withContext ToolResult.success("任务清单是空的。")
        ToolResult.success("当前任务清单（${items.size} 条）：\n" + render(items))
    }

    private fun render(items: List<TodoItem>): String = items.joinToString("\n") { item ->
        "- [${item.status}] ${item.id} ${item.subject}"
    }
}
