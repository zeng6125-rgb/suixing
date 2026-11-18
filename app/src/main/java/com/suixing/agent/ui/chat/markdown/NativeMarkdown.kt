package com.suixing.agent.ui.chat.markdown

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object NativeMarkdownJni {
    external fun nativeCreate(): Long
    external fun nativeCancel(handle: Long)
    external fun nativeRelease(handle: Long)
    external fun nativeParse(handle: Long, request: ByteArray): ByteArray
    external fun nativeRuntime(op: Int, source: String, from: Int, parameter: Int, flag: Boolean): ByteArray?
}

internal data class NativeInlineRun(val text: String, val style: Int, val link: String?)
internal data class NativeMarkdownTail(val blocks: List<MdBlock>, val stableEnd: Int, val stableCount: Int, val continuation: AppendableMarkdownTail?)
internal data class NativeTableAppend(val lastLine: Int, val completed: List<List<String>>, val partial: List<String>?)

/** Shared cold and streaming parsers; Kotlin retains presentation objects and completed prefixes. */
internal object NativeMarkdown {
    const val BATCH_SIZE = 16
    private const val MAGIC = 0x314D5853
    private const val MAX_DOCUMENT_BYTES = 512 * 1024
    private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    private const val MAX_NODES = 50_000
    private val available: Boolean by lazy {
        try { System.loadLibrary("suixing_search"); true }
        catch (_: UnsatisfiedLinkError) { false }
        catch (_: SecurityException) { false }
    }
    val runtimeAvailable: Boolean get() = available
    private fun runtime(op: Int, source: String, from: Int = 0, parameter: Int = 0, flag: Boolean = false): Reader? {
        if (!available) return null
        val bytes = NativeMarkdownJni.nativeRuntime(op, source, from, parameter, flag) ?: return null
        return Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }
    fun tail(source: String, from: Int): NativeMarkdownTail? {
        val reader = runtime(1, source, from) ?: return null
        val stable = reader.integer(); val count = reader.integer(); val blocks = reader.blocks(0)
        val continuation = when (reader.integer()) {
            1 -> AppendableMarkdownTail.Paragraph(from + reader.integer(), reader.string())
            2 -> {
                val body = from + reader.integer(); val last = from + reader.integer(); val bodyLine = reader.integer() != 0
                AppendableMarkdownTail.Code((blocks.last() as MdBlock.Code).language, body, last, bodyLine)
            }
            3 -> {
                val last = from + reader.integer(); val partial = reader.integer() != 0; val table = blocks.last() as MdBlock.Table
                AppendableMarkdownTail.Table(table.header, if (partial) table.rows.dropLast(1) else table.rows, last, if (partial) table.rows.last() else null)
            }
            else -> null
        }
        return NativeMarkdownTail(blocks, stable, count, continuation)
    }
    fun parseInline(source: String): List<NativeInlineRun>? {
        val reader = runtime(2, source) ?: return null
        return List(reader.count()) { NativeInlineRun(reader.string(), reader.integer(), reader.string().takeIf { it.isNotEmpty() }) }
    }
    fun imageSegments(source: String, streaming: Boolean): List<MessageSegment>? {
        val reader = runtime(3, source, flag = streaming) ?: return null
        val result = ArrayList<MessageSegment>(); var plain = 0
        repeat(reader.count()) {
            val start = reader.integer(); val end = reader.integer(); val alt = reader.integer(); val altLength = reader.integer(); val url = reader.integer(); val urlLength = reader.integer()
            if (plain < start) result += MessageSegment.Text(source.substring(plain, start))
            result += MessageSegment.Image(source.substring(alt, alt + altLength), source.substring(url, url + urlLength)); plain = end
        }
        if (plain < source.length) result += MessageSegment.Text(source.substring(plain))
        return result
    }
    fun paragraphContinues(source: String, previous: Int): Boolean = runtime(4, source, previous) != null
    fun codeAppend(source: String, from: Int, previous: Int, bodyLine: Boolean): IntArray? {
        val scanFrom = if (bodyLine) previous else from
        val reader = runtime(5, source, scanFrom, previous - scanFrom, bodyLine) ?: return null
        val relative = reader.integer(); val nextBody = reader.integer()
        return intArrayOf(if (bodyLine && relative == 0) from else scanFrom + relative, nextBody)
    }
    fun tableAppend(source: String, from: Int, columns: Int, previousLength: Int, previousCells: List<String>?): NativeTableAppend? {
        val completedRow = previousCells?.takeIf { source.getOrNull(previousLength) == '\n' }
        val scanFrom = if (completedRow == null) from else previousLength + 1
        val reader = runtime(6, source, scanFrom, columns) ?: return null
        val last = scanFrom + reader.integer()
        val count = reader.count()
        val completed = ArrayList<List<String>>(count + if (completedRow == null) 0 else 1)
        if (completedRow != null) completed.add(completedRow)
        repeat(count) { completed.add(List(reader.count()) { reader.string() }) }
        val partial = if (reader.integer() == 0) null else List(reader.count()) { reader.string() }
        return NativeTableAppend(last, completed, partial)
    }

