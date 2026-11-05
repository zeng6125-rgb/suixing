package com.suixing.agent.terminal

import android.content.Context

/**
 * #61 PR-0: low-level JNI PTY compatibility probe, intentionally NOT exposed
 * to the Agent tool registry or production UI. Never use on the main thread.
 *
 * nativeRead returns null on timeout, ByteArray(0) on EOF. The caller owns
 * master fd and child PID. Exactly one reader and one waitpid owner are required.
 * A later TerminalSessionManager will enforce that ownership.
 */
internal object TerminalNative {
    init { System.loadLibrary("suixing_terminal") }

    external fun nativeOpen(shell: String, cwd: String, columns: Int, rows: Int): LongArray
    external fun nativeRead(masterFd: Int, maxBytes: Int, timeoutMs: Int): ByteArray?
    external fun nativeWrite(masterFd: Int, bytes: ByteArray): Int
    external fun nativeResize(masterFd: Int, columns: Int, rows: Int)
    external fun nativeSignalGroup(pid: Int, signal: Int)
    external fun nativeWait(pid: Int): Int
    external fun nativeClose(masterFd: Int)

    /** 会话头部展示的 shell 与 forkpty 实际启动的是同一个常量，不各写一份字面量。 */
    const val APP_SHELL = "/system/bin/sh"

    /** 会话头部展示的 CWD 与 forkpty 实际用的是同一个解析路径。 */
    fun appShellCwd(context: Context): String {
        val workspace = context.getExternalFilesDir(null) ?: context.cacheDir
        require(workspace.isDirectory) { "Terminal workspace unavailable" }
        return workspace.absolutePath
    }

    fun openAppShell(context: Context, columns: Int = 80, rows: Int = 24): LongArray =
        nativeOpen(APP_SHELL, appShellCwd(context), columns, rows)
}
