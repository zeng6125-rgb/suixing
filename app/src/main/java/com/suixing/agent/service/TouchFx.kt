package com.suixing.agent.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
import kotlin.math.pow

/**
 * dp, not px: the effects sit next to touches, which the system reports in px, but their sizes
 * belong to the panel's own scale. These were raw pixels — a 96px ring is 48dp on a 2x phone and
 * 24dp on a 4x one — so the same gesture read as a different gesture from one device to the next.
 */
private const val FX_RING_MAX_RADIUS_DP = 32f
private const val FX_RING_HOLD_RADIUS_DP = 8f
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
private const val FX_RING_STROKE_DP = 2f
private const val FX_TRAIL_STROKE_DP = 1.5f

/**
 * The edge light is a halo that fades inward, and these are its numbers — Operit's, which is the
 * app the user pointed at.
 *
 * It used to be a stroke: one line of a fixed width laid around the screen. A stroke is a rule
 * drawn on the panel, and that is how it read (「像一个框」). What the user asked for is light
 * coming off the edge, so the band is filled from the screen's own edge inward over
 * [FX_HALO_WIDTH_FRACTION] of the short side, in [FX_HALO_BANDS] steps whose alpha follows
 * `(1 - t)^1.55`: brightest at the edge, gone by the time the fade ends.
 *
 * [FX_HALO_BANDS] is the one number to touch by eye. 72 is what Operit uses and 16 is the cheap
 * end of the same curve, so if the steps read as rings rather than as a fade, raise it.
 */
private const val FX_HALO_WIDTH_FRACTION = 0.046f
private const val FX_HALO_BANDS = 16
private const val FX_HALO_FALLOFF = 1.55f

/**
 * Ceiling for the band above, in dp.
 *
 * The fraction alone scales with the short side, which is right on a phone and wrong on anything
 * wider: a tablet's short side hands the band a width no phone ever showed, and the light stops
 * reading as a glow and starts reading as a bar. The cap is the widest band worth drawing.
 */
private const val FX_HALO_MAX_WIDTH_DP = 24f

/**
 * 彩虹环的颜色：绕屏幕中心一圈的角度渐变，**首尾同色**，所以 0° 那里没有接缝。
 *
 * 红 → 橙 → 黄 → 绿 → 青 → 蓝 → 品红 → 红，八段等分（`SweepGradient` 的 positions 传 null）。
 * 颜色不透明，亮度由 `Paint.alpha` 调（shader 的输出会被 paint 的 alpha 调制，所以呼吸与闪光
 * 的 alpha 仍然有效）。
 */
private val FX_RAINBOW = intArrayOf(
    0xFFFF0000.toInt(),
    0xFFFF7F00.toInt(),
    0xFFFFFF00.toInt(),
    0xFF00FF00.toInt(),
    0xFF00FFFF.toInt(),
    0xFF0000FF.toInt(),
    0xFFFF00FF.toInt(),
    0xFFFF0000.toInt(),
)

/**
 * 色相转一圈的时间：2s，即 0.18°/ms。
 *
 * 取模之后再算角度，不是图省事：`uptimeMillis` 开机一天就是 8 位数，直接乘 0.18 会让结果落进
 * float 精度不够的区间（间隔 2°），色相就会一跳一跳。
 */
private const val FX_HUE_PERIOD_MS = 2000L

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
 * How long the light fades out after the driving run has left.
 *
 * The light is on while a run that drove is still alive ([shouldLight]); this number is only the
 * fade of that one episode. A run can leave long after its last tap — it reads the screen, it
 * thinks, it answers — and going dark on the very frame the run disappears reads as a glitch
 * rather than as a state ending.
 *
 * It can never light the edge on its own: the fade counts from the departure ([drivingLeftAt]), and
 * a departure only exists if a run had switched the light on. 6s 曾经是「距上次动作多久」，那正是
 * 用户报的「不在执行任务也会显示」—— 一次驱动动作之后光自己亮了 6 秒，跟有没有 run 在跑无关。
 */
private const val FX_BREATH_HOLD_MS = 6000L

/**
 * The screen's own shape: a radius the size of a modern panel's corners.
 *
 * 52px read as 「不符合手机边缘」 on a 1080p panel whose corners are nearer 100px. It is only the
 * fallback now — [screenCornerRadius] asks the display for its real corner on API 31+ and lands
 * here when the platform does not report one.
 *
 * The inset is zero because the path is the display's real bounds rather than the view's measured
 * size — see [displayBounds] — and because the halo is drawn inward from that edge: there is no
 * outer half left to clip, so the light starts exactly on the panel's edge instead of stopping
 * short of it. An inset of 10px left a visible gap between the light and the edge it is supposed
 * to trace.
 */
