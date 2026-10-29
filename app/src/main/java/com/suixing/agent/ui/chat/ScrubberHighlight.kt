package com.suixing.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Mount at the restored position, then share the transcript's entrance clock and motion. */
@Composable
internal fun ScrubberPresentation(
    conversationId: String,
    presented: () -> Boolean,
    entrance: () -> Float,
    content: @Composable (Modifier) -> Unit,
) = key(conversationId) {
    val latestPresented by rememberUpdatedState(presented)
    val latestEntrance by rememberUpdatedState(entrance)
    // Position restoration is complete before presented flips. Waiting for entrance=1 would
    // leave the already-visible transcript without its rail for the rest of the animation.
    val ready by remember { derivedStateOf { latestPresented() } }
    if (ready) content(Modifier.graphicsLayer {
        val progress = latestEntrance()
        alpha = ConversationEntrance.alpha(progress)
        translationY = ConversationEntrance.remainingTravel(progress) * ConversationEntrance.TRAVEL_DP.dp.toPx()
    })
}

/** Keep restore and entrance changes out of the tick animations until the view has settled. */
internal fun scrubberHighlightSettling(
    restoring: Boolean,
    presented: Boolean,
    entrance: Float,
): Boolean = restoring || !presented || entrance < 1f
