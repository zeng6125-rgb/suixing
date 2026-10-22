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
                        // **旧格式也走同一契约**（#30）：列表不常驻正文。这里把刚解析出来的
                        // messages 丢掉，只保留 meta —— 否则同一个会话"迁移前带正文、迁移后不带"，
                        // 两条读路径给出不同的对象（测试 `an old chat is listed…` 抓到的就是这个）。
                        // 丢掉的只是内存里的引用；正文仍在 `.json` 里，`readAll`/`readTail`
                        // 会在需要时读回来（`readLegacyMessages` 就是为此保留的）。
                        ?.let {
                            byId[it.id] = it.copy(
                                messages = emptyList(),
                                messageCount = it.messages.size,
                                turnCount = it.messages.count { m ->
                                    m.role == com.suixing.agent.data.Roles.USER && !m.steer
                                },
                            )
                        }
                }
            val metas = storeMetas()
            for (meta in metas) {
                if (byId.containsKey(meta.id)) continue
                // **列表页只读 meta，不读日志**（#30 的核心收益）：原来这里走
                // `store.all(meta)` 把整份记录解析进内存 —— 10 个会话 54 MB，列表就常驻 54 MB
                // 的字符串树（实测 RSS 413 MB）。现在只要 meta，`messages` 留空、
                // `messageCount` 带上去给"只要条数"的调用方。
                //
                // **谁需要全文，谁自己读**：打开会话走 `readTail()`（只读尾部 N 轮），
                // 喂给模型的历史走 `readWire()`（从最后一个 checkpoint 往后），
                // 导出/详情走 `readAll()`。调用方不改成这些，就会拿到空 messages ——
                // 这是有意的：宁可让漏改的地方立刻暴露，也不要它悄悄把空历史写回盘。
                byId[meta.id] = meta.toConversation()
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
    /**
     * 真正需要修的会话 —— **只有"两份都在"的那些**。
     *
     * 以前这里是 `ids += storeMetas().map { it.id }`（把**每一个**会话都算进去），
     * 因为旧世界的 `repairOne` 末尾有一句 `writePlainFromStore`：只要 store 里有，
     * 就把它搬回 `.json`。那句话在 0.8.317 之后是**反向的**（见 [repairOne] 的注释），
     * 于是每次启动都会把全部已迁移会话搬回单文件，写侧优化当场失效。
     *
     * 现在 store 是唯一权威，"只有一份"的状态没有任何东西要修 ⇒ 不必进这个集合。
     * 只保留两种：
     *  - `.json` 与 store **同时存在**（迁移到一半被杀）⇒ 选新的那份、退休旧的；
     *  - 只有 `.json.migrated`（旧 build 留下的）⇒ 改回 `.json`，让它走正常迁移。
     */
    private fun idsNeedingRepair(): Set<String> {
        val ids = LinkedHashSet<String>()
        dir.listFiles { f -> f.name.endsWith(MIGRATED_SUFFIX) }
            ?.forEach { ids += it.name.removeSuffix(MIGRATED_SUFFIX).removeSuffix(".json") }
        // store 里有、同时 `.json` 也在的 —— 只有这种"两份都在"才需要裁决。
        storeMetas().map { it.id }.forEach { id ->
            if (legacyFile(id).isFile) ids += id
        }
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
                // **两份都在** —— 迁移到一半被杀，或旧版本在 store 之后又写过 `.json`。
                // 裁决：谁新谁说了算，但**留下的那份必须是 store**（新世界的写路径）。
                plain.isFile -> {
                    if (meta == null) return
                    val onDisk = json.decodeFromString<Conversation>(plain.readText())
                    if (meta.updatedAt > onDisk.updatedAt) {
                        // store 更新（迁移写完 store，还没来得及改名就死了）⇒ store 权威，
                        // 退休旧文件。
                        retireLegacy(id)
                        Log.i(TAG, "repair id=$id reason=store-is-newer")
                    } else {
                        // 文件更新（有旧版本在 store 之后写过）⇒ 以文件为准。
                        // 丢掉 store 副本，`migratePending` 会把它重新迁进 store ——
                        // **不能**像旧世界那样把 store 写回 `.json`（那会把写侧优化废掉）。
                        store.discard(id)
                        Log.i(TAG, "repair id=$id reason=plain-is-newer")
                    }
                }
                // Retired by that build and untouched since: nothing to rebuild, just rename.
                rolled.isFile && meta == null -> {
                    check(rolled.renameTo(plain)) { "could not rename ${rolled.name} back" }
                    Log.i(TAG, "repair id=$id reason=rolled-back")
                }
                // **store 里有、`.json` 没有**（新世界的正常态：已迁移）。
                //
                // 旧世界这里写的是 `writePlainFromStore(meta)` —— 把 store 里的会话**搬回
                // `.json` 单文件**再删掉 store 副本。那是"store 只是副本"时代的逻辑；
                // 0.8.317 把写路径切到 store 之后，它是**反向的**：每次启动都会把所有已迁移的
                // 会话搬回 `.json`，#31 的写侧优化（30 MB/轮 → 几 KB/轮）当场失效。
                //
                // 新世界里 store 是唯一权威 ⇒ 什么都不用做，只要把还残留的旧副本退休。
                meta != null -> {
                    if (rolled.isFile) rolled.delete()
                    Log.i(TAG, "repair id=$id reason=store-authoritative")
                }
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

    /**
     * Updates a conversation's **fields only** — title, pinned, archived, checkpoints, the model
     * pick — without touching the transcript.
     *
     * Why this exists (#30): `save()` writes the whole log. `recordCheckpoint` used to go through
     * it with `messages = base.messages`, so every compression rewrote the entire transcript —
     * and once `load()` stopped materialising messages, that list was empty, which would have been
     * read as "the history shrank". A meta-only write is both correct and O(1).
     *
     * Returns the updated Conversation as the list now holds it, or null when the chat is gone.
     */
    suspend fun updateMeta(
        id: String,
        title: String? = null,
        assistantId: String? = null,
        activeRuns: Map<String, String>? = null,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        checkpoints: List<ContextCheckpoint>? = null,
    ): Conversation? = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock null
            val next = meta.copy(
                title = title ?: meta.title,
                assistantId = assistantId ?: meta.assistantId,
                activeRuns = activeRuns ?: meta.activeRuns,
                providerId = providerId ?: meta.providerId,
                model = model ?: meta.model,
                reasoningEffort = reasoningEffort ?: meta.reasoningEffort,
                checkpoints = checkpoints ?: meta.checkpoints,
            )
            store.writeMeta(next)
            val updated = next.toConversation()
            _conversations.value = _conversations.value
                .map { if (it.id == id) updated.copy(messages = it.messages) else it }
            updated
        }
    }

    // ─── 按需读取（#30/#31）────────────────────────────────────────────────────
    //
    // `load()` 现在只读 meta，`_conversations` 里的 `messages` 是空的。谁需要正文谁自己调这里，
    // **四种读法对应四类需求**，别再让所有人共用"全部读进来"那一条：
    //
    //   readAll(id)      导出、文件详情、写回全量（只在真正需要全文时）
    //   readTail(id)     打开会话要画的那一页（最近 N 轮）
    //   readEarlier(id)  「加载更早」往上翻一页
    //   readWire(id)     **喂给模型的历史**：从最后一个 checkpoint 往后，与今天完全一致
    //
    // readWire 是这次改造里最容易漏的一条：只加载 12 轮却把它当模型上下文，模型会静默变笨
    // （不报错、只是答得差），比崩溃难查得多。

    /** Every message of one chat. Use only where the whole transcript is genuinely needed. */
    suspend fun readAll(id: String): List<StoredMessage> = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock readLegacyMessages(id)
            store.all(meta)
        }
    }

    /**
     * The page the screen opens with: the last [turns] rounds.
     *
     * Returns the conversation with those messages attached, or null when the chat is gone.
     */
    suspend fun readTail(id: String, turns: Int = OPEN_TURNS): Conversation? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val meta = store.readMeta(id) ?: return@withLock readLegacyConversation(id)
                meta.toConversation(store.tail(meta, turns).messages)
            }
        }

    /**
     * The history the model is given: everything from the last checkpoint forward.
     *
     * Same cut the engine makes today (`history.drop(resumeAt + 1)`), but read from disk instead of
     * from a fully materialised list — so opening a 30 MB chat no longer costs 30 MB to send a turn.
     */
    suspend fun readWire(id: String): List<StoredMessage> = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock readLegacyMessages(id)
            // No checkpoint yet: the whole transcript is the wire, which is what the engine does
            // when `resumeAt` is null. Memory is still bounded by the checkpoint when there is one.
            val from = meta.checkpoints.lastOrNull()?.toMessageId
                ?.let { store.offsetOf(meta, it) }
                ?: return@withLock store.all(meta)
            store.from(meta, from)
        }
    }

    /** One more page, walking backwards from [endOffset] (the window the caller already has). */
    /** 打开会话/每次「加载更早」取几轮。 */
    fun openTurns(): Int = OPEN_TURNS

    /**
     * The page of turns immediately older than [beforeMessageId], newest-last.
     *
     * "加载更早" needs this rather than an offset: the screen knows the id of its first message, and
     * walking the log backwards from that id is what keeps the pages contiguous no matter how many
     * saves happened in between.
     */
    suspend fun readEarlierBefore(
        id: String,
        beforeMessageId: String,
        turns: Int = OPEN_TURNS,
    ): List<StoredMessage>? = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock null
            val end = store.offsetOf(meta, beforeMessageId) ?: return@withLock null
            if (end <= 0L) return@withLock emptyList()
            store.window(meta, end, turns).messages
        }
    }

    internal suspend fun readEarlier(
        id: String,
        endOffset: Long,
        turns: Int = OPEN_TURNS,
    ): ChatStore.MessageWindow? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val meta = store.readMeta(id) ?: return@withLock null
                store.window(meta, endOffset, turns)
            }
        }

    /** Legacy single-file paths: still readable, so a chat that failed to migrate still works. */
    private fun readLegacyConversation(id: String): Conversation? =
        legacyFile(id).takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }

    private fun readLegacyMessages(id: String): List<StoredMessage> =
        readLegacyConversation(id)?.messages.orEmpty()

    suspend fun create(title: String = "新会话"): Conversation = withContext(Dispatchers.IO) {
        val conversation = Conversation(id = UUID.randomUUID().toString(), title = title)
        val stored = write(conversation)
        _conversations.value = listOf(stored) + _conversations.value
        stored
    }

    /**
     * @param allowShrink 只有当调用方**确实持有一份被有意截短的历史**时才传 true
     *   （目前只有「编辑重答」：它把改正过的问题之后的内容全部丢掉）。
     *   默认 false 是因为最常见的事故是**只加载了一页却回写** —— 那会把历史删掉。
     */
    suspend fun save(conversation: Conversation, allowShrink: Boolean = false) =
        withContext(Dispatchers.IO) {
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
            // **护栏：历史只能变长**（#30）。`load()` 现在只读 meta，`_conversations` 里的
            // `messages` 是空的或只是尾部窗口；任何调用方如果拿它当全量回写，就会把聊天记录
            // 截断成几条 —— 而应用里**没有删除消息的功能**（编辑重答走 `ChatStore.rewrite()`），
            // 所以"变短"永远是调用方的 bug。
            //
            // 宁可当场报错，也不要静默丢数据。真要缩短历史，只有 `ChatStore.rewrite()` 一条路。
            val known = onDisk?.messageCount ?: 0
            val incoming = conversation.messages.size
            if (known > 0 && incoming < known && !allowShrink) {
                // 放行合法缩短：editAndResend 会真的把后面的消息丢掉，那是用户的操作。
                // 它和「误传尾部窗口」的区别：合法调用手里的列表从第一条开始（首条 id 等于盘上
                // 首条 id），而尾部窗口的第一条是更靠后的消息。用这一点区分，而不是禁止一切缩短。
                val headOnDisk = runCatching {
                    store.readMeta(conversation.id)?.let { meta -> store.all(meta).firstOrNull()?.id }
                }.getOrNull()
                val looksLikeTail = headOnDisk != null &&
                    conversation.messages.isNotEmpty() &&
                    conversation.messages.first().id != headOnDisk
                if (incoming == 0 || looksLikeTail) {
                    throw IllegalStateException(
                        "refusing to truncate ${conversation.id}: $incoming of $known messages " +
                            "(a paged window or an empty list was passed where the full log is required)",
                    )
                }
            }
            val stored = write(stamped, allowShrink)
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
    /**
     * 一次保存：**追加写**，不再全量重写（#31 的写侧）。
     *
     * 原来的实现是 `encodeToString(整个 Conversation)` → 写临时文件 → rename：30 MB 的会话
     * 每轮就是 30 MB 序列化 + 30 MB 写 + rename，一次保存两份 IO。issue #30 把它和读侧并列为
     * 同一个病根的两半。
     *
     * 现在交给 [ChatStore.save]：它按水位（`messageCount` / `lastMessageId`）判断能不能**只追加
     * 尾部**；不能追加时（编辑重答、中段插入）才换代重写，旧代留着当回退。
     *
     * **读回校验保留**：写完之后把整份读出来比对，不一致就抛 —— 这是"不丢历史"的最后一道闸，
     * 比省一次 IO 重要得多。只在追加路径上做（rewrite 由 ChatStore 自己校验）。
     */
    private fun write(conversation: Conversation, allowShrink: Boolean = false): Conversation {
        val started = System.currentTimeMillis()
        // **meta 的字段一律取自这次要写的会话**（标题/置顶/归档/助手/模型/检查点），
        // 只从盘上继承计数器（messageCount / lastMessageId / logBytes / gen）。
        //
        // 教训：第一版直接复用 `store.readMeta()` 当 committed，于是每次保存都把
        // 盘上的**旧**标题/置顶/归档写回去 —— 置顶/归档"重启后丢失"就是这么来的
        // （单测 `pinning and archiving survive a restart` 抓到的）。
        val onDisk = runCatching { store.readMeta(conversation.id) }.getOrNull()
        val committed = if (onDisk == null) {
            store.newMeta(conversation)
        } else {
            onDisk.copy(
                title = conversation.title,
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
        }
        val saved = if (allowShrink && conversation.messages.size < committed.messageCount) {
            // 有意缩短（编辑重答）：`save` 按设计会抛，只有 `rewrite` 能删历史。
            ChatStore.Saved(
                store.rewrite(committed, conversation.messages),
                ChatStore.SaveKind.REWRITE_SHRINK,
            )
        } else {
            store.save(committed, conversation.messages)
        }
        val elapsed = System.currentTimeMillis() - started
        if (saved.kind.rewrote && conversation.messages.size > 200) {
            // 大会话的 rewrite 是可感知的卡顿：量化它，别靠猜（#30 的验收要求）。
            Log.w(
                TAG,
                "rewrite id=${conversation.id} messages=${conversation.messages.size} " +
                    "bytes=${saved.meta.logBytes} ms=$elapsed",
            )
        }
        // 盘上若是旧的 `.json`（尚未迁移），这一轮写完之后它就是过期副本了：
        // 留着会让两个读路径给出不同内容，所以直接退役（改名保留，可回滚）。
        val legacy = legacyFile(conversation.id)
        if (legacy.isFile) retireLegacy(conversation.id)
        return saved.meta.toConversation(conversation.messages)
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
        /** 打开会话与「加载更早」每次取的轮数。 */
        const val OPEN_TURNS = 12
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
