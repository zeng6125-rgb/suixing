package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import com.suixing.agent.ui.chat.markdown.prepareMarkdownTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A parser may finish after paging, editing, or switching chats; only a current projection publishes. */
@Composable
internal fun rememberChatMarkdownPreparation(
    state: ChatUiState,
    transcript: ChatTranscriptProjection,
    actions: ChatScreenActions,
): suspend (List<ChatRow>) -> Unit {
    val rowDocuments = transcript.rowDocuments
    val listState = transcript.listState
    val rowKeyIndices = transcript.rowKeyIndices
    val visible = transcript.visible
    suspend fun prepareRows(rows: List<ChatRow>) {
        fun sourceIsCurrent(): Boolean {
            val current = actions.screenState.value
            return current.conversationId == state.conversationId &&
                current.messagesRevision == state.messagesRevision && current.activeRuns == state.activeRuns &&
                current.liveRunId == state.liveRunId && current.runningHere == state.runningHere
        }
        val sources = rows.map { row -> Triple(row, rowDocuments.peek(row.message.id), rowDocuments.peekImageSegments(row.message.id)) }
        val (pending, prepared) = withContext(Dispatchers.Default) {
            val pending = sources.mapNotNull { (row, documents, cachedSegments) ->
                val segments = imageSegmentsToPrepare(row.parts, cachedSegments)
                val texts = markdownTextsToPrepare(row.parts,
                    if (segments.keys == cachedSegments.keys) documents else null, segments)
                if (texts == null) null
                else Triple(row.message.id, texts.orEmpty(), segments)
            }
            pending to prepareMarkdownTexts(pending.flatMap { it.second })
        }
        if (pending.isNotEmpty()) {
            // A parser may finish after a branch/page/chat changed during suspension.
            // Its old document must not publish into, or evict, the current row cache.
            if (!sourceIsCurrent()) return
            for ((id, texts, segments) in pending) {
                // 每行只拿自己那几个文本的解析结果（与原来逐行调用时的内容一致）。
                // Look up this row's texts directly instead of scanning the whole batch for every row.
                val documents = buildMap {
                    for (text in texts) prepared[text]?.let { put(text, it) }
                }
                rowDocuments.publish(id, documents, segments)
            }
        }
        if (!sourceIsCurrent()) return
        val retained = rows.mapTo(HashSet()) { it.message.id }
        listState.layoutInfo.visibleItemsInfo.forEach { item ->
            rowKeyIndices[item.key]?.let { retained.add(visible[it].message.id) }
        }
        rowDocuments.retainDocuments(retained)
    }

    return ::prepareRows
}
