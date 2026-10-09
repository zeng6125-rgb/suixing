package com.suixing.agent.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.suixing.agent.MainActivity
import com.suixing.agent.core.Core
import kotlin.math.PI
import kotlin.math.cos

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
 * The breathing edge: the same stroke, turned up until it is seen at a glance without becoming
 * a band that fights the screen for attention, and taken through a slow wave.
 */
private const val FX_BREATH_PERIOD_MS = 2400L
private const val FX_BREATH_MIN_ALPHA = 0.16f
private const val FX_BREATH_MAX_ALPHA = 0.40f

/**
 * The screen's own shape: a radius the size of a modern panel's corners, and a few px in from
 * the edge so a 10px stroke is neither half-eaten by those corners nor clipped by the window.
 */
private const val FX_EDGE_RADIUS_PX = 52f
private const val FX_EDGE_INSET_PX = 8f

/**
 * The light is a state the user reads at a glance, not an animation to admire: 25fps while it
 * is on, and one poll per second to notice the run ending. Both stop the moment it goes out.
 */
private const val FX_BREATH_FRAME_MS = 40L
private const val FX_BREATH_TICK_MS = 1000L

/**
 * How long the light survives the last driving action.
 *
 * A run hands over to the next one across a boundary the ticker only notices on its next tick,
 * and the agent often thinks for a few seconds between actions — without this the light blinks
 * out in exactly the moments the user is watching it, which is what they reported. Five seconds
 * covers a tick plus the pause before the next action.
 */
private const val FX_DRIVING_GRACE_MS = 5_000L

/**
 * A background job can operate the phone with no chat run in flight, and then there is no run
 * whose ending could put the light out. That path is bounded by time instead: the light stays
 * for this long after the last action, so a job that dies mid-way still lets go of it.
 */
private const val FX_JOB_DRIVING_ID = "job"
private const val FX_JOB_WINDOW_MS = 20_000L

/**
 * Shows the user what the agent is doing to their screen.
 *
 * The request was one sentence — 「要有点击特效和边缘闪动，这样我才知道在操控手机」 — and that
 * is the whole job: a ring where it touches, a line where it drags, and a flash around
 * the edge when a gesture starts. An agent that taps silently is indistinguishable from
 * one that is stuck, which is exactly the confusion this removes.
 *
 * The flash says "something just happened"; the faint breath of the same green that [noteDriving]
 * switches on says "it is still happening". Only driving earns the breath — a run that reads the
 * screen or only answers in words shows nothing, because the user asked to see their phone being
 * operated, not to be told that someone is looking at it.
 *
 * Nothing here steers anything: the accessibility service calls it after it has decided
 * to dispatch a gesture, so the drawing can never claim something the phone did not do.
 * Without the overlay permission every entry point does nothing — a window the system
 * will reject is not worth asking for. If the permission is revoked while the window is up, the
 * system takes it away without calling anything back: no effect is drawn again in this process
 * until it restarts. That is left as it is rather than papered over with a state machine.
 */
object TouchFx {

    private val main = Handler(Looper.getMainLooper())

    private var view: FxView? = null
    private var manager: WindowManager? = null
    private var lastEdgeFlashAt = 0L
    private var appContext: Context? = null

    /**
     * The runs that were in flight when the agent last drove the screen.
     *
     * Volatile because it is written from the tool dispatcher and read from the ticker on the
     * main thread; a tick that misses an update simply lights the edge a second later.
     */
    @Volatile
    private var drivingIds: Set<String> = emptySet()

    /** When the last driving action happened, for the grace window. Volatile as above. */
    @Volatile
    private var lastDrivingAt = 0L

    /**
     * When a driving action happened with no run in flight — see [FX_JOB_DRIVING_ID].
     *
     * Volatile for the same reason as [drivingIds].
     */
    @Volatile
    private var jobDrivingUntil = 0L

    /** The run set as the previous tick saw it, so a tick can tell which run just ended. */
    private var previousIds: Set<String> = emptySet()

    fun tap(context: Context, x: Float, y: Float) {
        noteDriving(context)
        post(context) { fx ->
            flashEdge(fx)
            fx.addRing(x, y, holdMs = 0L, delayMs = 0L)
        }
    }

    fun longPress(context: Context, x: Float, y: Float) {
        noteDriving(context)
        post(context) { fx ->
            flashEdge(fx)
            fx.addRing(x, y, holdMs = FX_LONG_PRESS_HOLD_MS, delayMs = 0L)
        }
    }

