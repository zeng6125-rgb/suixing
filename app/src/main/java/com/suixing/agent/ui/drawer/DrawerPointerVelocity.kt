package com.suixing.agent.ui.drawer

import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.unit.Velocity

/** Shared sampling for the fixed host and the home shield that moves with the drawer. */
internal class DrawerPointerVelocity {
    // Absolute physical samples let Impulse forget earlier motion after a reversal.
    private val tracker = VelocityTracker1D(isDataDifferential = false)
    private var pointerId: PointerId? = null
    private var physicalX = 0f
    private var lastMotionTime = 0L

    fun trackDrawerPointer(change: PointerInputChange) {
        if (pointerId != change.id || change.changedToDownIgnoreConsumed()) {
            pointerId = change.id
            tracker.resetTracking()
            physicalX = 0f
            lastMotionTime = change.previousUptimeMillis
            if (change.previousPressed) {
                tracker.addDataPoint(change.previousUptimeMillis, 0f)
            }
        }
        if (!change.pressed && change.position.x == change.previousPosition.x) {
            if (change.uptimeMillis - lastMotionTime > 40L) tracker.resetTracking()
            return
        }
        // Positions within this event share the shield's current coordinate system.
        var previousX = change.previousPosition.x
        for (sample in change.historical) {
            val dx = sample.position.x - previousX
            physicalX += dx
            tracker.addDataPoint(sample.uptimeMillis, physicalX)
            if (dx != 0f) lastMotionTime = sample.uptimeMillis
            previousX = sample.position.x
        }
        val dx = change.position.x - previousX
        physicalX += dx
        tracker.addDataPoint(change.uptimeMillis, physicalX)
        if (dx != 0f) lastMotionTime = change.uptimeMillis
    }

    fun calculateVelocity() = Velocity(tracker.calculateVelocity(), 0f)
}
