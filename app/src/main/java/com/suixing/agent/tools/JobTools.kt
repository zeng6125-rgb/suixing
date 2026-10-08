package com.suixing.agent.tools

import com.suixing.agent.data.JOB_RUNNING
import com.suixing.agent.data.JobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Background jobs: start a long command, come back to it later.
 *
 * `run_shell` waits for the command and hands back its output, which is right for a question
 * and wrong for a download. These four tools are the other half: the process is detached from
 * the turn that started it, and the model polls it instead of blocking on it.
 *
 * The tier is deliberately [TIER_NONE]. The process is started by the app itself through
 * `ProcessBuilder`, so it needs neither Shizuku nor root, and hanging these on the shell tier
 * would refuse them on a device where `run_shell` itself is unavailable. Approval is
 * unaffected: the engine puts every tool call through the same gate, so `job_start` is asked
 * about exactly like `run_shell`.
 */
class JobTools(private val jobs: JobStore) : ToolGroup() {

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "job_start",
            "在后台启动一条长时间运行的命令，立刻返回 job_id，不等待它结束。适合下载、编译、录屏、" +
                "长时间扫描等；短命令请用 run_shell。任务不会随本轮对话结束而停止。",
            Schema.objectOf(
                properties = listOf(
                    "command" to Schema.string("要执行的命令，如 `curl -o /sdcard/a.zip https://…`"),
                    "label" to Schema.string("给这个任务起的短名字，便于在 job_list 里认出来"),
                ),
                required = listOf("command"),
            ),
        ),
        ToolSpec(
            "job_list",
            "列出本应用启动过的后台任务：id、名字、状态（running/done/failed）、已运行时间和日志末行。",
            Schema.objectOf(properties = emptyList()),
        ),
        ToolSpec(
            "job_output",
            "读取后台任务的输出。默认给最后 50 行；给了 since（字节偏移）就只给那之后的新内容，" +
                "适合反复轮询一个还在跑的任务。",
            Schema.objectOf(
                properties = listOf(
                    "job_id" to Schema.string("job_start 返回的 id"),
                    "tail" to Schema.integer("要读的最后多少行，默认 50", default = 50, minimum = 1, maximum = 2000),
                    "since" to Schema.integer(
                        "只读这个字节偏移之后的内容；留空读末尾。上次返回里会给出新的偏移。",
                        minimum = 0,
                    ),
                ),
                required = listOf("job_id"),
            ),
        ),
        ToolSpec(
            "job_kill",
            "停止一个后台任务。先发 SIGTERM，2 秒后仍在跑就 SIGKILL。",
            Schema.objectOf(
                properties = listOf("job_id" to Schema.string("job_start 返回的 id")),
                required = listOf("job_id"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "job_start" -> start(args)
        "job_list" -> list()
        "job_output" -> output(args)
        "job_kill" -> kill(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun start(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args.str("command")?.trim().orEmpty()
        if (command.isEmpty()) return@withContext ToolResult.failure("缺少参数 command")
        val label = args.str("label")?.trim().orEmpty()
        val info = jobs.start(command, label)
        ToolResult.success(
            "已启动后台任务 ${info.id}（${info.label}）。\n" +
                "它不随本轮对话结束而停止：用 job_output 读输出，job_kill 停止，job_list 看全部。",
        )
    }

    private suspend fun list(): ToolResult = withContext(Dispatchers.IO) {
        val all = jobs.list()
        if (all.isEmpty()) return@withContext ToolResult.success("没有后台任务。")
        val text = all.joinToString("\n") { job ->
            val exit = job.exitCode?.let { " exit=$it" }.orEmpty()
            val tail = if (job.lastLine.isBlank()) "" else " | 末行: ${job.lastLine}"
            "- ${job.info.id} [${job.status}] ${seconds(job.elapsedMs)}$exit  ${job.info.label}$tail"
        }
        ToolResult.success("共 ${all.size} 个后台任务：\n$text")
    }

    private suspend fun output(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("job_id")?.trim().orEmpty()
        if (id.isEmpty()) return@withContext ToolResult.failure("缺少参数 job_id")
        val tail = args.int("tail", 50).coerceIn(1, 2000)
        val since = args.int("since", 0).coerceAtLeast(0).toLong()
        val text = jobs.output(id, tail, since) ?: return@withContext unknown(id)
        val status = jobs.find(id)
        val head = if (status == null) {
            ""
        } else {
            val exit = status.exitCode?.let { " exit=$it" }.orEmpty()
            "[${status.status}] ${seconds(status.elapsedMs)}$exit\n"
        }
        if (text.isBlank()) {
            return@withContext ToolResult.success("$head（还没有输出；下次可用 since=${jobs.logLength(id)} 继续读）")
        }
        val next = jobs.logLength(id)
        ToolResult.success("$head$text\n\n（日志共 $next 字节，下次可用 since=$next 只取新增部分）")
    }

    private suspend fun kill(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("job_id")?.trim().orEmpty()
        if (id.isEmpty()) return@withContext ToolResult.failure("缺少参数 job_id")
        val status = jobs.find(id) ?: return@withContext unknown(id)
        if (status.status != JOB_RUNNING) {
            return@withContext ToolResult.success("任务 $id 已经结束（${status.status}），不需要停止。")
        }
        if (!jobs.kill(id)) return@withContext ToolResult.failure("停止 $id 失败，它可能刚刚自己结束了。")
        ToolResult.success("已停止后台任务 $id。")
    }

    /** A job that this process never started: the handle died with the previous launch. */
    private fun unknown(id: String): ToolResult = ToolResult.failure(
        "没有这个后台任务：$id。应用重启后旧任务的进程句柄会丢失（日志仍在 filesDir/jobs 下）。" +
            "可以用 job_list 看当前还有哪些任务。",
    )

    private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
}
