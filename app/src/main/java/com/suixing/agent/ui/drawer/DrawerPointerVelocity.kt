package com.suixing.agent.ui.drawer

import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.unit.Velocity

/** Shared sampling for the fixed host and the home shield that moves with the drawer. */
internal class DrawerPointerVelocity {
    private val tracker = VelocityTracker1D(isDataDifferential = true)
    private var pointerId: PointerId? = null
    private var lastMotionTime = 0L

    fun trackDrawerPointer(change: PointerInputChange) {
        if (pointerId != change.id || change.changedToDownIgnoreConsumed()) {
            pointerId = change.id
            tracker.resetTracking()
            lastMotionTime = change.previousUptimeMillis
            if (change.previousPressed) {
                tracker.addDataPoint(change.previousUptimeMillis, 0f)
            }
        }
        if (!change.pressed) {
            if (change.uptimeMillis - lastMotionTime > 40L) tracker.resetTracking()
            return
        }
        // Positions within this event share the shield's current coordinate system.
        var previousX = change.previousPosition.x
        for (sample in change.historical) {
            val dx = sample.position.x - previousX
            tracker.addDataPoint(sample.uptimeMillis, dx)
            if (dx != 0f) lastMotionTime = sample.uptimeMillis
            previousX = sample.position.x
        }
        val dx = change.position.x - previousX
        tracker.addDataPoint(change.uptimeMillis, dx)
        if (dx != 0f) lastMotionTime = change.uptimeMillis
    }

    fun calculateVelocity() = Velocity(tracker.calculateVelocity(), 0f)
}
