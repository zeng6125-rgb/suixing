package com.suixing.agent.ui.drawer

import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange

/** Shared sampling for the fixed host and the home shield that moves with the drawer. */
internal fun VelocityTracker.trackDrawerPointer(change: PointerInputChange) {
    // Local positions can remain stationary or reverse when the shield follows the panel.
    // Compose samples the event's original coordinates and history, independent of that offset.
    addPointerInputChange(change)
}
