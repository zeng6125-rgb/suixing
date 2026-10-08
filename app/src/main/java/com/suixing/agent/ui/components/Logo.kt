package com.suixing.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The white feather, as one [ImageVector]: the same three strokes as
 * `res/drawable/ic_launcher_foreground.xml` (vane outline, shaft with its exposed
 * calamus, one barb) on the same 108 viewport, so the two cannot drift apart.
 *
 * The launcher asset is pure white for the home screen; here the colour is the
 * caller's tint, which is how the drawer puts white on the brand gradient and a
 * bubble puts the brand colour on a light disc. Stroke colour is left unset on
 * purpose: an [Icon] tints whatever the path does not pin down.
 */
val FeatherIcon: ImageVector = ImageVector.Builder(
    name = "ic_feather",
    defaultWidth = 108.dp,
    defaultHeight = 108.dp,
    viewportWidth = 108f,
    viewportHeight = 108f,
).apply {
    path(
        strokeLineWidth = 5.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(36f, 81.8f)
        curveTo(46f, 60f, 44.1f, 30.6f, 76f, 24f)
        curveTo(73f, 42f, 52.1f, 72.9f, 36f, 81.8f)
        close()
        moveTo(32f, 87.5f)
        lineTo(76f, 24f)
        moveTo(51.8f, 58.9f)
        lineTo(58.6f, 59.5f)
    }
}.build()

/**
 * The 随行 mark: the white feather on the brand gradient disc.
 *
 * It replaces the old trail-of-dots-and-spark mark, which read brown at small sizes
 * and was not the feather the launcher ships. One mark everywhere: the drawer head
 * takes it as-is, a bubble avatar tints [FeatherIcon] instead so the feather stays
 * legible on a light disc.
 */
@Composable
fun SuixingMark(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
    featherColor: Color = Color.White,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(scheme.primary, scheme.secondary),
                    start = Offset.Zero,
                    end = Offset(size.toPx(), size.toPx()),
                ),
            )
        }
        Icon(
            imageVector = FeatherIcon,
            contentDescription = null,
            tint = featherColor,
            modifier = Modifier.size(size * 0.62f),
        )
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
