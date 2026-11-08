package com.suixing.agent.ui.chat

import android.util.Log
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ConversationLogShrunkException
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal fun ChatRuntime.cachedMessages(chatId: String): List<StoredMessage>? = messageCache.get(chatId)


/**
 * @param diskBacked 这份 `messages` 是不是**已经在磁盘上**（决定合并草稿时能不能清草稿）。
 */
internal fun ChatRuntime.cacheMessages(
    chatId: String,
    messages: List<StoredMessage>,
    diskBacked: Boolean = false,
) {
    messageCache.put(chatId, messages, diskBacked)
}


/**
 * 缓存里的那份是否确认已落盘（与内容在同一格、同一把锁里读，**不可能脱节**）。
 *
 * 用 `entries.firstOrNull` 而**不是** `messageCache[chatId]`：后者是 `get`，在
 * `accessOrder = true` 的 LinkedHashMap 上会把这个会话移到"最近使用" ——
 * 读一个标记不该改变淘汰顺序（将来若有清理全量会话的调用，会给后台会话续命）。
 * 容量只有 5，线性找也是常数级。
 */
internal fun ChatRuntime.cacheIsDiskBacked(chatId: String): Boolean = messageCache.isDiskBacked(chatId)


/**
 * Keeps the conversation's draft file in step with the answer being streamed.
 *
 * Every 1–3s (adaptive — see [liveDraftIntervalMs]), and only when the answer, the thinking
 * or the round count actually changed: one write is an encode plus a file write plus a
 * rename, and tokens can arrive every few milliseconds. A second is the interval where a
 * kill costs the user the last tick instead of the whole answer, while a stalled stream
 * costs nothing at all; a long answer that grows by thousands of characters per second is
 * written less often, because copying the same amount again buys nothing.
 *
 * Because nothing runs after the process is killed, this is the only place the thinking
 * and the finished rounds can be saved at all — see [liveDraftFor].
 */
internal suspend fun ChatRuntime.writeLiveDraft(chatId: String) {
    var written: LiveDraft? = null
    var previous: RunSession? = null
    var previousPending = 0 to 0
    var messageSource: List<StoredMessage>? = null
    var messageRun = ""
    var runMessages: List<StoredMessage> = emptyList()
    var lastSavedChars = 0
    while (true) {
        // 自适应间隔（R4）：**先量当前还没落盘的增量，再决定睡多久**。
        //
        // 原来这里是 `lastMeasuredChars - lastSavedChars`，而 `lastMeasuredChars` 在同一拍
        // 保存之后被写成与 `lastSavedChars` 相同的值 ⇒ 差值恒为 0 ⇒ 永远 1s，2s/3s 不可达。
        // 增量的正确来源只能是“此刻内容有多长 − 上次真正落盘时多长”，所以量测必须在
        // `delay` **之前**、用不重建字符串的方式（见 [draftChars]）。
        val liveNow = sessions[chatId]
        val pendingNow = if (liveNow == null) 0
        else liveNow.draftChars(deltaBuffer.locked { deltaBuffer.sizes(chatId) }) - lastSavedChars
        delay(liveDraftIntervalMs(pendingChars = pendingNow.coerceAtLeast(0)))
        val live = sessions[chatId] ?: continue
        val pendingSize = deltaBuffer.locked { deltaBuffer.sizes(chatId) }
        val prior = previous
        if (prior != null && prior.runId == live.runId &&
            prior.messages === live.messages && prior.parts === live.parts &&
            prior.reasoningRevision == live.reasoningRevision && previousPending == pendingSize
        ) continue
        // The answer, the thinking beside it, and the rounds this run has already
        // produced. The reasoning and those rounds are not "extra": the session is the
        // only place they exist until the run ends, so a run that never ends would
        // otherwise leave a paragraph with no chain of thought and no tool cards.
        // **把还没发布的那段也算进去**：后台会话的 delta 只进缓冲、不进 `sessions`
        // （0.8.333 的"后台不发布"），而 `live.streaming` 只反映已发布的那些 ——
        // 不补的话，后台跑长回答时草稿永远是旧的/空的，进程一被杀就真丢输出。
        val (pendingText, pendingReasoning) = deltaBuffer.locked { deltaBuffer.peek(chatId) }
        // **组装也放 IO**：`derivedStreaming()` 是 O(正文长度) 的字符串重建，
        // 而原来这一段跑在 `viewModelScope`（Main）上 —— 长回答时每秒在主线程上
        // 重建一次整段正文。`saveLive` 自身已经在 IO 里，这里把整段一起放过去。
        val built = withContext(Dispatchers.IO) {
            if (messageSource !== live.messages || messageRun != live.runId) {
                runMessages = live.messages.filter { it.runId == live.runId }
                messageSource = live.messages
                messageRun = live.runId
            }
            liveDraftFor(
                chatId = chatId,
                runId = live.runId,
                text = live.derivedStreaming() + pendingText,
                // 正文在需要落盘这一刻从 `parts` 取一次快照（本块已在 IO 上）。
                reasoning = live.parts.thinkingText() + pendingReasoning,
                messages = live.messages,
                preparedMessages = runMessages,
            )
        } ?: continue
        if (!built.sameContentAs(written)) {
            environment.chats.saveLive(built)
            written = built
            // 基线只在**真正落盘**后推进；那一刻没有“未落盘增量”了。
            lastSavedChars = built.text.length + built.reasoning.orEmpty().length
        }
        previous = live
        previousPending = pendingSize
    }
}


