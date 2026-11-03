package com.suixing.agent.service

import android.view.View
import android.view.animation.DecelerateInterpolator

/** Press feedback belongs to the visual disc, never the window or its touch bounds. */
internal class FloatBallTouchFeedback(private val visual: View) {
    var active = false
        private set
    var dragging = false
        private set
    private val easing = DecelerateInterpolator()

    fun press() {
        active = true
        dragging = false
        animatePressed(true)
    }

    fun drag() {
        if (!active || dragging) return
        dragging = true
        animatePressed(false)
    }

    /** A tap is delivered now, not from an animation's end callback. */
    fun release(): Boolean {
        val tapped = active && !dragging
        if (tapped) animatePressed(false)
        active = false
        dragging = false
        return tapped
    }

    fun cancel() {
        release()
    }

    fun reset() {
        active = false
        dragging = false
        visual.animate().cancel()
        visual.isPressed = false
        visual.scaleX = 1f
        visual.scaleY = 1f
    }

    private fun animatePressed(pressed: Boolean) {
        visual.isPressed = pressed
        val scale = if (pressed) 0.96f else 1f
        visual.animate().cancel()
        visual.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(if (pressed) 90L else 160L)
            .setInterpolator(easing)
            .start()
    }
}
