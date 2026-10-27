package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color

/** Shares the running indicator's slot, so a finished row keeps the same title alignment. */
@Composable
internal fun CompletionDot(modifier: Modifier = Modifier) {
    val color = Tone.OK.color(LocalDarkTheme.current)
    Box(
        modifier.size(14.dp)
            .semantics { contentDescription = "已完成，未读" }
            .drawBehind { drawCircle(color, radius = 3.5.dp.toPx()) },
    )
}
