package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class LiveContent(val chatId: String, val runId: String, val parts: List<LivePart>)

internal fun ChatUiState.screenSnapshot(): ChatUiState = copy(
    liveParts = emptyList(),
    streaming = "",
    // 思考正文现在以修订号参与判等（issue #57 §3）；快照里把它归零，
    // 语义与原来"清空 reasoning 字符串"一致：这份快照不再代表活的思考流。
    reasoningRevision = 0,
)

internal fun ChatUiState.liveContent(): LiveContent = LiveContent(conversationId, liveRunId, liveParts)

/** Text updates replace their versioned wrapper; unchanged tool blocks retain their UI lists. */
internal class LivePartProjection(private val runId: String) {
    private var previous: List<LivePart> = emptyList()
    private var mapped: List<Part?> = emptyList()
    private var reasoningVisible = true
    private var rendered: List<Part> = emptyList()

    @Synchronized
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
                is LivePart.Text -> {
                    val text = part.text
                    Part.Text(
                        text = text,
                        bornAtMs = part.bornAtMs,
                        sourceVersion = part.version,
                        sourceId = System.identityHashCode(part.buffer),
                        hasImageMarker = part.buffer.hasImageMarkerIn(text),
                    )
                }
                is LivePart.Steer -> Part.Steer(part.message)
                is LivePart.Trace -> Part.Trace(part.note)
                is LivePart.Compress -> Part.Compress(part.checkpoint)
            }
        }
        val unchanged = next.size == mapped.size && next.indices.all { next[it] === mapped[it] }
        previous = parts
        mapped = next
        reasoningVisible = showReasoning
        if (!unchanged) rendered = buildList {
            var index = 0
            while (index < next.size) {
                val start = index
                val part = next[index++]
                if (part !is Part.Tools) {
                    if (part != null) add(part)
                    continue
                }
                var callCount = part.calls.size
                while (next.getOrNull(index) is Part.Tools) {
                    callCount += (next[index++] as Part.Tools).calls.size
                }
                val old = rendered.getOrNull(size) as? Part.Tools
                var callIndex = 0
                add(if (old != null && old.calls.size == callCount &&
                    (start until index).all { (next[it] as Part.Tools).calls.all { call -> old.calls[callIndex++] === call } }
                ) old else if (index == start + 1) part else Part.Tools(buildList {
                    for (position in start until index) addAll((next[position] as Part.Tools).calls)
                }))
            }
        }
        return rendered
    }
}

internal fun projectLiveContent(
    source: Flow<LiveContent>,
    projection: LivePartProjection,
    chatId: String,
    runId: String,
    showReasoning: Boolean,
): Flow<Pair<LiveContent, List<Part>>> = source
    .conflate()
    .flowOn(Dispatchers.Default)
    .map { live ->
        // Make the text snapshot on demand. A paused collector only buffers a source revision.
        val parts = if (live.chatId == chatId && live.runId == runId) {
            withContext(Dispatchers.Default) { projection.project(live.parts, showReasoning) }
        } else emptyList()
        live to parts
    }

/** The token stream subscribes inside the live item; the stored transcript remains skippable. */
@Composable
internal fun LiveMessageParts(
    actions: ChatScreenActions,
    chatId: String,
    runId: String,
    showReasoning: Boolean,
    content: @Composable (List<Part>) -> Unit,
) {
    val projection = remember(chatId, runId) { LivePartProjection(runId) }
    val flow = remember(actions, chatId, runId, showReasoning) {
        projectLiveContent(actions.liveContent, projection, chatId, runId, showReasoning)
    }
    key(chatId, runId) {
        val prepared by flow.collectAsStateWithLifecycle<Pair<LiveContent, List<Part>>?>(initialValue = null)
        val parts = prepared?.takeIf { it.first.chatId == chatId && it.first.runId == runId }?.second.orEmpty()
        val visible = remember(IdentityKey(parts), showReasoning) {
            if (showReasoning || parts.none { it is Part.Thinking }) parts
            else parts.filterNot { it is Part.Thinking }
        }
        content(visible)
        InitialViewportContentReady(prepared != null)
    }
}
