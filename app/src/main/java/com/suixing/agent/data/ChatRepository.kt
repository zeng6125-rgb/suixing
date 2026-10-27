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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

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
 * `chats/store/<id>.meta` (see [ChatStore]). **旧格式 `<id>.json` 已不再读取**（issue #40）——
 * 若机器上仍有它们，`load()` 会打一条 ERROR 说明原因，文件本身不会被删。A user's
 * existing chats are 54 MB of them — and [migratePending] moves each one over, renaming it to
 * 只有读回校验通过才认为写入成功（见 [save]）。
 */
class ChatRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File get() = File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }
    private val lock = Mutex()

    /**
     * 「最后一个 checkpoint 在日志里的字节偏移」缓存。
     *
     * checkpoint 只在**压缩**时产生（`AgentEngine` 的两处 `onCheckpoint` 都在压缩流程里），
     * 所以同一个 checkpoint 会被其后**每一轮**发消息复用；而 `readWire` 每次都要找它的字节位置，
     * `offsetOf` 是**从日志开头分块扫描**（O(文件大小)）⇒ 30MB 会话里等于每发一条消息扫 30MB。
     * 缓存后只有第一轮扫，之后 O(1)（换代或换 checkpoint 会自动失效）。
     */
    private val checkpointOffsets = OffsetCache()

    /** The new format, keyed under the same `chats` directory but in its own `store/` child. */
    private val store = ChatStore(File(context.filesDir, "chats"))

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            val byId = LinkedHashMap<String, Conversation>()
            // **旧格式（`<id>.json`）不再加载**（issue #40）：写路径自 0.8.317 起全在 `store/`，
            // 兼容层删掉之后没有任何读路径能解析它们。**但也不能静默消失** ——
            // 机器上若还有未迁移的文件，说明升级路径被跳过了；打一条 ERROR 让排查有线索，
            // 数据本身仍在磁盘上（可以用外部工具读出）。
            warnAboutLegacyFiles()
            val metas = storeMetas()
            store.retainCommitted(metas.mapTo(HashSet()) { it.id })
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
            // Quarantined originals stay recoverable; they are excluded by storeMetas().
            pruneStaleGens(metas)
        }
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
        pinned: Boolean? = null,
        archived: Boolean? = null,
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
                pinned = pinned ?: meta.pinned,
                archived = archived ?: meta.archived,
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

    /**
     * **整个会话**的统计（轮数 / 步数 / tokens / 用时）。
     *
     * 从 meta 直接读，**O(1)** —— 数字跟着追加走（[ChatStore.appendTail] 只累加新落盘的几条），
     * 显示统计时不需要扫描日志。
     *
     * 为什么不能拿界面上的消息列表加一遍：列表只加载最近 12 轮（`readTail`），
     * 加出来的只是"已加载那部分"（用户实测：标题栏的会话统计少了一大截）。
     *
     * 旧会话的 meta 没有这个字段：`chatJson` 有 `ignoreUnknownKeys`，缺失字段取声明默认值，
     * 所以 `totalsAt` 读到 **-1**，与 `messageCount` 不等 ⇒ 这里**一次性补算**并写回，
     * 此后都是 O(1)。补算在 IO 线程上，且只在这个会话第一次看统计时发生。
     */
    /** 会话在磁盘上的真实文件（`chats/store/<id>.<gen>.jsonl`）—— 详情弹窗与导出用它。 */
    data class ChatFile(
        val path: String,
        val bytes: Long,
        val modifiedAt: Long,
        /** 日志尾部原文（诊断用，最多 4000 字）。 */
        val tail: String,
        /** 消息条数（来自 meta，不受窗口影响）。 */
        val messageCount: Int,
    )

    /**
     * 读会话在磁盘上的真实文件信息。
     *
     * **必须读 store 路径**（`chats/store/`），不能再读旧的 `chats/<id>.json`：
     * 0.8.317 迁移之后那个文件已经不存在了，详情弹窗因此对**每个**会话都显示
     * 「这个会话在磁盘上没有对应的文件」（用户实测）。
     */
    /**
     * 只取日志文件尺寸（会话列表每行显示用）。
     *
     * **为什么单独开一个入口**：`chatFile` 为了 `tail` 会把**整份日志**读成 String
     * （30MB 会话 = 30MB 读 + 最多 60MB 中间字符串），而列表只需要 `length()`。
     * 会话行是 LazyColumn 的 item，滚出视口即销毁 ⇒ 原文每次滚回来都重读一遍整份日志。
     * 返回的字节数与 `chatFile().bytes` 同源同值（同一个文件、同一个 `length()`）。
     */
    internal suspend fun chatSize(id: String): Long? = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock null
            val log = store.logFileOf(meta) ?: return@withLock null
            if (log.isFile) log.length() else null
        }
    }

    internal suspend fun chatFile(id: String): ChatFile? = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id)
            if (meta != null) {
                val log = store.logFileOf(meta)
                if (log != null && log.isFile) {
                    return@withLock ChatFile(
                        path = log.absolutePath,
                        bytes = log.length(),
                        modifiedAt = log.lastModified(),
                        // **只读尾部字节**（issue #35 清单 P0-1）：原来 `readText().takeLast(4000)`
                        // 为四千字把整份日志读成 String —— 30MB 会话就是 30MB 读 + 最多 60MB
                        // 中间串，而且发生在仓库级锁内。20KB 足以覆盖 4000 个 4 字节字符。
                        tail = runCatching { readTailText(log) }.getOrDefault(""),
                        messageCount = meta.messageCount,
                    )
                }
            }
            null
        }
    }

