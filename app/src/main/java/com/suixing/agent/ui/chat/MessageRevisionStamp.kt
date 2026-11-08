package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage

/**
 * Confined to the upstream map of ChatViewModel.state's stateIn. stateIn starts one sharing
 * collector; downstream StateFlow subscribers read its results without rerunning this map.
 * Concurrent _state writers therefore cannot call stamp concurrently. Unchanged immutable
 * lists keep their revision across delta publications.
 */
internal class MessageRevisionStamp {
    private var last: List<StoredMessage>? = null
    private var revision = 0L
    private var sourceTranscript: ChatTranscriptState? = null
    private var stampedTranscript: ChatTranscriptState? = null

    fun stamp(snapshot: ChatUiState): ChatUiState {
        if (snapshot.messages !== last) {
            last = snapshot.messages
            revision++
        }
        val source = snapshot.transcript
        if (source !== sourceTranscript) {
            sourceTranscript = source
            stampedTranscript = if (source.messagesRevision == revision) source
                else source.copy(messagesRevision = revision)
        }
        val stamped = checkNotNull(stampedTranscript)
        return if (source === stamped) snapshot
            else ChatUiState(snapshot.session, stamped, snapshot.run, snapshot.input)
    }
}
