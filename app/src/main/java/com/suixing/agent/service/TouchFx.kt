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
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.suixing.agent.MainActivity
import com.suixing.agent.core.Core
import com.suixing.agent.ui.theme.AppearancePalette
import com.suixing.agent.virtualscreen.VirtualScreenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

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
 * 把「Agent 正在操作你的屏幕」画出来：点击画环、拖动画线、手势开始时边缘闪一下。
 *
 * 闪 = 刚刚发生了一次动作；呼吸 = UI 自动化回合仍在进行（整轮 run 结束就灭，非 UI 工具是中性的）。
 * 只在真正驱动屏幕时亮 —— 只看屏幕或只回答文字的 run 不亮。
 *
 * 本类不参与决策：无障碍服务在**决定要派发手势之后**才调用它。窗口优先挂在无障碍服务上
 * （TYPE_ACCESSIBILITY_OVERLAY，无需权限、MIUI 后台也不隐藏），否则回退到应用上下文
 * （需 SYSTEM_ALERT_WINDOW）。宿主消失时 bind/unbind/release 丢掉陈旧窗口，下次重建。
 */
object TouchFx {

    private val main = Handler(Looper.getMainLooper())

    /**
     * The window the effects are drawn into, and the manager that put it there.
     *
     * Volatile because the window is created and torn down on the main thread ([overlay]
     * and [release]) while [trace] reads it from whatever thread the effect came from —
     * the tool dispatch thread for a gesture or a tool call. Without the volatile write
     * that read is not guaranteed to see the latest value at all: a tick can report
     * `overlay=none` while a window is up, or `overlay=up` after the window was taken
     * down, and the trace then describes a screen that does not exist.
     *
     * [manager] is put up and taken down in the same two places as [view] — [overlay] adds
     * the window through it, [release] removes it through it — so the two are written as
     * a pair and cleared as a pair. It carries the same guarantee for the same reason: a
     * reader that sees the new window must not see the old manager (or none at all),
     * because [release] needs the manager that added the view to remove it.
     *
     * Both are single references, so volatile is enough — no lock is needed.
     */
    @Volatile
    private var view: FxView? = null

    @Volatile
    private var manager: WindowManager? = null

    /**
     * 我们**相信**屏幕上还挂着一块窗口 —— 只有 `removeView` 真的成功才置假（issue #8）。
     *
     * 与 `view != null` 不是一回事：`release()` 以前不管删没删成都把 `view` 置空，于是「窗口还在、
     * 代码以为它没了」。ticker 的停表条件必须看这个标记，而不是 `view.edgeAlpha`。
     */
    private var windowUp = false
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

    /**
     * 最近一次 UI 操作（含读屏）的时刻，回合的续期基准：距它超过 [EPISODE_QUIET_MS] ⇒ 回合结束。
     *
     * Volatile：工具派发线程写（[armEpisode] 在调用线程上写），主线程读（ticker、[apply]）。
     */
    @Volatile
    private var lastUiAt = 0L

    /** 最近一次已知的前台状态，只服务日志里的 `fg=self|other` —— 见 [trace]。主线程写。 */
    @Volatile
    private var lastForeground = false

    /** The last answer handed to the view, so the trace writes a line when it flips. Main thread. */
    private var lastTarget = -1f

    /**
     * 外观页的「边缘光」开关（`AppSettings.edgeLight`，默认开）。关掉只是不显示：
     * 驱动判定、自动返回、`fx-trace.log` 全都照旧。
     *
     * Volatile：设置收集协程写（主线程），[apply] 读（主线程）。
     */
    @Volatile
    private var edgeLightOn = false
    private var edgeSettingsReady = false
    private var edgePreferenceToken = 0L
    private var pendingMasterDisable: Long? = null
    private var pendingForegroundDisable: Long? = null

