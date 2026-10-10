package com.suixing.agent.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
private const val FX_EDGE_STROKE_PX = 14f

/**
 * The breathing edge: the same stroke, turned up until it is seen at a glance without becoming
 * a band that fights the screen for attention, and taken through a slow wave.
 *
 * The user's verdict on the first attempt was 「不太明显」, and the numbers are the whole of
 * that judgement: a 10px stroke at 0.16~0.40 alpha on a dark wallpaper is a rumour. Wider
 * stroke, higher floor, and a ceiling that is still short of solid.
 */
private const val FX_BREATH_PERIOD_MS = 2400L
private const val FX_BREATH_MIN_ALPHA = 0.22f
private const val FX_BREATH_MAX_ALPHA = 0.55f

/**
 * How long the edge stays lit after the last driving action.
 *
 * Long enough to bridge the gap between two taps of one sequence — a tap, a screen settling, the
 * next tap — and short enough that a run waiting on a model or a long shell command goes dark
 * while the user is watching it. The light means 「it is operating the phone right now」; a run in
 * flight is not the same thing, which is what the first version got wrong.
 *
 * Four seconds read as 「灭得太快」: a model turn between two taps routinely overruns it, so the
 * light went out mid-sequence and came back, which looks like a stutter rather than a state. Six
 * covers that turn without turning the light into something that lingers after the run is over.
 */
private const val FX_BREATH_HOLD_MS = 6000L

/**
 * The screen's own shape: a radius the size of a modern panel's corners.
 *
 * 52px read as 「不符合手机边缘」 on a 1080p panel whose corners are nearer 100px.
 *
 * The inset is zero because the path is now built on the display's real bounds rather than on the
 * view's measured size — see [displayBounds]. Centering the stroke on the true edge is what makes
 * the light look like it comes off the screen instead of stopping short of it; the outer half of
 * each glow pass is clipped by the window, and the inner half is the glow the user sees. An inset
 * of 10px left a visible gap between the light and the edge it is supposed to trace.
 */
private const val FX_EDGE_RADIUS_PX = 96f
private const val FX_EDGE_INSET_PX = 0f

/** The three passes of [TouchFx]'s glow: stroke width as a multiple of the edge stroke, and how
 * much of the requested alpha each one carries. Widest and faintest first, so the bright line
 * sits inside a soft band instead of on top of a hard edge. */
private val FX_GLOW_WIDTHS = floatArrayOf(4f, 2f, 1f)
private val FX_GLOW_WEIGHTS = floatArrayOf(0.22f, 0.45f, 1f)

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
 * Effects are invisible when they fail: a window the system refused to add looks exactly like a
 * run that never asked for one, and which of the two it was decides where to look next. Nothing
 * else in the app can tell them apart, so every entry writes a line here. It is a diagnostic, not
 * a log to grow forever — past the cap the oldest part is dropped.
 */
private const val FX_TRACE_FILE = "fx-trace.log"
private const val FX_TRACE_MAX_BYTES = 48 * 1024
private const val FX_TRACE_KEEP_BYTES = 24 * 1024

/** Built once, like the gesture patterns: what the user typed is not a diagnostic anyway. */
private val FX_TRACE_TEXT = Regex("""input\s+text\s+.*""")

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
 *
 * The window is hosted by the connected accessibility service when there is one
 * ([TYPE_ACCESSIBILITY_OVERLAY]) and falls back to the application context otherwise. The first
 * needs no permission at all — an enabled accessibility service is already trusted to draw over
 * other apps — and MIUI does not hide it when this app goes to the background, which is when the
 * agent is operating a different app. The fallback still needs SYSTEM_ALERT_WINDOW, and without
 * either host every entry point does nothing: a window the system will reject is not worth asking
 * for. If a host goes away while the window is up — the permission is revoked, or the service is
 * disconnected — [bindService]/[unbindService] and [release] drop the stale window so the next
 * effect rebuilds from whatever is available, rather than drawing into a view the system has
 * already destroyed.
 */
object TouchFx {

    private val main = Handler(Looper.getMainLooper())

    private var view: FxView? = null
    private var manager: WindowManager? = null
    private var lastEdgeFlashAt = 0L
    private var appContext: Context? = null

    /**
     * The connected [SuixingAccessibilityService], set from [bindService] and cleared from
     * [unbindService].
     *
     * Held separately from the `context` each entry point passes in, because the callers differ —
     * the gesture methods pass the service itself, the tools pass the application context — but
     * the window should be created from the service whenever one is connected, no matter who asked
     * for the effect. An accessibility-overlay window is the whole point of this rewrite: it needs
     * no SYSTEM_ALERT_WINDOW permission and, unlike TYPE_APPLICATION_OVERLAY, MIUI does not hide it
     * when the app goes to the background (which is exactly when the agent is operating another
     * app and the user most needs to see it). Volatile: written on the main thread from the service
     * lifecycle, read from [overlay] which may run on the main thread from a tool dispatcher.
     */
    @Volatile
    private var serviceContext: Context? = null

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

