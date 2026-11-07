package com.suixing.agent.agent

import com.suixing.agent.tools.ToolResult
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A bounded run-local ledger; hashes replace all argument and result text. No persistence or I/O. */
internal class RunLedger(maxRounds: Int = 0, capacity: Int = 96) {
    data class Entry(val step: Int, val tool: String, val argsHash: String, val ok: Boolean,
        val resultHash: String, val atMs: Long)
    private data class Repeated(val resultHash: String, var count: Int = 1, var notified: Int = 0)
    private val limit = capacity.coerceAtLeast(1)
    private val threshold = if (maxRounds <= 0) 5 else (maxRounds / 10).coerceAtLeast(3)
    private val entries = ArrayDeque<Entry>()
    private val repeated = LinkedHashMap<String, Repeated>()
    private val pollResults = LinkedHashMap<String, String>()
    private val reminders = ArrayDeque<String>()
    private var total = 0
    private var failed = 0

    @Synchronized
    fun record(step: Int, tool: String, args: JsonObject, result: ToolResult) {
        val argsHash = fingerprint { writeJson(this, args, root = true) }
        val resultHash = fingerprint {
            write(if (result.ok) "ok:" else "failed:")
            writeString(this, result.output)
            result.imageBase64?.let { writeString(this, it) }
        }
        entries.addLast(Entry(step, tool, argsHash, result.ok, resultHash, System.currentTimeMillis()))
        while (entries.size > limit) entries.removeFirst()
        total++
        if (!result.ok) failed++
        if (expectedPolling(tool, args)) {
            val previous = pollResults.put(tool, resultHash)
            if (previous != null && previous != resultHash) progress()
            while (pollResults.size > limit) pollResults.remove(pollResults.keys.first())
            return
        }
        val key = tool + ':' + argsHash
        val previous = repeated.remove(key)
        val value = if (previous == null || previous.resultHash != resultHash) {
            progress()
            Repeated(resultHash)
        } else previous.apply { count++ }
        repeated[key] = value
        while (repeated.size > limit) repeated.remove(repeated.keys.first())
        val level = when {
            value.count >= threshold * 2L -> 2
            value.count >= threshold -> 1
            else -> 0
        }
        if (level > value.notified) {
            value.notified = level
            reminders.addLast(if (level == 1) {
                "重复调用提醒：$tool 已出现 ${value.count} 次相同参数和相同结果。请核对是否有进展，考虑换一种方法；本提醒不会停止任务。"
            } else {
                "重复调用提醒升级：$tool 已出现 ${value.count} 次相同参数和相同结果。请避免继续重复；若无法推进，请说明阻塞点和已尝试的方法。任务仍可继续。"
            })
            while (reminders.size > 2) reminders.removeFirst()
        }
    }

    /** The engine appends each new reminder to the wire tail; reading consumes it once. */
    @Synchronized
    fun warning(): String? = reminders.removeFirstOrNull()

    @Synchronized
    fun summary(): String {
        val last = entries.lastOrNull() ?: return ""
        return "本次尝试 $total 次工具调用，其中 ${total - failed} 次成功、$failed 次失败；" +
            "最后一次为第 ${last.step} 步的 ${last.tool}（${if (last.ok) "成功" else "失败"}）。"
    }

    @Synchronized
    fun brief(): String {
        if (entries.isEmpty()) return ""
        val recent = entries.takeLast(4).joinToString("；") { "${it.step}. ${it.tool}：${if (it.ok) "成功" else "失败"}" }
        return "已尝试 $total 次工具调用，失败 $failed 次。最近记录：$recent。"
    }

    private fun progress() {
        repeated.values.forEach { it.count = 0; it.notified = 0 }
        reminders.clear()
    }

    private fun expectedPolling(tool: String, args: JsonObject): Boolean =
        tool in POLLING_TOOLS || tool.startsWith("list_") || tool.endsWith("_status") ||
            (tool == "ui_virtual_screen" && (args["action"] as? JsonPrimitive)?.contentOrNull in setOf("status", "screenshot"))

    private fun fingerprint(write: Writer.() -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        OutputStreamWriter(DigestOutputStream(DISCARD, digest), Charsets.UTF_8).use { writer -> write.invoke(writer) }
        val bytes = digest.digest()
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0xf])
            }
        }
    }

    /** Type/length prefixes avoid JSON escaping copies of large strings while keeping the hash canonical. */
    private fun writeJson(writer: Writer, value: JsonElement, root: Boolean = false) {
        when (value) {
            is JsonObject -> {
                writer.write('{'.code)
                value.keys.filterNot { root && it in NON_SEMANTIC_ARGS }.sorted().forEach { key ->
                    writeString(writer, key)
                    writeJson(writer, value.getValue(key))
                }
                writer.write('}'.code)
            }
            is JsonArray -> {
                writer.write('['.code)
                value.forEach { writeJson(writer, it) }
                writer.write(']'.code)
            }
            is JsonPrimitive -> {
                if (value.isString) writeString(writer, value.content)
                else {
                    writer.write('p'.code)
                    val normalized = value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toString() ?: value.content
                    writeString(writer, normalized)
                }
            }
        }
    }

    private fun writeString(writer: Writer, value: String) {
        writer.write('s'.code)
        writer.write(value.length.toString())
        writer.write(':'.code)
        writer.write(value)
    }

    private companion object {
        val NON_SEMANTIC_ARGS = setOf("timeout", "timeout_ms", "timeout_seconds", "deadline_ms", "wait_ms")
        const val HEX = "0123456789abcdef"
        val POLLING_TOOLS = setOf("wait_agents", "list_agents", "ui_dump", "ui_current_app", "ui_screenshot",
            "terminal_read", "todo_read", "goal_read", "goal_status")
        val DISCARD = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
    }
}
