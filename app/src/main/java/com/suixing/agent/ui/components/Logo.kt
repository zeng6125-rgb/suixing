package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The delivered round artwork, shared by the drawer, empty states and assistant fallback.
 * Running marks use signal-and-blink; static callers do not create an animation clock.
 */
@Composable
fun SuixingMark(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    animateIdle: Boolean = false,
) {
    RoundBrandIcon(size, modifier, running, animateIdle)
}

/**
 * The mark inside a soft radial glow, sized for empty states and onboarding.
 */
@Composable
fun SuixingOrb(
    size: Dp = 132.dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(size).drawWithCache {
            val glow = Brush.radialGradient(
                colors = listOf(
                    scheme.primary.copy(alpha = 0.42f),
                    scheme.secondary.copy(alpha = 0.14f),
                    Color.Transparent,
                ),
                center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f),
                radius = this.size.minDimension / 2f,
            )
            onDrawBehind { drawCircle(brush = glow) }
        },
        contentAlignment = Alignment.Center,
    ) {
        SuixingMark(size = size * 0.52f, animateIdle = true)
    }
}