    /**
     * One thread for the trace file, so the lines stay in the order the effects happened and no
     * caller — including the accessibility service, on the main thread — ever waits on the disk.
     */
    private val traceWorker = Executors.newSingleThreadExecutor()
    private var traceFile: File? = null
    private var lastOverlayNote: String? = null

    /**
     * Called from [SuixingAccessibilityService.onServiceConnected]: from here on the effects can
     * live in an accessibility overlay window, which is the version of this that actually works on
     * MIUI. See [overlay] for why that matters.
     */
    fun bindService(service: Context) {
        serviceContext = service
        appContext = service.applicationContext
        // A window created earlier from the application context is a TYPE_APPLICATION_OVERLAY one:
        // it is up and drawing, but it is the kind MIUI hides in the background. Dropping it here
        // means the next effect rebuilds it as an accessibility overlay, so turning the service on
        // upgrades the effects without waiting for a process restart.
        main.post { release("accessibility-connected") }
    }

    /**
     * Called from [SuixingAccessibilityService.onUnbind] / [SuixingAccessibilityService.onDestroy].
     *
     * Not optional: the system destroys an accessibility-overlay window when its service
     * disconnects, without telling the app. Leaving [view] pointing at that dead window would make
     * every later effect a silent no-op — [overlay] would happily return the stale view — which is
     * precisely the 「要显示的时候不显示」 failure this rewrite is meant to remove. Clearing it lets
     * the next effect rebuild a window from whatever host is still available.
     */
    fun unbindService(context: Context) {
        serviceContext = null
        main.post { release("accessibility-disconnected") }
    }

    /**
     * Takes the window down and forgets it.
     *
     * Distinct from [detach]: this runs even while the light is breathing, because a window whose
     * host service has gone away has to come down whatever state the effect is in. Runs on the main
     * thread only, and a removal the system refuses is ignored — there is nothing to do about a
     * window that is already gone.
     */
    private fun release(reason: String) {
        val fx = view ?: return
        val windowManager = manager
        val app = appContext
        view = null
        manager = null
        // Through the captured WindowManager rather than fx.context: this runs on service
        // disconnect, when asking the service for its system services again may find a context
        // that is already being torn down. The manager that added the view can always remove it.
        runCatching { windowManager?.removeView(fx) }
        if (app != null) noteOverlay(app, "released（$reason）")
    }

    fun tap(context: Context, x: Float, y: Float) {
        trace(context, "entry=tap x=$x y=$y")
        noteDriving(context)
        post(context) { fx ->
            flashEdge(fx)
            fx.addRing(x, y, holdMs = 0L, delayMs = 0L)
        }
    }

    fun longPress(context: Context, x: Float, y: Float) {
        trace(context, "entry=longPress x=$x y=$y")
        noteDriving(context)
        post(context) { fx ->
            flashEdge(fx)
            fx.addRing(x, y, holdMs = FX_LONG_PRESS_HOLD_MS, delayMs = 0L)
        }
    }