private const val FX_EDGE_RADIUS_PX = 96f
private const val FX_EDGE_INSET_PX = 0f

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
 * Effects are invisible when they fail: a window the system refused to add looks exactly like a
 * run that never asked for one, and which of the two it was decides where to look next. Nothing
 * else in the app can tell them apart, so every entry writes a line here. It is a diagnostic, not
 * a log to grow forever — past the cap the oldest part is dropped.
 */
private const val FX_TRACE_FILE = "fx-trace.log"

/**
 * The trace is bounded: past [FX_TRACE_MAX_BYTES] it is rewritten with its last [FX_TRACE_KEEP_BYTES].
 *
 * Both numbers are bytes. The tail is cut in bytes and then decoded, because the two units used to be
 * mixed: `takeLast` on a String counts *characters*, so a log with Chinese in it kept up to three
 * times what the name promised.
 *
 * A quarter of a megabyte, where it was 48/24KB: the light's own `light=` heartbeat (every
 * [FX_LIGHT_TRACE_MS]) writes ~6 lines a minute while the edge is lit, which filled the old window in
 * well under an hour of use. This file has exactly one job — still holding the beginning of a session
 * after the user reproduces something at its end — and 128KB of tail is what that job costs. Nothing
 * here is unbounded: the rewrite happens once per [FX_TRACE_KEEP_BYTES] written, not per line.
 */
private const val FX_TRACE_MAX_BYTES = 256 * 1024
private const val FX_TRACE_KEEP_BYTES = 128 * 1024

/**
 * How often the light's own state is written while it does not change.
 *
 * The `light=` line is written whenever the answer flips — that is the timeline a 「灯不该亮却亮着」
 * report is read from — and then once every this often, so a light that stays on for ten minutes
 * still says why. Ten seconds stays well inside the trace's own cap.
 */
