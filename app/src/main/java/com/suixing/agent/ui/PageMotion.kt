package com.suixing.agent.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

/** Foreground routes and the persistent home use the same timing and depth. */
internal object PageMotion {
    const val ENTER_MS = 320
    const val EXIT_MS = 280
    const val DEPTH_FRACTION = 1f / 14f
    val EASING = CubicBezierEasing(0.32f, 0f, 0.18f, 1f)

    fun <T> spec(entering: Boolean) = tween<T>(
        durationMillis = if (entering) ENTER_MS else EXIT_MS,
        easing = EASING,
    )
}