    /** Main-thread UI intent: suppress before an asynchronous preference write can complete. */
    fun suppressEdgeForPreferenceChange(foregroundOnly: Boolean): Long {
        val token = ++edgePreferenceToken
        if (foregroundOnly) pendingForegroundDisable = token else pendingMasterDisable = token
        if (!foregroundOnly || foreground()) view?.setEdgeEnabled(false)
        return token
    }

    /** A failed/cancelled write may undo only its own pending suppression. */
    fun cancelEdgePreferenceChange(token: Long) {
        if (pendingMasterDisable == token) pendingMasterDisable = null
        if (pendingForegroundDisable == token) pendingForegroundDisable = null
        apply("settings-write-not-saved")
    }

    private fun edgeAllowed(inForeground: Boolean): Boolean = edgeSettingsReady &&
        pendingMasterDisable == null && (!inForeground || pendingForegroundDisable == null) &&
        edgeLightVisible(edgeLightOn, edgeLightInForeground, inForeground)

    /**
     * 边缘光在**前台是否显示**（`AppSettings.edgeLightInForeground`，默认开 = 保持 issue #6）。
     * 关掉之后：JAgent在前台不亮，退到后台操作别的 app 照常亮。
     */
    @Volatile
    private var edgeLightInForeground = true

    /** 设置收集只开一次。 */
    private var watchingSettings = false

    /**
     * 目标第一次归零的时刻，用来判「停摆」（issue #7）。0 = 现在不是归零状态。
     * Main thread only（只在 [apply] 里读写）。
     */
    private var targetZeroAt = 0L

