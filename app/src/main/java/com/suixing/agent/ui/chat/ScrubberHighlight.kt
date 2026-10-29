package com.suixing.agent.ui.chat

/** Keep restore and entrance changes out of the tick animations until the view has settled. */
internal fun scrubberHighlightSettling(
    restoring: Boolean,
    presented: Boolean,
    entrance: Float,
): Boolean = restoring || !presented || entrance < 1f
