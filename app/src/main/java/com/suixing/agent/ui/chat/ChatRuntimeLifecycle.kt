package com.suixing.agent.ui.chat

import com.suixing.agent.agent.RunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun ChatRuntime.observe() {
    val startupNavigation = navIntents.current()
    viewModelScope.launch(Dispatchers.IO) { environment.goals.read() }
    // The engine keys its runs by conversation, so this screen subscribes to the
    // state of whichever conversation is on display: a run elsewhere neither
    // locks this screen nor narrates itself into its status line.
    viewModelScope.launch {
        state.map { it.conversationId }.distinctUntilChanged().collect { chatId ->
            // One watcher per displayed conversation: switching cancels the old
            // subscription before the new one starts, so two chats never feed one
            // status line.
            stateWatcher?.cancel()
            if (chatId.isEmpty()) return@collect
            stateWatcher = viewModelScope.launch {
                // Only a real Running → not-Running transition may drain the 排队 queue. Asking
                // on every emission is what let an unrelated publish touch the queue — and this
                // flow is not quiet: the run publishes a new Running(stats) every 120ms, so the
                // collector runs constantly while a run is going.
                var wasRunning = false
                environment.engine.stateOf(chatId).collect { run ->
                when (run) {
                    is RunState.Running -> {
                        _liveStats.value = run.stats
                        // runningHere 只在首帧真翻转；后续 120ms 的 copy 结果 equals，
                        // StateFlow 去重，不再触发屏幕重组。
                        _state.update { it.copy(runningHere = true) }
                    }

                    // Back to typing the moment the run is stopped, without waiting
                    // for the stream to wind down on its own. What already arrived
                    // is saved first: the run is over either way, and only this
                    // copy has it.
                    is RunState.Cancelled -> {
                        // Cancelled is terminal and the engine never follows it with
                        // Idle, so the screen has to be released here — otherwise the
                        // composer keeps its stop button and the conversation stays
                        // locked until the user switches away and back.
                        // stats **不**在这里清：清掉后圆环回落到 newestStored＝压缩前那一轮的
                        // 上报值（用户截图里的 90%），正是「一中断就显示之前的」。保留 stats 时
                        // 圆环继续显示本轮实测的 currentPrompt（含压缩后的真实大小）。
                        _state.update { it.copy(runningHere = false) }
                        sessions[chatId]?.let { before ->
                            // **先 flush 再取快照**（可达性已证）：这条分支由引擎的
                            // `RunState.Cancelled`（**状态**观察）触发，**不经过 `handleEvent`**
                            // ⇒ 没有任何地方替它 flush。而攒批最多 80ms 才发一次 ——
                            // 用户点「停止」时若刚到一个 chunk，它还在 `DeltaBuffer` 里。
                            // 原来 `saveSession(live)` 捕获的是 **flush 之前**那个对象，
                            // `endSession` 里的 flush 再写回 `sessions` 也追不回这份快照
                            // ⇒ 那段输出不会进文件（用户看到「最后一句话没了」）。
                            flushDeltas(chatId)
                            val live = sessions[chatId] ?: before
                            viewModelScope.launch { saveSession(live) }
                            endSession(chatId, "已停止")
                        }
                    }

                    is RunState.Idle -> _state.update {
                        // **stats 不清**（用户：「进度条那些怎么只有 run 才显示」）：圆环面板的三行
                        // 占比（系统提示词/工具定义/对话消息）读的就是它，清掉后 run 一结束三行
                        // 就消失。它是「这段上下文由什么构成」的最后一次实测，空闲时照旧显示才有
                        // 意义；下一次 run 会覆盖它。runningHere 照旧落回 false。
                        it.copy(runningHere = false)
                    }

                    else -> _state.update { it.copy(runningHere = false) }
                }
                // 排队 mode: the turn the user typed during that run is the *next* turn, and
                // this is the first moment it can be one. Gated on the transition rather than on
                // the state, so only the end of a run tries — see `wasRunning`.
                val running = run is RunState.Running
                if (wasRunning && !running) drainPendingTurns(chatId)
                wasRunning = running
            }
            }
        }
    }
    viewModelScope.launch {
        // Independent disk reads should overlap instead of serializing settings,
        // metadata and the last-opened marker before the transcript can be opened.
        val loadedChats = async(Dispatchers.IO) {
            if (environment.chats.conversations.value.isEmpty()) environment.chats.load()
        }
        val savedOpened = async(Dispatchers.IO) { lastOpenedId ?: environment.chats.readLastOpened() }
        val settings = environment.settings.current()
        val assistantId = settings.activeAssistant()?.id.orEmpty()
        // Only the cold path reads the files: when the conversations are already in
        // memory the state above is the real one, and loading them again would only
        // make the screen wait for something it already has.
        loadedChats.await()
        if (!navIntents.isCurrent(startupNavigation)) return@launch
        val conversations = environment.chats.conversations.value
        // The conversation the user was last in — and only if it is still there: the
        // marker can outlive its conversation (deleted from another entry point), and
        // opening a chat that no longer exists would leave the screen on nothing. The
        // in-memory id is preferred because it is what this process last showed; the
        // file is the cold-start answer.
        val restoredId = savedOpened.await()
        if (!navIntents.isCurrent(startupNavigation)) return@launch
        val reopened = restoredId?.let { id ->
            conversations.firstOrNull { it.id == id }
        }
        val existing = reopened ?: conversations.firstOrNull()
        if (existing == null) {
            val created = environment.chats.create()
            if (!navIntents.isCurrent(startupNavigation)) {
                runCatchingCancellable { environment.chats.delete(created.id) }
                return@launch
            }
            _state.value = ChatUiState(
                conversationId = created.id,
                title = created.title,
                assistantId = assistantId,
                loading = false,
            )
        } else {
            // **正文只读尾部**（#30）：`existing` 来自只读 meta 的列表，`messages` 是空的。
            // `readTail` 读最近 OPEN_TURNS 轮；读不到（老格式且未迁移）时它内部回退到旧文件。
            val opened = environment.chats.readTail(existing.id) ?: existing
            if (!navIntents.isCurrent(startupNavigation)) return@launch
        // 冷启动自动打开这条入口原来**完全不写缓存** ⇒ 水位对它无效（S1 只修了"点开"那一半）。
        // 从盘读到就记成"已落盘"，合并草稿时才敢清。
        if (opened.messages.isNotEmpty()) {
            cacheMessages(existing.id, opened.messages, diskBacked = true)
        }
            _state.value = opened.toUiState(assistantId).copy(loading = false)
        }
        // Recorded here as well as on a switch: a session that only ever read one
        // conversation never wrote the marker, and the next launch would fall back to
        // "most recently written" — which is not where the user was.
        rememberOpened(_state.value.conversationId)
        // Same recovery on a cold start: the run that was streaming when the process
        // died has no session any more, only its draft.
        mergeLiveDraft(_state.value.conversationId)
    }
    // The draft follows the displayed conversation: switching restores that
    // conversation's own unsent text without any action at the switch site.
    viewModelScope.launch {
        combine(state.map { it.conversationId }, _drafts) { id, all ->
            // Text has its own composer subscription; attachment changes still update the screen.
            id to (all[id] ?: Draft()).copy(text = "")
        }
            .distinctUntilChanged()
            .collect { (id, draft) ->
                if (_state.value.conversationId == id && _state.value.draft != draft) {
                    _state.update { it.copy(draft = draft) }
                }
            }
    }
}


internal fun ChatRuntime.dispose() {
    // debounce 中的「记住上次打开」可能还没落盘 —— 页面刚切走就退到后台时很容易撞上。
    // 用 appScope 写（viewModelScope 马上就要被取消），失败也无所谓：它只影响下次启动
    // 先打开哪个会话。
    lastOpenedJob?.cancel()
    lastOpenedId?.takeIf { it.isNotEmpty() }?.let { id ->
        environment.appScope.launch { runCatching { environment.chats.saveLastOpened(id) } }
    }
    // Every prompt this screen's runs put on screen is answered "refused" as the
    // screen dies; other conversations' prompts belong to their own screens.
    jobs.keys.forEach { gate.cancelPending(it) }
    // viewModelScope cancels its children on clear; these two are the explicit
    // belt to that pair of braces.
    stateWatcher?.cancel()
    jobs.values.forEach { it.cancel() }
    jobs.clear()
    attachmentCache.clear()
}
