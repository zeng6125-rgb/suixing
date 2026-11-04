package com.suixing.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.R

/**
 * The bot face: emerald disc with two tilted white eyes — one bitmap, shared by the home-screen
 * icon, the drawer head, the empty states, the assistant fallback avatar and the about page.
 *
 * It is a bitmap on purpose. The eyes are the artwork's own highlight pixels, and they are not
 * two copies of one shape: the right eye is narrower and sits higher than the left. Redrawing them
 * as geometry (which this composable used to do) drifted from the launcher artwork — the capsules
 * came out 43% larger in area than the reference. [R.drawable.brand_mark] is the same artwork as
 * the round launcher mipmaps, rendered at 4x so a 92dp use stays sharp.
 */
@Composable
fun SuixingMark(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.brand_mark),
        contentDescription = null,
        modifier = modifier.size(size),
    )
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
