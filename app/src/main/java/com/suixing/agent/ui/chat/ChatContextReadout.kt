package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.LlmChoice
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.choiceFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Endpoint resolution and context estimates are independent of transcript rendering. */
internal class ChatContextReadout(
    val assistant: Assistant?,
    val activeChoice: LlmChoice,
    val contextWindow: Int,
    val providerOfModel: Map<String, ProviderConfig>,
    val session: SessionStats?,
    val overhead: Pair<Int, Int>?,
    val reading: RingReading?,
    val sessionTokens: Int,
    val sessionTurns: Int,
    val sessionEstimate: Int,
    val sessionElapsedMs: Long,
    val sessionSplit: SessionSplit,
) {
    val activeModel get() = activeChoice.model
    val contextUsed get() = reading?.tokens ?: 0
}

@Composable
internal fun rememberChatContextReadout(
    state: ChatUiState,
    settings: AppSettings,
    messagePositions: Map<String, Int>,
    viewport: ConversationViewport,
    actions: ChatScreenActions,
    catalogRevision: Long,
): ChatContextReadout {
    // **每次重组都扫一遍助手表**（流式期 12.5 Hz）。下面两行把 provider×model 的解析记住了，
    // 理由一模一样，唯独这行漏了；助手表本身也很少变，所以按它的输入记。
    // 键用引用（IdentityKey）：这三个都是「只整体替换」的不可变数据，实例一换就重算绝不会
    // 漏更新；而直接拿 List/整份 AppSettings 当键 ⇒ 每次重组都深比较（providers 的
    // models/enabled/aliases Map、assistants 的提示词全文）。
    val assistant = remember(IdentityKey(settings.assistants), settings.activeAssistantId, state.assistantId) {
        settings.assistant(state.assistantId) ?: settings.activeAssistant()
    }
    // The chain a run takes, resolved once for the whole screen. The context readouts
    // below divide by the window of the model that actually sends, which is not the
    // app-wide default's window once a conversation or a persona picks its own.
    // 每次重组都要扫 provider×model（流式期 12.5 Hz），而这三个输入很少变 —— 记住它。
    // 同一个 remember 里把**生效的模型名**一并取出：圆环面板要用它说明"这个窗口属于谁"
    // （issue #49 修法 2：切 provider 后分母变了，用户需要看出变的是窗口）。
    val activeChoice = remember(IdentityKey(settings), state.providerId, state.model, assistant) {
        settings.choiceFor(state.providerId, state.model)
    }
    val activeModel = activeChoice.model
    val contextWindow = remember(IdentityKey(settings), state.providerId, state.model, assistant) {
        settings.contextWindowFor(activeModel)
    }
    // Which endpoint a stored round ran on. A message keeps its model id and nothing else, so
    // its owner is whichever endpoint lists that id — the same question the picker asks. Built
    // once per settings change rather than per row: a long transcript redraws far more often.
    // First endpoint wins, so the user's own order settles an id two of them both list.
    val providerOfModel = remember(IdentityKey(settings.providers)) {
        val owners = mutableMapOf<String, ProviderConfig>()
        settings.providers.forEach { provider ->
            (provider.enabled + provider.models + provider.aliases.keys).forEach { id ->
                owners.putIfAbsent(id, provider)
            }
        }
        owners
    }
    // Read prompt counts from the loaded transcript. Visible bubbles can hide tool-only rounds
    // and the active run, and depend on which part of the history is on screen.
    val checkpoint = state.checkpoints.lastOrNull()
    val newestStoredPrompt = remember(state.conversationId, state.messagesRevision, checkpoint?.id) {
        latestContextPrompt(state.messages, checkpoint)
    }
    // What the endpoint never priced, sized off the text: the ring's floor for a conversation
    // 键用 **messagesRevision**，不是 rowsKey：`rowsKey` 是（会话, 条数, 末条 id），
    // 编辑某条消息、或流式把文本追加进已落盘消息时，条数与末条 id 都不变 ⇒ 估算值不刷新，
    // 圆环读数会停在旧数字。revision 是 ViewModel 在每次发布时统一盖的章。
    // **逐条缓存 token 估算**（审计 1§2）：`estimateTokens` 对正文做逐字符 CJK 判定，
    // 12 轮 + 工具输出可达 5~20 万字符 ⇒ 每次 revision 一次几 ms 的主线程尖峰，
    // 与 frame.log 里「成对 24ms+16ms、每 100~350 帧一次」的形态吻合。
    // 消息是不可变值对象 ⇒ 按 id 缓存，用 text.hashCode()（String 自带缓存）验证内容未变。
    //
    // **缓存改成进程级、不再按会话重建**（切换会话卡的主线程开销之一，见 tokenCache 的 KDoc）：
    // 原来是 `remember(state.conversationId)` ⇒ 每次切会话换一张**空表** ⇒ 整页正文被逐字符
    // 重估一遍（就发生在组合期、主线程）；切回来再估一遍。内容指纹（stamp）本来就在条目里，
    // 全局表不会串数据；LRU 上限防内存。纯函数与单测见 TokenEstimateCache。
    // **估算移出组合期主线程**（issue #56 R39）：`estimateTokens` 对正文做逐字符 CJK 判定，
    // 一页 12 轮可达 5~20 万字符。原来整段跑在 `remember` 里 ⇒ 组合期（主线程）一次几 ms。
    // 切会话时只使用该会话的完整缓存；缓存不完整时等后台结果。发消息时保留本会话的
    // 上次估算，等新结果一次替换。
    val estimateInput = remember(state.conversationId, state.messagesRevision, state.checkpoints) {
        val last = state.checkpoints.lastOrNull()
        // issue #53 B-2：同一次建表里查 id（firstPositions 保留首个重复 id 的语义）。
        val positions = messagePositions
        val start = last?.let { cp ->
            val i = positions[cp.toMessageId] ?: -1
            if (i >= 0) i + 1 else 0
        } ?: 0
        val kept = if (start > 0) {
            state.messages.subList(start, state.messages.size)
        } else {
            state.messages
        }
        // 消息不可变 ⇒ 指纹按 id 缓存的既有做法不变（`String.hashCode` 自带缓存）。
        val stamps = kept.map { message ->
            message.text.hashCode().toLong() * 31 +
                message.toolOutput.orEmpty().length +
                message.toolArgs.orEmpty().length * 7
        }
        Triple(kept.toList(), stamps, (last?.summary?.length ?: 0) / 3)
    }
    val (estimateMessages, estimateStamps, estimateSummaryChars) = estimateInput
    var transcriptEstimate by remember(state.conversationId, checkpoint?.id) {
        if (state.loading) return@remember mutableStateOf<Int?>(null)
        var cached = estimateSummaryChars
        for (i in estimateMessages.indices) {
            val tokens = TokenEstimateCache.peek(estimateMessages[i], estimateStamps[i])
                ?: return@remember mutableStateOf<Int?>(null)
            cached += tokens
        }
        mutableStateOf<Int?>(cached)
    }
    LaunchedEffect(state.conversationId, state.messagesRevision, state.checkpoints, state.loading) {
        if (state.loading) return@LaunchedEffect
        transcriptEstimate = withContext(Dispatchers.Default) {
            estimateMessages.indices.sumOf { i ->
                TokenEstimateCache.tokensFor(estimateMessages[i], estimateStamps[i])
            } + estimateSummaryChars
        }
    }
    val transcriptTokens = transcriptEstimate ?: 0
    // The engine's provisional request stats change as it prepares each round. Use the report
    // published with the assistant message, so starting or ending a run cannot change the source.
    val session = actions.sessionStats.collectAsStateWithLifecycle(initialValue = null).value
        ?.forConversation(state.conversationId)
    val overhead = state.contextOverhead
    val estimate = transcriptEstimate?.let { history ->
        if (history == 0) 0 else overhead?.let { (system, tools) -> system + tools + history }
    }
    val reading = ringReading(
        newestStored = newestStoredPrompt,
        persistedContext = session?.contextTokens ?: 0,
        estimate = estimate,
        compressed = checkpoint != null,
    )
    // Tool and system estimates are independent of the transcript and refresh with their inputs.
    LaunchedEffect(state.conversationId, viewport, assistant, catalogRevision) {
        if (state.conversationId.isEmpty()) return@LaunchedEffect
        // Tool/plugin estimation is not needed to display the transcript's first frame.
        snapshotFlow { viewport.presented && viewport.entrance.value >= 1f }.first { it }
        actions.refreshContextOverhead()
    }
    val contextUsed = reading?.tokens ?: 0
    val sessionTokens = session?.tokens ?: 0
    val sessionTurns = session?.turns ?: 0
    // The cumulative row's own floor, for the same conversations as above: every turn re-sent the
    // context it had, so the transcript's size times the turns is the shape of the total. It is a
    // floor — a tool-heavy run re-sends more often than it has questions — which is why the card
    // writes 「约」 in front of it and nothing else about the split is invented.
    val sessionEstimate = if (sessionTokens > 0) 0 else transcriptTokens * sessionTurns.coerceAtLeast(0)
    // What each run actually took, summed over its own messages. A message's own `elapsedMs`
    // is one round's call — the model thinking, then calling a tool, then thinking again are
    // several messages in one run — while the status line the user watched counted the whole
    // run. Showing the round's number under the card is what made the two disagree.
    // The conversation's own clock, summed off the very rows [SessionStats] divides its speed
    // by — so 「会话总用时」 and 「会话输出速度」 are two readings of one measurement rather
    // than two tallies that can drift apart. The tally carries the speed but not the total.
    // **整个会话**的墙钟合计（原来是 `state.messages.sumOf{…}`，那只是 12 轮窗口）。
    val sessionElapsedMs = session?.elapsedMs ?: 0L
    // The conversation's spend, split the way the endpoint reports it, for the ring's card: the
    // prompt the cache could not answer, the cache reads, and the output. The card used to give
    // the total and the hit rate only; the user asked for the three parts by name —
    // 「未缓存输入」「缓存读取」「输出」 — and in full, because these are the figures a bill is
    // checked against and a rounded one cannot be checked against anything.
    // **整个会话**的三块花费（原来 `sessionSplitOf(state.messages)` 只算窗口）。
    val sessionSplit = session?.split ?: SessionSplit(0, 0, 0)

    return ChatContextReadout(
        assistant = assistant,
        activeChoice = activeChoice,
        contextWindow = contextWindow,
        providerOfModel = providerOfModel,
        session = session,
        overhead = overhead,
        reading = reading,
        sessionTokens = sessionTokens,
        sessionTurns = sessionTurns,
        sessionEstimate = sessionEstimate,
        sessionElapsedMs = sessionElapsedMs,
        sessionSplit = sessionSplit,
    )
}
