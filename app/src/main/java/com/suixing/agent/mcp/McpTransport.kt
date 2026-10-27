package com.suixing.agent.mcp

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

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val stderrTail = ArrayDeque<String>()
    private val writeLock = Any()

    /**
     * Only for reaping the child process: [close] has to return at once because it is called from
     * the UI, while a server that ignores SIGTERM can take a moment to die.
     */
    private val reaper = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (process != null) return@withContext
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
        Thread({ readLoop(started) }, "mcp-stdio-out").apply {
            isDaemon = true
            start()
        }
        Thread({ errorLoop(started) }, "mcp-stdio-err").apply {
            isDaemon = true
            start()
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
        val started = process
        process = null
        writer = null
        failAll("MCP 传输已关闭")
        // The wait does not belong on the caller's thread: `close()` is called from the UI when a
        // server is removed or switched off, and a stdio server that ignores SIGTERM would hold
        // the frame for the whole grace period. Reaping moves to IO — the caller only needs the
        // handles gone, which has already happened above.
        reaper.launch {
            val running = started ?: return@launch
            runCatching {
                running.destroy()
                if (!running.waitFor(PROCESS_GRACE_MS, TimeUnit.MILLISECONDS)) running.destroyForcibly()
            }
            runCatching { running.inputStream.close() }
            runCatching { running.errorStream.close() }
            runCatching { running.outputStream.close() }
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
                val id = (message["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                pending.remove(id)?.complete(message)
            }
            }
        } catch (_: Exception) {
            // The stream ends when the process dies; the finally below reports it.
        } finally {
            failAll("MCP 进程已退出：$label${stderrHint()}")
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
            val body = post(message, timeoutMs)
            if (body.isBlank()) throw McpException("MCP 服务器返回了空响应：$url")
            parse(body)
        }

    override suspend fun notify(message: JsonObject) {
        withContext(Dispatchers.IO) { post(message, McpTransport.DEFAULT_TIMEOUT_MS) }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun post(message: JsonObject, timeoutMs: Long): String {
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
        val response = try {
            callClient.newCall(request).execute()
        } catch (error: Exception) {
            throw McpException("请求 MCP 服务器失败：${error.message ?: error::class.java.simpleName}")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw McpException("MCP 服务器返回 HTTP ${it.code}：${text.take(300)}")
            }
            return text
        }
    }

    private fun parse(body: String): JsonObject {
        val trimmed = body.trim()
        val looksLikeSse = trimmed.startsWith("event:") || trimmed.startsWith("data:")
        if (looksLikeSse) {
            for (line in trimmed.lineSequence()) {
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val parsed = runCatching {
                    McpProtocol.json.parseToJsonElement(payload).jsonObject
                }.getOrNull() ?: continue
                return parsed
            }
            throw McpException("MCP 服务器返回的 SSE 流里没有 JSON-RPC 消息：${trimmed.take(300)}")
        }
        return runCatching { McpProtocol.json.parseToJsonElement(trimmed).jsonObject }
            .getOrElse { throw McpException("MCP 服务器返回的不是 JSON：${trimmed.take(300)}") }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
