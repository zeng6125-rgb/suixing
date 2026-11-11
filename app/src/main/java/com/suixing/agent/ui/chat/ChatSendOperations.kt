package com.suixing.agent.ui.chat

import android.util.Log
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.TranscriptRedaction
import com.suixing.agent.llm.LocalNetworkAccess
import com.suixing.agent.llm.ToolCallRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// -------------------------------------------------------------------- turns

/** Returns false when the message was dropped, so the caller keeps the input text. */
internal fun ChatRuntime.send(text: String, imagesBase64: List<String> = emptyList()): Boolean {
    val body = text.trim()
    if (body.isEmpty() && imagesBase64.isEmpty()) return false
    val chatId = _state.value.conversationId
    if (chatId.isEmpty()) return false
    val command = parseSlashCommand(body)
    val initialTool = command?.let { prepareSlashTool(chatId, it, imagesBase64) ?: return false }
    // No global lock: the engine refuses a second run in the same conversation
    // (two runs would interleave into one transcript), and every other
    // conversation is free to start its own run right now.
    if (environment.engine.isRunning(chatId)) return false

    // Android 17（API 37）起，发往内网的请求必须先有 ACCESS_LOCAL_NETWORK，否则只会超时，
    // 还会被重试 5 次后才报「连接超时」——把权限问题伪装成网络问题。这里先拦下来，
    // 直接告诉用户去哪儿授权，不发出那 5 次注定失败的请求。
    val appContext = runCatching { environment.appContext }.getOrNull()
    val settingsNow = settingsSnapshot.value
    if (appContext != null && settingsNow != null &&
        LocalNetworkAccess.blockedByMissingPermission(
            appContext,
            effectiveBaseUrl(settingsNow, _state.value),
        )
    ) {
        _state.update {
            it.copy(error = "Android 17 起访问局域网模型需要「局域网访问」权限：到「设置 → 模型」点一次" +
                "「测试连接」会弹出系统授权，允许后再发送。")
        }
        return false
    }

    // **Preparation is a window of its own** (issue #76 F-06): a long conversation
    // reads its full wire before `start()` runs, and during that read the engine is
    // not Running yet. Without this guard a second tap starts a second chain from a
    // different window snapshot. The gate is per chat; A preparing never blocks B.
    // A session that is still winding down also owns the slot: engine.isRunning may
    // already be false while `start()` is finishing its save.
    if (!sendGate.tryBegin(
            chatId = chatId,
            busy = environment.engine.isRunning(chatId) || sessions.containsKey(chatId),
        )
    ) {
        return false
    }

    val user = StoredMessage(
        id = UUID.randomUUID().toString(),
        role = Roles.USER,
        text = initialTool?.let { TranscriptRedaction.commandForTranscript(body, it.name, it.arguments) } ?: body,
        imagesBase64 = imagesBase64,
    )
    val fresh = UUID.randomUUID().toString()
    // Captured before the optimistic update so a synchronous start failure can put the
    // whole screen back, not just drop the message (issue #76: a refused send keeps the
    // text the user left in the field).
    val before = _state.value
    _state.value = before.acceptSend(user, fresh)
    // The state this accepted line belongs to. The read below may outlive a switch to
    // another conversation, so the run has to be started for this snapshot and this
    // chat id — not for whatever `_state` happens to show when the read returns.
    val snapshot = _state.value
    // **喂给模型的历史必须是完整 wire，不是屏幕上那个窗口**（#30 最要紧的一条）。
    // 打开会话只读最近 OPEN_TURNS 轮，`_state.value.messages` 就是那个窗口；
    // 直接拿它当历史 ⇒ 模型看不到更早的对话，**不报错、只是变笨**，比崩溃难查得多。
    //
    // 用接受前的窗口与最新落盘总量判定完整性，新增消息不能把部分窗口凑成完整历史。
    // 完整窗口直接发；否则先进协程读取摘要与完整尾部。
    val shownMessages = snapshot.messages
    val storedCount = environment.chats.conversations.value.firstOrNull { it.id == chatId }?.messageCount ?: 0
    if (maxOf(before.messageCount, storedCount) <= before.messages.size) {
        try {
            start(chatId, fresh, shownMessages, snapshot, initialTool)
        } catch (cancelled: CancellationException) {
            _state.update { it.rollbackSend(before, user.id, fresh) }
            throw cancelled
        } catch (failure: Throwable) {
            // `start` can refuse synchronously (scope gone, engine bookkeeping). The
            // line never reached a run, so it must not stay on screen as a ghost or keep
            // the caller from restoring the draft.
            _state.update { it.rollbackSend(before, user.id, fresh) }
            // `start` may have installed the run's live copy before it threw; that copy
            // has no job behind it and would make `sessions.containsKey` block every
            // later send. The board it cleared comes back with the same snapshot.
            sessions.remove(chatId)
            jobs.remove(chatId)
            setTodos(chatId, before.todos)
            Log.w(TAG, "send start failed for $chatId", failure)
            _state.update {
                if (it.conversationId != chatId) it
                else it.copy(error = "发送失败：${failure.message ?: failure.javaClass.simpleName}")
            }
            return false
        } finally {
            sendGate.finish(chatId)
        }
    } else {
        viewModelScope.launch {
            try {
                // **先落盘再读**（issue #76 F-03）：切会话只决定屏幕上画哪一份，
                // 不撤销一条已经接受的消息。进程在读盘期间被杀时，这一行是磁盘上
                // 唯一的那条；读盘完成后 `start()` 会从同一份 wire 接着跑。
                runCatching {
                    save(
                        chatId = chatId,
                        title = snapshot.title,
                        messages = snapshot.messages,
                        assistantId = snapshot.assistantId,
                        activeRuns = snapshot.activeRuns,
                        providerId = snapshot.providerId,
                        model = snapshot.model,
                        reasoningEffort = snapshot.reasoningEffort,
                        checkpoints = snapshot.checkpoints,
                    )
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    Log.w(TAG, "pre-send save failed for $chatId", failure)
                }
                // 读盘失败不能让发送带崩（`viewModelScope` 里未捕获的异常会打到主线程）。
                //
                // 降级方式：用屏幕上的历史继续发。**这里刻意不写 `_state.error`** ——
                // `start()` 开头就会 `error = null`（它要清掉上一轮的红条），
                // 在这里写等于白写；而下一次 `start` 之后用户已经看到消息发出去了，
                // 再补一条红条只会让人以为这一轮失败了。上下文不完整是次要的，
                // 而且坏数据本身已经在 `ChatStore` 层被记下（`LogDamaged`/`LogMismatch`）。
                val wire = runCatchingCancellable { environment.chats.readWire(chatId, snapshot.checkpoints) }.getOrNull()
                // **不要因为用户切走就丢掉这一条**（issue #76 F-03）：run 属于原会话，
                // `start()` 只在当前显示的仍是它时才画到屏幕上；后台会话的流式内容
                // 会进 `sessions`，跑完后由 `endSession` 落盘。
                val history = sendHistory(wire?.messages, shownMessages, user)
                start(chatId, fresh, history, snapshot, initialTool, resumeCheckpoint = wire?.checkpoint)
            } finally {
                sendGate.finish(chatId)
            }
        }
    }
    clearDraft(chatId)
    return true
}


