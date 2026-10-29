package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The four token buckets the endpoint reports per assistant message, summed. Long
 * because a heavy user's cumulative prompt tokens pass Int range well before the
 * cache is ever cleared.
 */
@Serializable
data class TokenTally(
    val prompt: Long = 0,
    val completion: Long = 0,
    val reasoning: Long = 0,
    val cached: Long = 0,
) {
    operator fun plus(message: StoredMessage): TokenTally = TokenTally(
        prompt = prompt + message.promptTokens,
        completion = completion + message.completionTokens,
        reasoning = reasoning + message.reasoningTokens,
        cached = cached + message.cachedTokens,
    )

    operator fun plus(other: TokenTally): TokenTally = TokenTally(
        prompt = prompt + other.prompt,
        completion = completion + other.completion,
        reasoning = reasoning + other.reasoning,
        cached = cached + other.cached,
    )

    val total: Long get() = prompt + completion
}

/** One day's usage, shaped to feed a bar chart straight in: an x label and heights. */
@Serializable
data class DayUsage(
    val date: String,
    val tokens: TokenTally = TokenTally(),
)

/** One model's share of the total. */
@Serializable
data class ModelUsage(
    val model: String,
    val tokens: TokenTally = TokenTally(),
)

/**
 * One chat's own contribution to the roll-up: the days and models it spent on, plus
 * the watermark that makes the next scan incremental.
 *
 * Kept per chat rather than as one flat total because a deleted chat has to take its
 * tokens back out again — a single aggregate can only ever grow, and then the header
 * reads "3 chats / 126M" about chats that no longer exist.
 */
@Serializable
data class ChatUsage(
    val days: Map<String, TokenTally> = emptyMap(),
    val models: Map<String, TokenTally> = emptyMap(),
    /**
     * How many of the chat's messages are already folded in. A count, not a timestamp:
     * a transcript only ever grows at the end, so a device clock that jumps backwards
     * cannot park new messages behind the watermark where they are never counted.
     */
    val scanned: Int = 0,
    /** Timestamp of the last message folded in — readable by eye, and the old cache's shape. */
    val scannedUpTo: Long = 0,
    /**
     * The byte offset in the log **after** the last message folded in (#30).
     *
     * This is what lets the next scan read only the new bytes instead of the whole
     * transcript. 0 = "no byte watermark yet" ⇒ the next scan reads the whole thing
     * (and then records one).
     */
    val scannedBytes: Long = 0,
    /**
     * The log generation the byte watermark belongs to.
     *
     * The log is append-only **within** a generation. A rewrite (edit-and-resend, or a
     * message that got its usage attached later) starts a new generation, and then the
     * old byte offset points into a different file ⇒ the next scan must start over.
     * -1 = "the watermark is not from a log at all" (the old single-file format).
     */
    val scannedGen: Int = -1,
)

/**
 * The persisted cache: per-chat detail only. The day and model charts are folds over
 * it, so a chat that disappears from disk simply stops being part of them.
 */
@Serializable
data class UsageSnapshot(
    // **缓存格式版本**（issue #41）：读缓存时版本不匹配就整份丢弃重建。一次性成本
    // （重扫一遍 chats/），换来之后一直走增量水位。没有这个字段时，格式一变就是
    // "旧字段静默当默认值用" —— 比重建更糟：数字悄悄错，且不会被发现。
    // 默认 **0**（哨兵），不是 SCHEMA_VERSION：旧缓存里没有这个字段，序列化会填默认值，
    // 若默认等于当前版本就永远判成"版本匹配" —— 校验形同虚设。新快照在
    // accumulateSlices 重建时显式写入当前版本。
    val schemaVersion: Int = 0,
    val chats: Map<String, ChatUsage> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
    @kotlinx.serialization.Transient
    private val aggregate: Lazy<UsageAggregate> = lazy {
        val days = mutableMapOf<String, TokenTally>()
        val models = mutableMapOf<String, TokenTally>()
        chats.values.forEach { usage ->
            usage.days.forEach { (key, tally) -> days[key] = (days[key] ?: TokenTally()) + tally }
            usage.models.forEach { (key, tally) -> models[key] = (models[key] ?: TokenTally()) + tally }
        }
        UsageAggregate(days, models, days.values.fold(TokenTally()) { acc, tally -> acc + tally })
    }

    val days: Map<String, TokenTally> get() = aggregate.value.days
    val models: Map<String, TokenTally> get() = aggregate.value.models
    val totals: TokenTally get() = aggregate.value.totals

}

