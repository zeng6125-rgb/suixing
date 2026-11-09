package com.suixing.agent.data

import com.suixing.agent.core.NativeCore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import okio.Buffer

internal object NativeUsageAggregationJni {
    /** Metadata only. Null means a complete operation must use its original Kotlin fold. */
    external fun nativeAggregate(request: ByteArray): ByteArray?
}

internal data class UsageMergeResult(
    val days: Map<String, TokenTally>,
    val models: Map<String, TokenTally>,
    val dailyModels: Map<String, Map<String, UsageBucket>>,
    val newest: Long,
)

/**
 * Bounded pure aggregation. Strings cross as exact UTF-16 keys; transcript bodies and images
 * never enter this protocol. Kotlin retains calendar dates, generations, watermarks and commits.
 */
internal object NativeUsageAggregation {
    private const val MAGIC = 0x31415855
    private const val MERGE = 1
    private const val SNAPSHOT = 2
    private const val WINDOW = 3
    private const val TOTALS = 4
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ROWS = 262_144
    private const val MAX_KEYS = 65_536

    fun merge(
        previous: ChatUsage, messages: Sequence<UsageProjectedMessage>, zone: ZoneId,
    ): UsageMergeResult? = attempt(MERGE, { request ->
        request.integer(request.key(UNKNOWN_MODEL_LABEL))
        request.long(previous.scannedUpTo)
        request.tallyMap(previous.days)
        request.tallyMap(previous.models)
        request.dailyModels(previous.dailyModels)
        val rows = Buffer()
        var count = 0
        for (message in messages) {
            request.charge(1)
            if (++count > MAX_ROWS || rows.size + 32L > MAX_BYTES) throw MetadataLimit()
            rows.writeIntLe(request.key(dayKey(message.timestamp, zone)))
            rows.writeIntLe(request.key(message.model))
            rows.writeLongLe(message.timestamp)
            rows.writeIntLe(message.promptTokens)
            rows.writeIntLe(message.completionTokens)
            rows.writeIntLe(message.reasoningTokens)
            rows.writeIntLe(message.cachedTokens)
        }
        request.integer(count)
        request.append(rows)
    }) { response ->
        UsageMergeResult(response.tallyMap(), response.tallyMap(), response.dailyModels(), response.long())
    }

    fun aggregate(chats: Collection<ChatUsage>): UsageAggregate? = attempt(SNAPSHOT, { request ->
        request.count(chats.size)
        for (chat in chats) {
            request.integer(if (chat.deletedAt > 0L) 1 else 0)
            // Old caches may contain independent maps; do not reconstruct them from dailyModels.
            request.tallyMap(chat.days)
            request.tallyMap(chat.models)
            request.dailyModels(chat.dailyModels)
        }
    }) { response ->
        UsageAggregate(response.tallyMap(), response.tallyMap(), response.tally(),
            response.bucket(), response.bucket())
    }

    fun window(chats: Collection<ChatUsage>, dates: List<String>): UsageWindow? =
        attempt(WINDOW, { request ->
            request.count(dates.size)
            dates.forEach { request.integer(request.key(it)) }
            request.count(chats.size)
            chats.forEach { request.dailyModels(it.dailyModels) }
        }) { response ->
            val days = List(response.count(52)) {
                val date = response.key()
                val bucket = response.bucket()
                DayUsage(date, bucket.tokens, bucket.reported, bucket.unreported)
            }
            response.valid(days.map { it.date } == dates)
            val seen = HashSet<String>()
            val models = List(response.count(52)) {
                val model = response.key()
                response.valid(seen.add(model))
                val bucket = response.bucket()
                ModelUsage(model, bucket.tokens, bucket.reported, bucket.unreported)
            }
            UsageWindow(days, models, response.bucket())
        }

    /** All roles participate in ordered run grouping, exactly as ChatStore's original fold. */
    fun foldTotals(messages: List<StoredMessage>, initial: ChatTotals): ChatTotals? =
        attempt(TOTALS, { request ->
            request.totals(initial)
            request.count(messages.size)
            for (message in messages) {
                request.integer(request.key(message.role))
                request.integer(if (message.steer) 1 else 0)
                request.integer(request.key(message.runId.orEmpty()))
                request.integer(message.promptTokens)
                request.integer(message.completionTokens)
                request.integer(message.cachedTokens)
                request.long(message.elapsedMs)
                request.long(message.firstTokenMs)
            }
        }, Response::totals)

