package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import kotlin.math.abs

/** A quarter-width deliberate swipe leaves either anchor; quick flings follow their velocity. */
internal fun drawerDragTarget(wasOpen: Boolean, progress: Float, velocityPx: Float): DrawerValue {
    val open = when {
        abs(velocityPx) > 800f -> velocityPx > 0f
        wasOpen -> progress >= 0.75f
        else -> progress > 0.25f
    }
    return if (open) DrawerValue.Open else DrawerValue.Closed
}
