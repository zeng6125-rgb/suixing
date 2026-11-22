package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import com.suixing.agent.core.NativeCore
import com.suixing.agent.core.NativeCoreJni
import kotlin.math.abs

/** A fling overrides the distance target at the AndroidX DrawerLayout velocity threshold. */
internal fun drawerDragTarget(openByDistance: Boolean, velocityDpPerSecond: Float): DrawerValue {
    val open = when {
        NativeCore.available -> NativeCoreJni.nativeDrawerReleaseOpen(openByDistance, velocityDpPerSecond)
        abs(velocityDpPerSecond) >= 400f -> velocityDpPerSecond > 0f
        else -> openByDistance
    }
    return if (open) DrawerValue.Open else DrawerValue.Closed
}