    /** 收集设置的协程作用域：进程级，服务停了也不用取消（对象本来就是单例）。 */
    private val settingsScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /**
     * 跟着外观页的开关走。第一次 [apply] 时开一次，之后常驻。
     *
     * 关掉的那一刻要**立刻**熄灯，不能等下一次 tick（最长一秒）—— 所以收集到变化就重算一次。
     */
    /** 当前配色的 primary 转 ARGB；themeMode: 0=跟随系统, 1=浅色, 2=深色。 */
    private fun brandColorFor(themeMode: Int, paletteMode: Int): Int {
        val night = (android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = themeMode == 2 || (themeMode == 0 && night)
        return AppearancePalette.fromId(paletteMode).scheme(dark).primary.toArgb()
    }

    private fun watchSettings() {
        if (watchingSettings) return
        watchingSettings = true
        settingsScope.launch {
            // **收集器不能静默死掉**（issue #9 第 3 条）：`settings.flow` 是 DataStore 的冷流，
            // 读盘失败会抛；没有 catch 的话 SupervisorJob 不会重启它，外观设置从此不生效，
            // 而且没有任何迹象。失败就写一行 trace，让人能从日志里看出来。
            runCatching {
                Core.settings.flow.collect { s ->
                    // 边缘光跟当前配色的 primary，不再留一套固定墨绿。
                    fxBrandColor = brandColorFor(s.themeMode, s.paletteMode)
                    val changed = !edgeSettingsReady || edgeLightOn != s.edgeLight ||
                        edgeLightInForeground != s.edgeLightInForeground
                    edgeLightOn = s.edgeLight
                    edgeLightInForeground = s.edgeLightInForeground
                    if (!s.edgeLight) pendingMasterDisable = null
                    if (!s.edgeLightInForeground) pendingForegroundDisable = null
                    edgeSettingsReady = true
                    if (changed) apply("settings")
                }
            }.onFailure { error ->
                // Unknown preferences must never briefly enable a saved-disabled effect.
                edgeSettingsReady = false
                edgeLightOn = false
                view?.setEdgeEnabled(false)
                watchingSettings = false
                appContext?.let {
                    trace(it, "entry=settings-collect-failed ${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }
    }

    /**
     * 这一回合是否已经写过 `episode=` 的转折行 —— 收尾时据此决定要不要写 `end`。Main thread only.
     *
     * 判据必须是它，不能是「灯亮没亮」：前台抑制期间灯全程灭着，而回合确实开了又收，按灯判定
     * 就会在日志里留下一串没有 `end` 的 `start`（[endEpisode] 每秒都会被 ticker 问一次，所以
     * 用一位标记，问一百次也只写一行）。
     */
    private var episodeTraced = false

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
     * The window is otherwise permanent（spec v2 改动四：一次 addView 常驻，亮灭靠 alpha），所以这里是
     * 它唯一被摘掉的地方：宿主服务没了，或者一块陈旧的窗口必须换掉。Runs on the main thread only, and a
     * removal the system refuses is ignored — there is nothing to do about a window that is already gone.
     */
    private fun release(reason: String) {
        val fx = view ?: return
        val windowManager = manager
        val app = appContext
        // Through the captured WindowManager rather than fx.context: this runs on service
        // disconnect, when asking the service for its system services again may find a context
        // that is already being torn down. The manager that added the view can always remove it.
        val error = runCatching { windowManager?.removeView(fx) }.exceptionOrNull()
        if (error != null) {
            // **失败不能静默**（issue #8）：`removeView` 失败时窗口还留在系统里，而以前这里照样把
            // `view` 置空 —— 那块还亮着的窗口就成了孤儿，`view.edgeAlpha` 读到的是别的实例（或 0），
            // ticker 于是判「看不见」停表，`apply()` 的兜底再也等不到执行。
            // 现在：引用留着，下一次 release 重试；并且写一行带异常的日志。
            noteOverlay(
                app ?: return,
                "released（$reason）**FAILED**: ${error.javaClass.simpleName}: ${error.message}",
            )
            return
        }
        view = null
        manager = null
        windowUp = false
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
     * that draws a ring or a flash counts as driving. 读屏（`ui_dump` / `ui_screenshot`）走的是另一条
     * 入口 [noteToolActivity]：spec v2 里它同样是 UI 操作，只是不画环。
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
        trace(context, "entry=noteDriving runs=${running.size} ids=${running.joinToString(",")}")
        synchronized(drivingLock) { drivingIds = drivingIds + running }
        // 一次驱动动作就是一次 UI 操作：开启/续期回合。
        armEpisode(context, "driving")
        // Also (re)starts the ticker: the first tick is immediate, so the light comes on with the
        // action instead of up to a second after it.
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    /**
     * 工具层的一次调用：命中触发集就开启/续期回合，否则**什么都不做**（中性）。
     *
     * 中性是关键：read_file / web_search / 只读 `run_shell` / list_dir 既不点亮也不熄灭 —— 它们只是
     * 「不是 UI 操作」。回合的结束由静默超时或整轮 run 结束决定，不由某个工具决定（v1「每个工具各自
     * 亮灭」正是语义错乱的来源）。任何线程可调；碰 view 的部分由 [armEpisode] 投到主线程。
     *
     * 触发集的判定放在这里而不是各工具里，是因为 [traceCall] 已经拿到了工具名与命令原文（含被掩码的
     * 输入文本，掩码只影响日志，不影响这里的正则）。读屏那两个工具不走 [traceCall]，而是在
     * `tools/UiTools.kt` 各自的入口直接调这里（`dump()` 传 `"ui_dump"`、`screenshot()` 传
     * `"ui_screenshot"`）。
     */
    fun noteToolActivity(context: Context, tool: String, detail: String? = null) {
        // These tools follow the active virtual display. Their main-screen feedback
        // must be decided at the actual dispatch site, where the target is known.
        if (VirtualScreenManager.state.value.running &&
            (tool.startsWith("ui_") || tool == "open_app")) {
            trace(context, "entry=neutral screen=virtual tool=$tool")
            return
        }
        val triggers = tool in FX_UI_EPISODE_TOOLS ||
            shellToolDrivesUi(tool, detail)
        if (triggers) {
            // **读屏也要登记成"驱动"**（issue #2）：改判据之后灯由 [drivingIds] 决定，
            // 只 arm 不登记的话，一轮只做 ui_dump / ui_screenshot 的任务永远点不亮
            // （issue 的时间线里 21:08:35 那次 reason=tool=ui_dump 就是这种情况）。
            // 与 [noteDriving] 同一条口径：这一轮碰过屏幕，灯就归它。
            val running = Core.engine.runsFlow.value.keys
            synchronized(drivingLock) { drivingIds = drivingIds + running }
            armEpisode(context, "tool=$tool")
            return
        }
        // **非 UI 工具是中性事件**（issue #2）：read_file / web_search / 只读 run_shell / list_dir
        // 只说明「这一次不是 UI 操作」，不说明「控制结束了」—— agent 在两次点击之间读文件、跑命令、
        // 等模型推理都是常态。灯由 [apply] 按「驱动过屏幕的 run 还活着」决定，这里什么也不改。
        trace(context, "entry=neutral tool=$tool")
    }

    /**
     * steer / 排队消息被发出时调这个（spec v2 改动六）：与一次工具调用等价 —— 刷新回合时刻并续期，
     * 修掉排队态「该灭不灭 / 该亮不亮」。
     */
    fun noteActivity(context: Context) = armEpisode(context, "steer")

    /**
     * 开启或续期回合：刷新 [lastUiAt]、点亮（幂等）、把静默定时器推后 [EPISODE_QUIET_MS]。
     *
     * [lastUiAt] 在调用线程上写（工具派发线程是常态），其余在主线程做 —— view 只能在主线程碰。
     */
    private fun armEpisode(context: Context, source: String) {
        appContext = context.applicationContext
        // 「这一次是开启还是续期」必须在刷新 [lastUiAt] **之前**问：刷新之后答案永远是「活着」。
        // 判据是回合 latch（[episodeLive]），不是 `view.lit` —— 前台抑制期间灯是灭的、回合却是活的，
        // 按灯判定会把同一段回合里的每次动作都写成 `start`，日志于是出现一串没有 `end` 的 start。
        val wasLive = episodeLive(SystemClock.uptimeMillis())
        lastUiAt = SystemClock.uptimeMillis()
        main.post {
            val app = appContext ?: return@post
            watchForeground()
            val inForeground = foreground()
            lastForeground = inForeground
            // 用户正盯着JAgent时不亮（spec v2 改动二）：回合照记（lastUiAt 已刷新），只是不显示。
            episodeTraced = true
            traceEpisode(app, if (wasLive) "extend" else "start", source)
            main.removeCallbacks(episodeEnd)
            // 目标交给 apply 算（它知道 run 是否还在、是不是驱动过手机），这里只负责"这一下算 UI 操作"。
            // 不自己 setLit：亮度已经是连续量，谁都能定目标就等于两处打架。
            apply("ui", app)
            // 回合的静默收尾照旧排表 —— 它现在只写日志（见 [endEpisode]），不再决定灯亮不亮，
            // 所以前台抑制期间也照样排：那只是"这一回合过去了"的记录。
            main.postDelayed(episodeEnd, EPISODE_QUIET_MS)
        }
    }

    /**
     * 静默到点：**只划日志边界，不灭灯**（issue #2）。
     *
     * 20 秒没有新的 UI 操作只说明「最近没碰屏幕」，不说明这一轮控制结束了 —— 真机实测两次
     * UI 操作之间 p50=18.9s、最长 191.7s，agent 读文件/跑命令/等推理都在这段间隔里。灯亮不亮
     * 由 [apply] 按「驱动过屏幕的 run 还活着」决定，所以这里把回合 latch 归零、写一行
     * `episode=quiet`，灯不动。
     *
     * 每次 [armEpisode] 都会重排它，所以这里只需回答「真的静默够久了吗」；不够就按剩余时间再排一次。
     * Main thread only.
     */
    private val episodeEnd = object : Runnable {
        override fun run() {
            val quiet = SystemClock.uptimeMillis() - lastUiAt
            if (quiet < EPISODE_QUIET_MS) {
                main.postDelayed(this, EPISODE_QUIET_MS - quiet)
                return
            }
            endEpisode("quiet", traceEnd = false)
        }
    }

    /**
     * 回合收尾：清续期基准 + 写日志。整轮 run 结束走这里；静默到点也走，但那时 [traceEnd] 为假，
     * 只写 `episode=quiet`（不写 `end`，因为灯可能还亮着 —— 见 [episodeEnd]）。
     *
     * Main thread only. 已经收过尾的回合不重复写日志 —— 这个函数会被 ticker 每秒问一次。
     */
    private fun endEpisode(reason: String, traceEnd: Boolean = true) {
        main.removeCallbacks(episodeEnd)
        val app = appContext ?: return
        // 清掉续期基准，否则下一轮 run 会被上一轮残留的 lastUiAt 误判成「还在同一个回合里」。
        lastUiAt = 0L
        // 判据是「这一回合有没有被记过」，不是「灯亮没亮」：前台抑制期间灯全程灭着而回合确实开了又收，
        // 按灯判定就永远不写 `end`（见 [episodeTraced]）。已经收过尾的回合不重复写 —— 这个函数每秒
        // 都会被 ticker 问一次。
        if (!episodeTraced) return
        // 静默到点只写一行 `episode=quiet`，**回合不关**（[episodeTraced] 保持为真）：灯这时可能
        // 还亮着（驱动 run 没结束），真正收尾的那一行留给 run 结束时的 `end reason=run-idle`。
        if (!traceEnd) {
            traceEpisode(app, "quiet", reason)
            return
        }
        episodeTraced = false
        traceEpisode(app, "end", reason)
        apply("episode-end", app)
    }

    /**
     * 回合 latch 还活着吗：最后一次 UI 操作在 [EPISODE_QUIET_MS] 之内。
     *
     * **它不再决定灯亮不亮**（issue #2）：只用来在日志里分辨 `episode=start` 与 `extend`。
     * 亮不亮由 [apply] 按「驱动过屏幕的 run 还活着」回答。
     */
    private fun episodeLive(now: Long): Boolean =
        lastUiAt != 0L && now - lastUiAt < EPISODE_QUIET_MS

    /** 回合的转折点（spec v2 改动七）：`episode=start|extend|end` 由这一行写进日志。 */
    private fun traceEpisode(app: Context, kind: String, reason: String) {
        trace(app, "episode=$kind reason=$reason", episode = kind)
    }

    /**
     * The light mirrors a state that lives in the engine, so it is polled rather than pushed — and
     * every path that changes one of its inputs re-asks [apply] instead of waiting for the next tick.
     *
     * Nothing has to switch it off, which is the point: a run that dies without telling anyone
     * cannot leave the light on, because the next tick sees that its id is gone. The ticker
     * stops itself once the light is out **and** no driving run is left, so no timer runs while
     * the agent is idle — a dark ticker with a run still in flight is the one that watches for
     * the departure that brings the user back to the app.
     *
     * 亮不亮由 [apply] 按「驱动过屏幕的 run 还活着」回答：所以每一 tick 都重新问一遍，窗口中途被
     * 重建（跳转 / 服务重连）也不会留下一块暗着的画布。
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
     * 重新按当前状态对一次灯，并返回这一次是否显示（回合还活着且不在前台）。
     *
     * 每个会改变输入的路径都走这里，而不是只等一秒一次的 ticker：进出前台（[watchForeground]）、
     * 窗口重建（[overlay]）、回合的开关（[armEpisode]/[endEpisode]）。Main thread only.
     */
    private fun apply(reason: String, context: Context? = null): Boolean {
        val app = appContext ?: context ?: return false
        watchForeground()
        watchSettings()
        val now = SystemClock.uptimeMillis()
        val running = Core.engine.runsFlow.value.keys
        val inForeground = foreground()
        lastForeground = inForeground
        // 「这个 run 驱动过手机，而且整轮还没结束」—— 用来兜住控制序列里的**长**思考间隔
        // （真机实测 UI→UI 间隔 p50=18.9s、最长 191.7s，任何超时窗都追不上；追就会变回一直亮）。
        val drivingAlive = drivingIds.any { it in running }
        // **亮 = 这一轮驱动过屏幕的 run 还活着**（issue #2）。两个旧条件都删了：
        //  · 静默超时（[episodeLive]）—— p50 已 18.9s，20 秒窗会在一半的控制间隔里误灭；
        //  · 非 UI 工具（原 nonUiSinceLastUi）—— read_file / web_search 只是「不是 UI 操作」，
        //    不是「控制结束了」，把它们当熄灭信号会让一次连续任务亮灭十几次。
        // 现在熄灭只有一条路：驱动过屏幕的 run 全部离场（ticker 把 drivingIds 与 running 取交集）。
        // 单一亮度，没有暗档 —— 暗档正是「看着像中断」的来源。
        val sessionAlive = drivingAlive
        // **前台不再是熄灭条件**（issue #6，用户：「JAgent自己在前台时也要显示」）：
        // 亮不亮只看这一轮驱动过屏幕的 run 还活不活着，前台/后台一视同仁。
        // `inForeground` 仍然照常传给 [traceLight]，日志里的 `fg=self|other` 不丢。
        // 外观页的开关是最后一道门：关掉只是不显示，驱动判定与自动返回照旧。
        val edgeEnabled = edgeAllowed(inForeground)
        val target = if (sessionAlive && edgeEnabled) {
            FX_ALPHA_HOT
        } else {
            0f
        }
        traceLight(app, reason, target, sessionAlive, running, now, inForeground)
        // 目标值而不是开关：view 自己逐帧缓动逼近，所以亮起/熄灭都是斜坡，不会「啪」地闪。
        val fx = (if (target > 0f) overlay(app) else view)
        // Explicitly disabling the feature clears both the breath and any in-flight flash.
        fx?.setEdgeEnabled(edgeEnabled)
        fx?.setTarget(target)
        // **停摆兜底**（issue #7）：窗口脱离窗口树之后 `postInvalidateDelayed` 不再排帧，
        // `onDraw` 停摆、`drawnAlpha` 冻在最后一帧 —— 日志里 `target=0.0 alpha=1.0` 挂几分钟就是它。
        // 正常淡出 0.8 秒，所以「目标归零超过 [FX_STALL_MS] 还亮着」只能是没在画，直接清零。
        if (target > 0f) {
            targetZeroAt = 0L
        } else {
            if (targetZeroAt == 0L) targetZeroAt = now
            if (fx != null && now - targetZeroAt > FX_STALL_MS && fx.edgeAlpha > 0.01f) {
                val stuck = fx.edgeAlpha
                fx.reset()
                // **拆窗口，不只是清 alpha**（issue #8 建议 4）：`onDraw` 都停摆了，`reset()` 里那句
                // `invalidate()` 同样不会触发重绘 —— 光留在屏幕上。拆掉之后下一次要亮时
                // `overlay()` 会建一块新画布，那是唯一保证「状态与屏幕一致」的路径。
                release("目标归零后 ${now - targetZeroAt}ms 仍未熄灭（alpha=$stuck）")
                trace(app, "entry=stalled-reset alpha=$stuck 停了 ${now - targetZeroAt}ms")
            }
        }
        return target > 0f
    }

    /**
     * 判死用的一行：回合的输入 + 结论 + 是谁问的。
     *
     * `session` 是**真正决定灯亮不亮的那个判据**（驱动过屏幕的 run 还活着），不是 20 秒静默窗 ——
     * 两者从 issue #2 起就不是同一件事，日志里写错一个就会把下一次排查引到错的分支上。`sinceUi` 是
     * 距上次 UI 操作的毫秒数、`quiet=20000ms` 是同一行的对照值（它现在只划日志边界，见 [episodeEnd]）。
     * `drivingAlive` 与 `session` 同源，留着只为对照 `running` / `drivingIds` 两个集合的大小。
     *
     * 变化时必写，另外每 [FX_LIGHT_TRACE_MS] 写一次心跳 —— 一次复现就能看出「谁把灯点亮的」和
     * 「谁没把它熄掉」。Main thread only, like [apply].
     */
    private fun traceLight(
        app: Context,
        reason: String,
        target: Float,
        session: Boolean,
        running: Set<String>,
        now: Long,
        foreground: Boolean,
    ) {
        if (target == lastTarget && now - lastLightTraceAt < FX_LIGHT_TRACE_MS) return
        lastTarget = target
        lastLightTraceAt = now
        trace(
            app,
            // target/alpha 一起写：target 是"该多亮"，alpha 是"现在多亮"。两者分开才看得出缓动在不在
            // 走 —— 只写一个的话，「目标一直是 0.26 却看着在闪」这种报障没有判据。
            "target=$target alpha=${view?.edgeAlpha ?: 0f} session=$session reason=$reason " +
                "foreground=$foreground sinceUi=${if (lastUiAt == 0L) "-" else "${now - lastUiAt}ms"} " +
                "quiet=${EPISODE_QUIET_MS}ms " +
                "drivingAlive=${drivingIds.any { it in running }} running=${running.size} " +
                "drivingIds=${drivingIds.size}",
        )
    }

    /**
     * JAgent自己在前台吗 —— **只服务日志与自动返回**，不再是亮灯的门（issue #6 拆掉了那道门）。
     *
     * 进程级判定，所以无障碍服务和前台服务都不会让它保持 STARTED：STARTED 就是「用户正看着
     * JAgent」，而不是「JAgent在跑」。Main thread only.
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
            val running = Core.engine.runsFlow.value.keys
            val departed = previousIds - running
            previousIds = running.toSet()
            // A run that was operating the phone has just ended, and no other driving run is left:
            // that is the moment the user asked to be handed their phone back, so it happens on
            // this tick rather than after a wait. A run that merely shared the screen with the one
            // that drove does not earn the trip on its own.
            val drivingLeft = departed.any { it in drivingIds }
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
            // 整轮结束 ⇒ 立刻收尾，不等 3 秒静默（spec v2 用例 ⑦）。ticker 一秒一跳，所以最坏一秒内
            // 灭到 0 —— 这比让引擎反向回调进来少一条依赖。
            if (running.isEmpty()) endEpisode("run-idle")
            val lit = apply("tick")
            // 亮着就还要人熄灯（静默 3 秒），前台里虽然不亮、但活着的驱动 run 还等着离场（自动返回
            // 就靠这一 tick 发现），所以两种都要继续；只有「灭 + 没有活着的驱动 run」才停表。
            // **停表条件不能看 `view.edgeAlpha`**（issue #8）：屏幕上可能挂着一块孤儿窗口，而 `view`
            // 指向的是另一个实例 —— 读到 0 就停表，`apply()` 的停摆兜底从此成了死代码。
            // 现在只要**相信窗口还在**（[windowUp]）就继续跳；1 秒一跳的代价可以忽略。
            if (lit || drivingIds.any { it in running }) {
                main.postDelayed(this, FX_BREATH_TICK_MS)
            } else if (windowUp) {
                // 窗口常驻、没有会话：放慢到 5 秒一跳。跳的意义只有一个 —— 让 [apply] 还有机会
                // 发现「目标归零却还亮着」（issue #7 的兜底）和「removeView 失败留下的孤儿窗口」。
                main.postDelayed(this, FX_IDLE_TICK_MS)
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
            watchSettings()
            val fx = overlay(app)
            fx?.setEdgeEnabled(edgeAllowed(foreground()))
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
        // 回合的开启/续期/结束已收口到引擎分发处的单一埋点（executeOneTool → noteToolActivity），
        // 这里只写日志行 —— 否则同一次 run_shell 会 arm 两遍。
    }

    /**
     * Appends one line to `filesDir/fx-trace.log`.
     *
     * Source names the explicit event, not an expensive caller stack. Event fields are captured
     * on the calling thread; formatting and file work happen on [traceWorker].
     */
    private fun trace(context: Context, line: String, episode: String? = null) {
        val app = context.applicationContext
        val overlay = view?.let { "up" } ?: lastOverlayNote ?: "none"
        // episode 只在转折点上给（start/extend/end）；其它行写的是此刻的 latch 状态。
        val edge = episode ?: if (view?.lit == true) "live" else "off"
        val at = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val foreground = lastForeground
        val alpha = view?.edgeAlpha ?: 0f
        val runCount = Core.engine.runsFlow.value.size
        // Event/tool fields are retained. Explicit event provenance replaces a full stack capture.
        val source = line.substringBefore(' ')
        traceWorker.execute {
            runCatching {
                val entry = "${traceStamp(at, zone)} $line source=event:$source overlay=$overlay " +
                    "episode=$edge fg=${if (foreground) "self" else "other"} " +
                    "alpha=${String.format(Locale.US, "%.2f", alpha)} runs=$runCount"
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

    /** Logs a change of overlay state, and only a change: a tick failing every second writes once. */
    private fun noteOverlay(context: Context, note: String) {
        if (lastOverlayNote == note) return
        lastOverlayNote = note
        trace(context, "overlay=$note")
    }

    private fun flashEdge(fx: FxView) {
        // 总开关关掉时**一律不显示**（用户 2026-10-23）：`onDraw` 里的边光只看
        // `edgeStart`，不看总开关，所以这里必须拦 —— 否则关掉之后每次 UI 操作
        // 还会闪 350ms。前台开关同理。
        if (!edgeAllowed(foreground())) return
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
        val fx = FxView(host, bounds, screenCornerRadius(host)) { displayBounds(it) }
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
        windowUp = true
        // 新画布，「该不该亮」不是：窗口被收走 / 服务重连 / 跳转重建之后新 view 的灯是关的，只有按
        // 当前回合状态立刻对一遍，光才不会跟着旧画布一起消失。这里直接算、直接点亮（不经过 [apply]，
        // 免得 [apply] → 建窗 → 再 [apply] 绕回来），所以 ticker 停了也照样亮。
        val now = SystemClock.uptimeMillis()
        val running = Core.engine.runsFlow.value.keys
        val inForeground = foreground()
        lastForeground = inForeground
        // 新画布，「该多亮」不是：窗口被收走 / 服务重连 / 跳转重建之后新 view 的灯是关的，只有按
        // 当前状态立刻对一遍，光才不会跟着旧画布一起消失。这里直接算、直接给目标（不经过 [apply]，
        // 免得 [apply] → 建窗 → 再 [apply] 绕回来），所以 ticker 停了也照样亮。
        // 与 [apply] 同一条会话判定（issue #2）：驱动过屏幕的 run 还活着就亮，静默和非 UI 工具都不参与。
        val sessionAlive = drivingIds.any { it in running }
        // 前台不压制（issue #6）：与 [apply] 同一口径，窗口重建后不能又暗下去。
        // 外观页开关同理（关掉时窗口重建也不该亮）。
        val target = if (sessionAlive && edgeLightVisible(edgeLightOn, edgeLightInForeground, inForeground)) {
            FX_ALPHA_HOT
        } else {
            0f
        }
        traceLight(context, "overlay", target, sessionAlive, running, now, inForeground)
        fx.setTarget(target)
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

}
