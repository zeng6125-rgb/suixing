package com.suixing.agent.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import com.termux.terminal.TerminalSession as TerminalDisplay

internal const val SIGINT = 2
internal const val SIGTERM = 15
internal const val SIGKILL = 9
internal const val TERMINAL_OUTPUT_LIMIT = 64 * 1024
internal fun appendBounded(current: String, chunk: String, limit: Int = TERMINAL_OUTPUT_LIMIT): String {
    require(limit > 0)
    return (current + chunk).takeLast(limit)
}

/** One native reader, queued writer and reaper. Page navigation only detaches the view. */
internal class TerminalSession(
    val id: String,
    private val masterFd: Int,
    val pid: Int,
    val launch: TerminalLaunch,
    val policy: TerminalPolicy,
    private val scope: CoroutineScope,
    val log: TerminalLog,
    private val onEnded: (Int, String?) -> Unit,
) {
    enum class SessionStatus { STARTING, RUNNING, EXITED, INTERRUPTED }
    private val _status = MutableStateFlow(SessionStatus.STARTING)
    val status = _status.asStateFlow()
    private val _ended = MutableStateFlow<String?>(null)
    val ended = _ended.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _historyLimited = MutableStateFlow(false)
    val historyLimited = _historyLimited.asStateFlow()
    private val outputRing = TerminalOutputRing()
    private val decoder = TerminalUtf8Decoder()
    private val closing = AtomicBoolean(false)
    private val finished = CompletableDeferred<Unit>()
    private val nativeLock = Any()
    private data class Input(val bytes: ByteArray, val caller: TerminalCaller?, val grantRevision: Long,
        val written: CompletableDeferred<Unit>, val ownerJob: Job?)
    private val writes = Channel<Input>(32)
    private val chunks = Channel<ByteArray>(8)
    private data class PtySize(val columns: Int, val rows: Int)
    private val resizes = Channel<PtySize>(Channel.CONFLATED)
    private var reader: Job? = null
    private var processor: Job? = null
    private var writer: Job? = null
    private var resizer: Job? = null
    private var dirty = false
    private var lastPublished = 0L
    private var frame: Job? = null
    var onCopy: ((String) -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    val shell: String get() = launch.label
    val cwd: String get() = launch.cwd
    val historyTruncated: Boolean get() = outputRing.truncated

    // The emulator and view share Main; all native calls run on IO.
    val display = TerminalDisplay(object : TerminalDisplay.Host {
        override fun input(bytes: ByteArray, fromUser: Boolean) {
            if (fromUser) policy.takeOver()
            scope.launch { runCatching { writeBytes(bytes) } }
        }
        override fun resize(columns: Int, rows: Int) {
            // Keyboard animation may request many sizes. One worker preserves order,
            // keeping only the newest pending size instead of racing independent IO jobs.
            resizes.trySend(PtySize(columns, rows))
        }
        override fun copy(text: String) { onCopy?.invoke(text) }
        override fun paste() { onPaste?.invoke() }
    }, 80, 24)

    fun start() {
        check(_status.value == SessionStatus.STARTING)
        _status.value = SessionStatus.RUNNING
        resizer = scope.launch(Dispatchers.IO) {
            for (size in resizes) synchronized(nativeLock) {
                if (!closing.get()) runCatching {
                    TerminalNative.nativeResize(masterFd, size.columns, size.rows)
                }
            }
        }
        processor = scope.launch(Dispatchers.Main.immediate) {
            var sliceStarted = android.os.SystemClock.uptimeMillis()
            for (bytes in chunks) {
                display.emulator.append(bytes, bytes.size)
                if (display.isHistoryFull) _historyLimited.value = true
                dirty = true
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPublished >= 32) publish(now)
                else if (frame?.isActive != true) frame = scope.launch {
                    delay(32 - (now - lastPublished))
                    if (dirty) publish(android.os.SystemClock.uptimeMillis())
                }
                // A busy PTY can keep the channel perpetually ready. Yield a bounded
                // Main-thread slice so input and display frames can run between chunks.
                if (now - sliceStarted >= 4L) {
                    yield()
                    sliceStarted = android.os.SystemClock.uptimeMillis()
                }
            }
            publish(android.os.SystemClock.uptimeMillis())
        }
        writer = scope.launch(Dispatchers.IO) {
            for (request in writes) {
                val allowed = synchronized(nativeLock) {
                    if (request.ownerJob?.isActive == false) {
                        request.written.cancel()
                        return@synchronized false
                    }
                    if (closing.get() || request.caller?.let {
                        !policy.allow(it) || request.grantRevision != policy.revision.value
                    } == true) {
                        request.written.completeExceptionally(IllegalStateException("输入已取消：会话停止或用户已接管"))
                        return@synchronized false
                    }
                    true
                }
                if (!allowed) continue
                try {
                    check(TerminalNative.writeOwned(masterFd, request.bytes, request.ownerJob) == request.bytes.size) { "终端输入未完整写入" }
                    request.written.complete(Unit)
                } catch (cancelled: CancellationException) {
                    request.written.cancel(cancelled)
                    currentCoroutineContext().ensureActive()
                } catch (error: Exception) {
                    if (!closing.get()) _ended.value = "写入失败：" + error.message
                    request.written.completeExceptionally(error)
                }
            }
        }
        reader = scope.launch(Dispatchers.IO) {
            var failure: String? = null
            try {
                while (!closing.get() && isActive) {
                    // close waits for this reader before releasing/reusing the fd.
                    val bytes = TerminalNative.nativeRead(masterFd, 16 * 1024, 200) ?: continue
                    if (bytes.isEmpty()) break
                    outputRing.append(decoder.decode(bytes))
                    log.append(bytes)
                    chunks.send(bytes)
                }
            } catch (error: Exception) {
                if (!closing.get()) failure = error.message ?: error.javaClass.simpleName
            } finally {
                outputRing.append(decoder.decode(ByteArray(0), end = true))
                if (!closing.get()) {
                    scope.launch { finish(force = failure != null, failure = failure) }
                }
            }
        }
    }

    private fun publish(now: Long) {
        dirty = false
        lastPublished = now
        _revision.value++
    }

    suspend fun writeBytes(bytes: ByteArray, caller: TerminalCaller? = null) {
        check(!closing.get()) { "终端会话已结束" }
        require(bytes.size <= 16 * 1024) { "每次输入最多 16 KiB" }
        if (caller != null) check(policy.allow(caller)) { "该终端未授权当前任务，或已由用户接管" }
        val written = CompletableDeferred<Unit>()
        writes.send(Input(bytes.copyOf(), caller, policy.revision.value, written, currentCoroutineContext()[Job]))
        written.await()
    }
    suspend fun write(text: String, caller: TerminalCaller? = null) = writeBytes(text.toByteArray(Charsets.UTF_8), caller)
    /** The line discipline delivers ^C to the actual foreground process group. */
    suspend fun interrupt(caller: TerminalCaller? = null) = writeBytes(byteArrayOf(3), caller)
    fun read(cursor: Long?, maxBytes: Int): TerminalRead = outputRing.read(cursor, maxBytes)
    suspend fun transcript(): String = withContext(Dispatchers.Main.immediate) {
        display.emulator.screen.transcriptText
    }
    fun clearOutput() {
        display.emulator.clearScreenAndHistory()
        outputRing.clear()
        _historyLimited.value = false
        publish(android.os.SystemClock.uptimeMillis())
    }
    fun takeOver() { policy.takeOver() }
    suspend fun stop(caller: TerminalCaller? = null) = finish(force = true, caller = caller)

    private suspend fun finish(force: Boolean, failure: String? = null, caller: TerminalCaller? = null) = withContext(NonCancellable + Dispatchers.IO) {
        if (caller != null) check(policy.allow(caller)) { "终端已由用户接管" }
        if (!closing.compareAndSet(false, true)) {
            finished.await()
            return@withContext
        }
        writes.close()
        resizes.close()
        try {
            if (force) {
                runCatching { TerminalNative.nativeSignalOwned(masterFd, pid, SIGTERM) }
                delay(200)
                runCatching { TerminalNative.nativeSignalOwned(masterFd, pid, SIGKILL) }
            }
            reader?.join()
            chunks.close()
            processor?.join()
            frame?.cancelAndJoin()
            writer?.cancelAndJoin()
            resizer?.cancelAndJoin()
            while (true) {
                val pending = writes.tryReceive().getOrNull() ?: break
                pending.written.completeExceptionally(IllegalStateException("终端会话已停止"))
            }
            // EOF only means the slave descriptors closed. A process may close stdio and
            // keep running; do not leave it in an uninterruptible natural-exit wait.
            var code = runCatching { TerminalNative.nativeWaitOwned(masterFd, pid, 300) }.getOrDefault(-1)
            if (code == -2) {
                runCatching { TerminalNative.nativeSignalOwned(masterFd, pid, SIGTERM) }
                delay(200)
                runCatching { TerminalNative.nativeSignalOwned(masterFd, pid, SIGKILL) }
                code = runCatching { TerminalNative.nativeWaitOwned(masterFd, pid, -1) }.getOrDefault(-1)
            }
            _ended.value = failure?.let { "终端已断开：$it" } ?: "会话已结束（退出码 $code）"
            _status.value = SessionStatus.EXITED
            onEnded(code, failure)
        } finally {
            synchronized(nativeLock) { runCatching { TerminalNative.nativeClose(masterFd) } }
            try { log.close() } finally { finished.complete(Unit) }
        }
    }
}
