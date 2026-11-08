package com.suixing.agent.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Owns publication boundaries; streaming text never becomes a screen-wide subscription. */
internal class ChatStateStore(scope: CoroutineScope) {
    val mutable = MutableStateFlow(ChatUiState())
    // Exactly one upstream collector stamps list identity, regardless of downstream readers.
    private val revision = MessageRevisionStamp()
    val state = mutable.map(revision::stamp).stateIn(scope, SharingStarted.Eagerly, mutable.value)
    val screen = state.projectState { it.screenSnapshot() }
    val session = state.projectState { it.session }
    val transcript = state.projectState { it.transcript }
    val run = state.projectState { it.run }
    val input = state.projectState { it.input }
    val live = mutable.map { it.liveContent() }.distinctUntilChanged()
    val active = mutable.projectState { it.liveParts.isNotEmpty() || it.reasoningRevision > 0 }
    fun currentLive() = mutable.value.liveContent()
}
