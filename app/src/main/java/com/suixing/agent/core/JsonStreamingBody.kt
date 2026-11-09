package com.suixing.agent.core

import androidx.annotation.Keep
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.buffer

/** OkHttp uploads run on its own worker; unchecked cancellation must not escape that worker. */
internal fun CoroutineContext.ensureIoActive() {
    try { ensureActive() } catch (cancelled: CancellationException) {
        throw IOException("Request upload cancelled", cancelled)
    }
}

/** Identity binding prevents user text from being interpreted as an image placeholder. */
internal class WireStringParts {
    private val values = IdentityHashMap<JsonPrimitive, Array<String>>()
    fun imageDataUri(image: String): JsonPrimitive = JsonPrimitive("").also {
        values[it] = arrayOf("data:image/jpeg;base64,", image)
    }
    fun get(value: JsonPrimitive): Array<String>? = values[value]
    fun snapshot(): WireStringParts = WireStringParts().also { copy ->
        values.forEach { (primitive, parts) -> copy.values[primitive] = parts.copyOf() }
    }
}

@Keep
internal class NativeJsonSink(private val sink: BufferedSink, private val context: CoroutineContext) {
    @Keep
    fun writeBytes(bytes: ByteArray, count: Int) {
        context.ensureIoActive()
        if (count !in 0..bytes.size) throw IOException("Invalid native JSON chunk")
        sink.write(bytes, 0, count)
    }
}

/** No complete JSON String or request ByteArray; one scratch buffer is reused for each write. */
internal class JsonStreamingBody(
    document: JsonElement,
    strings: WireStringParts = WireStringParts(),
    private val mediaType: MediaType? = null,
    private val context: CoroutineContext = EmptyCoroutineContext,
) : RequestBody() {
    // Containers are copied; immutable Strings and primitive identities (image bindings) are shared.
    private val document: JsonElement = snapshotDocument(document)
    private val strings: WireStringParts = strings.snapshot()
    private data class Size(val bytes: Long, val chars: Long) {
        operator fun plus(other: Size) = Size(Math.addExact(bytes, other.bytes), Math.addExact(chars, other.chars))
    }
    private val native: NativeJsonDocument? by lazy { NativeJsonDocument.prepare(document, strings, context) }
    private val size: Size by lazy {
        native?.let { Size(it.byteLength, it.characterLength) } ?: measure(document)
    }
    val characterLength: Long get() = size.chars
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = size.bytes

    private fun stringSize(text: String): Size {
        context.ensureIoActive()
        if (NativeCore.available) {
            val values = NativeCoreJni.nativeMeasureString(text)
            if (values.size != 2 || values[0] < 2 || values[1] < 2) throw IOException("原生 JSON 字符串长度无效")
            return Size(values[0], values[1])
        }
        val quoted = JsonPrimitive(text).toString()
        return Size(quoted.toByteArray(Charsets.UTF_8).size.toLong(), quoted.length.toLong())
    }
    private fun primitiveSize(value: JsonPrimitive): Size {
        if (!value.isString) {
            val raw = value.toString()
            return Size(raw.toByteArray(Charsets.UTF_8).size.toLong(), raw.length.toLong())
        }
        val parts = strings.get(value) ?: return stringSize(value.content)
        var result = Size(2, 2)
        for (part in parts) {
            val measured = stringSize(part)
            result += Size(measured.bytes - 2, measured.chars - 2)
        }
        return result
    }
    private fun measure(value: JsonElement): Size {
        context.ensureIoActive()
        return when (value) {
            is JsonPrimitive -> primitiveSize(value)
            is JsonArray -> {
                var size = Size(2L + maxOf(0, value.size - 1), 2L + maxOf(0, value.size - 1))
                for (item in value) size += measure(item)
                size
            }
            is JsonObject -> {
                var size = Size(2L + maxOf(0, value.size - 1), 2L + maxOf(0, value.size - 1))
                for ((key, item) in value) size += stringSize(key) + Size(1, 1) + measure(item)
                size
            }
        }
    }
    override fun writeTo(sink: BufferedSink) {
        context.ensureIoActive()
        val scratch = ByteArray(32768)
        val nativeSink = NativeJsonSink(sink, context)
        native?.let {
            it.write(nativeSink, scratch)
            context.ensureIoActive()
            return
        }
        fun string(text: String, quoted: Boolean = true) {
            context.ensureIoActive()
            if (NativeCore.available) {
                NativeCoreJni.nativeWriteString(text, nativeSink, scratch, quoted)
            } else {
                val encoded = JsonPrimitive(text).toString()
                sink.writeUtf8(if (quoted) encoded else encoded.substring(1, encoded.length - 1))
            }
        }
        fun value(item: JsonElement) {
            context.ensureIoActive()
            when (item) {
                is JsonPrimitive -> {
                    if (!item.isString) sink.writeUtf8(item.toString()) else {
                        val parts = strings.get(item)
                        if (parts == null) string(item.content) else {
                            sink.writeByte('"'.code)
                            for (part in parts) string(part, false)
                            sink.writeByte('"'.code)
                        }
                    }
                }
                is JsonArray -> {
                    sink.writeByte('['.code)
                    for (index in item.indices) {
                        if (index > 0) sink.writeByte(','.code)
                        value(item[index])
                    }
                    sink.writeByte(']'.code)
                }
                is JsonObject -> {
                    sink.writeByte('{'.code)
                    var first = true
                    for ((key, child) in item) {
                        if (!first) sink.writeByte(','.code)
                        first = false
                        string(key)
                        sink.writeByte(':'.code)
                        value(child)
                    }
                    sink.writeByte('}'.code)
                }
            }
        }
        value(document)
    }
    private fun snapshotDocument(value: JsonElement): JsonElement {
        context.ensureIoActive()
        return when (value) {
            is JsonPrimitive -> value
            is JsonArray -> JsonArray(java.util.Collections.unmodifiableList(value.map { snapshotDocument(it) }))
            is JsonObject -> JsonObject(java.util.Collections.unmodifiableMap(
                value.entries.associateTo(LinkedHashMap()) { (key, child) -> key to snapshotDocument(child) }))
        }
    }
    /** The existing opt-in diagnostic path materializes only a bounded preview. */
    fun preview(maxChars: Int): String {
        if (maxChars <= 0) return ""
        val buffer = Buffer()
        val quota = maxChars.toLong() * 3 + 4
        val stop = object : IOException() {}
        val limiter = object : okio.Sink {
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
            override fun flush() = Unit
            override fun write(source: Buffer, byteCount: Long) {
                val kept = minOf(byteCount, quota - buffer.size)
                if (kept > 0) buffer.write(source, kept)
                if (kept != byteCount) throw stop
            }
        }
        val sink = limiter.buffer()
        try { writeTo(sink); sink.flush() } catch (error: IOException) { if (error !== stop) throw error }
        return buffer.readUtf8().take(maxChars)
    }
}
