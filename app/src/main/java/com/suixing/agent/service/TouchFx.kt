package com.suixing.agent.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/** px, not dp: the effects sit next to touches, which the system reports in px. */
private const val FX_RING_MAX_RADIUS = 96f
private const val FX_RING_HOLD_RADIUS = 24f
private const val FX_RING_DURATION_MS = 260L
private const val FX_LONG_PRESS_HOLD_MS = 600L
private const val FX_SWIPE_DURATION_MS = 320L
private const val FX_EDGE_DURATION_MS = 350L

/**
 * A gesture lasts a fraction of a second, so without this a burst of taps leaves the edge
 * lit continuously and the flash stops meaning "something just happened".
 */
private const val FX_EDGE_THROTTLE_MS = 800L

private const val FX_COLOR_BRAND = 0xFF1F6B4C.toInt()
private const val FX_RING_STROKE_PX = 6f
private const val FX_TRAIL_STROKE_PX = 4f
private const val FX_EDGE_STROKE_PX = 10f

/**
 * Shows the user what the agent is doing to their screen.
 *
 * The request was one sentence — 「要有点击特效和边缘闪动，这样我才知道在操控手机」 — and that
 * is the whole job: a ring where it touches, a line where it drags, and a flash around
 * the edge when a gesture starts. An agent that taps silently is indistinguishable from
 * one that is stuck, which is exactly the confusion this removes.
 *
 * Nothing here steers anything: the accessibility service calls it after it has decided
 * to dispatch a gesture, so the drawing can never claim something the phone did not do.
 * Without the overlay permission every entry point does nothing — a window the system
 * will reject is not worth asking for.
 */
object TouchFx {

    private val main = Handler(Looper.getMainLooper())

    private var view: FxView? = null
    private var manager: WindowManager? = null
    private var lastEdgeFlashAt = 0L

    fun tap(context: Context, x: Float, y: Float) = post(context) { fx ->
        flashEdge(fx)
        fx.addRing(x, y, holdMs = 0L, delayMs = 0L)
    }

    fun longPress(context: Context, x: Float, y: Float) = post(context) { fx ->
        flashEdge(fx)
        fx.addRing(x, y, holdMs = FX_LONG_PRESS_HOLD_MS, delayMs = 0L)
    }

    fun swipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) = post(context) { fx ->
        flashEdge(fx)
        fx.addRing(x1, y1, holdMs = 0L, delayMs = 0L)
        // The far ring arrives with the finger rather than before it, so the pair reads as
        // one movement instead of two unrelated taps.
        fx.addRing(x2, y2, holdMs = 0L, delayMs = (FX_SWIPE_DURATION_MS * 55 / 100))
        fx.addTrail(x1, y1, x2, y2)
    }

    /** Gestures come off the tool dispatcher, but windows and drawing are main-thread only. */
    private fun post(context: Context, block: (FxView) -> Unit) {
        val app = context.applicationContext
        main.post {
            val fx = overlay(app) ?: return@post
            block(fx)
        }
    }

    private fun flashEdge(fx: FxView) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeFlashAt < FX_EDGE_THROTTLE_MS) return
        lastEdgeFlashAt = now
        fx.flashEdge()
    }

    private fun overlay(context: Context): FxView? {
        view?.let { return it }
        if (!Settings.canDrawOverlays(context)) return null
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        val fx = FxView(context) { detach() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never focusable and never touchable: the whole point is that the phone keeps
            // working normally underneath while the effect is drawn on top of it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (!runCatching { windowManager.addView(fx, params) }.isSuccess) return null
        manager = windowManager
        view = fx
        return fx
    }

    /** Added per gesture and removed when the last effect ends, so nothing is left over. */
    private fun detach() {
        val fx = view ?: return
        view = null
        runCatching { manager?.removeView(fx) }
        manager = null
    }
}

/**
 * The full-screen, untouchable canvas the effects are drawn on.
 *
 * It redraws itself only while something is animating, and reports idle the moment there
 * is nothing left, which is what lets the window be taken down again.
 */