/**
 * 读文件末尾的至多 [TAIL_MAX_CHARS] 个字符，**只读末尾 [TAIL_MAX_BYTES] 字节**。
 *
 * 为什么不是 `readText().takeLast(n)`：`readText` 会把整份日志读成一个 String，30MB 会话
 * 就是 30MB 读 + 最多两倍的内存抖动；而这里只要四千字。
 * 字节窗口开得比字符数大得多（20KB vs 4000 字）：UTF-8 中文 3 字节、生僻字 4 字节，
 * 20KB 足够覆盖 4000 字，且从字节中间起读最多只在**开头**产生几个替换字符 ——
 * 我们取的正是尾部，不受影响。
 */
private val TAIL_MAX_CHARS = 4000
private val TAIL_MAX_BYTES = 20_000L

private fun readTailText(file: java.io.File, maxChars: Int = TAIL_MAX_CHARS): String {
    val length = file.length()
    if (length <= 0L) return ""
    val window = minOf(length, TAIL_MAX_BYTES).toInt()
    val bytes = ByteArray(window)
    java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(length - window)
        raf.readFully(bytes)
    }
    return String(bytes, Charsets.UTF_8).takeLast(maxChars)
}

    /**
     * 导出用：整份会话序列化成 JSON 文本（**不是**直接拷日志 —— 日志是 JSONL，
     * 拷出去用户打不开；而旧 `.json` 已经不存在了）。
     */
    internal suspend fun exportJson(id: String): String? = withContext(Dispatchers.IO) {
        val meta = store.readMeta(id)
        val messages = if (meta != null) {
            store.all(meta)
        } else {
            return@withContext null
        }
        val base = _conversations.value.firstOrNull { it.id == id }
        val conversation = (base ?: meta?.toConversation() ?: return@withContext null)
            .copy(messages = messages)
        json.encodeToString(Conversation.serializer(), conversation)
    }

    internal suspend fun sessionTotals(id: String): ChatTotals? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val meta = store.readMeta(id) ?: return@withLock ChatTotals.EMPTY
                if (meta.totalsAt == meta.messageCount) return@withLock meta.totals
                val totals = runCatching {
                    foldTotals(store.all(meta))
                }.onFailure {
                    Log.w(TAG, "totals backfill failed for $id", it)
                }.getOrNull() ?: return@withLock meta.totals
                runCatching {
                    store.writeMeta(meta.copy(totals = totals, totalsAt = meta.messageCount))
                }
                totals
            }
        }


    /** Every message of one chat. Use only where the whole transcript is genuinely needed. */
    suspend fun readAll(id: String): List<StoredMessage> = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock emptyList()
            store.all(meta)
        }
    }

    /**
     * The page the screen opens with: the last [turns] rounds.
     *
     * Returns the conversation with those messages attached, or null when the chat is gone.
     */
    suspend fun readTail(id: String, turns: Int = OPEN_TURNS): Conversation? =
        readTailWindow(id, turns)?.first

    /**
     * 打开会话：尾部窗口**连同它的起飞字节位置**一起返回。
     *
     * 为什么要那个字节：`readEarlierBefore` 是按**消息 id** 找位置的（`offsetOf` 从日志开头
     * 整份扫描），30MB 的会话里每次「加载更早」都要扫最多 30MB；而 `readEarlier` 接受**字节偏移**，
     * 只读窗口那一段。调用方把这个偏移记住，往上翻就变成 O(窗口) 而不是 O(文件)。
     *
     * 返回的 gen 用来**校验偏移是否还有效**：换代重写（编辑重答）之后旧的字节位置没有意义。
     */
    suspend fun readTailWindow(
        id: String,
        turns: Int = OPEN_TURNS,
    ): Triple<Conversation, Long, Int>? = withContext(Dispatchers.IO) {
        readCommitted(id) { snapshot ->
            val window = snapshot.tail(turns)
            Triple(snapshot.meta.toConversation(window.messages), window.startOffset, snapshot.meta.gen)
        }?.let { return@withContext it }
        // 冷读：不拿仓库锁，直接读已提交代次（R3）。
        readCommittedCold(id) { snapshot ->
            val window = snapshot.tail(turns)
            Triple(snapshot.meta.toConversation(window.messages), window.startOffset, snapshot.meta.gen)
        }?.let { return@withContext it }
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock null
            val window = store.tail(meta, turns)
            Triple(meta.toConversation(window.messages), window.startOffset, meta.gen)
        }
    }

    /**
     * 冷路径同样只读已提交代次（R3）：未命中 `committedMetas` 时先**无锁**读一次 meta
     * （`ChatStore.readMeta` 只碰自己的同步缓存与一个 meta 文件），再打开那一代次的日志。
     * 任何异常（正在 rename、文件被删、解析失败）都返回 null，让调用方回落到原来的加锁路径 ——
     * 冷读不再排在“另一会话整份写盘”后面。
     */
    private fun <T : Any> readCommittedCold(
        id: String,
        expectGen: Int? = null,
        read: (ChatStore.ReadSnapshot) -> T,
    ): T? = try {
        val meta = store.readMeta(id) ?: return null
        if (expectGen != null && meta.gen != expectGen) return null
        store.openRead(meta).use(read)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Writers remain serialized. Read only immutable committed bytes on one pinned generation. */
    private fun <T : Any> readCommitted(
        id: String,
        expectGen: Int? = null,
        read: (ChatStore.ReadSnapshot) -> T,
    ): T? = try {
        store.committedRead(id)?.use { snapshot ->
            if (expectGen != null && snapshot.meta.gen != expectGen) null else read(snapshot)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A rewrite can unlink a generation between lookup and open; damage is rechecked on
        // the original locked path. Never treat failed snapshot reads as an empty transcript.
        null
    }

    /**
     * The history the model is given: everything from the last checkpoint forward.
     *
     * Same cut the engine makes today (`history.drop(resumeAt + 1)`), but read from disk instead of
     * from a fully materialised list — so opening a 30 MB chat no longer costs 30 MB to send a turn.
     */
    suspend fun readWire(id: String): List<StoredMessage> = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock emptyList()
            // No checkpoint yet: the whole transcript is the wire, which is what the engine does
            // when `resumeAt` is null. Memory is still bounded by the checkpoint when there is one.
            val checkpointId = meta.checkpoints.lastOrNull()?.toMessageId
                ?: return@withLock store.all(meta)
            val from = checkpointOffsets.get(meta.id, meta.gen, checkpointId)
                ?: store.offsetOf(meta, checkpointId)?.also {
                    checkpointOffsets.put(meta.id, meta.gen, checkpointId, it)
                }
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
    /**
     * 按**消息 id** 取更早的一页（回退路径：不知道字节偏移时用）。
     *
     * 返回值里带上 `startOffset` 与 `gen`：调用方拿到之后就能记下来，
     * **下一次**加载更早直接走字节偏移（O(窗口)）而不用再扫全文件。
     * 之前这个方法只返回消息，于是「编辑重答换代之后」窗口位置永久失效 ⇒
     * 之后每次翻页都退回全文件扫描（评审 S1）。
     */
    suspend fun readEarlierBefore(
        id: String,
        beforeMessageId: String,
        turns: Int = OPEN_TURNS,
    ): EarlierPage? = withContext(Dispatchers.IO) {
        lock.withLock {
            val meta = store.readMeta(id) ?: return@withLock null
            val end = store.offsetOf(meta, beforeMessageId) ?: return@withLock null
            if (end <= 0L) return@withLock EarlierPage(emptyList(), 0L, meta.gen)
            val window = store.window(meta, end, turns)
            EarlierPage(window.messages, window.startOffset, meta.gen)
        }
    }

    /** 一页更早的转录，附带"它从日志的哪个字节开始"与日志代次。 */
    data class EarlierPage(
        val messages: List<StoredMessage>,
        val startOffset: Long,
        val gen: Int,
    )

    internal suspend fun readEarlier(
        id: String,
        endOffset: Long,
        turns: Int = OPEN_TURNS,
        /** 调用方记住的代次；不匹配说明日志被换代重写过，旧字节偏移已失效。 */
        expectGen: Int? = null,
    ): ChatStore.MessageWindow? =
        withContext(Dispatchers.IO) {
            readCommitted(id, expectGen) { it.window(endOffset, turns) }?.let { return@withContext it }
            readCommittedCold(id, expectGen) { it.window(endOffset, turns) }?.let { return@withContext it }
            lock.withLock {
                val meta = store.readMeta(id) ?: return@withLock null
                if (expectGen != null && meta.gen != expectGen) return@withLock null
                store.window(meta, endOffset, turns)
            }
        }

    suspend fun create(title: String = "新会话"): Conversation = withContext(Dispatchers.IO) {
        lock.withLock {
            val conversation = Conversation(id = UUID.randomUUID().toString(), title = title)
            val stored = write(conversation)
            _conversations.value = listOf(stored) + _conversations.value
            stored
        }
    }

    /**
     * @param allowShrink 只有当调用方**确实持有一份被有意截短的历史**时才传 true
     *   （目前只有「编辑重答」：它把改正过的问题之后的内容全部丢掉）。
     *   默认 false 是因为最常见的事故是**只加载了一页却回写** —— 那会把历史删掉。
     */
    /**
     * 一次保存的结果。**拒绝必须能被调用方看见**（评审 M3）：
     * 原来的签名是 `Unit`，两处"为避免截断而跳过"只是 `Log.w` ⇒ 调用方当成成功，
     * 于是界面一切正常、重启后整轮消失，甚至会把**唯一副本**（live 草稿）清掉。
     */
    sealed interface SaveResult {
        data object Written : SaveResult
        data class Refused(val reason: String) : SaveResult
    }

    suspend fun save(
        conversation: Conversation,
        allowShrink: Boolean = false,
        /**
         * **这份 messages 是不是整份历史**（只有"先读全文再改"的路径才传 true）。
         *
         * `allowShrink` 的语义是"我有意缩短"，但它同时是**唯一能绕过「窗口不许当全量」
         * 这道护栏的开关**。编辑重答曾经把屏幕窗口（12 轮）连同 `allowShrink=true`
         * 一起交给这里 ⇒ 整份日志被重写成窗口、旧代被删 —— 那就是 issue #33 的形状。
         * 现在：`allowShrink` **且** 条数少于盘上 **且** 没声明 `fullHistory` ⇒ 拒绝写。
         */
        fullHistory: Boolean = false,
    ): SaveResult =
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
            val incoming = conversation.messages.size
            // **尾部窗口**（#30 读侧改造的必然结果）：UI 只持有最近 N 轮，
            // 盘上却有全部历史 ⇒ `incoming < known`。
            //
            // 这里曾经直接抛异常（"refusing to truncate"）——**那等于把"分页"当成 bug，
            // 让整个 app 崩掉**（真机实测：2215 条的会话，窗口 92 条，一发送就闪退）。
            // 正确的做法：**只把新增的那几条追加进日志**，历史一条不动。
            //
            // 怎么找"新增的那几条"：盘上日志的**最后一条**（`lastMessageId`）在窗口里的位置，
            // 它后面的就是新增的。找不到 ⇒ 窗口和日志末尾对不上，那是真的异常。
            var tailOnly: List<StoredMessage>? = null
            // **护栏以「盘上真有日志」为准，不看 messageCount**（issue #33 根因 1）。
            //
            // 旧写法是 `known > 0 && incoming < known` —— 而 `newMeta()` 建出来的 meta
            // 的 `messageCount` 就是 **0**（那表示"刚建"，不是"空会话"），
            // 于是 `known == 0` 时**什么都拦不住**：一个 `readTail` 的窗口被当成全量写进去，
            // 直接覆盖掉几百条历史（真机实测：85 条覆盖 400+ 条）。
            //
            // 现在改成：**只要盘上有日志（logBytes > 0），就必须核对首条 id**。
            var diskMeta = runCatching { store.readMeta(conversation.id) }.getOrNull()
            var diskHasLog = diskMeta != null && diskMeta.logBytes > 0

            // ─── **allowShrink 不许当后门**（评审 M1：#33 的原形状）──────────────────
            //
            // 编辑重答曾经把**屏幕窗口**（12 轮）连同 `allowShrink = true` 一起交进来；
            // 这个开关让下面两道护栏整段跳过 ⇒ 整份日志被重写成窗口、旧代被删。
            // 现在：声称"有意缩短"的同时，必须**显式声明手里是整份历史**。
            // **`fullHistory` 也要核对**（评审 S-3）：它只是调用方的一句声明。
            // 声明"我手里是整份历史"却又比盘上少、且首条也对不上 —— 那就是窗口冒充全量，
            // 和没有这个开关时一样危险，必须拒绝。
            if (allowShrink && fullHistory && diskHasLog) {
                val known = diskMeta?.messageCount ?: 0
                // **条数相等也要核对末条 id**（评审 9 建议项）：编辑不改 id，这个核对零成本；
                // 少了它，"另一个会话恰好同长的窗口"会被当成全文写进 `rewrite`（gen+1、旧代被删）。
                val tailMatches = conversation.messages.lastOrNull()?.id == diskMeta?.lastMessageId
                if (conversation.messages.size < known || !tailMatches) {
                    val headOnDisk = runCatching { store.firstMessageId(diskMeta!!) }.getOrNull()
                    val headMatches = headOnDisk != null &&
                        conversation.messages.firstOrNull()?.id == headOnDisk
                    if (!headMatches) {
                        Log.w(
                            TAG,
                            "save refused id=${conversation.id}: claims the whole history but holds " +
                                "${conversation.messages.size} of $known and does not start where the log does",
                        )
                        return@withLock SaveResult.Refused(
                            "声明了完整历史但内容对不上（手里 ${conversation.messages.size} 条、" +
                                "盘上 $known 条，末尾也对不上）；" +
                                "为避免截断没有写入",
                        )
                    }
                }
            }
            if (allowShrink && !fullHistory && diskHasLog) {
                val known = diskMeta?.messageCount ?: 0
                if (conversation.messages.size < known) {
                    Log.w(
                        TAG,
                        "save refused id=${conversation.id}: allowShrink with " +
                            "${conversation.messages.size} of $known messages and no " +
                            "fullHistory flag — a paged window would replace the log",
                    )
                    return@withLock SaveResult.Refused(
                        "这次缩短没有声明是完整历史（手里 ${conversation.messages.size} 条 < 盘上 $known 条）；" +
                            "为避免截断没有写入",
                    )
                }
            }

            // ─── **旧格式会话：先整份迁移，再谈这一轮写什么**（issue #33 事故机制）────
            //
            // 事故是这样发生的：`.json` 里有 400+ 条（几天累积的历史），UI 手里只有
            // 最近 12 轮（`readTail` 的窗口）。store 里**还没有**这个会话的日志 ⇒
            // 上面那条护栏的 `diskHasLog` 为假 ⇒ **什么都不拦** ⇒ 窗口被当成全量写进
            // store ⇒ 400+ 条被 12 轮顶替（真机：85 条覆盖 400+ 条，-96%）。
            //
            // 现在：盘上还有 `.json` ⇒ 它才是**完整**的那份 ⇒ 先把它**整份**迁进 store
            // （`migrateOne` 读的是整个文件，不是窗口），这一轮再按"追加尾部"写。
            //
            // 顺带修好了「统计只算已加载的」：迁移后的 meta 的 `messageCount`/`turnCount`
            // 是**整份**算出来的，追加时再累加新增的 ⇒ 列表页显示的轮数是全量。

            if (diskHasLog && !allowShrink) {
                val meta = diskMeta
                val cut = meta?.lastMessageId
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { lastId -> conversation.messages.indexOfFirst { it.id == lastId } }
                    ?: -1
                if (cut >= 0) {
                    tailOnly = conversation.messages.drop(cut + 1)
                } else {
                    // 对不上：手里这份**既不是尾部窗口，也不是全量**。
                    // 再核对一次**首条** —— 如果首条也不等于盘上的首条，那这份数据是
                    // 一个"从中间开始"的窗口，写进去就是截断。
                    val headOnDisk = runCatching {
                        store.firstMessageId(meta!!)
                    }.getOrNull()
                    val headMatches = headOnDisk != null &&
                        conversation.messages.firstOrNull()?.id == headOnDisk
                    if (!headMatches) {
                        // **两份都不是 ⇒ 拒绝写**。
                        // 但**不抛**——异常会一路冒到 viewModelScope，把 app 崩掉
                        // （真机实测：v0.8.320 一发送就闪退，堆栈就是这个方法）。
                        // 记下来、跳过这次保存，让用户看得见错误而不是崩。
                        Log.w(
                            TAG,
                            "save refused id=${conversation.id}: $incoming messages, " +
                                "head ${conversation.messages.firstOrNull()?.id} != " +
                                "$headOnDisk — the list is neither the whole log nor its tail",
                        )
                        return@withLock SaveResult.Refused(
                            "这份既不是完整历史、也不是盘上末尾的一段；为避免截断没有写入",
                        )
                    }
                }
            }
            val stored = if (tailOnly != null) {
                writeTail(stamped, tailOnly)
            } else {
                write(stamped, allowShrink)
            }
            val rest = _conversations.value.filterNot { it.id == stored.id }
            _conversations.value = (listOf(stored) + rest).sortedByDescending { it.updatedAt }
            SaveResult.Written
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
    suspend fun setPinned(id: String, pinned: Boolean) {
        // **只改 meta**（不碰日志）：`_conversations` 里的 `messages` 是空列表
        // （`load()` 只读 meta，见 #30），走 `write()` 会把「0 条」当全量回写 ⇒
        // `ConversationLogShrunkException` 崩进程（真机实测：点「取消归档」闪退）。
        val next = updateMeta(id = id, pinned = pinned) ?: return
        _conversations.value = _conversations.value.map { if (it.id == id) next else it }
    }

    /**
     * 改名。与 [setPinned] 同理**不动 `updatedAt`**：改名是给这一篇换个标签，不是新的对话内容。
     *
     * 调用方要注意：屏幕上那一份标题是 ChatViewModel 里的拷贝，只改这里的话，用户给**当前打开
     * 的会话**改名会在下一轮被它写回去 —— 所以 UI 走 `ChatViewModel.renameConversation`。
     */
    suspend fun rename(id: String, title: String) {
        // 同 [setPinned]：只改 meta。走 `write()` 会拿空 messages 回写 ⇒ 崩。
        val clean = title.trim()
        if (clean.isEmpty()) return
        val next = updateMeta(id = id, title = clean) ?: return
        _conversations.value = _conversations.value.map { if (it.id == id) next else it }
    }

    /**
     * 归档 / 取消归档。与 [setPinned] 同一条规矩：只改这一位，**不动 `updatedAt`**（归档是标签，
     * 不是一次内容变更），列表顺序也不在这里动（抽屉自己过滤与排序）。
     */
    suspend fun setArchived(id: String, archived: Boolean) {
        // 同 [setPinned]：只改 meta。真机崩溃栈就是这条 ——
        // `setArchived → write → ChatStore.save` 抛「log has 83 messages, caller holds 0」。
        val next = updateMeta(id = id, archived = archived) ?: return
        _conversations.value = _conversations.value.map { if (it.id == id) next else it }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            File(dir, "$id.json").delete()
            // The transcript itself, its metadata, and whatever a crashed rewrite left behind.
            store.discard(id)
            // A deleted chat's draft is garbage nobody will ever read: the conversation it
            // belongs to is gone from the list, so nothing will look for it again.
            liveFile(id).delete()
            // **顺带清掉它留下的坏草稿**（评审 10 的 S3）：删会话是用户的明确意图，
            // 而 `.live.json.<millis>.corrupt` 不会被任何清理路径碰到，会永久残留。
            runCatching {
                liveFile(id).parentFile?.listFiles { f ->
                    f.name.startsWith("${liveFile(id).name}.") && f.name.endsWith(".corrupt")
                }?.forEach { it.delete() }
            }
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
        // Encoding is pure snapshot work; it must not block another chat's tail read.
        // The run awaits its writer's cancellation before clearing this file.
        val text = json.encodeToString(LiveDraft.serializer(), draft)
        currentCoroutineContext().ensureActive()
        lock.withLock {
            writeAtomically(liveFile(draft.chatId), text)
        }
    }

    /**
     * 这个会话有没有草稿文件（`chats/<id>.live.json`）。
     *
     * 给「切会话」用（#35）：绝大多数会话没有草稿，先 stat 一下就能省掉一个协程 + 一次读盘。
     * 是一次 `File.isFile`（~几十微秒），比起协程便宜得多。
     */
    fun hasLiveDraft(chatId: String): Boolean =
        chatId.isNotEmpty() && liveFile(chatId).isFile

    /**
     * 被中断的 run 留下的草稿；没有就返回 null。
     *
     * 解析失败时**改名留档**（`<id>.live.json.<时间戳>.corrupt`），不是删除、也不是原地保留：
     * 原地保留会让每次启动都在同一个坏文件上失败；直接删掉则丢掉了唯一能人工找回的半截图。
     * 改名之后 [readLive] 再也读不到它，[delete] 清会话时一并清掉。
     */
    suspend fun readLive(chatId: String): LiveDraft? = withContext(Dispatchers.IO) {
        val file = liveFile(chatId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(LiveDraft.serializer(), file.readText()) }
            .getOrElse {
                // **解析失败不要删**（评审建议，与「用户没发话不删文件」同口径）：
                // 草稿是进程被杀后唯一的副本；文件坏了多半只是"写到一半被切断"，
                // 里面仍可能有一段可救的正文。改成改名留下痕迹，绝不 delete。
                // 目标名带时间戳：`renameTo` 失败是**返回 false 而不是抛**，
                // 原来的 `runCatching` 抓不到（文件仍在原名 ⇒ 每次打开都重解析）；
                // 固定目标名还会让第二次损坏覆盖第一次。
                val target = File(file.parentFile, file.name + "." + System.currentTimeMillis() + ".corrupt")
                if (!file.renameTo(target)) {
                    Log.w(TAG, "could not set aside a corrupt draft at ${file.name}; leaving it in place")
                }
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
     * 一次保存：按水位**只追加尾部**，不能追加（编辑重答/中段插入）才换代重写（#31）。
     *
     * 原来的实现每轮都把整份 `Conversation` 序列化再 rename：30 MB 的会话就是 30 MB 序列化 +
     * 30 MB 写。现在交给 [ChatStore.save] 判断。
     */
    /**
     * Appends only the messages that are new since the log's last one.
     *
     * The caller holds a **page** of the conversation (the last N turns the UI shows), not the
     * whole transcript — that is by design since #30 (the list must not keep whole transcripts
     * in memory). So a save from the UI cannot hand the writer a full list; it hands it the
     * tail that is new. The history on disk stays exactly as it is.
     */
    private fun writeTail(conversation: Conversation, newMessages: List<StoredMessage>): Conversation {
        val onDisk = runCatching { store.readMeta(conversation.id) }.getOrNull()
            ?: run {
                // 评审 S2/M4：这里的兜底原来是 `return write(conversation)`，
                // 而 meta 读不出来时 `write` 只会用 newMeta + 窗口 —— 等于"窗口当全量"。
                // 现在明确拒绝：调用方拿到 Refused，界面提示"本次未写入"。
                throw IllegalStateException(
                    "meta unreadable for ${conversation.id}: refusing to fall back to a full write",
                )
            }
        val next = store.appendNew(onDisk, newMessages)
        // The meta's own fields (title / pinned / archived / model …) still come from the
        // conversation, not from the old meta.
        val updated = next.copy(
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
        store.writeMeta(updated)
        return updated.toConversation()
    }

    /** 这个会话在 store 里是否**已经有日志文件**（不管 meta 读不读得出来）。 */
    private fun logExists(id: String): Boolean =
        File(dir, STORE_DIR).listFiles { file ->
            file.name.startsWith("$id.") && file.name.endsWith(LOG_SUFFIX)
        }?.isNotEmpty() == true

    private fun write(conversation: Conversation, allowShrink: Boolean = false): Conversation {
        val started = System.currentTimeMillis()
        // **meta 读不出来 ≠ 没有历史**（评审 M4，不可逆）。
        //
        // `store.readMeta` 解析失败时返回 null（`storeMetas` 还会把它改名成 `.meta.corrupt`），
        // 而下面 `onDisk == null` 会走 `store.newMeta`（logBytes = 0）⇒ `ChatStore` 看到
        // "元数据说 0 字节，文件却非空" ⇒ **`setLength(0)` 把整份日志清空**，再写进手里的窗口。
        // 迁移出来的会话 gen 本来就是 0，被清空的往往就是那份完整日志本身。
        // 所以：只要**日志文件还在**、meta 又读不出来，就什么都不写，抛出让人看得见的错。
        if (runCatching { store.readMeta(conversation.id) }.getOrNull() == null && logExists(conversation.id)) {
            throw IllegalStateException(
                "meta unreadable but a log exists for ${conversation.id}: refusing to rewrite the log " +
                    "from a paged window",
            )
        }
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
        // **meta-only**（用量数据振荡的根因）：这里曾把「带完整消息」的会话放回列表，
        // 而置顶/改名/重启放回的都是 meta-only ⇒ 列表里那个会话的消息忽有忽无，
        // 用量页「最长单轮」（读 chat.messages）就在真值与 0 之间跳。列表统一 meta-only。
        return saved.meta.toConversation()
    }

    /**
     * 只**报告**不处理：机器上若还有旧格式的 `<id>.json`，说明它没有走过迁移（0.8.316~0.8.340）。
     *
     * 为什么保留这条日志而不彻底删干净：删掉兼容层之后这些会话会**从列表里消失**，
     * 而"消失"与"数据真的没了"在用户眼里没有区别。一条 ERROR 至少说明原因是升级路径被跳过，
     * 而不是数据丢失 —— 文件仍在 `files/chats/` 下。
     */
    private fun warnAboutLegacyFiles() {
        val legacy = dir.listFiles { f -> f.extension == "json" && !f.name.endsWith(LIVE_SUFFIX) }
        if (legacy.isNullOrEmpty()) return
        Log.e(
            TAG,
            "${legacy.size} 个旧格式会话文件（<id>.json）未迁移，不会出现在列表中。" +
                "它们需要 0.8.316~0.8.340 的版本打开过一次才能搬进 store/；" +
                "文件本身仍在 files/chats/ 下，没有被删除。",
        )
    }

    private val storeDir: File get() = File(dir, STORE_DIR)


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
        writeAndSync(temp, text)
        if (!temp.renameTo(target)) {
            // renameTo reports failure by returning false instead of throwing. Fall back
            // to a direct write and let its exception surface: a silently swallowed save
            // is how a finished turn disappears from disk.
            try {
                writeAndSync(target, text)
            } finally {
                temp.delete()
            }
        }
        // **目录也要 sync**（评审 S-6）：只 sync 文件时，"rename 已经改了目录项"这件事
        // 仍在页缓存里；断电后可能出现"文件存在但名字没生效"，草稿就凭空消失。
        // `ChatStore.writeAtomically` 早就是这么做的，这份漏了。
        runCatching {
            FileChannel.open(target.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /**
     * 写文件并 **fsync**（评审 S6）。
     *
     * 这里写的是 `last-opened` 与 `<id>.live.json`（草稿）。原来只有 `writeText`，
     * 断电/被杀时**长度已更新、内容还在页缓存里**，重启后就是一份半截 JSON：
     * 而 `readLive` 解析失败会**直接删掉草稿** —— 于是"唯一副本"变成"读到坏的就丢"。
     * `ChatStore.writeAtomically` 早就带 `fd.sync()`，这份漏了。
     */
    private fun writeAndSync(target: File, text: String) {
        FileOutputStream(target).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
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

    private companion object {
        /** 打开会话与「加载更早」每次取的轮数。 */
        const val OPEN_TURNS = 12
        const val CORRUPT_SUFFIX = ".corrupt"

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
