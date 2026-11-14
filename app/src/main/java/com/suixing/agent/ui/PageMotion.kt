package com.suixing.agent.ui

import androidx.compose.animation.core.spring

/** Routes and the persistent home share a velocity-preserving spring and depth. */
internal object PageMotion {
    const val DEPTH_FRACTION = 1f / 14f

    fun <T> spec(entering: Boolean) = spring<T>(
        dampingRatio = 1f,
        stiffness = if (entering) 700f else 900f,
    )
}