private class FxView(context: Context, private val onIdle: () -> Unit) : View(context) {

    private class Ring(val x: Float, val y: Float, val startAt: Long, val holdMs: Long, val delayMs: Long)

    private class Trail(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val startAt: Long)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rings = mutableListOf<Ring>()
    private val trails = mutableListOf<Trail>()
    private val edgeRect = RectF()
    private var edgeStart = 0L
    private var idleReported = false

    fun addRing(x: Float, y: Float, holdMs: Long, delayMs: Long) {
        idleReported = false
        rings += Ring(x, y, SystemClock.uptimeMillis(), holdMs, delayMs)
        invalidate()
    }

    fun addTrail(x1: Float, y1: Float, x2: Float, y2: Float) {
        idleReported = false
        trails += Trail(x1, y1, x2, y2, SystemClock.uptimeMillis())
        invalidate()
    }

    fun flashEdge() {
        idleReported = false
        edgeStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()

        rings.removeAll { now - it.startAt > it.delayMs + it.holdMs + FX_RING_DURATION_MS }
        for (ring in rings) drawRing(canvas, ring, now)

        trails.removeAll { now - it.startAt > FX_SWIPE_DURATION_MS }
        for (trail in trails) drawTrail(canvas, trail, now)

        val edgeElapsed = now - edgeStart
        if (edgeElapsed < FX_EDGE_DURATION_MS) drawEdge(canvas, edgeElapsed)

        if (rings.isEmpty() && trails.isEmpty() && edgeElapsed >= FX_EDGE_DURATION_MS) {
            if (!idleReported) {
                idleReported = true
                onIdle()
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun drawRing(canvas: Canvas, ring: Ring, now: Long) {
        val elapsed = now - ring.startAt - ring.delayMs
        if (elapsed < 0) return
        val growing = elapsed > ring.holdMs
        val progress = if (growing) {
            ((elapsed - ring.holdMs).toFloat() / FX_RING_DURATION_MS).coerceIn(0f, 1f)
        } else {
            0f
        }
        val radius = if (growing) {
            FX_RING_HOLD_RADIUS + (FX_RING_MAX_RADIUS - FX_RING_HOLD_RADIUS) * progress
        } else {
            FX_RING_HOLD_RADIUS
        }
        // Held-down and expanding are different messages, so only the second one fades: a
        // long press that faded while the finger was still down would read as a tap.
        val alpha = if (growing) 0.5f * (1f - progress) else 0.5f
        if (alpha <= 0.01f) return

        paint.style = Paint.Style.FILL
        paint.color = FX_COLOR_BRAND
        paint.alpha = (alpha * 0.2f * 255).toInt()
        canvas.drawCircle(ring.x, ring.y, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = FX_RING_STROKE_PX
        paint.color = FX_COLOR_BRAND
        paint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(ring.x, ring.y, radius, paint)
    }

    private fun drawTrail(canvas: Canvas, trail: Trail, now: Long) {
        val progress = ((now - trail.startAt).toFloat() / FX_SWIPE_DURATION_MS).coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = FX_TRAIL_STROKE_PX
        paint.color = FX_COLOR_BRAND
        paint.alpha = (0.4f * (1f - progress * 0.4f) * 255).toInt()
        canvas.drawLine(
            trail.x1,
            trail.y1,
            trail.x1 + (trail.x2 - trail.x1) * progress,
            trail.y1 + (trail.y2 - trail.y1) * progress,
            paint,
        )
    }

    private fun drawEdge(canvas: Canvas, elapsed: Long) {
        val progress = (elapsed.toFloat() / FX_EDGE_DURATION_MS).coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = FX_EDGE_STROKE_PX
        paint.color = FX_COLOR_BRAND
        paint.alpha = (0.6f * (1f - progress) * 255).toInt()
        val inset = FX_EDGE_STROKE_PX / 2f
        edgeRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRect(edgeRect, paint)
    }
}