    fun swipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) {
        noteDriving(context)
        post(context) { fx ->
            flashEdge(fx)
            fx.addRing(x1, y1, holdMs = 0L, delayMs = 0L)
            // The far ring arrives with the finger rather than before it, so the pair reads as
            // one movement instead of two unrelated taps.
            fx.addRing(x2, y2, holdMs = 0L, delayMs = (FX_SWIPE_DURATION_MS * 55 / 100))
            fx.addTrail(x1, y1, x2, y2)
        }
    }

    /**
     * The screen is being handed to another app — `open_app`, `am start`, `monkey -p`.
     *
     * The edge flash on its own, with no ring: unlike [tap] and [swipe] there is no touch
     * point to draw, and a ring would have to invent a spot on a screen the agent never
     * touched. What is left is the part of the signal that is still true — something the
     * agent did just changed what the user is looking at. Shares [FX_EDGE_THROTTLE_MS]
     * with the gestures, so a launch straight after a tap does not flash twice.
     */
    fun handoff(context: Context) {
        noteDriving(context)
        post(context) { fx ->
            flashEdge(fx)
        }
    }

    /**
     * The agent has just driven the interface — a tap, a swipe, a launch, a typed line.
     *
     * The breathing edge is not "a run is going", it is "this run is operating the phone", and
     * this is where that difference is recorded. Every entry point above calls it, so anything
     * that draws a ring or a flash counts as driving, while reading the screen — `ui_dump`, a
     * screenshot, a file read — deliberately does not, and neither does a run that only talks.
     * The run ids are captured here rather than passed in from the tools: the run that drove is
     * the one whose ending has to put the light out, and the engine already knows which those are.
     *
     * With no run in flight — a background job operating the phone — there is nothing to watch
     * for an ending, so the light follows the actions themselves and then a fixed window.
     *
     * Ids are added, never replaced: a run that drove keeps the light for as long as it lives,
     * even if the next action is taken while a different set of runs happens to be in flight.
     * Ids that have ended are dropped by the ticker, so a later run in the same conversation
     * starts from nothing rather than inheriting the light from the one before it.
     */
    fun noteDriving(context: Context) {
        appContext = context.applicationContext
        val running = Core.engine.runsFlow.value.keys
        lastDrivingAt = SystemClock.uptimeMillis()
        if (running.isEmpty()) {
            jobDrivingUntil = lastDrivingAt + FX_JOB_WINDOW_MS
            drivingIds = drivingIds + FX_JOB_DRIVING_ID
        } else {
            jobDrivingUntil = 0L
            drivingIds = (drivingIds - FX_JOB_DRIVING_ID) + running
        }
        // Also (re)starts the ticker: the first tick is immediate, so the light comes on with the
        // action instead of up to a second after it.
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    /**
     * The light mirrors a state that lives in the engine, so it is polled rather than pushed.
     *
     * Nothing has to switch it off, which is the point: a run that dies without telling anyone
     * cannot leave the light on, because the next tick sees that its id is gone. The ticker
     * stops itself once the light is out, so no timer runs while the agent is idle.
     */
    private val ticker = object : Runnable {
        override fun run() {
            val app = appContext
            val now = SystemClock.uptimeMillis()
            val running = Core.engine.runsFlow.value.keys
            val departed = previousIds - running
            previousIds = running.toSet()
            // A run that was operating the phone has just ended, and no other driving run is left:
            // that is the moment the user asked to be handed their phone back, so it happens on
            // this tick rather than after the light's grace period. A run that merely shared the
            // screen with the one that drove does not earn the trip on its own.
            val drivingLeft = departed.any { it in drivingIds }
            if (drivingLeft && running.none { it in drivingIds }) returnToApp()
            // An id that ended stops counting as driving. The grace window below — not a stale id —
            // is what carries the light across the boundary, so a run that only chats afterwards
            // does not inherit it. Racing an action can only drop an id, which the grace covers.
            drivingIds = drivingIds - departed
            val lit = app != null &&
                (running.any { it in drivingIds } ||
                    now - lastDrivingAt < FX_DRIVING_GRACE_MS ||
                    (FX_JOB_DRIVING_ID in drivingIds && now < jobDrivingUntil))
            if (lit && app != null) {
                overlay(app)?.setBreathing(true)
                main.postDelayed(this, FX_BREATH_TICK_MS)
            } else {
                drivingIds = emptySet()
                jobDrivingUntil = 0L
                // Draws one last frame without the light, which is what lets the window go.
                view?.setBreathing(false)
                main.removeCallbacks(this)
            }
        }
    }

    /**
     * Brings Suixing back to the front once the agent is done operating the phone.
     *
     * Only a run that drove the screen earns this: a run that answered in words must not pull the
     * user out of whatever they were reading. Nothing happens when the app is already in front —
     * the ticker runs on the main thread, so the lifecycle answer is the live one — and a start
     * the system refuses is ignored, since being returned to the app is a courtesy, not a result.
     * Starting an activity from the background is allowed here because the overlay permission the
     * effects already need (SYSTEM_ALERT_WINDOW) is Android 10's documented exemption for it.
     */
    private fun returnToApp() {
        val app = appContext ?: return
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) return
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
        // The breathing light outlives any single effect, so the window stays while it is on:
        // taking it down here would cut the glow off mid-breath and force a rebuild a second
        // later. It is not a leak — the ticker switches the light off, this runs again, and
        // then the window goes.
        if (fx.isBreathing) return
        view = null
        runCatching { manager?.removeView(fx) }
        manager = null
    }
}