/** Resolve a typed command without executing it; the accepted run owns its tool card. */
internal fun ChatRuntime.prepareSlashTool(
    chatId: String,
    command: SlashCommand,
    images: List<String>,
): ToolCallRequest? {
    fun reject(message: String): ToolCallRequest? {
        _state.update { if (it.conversationId == chatId) it.copy(error = message) else it }
        return null
    }
    if (images.isNotEmpty()) return reject("斜杠指令不能带附件")
    val settings = settingsSnapshot.value
    val assistantId = sessions[chatId]?.assistantId
        ?: _state.value.takeIf { it.conversationId == chatId }?.assistantId.orEmpty()
    val assistant = settings?.assistant(assistantId) ?: settings?.activeAssistant()
    val disabled = assistant?.disabledTools?.toSet().orEmpty()
    val name = command.toolName()
    val tool = environment.tools.excluding(disabled).find(name)
        ?: return reject(if (!slashToolAllowed(name, disabled)) "当前助手禁用此工具：/${command.name}"
            else "未知指令或工具：/${command.name}")
    val args = command.toolArguments(tool.spec)
        ?: return reject("参数格式不对：需要 JSON 对象，或用一句文本")
    return ToolCallRequest(UUID.randomUUID().toString(), tool.spec.name, args.toString())
}


