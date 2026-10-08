package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * The persisted aggregate plus the watermark that makes the next scan incremental:
 * `lastScanned` maps chat id to the newest message timestamp already folded in, so a
 * re-scan only adds messages newer than that and never double-counts a chat.
 */
@Serializable
data class UsageSnapshot(
    val days: Map<String, TokenTally> = emptyMap(),
    val models: Map<String, TokenTally> = emptyMap(),
    val lastScanned: Map<String, Long> = emptyMap(),
) {
    val totals: TokenTally
        get() = days.values.fold(TokenTally()) { acc, tally -> acc + tally }
}

/**
 * Read-only roll-up of every chat's token usage, by day and by model. It never
 * writes to `chats/` — only to its own `filesDir/usage.json` cache — so the
 * conversation store stays the single source of truth.
 */
class UsageStore(private val context: Context) {
    private val chatsDir: File get() = File(context.filesDir, "chats")
    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    private var snapshot: UsageSnapshot = UsageSnapshot()

    /**
     * Re-reads the chats, folds in only what is new since the last scan, and
     * rewrites the cache. Safe to call on a timer or on screen open.
     */
    suspend fun refresh(): UsageSnapshot = withContext(Dispatchers.IO) {
        val next = accumulate(readCache(), loadConversations())
        snapshot = next
        writeCache(next)
        next
    }

    /** The most recent [limit] days with any usage, oldest first — chart order. */
    fun byDay(limit: Int = 30): List<DayUsage> = byDayOf(snapshot, limit)

    fun totals(): TokenTally = snapshot.totals

    /** Per-model subtotals, heaviest first. */
    fun byModel(): List<ModelUsage> = byModelOf(snapshot)

    private fun loadConversations(): List<Conversation> =
        chatsDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { json.decodeFromString<Conversation>(file.readText()) }.getOrNull() }
            ?: emptyList()

    private fun readCache(): UsageSnapshot {
        snapshot.let { if (it != UsageSnapshot()) return it }
        if (!cacheFile.isFile) return UsageSnapshot()
        return runCatching { json.decodeFromString<UsageSnapshot>(cacheFile.readText()) }
            .getOrDefault(UsageSnapshot())
    }

    private fun writeCache(next: UsageSnapshot) {
        cacheFile.writeText(json.encodeToString(UsageSnapshot.serializer(), next))
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
 * watermark forward. Pure and zone-injectable so the two properties that matter —
 * "a re-scan does not double-count" and "messages split across the right days" —
 * can be tested without a device, a clock, or the real chats folder.
 */
internal fun accumulate(
    snapshot: UsageSnapshot,
    conversations: List<Conversation>,
    zone: ZoneId = ZoneId.systemDefault(),
): UsageSnapshot {
    val days = snapshot.days.toMutableMap()
    val models = snapshot.models.toMutableMap()
    val last = snapshot.lastScanned.toMutableMap()

    for (conversation in conversations) {
        val since = last[conversation.id] ?: 0L
        // usageKnown filters out messages the endpoint never priced, so a chat full
        // of unknown-cost turns does not fabricate zero rows.
        val fresh = conversation.messages.filter { it.timestamp > since && it.usageKnown }
        if (fresh.isEmpty()) continue

        for (message in fresh) {
            val key = dayKey(message.timestamp, zone)
            days[key] = (days[key] ?: TokenTally()) + message
            // Per-message, not per-chat: a chat that switched persona mid-way splits
            // its spend across the models that actually answered.
            val model = message.model.ifBlank { UNKNOWN_MODEL_LABEL }
            models[model] = (models[model] ?: TokenTally()) + message
        }
        // Watermark advances to the newest message seen, so the next scan starts here.
        last[conversation.id] = fresh.maxOf { it.timestamp }.coerceAtLeast(since)
    }
    return UsageSnapshot(days = days, models = models, lastScanned = last)
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
