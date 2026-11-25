package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import com.suixing.agent.ui.chat.markdown.MdBlock
import com.suixing.agent.ui.chat.markdown.MessageSegment
import com.suixing.agent.ui.chat.markdown.SettledBlocks
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
        var readyToPublish: ArrayList<Triple<String, Map<String, List<MdBlock>>, Map<String, List<MessageSegment>>>>? = null
        var backgroundInputs: ArrayList<Triple<ChatRow, RowMarkdownDocuments?, Map<String, List<MessageSegment>>>>? = null
        for (row in rows) {
            val id = row.message.id
            val documents = rowDocuments.peek(id)
            val cachedSegments = rowDocuments.peekImageSegments(id)
            when (val plan = planRowMarkdown(row.parts, documents, cachedSegments, SettledBlocks::get)) {
                RowMarkdownPlan.Cached -> Unit
                is RowMarkdownPlan.Ready -> {
                    val target = readyToPublish
                        ?: ArrayList<Triple<String, Map<String, List<MdBlock>>, Map<String, List<MessageSegment>>>>(rows.size)
                            .also { readyToPublish = it }
                    target.add(Triple(id, plan.documents, plan.segments))
                }
                RowMarkdownPlan.NeedsBackground -> {
                    val target = backgroundInputs
                        ?: ArrayList<Triple<ChatRow, RowMarkdownDocuments?, Map<String, List<MessageSegment>>>>(rows.size)
                            .also { backgroundInputs = it }
                    target.add(Triple(row, documents, cachedSegments))
                }
            }
        }
        val coldSources = backgroundInputs
        if (coldSources != null) {
            val (pending, prepared) = withContext(Dispatchers.Default) {
                val pending = coldSources.mapNotNull { (row, documents, cachedSegments) ->
                    val segments = imageSegmentsToPrepare(row.parts, cachedSegments)
                    val texts = markdownTextsToPrepare(
                        row.parts,
                        if (segments === cachedSegments || segments.keys == cachedSegments.keys) documents else null,
                        segments,
                    )
                    if (texts == null) null else Triple(row.message.id, texts, segments)
                }
                val hasLongText = pending.any { (_, texts, _) ->
                    texts.any { it.length >= SettledBlocks.MIN_CHARS }
                }
                val prepared = if (hasLongText) prepareMarkdownTexts(pending.flatMap { it.second }) else emptyMap()
                pending to prepared
            }
            // A parser may finish after a branch/page/chat changed during suspension.
            // Its old document must not publish into, or evict, the current row cache.
            if (!sourceIsCurrent()) return
            readyToPublish?.forEach { (id, documents, segments) ->
                rowDocuments.publish(id, documents, segments)
            }
            for ((id, texts, segments) in pending) {
                // 每行只拿自己那几个文本的解析结果（与原来逐行调用时的内容一致）。
                // Look up this row's texts directly instead of scanning the whole batch for every row.
                val documents = if (prepared.isEmpty()) emptyMap() else buildMap {
                    for (text in texts) prepared[text]?.let { put(text, it) }
                }
                rowDocuments.publish(id, documents, segments)
            }
        } else if (readyToPublish != null) {
            if (!sourceIsCurrent()) return
            readyToPublish.forEach { (id, documents, segments) ->
                rowDocuments.publish(id, documents, segments)
            }
        }
        if (!sourceIsCurrent()) return
        if (rowDocuments.hasPreparedDocuments()) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val retained = HashSet<String>(rows.size + visibleItems.size)
            for (row in rows) retained.add(row.message.id)
            for (index in visibleItems.indices) {
                rowKeyIndices[visibleItems[index].key]?.let { retained.add(visible[it].message.id) }
            }
            rowDocuments.retainDocuments(retained)
        }
    }

    return ::prepareRows
}
