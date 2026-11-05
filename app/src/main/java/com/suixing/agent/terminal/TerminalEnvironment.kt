package com.suixing.agent.terminal

import android.content.Context
import com.suixing.agent.capability.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal data class TerminalLaunch(
    val argv: List<String>,
    val environment: Map<String, String>,
    val cwd: String,
    val label: String,
    val kind: String = "android",
    val note: String? = null,
)

internal suspend fun androidTerminalLaunch(context: Context): TerminalLaunch = withContext(Dispatchers.IO) {
    val cwd = TerminalNative.appShellCwd(context)
    val nativeDir = context.applicationInfo.nativeLibraryDir
    val temp = File(context.cacheDir, "terminal-tmp").apply { mkdirs() }
    val environment = linkedMapOf(
        "HOME" to cwd, "TMPDIR" to temp.absolutePath,
        "PATH" to "$nativeDir:/system/bin:/system/xbin",
        "TERM" to "xterm-256color", "LANG" to "C.UTF-8", "PS1" to "$ ",
    )
    Shell.bundledBusybox()?.let { environment["BB"] = it }
    val candidates = buildList {
        Shell.bundledBash()?.let { add(listOf(it, "--noprofile", "--norc") to "bash") }
        Shell.bundledBusybox()?.let { add(listOf(it, "sh") to "busybox sh") }
        add(listOf(TerminalNative.APP_SHELL) to "system sh")
    }
    val failures = mutableListOf<String>()
    for ((command, label) in candidates) {
        // Runtime selection also catches a shell killed by ROM seccomp immediately after exec.
        val failure = runCatching {
            val process = ProcessBuilder(command + listOf("-c", "exit 0")).apply {
                directory(File(cwd))
                environment().clear()
                environment().putAll(environment)
                redirectErrorStream(true)
                redirectOutput(File("/dev/null"))
            }.start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                error("启动超时")
            }
            check(process.exitValue() == 0) { "退出码 " + process.exitValue() }
        }.exceptionOrNull()
        if (failure == null) return@withContext TerminalLaunch(
            command + "-i", environment, cwd, label, note = failures.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
        failures += "$label 不可用：" + failure.message
    }
    error(failures.joinToString("；"))
}
