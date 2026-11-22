package com.suixing.agent.ui.drawer

import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.unit.Velocity
import com.suixing.agent.core.NativeCore
import com.suixing.agent.core.NativeCoreJni
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val VELOCITY_HISTORY_SIZE = 20
private const val VELOCITY_SAMPLE_BYTES = 16

/** Shared sampling for the fixed host and the home shield that moves with the drawer. */
internal class DrawerPointerVelocity(private val touchSlop: Float) {
    private val nativeSamples = if (NativeCore.available) {
        ByteBuffer.allocateDirect(VELOCITY_HISTORY_SIZE * VELOCITY_SAMPLE_BYTES).order(ByteOrder.nativeOrder())
    } else {
        null
    }
    private val tracker = if (nativeSamples == null) VelocityTracker1D(isDataDifferential = false) else null
    private var nextSample = 0
    private var sampleCount = 0
    private var pointerId: PointerId? = null
    private var physicalX = 0f
    private var trackedX = 0f
    private var trackedTime = 0L
    private var lastMotionTime = 0L
    private var lastMotionDelta = 0f

    fun trackDrawerPointer(change: PointerInputChange) {
        if (pointerId != change.id || change.changedToDownIgnoreConsumed()) {
            pointerId = change.id
            clearSamples()
            physicalX = 0f
            trackedX = 0f
            trackedTime = change.previousUptimeMillis
            lastMotionTime = change.previousUptimeMillis
            lastMotionDelta = 0f
            if (change.previousPressed) {
                addSample(change.previousUptimeMillis, 0f)
            }
        }
        // Positions within this event share the shield's current coordinate system.
        var previousX = change.previousPosition.x
        for (sample in change.historical) {
            recordSample(sample.uptimeMillis, sample.position.x - previousX)
            previousX = sample.position.x
        }
        if (change.pressed || change.position.x != previousX) {
            recordSample(change.uptimeMillis, change.position.x - previousX)
        }
        if (!change.pressed && change.uptimeMillis - lastMotionTime > 40L) clearSamples()
    }

    private fun recordSample(time: Long, dx: Float) {
        physicalX += dx
        val delta = physicalX - trackedX
        if (delta * lastMotionDelta < 0f) {
            // A fingertip can roll back a few pixels on lift. Keep tracking the panel itself
            // pixel-for-pixel, but start a new velocity segment only after a real reversal.
            if (abs(delta) < touchSlop) return
            clearSamples()
            addSample(trackedTime, trackedX)
        }
        if (delta != 0f) {
            lastMotionDelta = delta
            lastMotionTime = time
        }
        trackedX = physicalX
        trackedTime = time
        addSample(time, physicalX)
    }

    private fun clearSamples() {
        tracker?.resetTracking()
        nextSample = 0
        sampleCount = 0
    }

    private fun addSample(time: Long, x: Float) {
        val samples = nativeSamples
        if (samples == null) {
            checkNotNull(tracker).addDataPoint(time, x)
            return
        }
        val slot = nextSample * VELOCITY_SAMPLE_BYTES
        samples.putLong(slot, time)
        samples.putFloat(slot + Long.SIZE_BYTES, x)
        nextSample = (nextSample + 1) % VELOCITY_HISTORY_SIZE
        sampleCount = minOf(sampleCount + 1, VELOCITY_HISTORY_SIZE)
    }

    fun resetTracking(change: PointerInputChange) {
        pointerId = null
        trackDrawerPointer(change)
    }

    fun calculateVelocity(maximumVelocity: Float = Float.MAX_VALUE): Velocity {
        require(maximumVelocity > 0f)
        val samples = nativeSamples
        val velocity = if (samples != null) {
            NativeCoreJni.nativeDrawerVelocity(samples, nextSample, sampleCount, maximumVelocity)
        } else {
            checkNotNull(tracker).calculateVelocity(maximumVelocity)
        }
        return Velocity(velocity, 0f)
    }
}
