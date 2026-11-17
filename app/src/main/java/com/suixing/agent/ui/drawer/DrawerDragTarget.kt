package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import kotlin.math.abs

/** A quarter-width deliberate swipe leaves either anchor; quick flings follow their velocity. */
internal fun drawerDragTarget(
    wasOpen: Boolean,
    progress: Float,
    velocityPx: Float,
    startProgress: Float = if (wasOpen) 1f else 0f,
): DrawerValue {
    val open = when {
        abs(velocityPx) > 800f -> velocityPx > 0f
        startProgress > 0f && startProgress < 1f ->
            if (progress == startProgress) progress >= 0.5f else progress > startProgress
        startProgress == 1f -> progress >= 0.75f
        else -> progress > 0.25f
    }
    return if (open) DrawerValue.Open else DrawerValue.Closed
}
