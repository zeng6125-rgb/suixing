package com.suixing.agent.data

import com.suixing.agent.core.decodeCoreObject
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
import java.time.LocalDate
import com.suixing.agent.agent.SubagentRecords
import java.io.IOException
import kotlinx.coroutines.CancellationException

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
        prompt = prompt + message.promptTokens.coerceAtLeast(0),
        completion = completion + message.completionTokens.coerceAtLeast(0),
        reasoning = reasoning + message.reasoningTokens.coerceAtLeast(0),
        cached = cached + message.cachedTokens.coerceAtLeast(0),
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
    val reported: Long = 0,
    val unreported: Long = 0,
)

/** One model's share of the total. */
@Serializable
data class ModelUsage(
    val model: String,
    val tokens: TokenTally = TokenTally(),
    val reported: Long = 0,
    val unreported: Long = 0,
)

/** Counts describe requests, including requests for which the endpoint reported no tokens. */
@Serializable
data class UsageBucket(
    val tokens: TokenTally = TokenTally(),
    val reported: Long = 0,
    val unreported: Long = 0,
) {
    val requests: Long get() = reported + unreported
    operator fun plus(other: UsageBucket) = UsageBucket(tokens + other.tokens,
        reported + other.reported, unreported + other.unreported)
}

/**
 * One chat's own contribution to the roll-up: the days and models it spent on, plus
 * the watermark that makes the next scan incremental.
 *
 * Live transcripts can be rescanned after rewrites; deleted transcripts contribute
 * their independently persisted final summaries instead.
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
    val dailyModels: Map<String, Map<String, UsageBucket>> = emptyMap(),
    val sourceStamp: String = "",
    /** Zero for a live source; historical summaries remain counted after deletion. */
    val deletedAt: Long = 0,
)

/**
 * The persisted cache: per-chat detail only. The day and model charts are folds over
 * it, including the final summaries of conversations that were deleted.
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
    val zoneId: String = "",
    @kotlinx.serialization.Transient val issues: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 3
    }
    @kotlinx.serialization.Transient
    private val aggregate: Lazy<UsageAggregate> = lazy {
        NativeUsageAggregation.aggregate(chats.values) ?: run {
            val days = mutableMapOf<String, TokenTally>()
            val models = mutableMapOf<String, TokenTally>()
            var coverage = UsageBucket()
            var deletedCoverage = UsageBucket()
            chats.values.forEach { usage ->
                usage.days.forEach { (key, tally) -> days[key] = (days[key] ?: TokenTally()) + tally }
                usage.models.forEach { (key, tally) -> models[key] = (models[key] ?: TokenTally()) + tally }
                val bucket = usage.usageBucket()
                coverage += bucket
                if (usage.deletedAt > 0L) deletedCoverage += bucket
            }
            UsageAggregate(days, models, days.values.fold(TokenTally()) { acc, tally -> acc + tally },
                coverage, deletedCoverage)
        }
    }

    val days: Map<String, TokenTally> get() = aggregate.value.days
    val models: Map<String, TokenTally> get() = aggregate.value.models
    val totals: TokenTally get() = aggregate.value.totals

    val coverage: UsageBucket get() = aggregate.value.coverage
    val deletedCoverage: UsageBucket get() = aggregate.value.deletedCoverage
}

private fun ChatUsage.usageBucket(): UsageBucket = dailyModels.values.fold(UsageBucket()) { sum, models ->
    models.values.fold(sum) { acc, bucket -> acc + bucket }
}

internal data class UsageAggregate(
    val days: Map<String, TokenTally>,
    val models: Map<String, TokenTally>,
    val totals: TokenTally,
    val coverage: UsageBucket,
    val deletedCoverage: UsageBucket,
)

/**
 * Read-only roll-up of every chat's token usage, by day and by model. It never
 * writes to `chats/`. Live usage is cached in `usage.json`; final deleted-source
 * summaries are durable in `deleted-usage/` and remain available after cache rebuilds.
 */