    private inline fun <T> attempt(
        operation: Int, write: (Request) -> Unit, decode: (Response) -> T,
    ): T? {
        if (!NativeCore.available) return null
        val request = Request(operation)
        val bytes = try {
            write(request)
            request.finish()
        } catch (_: MetadataLimit) {
            return null
        }
        val result = try {
            NativeUsageAggregationJni.nativeAggregate(bytes)
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        val response = Response(result, operation, request.keys)
        return decode(response).also { response.done() }
    }

    private class MetadataLimit : RuntimeException()

    private class Request(private val operation: Int) {
        private val body = Buffer()
        val keys = ArrayList<String>()
        private val indices = LinkedHashMap<String, Int>()
        private var keyBytes = 0L
        private var rows = 0

        fun key(value: String): Int {
            indices[value]?.let { return it }
            val added = 4L + value.length * 2L
            if (keys.size >= MAX_KEYS || keyBytes + added > MAX_BYTES) throw MetadataLimit()
            keyBytes += added
            val index = keys.size
            keys += value
            indices[value] = index
            return index
        }

        fun charge(count: Int) {
            if (count < 0 || rows.toLong() + count > MAX_ROWS) throw MetadataLimit()
            rows += count
        }

        fun count(count: Int) { charge(count); integer(count) }
        private fun space(count: Long) {
            if (body.size + count + keyBytes + 12L > MAX_BYTES) throw MetadataLimit()
        }
        fun integer(value: Int) { space(4); body.writeIntLe(value) }
        fun long(value: Long) { space(8); body.writeLongLe(value) }
        fun append(buffer: Buffer) { space(buffer.size); body.write(buffer, buffer.size) }
        fun tally(value: TokenTally) {
            long(value.prompt); long(value.completion); long(value.reasoning); long(value.cached)
        }
        fun bucket(value: UsageBucket) {
            tally(value.tokens); long(value.reported); long(value.unreported)
        }
        fun tallyMap(values: Map<String, TokenTally>) {
            count(values.size)
            for ((name, value) in values) { integer(key(name)); tally(value) }
        }
        fun dailyModels(values: Map<String, Map<String, UsageBucket>>) {
            count(values.size)
            for ((date, models) in values) {
                integer(key(date))
                count(models.size)
                for ((model, value) in models) { integer(key(model)); bucket(value) }
            }
        }
        fun totals(value: ChatTotals) {
            integer(value.turns); integer(value.steps)
            integer(value.promptTokens); integer(value.completionTokens); integer(value.cachedTokens)
            integer(value.contextTokens)
            long(value.elapsedMs); long(value.firstTokenMs); long(value.longestTurnTokens)
            long(value.trailingRunPromptTokens); long(value.trailingRunCompletionTokens)
            integer(key(value.trailingRunId.orEmpty()))
        }
        fun finish(): ByteArray {
            space(0)
            val request = Buffer().writeIntLe(MAGIC).writeIntLe(operation).writeIntLe(keys.size)
            for (key in keys) {
                request.writeIntLe(key.length)
                for (unit in key) request.writeShortLe(unit.code)
            }
            request.write(body, body.size)
            return request.readByteArray()
        }
    }

    private class Response(bytes: ByteArray, operation: Int, private val keys: List<String>) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private var rows = 0
        init {
            valid(bytes.size in 8..MAX_BYTES)
            valid(integer() == MAGIC && integer() == operation)
        }
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生用量聚合响应无效")
        }
        fun integer(): Int { valid(input.remaining() >= 4); return input.int }
        fun long(): Long { valid(input.remaining() >= 8); return input.long }
        fun key(): String {
            val index = integer()
            valid(index in keys.indices)
            return keys[index]
        }
        fun count(minBytes: Int): Int {
            val count = integer()
            valid(count >= 0 && rows.toLong() + count <= MAX_ROWS)
            if (minBytes > 0) valid(count <= input.remaining() / minBytes)
            rows += count
            return count
        }
        fun tally(): TokenTally = TokenTally(long(), long(), long(), long())
        fun bucket(): UsageBucket = UsageBucket(tally(), long(), long())
        fun tallyMap(): Map<String, TokenTally> {
            val values = LinkedHashMap<String, TokenTally>()
            repeat(count(36)) {
                val key = key()
                valid(!values.containsKey(key))
                values[key] = tally()
            }
            return values
        }
        fun dailyModels(): Map<String, Map<String, UsageBucket>> {
            val values = LinkedHashMap<String, Map<String, UsageBucket>>()
            repeat(count(8)) {
                val date = key()
                valid(!values.containsKey(date))
                val models = LinkedHashMap<String, UsageBucket>()
                repeat(count(52)) {
                    val model = key()
                    valid(!models.containsKey(model))
                    models[model] = bucket()
                }
                values[date] = models
            }
            return values
        }
        fun totals(): ChatTotals {
            val turns = integer()
            val steps = integer()
            val prompt = integer()
            val completion = integer()
            val cached = integer()
            val context = integer()
            val elapsed = long()
            val firstToken = long()
            val longest = long()
            val trailingPrompt = long()
            val trailingCompletion = long()
            return ChatTotals(turns, steps, prompt, completion, cached, elapsed, firstToken, context,
                longest, key(), trailingPrompt, trailingCompletion)
        }
        fun done() { valid(!input.hasRemaining()) }
    }
}
