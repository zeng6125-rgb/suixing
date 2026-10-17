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

    override val group: String = "后台任务"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "job_start",
            "Start a long-running command in the background and return its job_id at once, without " +
                "waiting for it to finish. Use it for downloads, builds, screen recordings and long " +
                "scans; use run_shell for short commands. The job keeps running after this turn ends.",
            Schema.objectOf(
                properties = listOf(
                    "command" to Schema.string("Command to run, e.g. `curl -o /sdcard/a.zip https://…`"),
                    "label" to Schema.string("Short name for this job, so it is recognisable in job_list"),
                ),
                required = listOf("command"),
            ),
        ),
        ToolSpec(
            "job_list",
            "List the background jobs this app started: id, name, status (running/done/failed), " +
                "elapsed time and the last line of the log.",
            Schema.objectOf(properties = emptyList()),
        ),
        ToolSpec(
            "job_output",
            "Read a background job's output: the last 50 lines by default, or only what is new when " +
                "you pass since (a byte offset) — that is how to poll a job that is still running.",
            Schema.objectOf(
                properties = listOf(
                    "job_id" to Schema.string("The id job_start returned"),
                    "tail" to Schema.integer(
                        "How many trailing lines to read, default 50",
                        default = 50,
                        minimum = 1,
                        maximum = 2000,
                    ),
                    "since" to Schema.integer(
                        "Read only what comes after this byte offset; omit to read the tail. " +
                            "The reply reports the new offset.",
                        minimum = 0,
                    ),
                ),
                required = listOf("job_id"),
            ),
        ),
        ToolSpec(
            "job_kill",
            "Stop a background job: SIGTERM first, SIGKILL if it is still running two seconds later.",
            Schema.objectOf(
                properties = listOf("job_id" to Schema.string("The id job_start returned")),
                required = listOf("job_id"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "job_start" -> start(args)
        "job_list" -> list()
        "job_output" -> output(args)
        "job_kill" -> kill(args)
        else -> ToolResult.failure("Unknown tool: $name")
    }

    private suspend fun start(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args.str("command")?.trim().orEmpty()
        if (command.isEmpty()) return@withContext ToolResult.failure("Missing argument: command")
        val label = args.str("label")?.trim().orEmpty()
        val info = jobs.start(command, label)
        ToolResult.success(
            "Started background job ${info.id} (${info.label}).\n" +
                "It keeps running after this turn ends: read output with job_output, stop it with " +
                "job_kill, list everything with job_list.",
        )
    }

    private suspend fun list(): ToolResult = withContext(Dispatchers.IO) {
        val all = jobs.list()
        if (all.isEmpty()) return@withContext ToolResult.success("No background jobs.")
        val text = all.joinToString("\n") { job ->
            val exit = job.exitCode?.let { " exit=$it" }.orEmpty()
            val tail = if (job.lastLine.isBlank()) "" else " | last: ${job.lastLine}"
            "- ${job.info.id} [${job.status}] ${seconds(job.elapsedMs)}$exit  ${job.info.label}$tail"
        }
        ToolResult.success("${all.size} background job(s):\n$text")
    }

    private suspend fun output(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("job_id")?.trim().orEmpty()
        if (id.isEmpty()) return@withContext ToolResult.failure("Missing argument: job_id")
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
            return@withContext ToolResult.success(
                "$head(no output yet; continue from since=${jobs.logLength(id)} next time)",
            )
        }
        val next = jobs.logLength(id)
        ToolResult.success(
            "$head$text\n\n(log is $next bytes; pass since=$next next time to get only what is new)",
        )
    }

    private suspend fun kill(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val id = args.str("job_id")?.trim().orEmpty()
        if (id.isEmpty()) return@withContext ToolResult.failure("Missing argument: job_id")
        val status = jobs.find(id) ?: return@withContext unknown(id)
        if (status.status != JOB_RUNNING) {
            return@withContext ToolResult.success("Job $id already finished (${status.status}); nothing to stop.")
        }
        if (!jobs.kill(id)) {
            return@withContext ToolResult.failure("Failed to stop $id; it may have just finished on its own.")
        }
        ToolResult.success("Stopped background job $id.")
    }

    /** A job that this process never started: the handle died with the previous launch. */
    private fun unknown(id: String): ToolResult = ToolResult.failure(
        "No such background job: $id. Process handles for older jobs are lost after the app " +
            "restarts (the logs are still under filesDir/jobs). job_list shows what is running now.",
    )

    private fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
}
