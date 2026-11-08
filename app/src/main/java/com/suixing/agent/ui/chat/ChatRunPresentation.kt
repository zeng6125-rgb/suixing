package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.suixing.agent.core.Core
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One shared elapsed clock and expansion ledger span live-to-stored handover. */
internal class ChatRunPresentation(
    val liveStatusText: String,
    val runStartedAt: SnapshotStateMap<String, Long>,
    val runKey: String,
    val runElapsedClock: State<Long>,
    val reasoningExpanded: SnapshotStateMap<String, Boolean>,
    val reasoningExpansion: ReasoningExpansion,
)

@Composable
internal fun rememberChatRunPresentation(state: ChatUiState): ChatRunPresentation {
    // 压缩的告知就是过程区里那一块（[CompressionBlock]，位置就是它发生的地方），不再往气泡
    // 下面塞一行提示（用户：「不需要这种在卡片下的提示」）。
    val liveStatusText = state.status.ifBlank {
        // No ellipsis: 「正在思考…」 was read as a truncation of something longer. The line
        // already says the state, and the clock that follows says how long it has held.
        "正在思考中"
    }
    // When the live run began, remembered per run so a switch away and back does not
    // restart the caption's clock. Written in an effect, never during composition.
    val runStartedAt = remember { mutableStateMapOf<String, Long>() }
    // Keyed by the run, not by the conversation: a run that has not reported its id yet
    // would otherwise land on the conversation's key and read the previous run's start.
    val runKey = "${state.conversationId}:${state.liveRunId}"
    LaunchedEffect(state.runningHere, runKey) {
        if (state.runningHere) {
            runStartedAt.getOrPut(runKey) { System.currentTimeMillis() }
        } else {
            // The run is over, so its clock has no reader left — and a table that only ever
            // grows would hand the conversation's next run the start time of this one.
            runStartedAt.keys
                .filter { it.startsWith("${state.conversationId}:") }
                .forEach { runStartedAt.remove(it) }
        }
    }
    val runElapsedClock = rememberRunElapsed(runStartedAt[runKey] ?: 0L, state.runningHere, clockKey = runKey)
    // Which thinking blocks are open, per conversation. One table for the screen, because
    // the live block and the stored block it becomes are two different composables — a
    // remember inside the block was born fresh on that hand-over, which is why a think
    // never folded when it settled — and because the live row's key was shared by every
    // conversation, so two chats were reading the same switch.
    val reasoningExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val reasoningExpansion = remember(state.conversationId) {
        ReasoningExpansion(state.conversationId, reasoningExpanded)
    }
    SideEffect {
        if (state.liveRunId.isNotBlank() && reasoningExpansion.stored("time:${state.liveRunId}") == null) {
            // Do not insert a newly-created timestamp above an already-read live reply.
            reasoningExpansion.set("time:${state.liveRunId}", false)
        }
    }


    return ChatRunPresentation(
        liveStatusText = liveStatusText,
        runStartedAt = runStartedAt,
        runKey = runKey,
        runElapsedClock = runElapsedClock,
        reasoningExpanded = reasoningExpanded,
        reasoningExpansion = reasoningExpansion,
    )
}

@Composable
internal fun PruneChatPresentation(
    actions: ChatScreenActions,
    scroll: ChatScroll,
    transcript: ChatTranscriptProjection,
    presentation: ChatRunPresentation,
) {
    val runStartedAt = presentation.runStartedAt
    val reasoningExpanded = presentation.reasoningExpanded
    var shownTurnsByConversation by transcript.windowState
    LaunchedEffect(actions) {
        Core.engine.runsFlow.collect { runs ->
            val currentChat = actions.state.value.conversationId
            runStartedAt.keys.removeAll { key ->
                val chatId = key.substringBefore(':')
                chatId != currentChat && chatId !in runs
            }
        }
    }
    LaunchedEffect(actions, scroll) {
        actions.conversations.map { chats -> chats.mapTo(HashSet()) { it.id } }
            .distinctUntilChanged()
            .collect { ids ->
                val retained = ids + actions.state.value.conversationId
                shownTurnsByConversation = shownTurnsByConversation.filterKeys { it in retained }
                reasoningExpanded.keys.removeAll { it.substringBefore(':') !in retained }
                runStartedAt.keys.removeAll { it.substringBefore(':') !in retained }
            }
    }

}
