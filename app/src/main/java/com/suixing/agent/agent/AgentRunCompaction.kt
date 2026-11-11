package com.suixing.agent.agent

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.llm.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import com.suixing.agent.agent.AgentRun.Companion.KEEP_RECENT
import com.suixing.agent.agent.AgentRun.Companion.MIN_MESSAGES
import com.suixing.agent.agent.AgentRun.Companion.ROLE_TOOL
import com.suixing.agent.agent.AgentRun.Companion.ROLE_SYSTEM

/**
 * Summarises the oldest part of the conversation once the prompt is close to the
 * window the user set, so a long task does not end in a hard 400.
 *
 * The alternative — dropping the old messages — is what the trimmer already does
 * for the initial replay, and it is worse in the middle of a task: the model
 * forgets the decision it made ten steps ago and starts over. A summary keeps the
 * conclusions, the paths and the decisions, which is what the model actually
 * needs to carry on.
 *
 * The newest [KEEP_RECENT] messages are never touched: they are the work in
 * progress, and summarising the tool result the model is reading right now would
 * be self-defeating.
 *
 * The span it folded away is reported through [onCheckpoint] so it can be stored on the
 * conversation: a summary that lives only in this run's wire is paid for again on every
 * following turn. [asked] is a request from the menu, which skips the threshold and the
 * automatic switch but not the round boundary — a menu tap must never cut the answer in flight.
 *
 * The wire this leaves behind has to be exactly the wire a later run rebuilds from the
 * checkpoint — see [appendToWire] for the rule that keeps the two in step. A prefix that differs
 * by one message is a prompt cache miss for everything after it, and the model would be reading
 * a transcript the user never saw.
 */