private data class UsageAggregate(
    val days: Map<String, TokenTally>,
    val models: Map<String, TokenTally>,
    val totals: TokenTally,
)

/**
 * Read-only roll-up of every chat's token usage, by day and by model. It never
 * writes to `chats/` — only to its own `filesDir/usage.json` cache — so the
 * conversation store stays the single source of truth.
 */
class UsageStore(private val context: Context) {
    private val refreshLock = Mutex()
    private val chatsDir: File get() = File(context.filesDir, "chats")
    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** Reads the transcripts in whichever format they are in; never writes to them. */
    private val store = ChatStore(File(context.filesDir, "chats"))

    @Volatile
    private var snapshot: UsageSnapshot = UsageSnapshot()

    /**
     * Re-reads the chats, folds in only what is new since the last scan, and
     * rewrites the cache. Safe to call on a timer or on screen open.
     */
    suspend fun refresh(): UsageSnapshot = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val cache = readCache()
            // **目录只扫一次**：原来 loadSlices 与这里各 listFiles 一遍同一个目录。
            // 盘上仍有的会话（meta 在即算）：retainAll 的「确认删除」依据（issue #41 真问题 3）
            val metaFiles = File(chatsDir, STORE_DIR).listFiles { f -> f.name.endsWith(META_SUFFIX) }
                ?: emptyArray()
            val onDisk = metaFiles.mapTo(mutableSetOf()) { it.name.removeSuffix(META_SUFFIX) }
            val slices = loadSlices(cache, metaFiles)
            // 显式打上当前版本：cache 若刚被版本校验丢弃，这里就是"重建一次"的落点。
            val next = accumulateSlices(cache, slices, onDiskIds = onDisk)
                .copy(schemaVersion = UsageSnapshot.SCHEMA_VERSION)
            // **没有变化就不重写缓存**（优化清单 data-P2）：`writeCache` 会把整份快照
            // prettyPrint 成 JSON 再落盘；定时刷新时绝大多数次扫描都没有新消息。
            // 比较的是值相等（data class），结果与"总是写"完全一致 —— 只是少一次写盘。
            if (next != cache) writeCache(next)
            snapshot = next
            next
        }
    }

    /** The most recent [limit] days with any usage, oldest first — chart order. */
    fun byDay(limit: Int = 30): List<DayUsage> = byDayOf(snapshot, limit)

    fun totals(): TokenTally = snapshot.totals

    /** Per-model subtotals, heaviest first. */
    fun byModel(): List<ModelUsage> = byModelOf(snapshot)

    /** 每个会话**尚未计数**的那部分（#30）：只从上次数到的字节偏移续读、只解析新增。 */
    private fun loadSlices(snapshot: UsageSnapshot, metaFiles: Array<File>): List<UsageSlice> {
        val out = ArrayList<UsageSlice>()
        // 旧格式 `<id>.json` 已随 #40 删除（loadConversations 全量解析路径是死代码，一并删了），
        // 这里只扫 store 的 meta；增量续扫见下面的 slicedFrom。
        metaFiles.forEach { file ->
                val id = file.name.removeSuffix(META_SUFFIX)
                runCatching {
                    val meta = store.readMeta(id) ?: return@runCatching
                    val previous = snapshot.chats[id]
                    // **Incremental**: same generation and a byte watermark ⇒ only the
                    // bytes after the last counted message. A different generation means
                    // the log was rewritten, so the old offset points nowhere ⇒ start over.
                    val resumable = previous != null &&
                        previous.scannedGen == meta.gen &&
                        previous.scannedBytes > 0
                    val sliced = if (resumable) {
                        store.slicedFrom(meta, previous.scannedBytes)
                    } else {
                        store.allSliced(meta)
                    }
                    out += UsageSlice(
                        id = id,
                        // 一趟同时取两个字段（原来在同一个 sliced 上 map 两遍、建两个等长 list）。
                        messages = sliced.map { it.message },
                        baseIndex = if (resumable) previous.scanned else 0,
                        endOffsets = sliced.map { it.endOffset },
                        gen = meta.gen,
                    )
                }
            }
        return out
    }

    private fun readCache(): UsageSnapshot {
        snapshot.let { if (it != UsageSnapshot()) return it }
        if (!cacheFile.isFile) return UsageSnapshot()
        return runCatching { json.decodeFromString<UsageSnapshot>(cacheFile.readText()) }
            // 版本不匹配 ⇒ 当作没有缓存（下一次 refresh 按全量重建）。见 SCHEMA_VERSION。
            .map { if (it.schemaVersion == UsageSnapshot.SCHEMA_VERSION) it else UsageSnapshot() }
            .getOrDefault(UsageSnapshot())
    }

    private fun writeCache(next: UsageSnapshot) {
        val text = json.encodeToString(UsageSnapshot.serializer(), next)
        // Same temp+rename as every other store: a kill mid-write must not leave a cache that no
        // longer parses. (It would only cost a full rescan of the transcripts, but there is no
        // reason to allow the window at all.)
        val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        java.io.FileOutputStream(temp).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        java.nio.file.Files.move(temp.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val CACHE_FILE = "usage.json"
    }
}

