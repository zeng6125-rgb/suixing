package com.suixing.agent.agent

import com.suixing.agent.core.NativeCore
import com.suixing.agent.tools.ToolResult
import java.io.ByteArrayOutputStream
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
    private var nativeHandle = NativeRunLedger.create(maxRounds, limit)
    private val toolIds = HashMap<String, Int>()
    private val toolNames = ArrayList<String>()
    private val entries = ArrayDeque<Entry>()
    private val repeated = LinkedHashMap<String, Repeated>()
    private val pollResults = LinkedHashMap<String, String>()
    private val reminders = ArrayDeque<String>()
    private var total = 0
    private var failed = 0

    @Synchronized
    fun record(step: Int, tool: String, args: JsonObject, result: ToolResult) {
        val argsBytes = argsDigest(args)
        val handle = nativeHandle
        if (handle > 0L) {
            val id = toolIds.getOrPut(tool) { toolNames.size.also { toolNames.add(tool) } }
            val polling = expectedPolling(tool, args)
            val isJobList = tool == "job_list"
            val atMs = System.currentTimeMillis()
            var status = NativeRunLedger.record(
                handle = handle,
                step = step,
                toolId = id,
                polling = polling,
                argsHash = argsBytes,
                ok = result.ok,
                output = result.output,
                imageBase64 = result.imageBase64,
                isJobList = isJobList,
                atMs = atMs,
            )
            if (status == 4 && isJobList) {
                status = NativeRunLedger.record(
                    handle = handle,
                    step = step,
                    toolId = id,
                    polling = polling,
                    argsHash = argsBytes,
                    ok = result.ok,
                    output = JOB_LIST_ELAPSED.replace(result.output, "$1"),
                    imageBase64 = result.imageBase64,
                    isJobList = false,
                    atMs = atMs,
                )
            }
            if (status == 0) return
            // Native handle failure degrades safely for any future writes without breaking the run.
            NativeRunLedger.release(handle)
            nativeHandle = 0L
        }
        val argsHash = toHex(argsBytes)
        val resultHash = fingerprint {
            write(if (result.ok) "ok:" else "failed:")
            writeString(if (tool == "job_list") {
                JOB_LIST_ELAPSED.replace(result.output, "$1")
            } else result.output)
            result.imageBase64?.let { writeString(it) }
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
            reminders.addLast(formatReminder(tool, level, value.count))
            while (reminders.size > 2) reminders.removeFirst()
        }
    }

    /** The engine appends each new reminder to the wire tail; reading consumes it once. */
    @Synchronized
    fun warning(): String? {
        val handle = nativeHandle
        if (handle > 0L) {
            val warning = NativeRunLedger.popWarning(handle) ?: return null
            val tool = toolNames.getOrNull(warning.toolId) ?: return null
            return formatReminder(tool, warning.level, warning.count)
        }
        return reminders.removeFirstOrNull()
    }

    @Synchronized
    fun summary(): String {
        val handle = nativeHandle
        if (handle > 0L) {
            val summary = NativeRunLedger.summary(handle) ?: return ""
            val last = summary.recent.lastOrNull() ?: return ""
            val tool = toolNames.getOrNull(last.toolId).orEmpty()
            return "本次尝试 ${summary.total} 次工具调用，其中 ${summary.total - summary.failed} 次成功、${summary.failed} 次失败；" +
                "最后一次为第 ${last.step} 步的 $tool（${if (last.ok) "成功" else "失败"}）。"
        }
        val last = entries.lastOrNull() ?: return ""
        return "本次尝试 $total 次工具调用，其中 ${total - failed} 次成功、$failed 次失败；" +
            "最后一次为第 ${last.step} 步的 ${last.tool}（${if (last.ok) "成功" else "失败"}）。"
    }

    @Synchronized
    fun brief(): String {
        val handle = nativeHandle
        if (handle > 0L) {
            val summary = NativeRunLedger.summary(handle) ?: return ""
            if (summary.recent.isEmpty()) return ""
            val recent = summary.recent.joinToString("；") { entry ->
                val tool = toolNames.getOrNull(entry.toolId).orEmpty()
                "${entry.step}. $tool：${if (entry.ok) "成功" else "失败"}"
            }
            return "已尝试 ${summary.total} 次工具调用，失败 ${summary.failed} 次。最近记录：$recent。"
        }
        if (entries.isEmpty()) return ""
        val recent = entries.takeLast(4).joinToString("；") { "${it.step}. ${it.tool}：${if (it.ok) "成功" else "失败"}" }
        return "已尝试 $total 次工具调用，失败 $failed 次。最近记录：$recent。"
    }

    @Synchronized
    fun release() {
        val handle = nativeHandle
        if (handle > 0L) {
            nativeHandle = 0L
            NativeRunLedger.release(handle)
        }
    }

    private fun progress() {
        repeated.values.forEach { it.count = 0; it.notified = 0 }
        reminders.clear()
    }

    private fun expectedPolling(tool: String, args: JsonObject): Boolean =
        tool in POLLING_TOOLS || tool.startsWith("list_") || tool.endsWith("_status") ||
            (tool == "ui_virtual_screen" && (args["action"] as? JsonPrimitive)?.contentOrNull in setOf("status", "screenshot"))

    private fun argsDigest(args: JsonObject): ByteArray {
        if (args.isEmpty()) return EMPTY_OBJECT_SHA256
        val buffer = ByteArrayOutputStream(256)
        writeJson(buffer, args, root = true)
        return NativeCore.sha256Digest(buffer.toByteArray())
    }

    private fun fingerprint(write: ByteArrayOutputStream.() -> Unit): String {
        val buffer = ByteArrayOutputStream(256)
        write.invoke(buffer)
        return toHex(NativeCore.sha256Digest(buffer.toByteArray()))
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0xf])
        }
    }

    /** Type/length prefixes avoid JSON escaping copies of large strings while keeping the hash canonical. */
    private fun writeJson(out: ByteArrayOutputStream, value: JsonElement, root: Boolean = false) {
        when (value) {
            is JsonObject -> {
                out.write('{'.code)
                value.keys.filterNot { root && it in NON_SEMANTIC_ARGS }.sorted().forEach { key ->
                    out.writeString(key)
                    writeJson(out, value.getValue(key))
                }
                out.write('}'.code)
            }
            is JsonArray -> {
                out.write('['.code)
                value.forEach { writeJson(out, it) }
                out.write(']'.code)
            }
            is JsonPrimitive -> {
                if (value.isString) out.writeString(value.content)
                else {
                    out.write('p'.code)
                    val normalized = value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toString() ?: value.content
                    out.writeString(normalized)
                }
            }
        }
    }

    private fun ByteArrayOutputStream.write(ascii: String) {
        for (i in 0 until ascii.length) write(ascii[i].code)
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        write('s'.code)
        write(value.length.toString())
        write(':'.code)
        var asciiOnly = true
        for (i in 0 until value.length) {
            if (value[i].code >= 0x80) {
                asciiOnly = false
                break
            }
        }
        if (asciiOnly) {
            for (i in 0 until value.length) write(value[i].code)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            write(bytes, 0, bytes.size)
        }
    }

    private fun formatReminder(tool: String, level: Int, count: Int): String = if (level == 1) {
        "重复调用提醒：$tool 已出现 $count 次相同参数和相同结果。请核对是否有进展，考虑换一种方法；本提醒不会停止任务。"
    } else {
        "重复调用提醒升级：$tool 已出现 $count 次相同参数和相同结果。请避免继续重复；若无法推进，请说明阻塞点和已尝试的方法。任务仍可继续。"
    }

    private companion object {
        val NON_SEMANTIC_ARGS = setOf("timeout", "timeout_ms", "timeout_seconds", "deadline_ms", "wait_ms")
        const val HEX = "0123456789abcdef"
        val POLLING_TOOLS = setOf("wait_agents", "list_agents", "ui_dump", "ui_current_app", "ui_screenshot",
            "terminal_read", "todo_read", "goal_read", "goal_status", "job_list")
        val JOB_LIST_ELAPSED = Regex("""(?m)^(- \S+ \[(?:running|done|failed)\]) \d+\.\ds\b""")
        val EMPTY_OBJECT_SHA256 = NativeCore.sha256Digest(byteArrayOf('{'.code.toByte(), '}'.code.toByte()))
    }
}
