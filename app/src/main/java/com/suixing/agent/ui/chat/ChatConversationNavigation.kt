package com.suixing.agent.ui.chat

import android.util.Log
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Returns whether a page request was accepted; [onComplete] reports the actual merge. */
internal fun ChatRuntime.loadEarlier(turns: Int = environment.chats.openTurns(), onComplete: (Boolean) -> Unit = {}): Boolean {
    val chatId = _state.value.conversationId
    if (chatId.isEmpty()) return false
    val shown = _state.value.messages
    if (shown.isEmpty()) return false
    // 已经读到文件开头就别再读了。
    if (_state.value.messageCount in 1..shown.size) return false
    viewModelScope.launch {
        var added = false
        try {
            // **优先用字节偏移**（O(窗口)），只有偏移未知/代次不匹配时才退回按 id 扫全文件。
            val start = windowStarts[chatId]
            val window = if (start != null) {
                environment.chats.readEarlier(chatId, start.second, turns, expectGen = start.first)
            } else {
                null
            }
            // 回退路径也把窗口位置记下来：编辑重答会换 gen（旧偏移失效），
            // 不补种的话之后每次翻页都要重扫整个日志（评审 S1）。
            val fallback = if (window == null) {
                environment.chats.readEarlierBefore(chatId, shown.first().id, turns)
            } else {
                null
            }
            val older = window?.messages ?: fallback?.messages ?: return@launch
            if (older.isEmpty()) return@launch
            if (_state.value.conversationId != chatId) return@launch
            // **合并后的列表未必都在盘上**（评审 13 的 H1）：`older` 来自盘，但 `current.messages`
            // 可能含"只在内存"的消息（保存失败 / 刚合并进来的恢复消息）。无条件标"已落盘"
            // 会给后面删草稿开一条新路 —— 而草稿恰恰可能是那份内容的唯一副本。
            // 只有当**当前这份列表本身就是那份"确认已落盘"的缓存对象**时，合并结果才全部可证在盘上。
            // **`===` 是这条判据的一部分**：只有"当前列表就是缓存里那个对象"时，
            // 缓存条目的 `diskBacked` 才在描述这份内容。若将来有人在这里插 `.toList()`/`filter`，
            // 身份会静默失配 ⇒ 退化成"不敢清草稿"（方向安全，但没人会知道）。
            val baseWasDiskBacked = cacheIsDiskBacked(chatId) &&
                cachedMessages(chatId) === _state.value.messages
            // Install the page in the run first. A stream event may already hold a
            // pre-page snapshot; putSession carries these pages over that snapshot.
            var pagedRun: RunSession? = null
            sessions.computeIfPresent(chatId) { _, live ->
                val merged = prependEarlierPage(live.messages, shown.first(), older)
                if (merged != null) {
                    live.copy(messages = merged, loadedPrefix = older + live.loadedPrefix)
                        .also { pagedRun = it }
                } else {
                    live
                }
            }
            var mergedOut: List<StoredMessage>? = null
            _state.update { current ->
                // CAS may retry after a stream publication has already shown this page.
                mergedOut = null
                val merged = if (current.conversationId == chatId) {
                    prependEarlierPage(current.messages, shown.first(), older)
                } else null
                if (merged == null) {
                    current
                } else {
                    mergedOut = merged
                    current.copy(messages = merged)
                }
            }
            // 副作用放到 update 之外：`MutableStateFlow.update` 是 CAS 重试循环，lambda 可能被重跑。
            mergedOut?.let {
                cacheMessages(chatId, it, diskBacked = baseWasDiskBacked)
                added = true
            }
            // A stream publication can have displayed the installed page before
            // our CAS. That is still a successful page, even though the head changed.
            if (pagedRun != null) added = true
            if (added) {
                if (window != null && start != null) {
                    windowStarts[chatId] = start.first to window.startOffset
                } else if (fallback != null) {
                    windowStarts[chatId] = fallback.gen to fallback.startOffset
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "earlier page unavailable for $chatId", failure)
        } finally {
            onComplete(added)
        }
    }
    return true
}


/**
 * 有没有必要删掉一个**空会话**：误点"新建"、什么都没发就切走，会在列表里留一个空壳。
 *
 * 四条都要满足才删（任何一条不满足都保留）：
 *  · 盘上这条会话没有消息（`messageCount == 0`）；
 *  · 屏幕上它也没有消息（读到一半的会话不能因为内存里是空就当空壳）；
 *  · 它没有正在跑的 run（跑着的会话删了会把结果丢掉）；
 *  · 它**没有草稿** —— 用户打了字但没发送，那是有内容的，不能丢。
 */
/**
 * 离开会话时顺手回收"从未使用过"的空会话。失败就算了（下次切走再试）。
 *
 * [snapshotAtSwitch] 必须由调用方在**切走之前**捕获并传入：本函数是 fire-and-forget，
 * 执行时 `_state` 多半已经指向新会话 —— 在函数里现读 `_state` 的话，
 * `conversationId == chatId` 恒 false ⇒ "内存里还有消息"这条判据被静默禁用，
 * 盘上 0 条但内存有唯一副本的会话（保存失败红条、diskBacked=false 缓存）会被误删。
 * newConversation 路径同样先捕快照再调，两条路径判据语义一致。
 *
 * [discardInFlight] 去重：快速连切时每次都 launch 一个，不收敛就是几十个并发协程
 * 反复扫同一批 id。
 */
internal suspend fun ChatRuntime.discardIfUntouched(chatId: String, snapshotAtSwitch: ChatUiState) {
    if (chatId.isEmpty()) return
    if (sessions[chatId] != null) return
    if (!discardInFlight.add(chatId)) return
    try {
        val inMemory = if (snapshotAtSwitch.conversationId == chatId) snapshotAtSwitch.messages.size else 0
        val stored = environment.chats.conversations.value.firstOrNull { it.id == chatId }?.messageCount ?: return
        val hasDraft = environment.chats.hasLiveDraft(chatId) ||
            !_drafts.value[chatId]?.text.isNullOrBlank() || attachmentsFor(chatId).isNotEmpty()
        if (!shouldDiscardEmptyChat(stored, inMemory, snapshotAtSwitch.runningHere, hasDraft)) return
        runCatchingCancellable { environment.chats.delete(chatId) }.onSuccess { forgetConversation(chatId) }
    } finally {
        discardInFlight.remove(chatId)
    }
}


internal fun ChatRuntime.newConversation() {
    // 先取代次：之后任何挂起点结束时都要确认自己还是最新意图。
    val token = navIntents.begin()
    openingJob?.cancel()
    openingJob = null
    // 上一轮的实时统计挂在这个 ViewModel 上、全局共享：不清掉的话，新建的空会话在第一次
    // stats tick 之前仍会显示**上一个会话**的圆环读数（用户实测：新会话显示 40% / 409.5K）。
    _liveStats.value = null
    // **回收挪出关键路径**（issue #67 P0-2）：它要读草稿、可能要删文件，原来排在建会话
    // 之前 ⇒ 新建被上一会话的清理拖住。判据不变（shouldDiscardEmptyChat），
    // `discardIfUntouched` 内部的 `discardInFlight` 负责把重复清理合并成一次。
    val leavingForNew = _state.value
    if (leavingForNew.conversationId.isNotEmpty()) {
        viewModelScope.launch { discardIfUntouched(leavingForNew.conversationId, leavingForNew) }
    }
    viewModelScope.launch {
        val settings = environment.settings.current()
        if (!navIntents.isCurrent(token)) return@launch
        val created = environment.chats.create()
        // **建会话是挂起点**：期间用户可能已经点开别的会话（openConversation 会 begin 新号）。
        // 过期时只清掉自己刚建出来的空壳，绝不覆盖用户后来的选择。
        if (!navIntents.isCurrent(token)) {
            runCatchingCancellable { environment.chats.delete(created.id) }
            return@launch
        }
        leaveViewport()
        _state.value = ChatUiState(
            conversationId = created.id,
            title = created.title,
            assistantId = settings.activeAssistant()?.id.orEmpty(),
            loading = false,
        )
        // A brand-new conversation is where the user is now: without this the next
        // launch would reopen whatever was written last, not what they left open.
        rememberOpened(created.id)
    }
}


/**
 * Remembers the conversation the user is in, for the next launch.
 *
 * A file of its own rather than a timestamp on the conversation: a transcript can be
 * megabytes, and rewriting one just to say "this is where I was" would be absurd — which
 * is why "touch the conversation so it sorts first" was rejected. Fire and forget: the
 * switch must not wait for a disk write.
 */
/**
 * 记住「上次打开的是哪个」，供下次启动用。
 *
 * **不再每次切会话都写盘**（#35）：一次点击 = 一次 `writeAtomically`（临时文件 +
 * rename + fsync），而它只需要**最终值** —— 快速连点几个会话会连写几次。
 * 现在 debounce 1.2 秒：停下来才写。
 */
internal fun ChatRuntime.rememberOpened(chatId: String) {
    if (chatId.isEmpty()) return
    lastOpenedId = chatId
    lastOpenedJob?.cancel()
    lastOpenedJob = viewModelScope.launch {
        delay(LAST_OPENED_DEBOUNCE_MS)
        environment.chats.saveLastOpened(chatId)
    }
}


internal fun ChatRuntime.openConversation(conversation: Conversation) {
    if (_state.value.conversationId == conversation.id) return
    // **一次用户点击就是一个新代次**：在途的 newConversation 会因此变成过期工作，
    // 它建出来的空壳只能自己清掉，不许覆盖这次选择（issue #67 P0-1）。
    val navToken = navIntents.begin()
    leaveViewport()
    openingJob?.cancel()
    openingJob = null
    // 与 newConversation 同理：切过去的会话在它自己第一条 stats 到达前，不能顶着上一个
    // 会话的读数（圆环 / 三块占比都读这份）。
    _liveStats.value = null
    // 切走时把"什么都没发过的空会话"回收掉：判据在 shouldDiscardEmptyChat（无消息 /
    // 无草稿 / 无 run 才删），有草稿的会保留 —— 用户打了字没发那是有内容的。
    // 快照在切走前捕获（函数 KDoc 说明原因）。
    val leaving = _state.value
    viewModelScope.launch { discardIfUntouched(leaving.conversationId, leaving) }
    // Anything the conversation on screen was still holding is published before the
    // display moves: a buffered tail would otherwise land in the conversation being
    // opened, or be lost with the one being left.
    flushDeltas(_state.value.conversationId)
    // 后台会话按上面的规则只缓冲不发布 ⇒ 切回来时要先把它攒的 delta 冲进 `sessions`，
    // 否则刚建的界面缺最后一截（而且下一条事件又会把它叠在旧内容后面）。
    flushDeltas(conversation.id)
    val live = sessions[conversation.id]
    // **先查缓存**（#35）：命中就带正文建状态，连读盘协程都不用起。
    val cached = cachedMessages(conversation.id)
    // Read-and-clear: a failure that landed while another conversation was on screen
    // is reported the moment its own chat comes back.
    val failure = lastErrors.remove(conversation.id)
    val openedState = if (live != null) {
        // Coming back to the conversation a run owns: pick up what it has streamed
        // since, rather than the older snapshot on disk.
        ChatUiState(
            conversationId = live.chatId,
            title = live.title,
            messages = live.messages,
            activeRuns = live.activeRuns,
            assistantId = live.assistantId,
            providerId = live.providerId,
            model = live.model,
            reasoningEffort = live.reasoningEffort,
            approvalMode = live.approvalMode,
            checkpoints = live.checkpoints,
            streaming = live.streaming,
            reasoningRevision = live.reasoningRevision,
            status = live.status,
            runningHere = true,
            // A conversation the screen already has is not "still opening".
            loading = false,
            // The tool it is inside right now: without it the row that says what the
            // run is doing went blank on every switch back.
            runningTool = live.runningTool,
            liveParts = live.parts,
            liveRunId = live.runId,
            error = failure,
            // The queue belongs to the run, not to the screen: coming back to it must
            // still show what is waiting.
            pendingSteers = steersOf(conversation.id),
        )
    } else {
        // A conversation that never picked a persona keeps the one already on screen,
        // which was seeded from the settings when this screen was created.
        //
        // **正文要自己读**（#30）：传进来的 `conversation` 来自只读 meta 的列表，`messages`
        // 是空的。`openConversation` 不是挂起函数（它是点击回调），所以这里**先按 meta
        // 建状态、带上 loading**，正文由下面那段协程补读 —— 补读期间显示骨架，
        // 不会闪一个空白会话，也不会让 `save()` 把空列表当成"变短"。
        conversation.toUiState(_state.value.assistantId, conversationApprovalChoices[conversation.id]).let { base ->
            // **命中 LRU 缓存就直接带正文**（#35）：原来缓存命中也要先 loading=true
            // 再补读，「切走再切回」会闪一次骨架。
            if (cached != null) {
                val stored = environment.chats.conversations.value
                    .firstOrNull { it.id == conversation.id }
                base.copy(
                    messages = cached,
                    messageCount = stored?.messageCount ?: cached.size,
                    turnCount = stored?.turnCount ?: 0,
                    checkpoints = stored?.checkpoints.orEmpty(),
                    loading = false,
                    error = failure,
                    pendingSteers = steersOf(conversation.id),
                )
            } else {
                base.copy(
                    loading = true,
                    error = failure,
                    // A run that ended with lines still queued keeps them: they are the
                    // user's words, and the rows say they never went out.
                    pendingSteers = steersOf(conversation.id),
                )
            }
        }
    }
    // 排队输入属于会话。后台运行结束时只保留队列，重新打开必须把它放回屏幕；
    // 不能因为没有新 Running→terminal 事件就把用户输入永久留在不可见的表里。
    _state.value = openedState.copy(
        pendingTurns = pendingTurnsOf(conversation.id),
        todos = todosOf(conversation.id),
    )
    // **补读正文**（#30 + LRU 缓存）：上面只按 meta 建了状态，这里把最近 N 轮读进来。
    // 先查缓存：「切走再切回」在几个会话之间来回看是极常见的操作，不该每次都读盘。
    //
    // 条件都是"这个会话还在屏幕上"：用户可能已经切走，那就别再写回状态去覆盖新会话。
    // 只有**没命中缓存**才读盘（#35：命中时上面已经带正文建好状态了）。
    // 判据抽成纯函数（issue #67 P1-3）：有缓存或 live 会话时**绝不读盘** ——
    // 热 A→B→A 因此不会把同一份正文重新读一遍。
    if (needsColdRead(hasLiveSession = live != null, cachedIsPresent = cached != null)) openingJob = viewModelScope.launch {
        val tail = environment.chats.readTailWindow(conversation.id)
        val opened = tail?.let { (conversationTail, _, _) -> conversationTail }
            ?: environment.chats.readTail(conversation.id)
        // **先确认还是最新意图，再写任何共享状态**（issue #67 P1-3）：`windowStarts` 是
        // 下一次读取的起点，过期的 I/O 不能把它的值改掉。
        if (!navIntents.isCurrent(navToken)) {
            openingJob = null
            return@launch
        }
        tail?.let { (_, startOffset, gen) -> windowStarts[conversation.id] = gen to startOffset }
        // 三条判据收敛到纯函数（issue #67 P1-3），单测能逐条钉住。
        val current = _state.value
        if (opened != null && canApplyColdOpen(
                targetChatId = conversation.id,
                currentChatId = current.conversationId,
                intentCurrent = navIntents.isCurrent(navToken),
                currentIsEmpty = current.messages.isEmpty(),
            )
        ) {
            cacheMessages(conversation.id, opened.messages, diskBacked = true)
            _state.update {
                it.copy(
                    messages = opened.messages,
                    checkpoints = opened.checkpoints,
                    messageCount = opened.messageCount,
                    turnCount = opened.turnCount,
                    loading = false,
                )
            }
        } else if (_state.value.conversationId == conversation.id) {
            _state.update { it.copy(loading = false) }
        }
        // Recovery can write data, so only the read phase is superseded by another tap.
        openingJob = null
        // **尾部读完再合并草稿**（评审 10 的 S4）：这两件事原来各起一个协程，
        // 而草稿是小文件、往往先读完 ⇒ `mergeLiveDraft` 手里 `messages` 还是空
        // ⇒ 两个判重分支都看不到那条消息 ⇒ 又补一条（重复）。串起来之后才真正生效。
        // **必须 gate 在 `opened != null` 上**（评审 12 的 H1）：读盘失败时 `_state.messages`
        // 还是空列表，那不算"来自磁盘"。硬写 true 会让空 base 也获得"可以清草稿"的资格，
        // 而且紧接着的 `save` 在"盘上已有内容"时必被护栏拒绝（既不是全量也不是尾部）⇒ 假红条 +
        // 只剩一条消息的画面。（"盘上本来就 0 条"那个例外见 `shouldSkipEmptBaseMerge`：那时放行安全且能自愈。）
        // 读失败就把草稿留给下一次打开。
        if (opened != null &&
            _state.value.conversationId == conversation.id &&
            environment.chats.hasLiveDraft(conversation.id)
        ) {
            mergeLiveDraft(conversation.id, baseFromDisk = true)
        }
        if (opened != null) resumePendingTurns(conversation.id)
    }
    // A run that was killed left its half-written answer in a draft file: it belongs to
    // this conversation, so it is picked up the moment the conversation is.
    // 绝大多数会话**没有草稿**（`.live.json` 只在一轮跑到一半被杀时才有），
    // 所以先 stat 一下再决定要不要起协程（#35）。
    if (cached != null && environment.chats.hasLiveDraft(conversation.id)) {
        // 命中 LRU 缓存时正文已经在手里（不需要读盘），可以直接合并。
        viewModelScope.launch {
            mergeLiveDraft(conversation.id)
            resumePendingTurns(conversation.id)
        }
    } else if (cached != null) {
        resumePendingTurns(conversation.id)
    }
    // Where the user is, for the next launch.
    rememberOpened(conversation.id)
}


/**
 * 忘掉一个会话名下**所有**按会话保存的运行时状态（issue #38）。
 *
 * 原来删除会话只清了 `steers`，其余这些表都没有容量上限、也不会被淘汰：
 * `pendingTurns` 最重（排队未发的消息，**含 base64 图片，可达几 MB**），
 * 其次是 `todoBoards`、`lastErrors`、`windowStarts`、`attachmentCache`、LRU 正文缓存。
 * 会话数量只增不减 ⇒ 这些残留会一直堆着。
 */
internal fun ChatRuntime.forgetConversation(chatId: String) {
    conversationApprovalChoices.remove(chatId)
    clearSteers(chatId)
    _drafts.update { it - chatId }
    pendingTurns.forget(chatId)
    todoBoards.remove(chatId)
    lastErrors.remove(chatId)
    windowStarts.remove(chatId)
    attachmentCache.remove(chatId)
    messageCache.remove(chatId)
    userStopped -= chatId
}


internal fun ChatRuntime.deleteConversation(conversation: Conversation) {
    // Published before the conversation goes: a buffered tail is part of an answer that
    // may still be streaming into a chat that is about to disappear from the list.
    flushDeltas(conversation.id)
    // The run owns its conversation until it ends: deleting it would only have the
    // next save write it straight back.
    if (sessions.containsKey(conversation.id)) {
        // **不许静默拒绝**（issue #38）：用户点了删除却毫无反应，分不清是被拒绝还是卡了。
        _state.update {
            if (it.conversationId != conversation.id) it
            else it.copy(error = "这个会话正在执行任务，先停止或等它结束再删除")
        }
        return
    }
    // Nothing left to hand a queued line to.
    clearSteers(conversation.id)
    viewModelScope.launch {
        val deleted = runCatchingCancellable { environment.chats.delete(conversation.id) }
        if (deleted.isFailure) {
            _state.update {
                it.copy(error = deleted.exceptionOrNull()?.message ?: "会话删除失败，请重试")
            }
            return@launch
        }
        forgetConversation(conversation.id)
        if (conversation.id != _state.value.conversationId) return@launch
        // 删除后落到另一条会话（或一条都没有）必须走**和用户点击同一条**路径：
        // 直接 `_state.value = next.toUiState(...)` 只换了 id 和标题，正文、viewport 恢复、
        // 草稿合并、rememberOpened 全都没做 ⇒ 屏幕是空正文（看着像新会话），
        // 而 conversationId 已经指到列表里那条会话，抽屉于是高亮一个屏幕上没有内容的会话。
        // [environment.chats.delete] 返回前已把它移出 conversations 流，这里取到的不会是刚删的那条。
        val next = environment.chats.conversations.value.firstOrNull()
        if (next == null) newConversation() else openConversation(next)
    }
}
