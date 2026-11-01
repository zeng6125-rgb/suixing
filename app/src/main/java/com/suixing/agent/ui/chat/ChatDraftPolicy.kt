package com.suixing.agent.ui.chat

import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import java.util.UUID

/**
 * 流式草稿的纯判据（issue #69 P2-C：从 `ChatViewModel` 物理迁出）。
 *
 * 不碰 Android、不碰协程作用域，只回答"这份草稿该不该落盘、算不算已交付"，
 * 因此可脱离 ViewModel 直接单测。`MAX_CHECKPOINTS` 原文件仍有引用点，留在原处。
 */

/**
 * The draft to write for a run, or null when this run has nothing worth saving yet.
 *
 * The blank-text case is the one that mattered: a round that is still thinking, or that has
 * only issued a tool call so far, has no answer text at all — and that is precisely the round
 * a stop or a kill interrupts. Skipping it left no draft file behind, and everything the user
 * had watched arrive (the thinking, the tool cards) went with the process, because nothing
 * runs after a kill to save it.
 *
 * [messages] is filtered to this run: the rounds of an earlier answer in the same conversation
 * are already in its transcript, and re-appending them would duplicate them.
 */
internal fun liveDraftFor(
    chatId: String,
    runId: String,
    text: String,
    reasoning: String,
    messages: List<StoredMessage>,
    preparedMessages: List<StoredMessage>? = null,
): LiveDraft? {
    val mine = preparedMessages ?: messages.filter { it.runId == runId }
    if (text.isBlank() && reasoning.isBlank() && mine.isEmpty()) return null
    return LiveDraft(
        chatId = chatId,
        runId = runId,
        at = System.currentTimeMillis(),
        text = text,
        reasoning = reasoning.takeIf { it.isNotBlank() },
        messages = mine,
    )
}

/**
 * Whether [other] already holds exactly this content, so the write can be skipped.
 *
 * [LiveDraft.at] is deliberately not compared: it changes on every tick, and comparing it
 * would turn the interval into a write every second for a stream that has gone quiet. The
 * Check scalar lengths before walking strings/lists. A growing answer then rejects the old
 * draft in O(1); unchanged immutable lists use their identity fast path. Same-length edits
 * still receive exact comparisons: hashes/ids alone could lose the only recovery copy.
 */
internal fun LiveDraft.sameContentAs(other: LiveDraft?): Boolean = other != null &&
    other.chatId == chatId &&
    other.runId == runId &&
    other.text.length == text.length &&
    other.reasoning?.length == reasoning?.length &&
    other.messages.size == messages.size &&
    other.reasoning == reasoning &&
    other.messages == messages &&
    other.text == text

/**
 * 合并草稿时，手里这份 `messages` 是不是**确实在磁盘上**（决定能不能清掉草稿）。
 *
 * 这条判据关系不可逆：判成"是"就去删草稿，而草稿是进程被杀后唯一的副本。所以它必须覆盖
 * 那个容易漏的路径 —— **冷启动时的 base 恰恰是刚从盘上读回来的窗口**（`live == null`
 * 但 `shown != null` 且 `baseFromDisk == true`）⇒ 它就是磁盘来源，可以清。
 * 只有"来自内存缓存"时才是假的。
 *
 * 注意 **`shown == null` 并不等于"盘上有内容"**：按 #30，会话列表只带 meta，冷启动时
 * `stored.messages` 是空的 —— 那种空 base 也不会走清理分支（要求 `restored` 为空），
 * 所以这条判据只需要区分"屏幕上的这份是否来自磁盘"。
 */
internal fun draftIsDiskBacked(
    shownPresent: Boolean,
    baseFromDisk: Boolean,
    /**
     * 缓存里的那份是否**确认已落盘**（S1 的水位）。
     *
     * 这是缓存路径上唯一可信的证据：只看"屏幕上有没有会话"无法区分"这份来自磁盘"与
     * "这份含保存失败、只在内存的消息"。
     */
    cacheDiskBacked: Boolean = false,
): Boolean = !shownPresent || baseFromDisk || cacheDiskBacked

/**
 * What has to be appended to [messages] for the draft a killed run left behind to come back
 * whole: the rounds and tool results it had already produced, then the half-written answer.
 *
 * The thinking rides along with that answer, for the reason the engine hands it over on a
 * stop as well: the chain that produced the paragraph is part of what the user watched arrive.
 *
 * "This text is already in the transcript" is judged by the first [LIVE_DRAFT_PROBE_CHARS]
 * characters, not by the run alone: a run that used a tool has already stored messages under
 * the same runId, and judging by the run would throw the half-written final answer away — the
 * one thing this draft exists to save. The interrupted answer is appended only when the draft
 * is not already there; nothing else can say the answer is unfinished, because StoredMessage
 * has no field for it and a bubble that looks complete reads as the model stopping mid-sentence
 * for no reason.
 */