class UsageStore(private val context: Context) {
    private val refreshLock = Mutex()
    private val chatsDir: File get() = File(context.filesDir, "chats")
    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** Reads the transcripts in whichever format they are in; never writes to them. */
    private val store = ChatStore(File(context.filesDir, "chats"))
    private val deleted = DeletedUsageStore(File(context.filesDir, "deleted-usage"))

    @Volatile
    private var snapshot: UsageSnapshot = UsageSnapshot()

    /**
     * Re-reads the chats, folds in only what is new since the last scan, and
     * rewrites the cache. Safe to call on a timer or on screen open.
     */
    suspend fun refresh(): UsageSnapshot = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val zone = ZoneId.systemDefault()
            val loaded = readCache()
            val cache = if (loaded.zoneId.isNotBlank() && loaded.zoneId != zone.id) UsageSnapshot() else loaded
            val issues = mutableListOf<String>()
            val historical = deleted.read(issues)
            // **目录只扫一次**：原来 loadSlices 与这里各 listFiles 一遍同一个目录。
            // 盘上仍有的会话（meta 在即算）：retainAll 的「确认删除」依据（issue #41 真问题 3）
            val directory = File(chatsDir, STORE_DIR)
            val metaFiles = directory.listFiles { f -> f.name.endsWith(META_SUFFIX) }
                ?: if (!directory.exists()) emptyArray() else throw IOException("无法读取会话目录，请重试。")
            val onDisk = metaFiles.mapTo(mutableSetOf()) { it.name.removeSuffix(META_SUFFIX) }
            val slices = loadSlices(cache, metaFiles, issues).toMutableList()
            val children = SubagentRecords.usageSources(context.filesDir, onDisk,
                cache.chats.mapValues { it.value.sourceStamp })
            issues += children.issues
            children.sources.forEach { child ->
                onDisk += child.key
                child.messages?.let { messages ->
                    slices += UsageSlice(child.key, messages, 0, null, -2, child.stamp)
                }
            }
            // Preserve any still-available old cache entries whose sources are already gone.
            // This also survives timezone changes without erasing historical totals.
            for ((id, usage) in loaded.chats) {
                if (id !in onDisk && id !in historical) {
                    historical[id] = deleted.save(id, usage, loaded.zoneId.ifBlank { zone.id })
                }
            }
            // 显式打上当前版本：cache 若刚被版本校验丢弃，这里就是"重建一次"的落点。
            val live = accumulateSlices(cache, slices, zone, onDiskIds = onDisk)
            val combined = live.chats.mapValues { (_, usage) ->
                if (usage.deletedAt > 0L) usage.copy(deletedAt = 0) else usage
            }.toMutableMap()
            historical.forEach { (id, usage) -> if (id !in onDisk) combined[id] = usage }
            val next = live.copy(chats = combined,
                schemaVersion = UsageSnapshot.SCHEMA_VERSION, zoneId = zone.id, issues = issues)
            // **没有变化就不重写缓存**（优化清单 data-P2）：`writeCache` 会把整份快照
            // prettyPrint 成 JSON 再落盘；定时刷新时绝大多数次扫描都没有新消息。
            // 比较的是值相等（data class），结果与"总是写"完全一致 —— 只是少一次写盘。
            if (next != cache) writeCache(next)
            snapshot = next
            next
        }
    }

    /** Called under the repository's delete lock, before any transcript or child record is removed. */
    suspend fun preserveBeforeDeletion(id: String) = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val metaFile = File(File(chatsDir, STORE_DIR), "$id$META_SUFFIX")
            if (!metaFile.exists()) return@withLock
            val zone = ZoneId.systemDefault()
            val loaded = readCache()
            val cache = if (loaded.zoneId.isNotBlank() && loaded.zoneId != zone.id) UsageSnapshot() else loaded
            val issues = mutableListOf<String>()
            val slices = loadSlices(cache, arrayOf(metaFile), issues).toMutableList()
            val childSources = SubagentRecords.usageSources(context.filesDir, setOf(id),
                cache.chats.mapValues { it.value.sourceStamp })
            issues += childSources.issues
            val ids = mutableSetOf(id)
            childSources.sources.forEach { child ->
                ids += child.key
                child.messages?.let { slices += UsageSlice(child.key, it, 0, null, -2, child.stamp) }
            }
            if (issues.isNotEmpty()) throw IOException("删除前的用量记录未能完整保存，请重试。")
            val finalUsage = accumulateSlices(cache, slices, zone, onDiskIds = ids)
            for ((key, usage) in finalUsage.chats) deleted.save(key, usage.copy(deletedAt = 0), zone.id)
        }
    }

    /** The most recent [limit] days with any usage, oldest first — chart order. */
    fun byDay(limit: Int = 30): List<DayUsage> = byDayOf(snapshot, limit)

    fun totals(): TokenTally = snapshot.totals

    /** Per-model subtotals, heaviest first. */
    fun byModel(): List<ModelUsage> = byModelOf(snapshot)

    /** 每个会话**尚未计数**的那部分（#30）：只从上次数到的字节偏移续读、只解析新增。 */
    private suspend fun loadSlices(snapshot: UsageSnapshot, metaFiles: Array<File>, issues: MutableList<String>): List<UsageSlice> {
        val out = ArrayList<UsageSlice>()
        // 旧格式 `<id>.json` 已随 #40 删除（loadConversations 全量解析路径是死代码，一并删了），
        // 这里只扫 store 的 meta；增量续扫见下面的 slicedFrom。
        metaFiles.forEach { file ->
                val id = file.name.removeSuffix(META_SUFFIX)
                runCatching {
                    val meta = store.readMeta(id) ?: throw IOException("无法读取会话信息")
                    val previous = snapshot.chats[id]
                    // **Incremental**: same generation and a byte watermark ⇒ only the
                    // bytes after the last counted message. A different generation means
                    // the log was rewritten, so the old offset points nowhere ⇒ start over.
                    val resumable = previous != null &&
                        previous.scannedGen == meta.gen &&
                        previous.scannedBytes > 0 && previous.scannedBytes <= meta.logBytes && previous.scanned <= meta.messageCount
                    val offset = if (resumable) previous.scannedBytes else 0L
                    val base = if (resumable) previous.scanned else 0
                    store.openRead(meta).use { read ->
                        val projection = read.usageProjection(offset)
                        if (projection != null) {
                            out += UsageSlice(id, emptyList(), base, null, meta.gen, projection = projection)
                        } else {
                            // Unknown schema/Unicode candidates keep the old complete decoder.
                            // Reuse the pinned descriptor so a rewrite cannot change the source.
                            val sliced = read.slicedFrom(offset)
                            out += UsageSlice(id, sliced.map { it.message }, base,
                                sliced.map { it.endOffset }, meta.gen)
                        }
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    issues += "有会话未能刷新，已保留此前统计。"
                }
            }
        return out
    }

    private fun readCache(): UsageSnapshot {
        snapshot.let { if (it != UsageSnapshot()) return it }
        if (!cacheFile.isFile) return UsageSnapshot()
        return runCatching { json.decodeCoreObject(UsageSnapshot.serializer(), cacheFile.readText()) }
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
 * re-scan does not double-count" and "messages split across the right days" — can be
 * tested without a device, a clock, or the real chats folder. This fold handles live
 * sources; UsageStore merges independently persisted deleted summaries afterward.
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
    val sourceStamp: String = "",
    val projection: UsageProjection? = null,
) {
    val messageCount: Int get() = projection?.messageCount ?: messages.size
    val endOffset: Long? get() = projection?.endOffset ?: endOffsets?.lastOrNull()

    /** Child/live records still use their in-memory messages without a native round trip. */
    fun assistantUsage(startIndex: Int): Sequence<UsageProjectedMessage> =
        projection?.assistants?.asSequence()?.filter { it.index >= startIndex }
            ?: messages.asSequence().withIndex().drop(startIndex)
                .filter { it.value.role == Roles.ASSISTANT }
                .map { (index, message) -> UsageProjectedMessage(index, message.timestamp, message.model,
                    message.promptTokens, message.completionTokens, message.reasoningTokens, message.cachedTokens) }
}

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
        val cached = chats[slice.id] ?: ChatUsage()
        // Rewrites replace a conversation's contribution. Keeping its old count watermark
        // silently retained edited/deleted tokens and skipped newly priced messages.
        val rebuild = slice.baseIndex == 0 && (slice.gen != cached.scannedGen ||
            slice.gen < 0 || cached.scanned > slice.messageCount)
        val previous = if (rebuild) ChatUsage() else cached
        val start = previous.scanned.coerceIn(slice.baseIndex, slice.baseIndex + slice.messageCount)
        // The native fold is complete before Kotlin advances any source/cache watermark.
        val native = NativeUsageAggregation.merge(previous,
            slice.assistantUsage(start - slice.baseIndex), zone)
        if (native != null) {
            chats[slice.id] = ChatUsage(
                days = native.days, models = native.models, dailyModels = native.dailyModels,
                scanned = slice.baseIndex + slice.messageCount, scannedUpTo = native.newest,
                scannedBytes = slice.endOffset ?: previous.scannedBytes,
                scannedGen = slice.gen, sourceStamp = slice.sourceStamp,
            )
            continue
        }
        val days = previous.days.toMutableMap()
        val models = previous.models.toMutableMap()
        val dailyModels = previous.dailyModels.toMutableMap()
        var newest = previous.scannedUpTo
        for (message in slice.assistantUsage(start - slice.baseIndex)) {
            val date = dayKey(message.timestamp, zone)
            val model = message.model.ifBlank { UNKNOWN_MODEL_LABEL }
            val known = message.usageKnown
            val bucket = UsageBucket(if (known) message.tokens() else TokenTally(),
                reported = if (known) 1 else 0, unreported = if (known) 0 else 1)
            val byModel = dailyModels[date].orEmpty().toMutableMap()
            byModel[model] = (byModel[model] ?: UsageBucket()) + bucket
            dailyModels[date] = byModel
            if (known) {
                days[date] = (days[date] ?: TokenTally()) + bucket.tokens
                models[model] = (models[model] ?: TokenTally()) + bucket.tokens
            }
            newest = maxOf(newest, message.timestamp)
        }
        // Every committed assistant response is observed once, including unreported usage.
        // Attaching usage later rewrites ChatStore's generation, which triggers a full rebuild.
        chats[slice.id] = ChatUsage(
            days = days, models = models, dailyModels = dailyModels,
            scanned = slice.baseIndex + slice.messageCount, scannedUpTo = newest,
            scannedBytes = slice.endOffset ?: previous.scannedBytes,
            scannedGen = slice.gen, sourceStamp = slice.sourceStamp,
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

data class UsageWindow(val days: List<DayUsage>, val models: List<ModelUsage>, val usage: UsageBucket)

/** Calendar days, including gaps; all charts and totals use exactly the same inclusive range. */
fun usageWindow(snapshot: UsageSnapshot, count: Int, end: LocalDate = LocalDate.now()): UsageWindow {
    require(count in 1..366)
    val dates = (count - 1 downTo 0).map { end.minusDays(it.toLong()).toString() }
    NativeUsageAggregation.window(snapshot.chats.values, dates)?.let { return it }
    val dateSet = dates.toHashSet()
    val daily = mutableMapOf<String, UsageBucket>()
    val models = mutableMapOf<String, UsageBucket>()
    snapshot.chats.values.forEach { chat ->
        chat.dailyModels.forEach { (date, byModel) ->
            if (date in dateSet) byModel.forEach { (model, bucket) ->
                daily[date] = (daily[date] ?: UsageBucket()) + bucket
                models[model] = (models[model] ?: UsageBucket()) + bucket
            }
        }
    }
    val days = dates.map { date ->
        val bucket = daily[date] ?: UsageBucket()
        DayUsage(date, bucket.tokens, bucket.reported, bucket.unreported)
    }
    return UsageWindow(days, models.entries.sortedWith(compareByDescending<Map.Entry<String, UsageBucket>> { it.value.tokens.total }
        .thenBy { it.key }).map { (model, bucket) -> ModelUsage(model, bucket.tokens, bucket.reported, bucket.unreported) },
        daily.values.fold(UsageBucket()) { sum, bucket -> sum + bucket })
}
