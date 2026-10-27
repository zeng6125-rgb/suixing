package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.suixing.agent.core.Core
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

internal fun ChatUiState.readableConversationId(): String? =
    conversationId.takeIf { it.isNotBlank() && !loading && messages.isNotEmpty() }

/** Covered, paused or still-loading chats do not acknowledge a background completion. */
@Composable
internal fun ObserveConversationRead(chat: ChatViewModel, visible: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reads = Core.conversationReads
    LaunchedEffect(chat, reads, lifecycle, visible) {
        if (!visible) {
            reads.viewing(null)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                combine(chat.state, chat.presentedConversation) { state, presented ->
                    state.readableConversationId()?.takeIf { it == presented }
                }
                    .distinctUntilChanged().collect { reads.viewing(it) }
            } finally {
                reads.viewing(null)
            }
        }
    }
}
