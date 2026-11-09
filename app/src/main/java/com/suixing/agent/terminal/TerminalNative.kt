package com.suixing.agent.terminal

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Low-level JNI PTY bridge. Never use on the main thread.
 *
 * nativeRead returns null on timeout, ByteArray(0) on EOF. The caller owns
 * an opaque master handle and the child PID. Rust owns actual descriptors and
 * retains in-flight operations across close; one reader and one waitpid owner are required.
 * TerminalSessionManager enforces that ownership.
 */
internal object TerminalNative {
    init { System.loadLibrary("suixing_terminal") }

    external fun nativeOpenProcess(argv: Array<String>, environment: Array<String>, cwd: String, columns: Int, rows: Int): LongArray
    fun nativeOpen(shell: String, cwd: String, columns: Int, rows: Int): LongArray = nativeOpenProcess(
        arrayOf(shell, "-i"),
        arrayOf("PATH=/system/bin:/system/xbin", "TERM=xterm-256color", "LANG=C.UTF-8", "PS1=$ ", "HOME=$cwd"),
        cwd, columns, rows,
    )
    external fun nativeRead(masterFd: Int, maxBytes: Int, timeoutMs: Int): ByteArray?
    external fun nativeWrite(masterFd: Int, bytes: ByteArray): Int
    external fun nativeResize(masterFd: Int, columns: Int, rows: Int)
    external fun nativeSignalGroup(pid: Int, signal: Int)
    external fun nativeWait(pid: Int): Int
    /** Returns -2 while the child is still running; a returned exit code also reaps it. */
    external fun nativeWaitForExit(pid: Int, timeoutMs: Int): Int
    external fun nativeClose(masterFd: Int)
    external fun nativeSignalOwned(masterHandle: Int, pid: Int, signal: Int)
    /** A timeout of -1 waits for exit; nonnegative values are bounded to five seconds. */
    external fun nativeWaitOwned(masterHandle: Int, pid: Int, timeoutMs: Int): Int
    private external fun nativeBeginWrite(masterHandle: Int): Long
    private external fun nativeCancelWrite(token: Long)
    private external fun nativeReleaseWrite(token: Long)
    private external fun nativeWriteToken(token: Long, bytes: ByteArray): Int

    /** The requesting tool can cancel its own blocked input without closing the terminal. */
    suspend fun writeOwned(masterHandle: Int, bytes: ByteArray, owner: Job?): Int =
        suspendCancellableCoroutine { continuation ->
            val token = nativeBeginWrite(masterHandle)
            continuation.invokeOnCancellation { nativeCancelWrite(token) }
            // The owner waits for the actor's reply, not for this native call, so its completion
            // can notify the independent actor while the syscall is still waiting for POLLOUT.
            val ownerCancellation = owner?.invokeOnCompletion {
                if (owner.isCancelled) nativeCancelWrite(token)
            }
            try {
                if (owner?.isActive == false) nativeCancelWrite(token)
                if (continuation.isActive) continuation.resume(nativeWriteToken(token, bytes))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                ownerCancellation?.dispose()
                nativeReleaseWrite(token)
            }
        }

    /** 会话头部展示的 shell 与 forkpty 实际启动的是同一个常量，不各写一份字面量。 */
    const val APP_SHELL = "/system/bin/sh"

    /** 会话头部展示的 CWD 与 forkpty 实际用的是同一个解析路径。 */
    fun appShellCwd(context: Context): String {
        val workspace = context.getExternalFilesDir(null) ?: context.filesDir
        require(workspace.isDirectory) { "Terminal workspace unavailable" }
        return workspace.absolutePath
    }

    fun openAppShell(context: Context, columns: Int = 80, rows: Int = 24): LongArray =
        nativeOpen(APP_SHELL, appShellCwd(context), columns, rows)
}
