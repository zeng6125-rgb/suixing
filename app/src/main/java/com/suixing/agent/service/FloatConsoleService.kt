package com.suixing.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.core.Core
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A floating console for the run that is in progress: a ball that expands into a
 * panel showing how the agent is doing, with a stop button and a box to say
 * something to it mid-task.
 *
 * It exists because the interesting part of an agent run happens on *other* apps'
 * screens. Watching it work means leaving JAgent, and leaving means the stop button is
 * two taps away — so the two controls that matter live here instead, over whatever
 * is on screen.
 *
 * Everything it does goes through the engine's own interface ([Core.engine]'s
 * `runState`, `cancel()`, `steer()` and `liveLine`): the console owns no state of its own, so it
 * cannot disagree with the chat screen about what is happening. Its panel's own box is the one
 * exception, and it is not really one: a line typed there while nothing is running becomes the
 * conversation's next turn through the very [AgentEngine.startRun] the chat screen calls, with the
 * same registry, gate and stores — a run started here shows up in the chat as any other does.
 */
class FloatConsoleService : Service() {

    companion object {
        /** 自停复核延迟：覆盖 AgentForegroundService 启动 FCS → startRun 注册之间的挂起点。 */
        private const val STOP_CHECK_MS = 1500L

        private const val CHANNEL_ID = "Suixing_console"
        private const val NOTIFICATION_ID = 1202

        /** The order the settings screen offers: follow the system, light, dark. */
        internal const val THEME_SYSTEM = 0
        internal const val THEME_DARK = 2

        /**
         * The floating ball is the delivered icon artwork itself: the motion frames carry
         * their own cream rounded-square tile, so no coloured disc is drawn behind them.
         */
        /** The face loops play at this size inside the existing touch target. */
        internal const val BALL_SIZE_DP = 42
        internal const val TOUCH_TARGET_DP = 48

        /** The panel's width: the default, and the range the resize handle may drag it through. */
        internal const val PANEL_DEFAULT_DP = 268
        internal const val PANEL_MIN_DP = 200
        internal const val PANEL_MAX_DP = 420

        /**
         * 面板高度的可拖范围（dp）。用户拖过手柄后，面板从「随内容自适应」切成这个固定高度，
         * 内部主体包在 ScrollView 里 —— 拖得比内容矮也能滚到 stop / 发送，不会裁掉。
         * 0（默认）＝自适应，不写死高度，idle 时保持紧凑。
         */
        internal const val PANEL_MIN_HEIGHT_DP = 160
        internal const val PANEL_MAX_HEIGHT_DP = 640

        /** How much of a message the panel's summary keeps, per line. */
        /** 每条消息在悬浮窗里最多截多少字（内容区可滚后放宽，长答也能看个大概）。 */
        internal const val MESSAGE_PREVIEW_CHARS = 160

        /** 悬浮窗最多回显最近几条会话消息（内容区可滚，用户拖高就能看全这几条）。 */
        internal const val RECENT_MESSAGE_COUNT = 6

        /** The engine publishes a running tool as 「🔧 name…」 on [AgentEngine.RunHandle.liveLine]. */
        internal const val TOOL_LIVE_PREFIX = "🔧 "

        /** 诊断日志：用户报「球不出现」时，这一个文件就能分辨三种完全不同的原因。 */
        private const val CONSOLE_TRACE_FILE = "console-trace.log"

        /**
         * 上限 256KB、截断后保留尾部 128KB（外加头部 2KB）。
         *
         * 选「单文件 + 头尾保留」而不是按天清理：这个日志只在排查时有用，最需要的是**用户复现的
         * 那一次**，而按天切分会让「今天的文件里只有一半」这种问题再出现一次；256KB 按每行约 130
         * 字节算约 2000 行，够覆盖若干次开关 + 切前后台 + 跑任务，且永远不会撑爆用户存储。
         */
        private const val CONSOLE_TRACE_MAX_BYTES = 256 * 1024
        private const val CONSOLE_TRACE_KEEP_BYTES = 128 * 1024
        private const val CONSOLE_TRACE_HEAD_BYTES = 2 * 1024

        /** Overlay windows are the user's to grant; without it there is nothing to show. */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun overlayPermissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        /**
         * Brings the console up.
         *
         * @return false when no host can carry the window at all: the overlay permission has not
         *   been granted *and* the accessibility service is not connected either. With the
         *   accessibility host the grant is not needed — its window is granted by virtue of the
         *   service being enabled — so a device with the service on can show the ball without it.
         */
        fun start(context: Context): Boolean {
            if (!canDrawOverlays(context) && SuixingAccessibilityService.instance() == null) return false
            val intent = Intent(context, FloatConsoleService::class.java)
            return runCatching { context.startForegroundService(intent) }.isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatConsoleService::class.java)) }
        }

        /**
         * 服务此刻在不在。
         *
         * 给「回到前台时补一次启动」用：常驻开着而服务已经被系统回收时，没有任何东西在画球，
         * 而设置里那个开关还亮着 —— 这一对不上就是用户看到的「明明开着却不显示」。
         */
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        internal fun markRunning(value: Boolean) {
            running = value
        }
    }

    /** The overlay's colours, taken from the app's own theme so it does not look foreign. */
    internal class Palette(
        val surface: Int,
        val field: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val stroke: Int,
    )

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The trace file is written from its own thread: a log must never hold up a frame. */
    private val traceWorker = Executors.newSingleThreadExecutor()

    internal lateinit var palette: Palette
    internal lateinit var windowManager: WindowManager
    internal lateinit var params: WindowManager.LayoutParams

    /** Which kind of overlay window is up: accessibility (preferred) or application. */
    private var windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    internal lateinit var root: LinearLayout
    internal lateinit var panel: LinearLayout
    internal lateinit var ballMotionView: ImageView
    internal lateinit var stateText: TextView
    internal lateinit var liveText: TextView
    internal lateinit var statsText: TextView
    internal lateinit var conversationText: TextView
    /** 尾部读取闸门与最近一次结果（issue #69 P1-A）：同内容重复发射不再读盘。 */
    internal val tailGate = TailReadGate()
    internal var tailCache: List<com.suixing.agent.data.StoredMessage> = emptyList()

    internal lateinit var recentText: TextView

    /** 会话内容的可滚容器：手柄纵向拖动改的就是它的高度（用户拖高＝多看内容）。 */
    internal lateinit var contentScroll: ScrollView
    internal lateinit var steerInput: EditText
    internal lateinit var sendButton: TextView
    internal lateinit var stopArea: LinearLayout
    internal lateinit var feedbackText: TextView

    /** 底部动作行的「停止」按钮：单 run 时显示在「回到对话」右侧，多 run 时隐藏（改走标题行）。 */
    internal lateinit var stopButton: TextView

    private var initializing = false
    internal var settingsLoaded = false
    internal var attached = false
    internal lateinit var ballMotion: BallMotion
    internal var stopAreaKey: List<Triple<String, Long, String>>? = null
    internal var conversationRenderRevision = 0L

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    internal lateinit var ballTouchFeedback: FloatBallTouchFeedback

    /** The resize gesture: where the finger went down, and how wide the panel was then. */
    internal var resizeStartX = 0f
    internal var resizeStartWidth = 0

    /** 同一手柄的纵向分量：落点与当时的高（0＝自适应模式下从当前实测高度起算）。 */
    internal var resizeStartY = 0f
    internal var resizeStartHeight = 0
    internal var resizeMinWidth = 0
    internal var resizeMaxWidth = 0
    internal var resizeMinHeight = 0
    internal var resizeMaxHeight = 0

    /**
     * The three settings this service reads synchronously, kept as one readable copy.
     *
     * [SettingsRepository] offers a cold flow and a suspending `current()`, and every reader here —
     * a touch listener, a lifecycle callback, the layout pass — runs where neither can be awaited.
     * The collector in [observeRun] is the only writer and it writes on every change, so this copy
     * is never more than one emission behind.
     */
    internal data class ConsoleSettings(
        val resident: Boolean = false,
        val whileRunning: Boolean = true,
        val widthDp: Int = 0,
        val heightDp: Int = 0,
    )

    internal var consoleSettings = ConsoleSettings()

    // **自停要延迟并复核**（issue #56 R2）。FCS 由 AgentForegroundService 在
    // `engine.startRun()` **之前**启动，中间隔着 saveSession()/settings.current() 等挂起点；
    // 服务起来后 settingsLoaded 一置真就会调 refreshVisibility()，此刻 runsFlow 还是空的
    // ⇒ `running == false` 且默认 `resident == false` ⇒ 立刻 stopSelf()，而整轮 run
    // 期间没有任何补启路径（MainActivity 那处要回前台才触发）⇒ 悬浮球整轮不出现。
    // 这里改成「延迟 + 复核」：运行态在延迟内注册进来就撤销这次自停。
    internal val mainHandler = Handler(Looper.getMainLooper())
    private var stopCheckPosted = false
    private val stopCheck = Runnable {
        stopCheckPosted = false
        val stillIdle = Core.engine.runsFlow.value.isEmpty()
        if (settingsLoaded && !consoleSettings.resident && stillIdle) stopSelf()
    }

    /** The system's own threshold, so a drag feels the same here as anywhere else. */
    private val touchSlop: Float by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    /**
     * The console's visibility, re-decided on every lifecycle change and every run change.
     *
     * **In front of JAgent it is never shown**, whatever the switches say: the chat screen already
     * has the same stop button and the same input box, so a ball over that screen is one more
     * thing covering the answer. Everything below applies only once the user has left.
     *
     * Behind that, two switches, both the user's: 常驻 ([AppSettings.floatingConsole], the drawer
     * row's own switch) keeps the ball there whether or not anything is running;
     * [AppSettings.consoleWhileRunning] shows it only while a run is in flight, so the run ending
     * is what takes it back down. With neither of them on, the background shows nothing at all.
     *
     * Whether the service is running stays the drawer row's business: these switches only choose
     * what the user sees while it does.
     */
    private val appLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = refreshVisibility()

        override fun onStop(owner: LifecycleOwner) = refreshVisibility()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Core.startup.backgroundStarting()
        markRunning(true)
        ensureChannel()
        startForegroundCompat()
        if (!::root.isInitialized && !initializing) {
            initializing = true
            scope.launch {
                try {
                // Read once, as the console appears: the theme belongs to the user, and
                // re-reading it mid-run would repaint a window they are looking at.
                val settings = Core.settings.current()
                palette = paletteFor(settings.themeMode, settings.paletteMode)
                buildConsole()
                observeRun()
                watchAccessibility()
                ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycle)
                trace("service=start")
                // 常驻 shows the ball as soon as the console is up *and* the user has left JAgent;
                // in front of the app it is never shown. With only 「运行时显示」 ticked this
                // first decision is "stay hidden", and the observer above catches the moment the
                // user leaves — or a run starting while they are away.
                refreshVisibility()
                } finally { initializing = false }
            }
        } else if (::root.isInitialized) {
            refreshVisibility()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 这一行是「常驻静默失效」的唯一证据：被谁销毁、当时常驻是不是还开着，尾部字段都会写下来。
        trace("service=stop")
        markRunning(false)
        if (::root.isInitialized) ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycle)
        if (::ballMotion.isInitialized) ballMotion.onDetach()
        mainHandler.removeCallbacks(stopCheck)
        stopCheckPosted = false
        scope.cancel()
        detach()
        // 已经排队的日志照写（shutdown 不是 shutdownNow），之后这条线程自己退出：服务实例死了，
        // 线程不该还留着。
        traceWorker.shutdown()
        super.onDestroy()
    }

    /**
     * 用户从最近任务里划掉了JAgent。
     *
     * 常驻时这不是结束：服务还在、球还在，只有这一行日志说明「刚才被划过一次」——把「球突然没了」
     * 与「用户自己划掉了任务」分开。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        trace("service=taskRemoved（最近任务被划掉）")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 旋转 / 分屏 / 折叠：窗口宽变了，面板的宽度与位置都要重新夹一次。
     *
     * 面板宽度是「用户拖的宽，但不超过窗口宽 − 24dp」：横屏时窗口更宽不用管，分屏或折叠成窄窗口
     * 时原来那个宽度就会顶出屏幕，所以这里重算并重新夹住 x。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resetBallTouchFeedback()
        if (!::root.isInitialized) return
        trace("config width=${newConfig.screenWidthDp}dp")
        applyPanelWidth()
        // 旋转/分屏后窗口高也变了：用户拖的固定高度可能超出新窗口，重新夹一次。
        applyPanelHeight()
        clampX(panelWidth())
        if (attached) runCatching { windowManager.updateViewLayout(root, params) }
    }

    // ------------------------------------------------------------------ 窗口

    /**
     * Builds the views, and the window they live in.
     *
     * Which kind of window that is decided by [createWindow], and re-decided whenever the
     * accessibility service connects or goes away ([watchAccessibility]).
     */
    private fun buildConsole() {
        stopAreaKey = null
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = buildPanel()
        root.addView(buildBall())
        root.addView(panel)
        createWindow()
    }

    /**
     * The host the window is added from, and which kind of window it is.
     *
     * `TYPE_APPLICATION_OVERLAY` needs SYSTEM_ALERT_WINDOW **and** is the kind MIUI and its kin hide
     * the moment the app leaves the foreground — which is the only moment this console is shown at
     * all, so on that ROM the ball could never be seen however the switches were set. An
     * accessibility service's own context is a system-trusted host: `TYPE_ACCESSIBILITY_OVERLAY`
     * needs no permission and is not hidden in the background. The application window stays as the
     * fallback for a device with no accessibility service connected.
     */
    private fun windowHost(): Pair<Context, Int> {
        val service = SuixingAccessibilityService.instance()
        return if (service != null) {
            service to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    private fun createWindow() {
        val oldX = if (::params.isInitialized) params.x else dp(12)
        val oldY = if (::params.isInitialized) params.y else dp(180)
        val (host, type) = windowHost()
        // The host's own WindowManager: an accessibility overlay belongs to that service's window
        // stack, and every later updateViewLayout goes through the same one.
        windowManager = host.getSystemService(WindowManager::class.java)
            ?: getSystemService(WindowManager::class.java)
        windowType = type
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Not focusable while collapsed, so the ball never steals the keyboard from
            // the app underneath; not touch-modal, so taps beside it still reach that app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = oldX
            y = oldY
        }
        trace("window host=${if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) "accessibility" else "application"}")
    }

    /**
     * Rebuilds the window when the accessibility service appears or goes away.
     *
     * The host cannot be switched on a live window: a window belongs to the stack it was added to,
     * so the old one is taken down and the new one is built in its place — and put back up if it was
     * up, or the console would vanish on the user when they enable the service.
     */
    private fun watchAccessibility() {
        scope.launch {
            Core.capabilities.state.collect { state ->
                val wanted = if (state.accessibility) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                if (wanted == windowType) return@collect
                val wasAttached = attached
                detach()
                createWindow()
                if (wasAttached) attach()
            }
        }
    }

    private fun attach() {
        if (attached) return
        // The flag follows what the window manager actually did. A window the system refused
        // (the permission was revoked between the check and now) must not be remembered as
        // attached, or the console would stay invisible for the rest of the run.
        val added = runCatching { windowManager.addView(root, params) }
        attached = added.isSuccess
        if (attached) ballMotion.onAttach()
        val error = added.exceptionOrNull()
        trace(
            "attach=${if (attached) "ok" else "failed"} " +
                "host=${if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) "accessibility" else "application"}" +
                if (error == null) "" else " error=${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private fun detach() {
        resetBallTouchFeedback()
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
        // Stop the eye loop with the window it draws into. A delayed frame callback against a
        // detached view would keep invalidating an overlay that is no longer on screen.
        if (::ballMotion.isInitialized) ballMotion.onDetach()
        trace("detach")
    }

    /**
     * Whether the console is on screen right now — see [appLifecycle] for what each switch means.
     *
     * Read from the synchronous copy of the settings rather than from `current()`, which is
     * suspending and cannot be awaited from a lifecycle callback or a touch listener.
     */
    internal fun refreshVisibility() {
        val settings = consoleSettings
        val lifecycle = ProcessLifecycleOwner.get().lifecycle.currentState
        val foreground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
        val running = Core.engine.runsFlow.value.isNotEmpty()
        // 前台一律不显示；不在前台时：常驻 ⇒ 一直显示，否则看 B（运行时才显示）。
        val visible = !foreground &&
            (settings.resident || (settings.whileRunning && running))
        trace(
            "state lifecycle=$lifecycle foreground=$foreground running=$running " +
                "resident=${settings.resident} whileRunning=${settings.whileRunning} visible=$visible attached=$attached",
        )
        if (visible) attach() else detach()
        // A transient console has no further work once its final run has ended —— 但只能
        // 延迟复核后再停（见 stopCheck 的注释：run 可能还没注册进来）。
        if (settingsLoaded && !settings.resident && !running) {
            if (!stopCheckPosted) {
                stopCheckPosted = true
                mainHandler.postDelayed(stopCheck, STOP_CHECK_MS)
            }
        } else {
            if (stopCheckPosted) {
                mainHandler.removeCallbacks(stopCheck)
                stopCheckPosted = false
            }
        }
    }

    /**
     * One line per decision, appended to `filesDir/console-trace.log`.
     *
     * 「球不出现」 has three completely different causes — the service never started, the visibility
     * rule decided no, or the window manager refused the window — and from the phone they look
     * identical. This file tells them apart: the first line is `service=start`, the rule prints all
     * four of its inputs, and a refused window prints the exception. Keep it small, it is written
     * from a phone.
     */
    internal fun trace(line: String) {
        val at = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val accessibility = SuixingAccessibilityService.instance() != null
        val resident = consoleSettings.resident
        val runCount = Core.engine.runsFlow.value.size
        traceWorker.execute {
            runCatching {
                // Permission is checked on the worker; other fields describe the queued event.
                val entry = "${traceStamp(at, zone)} $line canDrawOverlays=${Settings.canDrawOverlays(this)} " +
                    "accessibility=$accessibility resident=$resident runs=$runCount\n"
                val file = File(filesDir, CONSOLE_TRACE_FILE)
                if (file.length() > CONSOLE_TRACE_MAX_BYTES) {
                    // 头尾都留：尾部是刚发生的事，而头部那条 `service=start` 是判读的起点 ——
                    // 只留尾部会把起点先丢掉，那正是「球不出现」最需要的一行。
                    val bytes = file.readBytes()
                    val head = utf8Prefix(bytes, CONSOLE_TRACE_HEAD_BYTES)
                    val tail = utf8Suffix(bytes, CONSOLE_TRACE_KEEP_BYTES)
                    file.writeText("$head\n— 中间已截断（上限 ${CONSOLE_TRACE_MAX_BYTES / 1024}KB）—\n$tail")
                }
                file.appendText(entry)
            }
        }
    }


    internal fun resetBallTouchFeedback() {
        if (::ballTouchFeedback.isInitialized) ballTouchFeedback.reset()
    }

    /**
     * Dragging moves the window; a touch that does not move opens the panel.
     *
     * The same view has to do both because a floating ball that needs a separate
     * handle for each gesture is worse than one that guesses: the system's own touch
     * slop decides which of the two the user meant.
     */
    internal fun onBallTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            touchStartX = event.rawX
            touchStartY = event.rawY
            windowStartX = params.x
            windowStartY = params.y
            ballTouchFeedback.press()
            if (::ballMotion.isInitialized) ballMotion.playTap()
            true
        }

        MotionEvent.ACTION_MOVE -> {
            if (ballTouchFeedback.active) {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!ballTouchFeedback.dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    ballTouchFeedback.drag()
                }
                if (ballTouchFeedback.dragging) {
                    params.x = windowStartX + dx.toInt()
                    params.y = windowStartY + dy.toInt()
                    // 拖拽期两个方向都要夹（issue #56 R11）：原来只夹 Y，X 可以拖出屏幕，
                    // 松手（ACTION_UP）才跳回来。`clampX` 内部已经含 `clampY`。
                    clampX(if (panel.visibility == View.VISIBLE) panelWidth() else 0)
                    if (attached) runCatching { windowManager.updateViewLayout(root, params) }
                }
            }
            true
        }

        MotionEvent.ACTION_UP -> {
            val wasDragging = ballTouchFeedback.dragging
            if (ballTouchFeedback.release()) {
                setExpanded(panel.visibility != View.VISIBLE)
            } else if (wasDragging) {
                // The ball may be dropped anywhere; the panel is what has to stay reachable.
                clampX(if (panel.visibility == View.VISIBLE) panelWidth() else 0)
                if (attached) runCatching { windowManager.updateViewLayout(root, params) }
            }
            true
        }

        MotionEvent.ACTION_CANCEL -> {
            ballTouchFeedback.cancel()
            true
        }

        else -> false
    }

    // ------------------------------------------------------------------ 状态

    /** The panel covers the chat screen, so it also has to be able to get back to it. */
    internal fun openChat() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { pending.send() }
    }

    internal fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        runCatching { manager.hideSoftInputFromWindow(steerInput.windowToken, 0) }
    }


    // ------------------------------------------------------------ 前台服务

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "悬浮控制台", NotificationManager.IMPORTANCE_MIN).apply {
            description = "JAgent悬浮控制台的常驻通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 1202, not 0: SchedulerService's reminder notification builds the same
        // `Intent(this, MainActivity)` under request code 0, and PendingIntent identity ignores
        // an intent's flags — sharing the code would let whichever notification was built last
        // overwrite the other's intent (the reminder's NEW_TASK|CLEAR_TOP included).
        val open = PendingIntent.getActivity(
            this,
            1202,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle("JAgent控制台")
            .setContentText("悬浮球已就位：点开可停止或插话")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