/** Answers the last question again, keeping the old reply as a branch. */
internal fun ChatRuntime.regenerate() {
    if (environment.engine.isRunning(_state.value.conversationId)) return
    val snapshot = _state.value
    val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
    val question = active.lastOrNull { it.role == Roles.USER } ?: return
    val prompt = Transcript.withoutLastAnswer(active)
    val fresh = UUID.randomUUID().toString()
    _state.update {
        it.copy(
            activeRuns = it.activeRuns + (question.id to fresh),
            streaming = "",
            reasoningRevision = 0,
            status = "正在重新回答…",
            error = null,
            // **立刻挂上 liveRunId**（用户：「按下的时候就应该把已经输出的一轮隐藏」）：
            // 它通常要等第一条 live 更新才被设上，那之间旧答案会闪一下。先设上，
            // 组行时 `chosenFrom` 就能认出"这是正在生成的新 run"并清空该轮。
            // 若生成失败，`liveRunId` 会被清空（见 :2906 的 `liveRunId = ""`），
            // 判据随之失效、旧答案自动回退显示。
            liveRunId = fresh,
        )
    }
    val acceptedSnapshot = _state.value
    val storedCount = environment.chats.conversations.value
        .firstOrNull { it.id == snapshot.conversationId }?.messageCount ?: 0
    if (maxOf(snapshot.messageCount, storedCount) <= snapshot.messages.size) {
        start(fresh, prompt)
    } else {
        viewModelScope.launch {
            val wire = runCatchingCancellable { environment.chats.readWire(snapshot.conversationId, snapshot.checkpoints) }.getOrNull()
            var resumeCheckpoint = wire?.checkpoint
            var fullPrompt = wire?.messages?.takeIf { it.isNotEmpty() }?.let {
                Transcript.withoutLastAnswer(Transcript.active(it, snapshot.activeRuns))
            } ?: prompt
            if (resumeCheckpoint != null && fullPrompt.none { it.id == resumeCheckpoint.toMessageId }) {
                val full = runCatchingCancellable { environment.chats.readAll(snapshot.conversationId) }.getOrNull()
                fullPrompt = full?.let { Transcript.withoutLastAnswer(Transcript.active(it, snapshot.activeRuns)) } ?: prompt
                resumeCheckpoint = null
            }
            if (_state.value.conversationId != snapshot.conversationId ||
                environment.engine.isRunning(snapshot.conversationId)
            ) return@launch
            start(snapshot.conversationId, fresh, fullPrompt, acceptedSnapshot, resumeCheckpoint = resumeCheckpoint)
        }
    }
}


/** Draws another candidate answer for [turnId]; the transcript itself is untouched. */
internal fun ChatRuntime.switchBranch(turnId: String, runId: String) {
    if (_state.value.activeRuns[turnId] == runId) return
    editDisplayed { it.copy(activeRuns = it.activeRuns + (turnId to runId)) }
    viewModelScope.launch { persistDisplayed() }
}


/**
 * Rewrites a message in place without asking the model anything. This is how a
 * typo gets fixed, or a long reply gets trimmed down to the part worth keeping.
 *
 * The edit lands as a *new* list with a *new* message instance (`map` + `copy`), which is what
 * makes the screen redraw it: its derivations are keyed on [ChatUiState.messagesRevision], and
 * the revision moves when the list is replaced — by reference, so an edit that keeps every id
 * and the same row count still counts as a change. An in-place mutation here would not.
 */
internal fun ChatRuntime.editMessage(id: String, text: String) {
    val body = text.trim()
    if (body.isEmpty()) return
    editDisplayed { snapshot ->
        val positions = snapshot.messages.firstPositions()
        val index = positions[id] ?: -1
        snapshot.copy(
            messages = snapshot.messages.map { message ->
                if (message.id == id) editedTranscriptMessage(message, body) else message
            },
            // A summary that covers the edited message no longer describes what the model
            // will read, and a stale one would quietly hand it the text from before the
            // fix — exactly the fact the user came back to correct. Only the records that
            // end before the edit are still accurate.
            checkpoints = if (index < 0) {
                snapshot.checkpoints
            } else {
                snapshot.checkpoints.filterNot { checkpoint ->
                    (positions[checkpoint.toMessageId] ?: -1) >= index
                }
            },
        )
    }
    viewModelScope.launch { persistDisplayed() }
}


/**
 * Rewrites the user message [id] and asks again from there. Everything the model
 * said afterwards is dropped: those answers belong to a question that no longer
 * exists, so keeping them would hang them off the wrong prompt.
 */