    fun swipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) {
        trace(context, "entry=swipe x1=$x1 y1=$y1 x2=$x2 y2=$y2")
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
        trace(context, "entry=handoff")
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
        trace(context, "entry=noteDriving runs=${running.size} ids=${running.joinToString(",")}")
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
            // An id that ended stops counting as driving.
            drivingIds = drivingIds - departed
            // The light follows the actions, not the run: 「agent 没有操作的时候仍旧显示」 was the
            // complaint, and a run spends most of its life waiting on a model or a tool. The hold
            // period has to outlast one of those turns without lingering after the run ends — see
            // FX_BREATH_HOLD_MS for the numbers and why they moved off four seconds.
            val lit = app != null && now - lastDrivingAt < FX_BREATH_HOLD_MS
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
     * Starting an activity from the background is allowed here only when the app holds
     * SYSTEM_ALERT_WINDOW, which is Android 10's documented exemption for it. On the
     * accessibility-overlay host this app may well not have that permission — not needing it for
     * the effects is the point — in which case the system refuses the start and the user simply
     * stays where they are. Being returned to the app is a courtesy, not a result, so that is
     * acceptable rather than a reason to ask for a permission the effects do not need.
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

    /**
     * One line for a call that is not an effect: a shell command that was inspected for gestures,
     * or a UI tool that decided to drive the screen. Written whether or not anything matched —
     * "nothing matched" and "it matched but nothing was drawn" have to be told apart from the file
     * alone, and the command itself is what makes that possible.
     *
     * Typed text is masked: a trace that records what the user typed would be a keylogger.
     */
    fun traceCall(context: Context, tool: String, detail: String, matched: List<String>) {
        val masked = detail.replace(FX_TRACE_TEXT, "input text ***").replace('\n', '⏎')
        trace(
            context,
            "entry=call tool=$tool matched=${matched.joinToString("+").ifEmpty { "无" }} detail=$masked",
        )
    }

    /**
     * Appends one line to `filesDir/fx-trace.log`.
     *
     * The caller is read off the stack rather than passed in: a diagnostic that every call site has
     * to remember to identify itself in is one that rots the first time a call site is added. The
     * write happens on [traceWorker] and every failure is swallowed — a trace must never be the
     * reason an effect does not happen.
     */
    private fun trace(context: Context, line: String) {
        val app = context.applicationContext
        val overlay = view?.let { "up" } ?: lastOverlayNote ?: "none"
        val entry = "${stamp()} $line source=${caller()} overlay=$overlay " +
            "breathing=${if (view?.isBreathing == true) "on" else "off"} " +
            "runs=${Core.engine.runsFlow.value.size}"
        traceWorker.execute {
            runCatching {
                val file = traceFile ?: File(app.filesDir, FX_TRACE_FILE).also { traceFile = it }
                if (file.length() > FX_TRACE_MAX_BYTES) {
                    val tail = file.readText().takeLast(FX_TRACE_KEEP_BYTES)
                    file.writeText("— 已截断，仅保留最近一段 —\n$tail")
                }
                file.appendText("$entry\n")
            }
        }
    }

    private fun stamp(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** The first frame outside this file: which tool asked for the effect, and where. */
    private fun caller(): String {
        val frame = Thread.currentThread().stackTrace.firstOrNull {
            !it.className.startsWith("com.suixing.agent.service.TouchFx") &&
                !it.className.startsWith("java.lang.Thread") &&
                !it.className.startsWith("java.util.concurrent")
        } ?: return "?"
        val file = frame.fileName ?: "?"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}($file:${frame.lineNumber})"
    }

    /** Logs a change of overlay state, and only a change: a tick failing every second writes once. */
    private fun noteOverlay(context: Context, note: String) {
        if (lastOverlayNote == note) return
        lastOverlayNote = note
        trace(context, "overlay=$note")
    }

    private fun flashEdge(fx: FxView) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeFlashAt < FX_EDGE_THROTTLE_MS) return
        lastEdgeFlashAt = now
        fx.flashEdge()
    }

    /**
     * The window the effects are drawn in, created on demand.
     *
     * Two hosts, in order of preference:
     *
     * 1. **Accessibility overlay** — [TYPE_ACCESSIBILITY_OVERLAY], added from the connected
     *    [SuixingAccessibilityService]'s own context. This needs no SYSTEM_ALERT_WINDOW permission
     *    at all (the accessibility service is already a system-trusted host, so the window is
     *    granted by virtue of the service being enabled), and — the reason it exists here — MIUI
     *    and its kin do **not** hide it when the app leaves the foreground. TYPE_APPLICATION_OVERLAY
     *    is hidden, which is why the effects vanished exactly when the agent was operating another
     *    app unless the user granted 「后台弹出界面」. This is the path that makes the effects
     *    work without that setting.
     * 2. **Application overlay** — [TYPE_APPLICATION_OVERLAY] from the application context, which
     *    needs [Settings.canDrawOverlays]. Kept as the fallback so effects still exist when
     *    gestures arrive over the privileged shell route with the accessibility service off; it is
     *    visible while the app is in front, which is better than nothing.
     *
     * Both use the same flags, and the same [FxView], so the drawing is identical — only the host
     * differs. A host that refuses the window is traced rather than swallowed: a window the system
     * rejected and one that was never asked for are the two things a 「没有特效」 report has to be
     * told apart from.
     */
    private fun overlay(context: Context): FxView? {
        view?.let { return it }
        val host = serviceContext ?: context.applicationContext
        val accessibility = serviceContext != null
        if (!accessibility && !Settings.canDrawOverlays(context)) {
            noteOverlay(context, "denied（没有悬浮窗权限，且无障碍服务未连接）")
            return null
        }
        val windowManager = host.getSystemService(WindowManager::class.java)
        if (windowManager == null) {
            noteOverlay(context, "failed（拿不到 WindowManager）")
            return null
        }
        val fx = FxView(host, displayBounds(host)) { detach() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (accessibility) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            },
            // Never focusable and never touchable: the whole point is that the phone keeps
            // working normally underneath while the effect is drawn on top of it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                // In screen, not merely no-limits: without it the window is laid out inside the
                // system bars, so the glow started below the status bar and stopped above the
                // gesture bar — 「边缘也不对，没有包括状态栏和手势条区域」.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // Swallowed before, which made a refused window indistinguishable from one never asked
        // for; the reason is the single most useful line this file can hold.
        val added = runCatching { windowManager.addView(fx, params) }
        val error = added.exceptionOrNull()
        if (error != null) {
            noteOverlay(
                context,
                "failed（${if (accessibility) "accessibility" else "application"}-overlay addView: " +
                    "${error.javaClass.simpleName}: ${error.message}）",
            )
            return null
        }
        noteOverlay(context, "created（${if (accessibility) "accessibility" else "application"}-overlay）")
        manager = windowManager
        view = fx
        return fx
    }

    /**
     * The display's true bounds in px, which is what the edge light is drawn on.
     *
     * Not the view's measured size: an accessibility-overlay window is full-screen but its measured
     * size lands a tick after the first draw, and an application-overlay one is laid out inside the
     * system bars — either way the rounded rectangle came up short of the panel's edge, which is
     * the 「没有完全覆盖好屏幕边缘」 report. The display bounds include the status bar and the
     * gesture bar, so the light traces the whole screen from the first frame.
     *
     * [WindowManager.getCurrentWindowMetrics] from API 30; below that `Display.getRealSize`, which
     * is the pre-R name for the same physical extent. An empty result — no display attached, which
     * happens on a service that is being torn down — falls through to the app's own metrics rather
     * than drawing nothing.
     */
    @Suppress("DEPRECATION") // defaultDisplay/getRealSize: minSdk 26, and this is the pre-R path.
    private fun displayBounds(context: Context): Rect {
        val manager = context.getSystemService(WindowManager::class.java)
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = runCatching { manager.currentWindowMetrics.bounds }.getOrNull()
                if (bounds != null && !bounds.isEmpty) return bounds
            } else {
                val size = android.graphics.Point()
                runCatching { manager.defaultDisplay.getRealSize(size) }
                if (size.x > 0 && size.y > 0) return Rect(0, 0, size.x, size.y)
            }
        }
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
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
 *
 * [screenBounds] is the display's real extent, captured by [TouchFx.overlay] when the window was
 * created, and is what the edge light is drawn on rather than this view's own measured size — see
 * [TouchFx.displayBounds].
 */
