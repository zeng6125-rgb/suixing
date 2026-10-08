package com.suixing.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 随行 mark: a trail of three dots rising into a spark — "walking along with
 * you". Drawn rather than shipped as a bitmap so it stays crisp at every size and
 * can take a gradient. The same geometry lives in
 * `res/drawable/ic_launcher_foreground.xml`; keep the two in sync.
 */
private val TRAIL = listOf(
    Triple(0.13f, 0.89f, 0.085f),
    Triple(0.33f, 0.66f, 0.110f),
    Triple(0.53f, 0.43f, 0.125f),
)
private const val TRAIL_ALPHA_MIN = 0.40f
private const val SPARK_X = 0.78f
private const val SPARK_Y = 0.21f
private const val SPARK_R = 0.19f
private const val SPARK_WAIST = 0.12f

@Composable
fun SuixingMark(
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
        val s = this.size.minDimension
        TRAIL.forEachIndexed { index, (fx, fy, fr) ->
            val alpha = TRAIL_ALPHA_MIN +
                (1f - TRAIL_ALPHA_MIN) * index / (TRAIL.size - 1).coerceAtLeast(1)
            drawCircle(
                brush = paint,
                radius = fr * s,
                center = Offset(fx * s, fy * s),
                alpha = alpha,
            )
        }
        val cx = SPARK_X * s
        val cy = SPARK_Y * s
        val r = SPARK_R * s
        val waist = r * SPARK_WAIST
        val spark = Path().apply {
            moveTo(cx, cy - r)
            quadraticTo(cx + waist, cy - waist, cx + r, cy)
            quadraticTo(cx + waist, cy + waist, cx, cy + r)
            quadraticTo(cx - waist, cy + waist, cx - r, cy)
            quadraticTo(cx - waist, cy - waist, cx, cy - r)
            close()
        }
        drawPath(spark, brush = paint)
    }
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
        SuixingMark(size = size * 0.52f)
    }
}
