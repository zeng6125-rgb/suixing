package com.suixing.agent.ui.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import com.suixing.agent.ui.chat.markdown.MdBlock

internal typealias RowMarkdownDocuments = Map<String, List<MdBlock>>

/** Preparing one message must not invalidate every visible message's document lookup. */
internal class RowMarkdownCache {
    private val rows = HashMap<String, MutableState<RowMarkdownDocuments>>()
    private val preparedRows = HashSet<String>()
    private var retainedRows: Set<String>? = null

    fun documentsFor(id: String): State<RowMarkdownDocuments> = rows.getOrPut(id) {
        mutableStateOf(emptyMap(), referentialEqualityPolicy())
    }

    fun peek(id: String): RowMarkdownDocuments? = rows[id]?.value

    fun publish(id: String, documents: RowMarkdownDocuments) {
        documentsFor(id)
        rows.getValue(id).value = documents
        if (documents.isEmpty()) preparedRows.remove(id) else preparedRows.add(id)
    }

    fun retainRows(ids: Set<String>) {
        if (retainedRows === ids) return
        retainedRows = ids
        rows.keys.retainAll(ids)
        preparedRows.retainAll(ids)
    }

    /** Keep lightweight row slots, but release parsed documents outside the preparation window. */
    fun retainDocuments(ids: Set<String>) {
        val iterator = preparedRows.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id !in ids) {
                rows[id]?.value = emptyMap()
                iterator.remove()
            }
        }
    }
}
