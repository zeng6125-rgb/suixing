package com.suixing.agent.agent

import androidx.annotation.Keep
import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.ToolCallRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

internal object NativeWireTextJni {
    external fun nativeBuild(protocol: ByteArray, strings: NativeWireStringPool, count: Int): ByteArray?
}

/** String leaves are resolved on the calling thread; attachments never enter this pool. */
@Keep
internal class NativeWireStringPool(private val values: Array<String>, private val checkpoint: (() -> Unit)?) {
    @Keep
    fun get(index: Int): String {
        checkpoint?.invoke()
        if (index !in values.indices) throw IOException("原生上下文字符串引用无效")
        return values[index]
    }
}

internal data class NativeWireReplay(val messages: List<ChatMessage>, val counts: IntArray)

internal object NativeWireText {
    const val REPLAY_BATCH_SIZE = 1024
    private const val MAGIC = 0x31575853
    private const val MAX_PROTOCOL = 4 * 1024 * 1024
    private const val MAX_RESPONSE = 16 * 1024 * 1024
    private const val MAX_POOL = 65_536
    private const val MAX_SUMMARY_MESSAGES = 4096
    private const val MAX_CALLS = 65_536

    fun replay(sources: List<StoredMessage>, settings: AppSettings, checkpoint: (() -> Unit)?): NativeWireReplay? {
        if (!NativeCore.available || sources.size > REPLAY_BATCH_SIZE) return null
        val builder = Builder(1, sources.size)
        // Only JVM references are retained. Even large image/base64 Strings never cross JNI.
        val images = sources.map { it.allImages.takeIf { settings.imageInput }.orEmpty() }
        for ((index, message) in sources.withIndex()) {
            checkpoint?.invoke()
            val role = when (message.role) { Roles.USER -> 1; Roles.SYSTEM -> 2; Roles.ASSISTANT -> 3; Roles.TOOL -> 4; else -> 0 }
            val flags = (if (message.steer) 1 else 0) or (if (message.imageCount > 0) 2 else 0) or (if (images[index].isNotEmpty()) 4 else 0)
            if (!builder.integer(role) || !builder.integer(flags) || !builder.string(message.text) ||
                !builder.nullable(message.toolName) || !builder.nullable(message.toolCallId) || !builder.nullable(message.toolOutput)) return null
        }
        val built = builder.finish()
        val bytes = call(built, checkpoint) ?: return null
        val input = Reader(bytes, checkpoint)
        input.valid(input.integer() == MAGIC && input.integer() == 1 && input.integer() == sources.size)
        val rowCount = input.integer()
        input.valid(rowCount in 0..(sources.size * 2) && rowCount <= input.buffer.remaining() / 20)
        val output = ArrayList<ChatMessage>(rowCount)
        val counts = IntArray(sources.size)
        var previous = -1
        repeat(rowCount) {
            checkpoint?.invoke()
            val source = input.integer()
            val kind = input.integer()
            val id = input.integer()
            input.valid(source in sources.indices && source >= previous && kind in 1..5)
            val text = when (input.integer()) {
                0 -> built.string(input.integer())
                1 -> input.string()
                else -> throw IOException("原生上下文正文类型无效")
            }
            val message = sources[source]
            input.valid(if (kind == 4) id in built.strings.indices else id == -1)
            val position = counts[source]++
            output += when (kind) {
                1 -> {
                    input.valid((message.role == Roles.USER && position == 0) ||
                        (message.role == Roles.TOOL && position == 1 && images[source].isEmpty() && message.imageCount > 0))
                    ChatMessage.user(text, if (message.role == Roles.USER) images[source] else emptyList())
                }
                2 -> { input.valid(message.role == Roles.SYSTEM && position == 0); ChatMessage.system(text) }
                3 -> {
                    input.valid(message.role == Roles.ASSISTANT && position == 0)
                    ChatMessage.assistant(text, message.toolCalls.map { ToolCallRequest(it.id, it.name, it.arguments) })
                }
                4 -> { input.valid(message.role == Roles.TOOL && position == 0); ChatMessage.tool(built.string(id), text) }
                else -> {
                    input.valid(message.role == Roles.TOOL && position == 1 && images[source].isNotEmpty())
                    ChatMessage.screenshot(text, images[source])
                }
            }
            previous = source
        }
        input.valid(!input.buffer.hasRemaining())
        sources.forEachIndexed { index, message ->
            val expected = when (message.role) { Roles.TOOL -> if (message.imageCount > 0) 2 else 1; Roles.USER, Roles.SYSTEM, Roles.ASSISTANT -> 1; else -> 0 }
            input.valid(counts[index] == expected)
        }
        checkpoint?.invoke()
        return NativeWireReplay(output, counts)
    }

