package com.suixing.agent.ui.chat

import com.suixing.agent.data.ViewportBookmark

/** Keep the active and previous conversation's measured position and prepared row documents. */
internal class ConversationViewportCache {
    private class Entry(
        var source: ChatUiState,
        var shownTurns: Int,
        var geometry: Triple<Int, Float, Float>,
        val storageReady: Boolean,
        val viewport: ConversationViewport,
    ) {
        fun matches(state: ChatUiState, turns: Int, dimensions: Triple<Int, Float, Float>, ready: Boolean): Boolean =
            !source.runningHere && !state.runningHere &&
                source.conversationId == state.conversationId && source.messages === state.messages &&
                source.activeRuns == state.activeRuns &&
                source.liveRunId == state.liveRunId && shownTurns == turns && geometry == dimensions &&
                storageReady == ready
    }

    private var active: Entry? = null
    private var previous: Entry? = null

    fun get(
        state: ChatUiState,
        bookmark: ViewportBookmark?,
        shownTurns: Int,
        geometry: Triple<Int, Float, Float>,
        storageReady: Boolean,
    ): ConversationViewport {
        val current = active
        if (current?.source?.conversationId == state.conversationId && current.storageReady == storageReady) {
            current.source = state
            current.shownTurns = shownTurns
            current.geometry = geometry
            return current.viewport
        }
        val reused = previous?.takeIf { it.matches(state, shownTurns, geometry, storageReady) }
        active = reused ?: Entry(
            state, shownTurns, geometry, storageReady, ConversationViewport(state.conversationId, bookmark),
        )
        previous = current
        return active!!.viewport.also { it.bookmark = bookmark }
    }
}
