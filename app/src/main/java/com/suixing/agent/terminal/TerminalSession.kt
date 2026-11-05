package com.suixing.agent.terminal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Linux signal numbers; the JNI bridge only accepts SIGINT/SIGTERM/SIGKILL/SIGHUP. */
internal const val SIGINT = 2
internal const val SIGTERM = 15
internal const val SIGKILL = 9

/** Keep the transcript bounded: unlimited PTY output must not grow the UI buffer forever. */
internal const val TERMINAL_OUTPUT_LIMIT = 64 * 1024

/**
 * Append a chunk, retaining only the tail. Returns the new buffer, never longer than
 * [limit]. Terminal output is a byte stream; we decode to text for display but keep the
 * bound in characters so the UI cannot OOM on a runaway process.
 */
internal fun appendBounded(current: String, chunk: String, limit: Int = TERMINAL_OUTPUT_LIMIT): String {
    require(limit > 0) { "output limit must be positive" }
    if (chunk.length >= limit) return chunk.takeLast(limit)
    val merged = current + chunk
    return if (merged.length <= limit) merged else merged.takeLast(limit)
}

/**
 * One App-UID interactive PTY session (#61 PR-0 bridge + the minimum ownership layer).
 *
 * The JNI bridge requires exactly one reader and one waitpid owner. This class owns both:
 * a single [Dispatchers.IO] reader drains the master fd, and [close] is the only place that
 * signals, waits and closes — all on IO, never the main thread.
 *
 * Deliberately a single-session owner, not the full TerminalSessionManager from #61 PR-1:
 * leaving this screen detaches the view and closes the session. It does not claim
 * background survival, multi-session tabs, VT/ANSI emulation or agent access.
 */
internal class TerminalSession private constructor(
    private val masterFd: Int,
    val pid: Int,
    val shell: String,
    val cwd: String,
    val columns: Int,
    val rows: Int,
    private val scope: CoroutineScope,
) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _status = MutableStateFlow(SessionStatus.STARTING)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()
    /** Non-null once the session has ended: exit code or the failure reason. */
    private val _ended = MutableStateFlow<String?>(null)
    val ended: StateFlow<String?> = _ended.asStateFlow()

    private var reader: Job? = null
    /** Guards the single TERM→KILL→waitpid→close path; written on IO, read on any thread. */
    @Volatile
    private var closed = false
    private val closeLock = Any()

    enum class SessionStatus { STARTING, RUNNING, EXITED }

    fun start() {
        _status.value = SessionStatus.RUNNING
        reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !closed) {
                    // Bounded poll: null = timeout, empty = EOF (slave closed).
                    val chunk = TerminalNative.nativeRead(masterFd, 16 * 1024, 200)
                    if (chunk == null) continue
                    if (chunk.isEmpty()) break
                    val text = String(chunk, Charsets.UTF_8)
                    _output.value = appendBounded(_output.value, text)
                }
            } catch (t: Throwable) {
                _ended.value = "读取失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                // EOF means the child already exited: reap without signalling it.
                if (!closed) {
                    scope.launch(Dispatchers.IO) { reap(force = false) }
                }
            }
        }
    }

    /** Write raw bytes, not a shell-command parser. Runs off the main thread. */
    suspend fun write(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

    /** Raw byte path for the key bar (ANSI escapes, control bytes). All native work on IO. */
    suspend fun writeBytes(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        runCatching { TerminalNative.nativeWrite(masterFd, bytes) }
            .onFailure { _ended.value = "写入失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** Clear only the displayed transcript; the PTY and its child are untouched. */
    fun clearOutput() { _output.value = "" }

    /** Notify the PTY of a new viewport size; the shell may redraw (e.g. stty size). */
    suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        runCatching { TerminalNative.nativeResize(masterFd, columns, rows) }
            .onFailure { _ended.value = "resize 失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** Ctrl-C: signal the foreground process group, not Process.destroy(). */
    suspend fun interrupt() = signal(SIGINT)

    private suspend fun signal(sig: Int) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        runCatching { TerminalNative.nativeSignalGroup(pid, sig) }
            .onFailure { _ended.value = "信号失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** Single owner: TERM, short grace, KILL, waitpid, close fd. All on IO.
     * [force] is false when the child already exited (reader saw EOF): just reap. */
    private suspend fun reap(force: Boolean) = withContext(Dispatchers.IO) {
        val already = synchronized(closeLock) {
            if (closed) true else { closed = true; false }
        }
        if (already) return@withContext
        reader?.cancel()
        try {
            if (force) {
                runCatching { TerminalNative.nativeSignalGroup(pid, SIGTERM) }
                delay(150)
                runCatching { TerminalNative.nativeSignalGroup(pid, SIGKILL) }
            }
            val code = runCatching { TerminalNative.nativeWait(pid) }.getOrDefault(-1)
            _ended.value = "会话结束（退出码 $code）"
        } catch (t: Throwable) {
            _ended.value = "回收失败：${t.message ?: t.javaClass.simpleName}"
        } finally {
            runCatching { TerminalNative.nativeClose(masterFd) }
            _status.value = SessionStatus.EXITED
            // All owned work is done (reader cancelled, child reaped, fd closed); the
            // scope can go now. Cancelling from inside its own last coroutine is safe.
            scope.cancel()
        }
    }

    /** Idempotent, non-blocking: detaching the view closes the session. */
    fun close() {
        if (closed) return
        // reap() owns the whole TERM→KILL→waitpid→close chain and cancels the scope
        // when it finishes; close() itself never touches native code on the main thread.
        scope.launch(Dispatchers.IO) { reap(force = true) }
    }

    companion object {
        /**
         * Open a single interactive `/system/bin/sh -i` session in the app's external
         * files dir. forkpty/JNI runs on IO, never the main thread. Any failure is
         * surfaced to the caller instead of being reported as a working terminal.
         */
        suspend fun open(context: Context): TerminalSession = withContext(Dispatchers.IO) {
            val columns = 80
            val rows = 24
            val shell = TerminalNative.APP_SHELL
            val cwd = TerminalNative.appShellCwd(context)
            val handle = TerminalNative.nativeOpen(shell, cwd, columns, rows)
            require(handle.size == 2) { "PTY open returned an invalid handle" }
            val fd = handle[0].toInt()
            val pid = handle[1].toInt()
            require(fd >= 0 && pid > 1) { "PTY open returned an invalid fd/pid" }
            TerminalSession(
                masterFd = fd,
                pid = pid,
                shell = shell,
                cwd = cwd,
                columns = columns,
                rows = rows,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    }
}
