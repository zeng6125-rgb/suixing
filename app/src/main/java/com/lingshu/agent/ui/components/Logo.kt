package com.lingshu.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * The 灵枢 mark: a four-point sparkle with concave sides. Drawn rather than
 * shipped as a bitmap so it stays crisp at every size and can take a gradient.
 */
@Composable
fun LingshuMark(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val paint = brush ?: Brush.linearGradient(
        listOf(scheme.primary, scheme.secondary),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = this.size.minDimension / 2f
        val waist = r * 0.17f
        val path = Path().apply {
            moveTo(cx, cy - r)
            quadraticTo(cx + waist, cy - waist, cx + r, cy)
            quadraticTo(cx + waist, cy + waist, cx, cy + r)
            quadraticTo(cx - waist, cy + waist, cx - r, cy)
            quadraticTo(cx - waist, cy - waist, cx, cy - r)
            close()
        }
        drawPath(path, brush = paint)
    }
}

/**
 * The mark inside a soft radial glow, sized for empty states and onboarding.
 */
@Composable
fun LingshuOrb(
    size: Dp = 132.dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.42f),
                        scheme.secondary.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = this.size.minDimension / 2f,
                ),
            )
        }
        LingshuMark(size = size * 0.42f)
    }
}