private class FxView(
    context: Context,
    private val screenBounds: Rect,
    private val onIdle: () -> Unit,
) : View(context) {

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
        drawGlow(canvas, edgeShape(), 0.7f * (1f - progress))
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
        drawGlow(canvas, edgeShape(), alpha)
    }

    /**
     * One edge, drawn as light rather than as a line.
     *
     * Three passes from wide and faint to narrow and bright: a single stroke reads as a rule drawn
     * around the screen — the user's verdict on the first version was 「不太明显」 and then, when it
     * was made thicker, that it still looked like a box. Stacking strokes is what turns it into a
     * glow that falls off inward, which is the effect they asked for.
     */
    private fun drawGlow(canvas: Canvas, path: Path, alpha: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = FX_COLOR_BRAND
        paint.strokeCap = Paint.Cap.ROUND
        for (pass in FX_GLOW_WIDTHS.indices) {
            paint.strokeWidth = FX_EDGE_STROKE_PX * FX_GLOW_WIDTHS[pass]
            paint.alpha = (alpha * FX_GLOW_WEIGHTS[pass] * 255f).toInt().coerceIn(0, 255)
            if (paint.alpha <= 0) continue
            canvas.drawPath(path, paint)
        }
    }

    /**
     * The outline both edge effects are drawn on: the screen's own shape rather than a rectangle
     * around it. A phone's corners are round, so square corners read as a box laid on the screen
     * instead of a light coming off its edges — which is exactly what the user reported seeing.
     *
     * Sized from [screenBounds] rather than [width]/[height]: the view's measured size is not the
     * screen. An accessibility-overlay window is not measured until after its first draw, and an
     * application-overlay one is laid out inside the system bars, so both came up short of the
     * panel's edge — 「没有完全覆盖好屏幕边缘」. The display's real bounds reach the status bar and
     * the gesture bar, and with the inset at zero the stroke is centred on the true edge: its outer
     * half is clipped by the window and its inner half is the glow that gets seen.
     */
    private fun edgeShape(): Path {
        val inset = FX_EDGE_INSET_PX
        edgeRect.set(
            screenBounds.left + inset,
            screenBounds.top + inset,
            screenBounds.right - inset,
            screenBounds.bottom - inset,
        )
        edgePath.reset()
        edgePath.addRoundRect(edgeRect, FX_EDGE_RADIUS_PX, FX_EDGE_RADIUS_PX, Path.Direction.CW)
        return edgePath
    }
}