/**
 * The bucket for messages written before per-message model tagging existed: their
 * [StoredMessage.model] is empty, and dropping them would make the totals disagree
 * with the day chart, so they are counted under one honest label instead.
 */
internal const val UNKNOWN_MODEL_LABEL = "历史/未知"

/**
 * Folds [conversations] into [snapshot], counting each chat only from its stored
 * watermark forward. Pure and zone-injectable so the properties that matter — "a
 * re-scan does not double-count", "messages split across the right days", "a deleted
 * chat gives its tokens back" — can be tested without a device, a clock, or the real
 * chats folder.
 */
/**
 * The part of one conversation that has not been folded into the usage snapshot yet.
 *
 * [messages] starts at index [baseIndex] of the whole transcript, so the count watermark
 * stays an absolute index no matter how much of the transcript was skipped.
 *
 * [endOffsets] is the byte offset **after** each message (null = the source has no log to
 * seek in, e.g. the old single-file format). It is what makes the next scan incremental.
 */
internal data class UsageSlice(
    val id: String,
    val messages: List<StoredMessage>,
    val baseIndex: Int,
    val endOffsets: List<Long>?,
    val gen: Int,
)

internal fun accumulate(
    snapshot: UsageSnapshot,
    conversations: List<Conversation>,
    zone: ZoneId = ZoneId.systemDefault(),
): UsageSnapshot = accumulateSlices(
    snapshot,
    conversations.map {
        UsageSlice(it.id, it.messages, baseIndex = 0, endOffsets = null, gen = -1)
    },
    zone,
)

/**
 * Folds the not-yet-counted part of every conversation into the snapshot.
 *
 * Same rules as the old [accumulate], with one addition: when the slice knows where each
 * message ends in the log, the byte watermark is advanced with it, so the next scan can
 * read only the new bytes (#30) instead of parsing the whole transcript again.
 */