internal fun ChatRuntime.editAndResend(id: String, text: String) {
    if (environment.engine.isRunning(_state.value.conversationId)) return
    val body = text.trim()
    if (body.isEmpty()) return
    val snapshot = _state.value
    val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
    val index = active.indexOfFirst { it.id == id }
    if (index < 0) return
    val command = parseSlashCommand(body)
    val initialTool = command?.let {
        prepareSlashTool(snapshot.conversationId, it, active[index].allImages) ?: return
    }
    val storedText = initialTool?.let { TranscriptRedaction.commandForTranscript(body, it.name, it.arguments) } ?: body
    val kept = active.take(index) + active[index].copy(text = storedText, edited = true)
    val fresh = UUID.randomUUID().toString()
    // **顺序很重要**（评审 M-2/M-4）：先落盘、成功了再起这一轮。
    // 原来先改屏幕再 `start()`，而 `start` 的第一件事就是 `saveSession`（allowShrink=false）——
    // 盘上那份的末尾还是**被丢掉的那条旧回答** ⇒ 对不上 ⇒ 护栏拒绝 ⇒ 界面挂一条"本次未写入"，
    // 而编辑其实已经写成功了；顺带模型拿到的还是窗口版。
    viewModelScope.launch {
        val full = runCatchingCancellable { environment.chats.readAll(snapshot.conversationId) }.getOrNull()
        val at = full?.indexOfFirst { it.id == id } ?: -1
        if (full == null || at < 0) {
            Log.w(TAG, "edit not persisted: full history unavailable for ${snapshot.conversationId}")
            // **把用户刚输入的文字放回输入框**（只改正文，别清掉附件）。
            backfillDraftText(snapshot.conversationId, body)
            _state.update {
                if (it.conversationId != snapshot.conversationId) it
                else it.copy(error = "编辑没有写入磁盘：读不到完整历史（已把文字放回输入框）")
            }
            return@launch
        }
        val keptFull = full.take(at) + full[at].copy(text = storedText, edited = true)
        // **被改的那条若落在某个压缩区间里，那个摘要就已经在说谎**（评审 M-4）：
        // `readWire` 只从最后一个 checkpoint 之后开始发，模型会拿到旧摘要而不是改后的句子。
        // 所以丢掉"覆盖它"的那个 checkpoint 以及**之后的所有** checkpoint（历史改从更早处回放）。
        val positions = full.firstPositions()
        val safeCheckpoints = snapshot.checkpoints
            .takeWhile { cp ->
                val from = positions[cp.fromMessageId] ?: Int.MAX_VALUE
                val to = positions[cp.toMessageId] ?: Int.MAX_VALUE
                at !in from..to
            }
        // 先落盘：`fullHistory = true` 是诚实声明（手里确实是整份），护栏据此放行。
        val written = save(
            chatId = snapshot.conversationId,
            title = snapshot.title,
            messages = keptFull,
            assistantId = snapshot.assistantId,
            activeRuns = snapshot.activeRuns,
            providerId = snapshot.providerId,
            model = snapshot.model,
            reasoningEffort = snapshot.reasoningEffort,
            checkpoints = safeCheckpoints,
            allowShrink = true,
            fullHistory = true,
        )
        if (written == null) {
            // 落盘失败同理：文字回到输入框，别让它连同那段编辑一起消失。
            backfillDraftText(snapshot.conversationId, body)
            return@launch
        }
        // **两次 IO 之后必须复检前置条件**（评审 8 第 4 条）：`readAll` + `save` 在大会话上
        // 是百毫秒到秒级，这期间用户可能切走了会话、或已经发起了另一轮 —— 那时再 `start()`
        // 会拿**旧会话的全文**当新会话的 wire 发出去（回答写进别人的会话），
        // 或与已在跑的那一轮撞车（引擎回 Failed，第二个 run 的 finally 还会把会话摘掉）。
        // **复检失败不能只记日志**（评审 9 第 3 条）：编辑已经落盘了，而
        // `messageCache` 还留着编辑前的窗口 —— 切回来会用旧缓存，再发一条时
        // 「编辑本该丢掉的旧回答」会被当成尾部追加回日志，等于**把编辑撤销**。
        if (_state.value.conversationId != snapshot.conversationId) {
            Log.w(TAG, "edit not started: the conversation changed while saving")
            cacheMessages(snapshot.conversationId, kept, diskBacked = true)
            return@launch
        }
        if (environment.engine.isRunning(snapshot.conversationId)) {
            Log.w(TAG, "edit not started: a run began while saving")
            // 那一轮的 session 是按**编辑前**的状态建的，这里不再起跑；
            // 至少把编辑后的窗口写进缓存 + 把文字还回输入框，并明确告诉用户。
            cacheMessages(snapshot.conversationId, kept, diskBacked = true)
            backfillDraftText(snapshot.conversationId, body)
            _state.update {
                if (it.conversationId != snapshot.conversationId) it
                else it.copy(
                    error = "这次编辑已保存，但当前有一轮正在运行，没有立刻重新回答（文字已放回输入框）",
                )
            }
            return@launch
        }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        // 写成了：更新屏幕，然后**用全文版**起这一轮（模型才看得到改后的句子）。
        _state.update {
            if (it.conversationId != snapshot.conversationId) it
            else it.copy(
                messages = kept,
                activeRuns = it.activeRuns.filterKeys { key -> key in keptIds } + (id to fresh),
                streaming = "",
                reasoningRevision = 0,
                status = "正在重新回答…",
                error = null,
                checkpoints = safeCheckpoints,
            )
        }
        start(snapshot.conversationId, fresh, keptFull, _state.value, initialTool)
    }
}
