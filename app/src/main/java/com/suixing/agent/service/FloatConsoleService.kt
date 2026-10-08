package com.suixing.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
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
 * `runState`, `cancel()` and `steer()`): the console owns no state and starts no
 * runs, so it cannot disagree with the chat screen about what is happening.
 */
class FloatConsoleService : Service() {

    companion object {
        private const val CHANNEL_ID = "Suixing_console"
        private const val NOTIFICATION_ID = 1202

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

        /** 0xAARRGGBB — the app's own palette, so the overlay does not look foreign. */
        private const val COLOR_SURFACE = 0xF2151619.toInt()
        private const val COLOR_FIELD = 0xFF23252B.toInt()
        private const val COLOR_INK = 0xFFF3F1EC.toInt()
        private const val COLOR_MUTED = 0xFF9A9AA5.toInt()
        private const val COLOR_ACCENT = 0xFFC0AEFF.toInt()
        private const val COLOR_STROKE = 0x33C0AEFF
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var ball: TextView
    private lateinit var panel: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var statsText: TextView
    private lateinit var steerInput: EditText

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var dragging = false

    /** The system's own threshold, so a drag feels the same here as anywhere else. */
    private val touchSlop: Float by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat()
        if (!::root.isInitialized) showConsole()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (::root.isInitialized) {
            runCatching { windowManager.removeView(root) }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 窗口

    private fun showConsole() {
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
        ball = buildBall()
        panel = buildPanel()
        root.addView(ball)
        root.addView(panel)

        windowManager.addView(root, params)
        observeRun()
    }

    private fun buildBall(): TextView = TextView(this).apply {
        text = "随"
        setTextColor(COLOR_INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_SURFACE)
            setStroke(dp(1), COLOR_ACCENT)
        }
        val size = dp(46)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setOnTouchListener { view, event -> onBallTouch(view, event) }
    }

    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(COLOR_SURFACE)
            setStroke(dp(1), COLOR_STROKE)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(252), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    TextView(context).apply {
                        text = "随行控制台"
                        setTextColor(COLOR_ACCENT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                addView(collapseButton())
            },
        )

        stateText = TextView(context).apply {
            setTextColor(COLOR_INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "空闲"
        }
        addView(stateText)

        statsText = TextView(context).apply {
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(10))
        }
        addView(statsText)

        steerInput = EditText(context).apply {
            hint = "对它说一句…"
            setHintTextColor(COLOR_MUTED)
            setTextColor(COLOR_INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(COLOR_FIELD)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(steerInput)
                addView(actionButton("插话") { sendSteering() })
                addView(actionButton("停止", accent = true) { stopRun() })
            },
        )
    }

    private fun collapseButton(): TextView = TextView(this).apply {
        text = "收起"
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(8), 0, 0, 0)
        setOnClickListener { setExpanded(false) }
    }

    private fun actionButton(label: String, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(if (accent) COLOR_SURFACE else COLOR_INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (accent) COLOR_ACCENT else COLOR_FIELD)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
                runCatching { windowManager.updateViewLayout(root, params) }
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
        runCatching { windowManager.updateViewLayout(root, params) }
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
            }
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

    private fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        runCatching { manager.hideSoftInputFromWindow(steerInput.windowToken, 0) }
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
 * The second line: how fast, how long, how full the context is, or what went wrong.
 *
 * The context figure is the endpoint's own prompt-token count, which makes it the one
 * number here that is not an estimate. The note rides along because the console is the
 * only place a compression can be seen while it is still news.
 */
private fun StreamStats.line(): String = buildString {
    append(
        String.format(
            Locale.US,
            "输出 %d tok · 思考 %d · %.1f tok/s · %.1fs",
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
