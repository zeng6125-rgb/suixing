package com.suixing.agent.agent

import com.suixing.agent.tools.Schema
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import com.suixing.agent.tools.int
import com.suixing.agent.tools.str
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val SUBAGENT_SPECS = listOf(
    ToolSpec("spawn_agent", "Start an independent subagent for a self-contained task. It sees only your task/context, not this conversation. Use for substantial independent work; collect its result with wait_agents. Child tools obey your permissions. No nested delegation.",
        Schema.objectOf(listOf("task" to Schema.string("Complete task and expected result"),
            "context" to Schema.string("Only relevant facts, paths and constraints the child needs"),
            "name" to Schema.string("Short task label"),
            "timeout_seconds" to Schema.integer("Task deadline", default = 180, minimum = 10, maximum = 600)), listOf("task"))),
    ToolSpec("wait_agents", "Wait for subagent results, in the requested order. A wait timeout does not cancel tasks. Prefer waiting over repeated list calls. Inspect/verify results before reporting completion.",
        Schema.objectOf(listOf("ids" to Schema.arrayOf("Subagent IDs; omit to wait for all", Schema.string("ID")),
            "timeout_seconds" to Schema.integer("Maximum time to wait", default = 30, minimum = 1, maximum = 120)))),
    ToolSpec("list_agents", "List this run's subagents and their current status/results.", Schema.objectOf(emptyList())),
    ToolSpec("cancel_agent", "Cancel one queued or running subagent. Completed results remain available.",
        Schema.objectOf(listOf("id" to Schema.string("Subagent ID")), listOf("id"))),
)
internal val SUBAGENT_TOOL_NAMES = SUBAGENT_SPECS.mapTo(HashSet()) { it.name }

internal data class SubagentTask(val id: String, val name: String, val task: String, val context: String, val timeoutMs: Long)
internal enum class SubagentStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
internal data class SubagentSnapshot(val id: String, val name: String, val status: SubagentStatus, val result: String = "")

/** A run-owned bounded pool. It owns no Activity, full parent transcript, or persistent chat. */
internal class SubagentPool(
    parent: CoroutineScope,
    parallelism: Int = 2,
    private val maximumTasks: Int = 6,
    private val onChanged: (List<SubagentSnapshot>) -> Unit = {},
    private val runner: suspend (SubagentTask) -> String,
) {
    private class Entry(val task: SubagentTask) {
        @Volatile var snapshot = SubagentSnapshot(task.id, task.name, SubagentStatus.QUEUED)
        var cancelRequested = false
        var job: Job? = null
    }
    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + supervisor)
    private val slots = Semaphore(parallelism.coerceIn(1, 3))
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false

    fun spawn(task: String, context: String = "", name: String = "", timeoutMs: Long = 180_000): SubagentSnapshot {
        require(task.isNotBlank()) { "子任务不能为空" }
        require(task.length <= 8_000 && context.length <= 64_000) { "子任务说明过长，请只传递相关上下文" }
        val entry = synchronized(lock) {
            check(!closed && supervisor.isActive) { "父任务已结束" }
            check(entries.size < maximumTasks) { "本轮最多创建 $maximumTasks 个子任务" }
            val id = "agent_${UUID.randomUUID().toString().take(12)}"
            Entry(SubagentTask(id, name.ifBlank { task.lineSequence().first { it.isNotBlank() }.trim().take(32) }.take(64), task, context,
                timeoutMs.coerceIn(1L, 600_000L))).also { entries[id] = it }
        }
        changed()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit {
                    update(entry, SubagentStatus.RUNNING)
                    val result = withTimeout(entry.task.timeoutMs) { runner(entry.task) }
                    val kept = if (result.length <= 64_000) result else result.take(64_000) + "\n[子任务结果超过64,000字符，后续内容未保留]"
                    update(entry, SubagentStatus.COMPLETED, kept)
                }
            } catch (timeout: TimeoutCancellationException) {
                update(entry, SubagentStatus.FAILED, "子任务超时")
            } catch (cancelled: CancellationException) {
                update(entry, SubagentStatus.CANCELLED, "子任务已取消")
                throw cancelled
            } catch (failure: Exception) {
                update(entry, SubagentStatus.FAILED, (failure.message ?: failure.javaClass.simpleName).take(2_000))
            }
        }
        job.invokeOnCompletion {
            if (job.isCancelled && entry.snapshot.status in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING)) {
                update(entry, SubagentStatus.CANCELLED, "子任务已取消")
            }
        }
        synchronized(lock) {
            entry.job = job
            if (closed || entry.cancelRequested) job.cancel() else job.start()
        }
        return entry.snapshot
    }

    fun snapshots(): List<SubagentSnapshot> = synchronized(lock) { entries.values.map { it.snapshot } }
    private fun changed() = synchronized(lock) { onChanged(entries.values.map { it.snapshot }) }
    private fun update(entry: Entry, status: SubagentStatus, result: String = "") {
        synchronized(lock) {
            if (entry.cancelRequested && status != SubagentStatus.CANCELLED) return
            entry.snapshot = entry.snapshot.copy(status = status, result = result)
        }
        changed()
    }

    suspend fun waitFor(ids: List<String>? = null, timeoutMs: Long = 30_000): List<SubagentSnapshot> {
        val selected = synchronized(lock) {
            val requested = ids ?: entries.keys.toList()
            requested.map { id -> entries[id] ?: error("本轮没有子任务 $id") }
        }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { selected.mapNotNull { it.job }.joinAll() }
        return selected.map { it.snapshot }
    }

    fun cancel(id: String): Boolean {
        val job = synchronized(lock) {
            val entry = entries[id] ?: return false
            if (entry.snapshot.status !in setOf(SubagentStatus.QUEUED, SubagentStatus.RUNNING)) return true
            entry.cancelRequested = true
            entry.snapshot = entry.snapshot.copy(status = SubagentStatus.CANCELLED, result = "子任务已取消")
            entry.job
        }
        job?.cancel()
        changed()
        return true
    }

    suspend fun close() {
        synchronized(lock) { closed = true }
        supervisor.cancel()
        supervisor.join()
    }

    suspend fun execute(name: String, args: JsonObject): ToolResult {
        return try {
            val values = when (name) {
                "spawn_agent" -> listOf(spawn(args.str("task").orEmpty(), args.str("context").orEmpty(),
                    args.str("name").orEmpty(), args.int("timeout_seconds", 180).coerceIn(10, 600) * 1000L))
                "wait_agents" -> {
                    val ids = args["ids"]?.let { value ->
                        (value as? JsonArray ?: error("ids 必须是子任务ID数组"))
                            .map { it.jsonPrimitive.contentOrNull ?: error("无效子任务ID") }
                    }
                    waitFor(ids, args.int("timeout_seconds", 30).coerceIn(1, 120) * 1000L)
                }
                "list_agents" -> snapshots()
                "cancel_agent" -> {
                    val id = args.str("id") ?: error("缺少子任务ID")
                    check(cancel(id)) { "本轮没有子任务 $id" }
                    snapshots().filter { it.id == id }
                }
                else -> error("未知子任务工具")
            }
            ToolResult.success(JsonArray(values.map { snapshot -> buildJsonObject {
                put("id", snapshot.id); put("name", snapshot.name)
                put("status", snapshot.status.name.lowercase())
                if (snapshot.result.isNotBlank()) put("result", snapshot.result)
            } }).toString())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolResult.failure(failure.message ?: "子任务操作失败") }
    }
}
