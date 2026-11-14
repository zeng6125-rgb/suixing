package com.suixing.agent.ui.chat

import android.os.SystemClock
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

internal fun ChatRuntime.handleEvent(chatId: String, event: AgentEvent) {
    val next = deltaBuffer.locked { applyEventLocked(chatId, event) } ?: return
    if (event is AgentEvent.ToolFinished) event.todoSnapshot?.let { board ->
        setTodos(chatId, board)
        _state.update { if (it.conversationId == chatId) it.copy(todos = board) else it }
    }
    // A handed-over line is persisted after its session update releases the stream lock.
    if (event is AgentEvent.Steered) viewModelScope.launch { saveSession(next) }
}

private fun ChatRuntime.applyEventLocked(chatId: String, event: AgentEvent): RunSession? {
    val live = sessions[chatId] ?: return null
    // The model can send hundreds of deltas a second, and every one of them used to
    // republish the state — which re-parses the whole Markdown body, because the render
    // cache is keyed by the text itself and a text that changes every frame is a miss
    // every frame. That is what made the ghosting worse the faster the endpoint wrote.
    // Deltas are buffered and published at most every DELTA_PUBLISH_MS; every other event
    // flushes first, so nothing is ever applied out of order and no tail is lost.
    val textDelta = event is AgentEvent.TextDelta
    val reasoningDelta = event is AgentEvent.ReasoningDelta
    // Buffer removal and session replacement share one transaction with timer flushes.
    if (textDelta) appendDeltaLocked(chatId, event.text, "")
    if (reasoningDelta) appendDeltaLocked(chatId, "", event.text)
    if ((textDelta || reasoningDelta) && !mayPublishLocked(chatId, SystemClock.uptimeMillis())) {
        scheduleTailFlush(chatId)
        return null
    }
    val (pendingText, pendingReasoning) = takeDeltasLocked(chatId)
    val base = applyPending(live, pendingText, pendingReasoning)
    val next = when (event) {
        // Already folded into `base` above.
        is AgentEvent.TextDelta -> base.clearRetryStatus()

        is AgentEvent.ReasoningDelta -> base.clearRetryStatus()

        // The round is stored, but it is not drawn from the transcript: the live
        // bubble keeps drawing it until the run lands, so the reply stays one bubble
        // from the first token to the last.
        is AgentEvent.AssistantMessage -> {
            // 轮落盘＝一段结束：封口思考块，并把段起点推到封口后的末尾 —— 下一轮（或同一 run
            // 的下一段）的思考/正文不会与这一轮的合并成一个块。
            val sealed = base.parts.sealThinking(base.thinkingStartedAt)
                .bindPreparedTools(event.message.toolCalls, event.toolStreamIndices)
            base.copy(
                messages = base.messages + event.message.copy(runId = base.runId),
                parts = sealed,
                segmentStart = sealed.size,
                // The round is a message now, so the two live buffers start empty: from here
                // they hold the round still being written, not the run so far. That is what
                // lets the draft carry the part no message has yet — otherwise it would hand
                // the whole run back as one paragraph and repeat the rounds already stored.
                streaming = "",
                reasoningRevision = 0,
            )
        }

        is AgentEvent.ToolPrepared -> base.copy(
            status = if (base.runningTool == null) "正在准备调用 ${event.name}" else base.status,
            parts = base.parts.sealThinking(base.thinkingStartedAt)
                .prepareTool(event.streamIndex, event.name, base.segmentStart),
        )

        is AgentEvent.ToolStarted -> {
            // 工具行＝一段结束（理由同 [appendText]：工具前后的正文是两段话）。
            val sealed = base.parts.sealThinking(base.thinkingStartedAt)
                .startTool(event.name, event.args, event.callId, event.streamIndex)
            base.copy(
                // 「正在调用」 rather than a bare verb: the line is a sentence the user reads
                // while waiting, and 「调用 read_file」 read as a log entry.
                status = "正在调用 ${event.name}",
                runningTool = event.name,
                parts = sealed,
                segmentStart = sealed.size,
            )
        }

        is AgentEvent.ToolMessage -> base.copy(
            messages = base.messages + event.message.copy(runId = base.runId),
            status = "",
            runningTool = null,
            // The result hangs off the call it belongs to, not only off the
            // transcript: the row needs its output and its screenshot.
            parts = base.parts.attachToolMessage(event.message, event.streamIndex),
        )

        is AgentEvent.ToolFinished -> base.copy(
            runningTool = null,
            parts = base.parts.finishTool(event.name, event.ok, event.callId, event.streamIndex),
        )

        // The error rides with the run: it shows on the error card only while that
        // conversation is the one on screen, and travels with it otherwise.
        is AgentEvent.Failed -> {
            // 每一次终止性失败都落盘，**不只是重试用尽的那种**：用户报「为什么写文件就触发」时，
            // 需要看的是网关实际回了什么 —— 而这条消息里现在带着 finish_reason 的**原文**与
            // 它自己报的 completion_tokens（见 AgentEngine 的 length 分支），所以这一行就是那次
            // 复现的原始证据，不必再靠推测。重试次数由 [Retrying] 设下的计数器给出，不解析文案。
            val attempts = retriesThisRun
            retriesThisRun = 0
            retryTrace(
                "turn-failed" + (if (attempts > 0) " attempts=$attempts" else "") +
                    " message=${event.message}",
            )
            // 最终失败要**像回复一样留在会话里**（用户：「如果最终确实是失败了，需要把失败的
            // 提示类似气泡那样作为回复」）。横幅 10 秒就自动消失、卡片里那行小红字也不落盘，
            // 关掉应用就再也查不到这一轮为什么死 —— 所以把失败文本作为一条消息进 live.messages，
            // run 结束时那次最终 saveSession 自然把它写进文件。角色用 SYSTEM：引擎回放 wire 只
            // 认 user/assistant/tool（AgentEngine:1822-1852），它不会进下一轮的请求；转录单独
            // 画它一行（groupRows 的 SYSTEM 分支）。注入点必须在 endSession 之前 —— finally 的
            // 顺序是 flushDeltas → saveSession → endSession(remove)，这里 live 还在。
            val receipt = StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.SYSTEM,
                text = FAILURE_RECEIPT_PREFIX + event.message,
                runId = base.runId,
            )
            // **插到失败那一轮的最后一条消息后面**（用户：「一轮错误就跟在一轮后面」「为什么
            // 老是放在卡片前面」）：Failed 事件可能晚于下一条消息到达（用户已发下一问/排队已
            // 落地），追加到表尾回执就跑到下一轮卡片前面去了。按 runId 找它那一轮的末条；
            // 找不到（极端乱序）才退回表尾。
            val at = base.messages.indexOfLast { it.runId == base.runId && it.runId.isNotEmpty() }
            val messages = if (at >= 0) {
                base.messages.subList(0, at + 1) + receipt + base.messages.subList(at + 1, base.messages.size)
            } else {
                base.messages + receipt
            }
            // 用户自己按了中断：取消路径上引擎仍可能报 Failed，那不是失败 —— 不落回执、
            // 不留红色提示（用户：「点击中断按钮中断的不必弹出错误提示」）。
            if (chatId in userStopped) base else base.copy(error = event.message, messages = messages)
        }

        // A failed attempt that is being repeated. The run is still going — it is waiting — and
        // without this the screen says nothing at all for the length of the delay, which is
        // exactly how a retrying run got read as one that had stopped. Whatever event comes next
        // overwrites the line.
        is AgentEvent.Retrying -> {
            retriesThisRun = event.attempt
            retryTrace(
                "retry-start attempt=${event.attempt} max=${event.maxAttempts} " +
                    "code=${event.code ?: "none"} delayMs=${event.delayMs} reason=${event.reason} " +
                    // These two are invariants of the decision, not measurements: a retry is only
                    // taken when nothing arrived on the attempt (or it would have been kept as
                    // content) and when the call was not cancelled. Written out anyway, because a
                    // log reader should not have to know the rule to trust the line.
                    "received=0 cancelled=0",
            )
            base.copy(
                status = RETRY_STATUS_PREFIX +
                    "，${event.delayMs / 1000} 秒后重试（第 ${event.attempt} 次）· ${event.reason}",
                // 过程块：连续重试更新同一块（「自动重试 1 次」→「自动重试 2 次」），不叠成两行。
                parts = base.parts.traceRetry("自动重试 ${event.attempt} 次 · ${event.reason}"),
            )
        }

        is AgentEvent.RunDuration -> {
            val index = base.messages.indexOfLast { it.runId == base.runId }
            if (index < 0) base else base.copy(messages = base.messages.toMutableList().apply {
                this[index] = this[index].copy(runElapsedMs = event.elapsedMs)
            })
        }

        is AgentEvent.Finished -> {
            retriesThisRun = 0
            // 这一轮成功了：屏幕上不该再挂着任何错误。重试成功的轮次走的是过程留痕
            // （StoredMessage.traceNote），从不设 error；这里清一次是给所有成功路径的保证
            // （用户：「整轮对话是成功的话 就不要有 错误提示卡片存在」）。
            base.copy(error = null)
        }

        // 这一轮作废重来（收到内容之后流断了、而且还能重试）：把本段已经流出去的内容撤掉，
        // 否则重发的那一轮会在气泡里把同一段开头再画一遍。
        AgentEvent.RoundRestarted -> base.copy(
            parts = base.parts.take(base.segmentStart.coerceIn(0, base.parts.size)),
            streaming = "",
            reasoningRevision = 0,
            status = if (base.status.startsWith("正在准备调用 ")) "" else base.status,
        )

        // 压缩摘要正在生成：状态行改说这件事（run 中也要有，用户要求）。
        //
        // **结束后不再立刻清空**（用户：「压缩完成后留一行『已压缩上下文，用时 3.2s』」）：
        // 压缩要再发一次模型请求，几秒钟里什么都不写，状态行一消失就又像卡住了。
        // 现在把这段耗时写进状态行，它会一直留到后续事件把它覆盖（或本轮结束）。
        is AgentEvent.Compressing -> if (event.on) {
            compressStartedAt = System.currentTimeMillis()
            base.copy(status = "正在压缩上下文")
        } else {
            val elapsed = (System.currentTimeMillis() - compressStartedAt).coerceAtLeast(0L)
            val seconds = elapsed / 1000.0
            base.copy(
                status = if (elapsed > 0L) {
                    "已压缩上下文，用时 " + "%.1f".format(seconds) + "s"
                } else {
                    ""
                },
            )
        }

        // The model has the line now: it leaves the queue and becomes a message in the
        // same breath, so a queued line is never both. Marked `steer` so the transcript
        // keeps reading it as an interruption rather than as the question being answered.
        //
        // It also becomes a live part, and that is the only way the user sees it while the
        // run is going: this run's stored rounds are hidden from the transcript for as
        // long as it streams (the live bubble draws them), and a stored steer folds into
        // exactly the hidden row. The message still lands, because the run is what saves
        // it — it is just not drawable yet.
        //
        // Off `base`, not `live`: the deltas buffered for this event were already taken
        // out of their buffers a few lines up, so building on `live` would have thrown
        // away whatever text and thinking had just arrived.
        is AgentEvent.Steered -> {
            removeSteer(chatId, event.id)
            publishSteers(chatId)
            val line = StoredMessage(
                id = event.id,
                role = Roles.USER,
                text = event.text,
                // The picture travels with the event, so the bubble the user sees is the
                // message the model was given — and it is stored, so a later run replaying
                // this history still sends it.
                imagesBase64 = event.imagesBase64,
                steer = true,
                // This run's id, like every other message it writes. The screen hides the
                // live run's stored rows while the bubble draws them; a steer without the
                // id slipped past that filter whenever no bubble was open yet (the model
                // had stored nothing back) and appeared twice — once settled, once live.
                // Nothing else keys off it: turns are opened by non-steer user messages
                // alone, and [Transcript.active] keeps every user message whatever its run.
                runId = base.runId,
            )
            run {
                // 插话行＝一段结束：用户插进来的这条线把前后的流分成两段。
                val sealed = base.parts.sealThinking(base.thinkingStartedAt) + LivePart.Steer(line)
                base.copy(
                    messages = base.messages + line,
                    parts = sealed,
                    segmentStart = sealed.size,
                )
            }
        }
    }
    putSession(next)
    return next
}
