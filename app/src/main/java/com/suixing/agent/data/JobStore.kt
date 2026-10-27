package com.suixing.agent.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private class Job(val info: JobInfo, @Volatile var process: Process?) {
        @Volatile
        var exitCode: Int? = null
    }

    private val jobs = LinkedHashMap<String, Job>()

    private val lock = Any()

    fun start(command: String, label: String): JobInfo {
        dir.mkdirs()
        pruneLogs()
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
        // One IO watcher on the app scope outlives the run that started the process.
        scope.launch(Dispatchers.IO) {
            try {
                job.exitCode = process.waitFor()
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                job.process = null
                synchronized(lock) {
                    var completed = jobs.values.count { it.exitCode != null }
                    val iterator = jobs.entries.iterator()
                    while (completed > KEEP_LOGS && iterator.hasNext()) {
                        if (iterator.next().value.exitCode != null) {
                            iterator.remove()
                            completed--
                        }
                    }
                }
            }
        }
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

    fun find(id: String): JobStatus? {
        // 原来写成 `list().firstOrNull { … }` —— 为了一个 job 把**每个 job** 的 256KB 日志都
        // 读一遍再拆行。这里只构造要的那一条。
        val job = synchronized(lock) { jobs[id] } ?: return null
        return JobStatus(
            info = job.info,
            status = statusOf(job),
            elapsedMs = System.currentTimeMillis() - job.info.startedAt,
            exitCode = job.exitCode,
            lastLine = lastLineOf(File(job.info.logPath)),
        )
    }

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
        val process = job.process ?: return false
        if (!process.isAlive) return false
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        return true
    }

    private fun statusOf(job: Job): String {
        val code = job.exitCode ?: return JOB_RUNNING
        return if (code == 0) JOB_DONE else JOB_FAILED
    }

    /**
     * 日志的最后一行（最多 160 字）。
     *
     * 原来走 `readJobLog(tail = 1)` —— 那个函数一次要读**末尾 256KB** 再 `lines()` 拆成整份
     * 列表，只为取最后一行。这里直接读末尾 [LAST_LINE_WINDOW] 字节、从最后一个换行处切一刀。
     * 结果与原来等价：窗口远大于一行，且最终只保留 160 字。
     */
    private fun lastLineOf(file: File): String = readJobLastLine(file).take(160)

    /**
     * Keeps `filesDir/jobs` bounded. One log per job, and a job nobody remembers starting
     * would otherwise leave its output on disk forever — nothing else ever deletes these.
     * The newest [KEEP_LOGS] files survive, and so does anything younger than
     * [LOG_MAX_AGE_MS]; everything else goes.
     */
    private fun pruneLogs() {
        val cutoff = System.currentTimeMillis() - LOG_MAX_AGE_MS
        // A job that is still in [jobs] is never pruned: its process holds the file open, so
        // deleting it leaves a job that keeps running while its output has silently become
        // unreadable — `job_output` would answer with an empty log and no hint of the loss.
        val live = synchronized(lock) { jobs.values.mapTo(mutableSetOf<String>()) { it.info.logPath } }
        dir.listFiles { file -> file.extension == "log" }
            ?.filterNot { it.absolutePath in live }
            ?.sortedByDescending { it.lastModified() }
            ?.filterIndexed { index, file -> index >= KEEP_LOGS || file.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val KEEP_LOGS = 20
        const val LOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * One `job_output` read is capped at this many bytes. A job that dumps a megabyte in one
 * go would otherwise be pulled into memory whole, and the turn reading it cannot use that
 * much anyway.
 */
private const val MAX_READ_BYTES = 256 * 1024

/**
 * Reads a job's log: everything after byte [since] when that is positive, otherwise its
 * last [tail] lines.
 *
 * Offsets are byte offsets because that is what the file itself offers, and a log keeps
 * growing while it is read: the caller passes the length it has already seen to get only
 * what is new, which is how a long job is followed without re-reading it every time.
 *
 * At most [MAX_READ_BYTES] is read per call, and the window always ends at the end of the
 * file — so the length the caller is told to pass next time still lines up with what it
 * has actually seen, even when one read could not cover the whole burst.
 */
/** 取"最后一行"时只读的字节窗口：远大于一行，又远小于 [MAX_READ_BYTES]。 */
private const val LAST_LINE_WINDOW = 8 * 1024

/** 只读日志末尾的一小段，返回最后一行（不含换行）。 */
internal fun readJobLastLine(file: File, window: Int = LAST_LINE_WINDOW): String {
    if (!file.exists()) return ""
    val length = file.length()
    if (length <= 0L) return ""
    val size = minOf(length, window.toLong()).toInt()
    val bytes = ByteArray(size)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(length - size)
        raf.readFully(bytes)
    }
    val text = String(bytes, Charsets.UTF_8)
    // 没读到文件开头时，第一行是半截，丢掉。
    val nl = 10.toChar()
    val body = if (size < length) text.substringAfter(nl, text) else text
    return body.lineSequence().lastOrNull { it.isNotEmpty() } ?: ""
}

internal fun readJobLog(file: File, tail: Int, since: Long): String {
    if (!file.exists()) return ""
    val length = file.length()
    if (since > 0L && since >= length) return ""
    val from = maxOf(since, length - MAX_READ_BYTES)
    val text = RandomAccessFile(file, "r").use { raf ->
        raf.seek(from)
        val bytes = ByteArray((length - from).toInt())
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }
    if (since > 0L) return text
    // Tail mode: the last [tail] lines of the window. Reading the file line by line into a
    // list (readLines) made `job_list` pull every job's whole log into memory to show one.
    //
    // `lines()` keeps the empty string a trailing newline produces, where `readLines()` dropped
    // it: a log that ends in a newline — which is every running job's — would tail as one real
    // line plus a blank one, and `lastLineOf` would then answer "". Drop exactly the final
    // terminator first, which is what readLines did.
    return text.removeSuffix("\n").removeSuffix("\r").lines()
        .takeLast(tail.coerceAtLeast(1)).joinToString("\n")
}
