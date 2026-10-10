package com.suixing.agent.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

data class ShellResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
) {
    fun render(limit: Int = 8000): String {
        val builder = StringBuilder()
        if (stdout.isNotBlank()) builder.append(stdout.trim())
        if (stderr.isNotBlank()) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append("[stderr] ").append(stderr.trim())
        }
        if (builder.isEmpty()) builder.append(if (ok) "(无输出)" else "命令失败，退出码 $exitCode")
        val text = builder.toString()
        // The length goes in the marker so a cut answer cannot be read as a short one. It is
        // deliberately "本条输出" and not "完整输出": the number here is what the collector kept
        // (at most MAX_OUTPUT_CHARS), not the length of the command's raw output, so calling it
        // the complete output would be a lie. Past 32K the true total is stated by the cap's own
        // tail marker instead — do not "fix" this wording back to 完整输出.
        return if (text.length > limit) {
            text.take(limit) + "\n…（输出过长已截断：仅显示前 $limit 字，本条输出共 ${text.length} 字符）"
        } else {
            text
        }
    }
}

/**
 * Three ways to reach a shell, in ascending order of privilege:
 *  - [local]   the app's own uid, no extra capability
 *  - [shizuku] the shell uid (2000) through the Shizuku service
 *  - [root]    uid 0 through `su`
 */
object Shell {
    private const val DEFAULT_TIMEOUT_MS = 45_000L
    private const val ROOT_PROBE_TIMEOUT_MS = 8_000L

    /** How often a wait re-checks: short enough that a stop is noticed in a fraction of a second. */
    private const val POLL_MS = 200L

    /**
     * How much of a command's output is kept. `yes`, a full `logcat` or a broad `dumpsys` all
     * produce megabytes, and a tool that can be killed by its own output is worse than one that
     * admits it truncated.
     */
    private const val MAX_OUTPUT_CHARS = 32 * 1024

    /**
     * Shizuku 13.x 把 `Shizuku.newProcess` 收成了 private（公开 API 只剩 binder 与 user service），
     * 但它仍是唯一能直接以 shell 身份起进程的入口，所以按签名反射取用；签名不对就返回 null，
     * 调用方会给出明确报错而不是静默降级。
     */
    private val newProcess: Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        }.getOrNull()
    }

    suspend fun local(command: String): ShellResult = run(arrayOf("sh", "-c", command))

    suspend fun shizuku(command: String): ShellResult {
        val method = newProcess
            ?: return ShellResult(false, "", "当前 Shizuku 版本不提供 newProcess（需要 13.x）", -1)
        return runCatching {
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            collect(process)
        }.getOrElse { error ->
            // A stop has to travel as a stop: without this, runCatching would turn the
            // cancellation into an ordinary "command failed" result and the run would carry on.
            if (error is CancellationException) throw error
            val cause = error.cause ?: error
            ShellResult(false, "", "Shizuku 不可用：${cause.message ?: cause.javaClass.simpleName}", -1)
        }
    }

    suspend fun root(command: String): ShellResult = runCatching {
        collect(ProcessBuilder("su", "-c", command).start())
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        ShellResult(false, "", "无法获取 root：${error.message ?: error.javaClass.simpleName}", -1)
    }

    suspend fun rootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").start()
        // Polled for the same reason as [collect]: `su` can sit on a prompt, and a stop during
        // the probe should not have to wait out the whole window.
        var finished = false
        val deadline = System.currentTimeMillis() + ROOT_PROBE_TIMEOUT_MS
        while (true) {
            if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                finished = true
                break
            }
            if (!currentCoroutineContext().isActive) {
                process.destroy()
                throw CancellationException("已停止")
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        if (!finished) {
            process.destroy()
            false
        } else {
            val text = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && text.contains("uid=0")
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        false
    }

    private suspend fun run(command: Array<String>): ShellResult = runCatching {
        collect(ProcessBuilder(*command).start())
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        ShellResult(false, "", error.message ?: error.javaClass.simpleName, -1)
    }

    private suspend fun collect(process: Process): ShellResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var outChars = 0
        var errChars = 0
        val outThread = Thread { outChars = readInto(process.inputStream.bufferedReader(), stdout) }
            .apply { isDaemon = true }
        val errThread = Thread { errChars = readInto(process.errorStream.bufferedReader(), stderr) }
            .apply { isDaemon = true }
        outThread.start()
        errThread.start()
        // Polled instead of waited out in one go: a command that is still running when the user
        // taps stop has to die there and then. `waitFor(45s)` would keep it — and its side
        // effects — alive for the rest of the window, which is exactly what a stop is not.
        val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
        var finished = false
        while (true) {
            if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                finished = true
                break
            }
            if (!currentCoroutineContext().isActive) {
                // Plain destroy, like the timeout below: SIGTERM is enough for the commands
                // this runs. It reaches the process we started, not its children, so a
                // pipeline's other end can outlive it — SIGKILL would have the same limit.
                process.destroy()
                throw CancellationException("已停止")
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        if (!finished) {
            process.destroy()
            outThread.join(500)
            errThread.join(500)
            return ShellResult(
                false,
                capped(stdout, outChars),
                "命令超时（${DEFAULT_TIMEOUT_MS / 1000}s）已终止",
                -1,
            )
        }
        outThread.join(1500)
        errThread.join(1500)
        return ShellResult(
            process.exitValue() == 0,
            capped(stdout, outChars),
            capped(stderr, errChars),
            process.exitValue(),
        )
    }

    /**
     * Says how much was dropped, so a truncated answer is never read as the whole one.
     * [total] counts every character the command produced, not just the kept ones.
     */
    private fun capped(target: StringBuilder, total: Int): String =
        if (total <= target.length) {
            target.toString()
        } else {
            target.toString() + "\n…（输出过长已截断：只保留前 $MAX_OUTPUT_CHARS 字符，完整输出共约 $total 字节）"
        }

    /**
     * Drains [reader] into [target], keeping at most [MAX_OUTPUT_CHARS] characters, and returns
     * how many the command produced in total.
     *
     * The stream is still read to the end after the cap: abandoning it would leave the process
     * blocked on a full pipe, which would turn a chatty command into a hanging one.
     */
    private fun readInto(reader: BufferedReader, target: StringBuilder): Int {
        var total = 0
        runCatching {
            reader.use { source ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (target.length < MAX_OUTPUT_CHARS) {
                        target.append(buffer, 0, minOf(read, MAX_OUTPUT_CHARS - target.length))
                    }
                }
            }
        }
        return total
    }
}
