package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Typing invalidates this composer scope instead of the transcript and app shell. */
@Composable
internal fun ComposerDraftText(
    actions: ChatScreenActions,
    conversationId: String,
    content: @Composable (String) -> Unit,
) {
    val textFlow = remember(actions, conversationId) {
        actions.drafts.map { it[conversationId]?.text.orEmpty() }.distinctUntilChanged()
    }
    val text by textFlow.collectAsStateWithLifecycle(initialValue = actions.draftFor(conversationId).text)
    content(text)
}