    private data class InlineEntry(val runs: List<NativeInlineRun>, val weight: Long)
    private val inlineLock = Any()
    private val inline = LinkedHashMap<String, InlineEntry>(16, 0.75f, true)
    private var inlineWeight = 0L

    fun inlineRuns(text: String): List<NativeInlineRun>? = synchronized(inlineLock) { inline[text]?.runs }

    private fun rememberInline(text: String, runs: List<NativeInlineRun>) {
        val weight = text.length * 2L + runs.sumOf { 40L + 2L * it.text.length + 2L * (it.link?.length ?: 0) }
        if (weight > 512L * 1024) return
        synchronized(inlineLock) {
            inline.remove(text)?.let { inlineWeight -= it.weight }
            inline[text] = InlineEntry(runs, weight)
            inlineWeight += weight
            while (inline.size > 512 || inlineWeight > 512L * 1024) {
                val iterator = inline.entries.iterator()
                inlineWeight -= iterator.next().value.weight
                iterator.remove()
            }
        }
    }

    /** Omitted keys request the existing Kotlin parser for that complete document. */
    suspend fun parseBatch(texts: List<String>): Map<String, List<MdBlock>> {
        if (!available || texts.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, List<MdBlock>>()
        for (batch in texts.chunked(BATCH_SIZE)) {
            val encoded = batch.mapNotNull { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (bytes.size <= MAX_DOCUMENT_BYTES && bytes.toString(Charsets.UTF_8) == text) text to bytes else null
            }
            // Split by bytes as well as count, so sixteen large documents cannot exceed JNI bounds.
            var from = 0
            while (from < encoded.size) {
                var to = from
                var weight = 8L
                while (to < encoded.size && weight + 4 + encoded[to].second.size <= MAX_REQUEST_BYTES) {
                    weight += 4 + encoded[to].second.size
                    to++
                }
                val candidates = encoded.subList(from, to)
                val request = ByteArrayOutputStream(weight.toInt())
                fun integer(value: Int) { repeat(4) { request.write(value ushr (8 * it) and 0xff) } }
                integer(MAGIC); integer(candidates.size)
                candidates.forEach { (_, bytes) -> integer(bytes.size); request.write(bytes) }
                result.putAll(call(request.toByteArray(), candidates.map { it.first }))
                from = to
            }
        }
        return result
    }

    private suspend fun call(request: ByteArray, sources: List<String>): Map<String, List<MdBlock>> =
        suspendCancellableCoroutine { continuation ->
            val handle = NativeMarkdownJni.nativeCreate()
            if (handle <= 0) {
                continuation.resumeWithException(IOException("无法创建原生 Markdown 任务"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { NativeMarkdownJni.nativeCancel(handle) }
            try {
                if (continuation.isActive) continuation.resume(decode(NativeMarkdownJni.nativeParse(handle, request), sources))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                NativeMarkdownJni.nativeRelease(handle)
            }
        }

    private fun decode(bytes: ByteArray, sources: List<String>): Map<String, List<MdBlock>> {
        if (bytes.size !in 8..MAX_RESPONSE_BYTES) throw IOException("原生 Markdown 结果大小无效")
        val reader = Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        reader.valid(reader.integer() == MAGIC && reader.integer() == sources.size)
        val result = LinkedHashMap<String, List<MdBlock>>()
        for (source in sources) {
            val status = reader.integer()
            reader.valid(status == 0 || status == 4)
            if (status == 4) continue
            val length = reader.integer()
            reader.valid(length >= 0 && length <= reader.input.remaining())
            val end = reader.input.position() + length
            val document = reader.input.slice().order(ByteOrder.LITTLE_ENDIAN).also { it.limit(length) }
            val parsed = Reader(document)
            val blocks = parsed.blocks(0)
            val inlineCount = parsed.count()
            val preparedInline = ArrayList<Pair<String, List<NativeInlineRun>>>(inlineCount)
            repeat(inlineCount) {
                val text = parsed.string()
                val runs = List(parsed.count()) {
                    val display = parsed.string()
                    val style = parsed.integer()
                    parsed.valid(style in 0..63 && !(style and 4 != 0 && style and 16 != 0))
                    val link = parsed.string().takeIf { it.isNotEmpty() }
                    NativeInlineRun(display, style, link)
                }
                preparedInline += text to runs
            }
            parsed.valid(!document.hasRemaining())
            preparedInline.forEach { (text, runs) -> rememberInline(text, runs) }
            reader.input.position(end)
            result[source] = blocks
        }
        reader.valid(!reader.input.hasRemaining())
        return result
    }

    private class Reader(val input: ByteBuffer) {
        private var nodes = 0
        fun valid(condition: Boolean) { if (!condition) throw IOException("原生 Markdown 结果格式无效") }
        fun integer(): Int { valid(input.remaining() >= 4); return input.int }
        fun count(): Int = integer().also { valid(it in 0..MAX_NODES && it <= input.remaining() / 4) }
        fun string(): String {
            val size = integer()
            valid(size >= 0 && size <= input.remaining())
            val bytes = ByteArray(size)
            input.get(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
        fun blocks(depth: Int): List<MdBlock> { valid(depth <= 8); return List(count()) { block(depth) } }
        private fun block(depth: Int): MdBlock {
            nodes++
            valid(nodes <= MAX_NODES)
            return when (integer()) {
                1 -> MdBlock.Heading(integer().also { valid(it in 1..6) }, string())
                2 -> MdBlock.Paragraph(string())
                3 -> {
                    val ordered = integer().also { valid(it in 0..1) } == 1
                    val nesting = integer().also { valid(it in 0..MAX_LIST_DEPTH) }
                    val start = integer().also { valid(it in 0..MAX_LIST_START) }
                    val items = List(count()) { string() }
                    val children = LinkedHashMap<Int, List<MdBlock.Bullet>>()
                    repeat(count()) {
                        val index = integer().also { valid(it in items.indices && it !in children) }
                        children[index] = blocks(depth + 1).map { valid(it is MdBlock.Bullet); it as MdBlock.Bullet }
                    }
                    MdBlock.Bullet(items, ordered, nesting, start, children)
                }
                4 -> MdBlock.Code(string(), string())
                5 -> MdBlock.Quote(string())
                6 -> MdBlock.Rule
                7 -> MdBlock.Table(List(count()) { string() }, List(count()) { List(count()) { string() } })
                else -> throw IOException("原生 Markdown 块类型无效")
            }
        }
    }
}