/**
 * Turns the draft a killed run left behind back into a message.
 *
 * The rounds and the thinking the draft carries come back with the paragraph: they are
 * what the card was made of, and no other copy of them outlives a run that never saved.
 *
 * Only when the run it belongs to is not already in the transcript: a draft that outlived
 * a clean finish would otherwise return as a second bubble saying the same thing. The
 * order is append, save, then clear — clearing first would lose the text for good if the
 * save never happened.
 */
internal suspend fun ChatRuntime.mergeLiveDraft(
    chatId: String,
    /**
     * `messages` 这份 base 是不是**刚从磁盘读回来的**（S1）。
     *
     * 冷启动路径的 base 恰恰是刚读回的窗口 —— 只看 `shown == null` 会把它判成"非磁盘来源"，
     * 于是判重命中时草稿**永远不清**（每次打开白读一遍；残留草稿带旧 runId，
     * 将来 `rewrite` 丢过那条消息后会变成一张过时的"中断"卡片）。
     */
    baseFromDisk: Boolean = false,
) {
    // **活会话直接返回，且要在读草稿之前**（S4）：运行中每秒都在写草稿，
    // 排在后面等于每次切进来都白读+解析一遍。
    if (sessions[chatId] != null) return
    // 多个入口（冷启动 / 切回 / 缓存命中）可能并发进这里（S3）。
    if (!mergingLive.tryEnter(chatId)) return
    try {
        mergeLiveDraftLocked(chatId, baseFromDisk)
    } finally {
        mergingLive.leave(chatId)
    }
}


