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
    viewModel: ChatViewModel,
    conversationId: String,
    content: @Composable (String) -> Unit,
) {
    val textFlow = remember(viewModel, conversationId) {
        viewModel.drafts.map { it[conversationId]?.text.orEmpty() }.distinctUntilChanged()
    }
    val text by textFlow.collectAsStateWithLifecycle(initialValue = viewModel.draftFor(conversationId).text)
    content(text)
}
