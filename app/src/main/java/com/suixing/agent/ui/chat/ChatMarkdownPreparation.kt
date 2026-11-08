package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.suixing.agent.ui.chat.markdown.prepareMarkdownTexts
import kotlinx.coroutines.Dispatchers

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
        // **整批一次**（性能审计 改动6）：附近准备窗口变化时，把所有行合为一次
        // `prepareMarkdownTexts` 调用，避免逐行切到 `Dispatchers.Default`；缓存命中行只遍历
        // parts，不再创建文本列表。
        val pending = mutableListOf<Pair<String, List<String>>>()
        for (row in rows) {
            val texts = markdownTextsToPrepare(row.parts, rowDocuments.peek(row.message.id)) ?: continue
            pending += row.message.id to texts
        }
        if (pending.isNotEmpty()) {
            // 短文本在 `prepareMarkdownTexts` 内部就被过滤掉（那里有 MIN_CHARS 短路），
            // 所以这里整批传过去不会让短文本被解析。
            val prepared = prepareMarkdownTexts(pending.flatMap { it.second })
            // A parser may finish after a branch/page/chat changed during suspension.
            // Its old document must not publish into, or evict, the current row cache.
            if (!sourceIsCurrent()) return
            for ((id, texts) in pending) {
                // 每行只拿自己那几个文本的解析结果（与原来逐行调用时的内容一致）。
                // Look up this row's texts directly instead of scanning the whole batch for every row.
                val documents = buildMap {
                    for (text in texts) prepared[text]?.let { put(text, it) }
                }
                rowDocuments.publish(id, documents)
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
