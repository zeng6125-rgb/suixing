package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.filter

internal data class LiveContent(val chatId: String, val runId: String, val parts: List<LivePart>)

internal fun ChatUiState.screenSnapshot(): ChatUiState = copy(
    liveParts = emptyList(),
    streaming = "",
    reasoning = "",
)

internal fun ChatUiState.liveContent(): LiveContent = LiveContent(conversationId, liveRunId, liveParts)

/** The token stream subscribes inside the live item; the stored transcript remains skippable. */
@Composable
internal fun LiveMessageParts(
    viewModel: ChatViewModel,
    chatId: String,
    runId: String,
    content: @Composable (List<LivePart>) -> Unit,
) {
    val flow = remember(viewModel, chatId, runId) {
        viewModel.liveContent.filter { it.chatId == chatId && it.runId == runId }
    }
    val live by flow.collectAsStateWithLifecycle(initialValue = viewModel.currentLiveContent())
    content(if (live.chatId == chatId && live.runId == runId) live.parts else emptyList())
}
