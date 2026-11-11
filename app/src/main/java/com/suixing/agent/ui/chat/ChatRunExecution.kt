package com.suixing.agent.ui.chat

import android.util.Log
import com.suixing.agent.agent.RunState
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.PerfRecord
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.forAssistant
import com.suixing.agent.data.withConversationApproval
import com.suixing.agent.llm.LlmException
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.llm.describeError
import com.suixing.agent.service.AgentForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/**
 * One line about retries, appended to `files/retry-trace.log`.
 *
 * **Why here and not inside `LlmClient.complete`.** The retry decision is made in the client, but
 * the client has no `Context` — it is deliberately a plain OkHttp class with no Android
 * dependency — and `AgentEngine` has none either (it takes everything by injection and does not
 * reference `Core`). The view model is the first place in the chain that has both a `Context`
 * and both ends of the story: [AgentEvent.Retrying] when an attempt is repeated, and
 * [AgentEvent.Failed] when they run out. The give-up line is therefore driven by
 * [retriesThisRun], which [AgentEvent.Retrying] sets — not by matching text in the message.
 *
 * Written off the main thread, and never allowed to fail the run: a diagnostic that can break
 * what it is diagnosing is worse than no diagnostic.
 */
internal fun ChatRuntime.retryTrace(line: String) {
    val context = runCatching { environment.appContext }.getOrNull() ?: return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val file = java.io.File(context.filesDir, RETRY_TRACE_FILE)
            if (file.length() > RETRY_TRACE_MAX_BYTES) {
                val tail = file.readText().takeLast(RETRY_TRACE_KEEP_CHARS)
                file.writeText(tail)
            }
            file.appendText("${System.currentTimeMillis()} $line\n")
        }
    }
}

internal fun ChatRuntime.stop() {
    // Only this conversation's reading: the speaker is one app-wide queue, so an
    // unconditional stop here used to cut short a reply another chat was reading out.
    val chatId = _state.value.conversationId
    // 中断原因（用户：「中断提示不要这么简单，由 xxx 原因，对话中断」）：由这里落下的停止
    // 标成「用户停止」，落盘那条半截回答的尾部就写得出原因；引擎自己取消的轮次用默认文案。
    cancelReason = "用户停止"
    // 停止不是失败：见 start() 里 catch 的用法（用户：「点击中断按钮中断的不必弹出错误提示」）。
    userStopped += chatId
    if (speakingChatId == chatId) {
        environment.speaker.stop()
        speakingChatId = null
    }
    // This conversation's run, by id: the engine keeps one handle per chat, and
    // stopping whatever happens to be newest would cut a run this screen does
    // not even show. Its approval prompt, if any, is answered "refused" for this
    // run only — another conversation's prompt stays on screen.
    gate.cancelPending(chatId)
    environment.engine.cancel(chatId)
    jobs.remove(chatId)?.cancel()
}


/**
 * Starts a run for the conversation currently on screen. Callers that accepted a
 * message before an asynchronous read must use the snapshot-taking overload with the
 * snapshot that message belongs to; otherwise a switch during the read starts the run
 * against the wrong chat (issue #76 F-03).
 */
internal fun ChatRuntime.start(runId: String, history: List<StoredMessage>) {
    start(_state.value.conversationId, runId, history, _state.value)
}


