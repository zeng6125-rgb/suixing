package com.suixing.agent.mcp

import com.suixing.agent.llm.readCancellable
import com.suixing.agent.llm.sseDataValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A JSON-RPC 2.0 message channel. Implementations own the framing; everything
 * above only deals in [JsonObject]s.
 */
interface McpTransport {
    val label: String

    suspend fun start()

    /** Sends [message] and returns the response carrying the same id. */
    suspend fun exchange(message: JsonObject, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JsonObject

    suspend fun notify(message: JsonObject)

    fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}

/** How long a killed MCP process gets to exit on its own before it is killed forcibly. */
private const val PROCESS_GRACE_MS = 300L
private val PROCESS_REAPER = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val MAX_MCP_MESSAGE_BYTES = 1_048_576L

/**
 * Spawns a child process and speaks newline-delimited JSON-RPC over its
 * stdin/stdout — the `stdio` transport of MCP.
 *
 * A background reader matches responses by id, so server-initiated
 * notifications never block a pending request, and lines that are not JSON
 * (servers that log to stdout) are skipped instead of killing the stream.
 */
class StdioTransport(
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val workingDir: String? = null,
) : McpTransport {
    override val label: String = command.joinToString(" ")

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    private val lifecycleLock = Any()
    private var outThread: Thread? = null
    private var errThread: Thread? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val stderrTail = ArrayDeque<String>()
    private val writeLock = Any()

    /**
     * Only for reaping the child process: [close] has to return at once because it is called from
     * the UI, while a server that ignores SIGTERM can take a moment to die.
     */
    override suspend fun start() = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
        if (process != null) return@synchronized
        if (command.isEmpty()) throw McpException("stdio 传输需要一条启动命令")
        val builder = ProcessBuilder(command)
        if (environment.isNotEmpty()) builder.environment().putAll(environment)
        if (!workingDir.isNullOrBlank()) builder.directory(File(workingDir))
        val started = try {
            builder.start()
        } catch (error: Exception) {
            throw McpException(
                "无法启动 MCP 进程：${error.message ?: error::class.java.simpleName}（命令：$label）",
            )
        }
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        outThread = Thread({ readLoop(started) }, "mcp-stdio-out").apply {
            isDaemon = true
            start()
        }
        errThread = Thread({ errorLoop(started) }, "mcp-stdio-err").apply {
            isDaemon = true
            start()
        }
        }
    }

    override suspend fun exchange(message: JsonObject, timeoutMs: Long): JsonObject {
        val id = (message["id"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw McpException("JSON-RPC 请求缺少数字 id")
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            writeLine(message)
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw McpException("等待 MCP 服务器响应超时（${timeoutMs / 1000} 秒）：$label${stderrHint()}")
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun notify(message: JsonObject) {
        writeLine(message)
    }

    override fun close() {
        val (started, readers) = synchronized(lifecycleLock) {
            val captured = process to listOfNotNull(outThread, errThread)
            process = null
            writer = null
            outThread = null
            errThread = null
            failAll("MCP 传输已关闭")
            captured
        }
        // The wait does not belong on the caller's thread: `close()` is called from the UI when a
        // server is removed or switched off, and a stdio server that ignores SIGTERM would hold
        // the frame for the whole grace period. Reaping moves to IO — the caller only needs the
        // handles gone, which has already happened above.
        PROCESS_REAPER.launch {
            val running = started ?: return@launch
            runCatching {
                running.destroy()
                if (!running.waitFor(PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)) running.destroyForcibly()
            }
            runCatching { running.inputStream.close() }
            runCatching { running.errorStream.close() }
            runCatching { running.outputStream.close() }
            readers.forEach { thread ->
                thread.interrupt()
                runCatching { thread.join(PROCESS_GRACE_MS) }
            }
        }
    }

    private suspend fun writeLine(message: JsonObject) = withContext(Dispatchers.IO) {
        val out = writer ?: throw McpException("MCP 进程尚未启动：$label")
        try {
            synchronized(writeLock) {
                out.write(message.toString())
                out.write("\n")
                out.flush()
            }
        } catch (error: Exception) {
            throw McpException(
                "写入 MCP 进程失败（进程可能已退出）：${error.message ?: error::class.java.simpleName}${stderrHint()}",
            )
        }
    }

    private fun readLoop(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = try {
                    McpProtocol.json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    // Preserve the existing tolerance for ordinary stdout startup messages.
                    // A line shaped as a JSON object is a broken protocol response.
                    if (line.trimStart().startsWith('{')) {
                        failAll("MCP 服务器返回了无效 JSON：$label")
                    }
                    continue
                }
                if (message["method"] != null && message["id"] != null) {
                    val reply = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", message.getValue("id"))
                        put("error", buildJsonObject {
                            put("code", -32601)
                            put("message", "Client method not supported")
                        })
                    }
                    synchronized(writeLock) {
                        if (process === started) writer?.apply { write(reply.toString()); newLine(); flush() }
                    }
                    continue
                }
                val id = (message["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                pending.remove(id)?.complete(message)
            }
            }
        } catch (_: Exception) {
            // The stream ends when the process dies; the finally below reports it.
        } finally {
            synchronized(lifecycleLock) {
                if (process === started) failAll("MCP 进程已退出：$label${stderrHint()}")
            }
        }
    }

    private fun errorLoop(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.errorStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    while (stderrTail.size > STDERR_KEEP) stderrTail.removeFirst()
                }
            }
            }
        } catch (_: Exception) {
            // Diagnostics only.
        }
    }

    private fun failAll(reason: String) {
        val waiting = pending.keys.toList()
        for (id in waiting) {
            pending.remove(id)?.completeExceptionally(McpException(reason))
        }
    }

    private fun stderrHint(): String {
        val tail = synchronized(stderrTail) { stderrTail.toList() }
        if (tail.isEmpty()) return ""
        return "；stderr：${tail.takeLast(5).joinToString(" / ").take(500)}"
    }

    private companion object {
        const val STDERR_KEEP = 40
    }
}

