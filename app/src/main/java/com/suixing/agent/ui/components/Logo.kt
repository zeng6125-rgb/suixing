package com.suixing.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The bot face: emerald disc with two tilted white eyes — the same face the launcher
 * ships (`mipmap-*dpi/ic_launcher.png`), so the drawer head, the empty
 * state and the home-screen icon are one mark instead of three.
 *
 * Eye geometry is the measured fit to the reference artwork, expressed as fractions
 * of the disc radius: left eye 0.162r wide, −26°, centred at (+0.189r, −0.429r);
 * right eye 0.104r wide, −25°, at (+0.607r, −0.508r); both 0.376r long. They are
 * deliberately unequal — the thinner right eye is what makes the face read as alive.
 *
 * Flat by design: no gradient, no shadow. The disc is the artwork's own emerald.
 */
@Composable
fun SuixingMark(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(color = MARK_BALL, radius = r)
        drawMarkEye(r, dx = 0.189f, dy = -0.429f, w = 0.162f, l = 0.376f, angle = -26f)
        drawMarkEye(r, dx = 0.607f, dy = -0.508f, w = 0.104f, l = 0.376f, angle = -25f)
    }
}

/** Brand emerald of the mark; matches the launch icon's `#126341`. */
private val MARK_BALL = Color(0xFF126341)

private fun DrawScope.drawMarkEye(
    r: Float,
    dx: Float,
    dy: Float,
    w: Float,
    l: Float,
    angle: Float,
) {
    val ex = center.x + dx * r
    val ey = center.y + dy * r
    // 负角度 = 顶部向左倾，与素材一致。
    rotate(degrees = angle, pivot = Offset(ex, ey)) {
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(ex - w * r / 2f, ey - l * r / 2f),
            size = Size(w * r, l * r),
            cornerRadius = CornerRadius(w * r / 2f, w * r / 2f),
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