internal suspend fun AgentRun.compressContext(
    wire: MutableList<ChatMessage>,
    spans: MutableList<WireSpan>,
    settings: AppSettings,
    onCheckpoint: (ContextCheckpoint) -> Unit,
    asked: Boolean,
    /**
     * 端点**实际上报**的 prompt token 数（issue #44 第七项规则 1）。
     *
     * 0 表示还没有任何一次请求返回过 usage（首次请求前）—— 此时不触发自动压缩，
     * 因为估算值对中文偏低、对截图是全错的两套口径，"猜容量"要么压早要么压晚。
     * 手动请求（[asked]）不受此限。
     */
    realPromptTokens: Int = 0,
    /**
     * 摘要生成期间的忙碌通知：true＝开始、false＝结束。
     *
     * 状态行据此显示「正在压缩上下文」—— 压缩要再发一次模型请求，几秒钟里什么都不写，
     * 看起来和卡死一样（用户：「run 的时候状态行也跟上」）。
     */
    onBusy: (Boolean) -> Unit = {},
) {
    val window = settings.contextWindowFor(settings.model)
    // A request from the menu needs neither a ceiling nor the automatic switch: the user asked
    // for the oldest context to be folded away, and the threshold is only what decides when
    // that happens on its own.
    if (!asked && (window <= 0 || !settings.autoCompress)) return
    if (wire.size <= MIN_MESSAGES) return
    // Priced before anything is replaced: what the checkpoint reports is how big the wire was
    // when this compression was decided on, not what it shrank to afterwards.
    val before = estimatePromptTokens(wire)
    // The last figure the endpoint itself reported, kept for the note below: when a compression
    // looks wrong, the first question is whether the estimate or the conversation was at fault,
    // and the two numbers side by side answer it without a debugger.
    val realBefore = realPromptTokens
    // **优先用实际 usage，但照搬 Eta 的"只信实测"会在这里出事**（借鉴 ≠ 照搬）：
    // `usage` 是端点**可选**字段（`usage?.promptTokens`），本仓不少兼容端点整个不返回它。
    // 若照原样写成"实测为 0 就不压缩"，那些端点上自动压缩会**永不触发** —— 比改之前更糟
    // （原来至少还有估算兜底，只是不准）。
    //
    // 所以取 Eta 的**意图**（有实测就别猜）而不是它的字面规则：
    //  · 有实测 ⇒ 用它判定，估算只用于定价与报告；
    //  · 没有实测（本次 run 还没拿到过 usage）⇒ 退回估算，保留原有兜底。
    // "缺少实际 usage 时不猜容量"这条只在**首轮请求之前**成立，那本来也没有容量可言。
    val triggerTokens = if (realBefore > 0) realBefore else before
    // Threshold from the settings snapshot this run was started with, so a change in
    // 设置 applies from the next run on rather than mid-run.
    if (!asked && triggerTokens <= window * settings.compressAtFraction) return

    // The cut never splits an assistant's tool_calls from their results: a lone
    // tool message is a hard 400 on every OpenAI-compatible endpoint.
    var cut = wire.size - KEEP_RECENT
    while (cut > 1 && wire[cut].role == ROLE_TOOL) cut--
    val pending = HashSet<String>()
    var groupStart = -1
    for (index in 1 until cut) {
        val message = wire[index]
        if (message.toolCalls.isNotEmpty()) {
            if (pending.isEmpty()) groupStart = index
            for (call in message.toolCalls) pending.add(call.id)
        }
        if (message.role == ROLE_TOOL) message.toolCallId?.let { pending.remove(it) }
    }
    // Screenshots and notes can sit between responses; retain their entire pending group.
    if (pending.isNotEmpty()) cut = groupStart
    if (cut <= 1) return

    val prefix = wire.subList(1, cut)
    var olderCount = 0
    var prefixChars = 0
    for (message in prefix) {
        if (message.role != ROLE_SYSTEM) olderCount++
        prefixChars += promptMessageChars(message)
    }
    if (olderCount == 0) return
    if (!compactionRetry.allows(cut, prefixChars, asked)) return

    val attempt = try {
        onBusy(true)
        // The whole span, system notes included: the summary this one replaces has to be folded
        // into it. Otherwise the rebuild that resumes from the checkpoint — which can only carry
        // one summary — would drop the older one and its context with it.
        summarize(prefix, settings)
    } catch (error: IOException) {
        // A stop during the summary is still a stop: let the caller's check see it
        // rather than reporting the abort as a failure of the whole run.
        if (cancelled.get()) return
        throw error
    } finally {
        inFlight = null
        onBusy(false)
    }
    // **摘要提交门槛**（issue #44 第七项规则 2）：只提交「完整、有界、且实际变小」的摘要。
    // 截断 / 空转 / 反而更大的摘要一律不提交 —— 保留原文，下一轮再试。宁可这一轮不压缩
    // （wire 大一点），也不能拿半截摘要换掉模型真正做过的那些事。
    if (!attempt.commit) {
        compactionRetry.rejected(cut, prefixChars)
        contextNote = "压缩未提交：${attempt.why}，保留原文"
        tick()
        return
    }
    compactionRetry.reset()
    val summary = attempt.text
    if (summary.isBlank()) return

    // The span this summary stands for, named by the messages it covered. Only a span that
    // begins and ends on whole messages can be resumed from by a later run, so a message the
    // cut would split is left out of the record — it stays in the tail and gets replayed, which
    // is the safe direction to be wrong in.
    val covered = spans.filter { it.from >= 1 && it.to <= cut }

    // The span's own system messages are not re-inserted: the summary above has already absorbed
    // them, and leaving them out is what makes this wire byte-for-byte the one a later run
    // rebuilds from the checkpoint — a prefix that differs by even one message is a prompt cache
    // miss for everything after it.
    val tail = wire.subList(cut, wire.size).toList()
    // firstOrNull rather than first(): the system message is always at index 0 today, and this
    // function's whole job is to rewrite the wire — a crash here would take the run down over a
    // state that simply has nothing to compress.
    val head = wire.firstOrNull() ?: return
    wire.clear()
    wire += head
    wire += ChatMessage.system("[上下文摘要] $summary")
    wire += tail

    // The spans move with the wire: what was folded away is gone, and what is left now starts
    // after the summary. Without this the next compression would name the wrong messages.
    val shift = 2 - cut
    val kept = spans.filter { it.from >= cut }.map { it.copy(from = it.from + shift, to = it.to + shift) }
    spans.clear()
    spans += kept

    // Counted in transcript messages, not in wire entries: the card that shows this number also
    // shows how many messages the span covers, and the two have to be the same fact. A wire
    // entry that came from nowhere — the screenshot note, a steer line — would make them differ.
    val folded = if (covered.isEmpty()) olderCount else covered.size

    // Handed up rather than kept here: the summary has to outlive this run, or the next one
    // starts from the full history and pays to summarise the same messages all over again.
    val first = covered.firstOrNull()
    val last = covered.lastOrNull()
    if (first != null && last != null) {
        onCheckpoint(
            ContextCheckpoint(
                id = UUID.randomUUID().toString(),
                at = System.currentTimeMillis(),
                fromMessageId = first.id,
                toMessageId = last.id,
                summary = summary,
                messages = folded,
                promptTokens = before,
            ),
        )
    }

    // The next round's estimate has to be priced off the summary, not off the
    // messages it replaced, or the threshold would fire again immediately. With no anchor the
    // next estimate is a plain character count, which is why screenshots are priced per image
    // there rather than by their base64 length.
    lastPromptTokens = 0
    lastPromptChars = 0
    lastPromptImages = 0
    // The estimate and the last real figure travel together: 「设了更大的窗口还是压缩了」 is a
    // question about which of the two was wrong, and the note is the only place the user can see
    // either of them.
    val evidence = if (realBefore > 0) {
        "（估算 ${before / 1000}k / 上轮实际 ${realBefore / 1000}k）"
    } else {
        "（估算 ${before / 1000}k）"
    }
    contextNote = if (asked) {
        "已压缩上下文（手动）：$folded 条消息 → 摘要$evidence"
    } else {
        "已压缩上下文：$folded 条消息 → 摘要$evidence"
    }
    // Published at once rather than waiting for the next tick, so the note is on
    // screen while the summary is still the news.
    tick()
}

/** One call to the same endpoint, asking only for prose: no tools, no streaming UI. */
internal suspend fun AgentRun.summarize(older: List<ChatMessage>, settings: AppSettings): SummaryAttempt {
    val transcript = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        renderSummaryBatch(older) { context.ensureActive() }
    }
    val turn = llm.complete(
        settings = settings,
        messages = listOf(
            ChatMessage.system(COMPRESS_PROMPT),
            ChatMessage.user(transcript),
        ),
        tools = emptyList(),
        onCall = { inFlight = it },
    )
    return checkedSummary(turn.text, turn.finishReason, transcript.length)
}