internal suspend fun ChatRuntime.mergeLiveDraftLocked(chatId: String, baseFromDisk: Boolean) {
    val draft = environment.chats.readLive(chatId) ?: return
    val live = sessions[chatId]
    // **这一轮还活着就不合并**（评审 M2）：草稿是"运行中每秒写一份"的半截正文，
    // 而活着的这一轮由引擎自己收尾、草稿由 `endSession` 清。合并它会把同一段文字
    // 存成两条（一条"（由「任务被停止」，对话中断）"结尾），模型下一轮会看到自己
    // 回答重复；顺带还会把唯一副本清掉。
    // **只要有活会话就不合并**（评审 S-5）：原来只挡"同一个 runId"，
    // 而 `live.runId != draft.runId` 时（上一轮残留的会话对象）仍会合并 ——
    // 那会把上一轮被中断的半截回答（带"（由…原因，对话中断）"尾巴）混进**当前**这一轮。
    // 活着的会话自己会收尾，草稿留给 `endSession`/`finally` 清。
    if (live != null) return
    val shown = _state.value.takeIf { it.conversationId == chatId }
    val stored = environment.chats.conversations.value.firstOrNull { it.id == chatId }
    val messages = shown?.messages ?: stored?.messages.orEmpty()
    // The rounds and tool results this run had already produced, put back before the
    // paragraph that was still being written. Until the run ends they exist nowhere else,
    // and leaving them out is what brought the card back as a bare sentence with no chain
    // of thought and no tool row — the answer with its reasoning and its work removed.
    val restored = liveDraftRecovery(
        draft,
        messages,
        // 用户自己按停：留空串 ⇒ 尾部不写任何说明（见 liveDraftRecovery）。引擎/超时那种
        // 被动中断仍写原因，用户才知道为什么断了。
        cancelReason = if (chatId in userStopped) "" else cancelReason,
    )
    // Nothing this draft holds is missing from the transcript; clearing it is all that is
    // left.
    if (restored.isEmpty()) {
        // **只有"手里这份来自磁盘"时才清草稿**（评审 10 的 M2）。
        // `messages` 可能来自活会话或 LRU 缓存 —— 里面可能有**保存失败、只在内存里**的消息
        // （`endSession` 把内存版写进了缓存）。判重命中它们就删草稿，等于把"唯一副本"
        // 在一个不可逆的方向上扔掉。宁可留着（下次还能再判一次）。
        // 走到这里 `live` 必为 null（上面已 early-return），所以判据里不需要它 —— 去掉死参数。
        val fromDisk = draftIsDiskBacked(
            shownPresent = shown != null,
            baseFromDisk = baseFromDisk,
            cacheDiskBacked = cacheIsDiskBacked(chatId),
        )
        if (fromDisk) {
            environment.chats.clearLive(chatId)
        } else {
            Log.i(TAG, "live draft kept for $chatId: its content is not disk-backed yet")
        }
        return
    }
    // **base 为空就别动磁盘**（评审 14 建议 6）：`shown` 在合并读盘期间可能变 null，
    // 而 `stored.messages` 恒为空（#30 之后列表只带 meta）⇒ `messages` 是空的 ⇒
    // `next` 只剩那条恢复消息。若此时 save 成功，会把一份**截短的历史**写进缓存并标"已落盘"，
    // 切回来命中缓存、不再读盘 ⇒ 屏幕上只显示那几条。
    // 草稿留着，等下一次真正带着 base 的合并。
    if (shouldSkipEmptBaseMerge(messages.isEmpty(), baseFromDisk, stored?.messageCount)) {
        Log.i(TAG, "live draft kept for $chatId: no base to merge into yet")
        return
    }
    // 原因消费一次即复位（**放在守卫之后**：早退时还没"消费"，别把这次的原因弄丢 ——
    // 否则下一次真合并会把用户主动停止误写成"（由「任务被停止」原因，对话中断）"）。
    cancelReason = "任务被停止"
    val next = messages + restored
    // 自愈时 `_state.messageCount` 还是 0 ⇒ `loadEarlier` 每次触顶都白读一次。
    if (shown != null) {
        _state.update { it.copy(messages = next, messageCount = maxOf(it.messageCount, next.size)) }
    }
    // `save` 返回 null = **没有写下去**（被护栏拒绝或抛异常）⇒ 草稿是唯一副本，不能清。
    val written = save(
        chatId = chatId,
        title = live?.title ?: shown?.title ?: stored?.title.orEmpty(),
        messages = next,
        assistantId = live?.assistantId ?: shown?.assistantId ?: stored?.assistantId.orEmpty(),
        activeRuns = live?.activeRuns ?: shown?.activeRuns ?: stored?.activeRuns.orEmpty(),
        providerId = live?.providerId ?: shown?.providerId ?: stored?.providerId,
        model = live?.model ?: shown?.model ?: stored?.model,
        reasoningEffort = live?.reasoningEffort ?: shown?.reasoningEffort ?: stored?.reasoningEffort,
        checkpoints = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty(),
    )
    if (written != null) {
        // **回写 LRU**（S2）：合并只改了 `_state` 与 `sessions`，而切走再切回走的是缓存 ——
        // 不回写的话，本进程内会少那条刚恢复的消息（盘上是对的，重启才看得见）。
        cacheMessages(chatId, next, diskBacked = true)
        environment.chats.clearLive(chatId)
    } else {
        Log.w(TAG, "live draft kept for $chatId: the merged copy was not written")
    }
}


/** Saves the conversation on screen. */
internal suspend fun ChatRuntime.persistDisplayed() {
    val shown = _state.value
    val title = save(
        chatId = shown.conversationId,
        title = shown.title,
        messages = shown.messages,
        assistantId = shown.assistantId,
        activeRuns = shown.activeRuns,
        providerId = shown.providerId,
        model = shown.model,
        checkpoints = shown.checkpoints,
    ) ?: return
    if (title != shown.title) _state.update { it.copy(title = title) }
}


/**
 * 保存这一轮，返回**是否真的写下去了**。
 *
 * 返回值不能被忽略（评审 M-1）：`clearLive` 会删掉草稿，而草稿是"进程被杀后唯一副本"。
 * 保存被拒（`Refused`）或抛异常时若照样清草稿，长回答在磁盘上就**一份都不剩**。
 */
internal suspend fun ChatRuntime.saveSession(live: RunSession): Boolean {
    val title = save(
        chatId = live.chatId,
        title = live.title,
        messages = live.messages,
        assistantId = live.assistantId,
        activeRuns = live.activeRuns,
        providerId = live.providerId,
        model = live.model,
        checkpoints = live.checkpoints,
    ) ?: return false
    // **写成功就把这份记成"已落盘"的缓存**（评审 13 建议 3）：停止路径走的是
    // `RunState.Cancelled` 观察者 → fire-and-forget 保存 → `endSession` 把水位降级，
    // 等 `finally` 跑到时会话已被摘掉 ⇒ 水位一直是 false ⇒ 水位对"停止留下的草稿"
    // 几乎不生效。这里补上：写成功的那份就是内存这份。
    cacheMessages(live.chatId, live.messages, diskBacked = true)
    val current = sessions[live.chatId]
    if (current != null && title != current.title) {
        putSession(current.copy(title = title))
    }
    return true
}