    fun summary(sources: List<ChatMessage>, checkpoint: (() -> Unit)?): String? {
        if (!NativeCore.available || sources.size > MAX_SUMMARY_MESSAGES) return null
        val builder = Builder(2, sources.size)
        var calls = 0L
        for (message in sources) {
            checkpoint?.invoke()
            calls += message.toolCalls.size
            if (calls > MAX_CALLS || !builder.string(message.role) || !builder.string(message.text) ||
                !builder.integer(if (message.imageCount > 0) 1 else 0) || !builder.integer(message.toolCalls.size)) return null
            for (call in message.toolCalls) {
                checkpoint?.invoke()
                if (!builder.string(call.name) || !builder.string(call.arguments)) return null
            }
        }
        val built = builder.finish()
        val bytes = call(built, checkpoint) ?: return null
        val input = Reader(bytes, checkpoint)
        input.valid(input.integer() == MAGIC && input.integer() == 2 && input.integer() == sources.size)
        val result = input.string()
        input.valid(!input.buffer.hasRemaining())
        checkpoint?.invoke()
        return result
    }

    private fun call(built: Built, checkpoint: (() -> Unit)?): ByteArray? {
        checkpoint?.invoke()
        val response = try { NativeWireTextJni.nativeBuild(built.protocol, NativeWireStringPool(built.strings, checkpoint), built.strings.size) }
            catch (_: UnsatisfiedLinkError) { return null }
        checkpoint?.invoke()
        return response
    }

    private data class Built(val protocol: ByteArray, val strings: Array<String>) {
        fun string(index: Int): String = strings.getOrNull(index) ?: throw IOException("原生上下文结果引用无效")
    }
    private class Builder(mode: Int, count: Int) {
        private val output = ByteArrayOutputStream()
        private val strings = ArrayList<String>()
        private val identities = IdentityHashMap<String, Int>()
        init { integer(MAGIC); integer(mode); integer(0); integer(count); reference("") }
        fun integer(value: Int): Boolean {
            if (output.size() > MAX_PROTOCOL - 4) return false
            repeat(4) { output.write(value ushr (it * 8) and 0xff) }
            return true
        }
        private fun reference(value: String): Int? = identities[value] ?: run {
            if (strings.size >= MAX_POOL) return null
            strings.size.also { strings += value; identities[value] = it }
        }
        fun string(value: String): Boolean = reference(value)?.let { integer(it) } ?: false
        fun nullable(value: String?): Boolean = if (value == null) integer(-1) else string(value)
        fun finish(): Built {
            val bytes = output.toByteArray()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, strings.size)
            return Built(bytes, strings.toTypedArray())
        }
    }
    private class Reader(bytes: ByteArray, private val checkpoint: (() -> Unit)?) {
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        init { valid(bytes.size in 16..MAX_RESPONSE) }
        fun valid(ok: Boolean) { if (!ok) throw IOException("原生上下文结果格式无效") }
        fun integer(): Int { valid(buffer.remaining() >= 4); return buffer.int }
        fun string(): String {
            val units = integer()
            valid(units >= 0 && units.toLong() * 2 <= buffer.remaining())
            val text = CharArray(units) { index ->
                if ((index and 8191) == 0) checkpoint?.invoke()
                buffer.short.toInt().and(0xffff).toChar()
            }
            checkpoint?.invoke()
            return text.concatToString()
        }
    }
}
