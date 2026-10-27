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

/** Text updates replace their versioned wrapper; unchanged tool blocks retain their UI lists. */
internal class LivePartProjection(private val runId: String) {
    private var previous: List<LivePart> = emptyList()
    private var mapped: List<Part?> = emptyList()
    private var reasoningVisible = true
    private var rendered: List<Part> = emptyList()

    fun project(parts: List<LivePart>, showReasoning: Boolean): List<Part> {
        if (parts === previous && showReasoning == reasoningVisible) return rendered
        val next = parts.mapIndexed { index, part ->
            if (previous.getOrNull(index) === part &&
                (part !is LivePart.Thinking || showReasoning == reasoningVisible)) {
                mapped[index]
            } else when (part) {
                is LivePart.Thinking -> if (showReasoning) {
                    Part.Thinking(part.text, part.tokens, part.elapsedMs, runId)
                } else null
                is LivePart.Tools -> Part.Tools(part.calls.map(ToolLine::of))
                is LivePart.Text -> Part.Text(part.text, part.bornAtMs)
                is LivePart.Steer -> Part.Steer(part.message)
                is LivePart.Trace -> Part.Trace(part.note)
                is LivePart.Compress -> Part.Compress(part.checkpoint)
            }
        }
        val unchanged = next.size == mapped.size && next.indices.all { next[it] === mapped[it] }
        previous = parts
        mapped = next
        reasoningVisible = showReasoning
        if (!unchanged) rendered = next.filterNotNull()
        return rendered
    }
}

/** The token stream subscribes inside the live item; the stored transcript remains skippable. */
@Composable
internal fun LiveMessageParts(
    viewModel: ChatViewModel,
    chatId: String,
    runId: String,
    showReasoning: Boolean,
    content: @Composable (List<Part>) -> Unit,
) {
    val flow = remember(viewModel, chatId, runId) {
        viewModel.liveContent.filter { it.chatId == chatId && it.runId == runId }
    }
    val live by flow.collectAsStateWithLifecycle(initialValue = viewModel.currentLiveContent())
    val projection = remember(chatId, runId) { LivePartProjection(runId) }
    val source = if (live.chatId == chatId && live.runId == runId) live.parts else emptyList()
    content(projection.project(source, showReasoning))
}
