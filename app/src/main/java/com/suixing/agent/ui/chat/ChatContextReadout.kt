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
    // 消息不可变，组合期只按实例读取进程级缓存，不计算全文指纹或比较长字段。
    // 新实例在后台核对并估算；切会话只使用完整缓存，更新期间保留本会话的上次估算。
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
        kept to (last?.summary?.length ?: 0) / 3
    }
    val (estimateMessages, estimateSummaryChars) = estimateInput
    var transcriptEstimate by remember(state.conversationId, checkpoint?.id) {
        if (state.loading) return@remember mutableStateOf<Int?>(null)
        var cached = estimateSummaryChars
        for (message in estimateMessages) {
            val tokens = TokenEstimateCache.peek(message)
                ?: return@remember mutableStateOf<Int?>(null)
            cached += tokens
        }
        mutableStateOf<Int?>(cached)
    }
    LaunchedEffect(state.conversationId, state.messagesRevision, state.checkpoints, state.loading) {
        if (state.loading) return@LaunchedEffect
        transcriptEstimate = withContext(Dispatchers.Default) {
            estimateMessages.sumOf { TokenEstimateCache.tokensFor(it) } + estimateSummaryChars
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
