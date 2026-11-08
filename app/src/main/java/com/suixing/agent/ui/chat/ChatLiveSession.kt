package com.suixing.agent.ui.chat

import com.suixing.agent.data.TodoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Guards the delta buffers **and** the live copy they are published into.
 *
 * One lock for both, because publishing is one step: the buffered text is taken out of
 * the buffer and written into the live copy of the conversation, and the two must not
 * come apart — a live copy written from a stale read throws the text away again.
 *
 * A plain monitor rather than a `Mutex`: the accessors are not suspending functions
 * (the engine's event callback is a plain call), and a `Mutex` can only be taken from
 * inside a coroutine. The same shape as [PendingMessageQueues]' internal monitor.
 *
 * Held for one event and nothing else: no suspending call, no callback into this class
 * except the [removeSteer]/[publishSteers] pair of the 插话 event, which takes
 * the steering queue's monitor — always in that order. Queue methods never call back
 * while holding their monitor, so there is no reverse lock order.
 */

/**
 * Appends a piece of the answer and of the thinking to the buffers of [chatId].
 *
 * **Caller holds the delta lock.** A [StringBuilder] that one thread writes while another
 * reads it out of the map is a garbled answer, and a buffer that is taken out of the map
 * while a delta is being appended to it is a delta that is never published.
 */
internal fun ChatRuntime.appendDeltaLocked(chatId: String, text: String, reasoning: String) =
    deltaBuffer.append(chatId, text.ifEmpty { null }, reasoning.ifEmpty { null })


/**
 * Whether the buffered text may be published now. The clock is read and set in one step,
 * so two threads cannot both decide that they may publish.
 *
 * **Caller holds the delta lock.**
 */
internal fun ChatRuntime.mayPublishLocked(chatId: String, now: Long): Boolean =
    deltaBuffer.mayPublish(chatId, now, DELTA_PUBLISH_MS)


internal fun ChatRuntime.scheduleTailFlush(chatId: String) {
    if (tailFlushJobs[chatId]?.isActive == true) return
    tailFlushJobs[chatId] = viewModelScope.launch {
        delay(DELTA_PUBLISH_MS)
        tailFlushJobs.remove(chatId)
        flushDeltas(chatId)
    }
}


internal fun ChatRuntime.cancelTailFlush(chatId: String) {
    tailFlushJobs.remove(chatId)?.cancel()
}


/**
 * Takes the buffered text and thinking of [chatId] out and hands them over.
 *
 * **Caller holds [deltaLock]** — the take and the publish that follows it are one step.
 */
internal fun ChatRuntime.takeDeltasLocked(chatId: String): Pair<String, String> =
    deltaBuffer.take(chatId)


/** The board of one conversation, read under [todoBoardsLock]. */
internal fun ChatRuntime.todosOf(chatId: String): List<TodoItem> =
    synchronized(todoBoardsLock) { todoBoards[chatId].orEmpty() }


/** Replaces the board of one conversation — the read and the write are one step. */
internal fun ChatRuntime.setTodos(chatId: String, board: List<TodoItem>) {
    synchronized(todoBoardsLock) { todoBoards[chatId] = board }
}


/**
 * 把这一拍攒下来的正文/思考并进 [live]。
 *
 * **只做一次 `copy`**：原来每有一种增量就 copy 一次（每次都重建整份 Session）。
 * 一轮跑起来时这个方法每 80ms 调一次，少一次全量复制就少一次分配与 GC 压力；
 * 字段与取值顺序与改动前逐字一致，行为不变。
 *
 * 正文**不再另存一份**（`streaming` 留空，由 [derivedStreaming] 派生）——
 * 那是 issue #38 的第 3 条：同一段文本存两份、每个 delta 各拼一遍。
 */
internal fun ChatRuntime.applyPending(live: RunSession, text: String, reasoning: String): RunSession {
    val hasText = text.isNotEmpty()
    val hasReasoning = reasoning.isNotEmpty()
    if (!hasText && !hasReasoning) return live
    val opening = hasReasoning && !live.parts.hasOpenThinking(live.segmentStart)
    val parts = when {
        hasText && hasReasoning ->
            live.parts.appendThinking(reasoning, live.segmentStart).appendText(text, live.segmentStart)
        hasText -> live.parts.appendText(text, live.segmentStart)
        else -> live.parts.appendThinking(reasoning, live.segmentStart)
    }
    // **正文只存一份**（issue #38 第 3 条）：原来 `streaming` 与 `parts` 里的 Text 块
    // 各存一份正文，每个 delta 各拼一遍 —— CPU 与内存都是双份。
    // 现在 `streaming` 由 `parts` 派生（见 `derivedStreaming`），写入路径只碰 `parts`。
    return live.copy(
        streaming = "",
        // 有思考就自增修订号：这就是「状态变了」的信号，取代原来的整串复制。
        reasoningRevision = if (hasReasoning) live.reasoningRevision + 1 else live.reasoningRevision,
        // **这里必须继续产出新 String**（issue #57 §3.3，勿改成共享缓冲）：
        // 思考流式之所以还能刷新，正是因为每拍 `reasoning` 是新实例 ⇒ `ChatUiState`
        // 整体判不等 ⇒ 状态发射。换成 `GrowableText` 共享缓冲会让这条触发器失效
        // （`LiveParts.kt` 的 KDoc 记着那次「整屏冻结」事故）；同时保存路径的
        // `prior.reasoning === live.reasoning` 判等会恒真 ⇒ 草稿永远不会落盘。
        // 代价是长思考期 62.5 次/秒的整串复制 —— 已知取舍，不再往缓冲方向改。
        // 正文不再存副本（见 `reasoningRevision` 的说明）。
        parts = parts,
        status = "",
        thinkingStartedAt = if (opening) System.currentTimeMillis() else live.thinkingStartedAt,
    )
}


/**
 * Publishes whatever is still buffered for [chatId].
 *
 * Called before anything that reads the session as a whole — saving it, switching away
 * from it — because the buffered text is part of the answer and dropping it here is how a
 * stopped or failed run would lose its last words.
 */
internal fun ChatRuntime.flushDeltas(chatId: String) {
    cancelTailFlush(chatId)
    val live = sessions[chatId]
    if (live == null) {
        // 会话已经没了：缓冲里的文字无处可去，**必须丢掉**。
        // 留着它会在同一个会话的**下一轮**被 `takeDeltasLocked` 取走、拼到新内容前面 ——
        // 那是「新回答开头莫名多出一段上一轮的话」这种幽灵 bug，且极难复现。
        deltaBuffer.drop(chatId)
        return
    }
    // 同一个锁：取走与事件线程的 append 互斥（否则刚收到的 delta 会被这次 flush 顺手带走）。
    val (text, reasoning) = deltaBuffer.locked { takeDeltasLocked(chatId) }
    if (text.isEmpty() && reasoning.isEmpty()) return
    putSession(applyPending(live, text, reasoning))
}


/**
 * Installs [next] as its conversation's live copy and, when that conversation is
 * the one on screen, copies the live parts of it onto the screen. A run in some
 * other conversation changes nothing here at all — the map keeps it apart, and
 * the display only ever reads its own entry.
 */
internal fun ChatRuntime.putSession(next: RunSession) {
    val installed = sessions.compute(next.chatId) { _, current ->
        if (current != null && current.runId == next.runId && current.loadedPrefix.size > next.loadedPrefix.size) {
            next.copy(
                messages = carryEarlierPages(next.messages, next.loadedPrefix.size, current.loadedPrefix),
                loadedPrefix = current.loadedPrefix,
            )
        } else {
            next
        }
    } ?: return
    // **不在这里刷 LRU 缓存**（#35）：流式期间每 ~80ms 一次 `putSession`，每次都要动缓存
    // （`MessageCache` 内部是一把监视器锁），而切会话时的 `readTail` 读盘也要用同一把 ——
    // 撞在一起就是可感知的抖动。缓存只在「一轮结束」时刷（见 [endSession]）就够：
    // 一轮中途切回来走的是 `sessions`（live 分支），根本不看缓存。
    _state.update { snapshot ->
        // Re-read inside the CAS: pagination can install/publish a newer copy
        // after this call's compute, and a CAS retry must keep that copy.
        val latest = sessions[next.chatId] ?: installed
        if (snapshot.conversationId != latest.chatId) {
            snapshot
        } else {
            snapshot.copy(
                title = latest.title,
                messages = latest.messages,
                activeRuns = latest.activeRuns,
                streaming = latest.streaming,
                reasoningRevision = latest.reasoningRevision,
                status = latest.status,
                runningTool = latest.runningTool,
                liveParts = latest.parts,
                liveRunId = latest.runId,
                error = latest.error ?: snapshot.error,
            )
        }
    }
}


/**
 * Drops the run's claim on its conversation. The screen keeps whatever the user was
 * looking at: the run's own conversation loses its streaming state, any other one is
 * left exactly as it was.
 */
internal fun ChatRuntime.endSession(chatId: String, status: String, error: String? = null) {
    if (_state.value.conversationId == chatId) _liveStats.value = null
    // **先把攒着的 delta 冲进去，再摘会话**（0.8.333 引入的风险）：后台会话的 delta 现在
    // 只缓冲不发布（屏幕上看不到它，发布只是白做 O(n) 拼接）。而一轮结束是
    // `sessions.remove(chatId)` —— 若此时缓冲里还有没应用的尾巴，那段文字既不会进
    // 将要落盘的消息，也不会再有人来取（`flushDeltas` 找不到会话就直接返回）。
    // 用户看到的就是「回答少了一截」，且没有任何报错。
    flushDeltas(chatId)
    val live = sessions.remove(chatId)
    deltaBuffer.drop(chatId)
    // 一轮结束：`sessions` 里那份没了，切回来只能靠 LRU 缓存 ⇒ 在这里刷一次
    // （#35：原来是在 `putSession` 里每 80ms 刷，太频繁）。
    // **内存版**：可能含保存失败、只在内存的消息 ⇒ 水位显式降级，别拿去清草稿。
    if (live != null) cacheMessages(chatId, live.messages, diskBacked = false)
    val failure = error ?: live?.error
    // A run that failed while another conversation was on screen has no state to land
    // in — its own copy is gone a line above — so the reason waits here until that
    // conversation is opened again. Otherwise the user comes back to half an answer
    // and no explanation.
    if (failure != null && _state.value.conversationId != chatId) lastErrors[chatId] = failure
    _state.update { snapshot ->
        if (live != null && snapshot.conversationId == chatId) {
            snapshot.copy(
                streaming = "",
                reasoningRevision = 0,
                status = status,
                runningTool = null,
                liveParts = emptyList(),
                liveRunId = "",
                // 与 liveRunId 同一次发射落下：否则 runningHere 由 RunState.Idle 的
                // watcher 晚一拍才清，中间会有一帧「runId 已空但仍在跑」，live item
                // 以空 parts 挂着先塌再弹 —— 就是新轮次收口时那一下高度跳变。
                runningHere = false,
                error = failure ?: snapshot.error,
            )
        } else {
            snapshot
        }
    }
}


/**
 * Applies [change] to the conversation on screen. While that conversation is the one
 * a run owns, the change goes into the run's copy as well — otherwise the next
 * streamed token would mirror the older messages back over the edit.
 */
internal fun ChatRuntime.editDisplayed(change: (ChatUiState) -> ChatUiState) {
    _state.update(change)
    val shown = _state.value
    val live = sessions[shown.conversationId] ?: return
    putSession(
        live.copy(
            title = shown.title,
            assistantId = shown.assistantId,
            messages = shown.messages,
            activeRuns = shown.activeRuns,
            // Carried too: an edit can drop a compression record, and a session still
            // holding the old list would write it back at the end of the run.
            checkpoints = shown.checkpoints,
        ),
    )
}
