package com.lingshu.agent.capability

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
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
        return if (text.length > limit) text.take(limit) + "\n…（输出被截断）" else text
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

    fun local(command: String): ShellResult = run(arrayOf("sh", "-c", command))

    fun shizuku(command: String): ShellResult {
        val method = newProcess
            ?: return ShellResult(false, "", "当前 Shizuku 版本不提供 newProcess（需要 13.x）", -1)
        return runCatching {
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            collect(process)
        }.getOrElse { error ->
            val cause = error.cause ?: error
            ShellResult(false, "", "Shizuku 不可用：${cause.message ?: cause.javaClass.simpleName}", -1)
        }
    }

    fun root(command: String): ShellResult = runCatching {
        collect(ProcessBuilder("su", "-c", command).start())
    }.getOrElse { error ->
        ShellResult(false, "", "无法获取 root：${error.message ?: error.javaClass.simpleName}", -1)
    }

    fun rootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").start()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            false
        } else {
            val text = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && text.contains("uid=0")
        }
    }.getOrDefault(false)

    private fun run(command: Array<String>): ShellResult = runCatching {
        collect(ProcessBuilder(*command).start())
    }.getOrElse { error ->
        ShellResult(false, "", error.message ?: error.javaClass.simpleName, -1)
    }

    private fun collect(process: Process): ShellResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { readInto(process.inputStream.bufferedReader(), stdout) }.apply { isDaemon = true }
        val errThread = Thread { readInto(process.errorStream.bufferedReader(), stderr) }.apply { isDaemon = true }
        outThread.start()
        errThread.start()
        val finished = process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            outThread.join(500)
            errThread.join(500)
            return ShellResult(false, stdout.toString(), "命令超时（${DEFAULT_TIMEOUT_MS / 1000}s）已终止", -1)
        }
        outThread.join(1500)
        errThread.join(1500)
        return ShellResult(process.exitValue() == 0, stdout.toString(), stderr.toString(), process.exitValue())
    }

    private fun readInto(reader: BufferedReader, target: StringBuilder) {
        runCatching {
            reader.use { source ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    target.append(buffer, 0, read)
                }
            }
        }
    }
}
