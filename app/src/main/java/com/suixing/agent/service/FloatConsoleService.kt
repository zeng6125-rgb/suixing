package com.suixing.agent.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import com.suixing.agent.agent.RunState
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * A floating console for the run that is in progress: a ball that expands into a
 * panel showing how the agent is doing, with a stop button and a box to say
 * something to it mid-task.
 *
 * It exists because the interesting part of an agent run happens on *other* apps'
 * screens. Watching it work means leaving 随行, and leaving means the stop button is
 * two taps away — so the two controls that matter live here instead, over whatever
 * is on screen.
 *
 * Everything it does goes through the engine's own interface ([Core.engine]'s
 * `runState`, `cancel()`, `steer()` and `liveLine`): the console owns no state and
 * starts no runs, so it cannot disagree with the chat screen about what is happening.
 */
class FloatConsoleService : Service() {

    companion object {
        private const val CHANNEL_ID = "Suixing_console"
        private const val NOTIFICATION_ID = 1202

        /** The order the settings screen offers: follow the system, light, dark. */
        private const val THEME_SYSTEM = 0
        private const val THEME_DARK = 2

        /** The ball is brand-coloured in both themes, like the launcher icon it borrows. */
        private const val BALL_TOP = 0xFF0E2A1F.toInt()
        private const val BALL_BOTTOM = 0xFF245E45.toInt()
        private const val STATUS_RUNNING = 0xFF5FD8A0.toInt()
        private const val STATUS_IDLE = 0xFF9AA5A0.toInt()

        /** Overlay windows are the user's to grant; without it there is nothing to show. */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun overlayPermissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

        /**
         * Brings the console up.
         *
         * @return false when the overlay permission has not been granted, in which case
         *   nothing is started at all — a window the system will reject is not worth
         *   asking for.
         */
        fun start(context: Context): Boolean {
            if (!canDrawOverlays(context)) return false
            val intent = Intent(context, FloatConsoleService::class.java)
            return runCatching { context.startForegroundService(intent) }.isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatConsoleService::class.java)) }
        }
    }

    /** The overlay's colours, taken from the app's own theme so it does not look foreign. */
    private class Palette(
        val surface: Int,
        val field: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val stroke: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var palette: Palette
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var statusDot: View
    private lateinit var stateText: TextView
    private lateinit var liveText: TextView
    private lateinit var statsText: TextView
    private lateinit var steerInput: EditText

    private var attached = false
    private var dotPulse: ValueAnimator? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var dragging = false

    /** The system's own threshold, so a drag feels the same here as anywhere else. */
    private val touchSlop: Float by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    /**
     * The console is only worth showing once the user has left 随行: the chat screen has
     * the same stop button and the same input box, and a ball floating over the very
     * screen that already shows the run is one more thing covering the answer.
     */
    private val appLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = detach()

        override fun onStop(owner: LifecycleOwner) = attachIfRunning()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat()
        if (!::root.isInitialized) {
            scope.launch {
                // Read once, as the console appears: the theme belongs to the user, and
                // re-reading it mid-run would repaint a window they are looking at.
                palette = paletteFor(Core.settings.current().themeMode)
                buildConsole()
                observeRun()
                ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycle)
                // A run normally starts from the app, so the first decision is almost always
                // "stay hidden"; the observer above catches the moment the user leaves.
                attachIfRunning()
            }
        } else {
            attachIfRunning()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::root.isInitialized) ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycle)
        dotPulse?.cancel()
        scope.cancel()
        detach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 窗口

    /**
     * Builds the window and the views but does not attach them: whether the console is
     * visible at all depends on where the user is, which [attachIfRunning] decides.
     */
    private fun buildConsole() {
        windowManager = getSystemService(WindowManager::class.java)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable while collapsed, so the ball never steals the keyboard from
            // the app underneath; not touch-modal, so taps beside it still reach that app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = buildPanel()
        root.addView(buildBall())
        root.addView(panel)
    }

    private fun attach() {
        if (attached) return
        runCatching { windowManager.addView(root, params) }
        attached = true
    }

    private fun detach() {
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    private fun attachIfRunning() {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!foreground && Core.engine.runState.value is RunState.Running) attach() else detach()
    }

    /**
     * A 52dp brand disc with the launcher's feather on it, and a dot that says whether a
     * run is live. Drawn from views rather than Compose: this window lives outside any
     * activity, so there is no composition to host it.
     */
    private fun buildBall(): FrameLayout {
        val size = dp(52)
        val ball = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(BALL_TOP, BALL_BOTTOM),
            ).apply { shape = GradientDrawable.OVAL }
            // A real shadow needs an outline: without one the window draws as a flat disc.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setOval(0, 0, view.width, view.height)
            }
            elevation = dp(6).toFloat()
            setOnTouchListener { view, event -> onBallTouch(view, event) }
        }
        ball.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
            },
        )
        statusDot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(8), dp(8), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(3)
                bottomMargin = dp(3)
            }
        }
        ball.addView(statusDot)
        return ball
    }

    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.stroke)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(dp(268), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    TextView(context).apply {
                        text = "随行控制台"
                        setTextColor(palette.accent)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                addView(collapseButton())
            },
        )

        stateText = TextView(context).apply {
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "空闲"
        }
        addView(stateText)

        // One line, always: it changes many times a second, and a box that grows and
        // shrinks with every delta is unreadable.
        liveText = TextView(context).apply {
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        addView(liveText)

        statsText = TextView(context).apply {
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(10))
        }
        addView(statsText)

        steerInput = EditText(context).apply {
            hint = "对它说一句…"
            setHintTextColor(palette.muted)
            setTextColor(palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(palette.field)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(steerInput)
                addView(actionButton("插话", accent = true) { sendSteering() })
            },
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(actionButton("回到对话") { openChat() })
                addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    },
                )
                addView(actionButton("停止", accent = true) { stopRun() })
            },
        )
    }

    private fun collapseButton(): TextView = TextView(this).apply {
        text = "收起"
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(8), 0, 0, 0)
        setOnClickListener { setExpanded(false) }
    }

    private fun actionButton(label: String, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(if (accent) palette.onAccent else palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (accent) palette.accent else palette.field)
            }
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /**
     * Dragging moves the window; a touch that does not move opens the panel.
     *
     * The same view has to do both because a floating ball that needs a separate
     * handle for each gesture is worse than one that guesses: the system's own touch
     * slop decides which of the two the user meant.
     */
    private fun onBallTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            touchStartX = event.rawX
            touchStartY = event.rawY
            windowStartX = params.x
            windowStartY = params.y
            dragging = false
            true
        }

        MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - touchStartX
            val dy = event.rawY - touchStartY
            if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
            if (dragging) {
                params.x = windowStartX + dx.toInt()
                params.y = windowStartY + dy.toInt()
                if (attached) runCatching { windowManager.updateViewLayout(root, params) }
            }
            true
        }

        MotionEvent.ACTION_UP -> {
            if (!dragging) setExpanded(panel.visibility != View.VISIBLE)
            true
        }

        else -> false
    }

    /**
     * Expanding makes the window focusable, which is the only way an overlay's text
     * field can receive typing; collapsing hands the keyboard back to the app behind.
     */
    private fun setExpanded(expanded: Boolean) {
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        val flags = params.flags
        params.flags = if (expanded) {
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = if (expanded) {
            // ADJUST_RESIZE is deprecated for activities in favour of WindowInsets, but
            // an overlay window is not an activity and has no decor to fit: this is
            // still the only way to keep the input box above the keyboard.
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (attached) runCatching { windowManager.updateViewLayout(root, params) }
        if (!expanded) hideKeyboard()
    }

    // ------------------------------------------------------------------ 状态

    /**
     * Mirrors the engine's run state into the panel.
     *
     * The state comes from [Core.engine] rather than from anything local, so the panel
     * and the chat screen can never tell two different stories about the same run.
     */
    private fun observeRun() {
        scope.launch {
            Core.engine.runState.collectLatest { state ->
                stateText.text = state.title()
                statsText.text = state.detail()
                // The live line is re-read on every state, and the engine ticks its state
                // while a run is in flight, so this stays current without a second stream.
                liveText.text = if (state is RunState.Running) Core.engine.liveLine else ""
                setStatusDot(state is RunState.Running)
            }
        }
    }

    private fun setStatusDot(running: Boolean) {
        dotPulse?.cancel()
        dotPulse = null
        statusDot.alpha = 1f
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) STATUS_RUNNING else STATUS_IDLE)
        }
        if (!running) return
        // A slow breath is the difference between "a run is live" and "this is just how the
        // ball looks"; it is slow on purpose so it reads as a pulse, not as a warning.
        dotPulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { statusDot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopRun() {
        val stopped = Core.engine.cancel()
        statsText.text = if (stopped) "已请求停止" else "当前没有进行中的任务"
    }

    private fun sendSteering() {
        val text = steerInput.text.toString().trim()
        if (text.isEmpty()) return
        val queued = Core.engine.steer(text)
        if (queued) {
            steerInput.setText("")
            statsText.text = "已插话，模型下一轮就能看到"
            hideKeyboard()
        } else {
            statsText.text = "当前没有进行中的任务，这句话没有发出"
        }
    }

    /** The panel covers the chat screen, so it also has to be able to get back to it. */
    private fun openChat() {
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

    private fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        runCatching { manager.hideSoftInputFromWindow(steerInput.windowToken, 0) }
    }

    private fun paletteFor(themeMode: Int): Palette {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = themeMode == THEME_DARK || (themeMode == THEME_SYSTEM && night)
        return if (dark) {
            Palette(
                surface = 0xF2151619.toInt(),
                field = 0xFF23252B.toInt(),
                ink = 0xFFF3F1EC.toInt(),
                muted = 0x8AF3F1EC.toInt(),
                accent = 0xFF8FD6B6.toInt(),
                onAccent = 0xFF10241B.toInt(),
                stroke = 0x338FD6B6,
            )
        } else {
            Palette(
                surface = 0xF2FFFFFF.toInt(),
                field = 0xFFF1F3F0.toInt(),
                ink = 0xDE1A1C1E.toInt(),
                muted = 0x8A1A1C1E.toInt(),
                accent = 0xFF1F6B4C.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                stroke = 0x1A1F6B4C,
            )
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

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
            description = "随行悬浮控制台的常驻通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spark)
            .setContentTitle("随行控制台")
            .setContentText("悬浮球已就位：点开可停止或插话")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}

/** One line for the panel's headline. */
private fun RunState.title(): String = when (this) {
    is RunState.Idle -> "空闲"
    is RunState.Running -> "正在执行"
    is RunState.Done -> "已完成"
    is RunState.Cancelled -> "已停止"
    is RunState.Failed -> "失败"
}

/** The second line: how fast, how long, or what went wrong. */
private fun RunState.detail(): String = when (this) {
    is RunState.Idle -> "没有进行中的任务"
    is RunState.Running -> stats.line()
    is RunState.Done -> stats.line()
    is RunState.Cancelled -> "已中断 · ${stats.line()}"
    is RunState.Failed -> message
}

/**
 * The third line: what went in, what came out, how fast, how long, how full the context
 * is, or what went wrong.
 *
 * The context figure is the endpoint's own prompt-token count, which makes it the one
 * number here that is not an estimate. The note rides along because the console is the
 * only place a compression can be seen while it is still news.
 */
private fun StreamStats.line(): String = buildString {
    append(
        String.format(
            Locale.US,
            "入 %d · 出 %d · 思考 %d · %.1f tok/s · %.1fs",
            lastPromptTokens,
            tokensOut,
            thinkingTokens,
            tokPerSec,
            elapsedMs / 1000.0,
        ),
    )
    if (lastPromptTokens > 0) {
        append(String.format(Locale.US, " · 上下文 %.1fk", lastPromptTokens / 1000.0))
    }
    if (note.isNotEmpty()) append(" · ").append(note)
}
