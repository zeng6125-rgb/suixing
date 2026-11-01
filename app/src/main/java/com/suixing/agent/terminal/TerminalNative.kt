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

    /** App UID only. Never inherits Root/Shizuku grants from ShellTools. */
    fun openAppShell(context: Context, columns: Int = 80, rows: Int = 24): LongArray {
        val workspace = context.getExternalFilesDir(null) ?: context.cacheDir
        require(workspace.isDirectory) { "Terminal workspace unavailable" }
        return nativeOpen("/system/bin/sh", workspace.absolutePath, columns, rows)
    }
}
