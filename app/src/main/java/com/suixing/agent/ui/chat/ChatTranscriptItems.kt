package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.StoredMessage

/** List registration stays chronological; each rendering branch has its own composition scope. */
internal fun LazyListScope.chatTranscriptItems(
    state: ChatUiState,
    settings: AppSettings,
    assistant: Assistant?,
    visible: List<ChatRow>,
    rowKeys: List<String>,
    runElapsed: Map<String, Long>,
    providerOfModel: Map<String, ProviderConfig>,
    reasoningExpansion: ReasoningExpansion,
    rowDocuments: RowMarkdownCache,
    actions: ChatScreenActions,
    onEdit: (StoredMessage) -> Unit,
    checkpointsByRow: Map<String, List<ContextCheckpoint>>,
    messagePositions: Map<String, Int>,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    hasEarlier: Boolean,
    earlierCount: Int,
    loadingEarlier: Boolean,
    presented: Boolean,
    onLoadEarlier: () -> Unit,
    errorLine: Boolean,
    live: Boolean,
    liveStatusText: String,
    runStartedAt: Long,
    runElapsedClock: State<Long>,
) {
    if (hasEarlier) {
        item(key = "load-earlier", contentType = "load-earlier") {
            LoadEarlierRow(earlierCount, loadingEarlier, presented, onLoadEarlier)
        }
    }
    items(
        count = visible.size,
        key = { rowKeys[it] },
        contentType = { visible[it].message.role },
    ) { index ->
        val row = visible[index]
        TranscriptMessageWithCheckpoints(
            row = row,
            readingKey = rowKeys[index],
            settings = settings,
            assistant = assistant,
            bubbleMaxWidth = bubbleMaxWidth,
            contextWindow = contextWindow,
            runElapsedMs = runElapsed[row.message.runId] ?: 0L,
            providerOfModel = providerOfModel,
            reasoningExpansion = reasoningExpansion,
            rowDocuments = rowDocuments,
            actions = actions,
            onEdit = onEdit,
            checkpoints = checkpointsByRow[row.message.id].orEmpty(),
            messages = state.messages,
            revision = state.messagesRevision,
            messagePositions = messagePositions,
        )
    }
    if (errorLine) {
        item(key = "error:${state.conversationId}", contentType = "error") {
            RunReasonLine(state.error.orEmpty())
        }
    }
    if (!state.runningHere && state.compressingSince > 0L) {
        item(key = "compress-status:${state.conversationId}", contentType = "compress-status") {
            RunStatusLine(
                text = "正在压缩上下文",
                startedAt = state.compressingSince,
                timed = true,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
    // One key spans initial wait, streaming, and handover to the stored assistant row.
    if (live || state.runningHere) {
        item(key = liveRowKey(state.conversationId, state.liveRunId), contentType = "assistant") {
            LiveTranscriptRow(
                actions, state.conversationId, state.liveRunId, settings, assistant,
                bubbleMaxWidth, contextWindow, reasoningExpansion, state.runningHere,
                state.runningTool, liveStatusText, runStartedAt, runElapsedClock,
            )
        }
    }
}

@Composable
private fun LoadEarlierRow(
    earlierCount: Int,
    loading: Boolean,
    presented: Boolean,
    onLoad: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(enabled = !loading && presented, onClick = onLoad) {
            Text(
                text = if (loading) "正在加载更早的内容…"
                    else "加载更早的 $PAGE_TURNS 轮 · 上面还有 $earlierCount 轮",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun TranscriptMessageWithCheckpoints(
    row: ChatRow,
    readingKey: String,
    settings: AppSettings,
    assistant: Assistant?,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    runElapsedMs: Long,
    providerOfModel: Map<String, ProviderConfig>,
    reasoningExpansion: ReasoningExpansion,
    rowDocuments: RowMarkdownCache,
    actions: ChatScreenActions,
    onEdit: (StoredMessage) -> Unit,
    checkpoints: List<ContextCheckpoint>,
    messages: List<StoredMessage>,
    revision: Long,
    messagePositions: Map<String, Int>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatMessageRowHost(
            row, readingKey, settings, assistant, bubbleMaxWidth, contextWindow,
            runElapsedMs, providerOfModel, reasoningExpansion, rowDocuments, actions, onEdit,
        )
        // Keep the checkpoint directly under the last message it folded away.
        checkpoints.forEach { checkpoint ->
            CheckpointUnderRow(
                checkpoint, messages, revision, messagePositions, bubbleMaxWidth,
                settings.assistantCard, settings.flatPaddingDp.dp,
            )
        }
    }
}

/** Only this leaf subscribes to token text; the status clock is shared with the composer. */
@Composable
private fun LiveTranscriptRow(
    actions: ChatScreenActions,
    chatId: String,
    runId: String,
    settings: AppSettings,
    assistant: Assistant?,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    reasoningExpansion: ReasoningExpansion,
    running: Boolean,
    runningTool: String?,
    status: String,
    startedAt: Long,
    elapsed: State<Long>,
) {
    CompositionLocalProvider(LocalReadingRowKey provides liveRowKey(chatId, runId)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                LiveMessageParts(actions, chatId, runId, settings.showReasoning) { parts ->
                    AssistantRow(
                        parts = parts,
                        streaming = true,
                        card = settings.assistantCard,
                        flatPad = settings.flatPaddingDp.dp,
                        runningElapsed = elapsed,
                        name = assistant?.label ?: "JAgent",
                        face = assistant?.face ?: "",
                        avatarPath = assistant?.avatarPath ?: "",
                        runningTool = runningTool,
                        // Streaming never reads liveStats; do not invalidate its markdown per tick.
                        liveStats = null,
                        contextWindow = contextWindow,
                        expandTools = settings.expandTools,
                        expandReasoning = settings.expandReasoning,
                        reasoningMaxHeight = settings.reasoningHeight.dp,
                        followReasoning = settings.followReasoning,
                        bubbleMaxWidth = bubbleMaxWidth,
                        reasoningExpansion = reasoningExpansion,
                        runId = runId,
                    )
                }
            }
            if (running) {
                RunStatusLine(
                    text = status,
                    startedAt = startedAt,
                    elapsed = elapsed,
                    timed = true,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 0.dp),
                )
            }
        }
    }
}

/**
 * 一条压缩记录画在它折掉的那条消息下面。
 *
 * **独立成 composable**（性能审计 改动9）：原来 `remember` 写在 `forEach` 里 ——
 * Compose 的位置记忆按调用顺序分配，槽位数随记录数变化会错位。抽出来后每条记录有自己的槽位。
 */
@Composable
private fun CheckpointUnderRow(
    checkpoint: ContextCheckpoint,
    messages: List<StoredMessage>,
    revision: Long,
    positions: Map<String, Int>?,
    /** 与助手气泡同一个宽度上限（`AssistantRow` 用的是 `.widthIn(max = bubbleMaxWidth)`）。 */
    maxWidth: androidx.compose.ui.unit.Dp,
    /** 卡片模式：卡内还有 16dp 内边距，折叠块的可见边缘因此比列表留白再进 16dp。 */
    cardMode: Boolean,
    /** 扁平模式的左右留白（设置 → 外观）。 */
    flatPad: androidx.compose.ui.unit.Dp,
) {
    // O(messages) 的扫描放到 remember 里：每次重组跑一遍是纯浪费，
    // 键用 revision + checkpoint（两者任一变化才需要重算）。
    // `positions` 也入键（issue #56 R7）：它和 `messages` 都由 `messagesRevision` 驱动重建，
    // 之前只按 `revision` 记忆，等于依赖「换了 messages 必换 revision」这条没写下来的契约。
    // `positions` 是 `remember(conversationId, messagesRevision)` 的稳定实例，入键不会抖动；
    // `messages` 仍靠 revision 保证同步（它每次状态更新都是新实例，入键会导致每次都重算）。
    val originals = remember(checkpoint, revision, positions) {
        checkpointSpan(messages, checkpoint, positions)
    }
    // A checkpoint whose span is not in this transcript (a branch switch, a trimmed
    // conversation) is not drawn: half a record would read as data loss.
    if (originals != null) {
        CheckpointCard(
            checkpoint = checkpoint,
            originals = originals,
            // **与助手卡片同一个宽度上限**（用户：折叠条宽度和助手卡片的折叠块对不上）。
            // 不是内边距差异：助手卡是 `.widthIn(max = bubbleMaxWidth).fillMaxWidth()`，
            // 压缩卡没有上限 ⇒ 宽屏上比气泡宽出一截。这里补上同一个约束。
            // 与 `AssistantRow` 完全同一套：卡片模式 16dp 卡内边距、扁平模式用设置里的留白，
            // 再叠同一个气泡宽度上限。列表自己已经给了 16dp，这里只补卡内那一层。
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(horizontal = checkpointInsetDp(cardMode, flatPad.value).dp),
        )
    }
}
