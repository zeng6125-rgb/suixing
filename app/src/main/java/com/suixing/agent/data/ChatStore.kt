package com.suixing.agent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The conversation store: a metadata file per chat beside an append-only message log.
 *
 * Why it exists (#30/#31): a conversation used to be one JSON object in one file, so every save
 * re-encoded the whole transcript and rewrote it, and every launch parsed all of them. A chat the
 * user has had for a year is 30–50 MB, and both costs scale with that number on every single turn.
 *
 * Layout, deliberately inside its own directory so that no existing listing of `filesDir/chats`
 * (which picks files out by `extension == "json"`, in [ChatRepository] and in [UsageStore]) can
 * ever mistake a piece of it for a conversation:
 *
 * ```
 * filesDir/chats/store/<id>.meta          ConversationMeta — id, title, times, counts, watermark
 * filesDir/chats/store/<id>.<gen>.jsonl   one StoredMessage per line
 * ```
 *
 * Two rules make the pair safe against a kill at any instant:
 *
 * 1. **[ConversationMeta.logBytes] is the commit boundary.** Bytes past it are not history. A turn
 *    is on disk only once its bytes are appended *and* [RandomAccessFile.getFD] has been synced
 *    *and* a meta file naming the new length has been renamed into place. A crash between any two
 *    of those leaves the log longer than the meta says; the next writer truncates back to the
 *    boundary and the conversation still parses ([append] does this on its way in).
 * 2. **Replacing the log takes a new generation.** A rewrite never truncates or overwrites the
 *    live file: it writes `<id>.<gen+1>.jsonl` whole, syncs it, then points the meta at it, then
 *    deletes the old one. Kill it anywhere and either the old generation or the new one is
 *    complete and named by the meta that survived. (Truncating the live file in place would leave
 *    a log whose bytes no longer correspond to any count the meta could describe.)
 *
 * Everything here is plain blocking IO: the caller wraps it in `Dispatchers.IO` (see
 * [ChatRepository]). Keeping this class free of `Context` and of coroutines is what lets
 * [ChatStoreTest] drive it on a plain `TemporaryFolder`.
 */
/**
 * 一个会话的**全量**统计：轮数、步数、tokens、用时。
 *
 * 为什么需要它（用户实测）：标题栏那个「会话统计」原来是把界面上的消息列表加一遍，
 * 而列表只加载最近 12 轮（`readTail`）⇒ 显示的是「已加载那部分」，不是整个会话。
 * 用户的要求是**整个会话**。
 *
 * 怎么保证是整份：这些数字**跟着追加走** —— [ChatStore.appendTail] 把新落盘那几条加进来
 * （O(新增条数)），显示时直接读 meta，不需要为了一次统计去扫日志。
 */
@Serializable
internal data class ChatTotals(
    /** 用户提了几轮（`isTurnStart`）。 */
    val turns: Int = 0,
    /** 工具调用次数（role == TOOL）。 */
    val steps: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** Σ整轮墙钟（含 TTFT 等待）。 */
    val elapsedMs: Long = 0,
    /** Σ首字延迟。`elapsed - firstToken` 就是各轮 decode 段之和。 */
    val firstTokenMs: Long = 0,
    /** 最近一条**报了用量**的消息的 prompt tokens。 */
    val contextTokens: Int = 0,
) {
    operator fun plus(other: ChatTotals): ChatTotals = ChatTotals(
        turns = turns + other.turns,
        steps = steps + other.steps,
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        elapsedMs = elapsedMs + other.elapsedMs,
        firstTokenMs = firstTokenMs + other.firstTokenMs,
        // 这个字段是"最后一条"而不是"求和"，所以后一段覆盖前一段。
        contextTokens = if (other.contextTokens > 0) other.contextTokens else contextTokens,
    )

    companion object {
        val EMPTY = ChatTotals()
    }
}

/** 一条消息贡献的统计量。 */
internal fun totalsOfMessage(message: StoredMessage): ChatTotals = ChatTotals(
    turns = if (isTurnStart(message)) 1 else 0,
    steps = if (message.role == Roles.TOOL) 1 else 0,
    promptTokens = message.promptTokens,
    completionTokens = message.completionTokens,
    cachedTokens = message.cachedTokens,
    elapsedMs = message.elapsedMs,
    firstTokenMs = message.firstTokenMs,
    contextTokens = if (message.usageKnown) message.promptTokens else 0,
)

internal class ChatStore(private val root: File) {

    /**
     * The metadata of one conversation: what the list screen needs, plus the watermark that says
     * how much of the log is real.
     *
     * [messageCount], [turnCount], [lastMessageId] and [logBytes] are **maintained by this class**,
     * never by callers; [ChatStore.writeMeta] is for the other fields only. A meta whose watermark
     * disagrees with its log is a bug that [append] reports rather than repairs.
     */
    @Serializable
    internal data class ConversationMeta(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val pinned: Boolean = false,
        val archived: Boolean = false,
        val assistantId: String = "",
        val activeRuns: Map<String, String> = emptyMap(),
        val providerId: String? = null,
        val model: String? = null,
        val reasoningEffort: String? = null,
        val checkpoints: List<ContextCheckpoint> = emptyList(),
        /** Which `<id>.<gen>.jsonl` this record describes. See rule 2 above. */
        val gen: Int = 0,
        /** Committed length of that log, in bytes. Rule 1 above. */
        val logBytes: Long = 0,
        /** Messages committed to that log — what the list screen shows instead of parsing it. */
        val messageCount: Int = 0,
        /** Turns (a user message that is not a [StoredMessage.steer] and what follows it). */
        val turnCount: Int = 0,
        /** Id of the last committed message; the anchor an append has to line up with. */
        val lastMessageId: String = "",
        /**
         * 整个会话的统计。**跟着追加走**（[appendTail] 只累加新落盘的几条），
         * 所以「会话统计」不需要为了显示数字去扫一遍日志。
         */
        val totals: ChatTotals = ChatTotals(),
        /**
         * [totals] 是按多少条消息算出来的。
         *
         * 用来判断"统计跟当前日志对不对得上"：相等 ⇒ 直接读；不等（旧 meta 默认 -1）⇒
         * 需要一次性补算。这样旧会话也能得到正确的统计，而不是显示 0。
         */
        val totalsAt: Int = -1,
    ) {
        /** The list screen's copy of a conversation: every field, no messages. */
        fun toConversation(): Conversation = Conversation(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = emptyList(),
            assistantId = assistantId,
            pinned = pinned,
            archived = archived,
            activeRuns = activeRuns,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort,
            checkpoints = checkpoints,
            // **列表页要知道条数，但不该为此把整份记录读进内存**（#30：10 个会话 54 MB，
            // 全量解析常驻后 RSS 413 MB）。meta 里本来就存着 messageCount，这里直接带上去。
            messageCount = messageCount,
            turnCount = turnCount,
        )

        /** The same conversation with [messages] attached — what [readTail] hands the screen. */
        fun toConversation(messages: List<StoredMessage>): Conversation =
            toConversation().copy(messages = messages)
    }

    /** One message and the byte offset of the line it was read from. */
    private data class Entry(val message: StoredMessage, val offset: Long)

    /**
     * A slice of the log: `[startOffset, endOffset)` of the generation the meta names.
     *
     * [atStart] is true when the slice reaches byte 0, which is the only slice that may begin with
     * something other than a turn — the leading system message some transcripts open with.
     */
    internal data class MessageWindow(
        val messages: List<StoredMessage>,
        val startOffset: Long,
        val atStart: Boolean,
    )

    /** The chat's directory. Created on demand, never on a read. */
    private val dir: File get() = File(root, STORE_DIR)

    private fun logFile(meta: ConversationMeta): File = File(dir, "${meta.id}.${meta.gen}$LOG_SUFFIX")

    private fun metaFile(id: String): File = File(dir, "$id$META_SUFFIX")

    /** True when this chat is in the new format: a meta that names a log that exists. */
    fun has(id: String): Boolean {
        if (!metaFile(id).isFile) return false
        // Whatever generation the meta names is the one that has to be there.
        return logFile(readMeta(id) ?: return false).isFile
    }

    /**
     * The metadata of [id], or null when the chat has none (still a legacy `.json`).
     *
     * A meta file that exists but does not parse throws instead of reading as null: that is the
     * difference between "not migrated yet" and "this chat's bytes are broken", and only the
     * caller ([ChatRepository]) knows which recovery applies.
     */
    fun readMeta(id: String): ConversationMeta? {
        val file = metaFile(id)
        if (!file.isFile) return null
        return chatJson.decodeFromString(ConversationMeta.serializer(), file.readText())
    }

    /**
     * Writes the non-watermark fields of [meta] (title, pin, archive, checkpoints, model choice…).
     *
     * Cheap on purpose: renaming a chat or recording a checkpoint must not touch the log at all,
     * which is exactly what used to cost a full re-encode of the transcript.
     */
    fun writeMeta(meta: ConversationMeta) {
        writeAtomically(
            metaFile(meta.id),
            chatJson.encodeToString(ConversationMeta.serializer(), meta).toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * How a save reached the disk.
     *
     * [reason] is the word the caller logs, so a field report can be grepped for one shape:
     * `rewrite id=… bytes=… ms=… reason=inplace`.
     */
    internal enum class SaveKind(val reason: String) {
        /** Only the messages past the watermark were written. */
        APPEND("append"),

        /** The list grew, but not from where the log ends — history was edited and extended. */
        REWRITE_ANCHOR("anchor"),

        /** Same length: an edit in place, which the anchor cannot see. */
        REWRITE_INPLACE("inplace"),

        /** Shorter than the log: a deliberate drop, only ever through [rewrite]. */
        REWRITE_SHRINK("shrink"),
        ;

        /** True when the whole transcript was written again. */
        val rewrote: Boolean get() = this != APPEND
    }

    internal data class Saved(val meta: ConversationMeta, val kind: SaveKind)

    /**
     * Writes [messages] as this chat's transcript, appending when that is what the change was and
     * replacing the log when it is not.
     *
     * This is what [ChatRepository.save] calls, and it is the safe default because it can only
     * ever *add* to the log on its own: anything else — a message the user edited in place, a
     * branch switch that rewrote history — falls through to [rewrite] with the list it was given.
     * A same-length edit has to rewrite: the anchor below only proves the *last* committed message
     * still lines up, not that the ones above it do, and the log is far too big to compare whole.
     *
     * Shortening is still an error, because the one caller that legitimately does it
     * (`editAndResend`) knows it does and calls [rewrite] itself. Turning a page-load into a
     * silent "user deleted 800 messages" is the failure this whole split exists to prevent.
     */
    fun save(committed: ConversationMeta, messages: List<StoredMessage>): Saved {
        val grows = messages.size > committed.messageCount
        val continues = grows && (
            committed.messageCount == 0 ||
                messages[committed.messageCount - 1].id == committed.lastMessageId
            )
        return when {
            continues -> Saved(append(committed, messages), SaveKind.APPEND)
            // A shorter list is still an error, even though [rewrite] could absorb it: the
            // caller may be holding a page of the conversation, and rewriting that page would
            // delete everything above it. A caller that really means to drop history says so by
            // calling [rewrite] itself, which is how editAndResend reaches the disk.
            messages.size < committed.messageCount -> throw ConversationLogShrunkException(
                "chat ${committed.id}: log has ${committed.messageCount} messages, caller holds " +
                    "${messages.size} — a shorter list means it was not loaded whole; call " +
                    "rewrite() to drop history on purpose",
            )
            // Grew, but not from where the log ends: history was edited and extended.
            grows -> Saved(rewrite(committed, messages), SaveKind.REWRITE_ANCHOR)
            // Same length: an edit in place, which the anchor above cannot see.
            else -> Saved(rewrite(committed, messages), SaveKind.REWRITE_INPLACE)
        }
    }

    /**
     * Appends whatever [messages] has past [committed]'s watermark, then records the new watermark.
     *
     * [committed] must be the meta as it was **read back from disk**, not a value built up in
     * memory: it is what this method trusts to say where the log ends. Its other fields are
     * carried into the returned meta, so a caller that also renamed the chat passes
     * `readMeta(id)!!.copy(title = …)` and gets both changes in one write.
     *
     * Fails instead of guessing when the list does not continue the log:
     * - shorter than the log ([ConversationLogShrunkException]) — a caller that only loaded a page
     *   of the conversation, and would have silently deleted everything above it. The one
     *   legitimate way to shorten a conversation is [rewrite], called deliberately.
     * - not the same message at the watermark ([ConversationLogMismatchException]) — an edit or a
     *   branch switch rewrote history, which has to be a [rewrite].
     *
     * On failure the log is untouched.
     */
    fun append(committed: ConversationMeta, messages: List<StoredMessage>): ConversationMeta {
        val from = committed.messageCount
        if (messages.size < from) {
            throw ConversationLogShrunkException(
                "chat ${committed.id}: log has $from messages, caller holds ${messages.size} — " +
                    "a shorter list means it was not loaded whole; use rewrite() to drop history",
            )
        }
        if (messages.size == from) return committed
        if (from > 0 && messages[from - 1].id != committed.lastMessageId) {
            throw ConversationLogMismatchException(
                "chat ${committed.id}: message ${committed.lastMessageId} is no longer at " +
                    "position ${from - 1} (found ${messages[from - 1].id}) — history was edited",
            )
        }
        return appendTail(committed, messages.subList(from, messages.size))
    }

    /** Writes [tail] onto the end of the log and advances the meta. */
    private fun appendTail(committed: ConversationMeta, tail: List<StoredMessage>): ConversationMeta {
        val bytes = encode(tail)
        val log = logFile(committed)
        log.parentFile?.mkdirs()
        RandomAccessFile(log, "rw").use { raf ->
            // Anything past the commit boundary is a leftover from a save that died between
            // writing bytes and recording them. It is not history, so it goes before we add to it.
            if (raf.length() < committed.logBytes) {
                throw ConversationLogDamagedException(
                    "chat ${committed.id}: log is ${raf.length()} bytes, meta says " +
                        "${committed.logBytes} — bytes went missing",
                )
            }
            if (raf.length() > committed.logBytes) raf.setLength(committed.logBytes)
            raf.seek(committed.logBytes)
            try {
                raf.write(bytes)
                raf.fd.sync()
            } catch (e: Throwable) {
                // Put the log back the way the meta describes it, so the next attempt starts
                // from a boundary instead of from a half-written line.
                runCatching { raf.setLength(committed.logBytes) }
                throw e
            }
        }
        val next = committed.copy(
            logBytes = committed.logBytes + bytes.size,
            messageCount = committed.messageCount + tail.size,
            turnCount = committed.turnCount + tail.count { isTurnStart(it) },
            lastMessageId = tail.last().id,
            // 会话统计跟着追加走：只累加**新落盘**这几条，不重扫日志。
            totals = tail.fold(committed.totals) { acc, m -> acc + totalsOfMessage(m) },
            totalsAt = committed.messageCount + tail.size,
        )
        writeMeta(next)
        return next
    }

    /**
     * Appends [newMessages] to the end of the log.
     *
     * Unlike [save]/[append], the caller does **not** need the whole transcript — only the
     * messages that came after the log's last one. That is exactly what a paged UI has: the
     * screen holds the last N turns, and everything below the fold is already on disk.
     *
     * Without this, saving from a paged UI has only two options, both wrong: write the page
     * as if it were the whole thing (truncates history) or refuse (crashes the app).
     *
     * The caller must have found [committed]'s `lastMessageId` inside its own list, so the
     * new messages really do continue the log.
     */
    fun appendNew(committed: ConversationMeta, newMessages: List<StoredMessage>): ConversationMeta {
        if (newMessages.isEmpty()) return committed
        return appendTail(committed, newMessages)
    }

    /**
     * Replaces the whole log with [messages] and points the meta at it.
     *
     * This is the only way a conversation gets shorter, and it is what `editAndResend` has to call:
     * that feature drops every message after the edited question on purpose, so it cannot go
     * through [append]'s "must continue the log" check.
     *
     * Safe against a kill at any point (rule 2 in the class docs): the new generation is written
     * and synced whole before the meta names it, so the previous conversation stays readable if
     * this dies halfway.
     */
    fun rewrite(base: ConversationMeta, messages: List<StoredMessage>): ConversationMeta {
        val gen = base.gen + 1
        val bytes = encode(messages)
        val next = base.copy(
            gen = gen,
            logBytes = bytes.size.toLong(),
            messageCount = messages.size,
            turnCount = messages.count { isTurnStart(it) },
            lastMessageId = messages.lastOrNull()?.id ?: "",
        )
        writeAtomically(logFile(next), bytes)
        writeMeta(next)
        // Only now is the old generation unreachable: a crash before the meta write above leaves
        // it named by the surviving meta, which is exactly the fallback we want.
        pruneGens(base.id, keep = gen)
        return next
    }

    /** Every committed message, in order. What `loadFull` / export still need. */
    fun all(meta: ConversationMeta): List<StoredMessage> = from(meta, 0)

    /**
     * A message plus the byte offset **after** it — the point a later scan resumes from.
     *
     * The offset is the *next* line's start, so resuming there never re-reads this message
     * and never starts mid-line.
     */
    data class SlicedMessage(val message: StoredMessage, val endOffset: Long)

    /**
     * Every committed message, each with the offset after it.
     *
     * This is what makes an **incremental** scan possible (#30): the usage panel used to
     * parse every transcript in full, just to count the tokens of the messages it had not
     * counted yet. With the offsets, the next scan reads only the bytes after the last
     * message it folded in.
     */
    fun allSliced(meta: ConversationMeta): List<SlicedMessage> = slicedFrom(meta, 0)

    /** Every committed message from [startOffset] on, each with the offset after it. */
    fun slicedFrom(meta: ConversationMeta, startOffset: Long): List<SlicedMessage> {
        val log = logFile(meta)
        if (!log.isFile) throw ConversationLogDamagedException("chat ${meta.id}: no log at ${log.name}")
        val from = startOffset.coerceIn(0, meta.logBytes)
        val entries = readEntries(log, from, meta.logBytes)
        return entries.mapIndexed { index, entry ->
            // The next line's start is where a later scan continues; the last message ends
            // at the end of the log.
            val end = entries.getOrNull(index + 1)?.offset ?: meta.logBytes
            SlicedMessage(entry.message, end)
        }
    }

    /** Every committed message from the line at [startOffset] to the end. */
    fun from(meta: ConversationMeta, startOffset: Long): List<StoredMessage> {
        val log = logFile(meta)
        if (!log.isFile) throw ConversationLogDamagedException("chat ${meta.id}: no log at ${log.name}")
        return readEntries(log, startOffset.coerceIn(0, meta.logBytes), meta.logBytes).map { it.message }
    }

    /** The newest [turns] turns. What opening a conversation reads. */
    fun tail(meta: ConversationMeta, turns: Int): MessageWindow = window(meta, meta.logBytes, turns)

    /**
     * Up to [turns] complete turns ending at [endOffset], which is where the reader has scrolled
     * to — [tail]'s offset to start with, then the returned [MessageWindow.startOffset] for each
     * 「加载更早」. Returns from the start of a turn, so a card of tool results never shows up on
     * screen without the question that asked for it.
     *
     * Reads a window and grows it (×4) until it holds enough turns: a turn here can be megabytes
     * when the answer carried screenshots, so the window is measured in what it found, not in a
     * fixed size.
     */
    fun window(meta: ConversationMeta, endOffset: Long, turns: Int): MessageWindow {
        require(turns >= 1) { "turns must be at least 1, was $turns" }
        val log = logFile(meta)
        if (!log.isFile) throw ConversationLogDamagedException("chat ${meta.id}: no log at ${log.name}")
        val end = endOffset.coerceIn(0, meta.logBytes)
        if (end == 0L) return MessageWindow(emptyList(), 0, atStart = true)

        var budget = INITIAL_WINDOW_BYTES
        while (true) {
            val rawFrom = (end - budget).coerceAtLeast(0)
            val atStart = rawFrom == 0L
            // A window that starts mid-line would decode a truncated message: skip to the next
            // line boundary. It costs one partial turn, which the cut below drops anyway.
            val from = if (atStart) 0 else startOfNextLine(log, rawFrom, end)
            val entries = readEntries(log, from, end)
            val starts = entries.indices.filter { isTurnStart(entries[it].message) }
            val cut = when {
                atStart && starts.size <= turns -> 0
                starts.size >= turns -> starts[starts.size - turns]
                else -> -1
            }
            if (cut >= 0) {
                val first = entries[cut].offset
                return MessageWindow(
                    messages = entries.subList(cut, entries.size).map { it.message },
                    startOffset = first,
                    atStart = atStart && cut == 0,
                )
            }
            if (atStart) return MessageWindow(entries.map { it.message }, 0, atStart = true)
            budget *= 4
        }
    }

    /**
     * Byte offset of the line that holds message [messageId], or null.
     *
     * Used to rebuild the wire from the last compression checkpoint without parsing everything
     * above it: [StoredMessage.id] is the first field of every line, and a real newline byte
     * inside a line is impossible (the encoder escapes it), so a line starting with
     * `{"id":"<id>"` is the message itself and never a mention of it inside someone's text.
     */
    /**
     * The id of the **first** message in the log, or null when the log is empty.
     *
     * The guard needs it to tell "the whole transcript" from "a window that starts in the
     * middle" (issue #33): comparing the **count** is not enough, because a freshly built
     * meta has `messageCount == 0` — that means "just created", not "empty" — and with
     * `known == 0` the guard used to let anything through, so a `readTail` window overwrote
     * hundreds of messages.
     *
     * Only the first line is read; the rest of the log is never touched.
     */
    /** 这个 meta 指向的日志文件（详情弹窗/导出/诊断用）。不存在时返回 null。 */
    fun logFileOf(meta: ConversationMeta): java.io.File? =
        logFile(meta).takeIf { it.isFile }

    fun firstMessageId(meta: ConversationMeta): String? {
        val log = logFile(meta)
        if (!log.isFile || meta.logBytes <= 0) return null
        RandomAccessFile(log, "r").use { raf ->
            val head = ByteArray(minOf(4096L, meta.logBytes).toInt())
            raf.readFully(head)
            val lineEnd = head.indexOf(NEWLINE.toByte()).let { if (it < 0) head.size else it }
            val line = String(head, 0, lineEnd, Charsets.UTF_8)
            // Every line starts with {"id":"<uuid>", — the encoder writes the id first.
            val prefix = "{\"id\":\""
            if (!line.startsWith(prefix)) return null
            val end = line.indexOf('"', prefix.length)
            if (end <= prefix.length) return null
            return line.substring(prefix.length, end)
        }
    }

    fun offsetOf(meta: ConversationMeta, messageId: String): Long? {
        val log = logFile(meta)
        if (!log.isFile || messageId.isEmpty()) return null
        val needle = "{\"id\":\"$messageId\"".toByteArray(Charsets.UTF_8)
        val limit = meta.logBytes
        RandomAccessFile(log, "r").use { raf ->
            var pos = 0L
            val buf = ByteArray(SEARCH_CHUNK + needle.size)
            while (pos < limit) {
                val want = minOf(SEARCH_CHUNK.toLong(), limit - pos).toInt()
                // One needle's worth of overlap so a match straddling two chunks is still found.
                val extra = minOf(needle.size.toLong(), limit - (pos + want)).toInt()
                raf.seek(pos)
                raf.readFully(buf, 0, want + extra)
                val avail = want + extra
                var i = 0
                while (i + needle.size <= avail) {
                    if (buf[i] == needle[0] && regionMatches(buf, i, needle)) {
                        // Only a line start counts; the first line has no newline before it.
                        if (pos + i == 0L || buf[i - 1] == NEWLINE) return pos + i
                    }
                    i++
                }
                pos += want
            }
        }
        return null
    }

    /** Deletes the chat's store: the meta and every generation of its log. */
    fun discard(id: String) {
        metaFile(id).delete()
        dir.listFiles { file -> file.name.startsWith("$id.") && file.name.endsWith(LOG_SUFFIX) }
            ?.forEach { it.delete() }
    }

    /**
     * The metadata for a conversation that has no log yet — a brand-new chat, or one being
     * migrated out of the old single-file format.
     *
     * The watermark is zeroed on purpose: the next save then appends the whole transcript from
     * byte 0, which is exactly what writing it fresh means.
     */
    fun newMeta(conversation: Conversation): ConversationMeta =
        metaOf(conversation, gen = 0, logBytes = 0)
            // 水位全部归零 —— **包括统计**（`totals` / `totalsAt`）：下一次 append 会从头
            // 把落盘的每一条累加进来。不归零的话首次追加会把整份统计又加一遍
            // （单测抓到过：20 条消息算成 20 轮）。
            .copy(
                messageCount = 0,
                turnCount = 0,
                lastMessageId = "",
                totals = ChatTotals.EMPTY,
                totalsAt = 0,
            )

    /**
     * Builds the meta for [conversation] whose log has already been written at [logBytes].
     *
     * Migration and the first write share this: the counts always come from the messages that were
     * actually written, never from a number kept somewhere else.
     */
    fun metaOf(conversation: Conversation, gen: Int, logBytes: Long): ConversationMeta =
        ConversationMeta(
            id = conversation.id,
            title = conversation.title,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            pinned = conversation.pinned,
            archived = conversation.archived,
            assistantId = conversation.assistantId,
            activeRuns = conversation.activeRuns,
            providerId = conversation.providerId,
            model = conversation.model,
            reasoningEffort = conversation.reasoningEffort,
            checkpoints = conversation.checkpoints,
            gen = gen,
            logBytes = logBytes,
            messageCount = conversation.messages.size,
            turnCount = conversation.messages.count { isTurnStart(it) },
            lastMessageId = conversation.messages.lastOrNull()?.id ?: "",
            // 传进来的一定是**完整**消息列表（新会话 / rewrite / 迁移），所以这就是整份。
            totals = conversation.messages.fold(ChatTotals.EMPTY) { acc, m ->
                acc + totalsOfMessage(m)
            },
            totalsAt = conversation.messages.size,
        )

    /** Encodes [messages] as the log's bytes: one record per line, each newline-terminated. */
    fun encode(messages: List<StoredMessage>): ByteArray = buildString {
        for (message in messages) {
            append(chatJson.encodeToString(StoredMessage.serializer(), message))
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    /** Deletes every generation of `id` but [keep]. */
    private fun pruneGens(id: String, keep: Int) {
        dir.listFiles { file ->
            file.name.startsWith("$id.") && file.name.endsWith(LOG_SUFFIX) &&
                file.name != "$id.$keep$LOG_SUFFIX"
        }?.forEach { it.delete() }
    }

    /** The byte offset of the first line start at or after [from], or [end] when there is none. */
    private fun startOfNextLine(log: File, from: Long, end: Long): Long {
        RandomAccessFile(log, "r").use { raf ->
            raf.seek(from)
            val probe = ByteArray(minOf(SKIP_PROBE.toLong(), end - from).toInt())
            raf.readFully(probe)
            val index = probe.indexOf(NEWLINE)
            return if (index < 0) end else from + index + 1
        }
    }

    /** Parses the committed lines in `[from, end)`. Never crosses the commit boundary. */
    private fun readEntries(log: File, from: Long, end: Long): List<Entry> {
        if (end <= from) return emptyList()
        if (log.length() < end) {
            throw ConversationLogDamagedException(
                "chat ${log.name}: the log is ${log.length()} bytes but its meta claims $end",
            )
        }
        val span = end - from
        require(span <= Int.MAX_VALUE) { "window of $span bytes is too large to read at once" }
        val bytes = ByteArray(span.toInt())
        RandomAccessFile(log, "r").use { raf ->
            raf.seek(from)
            raf.readFully(bytes)
        }
        val entries = ArrayList<Entry>()
        var start = 0
        var index = 0
        while (index <= bytes.size) {
            if (index == bytes.size || bytes[index] == NEWLINE) {
                if (index > start) {
                    val line = String(bytes, start, index - start, Charsets.UTF_8)
                    val message = runCatching {
                        chatJson.decodeFromString(StoredMessage.serializer(), line)
                    }.getOrElse { cause ->
                        throw ConversationLogDamagedException(
                            "chat ${log.name}: line ${index - start} bytes at offset " +
                                "${from + start} does not parse: ${cause.message}",
                        )
                    }
                    entries.add(Entry(message, from + start))
                }
                start = index + 1
            }
            index++
        }
        return entries
    }

    private fun regionMatches(buf: ByteArray, at: Int, needle: ByteArray): Boolean {
        for (i in needle.indices) if (buf[at + i] != needle[i]) return false
        return true
    }

    /** Writes [bytes] to [target] through a synced temp file and an atomic rename. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            // The rename is only atomic about *which* name the bytes are under; without this the
            // bytes themselves can still be in the page cache when the process dies.
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            // renameTo reports failure by returning false instead of throwing. Fall back to a
            // direct write and let its exception surface: a silently swallowed save is how a
            // finished turn disappears from disk.
            try {
                target.writeBytes(bytes)
                syncDirectory(target.parentFile)
            } finally {
                temp.delete()
            }
            return
        }
        syncDirectory(target.parentFile)
    }

    /**
     * Makes the rename itself durable, best effort.
     *
     * A directory's own entry list is not covered by syncing its files: without this a power cut
     * can bring back the pre-rename directory while the file it named is already gone. Not every
     * platform allows opening a directory (the JVM unit tests run on Windows, where it throws), so
     * this is a hint rather than a requirement — the watermark is what keeps a lost rename from
     * costing history.
     */
    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private companion object {
        /** Bytes to look back through when a window comes up short of turns. */
        const val INITIAL_WINDOW_BYTES = 256L * 1024

        /** How far a window may scan forward for the start of a line it lands in the middle of. */
        const val SKIP_PROBE = 64L * 1024

        /** Chunk size for the byte search behind [offsetOf]. */
        const val SEARCH_CHUNK = 1 shl 20

        val NEWLINE = '\n'.code.toByte()

        val chatJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** `filesDir/chats/store/` — [ChatStore]'s own directory. */
internal const val STORE_DIR = "store"

/** `<chatId>.<gen>.jsonl` — one [StoredMessage] per line. */
internal const val LOG_SUFFIX = ".jsonl"

/** `<chatId>.meta` — no `.json` extension on purpose: every listing of `filesDir/chats` picks */
/** files out by `extension == "json"`, and nothing in here may be mistaken for a conversation. */
internal const val META_SUFFIX = ".meta"

/** A user message that is not a steer opens a turn; everything after it belongs to that turn. */
internal fun isTurnStart(message: StoredMessage): Boolean =
    message.role == Roles.USER && !message.steer

/**
 * Everything a caller is allowed to set on a chat, copied from [conversation].
 *
 * The watermark ([ChatStore.ConversationMeta.logBytes] and the counts beside it) is deliberately
 * absent: it describes the log on disk, so only the store may touch it. This is what makes
 * "rename the chat" and "append two messages" one call each instead of two competing writers.
 */
internal fun ChatStore.ConversationMeta.withFieldsOf(
    conversation: Conversation,
): ChatStore.ConversationMeta = copy(
    title = conversation.title,
    createdAt = conversation.createdAt,
    updatedAt = conversation.updatedAt,
    pinned = conversation.pinned,
    archived = conversation.archived,
    assistantId = conversation.assistantId,
    activeRuns = conversation.activeRuns,
    providerId = conversation.providerId,
    model = conversation.model,
    reasoningEffort = conversation.reasoningEffort,
    checkpoints = conversation.checkpoints,
)

/**
 * The caller's list does not continue the log: it was loaded from a page, and writing it back
 * would have deleted every message the page did not bring with it.
 *
 * Its own type so [ChatRepository] can tell this apart from a genuinely broken file: this one is
 * a programming error at the call site, not damage on disk.
 */
internal class ConversationLogShrunkException(message: String) : IllegalStateException(message)

/** The message the log ends with is no longer the one the caller has at that position. */
internal class ConversationLogMismatchException(message: String) : IllegalStateException(message)

/** The log and its meta disagree, or a line does not parse: the bytes on disk are broken. */
internal class ConversationLogDamagedException(message: String) : IllegalStateException(message)