internal fun liveDraftRecovery(
    draft: LiveDraft,
    messages: List<StoredMessage>,
    /** 中断原因，写进半截回答的尾部（用户：「由 xxx 原因，对话中断」）。 */
    cancelReason: String = "任务被停止",
): List<StoredMessage> {
    val probe = draft.text.trim().take(LIVE_DRAFT_PROBE_CHARS)
    // **正文为空、只有思考内容**时也要判重（评审 9 建议项）。
    // `emitPartial` 在"还没吐正文"时会把 text="" + reasoning 全文发出来并落盘；
    // 草稿的 text 也是空 ⇒ `probe` 为空 ⇒ 原来判 delivered=false ⇒ 再补一条
    // 带同样思考内容的助理消息 ⇒ 用户看到**重复的思考卡**（而 0.8.372 的 `?: false`
    // 让"用户按停止"这条路每次都会留下草稿，所以更容易走到）。
    // **必须用 `startsWith`，不能用相等**（评审 10 的 M1）：草稿是"最多 1 秒前"的快照，
    // 而落盘那条 = 草稿 + 最后一个 tick 之后到达的增量。只要用户按停止时它还在想
    // （这恰恰是最常见的场景），相等永远不成立 ⇒ 还是会补一张重复的思考卡。
    // 方向与正文分支一致（那边一直用 `startsWith`），注释里也早写明"用相等会把每次正常结束
    // 都判成未落盘"。不做 trim：两边都没有规范化，逐字前缀关系是可靠的。
    val existingIds = HashSet<String>(messages.size)
    val reasoningProbe = draft.reasoning?.takeIf { probe.isEmpty() && it.isNotBlank() }
    var delivered = false
    // One transcript traversal supplies both id de-duplication and the run-local prefix check.
    // Recovery runs once on opening/stopping, not at the periodic writer's frequency.
    for (message in messages) {
        existingIds += message.id
        if (delivered || message.runId != draft.runId || message.role != Roles.ASSISTANT) continue
        delivered = if (reasoningProbe != null) {
            message.reasoning?.startsWith(reasoningProbe) == true
        } else if (probe.isNotEmpty()) {
            message.text.trimmedStartsWith(probe)
        } else {
            false
        }
    }
    val restored = draft.messages.filterNot { it.id in existingIds }
    // Nothing left to say only when the answer already landed, or when the draft is empty in
    // every sense. A run stopped while it was still thinking has no answer text at all, and
    // that is not the same as having produced nothing: the thinking — and any tool round
    // restored above — is exactly what the user watched arrive and expects to find again. The
    // marker stands alone as the bubble's text in that case.
    val body = draft.text.trimEnd()
    // 空串＝用户自己按的停止：**一个字都不补**（用户：「用户自行点击的中断不需要错误提示」）。
    // 半截回答原样收尾，读起来就是「我说到这儿就够了」，而不是一次失败。
    val text = if (cancelReason.isBlank()) {
        body
    } else if (body.isEmpty()) {
        "（由「$cancelReason」原因，对话中断）"
    } else {
        body + "\n\n（由「$cancelReason」原因，对话中断）"
    }
    val line = if (delivered || (probe.isEmpty() && draft.reasoning.isNullOrBlank() && restored.isEmpty())) {
        emptyList()
    } else {
        listOf(
            StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.ASSISTANT,
                text = text,
                reasoning = draft.reasoning,
                runId = draft.runId,
            ),
        )
    }
    return restored + line
}

/** Equivalent to trim().startsWith(prefix), without copying the remaining long answer. */
private fun String.trimmedStartsWith(prefix: String): Boolean {
    var start = 0
    var end = length
    while (start < end && this[start].isWhitespace()) start++
    while (end > start && this[end - 1].isWhitespace()) end--
    return end - start >= prefix.length && startsWith(prefix, startIndex = start)
}

/**
 * How much of a draft is compared against the transcript to decide it was already saved.
 *
 * A prefix, not the whole text: the saved answer is the draft plus whatever arrived after the
 * last write, so equality would call every cleanly finished run "not delivered" and duplicate
 * the bubble. 64 characters are far more than any two answers share by accident.
 */
private const val LIVE_DRAFT_PROBE_CHARS = 64