internal fun ChatRuntime.start(
    chatId: String,
    runId: String,
    history: List<StoredMessage>,
    snapshot: ChatUiState,
    initialTool: ToolCallRequest? = null,
    resumeCheckpoint: ContextCheckpoint? = null,
) {
    // The snapshot can lag behind the display (the user switched while the wire was
    // being read); only its conversation identity is wrong, and the run must use the
    // id the message was accepted for.
    val shown = if (snapshot.conversationId == chatId) snapshot else snapshot.copy(conversationId = chatId)
    // Lines left queued by the run that just ended, taken before this one starts: the
    // queue outlives a run, and a line queued from here on is already on its way to the
    // new run, so handing that one over again would deliver it twice.
    val carried = steersOf(chatId)
    putSession(
        RunSession(
            chatId = chatId,
            title = shown.title,
            assistantId = shown.assistantId,
            providerId = shown.providerId,
            model = shown.model,
            reasoningEffort = shown.reasoningEffort,
            approvalMode = shown.approvalMode,
            checkpoints = shown.checkpoints,
            runId = runId,
            messages = shown.messages,
            activeRuns = shown.activeRuns,
            status = shown.status,
        ),
    )
    // A new run starts with an empty board on screen. The file still holds the last run's
    // items — and drawing them here would show the previous task's plan wearing the new
    // run's clothes, most visibly for a run that never touches the todo tools at all.
    // The first `todo_write` of this run puts it back. 只清这个会话自己那一格。
    setTodos(chatId, emptyList())
    // 新一轮开始：清掉上一轮留下的红色提示，并把"用户停止"标记复位（否则这一轮真失败会被
    // 当成用户中断而不再报出来）。只在它仍是屏幕上的会话时动 UI：后台会话的 run 不能
    // 把当前会话的红条/待办清掉（issue #76 F-03）。
    userStopped -= chatId
    _state.update {
        if (it.conversationId == chatId) it.copy(todos = emptyList(), error = null) else it
    }
    val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        AgentForegroundService.start(environment.appContext, "JAgent正在执行任务")
        // The draft writer rides inside the run's own coroutine, so it stops the moment
        // the run does — no separate job to cancel, and no writes after the answer has
        // landed in the transcript. It is cancelled by hand in the finally as well: a
        // `while (true)` loop left to itself would tick once a second for the life of
        // the view model, and the cancel lands before the draft is cleared, so no write
        // can slip in after it.
        val writer = launch { writeLiveDraft(chatId) }
        // Lines still queued from the run that ended: the queue outlives a run, so the new
        // one has to be told about them. Waiting for Running is what makes this work — the
        // engine refuses a steer for a conversation it is not running yet, and startRun
        // below does not return until the run is over.
        if (carried.isNotEmpty()) {
            launch {
                environment.engine.stateOf(chatId).first { it is RunState.Running }
                // The queue is the truth: a line retracted while the run was starting is
                // gone from the table, and gone from the UI, so it must not go out here.
                // One snapshot under the lock, not a lookup per line.
                val stillWaiting = steersOf(chatId)
                // O(n×m) 的字符串两两比较 → 建一次集合后 O(n+m)。
                run {
                    val waitingIds = stillWaiting.mapTo(HashSet(stillWaiting.size)) { it.id }
                    carried.filter { it.id in waitingIds }
                }
                    .forEach { queued ->
                        // The picture rides along: a steer carries text *and* image, and
                        // re-sending only the text dropped the screenshot without a word.
                        val command = parseSlashCommand(queued.text)
                        val directTool = command?.let { prepareSlashTool(chatId, it, queued.imagesBase64) }
                        if (command != null && directTool == null) return@forEach
                        environment.engine.steer(
                            chatId,
                            queued.id,
                            queued.text,
                            immediate = false,
                            imagesBase64 = queued.imagesBase64,
                            directTool = directTool,
                        )
                    }
            }
        }
        // Decided in the catches, applied once in the finally: there is exactly one
        // place that saves and one that closes the run, whichever way it ended.
        var status = ""
        var failure: String? = null
        try {
            sessions[chatId]?.let { saveSession(it) }
            val settings = environment.settings.current()
            val assistant = settings.assistant(shown.assistantId) ?: settings.activeAssistant()
            // The persona bends the live settings; anything it left blank stays as-is.
            val persona = settings.forAssistant(assistant)
            // The wire comes from the one chain the picker also reads: this
            // conversation's own pick, then the persona's pins, then the app-wide
            // default. Everything else keeps the persona's overrides.
            val choice = settings.choiceFor(shown.providerId, shown.model)
            val effective = persona.copy(
                baseUrl = choice.baseUrl,
                apiKey = choice.apiKey,
                model = choice.model,
                // 协议也走这条链：这条会话落在哪个端点，就按那个端点的格式发。
                // 少了它，Anthropic/Responses 端点会收到 OpenAI 形状的请求体。
                protocol = choice.protocol,
                // The level follows the same chain as the model: this chat's own pick
                // wins, then whatever the persona's provider declared, then the
                // app-wide default the persona already carries.
                reasoningEffort = shown.reasoningEffort ?: persona.reasoningEffort,
            ).withConversationApproval(shown.approvalMode)
            val registry = environment.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
            val produced = engine.startRun(
                chatId = chatId,
                title = shown.title,
                history = history,
                initialTool = initialTool,
                runId = runId,
                settings = effective,
                registry = registry,
                capabilities = environment.capabilities,
                skills = environment.skills,
                memory = environment.memory,
                profile = environment.profile,
                gate = gate,
                // Captured per run: the engine's events carry no chat id, so the
                // closure is what keeps two parallel runs out of each other's
                // transcript.
                onEvent = { event -> handleEvent(chatId, event) },
                // The compressions this conversation has already paid for, and the one
                // this run is about to make: without both, every turn would fold — and
                // charge for — the same span again.
                checkpoints = shown.checkpoints,
                onCheckpoint = { checkpoint -> recordCheckpoint(chatId, checkpoint) },
                resumeCheckpoint = resumeCheckpoint,
            )
            // Only the run's own last reply is read out, not the whole history.
            if (effective.speakReplies) {
                produced.lastOrNull { it.role == Roles.ASSISTANT && it.text.isNotBlank() }
                    ?.text
                    ?.let {
                        val speechNavigation = navIntents.current()
                        val speechJob = currentCoroutineContext()[Job]
                        val speech = withContext(Dispatchers.Default) { environment.speaker.prepareText(it) }
                        // Preparation cannot speak by itself. Recheck on Main: navigation,
                        // stop or a replacement run makes this prepared reply obsolete.
                        currentCoroutineContext().ensureActive()
                        if (navIntents.isCurrent(speechNavigation) && jobs[chatId] === speechJob &&
                            chatId !in userStopped) {
                            speakingChatId = chatId
                            environment.speaker.speakPrepared(speech)
                        }
                    }
            }
        } catch (cancellation: CancellationException) {
            // RunState.Cancelled usually released the screen first; this is the belt
            // to that pair of braces.
            status = "已停止"
            throw cancellation
        } catch (error: Throwable) {
            // The transport's own words say nothing the user can act on — "timeout",
            // "Connection reset", "Trust anchor for certification path not found" — and they
            // are in English besides. `describeError` walks the cause chain and turns them into
            // what to do about the address, the certificate or the key; it is the same
            // description the settings page's connection test shows, so a failure reads the
            // same wherever it is met.
            //
            // One thing it cannot know is kept: a round the engine retried is re-thrown as an
            // LlmException whose message is the failure's own text — or, when it had none, its
            // class name (AgentEngine's own catch) — followed by the retry note. The note is
            // what is left once that prefix is gone, and it is only taken when it still looks
            // like the note (the engine's own full-width parenthesis); anything else means the
            // message was not shaped as expected, and the description stands alone rather than
            // repeating an English class name underneath itself.
            val note = if (error.cause != null && error is LlmException) {
                val prefix = error.cause?.let { it.message ?: it::class.java.simpleName }.orEmpty()
                error.message.orEmpty().removePrefix(prefix).trim().takeIf { it.startsWith("（") }
                    .orEmpty()
            } else {
                ""
            }
            val described = listOf(describeError(error), note)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            // 用户按了中断按钮：这不是失败。传输层被取消时抛的是 IOException("Canceled") 而不是
            // CancellationException，会掉进这个分支被当成失败 —— 写回执、留红色提示（用户：
            // 「点击中断按钮中断的不必弹出错误提示」）。此时只把状态收成「已停止」，不写回执、不留错误。
            if (chatId in userStopped) {
                status = "已停止"
            } else {
                failure = described
            }
            // 「tool name is invalid or duplicated」这类 400 的最小探针全部 200（点号/横杠/重名/
            // 插件全 schema/带未知工具名的历史，两个通道都测过），复现不出确切触发条件 ⇒ 让它
            // 自己报：把这一轮的工具表与历史引用过的工具名写进 trace，下次发生直接读日志定位。
            if (failure?.contains("tool name", ignoreCase = true) == true) {
                val live = sessions[chatId]
                val historyCalls = live?.messages
                    ?.flatMap { it.toolCalls }
                    ?.map { it.name }
                    ?.distinct()
                    ?.takeLast(40)
                    .orEmpty()
                retryTrace(
                    "toolname-400 toolsTable=${environment.tools.names().joinToString(",")} " +
                        "historyCalls=${historyCalls.joinToString(",")}",
                )
            }
        } finally {
            AgentForegroundService.stop(environment.appContext)
            withContext(NonCancellable) {
                // First of all: the writer must not run again, or a tick landing after
                // the clear below would resurrect a draft for an answer already saved.
                writer.cancelAndJoin()
                // 传输层抛异常的失败（400/超时/断连）不走 AgentEvent.Failed，而回执只埋在
                // Failed 分支 ⇒ 横幅倒计时一过就什么都不剩（用户：「红色回执依旧会消失」）。
                // 这里补同一张回执，并且**必须走 putSession**：直接给 sessions[chatId] 赋值只会
                // 落盘、不上屏 —— 上一版就是这么漏的，用户当轮只看到会消失的横幅。putSession
                // 同时写 sessions 与在屏 state，红卡当场出现；下面的最终 saveSession 再落盘。
                // Failed 分支已写过回执的（末条已是 SYSTEM）不重复写。
                if (failure != null) {
                    sessions[chatId]?.let { live ->
                        if (live.messages.lastOrNull()?.role != Roles.SYSTEM) {
                            putSession(
                                live.copy(
                                    messages = live.messages + StoredMessage(
                                        id = UUID.randomUUID().toString(),
                                        role = Roles.SYSTEM,
                                        text = FAILURE_RECEIPT_PREFIX + failure,
                                        runId = live.runId,
                                    ),
                                ),
                            )
                        }
                    }
                }
                // A cancelled coroutine refuses to suspend, so the final save has to
                // run in a NonCancellable block or a stopped run loses its messages.
                // Whatever the endpoint sent since the last publish belongs to the answer
                // too: without this flush a stopped run would save all but its tail.
                flushDeltas(chatId)
                // **写成功才清草稿**（评审 M-1，不可逆）：`saveSession` 现在返回结果；
                // 保存被拒/抛异常时草稿是磁盘上唯一的副本，绝不能删。
                // **会话已不在时按"没写成"处理**（评审 8 第 1 条）：用户按停止走的是
                // `RunState.Cancelled` 观察者 —— 它 fire-and-forget 一次保存（返回值被丢）、
                // 再 `endSession` 把会话摘掉。等这段 `finally` 跑到时 `sessions` 已经 null，
                // 而那次保存**可能被拒或抛异常** ⇒ 回答盘上一份不剩。宁可留草稿：
                // 下次打开时 `liveDraftRecovery` 会按前缀判重，不会出现两条。
                val written = sessions[chatId]?.let { saveSession(it) } ?: false
                // 性能记录：**这一轮真的发生了什么**。首字延迟、缓存命中、prompt/completion
                // 都取自本轮落下的那条回答（StoredMessage 本身就带着端点的回报），不另做估算。
                // 请求体大小目前没有采集点，留 0 —— 监测页会把它显示成「—」，不是「测到零」。
                val answered = sessions[chatId]?.messages
                    ?.lastOrNull { it.role == Roles.ASSISTANT && it.promptTokens > 0 }
                val runStats = _liveStats.value
                if (answered != null || runStats != null) {
                    // **IO 线程落盘**（审计 3§5）：record 里 writeText+delete+renameTo
                    // 是整轮唯一确定的主线程阻塞写；PerfMonitor 的测试契约要求
                    // record 后立即可 load，所以不改成异步类，改在调用点切 IO。
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                    environment.perf.record(
                        PerfRecord(
                            at = System.currentTimeMillis(),
                            conversationId = chatId,
                            ttftMs = answered?.firstTokenMs ?: 0,
                            totalMs = answered?.elapsedMs ?: (runStats?.elapsedMs ?: 0),
                            promptTokens = answered?.promptTokens ?: (runStats?.lastPromptTokens ?: 0),
                            completionTokens = answered?.completionTokens ?: (runStats?.tokensOut ?: 0),
                            cachedTokens = answered?.cachedTokens ?: 0,
                        ),
                    )
                    }
                }
                endSession(chatId, status, failure)
                // The run is over for certain here — this coroutine is the one that called
                // into the engine, so it has already returned and unregistered. A turn queued
                // in 排队 mode that the state observer could not start (its Done arrives while
                // the run is still registered) is started now.
                drainPendingTurns(chatId)
                // The answer is in the transcript now, whatever way the run ended: a
                // draft left behind would come back on the next launch as a second
                // bubble saying the same thing.
                if (written) {
                    environment.chats.clearLive(chatId)
                } else {
                    Log.w(TAG, "live draft kept for $chatId: the final save did not land")
                }
            }
        }
    }
    jobs[chatId] = job
    job.invokeOnCompletion { jobs.remove(chatId, job) }
    job.start()
}