internal fun accumulateSlices(
    snapshot: UsageSnapshot,
    slices: List<UsageSlice>,
    zone: ZoneId = ZoneId.systemDefault(),
    /** 盘上仍存在（meta 在）的会话 id；默认空集 = 旧行为，生产调用方必传。 */
    onDiskIds: Set<String> = emptySet(),
): UsageSnapshot {
    val chats = snapshot.chats.toMutableMap()

    // A chat the user deleted takes its tokens with it: the totals have to describe the
    // chats still on disk, or the header keeps counting conversations that are gone.
    // **「在盘上」以 meta 文件为准，不以本次扫描为准**（issue #41 真问题 3）：
    // 只按 slices 清会把「meta 在、这次扫描失败/被跳过」的会话用量也抹掉。
    chats.keys.retainAll((slices.mapTo(mutableSetOf<String>()) { it.id }) + onDiskIds)

    for (slice in slices) {
        val previous = chats[slice.id] ?: ChatUsage()
        val messages = slice.messages
        // The watermark is an absolute index; the slice may start later than 0.
        val start = previous.scanned.coerceIn(slice.baseIndex, slice.baseIndex + messages.size)
        val tail = messages.subList(start - slice.baseIndex, messages.size)
        // Only up to the newest *priced* message: the newest turn may still be mid-flight
        // with its usage not attached yet, and it has to be re-read once it is priced.
        val lastPriced = tail.indexOfLast { it.usageKnown }
        if (lastPriced < 0) {
            // Nothing priced to add. Record a clamped position (a transcript that somehow
            // shrank is never re-counted from the top), but do not invent an entry for a
            // chat that has never contributed anything.
            // 字节水位**可能来自上一个世代**（日志被改写过）⇒ 丢掉它。
            // 留着的话，下一次扫描的"可续读"判断会拿旧偏移去读新文件 —— 那个偏移
            // 在新世代里指的是别的位置。（可续读还要代数相同，所以现在读不到；
            // 但把不用的水位留着是雷，丢掉最省心。）
            val fixed = previous.copy(
                scanned = start,
                scannedBytes = if (slice.endOffsets != null && slice.gen != previous.scannedGen) 0L else previous.scannedBytes,
                scannedGen = if (slice.endOffsets != null) slice.gen else previous.scannedGen,
            )
            if (fixed != previous) chats[slice.id] = fixed
            continue
        }

        val days = previous.days.toMutableMap()
        val models = previous.models.toMutableMap()
        var newest = previous.scannedUpTo
        for (message in tail.subList(0, lastPriced + 1)) {
            // usageKnown filters out messages the endpoint never priced, so a chat full
            // of unknown-cost turns does not fabricate zero rows.
            if (!message.usageKnown) continue
            val key = dayKey(message.timestamp, zone)
            days[key] = (days[key] ?: TokenTally()) + message
            // Per-message, not per-chat: a chat that switched persona mid-way splits
            // its spend across the models that actually answered.
            val model = message.model.ifBlank { UNKNOWN_MODEL_LABEL }
            models[model] = (models[model] ?: TokenTally()) + message
            if (message.timestamp > newest) newest = message.timestamp
        }
        // The watermark advances past everything that was priced, so the next scan starts
        // here and the unpriced tail is looked at again.
        // The byte watermark advances to the end of the **last counted** message — the
        // unpriced tail after it is looked at again next time (same rule as the count
        // watermark above it).
        val countedIndex = start - slice.baseIndex + lastPriced
        val endBytes = slice.endOffsets?.getOrNull(countedIndex)
        chats[slice.id] = ChatUsage(
            days = days,
            models = models,
            scanned = start + lastPriced + 1,
            scannedUpTo = newest,
            scannedBytes = endBytes ?: previous.scannedBytes,
            scannedGen = if (slice.endOffsets != null) slice.gen else previous.scannedGen,
        )
    }
    return UsageSnapshot(chats = chats)
}

/** `yyyy-MM-dd` in [zone] — the local calendar day the tokens were spent on. */
internal fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

internal fun byDayOf(snapshot: UsageSnapshot, limit: Int): List<DayUsage> =
    snapshot.days.entries
        .sortedByDescending { it.key }
        .take(limit)
        .sortedBy { it.key }
        .map { (date, tally) -> DayUsage(date, tally) }

internal fun byModelOf(snapshot: UsageSnapshot): List<ModelUsage> =
    snapshot.models.entries
        .sortedByDescending { it.value.total }
        .map { (model, tally) -> ModelUsage(model, tally) }
