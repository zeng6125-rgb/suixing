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
)

/**
 * The persisted cache: per-chat detail only. The day and model charts are folds over
 * it, so a chat that disappears from disk simply stops being part of them.
 */
@Serializable
data class UsageSnapshot(
    val chats: Map<String, ChatUsage> = emptyMap(),
) {
    val days: Map<String, TokenTally>
        get() = merge { it.days }

    val models: Map<String, TokenTally>
        get() = merge { it.models }

    val totals: TokenTally
        get() = days.values.fold(TokenTally()) { acc, tally -> acc + tally }

    /** Sums one map out of every chat, so the charts stay derived from the per-chat detail. */
    private fun merge(pick: (ChatUsage) -> Map<String, TokenTally>): Map<String, TokenTally> {
        val out = mutableMapOf<String, TokenTally>()
        chats.values.forEach { usage ->
            pick(usage).forEach { (key, tally) -> out[key] = (out[key] ?: TokenTally()) + tally }
        }
        return out
    }
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
        // A live draft ends in `.json` too; reading it as a conversation would hand
        // accumulate() an id that no transcript has.
        chatsDir.listFiles { file -> file.extension == "json" && !file.name.endsWith(LIVE_SUFFIX) }
            ?.mapNotNull { file -> runCatching { json.decodeFromString<Conversation>(file.readText()) }.getOrNull() }
            ?: emptyList()

    private fun readCache(): UsageSnapshot {
        snapshot.let { if (it != UsageSnapshot()) return it }
        if (!cacheFile.isFile) return UsageSnapshot()
        return runCatching { json.decodeFromString<UsageSnapshot>(cacheFile.readText()) }
            .getOrDefault(UsageSnapshot())
    }

    private fun writeCache(next: UsageSnapshot) {
        val text = json.encodeToString(UsageSnapshot.serializer(), next)
        // Same temp+rename as every other store: a kill mid-write must not leave a cache that no
        // longer parses. (It would only cost a full rescan of the transcripts, but there is no
        // reason to allow the window at all.)
        val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(cacheFile)) {
            runCatching { temp.delete() }
            cacheFile.writeText(text)
        }
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
internal fun accumulate(
    snapshot: UsageSnapshot,
    conversations: List<Conversation>,
    zone: ZoneId = ZoneId.systemDefault(),
): UsageSnapshot {
    val chats = snapshot.chats.toMutableMap()

    // A chat the user deleted takes its tokens with it: the totals have to describe the
    // chats still on disk, or the header keeps counting conversations that are gone.
    chats.keys.retainAll(conversations.mapTo(mutableSetOf<String>()) { it.id })

    for (conversation in conversations) {
        val previous = chats[conversation.id] ?: ChatUsage()
        val messages = conversation.messages
        val start = previous.scanned.coerceIn(0, messages.size)
        val tail = messages.subList(start, messages.size)
        // Only up to the newest *priced* message: the newest turn may still be mid-flight
        // with its usage not attached yet, and it has to be re-read once it is priced.
        val lastPriced = tail.indexOfLast { it.usageKnown }
        if (lastPriced < 0) {
            // Nothing priced to add. Record a clamped position (a transcript that somehow
            // shrank is never re-counted from the top), but do not invent an entry for a
            // chat that has never contributed anything.
            if (previous.scanned != start) chats[conversation.id] = previous.copy(scanned = start)
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
        chats[conversation.id] = ChatUsage(
            days = days,
            models = models,
            scanned = start + lastPriced + 1,
            scannedUpTo = newest,
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