private const val FX_LIGHT_TRACE_MS = 10_000L

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
 * The flash says "something just happened"; the faint breath of the same rainbow ring that
 * [noteDriving] switches on says "it is still happening". Only driving earns the breath — a run
 * that reads the screen or only answers in words shows nothing, because the user asked to see
 * their phone being operated, not to be told that someone is looking at it. 随行自己在前台时也
 * 不亮（用户口径：在前台一律不显示）—— 用户正看着 App 的时候，光没有要传达的信息。
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

    /**
     * The application context every effect is built from — and the one [returnToApp] starts from.
     *
     * Volatile because [noteDriving] writes it on the tool dispatcher while [apply] and [returnToApp]
     * read it on the main thread. Without it a tick can read null and skip the light — or skip the
     * trip home — for one action, which is exactly the kind of one-in-a-hundred report nobody can
     * reproduce. A single reference, so volatile is enough; no lock is needed.
     */
    @Volatile
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
     *
     * Volatile alone is not enough for the two *writers*, though: both do a read-modify-write
     * ([noteDriving] adds the ids of the runs it just saw, the ticker drops the ones that ended),
     * and two of those interleaved lose one update — an id that never arrives means the light never
     * comes on for that run, and [returnToApp] never fires for it either. Hence [drivingLock], held
     * for exactly those two assignments and nothing else.
     */
    @Volatile
    private var drivingIds: Set<String> = emptySet()

    /** Guards the read-modify-write of [drivingIds] against its two writers. See there. */
    private val drivingLock = Any()

    /** When the last driving action happened, for the grace window. Volatile as above. */
    @Volatile
    private var lastDrivingAt = 0L

    /**
     * When the last driving run left, which is what the fade [FX_BREATH_HOLD_MS] counts from.
     *
     * Zero means no episode is finishing. Set by the ticker on the tick that sees the departure, so
     * a tail can only extend a light a run had already switched on — a timer alone cannot light the
     * edge. Volatile for the same reason as [drivingIds].
     */
    @Volatile
    private var drivingLeftAt = 0L

    /** The last answer handed to the view, so the trace writes a line when it flips. Main thread. */
    private var lastLit = false

    /** When the trace last wrote a `light=` line, so a long run still shows as a heartbeat. */
    private var lastLightTraceAt = 0L

    /** The process-lifecycle observer is added once, from the main thread — see [watchForeground]. */
    private var watchingForeground = false

    /** The run set as the previous tick saw it, so a tick can tell which run just ended. */
    private var previousIds: Set<String> = emptySet()

    /**
     * One thread for the trace file, so the lines stay in the order the effects happened and no
     * caller — including the accessibility service, on the main thread — ever waits on the disk.
     *
     * Named, daemon and low priority: the thread exists for the life of the process (the executor is
     * deliberately never shut down — a trace that silently stops being written is worse than one
     * idle thread), so it must not be the thing that keeps the process alive, and writing a
     * diagnostic must never compete with the UI thread for the CPU.
     */
    private val traceWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fx-trace").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
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
        noteDriving(context)
        post(context, "entry=tap x=$x y=$y") { fx ->
            flashEdge(fx)
            fx.addRing(x, y, holdMs = 0L, delayMs = 0L)
        }
    }

    fun longPress(context: Context, x: Float, y: Float) {
        noteDriving(context)
        post(context, "entry=longPress x=$x y=$y") { fx ->
            flashEdge(fx)
            fx.addRing(x, y, holdMs = FX_LONG_PRESS_HOLD_MS, delayMs = 0L)
        }
    }

    fun swipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) {
        noteDriving(context)
        post(context, "entry=swipe x1=$x1 y1=$y1 x2=$x2 y2=$y2") { fx ->
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
        post(context, "entry=handoff") { fx ->
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
     * Ids are added, never replaced: a run that drove keeps the light for as long as it lives,
     * even if the next action is taken while a different set of runs happens to be in flight.
     * Ids that have ended are dropped by the ticker, so a later run in the same conversation
     * starts from nothing rather than inheriting the light from the one before it.
     *
     * 没有任何 run 在飞时这里什么也不点亮（原来的 job 窗口已删）：后台定时任务本身也是 run
     * （SchedulerService 走 Core.engine.startRun），所以「没有 run 的一次驱动动作」不是任务，
     * 它只该留下那一圈光晕，不该让边缘一直亮着 —— 用户报的正是这个。
     */
    fun noteDriving(context: Context) {
        appContext = context.applicationContext
        val running = Core.engine.runsFlow.value.keys
        lastDrivingAt = SystemClock.uptimeMillis()
        trace(context, "entry=noteDriving runs=${running.size} ids=${running.joinToString(",")}")
        synchronized(drivingLock) { drivingIds = drivingIds + running }
        // 一次驱动动作开一段新的亮灯：上一段的收尾淡出到此为止。
        drivingLeftAt = 0L
        // Also (re)starts the ticker: the first tick is immediate, so the light comes on with the
        // action instead of up to a second after it.
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    /**
     * The light mirrors a state that lives in the engine, so it is polled rather than pushed — and
     * every path that changes one of its inputs re-asks [shouldLight] through [apply] instead of
     * waiting for the next tick.
     *
     * Nothing has to switch it off, which is the point: a run that dies without telling anyone
     * cannot leave the light on, because the next tick sees that its id is gone. The ticker
     * stops itself once the light is out **and** no driving run is left, so no timer runs while
     * the agent is idle — a dark ticker with a run still in flight is the one that watches for
     * the departure that brings the user back to the app.
     *
     * 亮不亮由 [shouldLight] 回答，而那个答案是状态推出来的：所以每一 tick 都重新问一遍，窗口
     * 中途被重建（跳转 / 服务重连）也不会留下一块暗着的画布。
     */
    /**
     * How long the way home is held back after the last driving run ends.
     *
     * Three seconds: longer than the gap between one turn of a conversation and its next, short
     * enough that a user who is waiting for the trip does not think nothing happened. See
     * [returnCheck] for what it is protecting against.
     */
    private const val FX_RETURN_QUIET_MS = 3000L

    /**
     * The trip home, answered only after the engine has gone quiet.
     *
     * Armed by a driving run's departure and asked [FX_RETURN_QUIET_MS] later: if anything is
     * still running — the next turn of this conversation, another conversation's task, a queued
     * message — the user is still being operated for and nothing happens. Only a whole engine at
     * rest gets the phone handed back. [returnToApp] itself still decides whether the app is
     * already in front and whether the system lets the start through.
     */
    private val returnCheck = object : Runnable {
        override fun run() {
            if (Core.engine.runsFlow.value.isNotEmpty()) return
            returnToApp()
        }
    }

    /**
     * 该不该亮：只从状态推，不看任何 view，也**不看「上一次动作有多近」**。
     *
     * 主判据只有一条：**驱动过手机的 run 还活着**（[drivingIds] 里有 id 且 `it in running`）。
     * 用户的两次真机反馈把这条钉死了：光不能由时间自己点起来（「不在执行任务也会显示」），也
     * 不能在随行自己的前台亮（「有时候在前台也会自动显示」）。
     *
     * 三种亮法，按优先级：
     * 1. `foreground` → 一律不亮，而且立刻灭：进前台不留尾巴（用户口径：在前台一律不显示）。
     * 2. 驱动过的 run 还活着 → 亮。模型想多久、读屏多久都不灭。
     * 3. 那个 run 刚离场（[drivingLeftAt] 起 [FX_BREATH_HOLD_MS] 之内）→ 亮，这一段是它的淡出。
     *    它只能延长已经亮起来的一段：没有 run 亮过就没有 [drivingLeftAt]，时间自己点不着灯。
     *
     * 抽成一道纯判断，是因为每个会改变输入的路径都要重问一遍（[apply]）：窗口重建、进出前台、
     * 驱动动作。窗口被系统收走、无障碍服务重连、跳转之后新建的 view 都是「关着」的 —— 「点击
     * 跳转后直接没了」就是新画布没有继承状态。
     */
    private fun shouldLight(now: Long, running: Set<String>, foreground: Boolean): Boolean {
        if (foreground) return false
        if (drivingIds.any { it in running }) return true
        return drivingLeftAt != 0L && now - drivingLeftAt < FX_BREATH_HOLD_MS
    }

    /**
     * 重新算一次「该不该亮」，并立刻落到 view 上；返回这次算出来的答案。
     *
     * 每个会改变输入的路径都走这里，而不是只等一秒一次的 ticker —— ticker、窗口重建
     * （[overlay]）、进出前台（[watchForeground]）、驱动动作（[noteDriving]，它立刻 post 一次
     * ticker）。跳转和切前后台都是一瞬间的事，一秒就是「光没回来」和「光马上回来」的差别。
     *
     * Main thread only, like every other window operation here.
     */
    private fun apply(reason: String, context: Context? = null): Boolean {
        val app = appContext ?: context ?: return false
        watchForeground()
        val now = SystemClock.uptimeMillis()
        val running = Core.engine.runsFlow.value.keys
        val inForeground = foreground()
        val lit = shouldLight(now, running, inForeground)
        traceLight(app, reason, lit, running, now, inForeground)
        if (lit) overlay(app)?.setBreathing(true) else view?.setBreathing(false)
        return lit
    }

    /**
     * 判死用的一行：三个输入 + 结论 + 是谁问的。
     *
     * 变化时必写，另外每 [FX_LIGHT_TRACE_MS] 写一次心跳 —— 一次复现就能看出「谁把灯点亮的」和
     * 「谁没把它熄掉」。Main thread only, like [apply].
     */
    private fun traceLight(
        app: Context,
        reason: String,
        lit: Boolean,
        running: Set<String>,
        now: Long,
        foreground: Boolean,
    ) {
        if (lit == lastLit && now - lastLightTraceAt < FX_LIGHT_TRACE_MS) return
        lastLit = lit
        lastLightTraceAt = now
        trace(
            app,
            "light=${if (lit) "on" else "off"} reason=$reason foreground=$foreground " +
                "drivingAlive=${drivingIds.any { it in running }} running=${running.size} " +
                "drivingIds=${drivingIds.size} sinceAction=${now - lastDrivingAt}ms " +
                "sinceLeft=${if (drivingLeftAt == 0L) "-" else "${now - drivingLeftAt}ms"}",
        )
    }

    /**
     * 随行自己在前台吗 —— 与悬浮球同一条口径（用户：「在前台一律不显示」）。
     *
     * 进程级判定，所以无障碍服务和前台服务都不会让它保持 STARTED：STARTED 就是「用户正看着
     * 随行」，而不是「随行在跑」。Main thread only.
     */
    private fun foreground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * 进出前台各重算一次，不等 ticker：进前台立刻灭（不留 6 秒尾巴），退到后台且驱动 run 还活着
     * 立刻亮。注册只在主线程做一次 —— [apply] 就是主线程，[ProcessLifecycleOwner] 也只认主线程。
     */
    private fun watchForeground() {
        if (watchingForeground) return
        watchingForeground = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    apply("foreground")
                }

                override fun onStop(owner: LifecycleOwner) {
                    apply("background")
                }
            },
        )
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val running = Core.engine.runsFlow.value.keys
            val departed = previousIds - running
            previousIds = running.toSet()
            // A run that was operating the phone has just ended, and no other driving run is left:
            // that is the moment the user asked to be handed their phone back, so it happens on
            // this tick rather than after the light's grace period. A run that merely shared the
            // screen with the one that drove does not earn the trip on its own.
            val drivingLeft = departed.any { it in drivingIds }
            if (drivingLeft) {
                // 亮灯这一段的收尾从这里起算：尾巴只能延长一段已经亮起来的灯，时间自己点不着。
                drivingLeftAt = now
            }
            if (drivingLeft && running.none { it in drivingIds }) {
                // Armed, not taken. A conversation can run its next turn the moment this one ends
                // — a steered message waiting its turn, a task list moving to its next item — and
                // handing the phone back between two of those would take it away again a second
                // later. The user's worry was exactly that: 「万一是连续动作呢，那岂不是一直跳」.
                // [returnCheck] answers after FX_RETURN_QUIET_MS, and anything still in flight
                // then means this was not the end of the task.
                main.removeCallbacks(returnCheck)
                main.postDelayed(returnCheck, FX_RETURN_QUIET_MS)
            }
            // An id that ended stops counting as driving, and an id from a run nobody is watching
            // any more cannot light anything either: what stays is exactly 「still alive and drove」.
            // The trim happens here rather than in [apply] on purpose — the departure above needs
            // the id to still be in the set on the tick that sees it.
            synchronized(drivingLock) { drivingIds = drivingIds.intersect(running) }
            val lit = apply("tick")
            // 亮着就还要人熄灯（收尾那几秒），前台里虽然不亮、但活着的驱动 run 还等着离场（自动
            // 返回就靠这一 tick 发现），所以两种都要继续；只有「不亮 + 没有活着的驱动 run」才停表。
            if (lit || drivingIds.any { it in running }) {
                main.postDelayed(this, FX_BREATH_TICK_MS)
            } else {
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
        if (foreground) {
            // 「还是不会自动返回」 has three candidates — the tick never got here, the app was
            // already in front, or the system refused the start — and they look identical from
            // outside. One line per call tells them apart: this one, then the two below.
            trace(app, "entry=returnToApp foreground=true（已在前台）")
            return
        }
        // The exemption for a background activity start is holding SYSTEM_ALERT_WINDOW, so its
        // answer is the first thing to check when the start below reports no error yet nothing
        // happens: on the accessibility-overlay host this app may not have the permission at all.
        val overlays = Settings.canDrawOverlays(app)
        val error = runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.exceptionOrNull()
        trace(
            app,
            "entry=returnToApp canDrawOverlays=$overlays " +
                "startActivity=${error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok"}",
        )
    }

    /**
     * Gestures come off the tool dispatcher, but windows and drawing are main-thread only.
     *
     * The entry line is written **here**, after [overlay] has run, and not by the caller: the
     * window is created on this thread one hop after the caller posts, so a line written by the
     * caller could only ever report the state *before* the effect — which is why an action that
     * lit the screen a millisecond later still read `overlay=none`, and why the file could not
     * answer the one question it exists for. `none` now means what it says: no window at the
     * moment of the effect. ([noteDriving]'s own line still runs first and still reports the
     * state before this post; it is about which runs are driving, not about the window.)
     */
    private fun post(context: Context, entry: String, block: (FxView) -> Unit) {
        val app = context.applicationContext
        main.post {
            val fx = overlay(app)
            trace(app, entry)
            if (fx != null) block(fx)
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
                    // Bytes, not characters, and the first (partial) line of the tail is dropped:
                    // cutting a UTF-8 log at a byte offset lands mid-character, and a line starting
                    // mid-word is worse than no line. One rewrite per [FX_TRACE_KEEP_BYTES] written.
                    val bytes = file.readBytes()
                    val from = (bytes.size - FX_TRACE_KEEP_BYTES).coerceAtLeast(0)
                    val tail = String(bytes, from, bytes.size - from, Charsets.UTF_8)
                        .substringAfter('\n', "")
                    file.writeText("— 已截断，仅保留最近一段 —\n$tail\n")
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
        // 留着一个已经不在窗口树上的 view 是最坏的状态：系统把它收走了（跳转到别的界面、无障碍
        // 服务重连、ROM 自己回收悬浮窗），而我们还在往它上面画 —— invalidate() 对不在树上的 view
        // 不会再触发 onDraw，光从此不会回来，从外面看和「没有特效」一模一样。窗口在不在树上才是
        // 真的，所以丢掉它重建，而不是继续信旧实例。这就是「不依赖旧的 view 状态」。
        view?.let { fx ->
            if (fx.isAttachedToWindow) return fx
            release("窗口已不在窗口树上")
        }
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
        val bounds = displayBounds(host)
        val fx = FxView(host, bounds, screenCornerRadius(host)) { detach() }
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // These two are what actually make the window full-screen, and why the light never
            // reached the status bar without them.
            //
            // `FLAG_LAYOUT_IN_SCREEN` is not enough on its own, which is what the previous
            // attempt assumed. In R+ a window's frame comes from `WindowLayout.computeFrames`,
            // which insets the display frame by `LayoutParams.getFitInsetsTypes()` — and that
            // defaults to `WindowInsets.Type.systemBars()` (LayoutParams.java:4259, with sides
            // `Side.all()` at :4279). `FLAG_LAYOUT_IN_SCREEN` only reaches the parent frame and
            // the cutout exception in that method, never those insets, so a MATCH_PARENT window
            // was still laid out from the bottom of the status bar to the top of the gesture bar,
            // and everything the glow drew above that was clipped away — 「没有包括状态栏」.
            // Fitting no insets at all is how the system's own full-screen overlay asks for the
            // same thing (DisplayPolicy.java:2799, the pointer-location window).
            //
            // ALWAYS for the cutout, for the notch and the punch-hole: any other mode clamps the
            // window into `displayCutoutSafe` (WindowLayout.java:108-111), which is a second way
            // for the top strip to go missing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Neither `setFitInsetsTypes` nor ALWAYS exists before R; SHORT_EDGES is the
                // portrait cutout mode those releases have.
                @Suppress("DEPRECATION")
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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
        // The window's own size against the display's, one frame after it is added. Whether the
        // window really spans the panel is the whole question the 「没有覆盖状态栏」 report turns
        // on, and from outside this file it is invisible: a window the system laid out inside the
        // bars draws a light that stops at them and looks exactly like a drawing bug.
        fx.post {
            trace(context, "window=${fx.width}x${fx.height} display=${bounds.width()}x${bounds.height()}")
        }
        manager = windowManager
        view = fx
        // 画布是新的，「该不该亮」不是：跳转、服务重连、窗口重建之后新 view 的呼吸是关的，只有
        // 按当前状态立刻对一遍，光才不会跟着旧画布一起消失 —— 「跳转后不显示」一半是旧的亮灯
        // 规则，另一半就是这里从来没有人把状态补回来。这里直接算、直接点亮（不经过 [apply]，
        // 免得 [apply] → 建窗 → 再 [apply] 绕回来），所以 ticker 停了也照样亮。
        val now = SystemClock.uptimeMillis()
        val running = Core.engine.runsFlow.value.keys
        val inForeground = foreground()
        val lit = shouldLight(now, running, inForeground)
        traceLight(context, "overlay", lit, running, now, inForeground)
        if (lit) fx.setBreathing(true)
        return fx
    }

    /**
     * The display's true bounds in px, which is what the edge light is drawn on.
     *
     * Not the view's measured size: an accessibility-overlay window is full-screen but its measured
     * size lands a tick after the first draw, and an application-overlay one is laid out inside the
     * system bars — either way the rounded rectangle came up short of the panel's edge, which is
     * the 「没有完全覆盖好屏幕边缘」 report. These bounds include the status bar, the gesture bar
     * and the cutout, so the light traces the whole screen from the first frame.
     *
     * `getMaximumWindowMetrics` and not `getCurrentWindowMetrics`: the latter describes the window,
     * which is exactly the thing that came up short, while the former is the largest area the
     * display can hand to any window. Asking the display rather than the window is the point — it
     * cannot inherit a layout mistake. From API 30; below that `Display.getRealSize`, which is the
     * pre-R name for the same physical extent. An empty result — no display attached, which happens
     * on a service that is being torn down — falls through to the app's own metrics rather than
     * drawing nothing.
     */
    @Suppress("DEPRECATION") // defaultDisplay/getRealSize: minSdk 26, and this is the pre-R path.
    private fun displayBounds(context: Context): Rect {
        val manager = context.getSystemService(WindowManager::class.java)
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = runCatching { manager.maximumWindowMetrics.bounds }.getOrNull()
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

    /**
     * The panel's own corner radius in px, which is the only radius the light can trace.
     *
     * 96px was a guess that happened to be near right on one 1080p panel; on a screen whose corners
     * are rounder the ring cuts the corner, and on one that is squarer it bulges away from the
     * edge — both read as 「没有严格按照边缘画」. API 31 publishes the real number, so ask for it;
     * an older device, or a display that reports 0 (which is what "no rounded corner" looks like),
     * falls back to the constant. Never throws: a missing corner is not worth losing the effect.
     */
    private fun screenCornerRadius(context: Context): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return FX_EDGE_RADIUS_PX
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return FX_EDGE_RADIUS_PX
        val radius = runCatching {
            display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        }.getOrDefault(0)
        return if (radius > 0) radius.toFloat() else FX_EDGE_RADIUS_PX
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
        // The log has to say what is true now, not what was true when the window was built:
        // leaving the old "created" note behind made a taken-down window look alive, which is
        // the kind of lie that costs an hour of guessing.
        fx.context.applicationContext?.let { noteOverlay(it, "detached（空闲）") }
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
 * [TouchFx.displayBounds]. [cornerPx] is the panel's own corner radius, so the ring follows the
 * screen's shape rather than a guess at it.
 */
private class FxView(
    context: Context,
    private val screenBounds: Rect,
    private val cornerPx: Float,
    private val onIdle: () -> Unit,
) : View(context) {

    private class Ring(val x: Float, val y: Float, val startAt: Long, val holdMs: Long, val delayMs: Long)

    private class Trail(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val startAt: Long)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The panel's own scale. Every size this view draws is written in dp and converted here, so
     * the ring a finger gets is the same ring on a 2x phone and on a 4x one.
     */
    private val density = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    /**
     * The edge light's own paint, kept apart from [paint] because it carries a shader: a shader
     * wins over `color`, so sharing one Paint would quietly repaint the rings and the trail with
     * the rainbow as well.
     */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rings = mutableListOf<Ring>()
    private val trails = mutableListOf<Trail>()
    private val edgeRect = RectF()
    private val edgePath = Path()

    /**
     * Scratch paths for the halo, one band at a time.
     *
     * Reused rather than built per band: 16 bands at 25fps is 400 `Path.op` calls a second, and a
     * fresh Path per band would be 400 allocations a second on top of that for no gain.
     */
    private val haloBand = Path()
    private val haloInner = Path()
    private var edgeStart = 0L
    private var idleReported = false

    /** The ring's shader, rebuilt only when the screen's shape changes — see [rainbow]. */
    private var rainbowShader: SweepGradient? = null
    private val rainbowRect = RectF()
    private val rainbowMatrix = Matrix()

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
        if (edgeElapsed < FX_EDGE_DURATION_MS) drawEdge(canvas, edgeElapsed, now)

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
            dp(FX_RING_HOLD_RADIUS_DP) +
                (dp(FX_RING_MAX_RADIUS_DP) - dp(FX_RING_HOLD_RADIUS_DP)) * progress
        } else {
            dp(FX_RING_HOLD_RADIUS_DP)
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
        paint.strokeWidth = dp(FX_RING_STROKE_DP)
        paint.color = FX_COLOR_BRAND
        paint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(ring.x, ring.y, radius, paint)
    }

    private fun drawTrail(canvas: Canvas, trail: Trail, now: Long) {
        val progress = ((now - trail.startAt).toFloat() / FX_SWIPE_DURATION_MS).coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(FX_TRAIL_STROKE_DP)
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

    private fun drawEdge(canvas: Canvas, elapsed: Long, now: Long) {
        val progress = (elapsed.toFloat() / FX_EDGE_DURATION_MS).coerceIn(0f, 1f)
        drawGlow(canvas, 0.7f * (1f - progress), now)
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
        drawGlow(canvas, alpha, now)
    }

    /**
     * One edge, drawn as light rather than as a line.
     *
     * The band is filled, not stroked. A stroke is one width laid around the screen and reads as a
     * rule on the panel — 「像一个框」 was the user's verdict on it, at every width it was tried at.
     * This is Operit's edge instead, the app the user pointed at: the light starts on the screen's
     * own edge at full strength and fades inward over [FX_HALO_WIDTH_FRACTION] of the short side,
     * in [FX_HALO_BANDS] steps whose alpha follows `(1 - t)^1.55`. What the eye gets is a glow
     * coming off the panel rather than a border on it.
     *
     * Starting at the true edge is also what covers the status bar and the gesture bar: there is no
     * inset left for a gap to open in, and a pixel or two of layout error is hidden inside the
     * brightest band instead of showing up as a strip the light does not reach. That is the second
     * half of the answer to 「没有包括状态栏」 — the window flags are the first.
     *
     * Every band carries the same sweep shader, so the colour at a given angle is identical across
     * them and the halo stays one rainbow. The alpha rides on the Paint, which is what makes the
     * shader breathe: Skia multiplies a shader's output by the paint's alpha (the same reason
     * `GradientDrawable.setAlpha` works on a gradient).
     */
    private fun drawGlow(canvas: Canvas, alpha: Float, now: Long) {
        if (alpha <= 0.01f) return
        val outer = edgeShape()
        // Proportional to the short side, then capped: the fraction alone keeps growing with the
        // panel, and past [FX_HALO_MAX_WIDTH_DP] the light reads as a bar laid on the screen.
        val fadeWidth = (minOf(edgeRect.width(), edgeRect.height()) * FX_HALO_WIDTH_FRACTION)
            .coerceAtMost(dp(FX_HALO_MAX_WIDTH_DP))
        glowPaint.style = Paint.Style.FILL
        glowPaint.shader = rainbow(edgeRect, now)
        for (band in 0 until FX_HALO_BANDS) {
            val startT = band / FX_HALO_BANDS.toFloat()
            val endT = (band + 1) / FX_HALO_BANDS.toFloat()
            val level = ((1f - startT).pow(FX_HALO_FALLOFF) * alpha * 255f).toInt().coerceIn(0, 255)
            if (level <= 0) continue
            haloBand.set(outer)
            haloInnerBoundary(fadeWidth * endT)
            haloBand.op(haloInner, Path.Op.DIFFERENCE)
            glowPaint.alpha = level
            canvas.drawPath(haloBand, glowPaint)
        }
    }

    /**
     * The inner boundary of one band: the screen's shape moved in by [inset], its corners rounded
     * by the same amount less.
     *
     * Subtracting the inset rather than holding the radius is what keeps a band one width all the
     * way round. A corner's arc is centred `cornerPx` in from both edges, and moving the edge in by
     * `inset` while shortening the radius by `inset` leaves that centre exactly where it was — so
     * the two contours are concentric, and the fade is as wide around a corner as along a straight
     * edge. Holding the radius would pinch the band at the corners; holding it *and* drawing the
     * square corners Operit uses would balloon it there instead.
     */
    private fun haloInnerBoundary(inset: Float) {
        val safe = inset.coerceAtMost(minOf(edgeRect.width(), edgeRect.height()) / 2f)
        val radius = (cornerPx - safe).coerceAtLeast(0f)
        haloInner.reset()
        haloInner.addRoundRect(
            edgeRect.left + safe,
            edgeRect.top + safe,
            edgeRect.right - safe,
            edgeRect.bottom - safe,
            radius,
            radius,
            Path.Direction.CW,
        )
    }

    /**
     * 绕屏幕一圈的彩虹，每帧往前转一点色相。
     *
     * The gradient itself is built once and only rebuilt when the screen's shape changes; what
     * moves per frame is its local matrix. Building a `SweepGradient` per frame would allocate one
     * shader and one colour array 25 times a second for no visible difference.
     *
     * The rotation is driven off the clock rather than off a frame counter, so the colour turns at
     * the same speed whatever the frame rate does — and the elapsed time is reduced modulo one turn
     * *before* it becomes a float, which keeps the angle exact (see [FX_HUE_PERIOD_MS]).
     */
    private fun rainbow(rect: RectF, now: Long): SweepGradient {
        var shader = rainbowShader
        if (shader == null || rainbowRect != rect) {
            shader = SweepGradient(rect.centerX(), rect.centerY(), FX_RAINBOW, null)
            rainbowShader = shader
            rainbowRect.set(rect)
        }
        val degrees = (now % FX_HUE_PERIOD_MS).toFloat() / FX_HUE_PERIOD_MS * 360f
        rainbowMatrix.setRotate(degrees, rect.centerX(), rect.centerY())
        shader.setLocalMatrix(rainbowMatrix)
        return shader
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
     * the gesture bar, and with the inset at zero this contour *is* the edge the halo starts from.
     *
     * One `addRoundRect` and not four sides: a path assembled from four line segments has a seam at
     * every corner — a visible break in the light, the 「不连续」 half of the report — while this is
     * a single closed contour that runs round without ever lifting.
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
        edgePath.addRoundRect(edgeRect, cornerPx, cornerPx, Path.Direction.CW)
        return edgePath
    }
}
