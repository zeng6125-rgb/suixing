package com.suixing.agent.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * `<chatId>.live.json` — a draft beside its conversation, never inside it. Top-level because
 * every listing of `filesDir/chats` has to skip it: it ends in `.json`, exactly like a
 * transcript does.
 */
internal const val LIVE_SUFFIX = ".live.json"

/**
 * Conversations are stored one JSON file per conversation under filesDir/chats.
 *
 * The messages no longer live in that file: they are appended one per line to
 * `chats/store/<id>.<gen>.jsonl`, and the metadata the list screen needs sits beside it in
 * `chats/store/<id>.meta` (see [ChatStore]). A legacy `<id>.json` is still read — a user's
 * existing chats are 54 MB of them — and [migratePending] moves each one over, renaming it to
 * `<id>.json.migrated` only after the new copy has been read back and compared whole.
 */
class ChatRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File get() = File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }
    private val lock = Mutex()

    /** The new format, keyed under the same `chats` directory but in its own `store/` child. */
    private val store = ChatStore(File(context.filesDir, "chats"))

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            val byId = LinkedHashMap<String, Conversation>()
            // The plain files come first on purpose: while the store is not the write path, a
            // `.json` is always the newer copy and the store can only hold a leftover from the
            // build that wrote there. (When the migration is switched on this flips: the store
            // becomes authoritative and a `.json` will only be an unretired old copy.)
            // A live draft is not a conversation: it sits in the same directory and also ends
            // in `.json`, so it has to be excluded by name or it would show up as a chat.
            dir.listFiles { f -> f.extension == "json" && !f.name.endsWith(LIVE_SUFFIX) }
                ?.forEach { file ->
                    runCatching { json.decodeFromString<Conversation>(file.readText()) }
                        // Skipping a file that no longer parses made the chat look deleted
                        // while its bytes were still on disk, with nothing left to recover.
                        .onFailure { quarantine(file) }
                        .getOrNull()
                        ?.let { byId[it.id] = it }
                }
            val metas = storeMetas()
            for (meta in metas) {
                if (byId.containsKey(meta.id)) continue
                // A chat whose log no longer parses must not take the whole list down with it.
                runCatching { meta.toConversation(store.all(meta)) }
                    .onFailure { cause ->
                        Log.e(TAG, "log for ${meta.id} is unreadable; quarantining its meta", cause)
                        quarantine(File(storeDir, "${meta.id}$META_SUFFIX"))
                    }
                    .onSuccess { byId[it.id] = it }
            }
            _conversations.value = byId.values.sortedByDescending { it.updatedAt }
            // Same pass, same thread: quarantined files are never read again, so without this
            // they would sit in filesDir for ever.
            pruneQuarantine()
            pruneStaleGens(metas)
        }
    }

    /**
     * Converts the chats that are still in the old single-file format, one at a time.
     *
     * Deliberately **not** called by [load]: reading a 54 MB transcript, writing it back and
     * comparing the two is a second of IO per chat, and the list has to be on screen before that.
     * Call it once, from an IO coroutine, after the UI is up. Idempotent and safe to call on every
     * launch — a chat already migrated is skipped, and one that failed keeps its old file.
     */
    suspend fun migratePending() = withContext(Dispatchers.IO) {
        val last = readLastOpenedMarker()
        // The chat the user is about to open goes first, so the wait lands on a chat nobody is
        // looking at yet.
        for (id in legacyIds().sortedByDescending { it == last }) {
            lock.withLock { migrateOne(id) }
        }
        pruneMigrated()
    }

    /**
     * Puts every chat back where the rest of the app expects it: a plain `chats/<id>.json`.
     *
     * The build that shipped with the store half-connected left three shapes on a device:
     * - `<id>.json.migrated` and nothing else — nothing has been written since, so that copy is a
     *   complete transcript and is simply renamed back;
     * - a store copy and no plain file — read it whole, write `.json` atomically, read *that* back
     *   and compare the message count, and only then drop the store copy;
     * - both — whichever carries the newer `updatedAt` wins, so a chat that build saved is not
     *   rolled back to the file it retired.
     *
     * Not part of [load] for the same reason [migratePending] is not: it moves whole transcripts,
     * and the list has to be on screen first. Idempotent and re-entrant — a kill halfway leaves
     * either the old file or the store copy, never neither, and the next pass continues.
     */
    suspend fun repairPending() = withContext(Dispatchers.IO) {
        for (id in idsNeedingRepair()) {
            lock.withLock { repairOne(id) }
        }
    }

    /** Ids that may have a `.json.migrated` or a store copy, and so may need repairing. */
    private fun idsNeedingRepair(): Set<String> {
        val ids = LinkedHashSet<String>()
        dir.listFiles { f -> f.name.endsWith(MIGRATED_SUFFIX) }
            ?.forEach { ids += it.name.removeSuffix(MIGRATED_SUFFIX).removeSuffix(".json") }
        ids += storeMetas().map { it.id }
        return ids
    }

    /**
     * Repairs one chat, or logs why it could not be. Nothing is deleted before the copy replacing
     * it has been written and read back.
     */
    private fun repairOne(id: String) {
        val plain = legacyFile(id)
        val rolled = File(dir, "${plain.name}$MIGRATED_SUFFIX")
        val meta = runCatching { store.readMeta(id) }.getOrNull()
        try {
            when {
                // A readable chat is already there: keep the newer of the two copies.
                plain.isFile -> {
                    if (meta == null) return
                    val onDisk = json.decodeFromString<Conversation>(plain.readText())
                    if (meta.updatedAt > onDisk.updatedAt) {
                        writePlainFromStore(meta)
                    } else {
                        store.discard(id)
                        Log.i(TAG, "repair id=$id reason=plain-is-newer")
                    }
                }
                // Retired by that build and untouched since: nothing to rebuild, just rename.
                rolled.isFile && meta == null -> {
                    check(rolled.renameTo(plain)) { "could not rename ${rolled.name} back" }
                    Log.i(TAG, "repair id=$id reason=rolled-back")
                }
                meta != null -> writePlainFromStore(meta)
            }
        } catch (cause: Throwable) {
            // Deleting nothing is the point: the chat stays readable through whichever copy
            // survived, and the next launch tries again.
            Log.w(TAG, "repair-failed id=$id reason=${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /**
     * Writes `<id>.json` out of the store copy, verifies it, and only then drops that copy.
     *
     * That order is what makes this safe: a chat that exists only in the store would otherwise be
     * deleted in exchange for a file that did not get written.
     */
    private fun writePlainFromStore(meta: ChatStore.ConversationMeta) {
        val messages = store.all(meta)
        check(messages.size == meta.messageCount) {
            "store holds ${messages.size} of ${meta.messageCount} messages"
        }
        writeAtomically(
            legacyFile(meta.id),
            json.encodeToString(Conversation.serializer(), meta.toConversation(messages)),
        )
        val parsed = json.decodeFromString<Conversation>(legacyFile(meta.id).readText())
        check(parsed.messages.size == meta.messageCount) {
            "read back ${parsed.messages.size} of ${meta.messageCount} messages"
        }
        store.discard(meta.id)
        Log.i(TAG, "repair id=${meta.id} reason=written-from-store messages=${parsed.messages.size}")
    }

    suspend fun create(title: String = "新会话"): Conversation = withContext(Dispatchers.IO) {
        val conversation = Conversation(id = UUID.randomUUID().toString(), title = title)
        val stored = write(conversation)
        _conversations.value = listOf(stored) + _conversations.value
        stored
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        // Two saves for one chat really do overlap: steering saves from its own
        // coroutine while the run's finally saves the finished turn. Without the lock
        // the later write can be the older snapshot, and two truncating writes can
        // interleave into a file that no longer parses.
        lock.withLock {
            // 置顶由 [setPinned] 单独管，正文保存只继承盘上那一位：调用方（ChatViewModel 的
            // save）是按字段重建 Conversation 的，新字段默认 false —— 不在这里继承，一次
            // 「消息落盘」就会把用户刚置顶的会话取消置顶（那个构造里 providerId / model /
            // reasoningEffort / checkpoints 都为此写了同样的继承，这里是同一个坑的第五个）。
            val onDisk = _conversations.value.firstOrNull { it.id == conversation.id }
            val stamped = conversation.copy(
                updatedAt = System.currentTimeMillis(),
                pinned = onDisk?.pinned ?: conversation.pinned,
                // 归档位同理（第六个）：不继承的话，归档过的会话只要再落一次盘就自己回到列表里。
                archived = onDisk?.archived ?: conversation.archived,
            )
            val stored = write(stamped)
            val rest = _conversations.value.filterNot { it.id == stored.id }
            _conversations.value = (listOf(stored) + rest).sortedByDescending { it.updatedAt }
        }
    }

    /**
     * 置顶 / 取消置顶。只改这一位，**不动 `updatedAt`**。
     *
     * 不复用 [save]：那个方法会把 `updatedAt` 盖成「现在」，于是置顶看起来像一次编辑 ——
     * 会话管理页那一行会显示成「刚刚更新」，其它按时间排的地方也会被这一下打乱。置顶是个
     * 标签，不是一次内容变更。
     *
     * 列表顺序也不在这里动：抽屉自己按「置顶优先」排（那是它的展示规则），[conversations]
     * 保持原本的时间序，别处读到的还是同一份。
     */
    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = _conversations.value.firstOrNull { it.id == id } ?: return@withLock
            val next = current.copy(pinned = pinned)
            write(next)
            _conversations.value = _conversations.value.map { if (it.id == id) next else it }
        }
    }

    /**
     * 改名。与 [setPinned] 同理**不动 `updatedAt`**：改名是给这一篇换个标签，不是新的对话内容。
     *
     * 调用方要注意：屏幕上那一份标题是 ChatViewModel 里的拷贝，只改这里的话，用户给**当前打开
     * 的会话**改名会在下一轮被它写回去 —— 所以 UI 走 `ChatViewModel.renameConversation`。
     */
    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        val clean = title.trim()
        if (clean.isEmpty()) return@withContext
        lock.withLock {
            val current = _conversations.value.firstOrNull { it.id == id } ?: return@withLock
            val next = current.copy(title = clean)
            write(next)
            _conversations.value = _conversations.value.map { if (it.id == id) next else it }
        }
    }

    /**
     * 归档 / 取消归档。与 [setPinned] 同一条规矩：只改这一位，**不动 `updatedAt`**（归档是标签，
     * 不是一次内容变更），列表顺序也不在这里动（抽屉自己过滤与排序）。
     */
    suspend fun setArchived(id: String, archived: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = _conversations.value.firstOrNull { it.id == id } ?: return@withLock
            val next = current.copy(archived = archived)
            write(next)
            _conversations.value = _conversations.value.map { if (it.id == id) next else it }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            File(dir, "$id.json").delete()
            // The transcript itself, its metadata, and whatever a crashed rewrite left behind.
            store.discard(id)
            File(dir, "$id.json$MIGRATED_SUFFIX").delete()
            File(dir, "$id.json$MIGRATE_FAILED_SUFFIX").delete()
            // A deleted chat's draft is garbage nobody will ever read: the conversation it
            // belongs to is gone from the list, so nothing will look for it again.
            liveFile(id).delete()
            // A marker pointing at a chat that no longer exists would only send the next
            // launch down the fallback path anyway, so it is cleared rather than left lying.
            if (readLastOpenedMarker() == id) lastOpenedFile.delete()
        }
        _conversations.value = _conversations.value.filterNot { it.id == id }
    }

    /**
     * Records the answer being streamed right now, so a run that is killed — by the system,
     * by a crash, by a restart — still leaves its half-finished text on disk. It goes to its
     * own file instead of into the conversation because the transcript is only written when a
     * run ends: a draft kept there would be lost exactly when it is needed.
     */
    suspend fun saveLive(draft: LiveDraft) = withContext(Dispatchers.IO) {
        lock.withLock {
            writeAtomically(liveFile(draft.chatId), json.encodeToString(LiveDraft.serializer(), draft))
        }
    }

    /**
     * The draft a killed run left behind, or null when there is none. A draft that does not
     * parse is deleted rather than quarantined: unlike a transcript there is nothing in it
     * worth recovering by hand, and keeping it would mean failing on it at every launch.
     */
    suspend fun readLive(chatId: String): LiveDraft? = withContext(Dispatchers.IO) {
        val file = liveFile(chatId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(LiveDraft.serializer(), file.readText()) }
            .getOrElse {
                file.delete()
                null
            }
    }

    /** Called once the draft has been appended to the transcript, so it is not read twice. */
    suspend fun clearLive(chatId: String) {
        withContext(Dispatchers.IO) { lock.withLock { liveFile(chatId).delete() } }
    }

    /**
     * Remembers which conversation was on screen, so the next launch opens that one instead of
     * whichever chat was written most recently — a chat the user only read has no reason to be
     * first, and re-saving it just to move it up the list would rewrite megabytes on every
     * switch.
     *
     * The marker is a bare id in a file with no extension, never JSON: [load] picks files out
     * of this directory by extension, and a dotless name has no extension to match, so this
     * can never be read back as a conversation.
     */
    suspend fun saveLastOpened(chatId: String) = withContext(Dispatchers.IO) {
        val id = chatId.trim()
        lock.withLock {
            if (id.isEmpty()) {
                lastOpenedFile.delete()
            } else {
                writeAtomically(lastOpenedFile, id)
            }
        }
    }

    /** The conversation that was on screen last, or null when there is none to restore. */
    suspend fun readLastOpened(): String? = withContext(Dispatchers.IO) {
        val id = readLastOpenedMarker()
        // Missing, empty and unreadable all mean the same thing: nothing to restore. The
        // marker holds no history worth rescuing, so it goes rather than being re-reported
        // at every launch.
        if (id == null) lastOpenedFile.delete()
        id
    }

    private fun readLastOpenedMarker(): String? =
        runCatching { lastOpenedFile.takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun liveFile(chatId: String): File = File(dir, "$chatId$LIVE_SUFFIX")

    /**
     * `filesDir/chats/last-opened` — which conversation was on screen when the app went away.
     * No extension on purpose; see [saveLastOpened].
     */
    private val lastOpenedFile: File get() = File(dir, "last-opened")

    /**
     * Saves [conversation] as the single `<id>.json` file this app has always written, and drops
     * any copy the append-only store holds for the same chat.
     *
     * The store is not the write path yet: 0.8.307 shipped with it half-connected, so a chat
     * could end up existing only in `chats/store/`, where an older build — and this app's own
     * export button — cannot see it. Until the migration is switched on deliberately, writing the
     * plain file and dropping the store copy **heals** any chat that build already moved.
     *
     * The order is the whole point: file written, then read back, and only then is the other copy
     * discarded. Discarding first would leave a chat with no copy at all if the write failed.
     */
    private fun write(conversation: Conversation): Conversation {
        val target = File(dir, "${conversation.id}.json")
        writeAtomically(target, json.encodeToString(Conversation.serializer(), conversation))
        val healing = store.has(conversation.id)
        // Only needed when a second copy is about to be dropped, and not free: it parses the whole
        // transcript that was just encoded. An ordinary save skips it.
        val written = if (healing) {
            val parsed = json.decodeFromString<Conversation>(target.readText())
            check(parsed.messages == conversation.messages) {
                "wrote ${conversation.id} but read back ${parsed.messages.size} of " +
                    "${conversation.messages.size} messages"
            }
            parsed
        } else {
            conversation
        }
        if (healing) {
            // The chat is a plain file again, so the store's copy is stale by definition.
            store.discard(conversation.id)
            Log.i(TAG, "healed id=${conversation.id} messages=${conversation.messages.size}")
        }
        return written
    }

    private val storeDir: File get() = File(dir, STORE_DIR)

    private fun legacyFile(id: String): File = File(dir, "$id.json")

    private fun legacyIds(): List<String> =
        dir.listFiles { f -> f.extension == "json" && !f.name.endsWith(LIVE_SUFFIX) }
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()

    private fun storeMetas(): List<ChatStore.ConversationMeta> =
        storeDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?.mapNotNull { file ->
                val id = file.name.removeSuffix(META_SUFFIX)
                runCatching { store.readMeta(id) }
                    .onFailure { cause ->
                        Log.e(TAG, "meta for $id does not parse; quarantining it", cause)
                        quarantine(file)
                    }
                    .getOrNull()
            }
            ?: emptyList()

    /**
     * Moves one chat from `<id>.json` into the store; returns whether it is in the new format now.
     *
     * The old file is renamed only after the new copy has been read back and compared message for
     * message with what was parsed out of it — "it parsed and the counts agree" would not catch a
     * bad encode. On any failure the legacy bytes stay exactly as they were, a
     * `<id>.json.migrate-failed` marker says why, and whatever reached the store is thrown away so
     * it cannot shadow the real file. The next launch tries again.
     */
    private fun migrateOne(id: String): Boolean {
        val legacy = legacyFile(id)
        if (!legacy.isFile) return true
        if (store.has(id)) {
            // Killed after the store write but before the rename: nothing to redo but the rename.
            retireLegacy(id)
            return true
        }
        val bytes = legacy.length()
        // Peak is the new log beside the old file, plus the store's temp file during its rename.
        val free = context.filesDir.usableSpace
        if (free in 0 until bytes + bytes / 2) {
            Log.w(TAG, "migrate-skipped id=$id bytes=$bytes free=$free reason=no-space")
            return false
        }
        val conversation = runCatching { json.decodeFromString<Conversation>(legacy.readText()) }
            .getOrElse { cause ->
                Log.e(TAG, "migrate-failed id=$id bytes=$bytes reason=unparseable", cause)
                markMigrateFailed(id, cause)
                return false
            }
        return runCatching {
            val written = store.append(store.newMeta(conversation), conversation.messages)
            val readBack = store.all(written)
            check(readBack == conversation.messages) {
                "read back ${readBack.size} of ${conversation.messages.size} messages"
            }
            retireLegacy(id)
            Log.i(TAG, "migrated id=$id bytes=$bytes messages=${conversation.messages.size}")
            true
        }.getOrElse { cause ->
            Log.e(TAG, "migrate-failed id=$id bytes=$bytes reason=unwritable", cause)
            markMigrateFailed(id, cause)
            // A half-written entry would otherwise shadow the legacy file it failed to replace.
            runCatching { store.discard(id) }
            false
        }
    }

    private fun retireLegacy(id: String) {
        val legacy = legacyFile(id)
        if (!legacy.isFile) return
        if (!legacy.renameTo(File(dir, "${legacy.name}$MIGRATED_SUFFIX"))) {
            Log.w(TAG, "migrated id=$id reason=rename-failed; the old file is skipped from now on")
        }
    }

    private fun markMigrateFailed(id: String, cause: Throwable) {
        runCatching {
            File(dir, "${legacyFile(id).name}$MIGRATE_FAILED_SUFFIX").writeText(cause.toString())
        }
    }

    /** Old files are kept through a rollback window, then dropped so nothing reads them twice. */
    private fun pruneMigrated() {
        runCatching {
            val cutoff = System.currentTimeMillis() - MIGRATED_KEEP_MS
            dir.listFiles { f -> f.name.endsWith(MIGRATED_SUFFIX) }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    /** Log generations a crashed rewrite left behind. The one the meta names is never touched. */
    private fun pruneStaleGens(metas: List<ChatStore.ConversationMeta>) {
        runCatching {
            val live = metas.associate { it.id to "${it.id}.${it.gen}$LOG_SUFFIX" }
            storeDir.listFiles { f -> f.name.endsWith(LOG_SUFFIX) }
                ?.forEach { file ->
                    val id = file.name.removeSuffix(LOG_SUFFIX).substringBeforeLast('.')
                    val keep = live[id] ?: return@forEach
                    if (file.name != keep) file.delete()
                }
        }
    }

    /**
     * Writes to a temp file in the same directory, then renames it over the real one.
     * A rename is atomic, so being killed mid-save leaves the previous file intact
     * instead of a half-written file that [load] cannot parse. The temp name ends in
     * `.tmp` on purpose: [load] only picks up `*.json` (and skips [LIVE_SUFFIX]).
     */
    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // renameTo reports failure by returning false instead of throwing. Fall back
            // to a direct write and let its exception surface: a silently swallowed save
            // is how a finished turn disappears from disk.
            try {
                target.writeText(text)
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * Keeps an unreadable transcript as `<id>.json.corrupt` rather than skipping it
     * (the same call McpStore makes for mcp.corrupt.json): the bytes stay recoverable
     * by hand, and the file no longer shows up in [load]'s listing.
     */
    private fun quarantine(file: File) {
        runCatching { file.renameTo(File(dir, "${file.name}$CORRUPT_SUFFIX")) }
    }

    /**
     * Quarantined transcripts older than [CORRUPT_KEEP_MS] go for good: they are kept so a broken
     * file can still be rescued by hand, and a month is long past the point where anyone would.
     *
     * Best effort on purpose — a sweep that fails (a locked file, a directory that vanished) must
     * never be a reason for a load to fail.
     */
    private fun pruneQuarantine() {
        runCatching {
            val cutoff = System.currentTimeMillis() - CORRUPT_KEEP_MS
            dir.listFiles { file -> file.name.endsWith(CORRUPT_SUFFIX) }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        /** How long a quarantined transcript is kept for hand recovery. */
        const val CORRUPT_KEEP_MS = 30L * 24 * 60 * 60 * 1000

        const val CORRUPT_SUFFIX = ".corrupt"

        /**
         * A migrated chat's old single file, kept through a rollback window so a build that
         * cannot read the new format still has something to fall back to.
         */
        const val MIGRATED_SUFFIX = ".migrated"

        /**
         * Why a chat is still in the old format. Deliberately not `.json`: every listing in this
         * app picks files out by extension, and a marker must never be read as a conversation.
         */
        const val MIGRATE_FAILED_SUFFIX = ".migrate-failed"

        /** How long that rollback copy is worth keeping after a successful migration. */
        const val MIGRATED_KEEP_MS = 7L * 24 * 60 * 60 * 1000

        /** Tag for the storage lines: rewriting a whole transcript, and every migration step. */
        const val TAG = "ChatStore"
    }
}

/**
 * The answer a run was still streaming when the process died.
 *
 * Written while the answer streams — not at the end of the run — and read back on the next
 * launch: the caller appends [text] to the transcript as an assistant message of [runId] and
 * then clears it, so a kill mid-answer costs the user the last tick instead of the whole
 * answer — the failure they kept seeing as "only my question was saved".
 */
@Serializable
data class LiveDraft(
    val chatId: String,
    val runId: String,
    /** When the draft was last written, in milliseconds. */
    val at: Long,
    val text: String,
    /**
     * The thinking of the round still being written.
     *
     * Kept beside [text] because a run that is interrupted before that round lands has no
     * message to carry its chain of thought, and the card came back with the block empty.
     */
    val reasoning: String? = null,
    /**
     * The rounds and tool results the run had already produced.
     *
     * Until the run ends these live only in the session, so a run that never gets to save
     * them left the transcript with a bare paragraph and none of the tool cards the answer
     * was actually made of.
     */
    val messages: List<StoredMessage> = emptyList(),
)
