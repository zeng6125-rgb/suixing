package com.suixing.agent.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import com.suixing.agent.ui.chat.markdown.MdBlock
import com.suixing.agent.ui.chat.markdown.MessageSegment

internal typealias RowMarkdownDocuments = Map<String, List<MdBlock>>

/** Preparing one message must not invalidate every visible message's document lookup. */
internal class RowMarkdownCache {
    private val rows = HashMap<String, MutableState<RowMarkdownDocuments>>()
    private val imageSegments = HashMap<String, MutableState<Map<String, List<MessageSegment>>>>()
    private val preparedRows = HashSet<String>()
    private var retainedRows: Set<String>? = null

    fun documentsFor(id: String): State<RowMarkdownDocuments> = rows.getOrPut(id) {
        mutableStateOf(emptyMap(), referentialEqualityPolicy())
    }

    fun peek(id: String): RowMarkdownDocuments? = rows[id]?.value
    fun peekImageSegments(id: String): Map<String, List<MessageSegment>> = imageSegments[id]?.value.orEmpty()

    fun imageSegmentsFor(id: String): State<Map<String, List<MessageSegment>>> = imageSegments.getOrPut(id) {
        mutableStateOf(emptyMap(), referentialEqualityPolicy())
    }

    fun publish(id: String, documents: RowMarkdownDocuments, segments: Map<String, List<MessageSegment>> = emptyMap()) {
        documentsFor(id)
        rows.getValue(id).value = documents
        imageSegmentsFor(id)
        imageSegments.getValue(id).value = segments
        if (documents.isEmpty() && segments.isEmpty()) preparedRows.remove(id) else preparedRows.add(id)
    }

    fun retainRows(ids: Set<String>) {
        if (retainedRows === ids) return
        retainedRows = ids
        rows.keys.retainAll(ids)
        imageSegments.keys.retainAll(ids)
        preparedRows.retainAll(ids)
    }

    fun hasPreparedDocuments(): Boolean = preparedRows.isNotEmpty()

    /** Keep lightweight row slots, but release parsed documents outside the preparation window. */
    fun retainDocuments(ids: Set<String>) {
        val iterator = preparedRows.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id !in ids) {
                rows[id]?.value = emptyMap()
                imageSegments[id]?.value = emptyMap()
                iterator.remove()
            }
        }
    }
}
