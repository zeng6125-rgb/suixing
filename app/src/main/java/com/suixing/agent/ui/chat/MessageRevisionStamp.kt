package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage

/** Used by one flow collector; unchanged lists keep their revision across delta publishes. */
internal class MessageRevisionStamp {
    private var last: List<StoredMessage>? = null
    private var revision = 0L

    fun stamp(snapshot: ChatUiState): ChatUiState {
        if (snapshot.messages !== last) {
            last = snapshot.messages
            revision++
        }
        return snapshot.copy(messagesRevision = revision)
    }
}