/** Writes one conversation to disk; returns the title it ended up with. */
internal suspend fun ChatRuntime.save(
    chatId: String,
    title: String,
    messages: List<StoredMessage>,
    assistantId: String,
    activeRuns: Map<String, String>,
    providerId: String?,
    model: String?,
    reasoningEffort: String? = null,
    checkpoints: List<ContextCheckpoint>? = null,
    /** 只有「编辑重答」传 true：它有意把改正后的问题之后的内容丢掉。 */
    allowShrink: Boolean = false,
    /** 配套声明：这次缩短手里的确是**整份历史**（护栏据此放行或拒绝）。 */
    fullHistory: Boolean = false,
): String? {
    if (chatId.isEmpty()) return null
    val previous = environment.chats.conversations.value.firstOrNull { it.id == chatId }
    val derived = if (title.isBlank() || title == "新会话") {
        messages.firstOrNull { it.role == Roles.USER }
            ?.text
            ?.replace('\n', ' ')
            ?.take(18)
            ?.takeIf { it.isNotBlank() }
            ?: title.ifBlank { "新会话" }
    } else {
        title
    }
    // **保存失败不许把 app 崩掉**（真机实测：v0.8.320 一发送就闪退，
    // 堆栈是 `ChatRepository$save` 抛的 `IllegalStateException`，
    // 它在 `viewModelScope.launch` 里没人接 ⇒ 直接崩进程）。
    //
    // 落盘失败是**用户能理解、也能恢复**的事（磁盘满、日志损坏、存储格式改过），
    // 不是"App 该死"的事。所以：接住，把原因写进气泡里的红条，用户看得见。
    val outcome = runCatchingCancellable {
        environment.chats.save(
            allowShrink = allowShrink,
            fullHistory = fullHistory,
            conversation = Conversation(
                id = chatId,
                title = derived,
                createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                messages = messages,
                assistantId = assistantId,
                activeRuns = activeRuns,
            // The conversation's own model pick survives every save: dropping it here
            // would silently hand the next run back to the persona. Saves that carry
            // no pick (a message landing, a derived title, the end of a run) fall back
            // to what is already on disk instead of clearing it.
                providerId = providerId ?: previous?.providerId,
                model = model ?: previous?.model,
            // Same trap again: the thinking level is picked in the same sheet as the
            // model, so a save that carries none must inherit the one on disk rather
            // than resetting the chat to the app-wide default.
                reasoningEffort = reasoningEffort ?: previous?.reasoningEffort,
                approvalMode = previous?.approvalMode,
            // Same trap as the model pick: a save that carries no checkpoints must
            // inherit the ones on disk, or every message landing would wipe the record
            // of what was compressed — and the next run would compress and pay again.
                checkpoints = checkpoints ?: previous?.checkpoints ?: emptyList(),
            ),
        )
    }
    return outcome.fold(
        onSuccess = { result ->
            // **被拒绝也要说出来**（评审 M3）：原来 save 返回 Unit，两处"为避免截断而跳过"
            // 只写日志 ⇒ 调用方当成成功 ⇒ 界面一切正常、重启后整轮消失。
            if (result is ChatRepository.SaveResult.Refused) {
                Log.w(TAG, "save refused for $chatId: ${result.reason}")
                _state.update {
                    if (it.conversationId != chatId) it
                    else it.copy(error = "本次未写入：${result.reason}")
                }
                null
            } else {
                derived
            }
        },
        onFailure = { cause ->
            // **"变短"不是失败**（评审 S4）：一次停机可能保存两次（`RunState.Cancelled` 分支
            // 一次、引擎 `finally` 一次）。第二次手里的快照更短时 `ChatStore.append` 会抛
            // `ConversationLogShrunkException` —— 而盘上那份其实**更全**，第一次已经写成功了。
            // 把它当错误弹红条，用户会看到一条假的「保存失败」，还会以为内容丢了。
            if (cause is ConversationLogShrunkException) {
                Log.i(TAG, "save skipped for $chatId: disk already holds a longer log", )
                null
            } else {
                Log.w(TAG, "save failed for $chatId: ${cause.message}", cause)
                _state.update {
                    if (it.conversationId != chatId) it
                    else it.copy(
                        error = "保存失败：${cause.message ?: cause.javaClass.simpleName}",
                    )
                }
                null
            }
        },
    )
}