/**
 * The full-screen, untouchable canvas the effects are drawn on.
 *
 * It redraws itself while something is animating, or while the breathing edge is on — the latter
 * at a fraction of the frame rate, since a glow that moves this slowly does not need one — and
 * reports idle the moment there is nothing left, which is what lets the window be taken down.
 */
private class FxView(context: Context, private val onIdle: () -> Unit) : View(context) {

    private class Ring(val x: Float, val y: Float, val startAt: Long, val holdMs: Long, val delayMs: Long)

    private class Trail(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val startAt: Long)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rings = mutableListOf<Ring>()
    private val trails = mutableListOf<Trail>()
    private val edgeRect = RectF()
    private val edgePath = Path()
    private var edgeStart = 0L
    private var idleReported = false

    /** The breathing edge, which is a state rather than an effect with a duration. */
    private var breathing = false
    private var breathStart = 0L

    val isBreathing: Boolean get() = breathing

    /**
     * Switched by the ticker in [TouchFx], not by a gesture: the light belongs to the run, and
     * gestures only come and go inside it. Turning it on does not restart the wave from the
     * top — it starts at its dimmest either way — so a burst of taps does not make it flicker.
     */
    fun setBreathing(on: Boolean) {
        if (breathing == on) return
        breathing = on
        breathStart = SystemClock.uptimeMillis()
        idleReported = false
        invalidate()
    }

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
        // Under the flash, so an action still reads as brighter than the state it happened in.
        if (breathing) drawBreath(canvas, now)
        if (edgeElapsed < FX_EDGE_DURATION_MS) drawEdge(canvas, edgeElapsed)

        val animating = rings.isNotEmpty() || trails.isNotEmpty() || edgeElapsed < FX_EDGE_DURATION_MS
        when {
            // A gesture is a burst of frames; the breath is a slow glow and 25fps is plenty for
            // it, so the two run at their own rates instead of the window spinning at 60fps.
            animating -> postInvalidateOnAnimation()
            breathing -> postInvalidateDelayed(FX_BREATH_FRAME_MS)
            !idleReported -> {
                idleReported = true
                onIdle()
            }
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
        canvas.drawPath(edgeShape(), paint)
    }

    /**
     * The same edge as [drawEdge], held at a whisper: the phone is being operated right now, and
     * that is worth saying continuously rather than only at the moment of each touch.
     *
     * A cosine rather than a sawtooth, so the turnarounds are soft and the wave starts at its
     * dimmest — switching the light on never jumps the edge brighter than the flash beside it.
     */
    private fun drawBreath(canvas: Canvas, now: Long) {
        val phase = ((now - breathStart) % FX_BREATH_PERIOD_MS).toFloat() / FX_BREATH_PERIOD_MS
        val wave = (1f - cos(phase * 2f * PI.toFloat())) / 2f
        val alpha = FX_BREATH_MIN_ALPHA + (FX_BREATH_MAX_ALPHA - FX_BREATH_MIN_ALPHA) * wave
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = FX_EDGE_STROKE_PX
        paint.color = FX_COLOR_BRAND
        paint.alpha = (alpha * 255).toInt()
        canvas.drawPath(edgeShape(), paint)
    }

    /**
     * The outline both edge effects are drawn on: the screen's own shape rather than a rectangle
     * around it. A phone's corners are round, so square corners read as a box laid on the screen
     * instead of a light coming off its edges — which is exactly what the user reported seeing.
     */
    private fun edgeShape(): Path {
        val inset = FX_EDGE_INSET_PX
        edgeRect.set(inset, inset, width - inset, height - inset)
        edgePath.reset()
        edgePath.addRoundRect(edgeRect, FX_EDGE_RADIUS_PX, FX_EDGE_RADIUS_PX, Path.Direction.CW)
        return edgePath
    }
}