/**
 * The `streamable HTTP` transport: every JSON-RPC message is one POST. Servers
 * may answer with plain JSON or with a single-message SSE stream, so both are
 * accepted.
 */
class HttpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : McpTransport {
    override val label: String = url

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun start() {
        if (url.isBlank()) throw McpException("http 传输需要填写服务器地址")
    }

    override suspend fun exchange(message: JsonObject, timeoutMs: Long): JsonObject =
        withContext(Dispatchers.IO) {
            post(message, timeoutMs, expectReply = true)
                ?: throw McpException("MCP 服务器返回了空响应：$url")
        }

    override suspend fun notify(message: JsonObject) {
        withContext(Dispatchers.IO) { post(message, McpTransport.DEFAULT_TIMEOUT_MS, expectReply = false) }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun post(message: JsonObject, timeoutMs: Long, expectReply: Boolean): JsonObject? {
        // The caller's deadline is honoured per call. A client built from [client] shares its
        // dispatcher and connection pool, so this neither changes the 180s default nor breaks
        // close(); stdio has always honoured timeoutMs, the HTTP path used to ignore it.
        val deadline = timeoutMs.coerceAtLeast(1L)
        val callClient = client.newBuilder()
            .callTimeout(deadline, TimeUnit.MILLISECONDS)
            .readTimeout(deadline, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(message.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json, text/event-stream")
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()
        return callClient.newCall(request).readCancellable { response ->
            if (!response.isSuccessful) throw McpException("MCP 服务器返回 HTTP ${response.code}")
            if (!expectReply) return@readCancellable null
            val body = response.body ?: throw McpException("MCP 服务器返回了空响应：$url")
            val source = body.source()
            val sse = response.header("Content-Type").orEmpty().contains("text/event-stream", true) ||
                (source.request(1) && source.buffer[0].toInt().toChar() !in "{[ \t\r\n")
            if (!sse) {
                if (source.request(MAX_MCP_MESSAGE_BYTES + 1)) throw McpException("MCP 响应超过 1 MiB 上限")
                val parsed = parseObject(source.readUtf8())
                if (!isResponseFor(parsed, message["id"])) throw McpException("MCP 响应 id 与请求不匹配")
                return@readCancellable parsed
            }
            val data = StringBuilder()
            var total = 0L
            fun event(): JsonObject? {
                if (data.isEmpty()) return null
                val payload = data.toString()
                data.setLength(0)
                if (payload == "[DONE]") return null
                val parsed = parseObject(payload)
                return parsed.takeIf { isResponseFor(it, message["id"]) }
            }
            while (true) {
                val newline = source.indexOf('\n'.code.toByte(), 0, MAX_MCP_MESSAGE_BYTES + 1)
                if (newline < 0 && source.buffer.size > MAX_MCP_MESSAGE_BYTES) throw McpException("MCP SSE 行超过 1 MiB 上限")
                val line = if (newline >= 0) source.readUtf8LineStrict(MAX_MCP_MESSAGE_BYTES)
                    else if (source.buffer.size > 0) source.readUtf8() else break
                total += line.length + 1L
                if (total > 16 * MAX_MCP_MESSAGE_BYTES) throw McpException("MCP SSE 响应超过 16 MiB 上限")
                if (line.isEmpty()) {
                    event()?.let { return@readCancellable it }
                } else if (line.startsWith("data:")) {
                    val value = sseDataValue(line)
                    if (data.length.toLong() + value.length + 1 > MAX_MCP_MESSAGE_BYTES) throw McpException("MCP SSE 事件超过 1 MiB 上限")
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
            }
            event() ?: throw McpException("MCP SSE 流中没有与请求 id 匹配的响应")
        }
    }

    private fun parseObject(body: String): JsonObject =
        runCatching { McpProtocol.json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw McpException("MCP 服务器返回的不是 JSON：${body.take(300)}") }

    private fun isResponseFor(message: JsonObject, id: JsonElement?): Boolean =
        id != null && message["id"] == id && message["method"] == null &&
            (message.containsKey("result") || message.containsKey("error"))

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
