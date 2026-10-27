package com.suixing.agent.capability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/** seccomp 杀死进程时的退出码：128 + SIGSYS(31)。 */
internal const val SECCOMP_SIGSYS_EXIT = 159

/**
 * 自带 bash 被 seccomp 拦下、自动改用系统 sh 时写在输出最前面的一行。
 *
 * 不列举"哪些特性不可用"：Android 的系统 sh 是 mksh，`[[ ]]`、`set -A` 这些其实有，
 * 列清单只会让模型据此猜错。只陈述已经发生的事，让模型自己验证。
 */
internal const val SECCOMP_FALLBACK_NOTE =
    "[随行] 自带 bash 被系统的 seccomp 过滤器拦下（exit 159 / SIGSYS），这条命令已改用系统 sh 执行；" +
        "bash 专有语法在这条命令里可能不可用，需要时请改用 POSIX 写法。\n"

/** 这个退出码是不是「被 seccomp 杀掉」的信号。 */
internal fun looksLikeSeccompKill(exitCode: Int): Boolean = exitCode == SECCOMP_SIGSYS_EXIT

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

    /**
     * 随 APK 分发的静态 bash / busybox 所在目录（`nativeLibraryDir`，由 `Core.init` 写入）。
     *
     * 为什么放 nativeLibraryDir 而不是 filesDir：Android 10 起 targetSdk≥29 的 app **不能执行**
     * 自己数据目录里的文件（W^X），而 nativeLibraryDir 里的 `lib*.so` 是可执行的。所以两个二进制
     * 以 `jniLibs/arm64-v8a/libbash.so`、`libbusybox.so` 的形式随包分发，并由
     * `packaging.jniLibs.useLegacyPackaging = true` 保证它们被**解压到磁盘**（AGP 默认把 .so
     * 直接 mmap 在 APK 里，那样 execve 拿不到路径，只能给链接器加载）。
     */
    @Volatile
    private var binDir: String = ""

    fun initBinaries(nativeLibraryDir: String) {
        binDir = nativeLibraryDir
    }

    /** 自带的 bash 路径；没打包或不可执行时返回 null（调用方退回系统 sh）。 */
    fun bundledBash(): String? = bundled("libbash.so")

    /** 自带的 busybox 路径。它按 `argv[0]` 分派 applet，所以要用 `"$BB" <applet>` 调。 */
    fun bundledBusybox(): String? = bundled("libbusybox.so")

    private fun bundled(name: String): String? = binDir
        .takeIf { it.isNotEmpty() }
        ?.let { File(it, name) }
        ?.takeIf { it.canExecute() }
        ?.absolutePath

    /** POSIX 单引号包裹：内部单引号写成 `'\''`，这样整串能安全地作为单个参数交给 sh。 */
    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

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

    suspend fun local(
        command: String,
        shell: String = "auto",
        /**
         * 工作目录。**有它就省掉 `cd X &&` 前缀**：命令直接交给 shell，
         * 目录由 ProcessBuilder 设定 —— 插件调用因此少一层命令拼接。
         */
        cwd: java.io.File? = null,
    ): ShellResult {
        // 自带 bash 时优先用它：Android 只有 toybox 的 sh，连 `[[ ]]`、数组、`$(<file)` 都没有。
        //
        // 三个档位（插件清单的 `shell` 字段与 run_shell 的 `shell` 参数都走这里）：
        //   auto（缺省）＝先试 bash，被 seccomp 拦下再退回 sh —— 双接口；
        //   sh          ＝直接用系统 sh，不试探（纯 POSIX 命令省掉一次进程创建）；
        //   bash        ＝**只用 bash**：bash 不可用或被 seccomp 杀掉就明确失败，不偷偷换 sh。
        //                 写 `[[ ]]`、数组的插件必须用这一档 —— 一旦静默回退，sh 会以
        //                 看不懂的语法错误收场，比直接报错更难查。
        val bash = bundledBash()
        val wantBash = shell == "bash"
        if (bash == null) {
            return if (wantBash) {
                ShellResult(false, "", "这个构建没有随包 bash，无法按 shell=bash 执行", -1)
            } else {
                run(arrayOf("sh", "-c", command), cwd = cwd)
            }
        }
        if (shell == "sh") return run(arrayOf("sh", "-c", command), cwd = cwd)
        val env = buildMap {
            // 自带 busybox 以 $BB 暴露（applet 名要靠 argv[0]，所以不放进 PATH 当命令名用）：
            // 命令里写 `"$BB" grep -n foo *.kt` 即可拿到完整的 coreutils 级工具。
            bundledBusybox()?.let { put("BB", it) }
            put("PATH", "$binDir:/system/bin:/system/xbin")
            put("TERM", "dumb")
        }
        val result = run(arrayOf(bash, "-lc", command), env, cwd = cwd)
        // 自带 bash 在本进程里可能被系统的 seccomp 过滤器杀掉（exit 159 = 128+SIGSYS）：app 派生的
        // 子进程**继承**那份过滤器，而 su 起的是另一棵进程树、不继承，所以只有 local 档会中招。
        //
        // **只对这一条命令回退，不做进程级永久降级**：被拦的往往是 bash 用到的某个新 syscall，
        // 哪些命令会踩到无法事先知道 —— 永久降级会让本来能跑的命令也无故失去 bash 能力。
        // 代价只是失败时多一个瞬间退出的子进程（毫秒级）。
        if (!looksLikeSeccompKill(result.exitCode)) return result
        if (wantBash) {
            return ShellResult(
                false,
                "",
                "自带 bash 被系统的 seccomp 过滤器拦下（exit 159 / SIGSYS），而调用方要求 shell=bash，" +
                    "所以没有改用 sh 重试。把 shell 改成 auto 可以自动回退。",
                result.exitCode,
            )
        }
        val retry = run(arrayOf("sh", "-c", command), cwd = cwd)
        return retry.copy(stdout = SECCOMP_FALLBACK_NOTE + retry.stdout)
    }

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
        // root 档也走随包 bash（用户要求）：su 起来后由自带 bash 解释命令，环境与 local 档对齐
        // （PATH 带 binDir、BusyBox 以 $BB 暴露）。没有自带 bash（未打包或不可执行）时退回原形态：
        // su -c 直接交给系统 sh。命令与路径都过 [quote]，避免二次分词。
        val bash = bundledBash()
        val line = if (bash == null) {
            command
        } else {
            val prelude = buildString {
                append("PATH=").append(quote("$binDir:/system/bin:/system/xbin")).append(' ')
                bundledBusybox()?.let { append("BB=").append(quote(it)).append(' ') }
                append("TERM=dumb ")
            }
            prelude + quote(bash) + " -lc " + quote(command)
        }
        collect(ProcessBuilder("su", "-c", line).start())
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
                process.killAndReap()
                throw CancellationException("已停止")
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        if (!finished) {
            process.killAndReap()
            false
        } else {
            val text = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && text.contains("uid=0")
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        false
    }

    private suspend fun run(
        command: Array<String>,
        env: Map<String, String> = emptyMap(),
        /** 工作目录：直接交给 ProcessBuilder，省掉命令里的 `cd X &&` 前缀（少一层 shell）。 */
        cwd: java.io.File? = null,
    ): ShellResult =
        // **必须切到 IO**（issue #34 缺陷 2）：`collect()` 里是 `process.waitFor(POLL_MS)`
        // 的阻塞轮询，`ProcessBuilder.start()` 本身也是阻塞的。原来这里没有 `withContext`，
        // 整段就占着调用方的 dispatcher —— 调用方若是 `Dispatchers.Main`，插件工具一跑
        // 界面就卡，而且会一直卡到 `DEFAULT_TIMEOUT_MS`。
        //
        // 同文件里 `http()` 早就切了 IO，两个分支不该不一致。
        // 取消仍然有效：`collect()` 每轮 `waitFor` 之后都查 `currentCoroutineContext().isActive`。
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = ProcessBuilder(*command).apply { environment().putAll(env) }
                if (cwd != null && cwd.isDirectory) builder.directory(cwd)
                collect(builder.start())
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ShellResult(false, "", error.message ?: error.javaClass.simpleName, -1)
            }
        }

    private suspend fun collect(process: Process): ShellResult {
        val stdout = OutputCapture()
        val stderr = OutputCapture()
        val outThread = Thread { readInto(process.inputStream.bufferedReader(), stdout) }.apply { isDaemon = true }
        val errThread = Thread { readInto(process.errorStream.bufferedReader(), stderr) }.apply { isDaemon = true }
        outThread.start()
        errThread.start()
        try {
            val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
            var finished = false
            while (true) {
                if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
                if (!currentCoroutineContext().isActive) {
                    process.killAndReap()
                    throw CancellationException("已停止")
                }
                if (System.currentTimeMillis() >= deadline) break
            }
            if (!finished) process.killAndReap()
            outThread.join(if (finished) 1500L else 500L)
            errThread.join(if (finished) 1500L else 500L)
            return ShellResult(
                finished && process.exitValue() == 0,
                stdout.snapshot(),
                if (finished) stderr.snapshot() else "命令超时（${DEFAULT_TIMEOUT_MS / 1000}s）已终止",
                if (finished) process.exitValue() else -1,
            )
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    /** Reader threads and timeout snapshots share one lock, including the truncation counter. */
    private class OutputCapture {
        private val text = StringBuilder()
        private var total = 0L

        @Synchronized fun append(buffer: CharArray, count: Int) {
            total += count
            if (text.length < MAX_OUTPUT_CHARS) {
                text.append(buffer, 0, minOf(count, MAX_OUTPUT_CHARS - text.length))
            }
        }

        @Synchronized fun snapshot(): String = if (total <= text.length) text.toString() else
            text.toString() + "\n…（输出过长已截断：只保留前 $MAX_OUTPUT_CHARS 字符，完整输出共约 $total 字节）"
    }

    /**
     * 杀掉并**回收**一个子进程（issue #9）。
     *
     * `destroy()` 只是发 SIGTERM：不 `waitFor()` 就拿不到退出码，三个管道也不会关闭 ——
     * 每次超时/取消泄漏 3 个 fd + 1 个进程句柄，长期跑自动化会耗光 fd。
     * 先等 2 秒，不走就 `destroyForcibly()`，最后无论如何把三条流关掉。
     */
    private fun Process.killAndReap() {
        runCatching { destroy() }
        val reaped = runCatching { waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!reaped) runCatching { destroyForcibly() }
        runCatching { inputStream.close() }
        runCatching { errorStream.close() }
        runCatching { outputStream.close() }
    }

    /**
     * Drains [reader] into [target], keeping at most [MAX_OUTPUT_CHARS] characters, and returns
     * how many the command produced in total.
     *
     * The stream is still read to the end after the cap: abandoning it would leave the process
     * blocked on a full pipe, which would turn a chatty command into a hanging one.
     */
    private fun readInto(reader: BufferedReader, target: OutputCapture) {
        runCatching {
            reader.use { source ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    target.append(buffer, read)
                }
            }
        }
    }
}
