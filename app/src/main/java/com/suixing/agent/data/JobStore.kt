package com.suixing.agent.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

const val JOB_RUNNING = "running"
const val JOB_DONE = "done"
const val JOB_FAILED = "failed"

/** What was started, and where its output is going. */
data class JobInfo(
    val id: String,
    val label: String,
    val command: String,
    val startedAt: Long,
    val logPath: String,
)

/** One row of `job_list`: the record, plus what only the live process can answer. */
data class JobStatus(
    val info: JobInfo,
    val status: String,
    val elapsedMs: Long,
    val exitCode: Int?,
    val lastLine: String,
)

/**
 * Background shell jobs — the counterpart of a synchronous `run_shell`.
 *
 * A build, a download or a long `ffmpeg` run outlives the model turn that started it, so
 * the process is deliberately *not* tied to the run: when the agent finishes its turn the
 * job keeps going, and the model reads it later with `job_output`.
 *
 * The handle lives in this process only. If the app is killed the process is orphaned and
 * forgotten — there is no way to reattach to a process we no longer own — so the jobs map
 * starts empty on every launch. The logs stay on disk under `filesDir/jobs`, which is what
 * makes a forgotten job's output still worth reading.
 */
class JobStore(private val dir: File, private val scope: CoroutineScope) {

    private class Job(val info: JobInfo, val process: Process) {
        @Volatile
        var exitCode: Int? = null
    }

    private val jobs = LinkedHashMap<String, Job>()

    private val lock = Any()

    fun start(command: String, label: String): JobInfo {
        dir.mkdirs()
        val id = UUID.randomUUID().toString().substring(0, 8)
        val log = File(dir, "$id.log")
        // stderr is merged into stdout on purpose: a job's failure is usually explained by
        // the two interleaved, and separate files would make `job_output` guess which one
        // the caller meant.
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        val info = JobInfo(
            id = id,
            label = label.ifBlank { command.take(60) },
            command = command,
            startedAt = System.currentTimeMillis(),
            logPath = log.absolutePath,
        )
        val job = Job(info, process)
        synchronized(lock) { jobs[id] = job }
        // One watcher per job, on a scope of its own hanging off the app's: it exists to
        // record the exit code, and it must outlive the run that started the job.
        val jobScope = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO,
        )
        jobScope.launch { job.exitCode = process.waitFor() }
        return info
    }

    fun list(): List<JobStatus> = synchronized(lock) { jobs.values.toList() }.map { job ->
        JobStatus(
            info = job.info,
            status = statusOf(job),
            elapsedMs = System.currentTimeMillis() - job.info.startedAt,
            exitCode = job.exitCode,
            lastLine = lastLineOf(File(job.info.logPath)),
        )
    }

    fun find(id: String): JobStatus? = list().firstOrNull { it.info.id == id }

    /**
     * The log of [id], from byte [since] when that is positive, otherwise its last [tail]
     * lines. Null when no such job was started in this process.
     */
    fun output(id: String, tail: Int, since: Long): String? {
        val job = synchronized(lock) { jobs[id] } ?: return null
        return readJobLog(File(job.info.logPath), tail, since)
    }

    /** How much of the log exists, i.e. the offset to pass as `since` next time. */
    fun logLength(id: String): Long =
        synchronized(lock) { jobs[id] }?.let { File(it.info.logPath).length() } ?: 0L

    /**
     * Stops the job: SIGTERM first so a well-behaved command can clean up, SIGKILL if it
     * ignores that, because a job the user asked to stop has to actually stop.
     *
     * False when it had already finished, or when the id is unknown.
     *
     * What gets signalled is the `sh -c` shell. A command that deliberately daemonises
     * itself into another process group can outlive this — a property of `sh -c`, not of
     * the signal.
     */
    fun kill(id: String): Boolean {
        val job = synchronized(lock) { jobs[id] } ?: return false
        if (!job.process.isAlive) return false
        job.process.destroy()
        if (!job.process.waitFor(2, TimeUnit.SECONDS)) job.process.destroyForcibly()
        return true
    }

    private fun statusOf(job: Job): String {
        val code = job.exitCode ?: return JOB_RUNNING
        return if (code == 0) JOB_DONE else JOB_FAILED
    }

    private fun lastLineOf(file: File): String =
        readJobLog(file, tail = 1, since = 0).lineSequence().lastOrNull().orEmpty().take(160)
}

/**
 * Reads a job's log: everything after byte [since] when that is positive, otherwise its
 * last [tail] lines.
 *
 * Offsets are byte offsets because that is what the file itself offers, and a log keeps
 * growing while it is read: the caller passes the length it has already seen to get only
 * what is new, which is how a long job is followed without re-reading it every time.
 */
internal fun readJobLog(file: File, tail: Int, since: Long): String {
    if (!file.exists()) return ""
    if (since > 0L) {
        val length = file.length()
        if (since >= length) return ""
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(since)
            val bytes = ByteArray((length - since).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }
    return file.readLines().takeLast(tail.coerceAtLeast(1)).joinToString("\n")
}
