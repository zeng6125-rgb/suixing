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
import android.graphics.PixelFormat
import android.graphics.Point
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
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.agent.RunState
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.core.Core
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * `runState`, `cancel()`, `steer()` and `liveLine`): the console owns no state of its own, so it
 * cannot disagree with the chat screen about what is happening. Its panel's own box is the one
 * exception, and it is not really one: a line typed there while nothing is running becomes the
 * conversation's next turn through the very [AgentEngine.startRun] the chat screen calls, with the
 * same registry, gate and stores — a run started here shows up in the chat as any other does.
 */
class FloatConsoleService : Service() {

    companion object {
        private const val CHANNEL_ID = "Suixing_console"
        private const val NOTIFICATION_ID = 1202

        /** The order the settings screen offers: follow the system, light, dark. */
        private const val THEME_SYSTEM = 0
        private const val THEME_DARK = 2

        /**
         * The ball's one colour: a flat brand green in both themes, because the icon on it is
         * white in both. It used to be a two-stop gradient plus a 6dp elevation.
         */
        private const val BALL_COLOR = 0xFF1F6B4C.toInt()
        private const val STATUS_RUNNING = 0xFF5FD8A0.toInt()
        private const val STATUS_IDLE = 0xFF9AA5A0.toInt()

        /** The panel's width: the default, and the range the resize handle may drag it through. */
        private const val PANEL_DEFAULT_DP = 268
        private const val PANEL_MIN_DP = 200
        private const val PANEL_MAX_DP = 420

        /** How much of a message the panel's summary keeps, per line. */
        private const val MESSAGE_PREVIEW_CHARS = 56

        /** The engine publishes a running tool as 「🔧 name…」 on [AgentEngine.RunHandle.liveLine]. */
        private const val TOOL_LIVE_PREFIX = "🔧 "

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
    private lateinit var conversationText: TextView
    private lateinit var recentText: TextView
    private lateinit var steerInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var stopArea: LinearLayout
    private lateinit var feedbackText: TextView

    private var attached = false
    private var dotPulse: ValueAnimator? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var dragging = false

    /** The resize gesture: where the finger went down, and how wide the panel was then. */
    private var resizeStartX = 0f
    private var resizeStartWidth = 0

    /**
     * The three settings this service reads synchronously, kept as one readable copy.
     *
     * [SettingsRepository] offers a cold flow and a suspending `current()`, and every reader here —
     * a touch listener, a lifecycle callback, the layout pass — runs where neither can be awaited.
     * The collector in [observeRun] is the only writer and it writes on every change, so this copy
     * is never more than one emission behind.
     */
    private data class ConsoleSettings(
        val resident: Boolean = false,
        val whileRunning: Boolean = true,
        val widthDp: Int = 0,
    )

    private var consoleSettings = ConsoleSettings()

    /** The system's own threshold, so a drag feels the same here as anywhere else. */
    private val touchSlop: Float by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    /**
     * The console's visibility, re-decided on every lifecycle change and every run change.
     *
     * **In front of 随行 it is never shown**, whatever the switches say: the chat screen already
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
                // 常驻 shows the ball as soon as the console is up *and* the user has left 随行;
                // in front of the app it is never shown. With only 「运行时显示」 ticked this
                // first decision is "stay hidden", and the observer above catches the moment the
                // user leaves — or a run starting while they are away.
                refreshVisibility()
            }
        } else {
            refreshVisibility()
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
     * visible at all depends on where the user is and on the two switches, which
     * [refreshVisibility] decides.
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
        // The flag follows what the window manager actually did. A window the system refused
        // (the permission was revoked between the check and now) must not be remembered as
        // attached, or the console would stay invisible for the rest of the run.
        attached = runCatching { windowManager.addView(root, params) }.isSuccess
    }

    private fun detach() {
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    /**
     * Whether the console is on screen right now — see [appLifecycle] for what each switch means.
     *
     * Re-read from the settings on every call rather than cached: both switches can be flipped
     * while the console is up, and a cached copy would leave the ball disagreeing with its own
     * switch.
     */
    private fun refreshVisibility() {
        val settings = consoleSettings
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        val running = Core.engine.runsFlow.value.isNotEmpty()
        // 前台一律不显示；不在前台时：常驻 ⇒ 一直显示，否则看 B（运行时才显示）。
        val visible = !foreground &&
            (settings.resident || (settings.whileRunning && running))
        if (visible) attach() else detach()
    }

    /**
     * The panel's width: whatever the user last dragged it to, or 268dp, and never wider than the
     * window it floats in.
     *
     * A fixed 268dp is 74% of a 360dp screen and 100% of nothing at all in split screen, where the
     * window can be 300dp wide: the card then ran off the right edge and its own text was cut.
     */
    private fun panelWidth(): Int = minOf(
        dp(consoleSettings.widthDp.takeIf { it > 0 } ?: PANEL_DEFAULT_DP),
        windowWidthPx() - dp(24),
    ).coerceAtLeast(panelMinWidth())

    /** The panel's width, re-applied whenever the stored one changes under it. */
    private fun applyPanelWidth() {
        (panel.layoutParams as? LinearLayout.LayoutParams)?.let { layout ->
            val width = panelWidth()
            if (layout.width == width) return
            layout.width = width
            panel.layoutParams = layout
        }
    }

    private fun panelMinWidth(): Int = dp(PANEL_MIN_DP)

    /** The widest the handle may drag: the ceiling, or the window, whichever is smaller. */
    private fun panelMaxWidth(): Int =
        minOf(dp(PANEL_MAX_DP), windowWidthPx() - dp(24)).coerceAtLeast(panelMinWidth())

    /**
     * The width of the window the console floats in, in px — not the display's own.
     *
     * `resources.displayMetrics` reports the whole screen, so in split screen it hands the panel a
     * width the window does not have. `currentWindowMetrics` is the window's own bounds, which is
     * what a width cap has to be measured against; below R that call does not exist and the
     * display's real size is the best answer available.
     */
    @Suppress("DEPRECATION")
    private fun windowWidthPx(): Int {
        val manager = getSystemService(WindowManager::class.java)
            ?: return resources.displayMetrics.widthPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val width = runCatching { manager.currentWindowMetrics.bounds.width() }.getOrDefault(0)
            if (width > 0) return width
        }
        val point = Point()
        manager.defaultDisplay?.getRealSize(point)
        return point.x.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    }

    /**
     * Keeps the window inside the window's own width.
     *
     * The panel is a child of the same window as the ball, so the window may not start closer to
     * the right edge than the panel is wide — dragged further right, the panel simply left the
     * screen and its buttons were cut in half. [extraWidth] carries the panel's intended width for
     * the moment it becomes visible, when this window has not been measured with it yet.
     */
    private fun clampX(extraWidth: Int = 0) {
        val width = maxOf(root.width, extraWidth, dp(44))
        params.x = params.x.coerceIn(0, (windowWidthPx() - width).coerceAtLeast(0))
    }

    /**
     * A 44dp brand disc with the feather on it, and a dot that says whether a run is live. Drawn
     * from views rather than Compose: this window lives outside any activity, so there is no
     * composition to host it.
     */
    private fun buildBall(): FrameLayout {
        val size = dp(44)
        val ball = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            // A flat disc: one solid colour, no gradient, no shadow, no ring around it. The
            // verdict on the dressed-up version was 「就干净的小球就行了」 — the elevation and the
            // outline that existed only to give that shadow a shape went with it.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
            }
            setOnTouchListener { view, event -> onBallTouch(view, event) }
        }
        ball.addView(
            ImageView(this).apply {
                // A dedicated vector, not the launcher's foreground: that one is drawn on a 108dp
                // canvas with the art inside its safe zone, so at this size its ink came out about
                // 12×18dp in a 30dp box — small, and soft from the downscale. This one is framed
                // for the box it is drawn in. The white tint stays: the disc is the brand gradient.
                setImageResource(R.drawable.ic_console_feather)
                setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
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
        layoutParams = LinearLayout.LayoutParams(panelWidth(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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

        // 能看：which conversation this panel is talking about, and its last line or two. Without
        // the name the box below would write into a chat the user cannot see; without the lines
        // the panel could only ever say what the run is doing, never what was asked.
        conversationText = TextView(context).apply {
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(conversationText)

        recentText = TextView(context).apply {
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(8))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(recentText)

        steerInput = EditText(context).apply {
            // Neutral on purpose: this one box both steers a run and opens the next turn.
            hint = "说一句…"
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
                // One button for both cases, labelled by the run state: 「插话」 into the run in
                // flight, 「发送」 when nothing is running. See [sendFromPanel].
                sendButton = actionButton("发送", accent = true) { sendFromPanel() }
                addView(sendButton)
            },
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(actionButton("回到对话") { openChat() })
            },
        )
        // Filled in by [renderStopArea] once the run set is known: one run keeps the plain stop
        // button, several get a titled row each, because a single button could only guess which
        // conversation the user meant.
        stopArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addView(stopArea)
        // The panel's other lines are rewritten from the run's state on every change, so the
        // confirmation of a stop needs a line of its own to still be there when the run it
        // named has already left the list.
        feedbackText = TextView(context).apply {
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        addView(feedbackText)
        // Last, so it reads as the panel's own corner: the handle that says 「this card can be
        // dragged wider」 without a label.
        addView(resizeHandle())
    }

    /**
     * The panel's resize handle: drag sideways to widen or narrow the card.
     *
     * Width only. The height follows the content — the stop area grows a row per run in flight,
     * and a fixed height would cut exactly those rows off.
     */
    private fun resizeHandle(): View = View(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(palette.muted)
        }
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(4)).apply {
            gravity = Gravity.END
            topMargin = dp(10)
        }
        setOnTouchListener { _, event -> onResizeTouch(event) }
    }

    /**
     * The drag itself: the finger's own delta is what changes the width, so the handle keeps up
     * with the gesture instead of jumping to where it points.
     *
     * The last width is written to the settings on the lift, not on every frame — a save per move
     * would have the console writing a JSON file sixty times a second.
     */
    private fun onResizeTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            resizeStartX = event.rawX
            resizeStartWidth = panel.width
            true
        }

        MotionEvent.ACTION_MOVE -> {
            val width = (resizeStartWidth + (event.rawX - resizeStartX)).toInt()
                .coerceIn(panelMinWidth(), panelMaxWidth())
            (panel.layoutParams as? LinearLayout.LayoutParams)?.let { layout ->
                layout.width = width
                panel.layoutParams = layout
            }
            true
        }

        MotionEvent.ACTION_UP -> {
            rememberPanelWidth(panel.width)
            // A wider panel may no longer fit where a narrower one did, so the window is pulled
            // back inside the screen with the width it just got.
            clampX(panelWidth())
            if (attached) runCatching { windowManager.updateViewLayout(root, params) }
            true
        }

        else -> false
    }

    /** Remembers the dragged width in dp, so a density change cannot resize the card on its own. */
    private fun rememberPanelWidth(px: Int) {
        val widthDp = (px / resources.displayMetrics.density).roundToInt()
        Core.appScope.launch {
            Core.settings.update { it.copy(consoleWidthDp = widthDp.coerceIn(PANEL_MIN_DP, PANEL_MAX_DP)) }
        }
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
            if (!dragging) {
                setExpanded(panel.visibility != View.VISIBLE)
            } else {
                // The ball may be dropped anywhere; the panel is what has to stay reachable.
                clampX(if (panel.visibility == View.VISIBLE) panelWidth() else 0)
                if (attached) runCatching { windowManager.updateViewLayout(root, params) }
            }
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
        // Expanding widens this window to the panel's width, so the ball's own position can now be
        // too far right for it: pulled back before the layout that would hang the card off-screen.
        if (expanded) {
            // Also the moment to re-measure: a rotation or a split-screen resize changes the window
            // the card has to fit in, and this width was decided when the panel was built.
            (panel.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = panelWidth()
                panel.layoutParams = lp
            }
            clampX(panelWidth())
        }
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
     * Mirrors the engine's run state into the panel, and keeps the panel's own two lines current.
     *
     * The state comes from [Core.engine] rather than from anything local, so the panel
     * and the chat screen can never tell two different stories about the same run.
     */
    private fun observeRun() {
        scope.launch {
            // The console follows whichever run started last; the map re-emits on every
            // start/stop, so a newer run takes the panel over and an ended one hands it
            // back to the previous run or hides the panel when none is left.
            Core.engine.runsFlow
                .collectLatest { runs ->
                    // The panel follows whichever run started last; the map re-emits on every
                    // start/stop, so a newer run takes the panel over and an ended one hands it
                    // back to the previous run or hides the panel when none is left. The stop
                    // area, unlike the panel, shows every run in flight.
                    val handle = runs.values.maxByOrNull { it.startedAt }
                    val state = handle?.state?.value ?: RunState.Idle
                    stateText.text = consoleStatus(state, handle)
                    statsText.text = state.detail()
                    // The live line is re-read on every state, and the engine ticks its state
                    // while a run is in flight, so this stays current without a second stream.
                    liveText.text = if (state is RunState.Running) handle?.liveLine.orEmpty() else ""
                    // One button, two meanings, decided by the same question the label answers:
                    // while something is running a line typed here joins that run, and with
                    // nothing running it opens the conversation's next turn. The chat composer
                    // reads the same way, so the two never disagree about what a tap will do.
                    sendButton.text = if (state is RunState.Running) "插话" else "发送"
                    setStatusDot(state is RunState.Running)
                    renderStopArea(runs.values.sortedByDescending { it.startedAt })
                    renderConversation()
                    // Re-decided here as well, because a run can also start with the app already
                    // in the background (a reminder): this is the only place that sees one
                    // appear, and without it the ball would wait for the next trip out of the
                    // app to show up — or never show for a run that both starts and ends there.
                    refreshVisibility()
                }
        }
        // Which conversation the panel is talking about: the list re-emits on every save, so the
        // title, the last lines and a rename all land without polling.
        scope.launch {
            Core.chats.conversations.collectLatest { renderConversation() }
        }
        // Both visibility switches live in the settings: a flip has to be felt at once, or the
        // ball would sit there disagreeing with the switch that turned it off. This collector is
        // also the only writer of [consoleSettings] — the synchronous copy everything else reads.
        scope.launch {
            Core.settings.flow.collect { stored ->
                consoleSettings = ConsoleSettings(
                    resident = stored.floatingConsole,
                    whileRunning = stored.consoleWhileRunning,
                    widthDp = stored.consoleWidthDp,
                )
                applyPanelWidth()
                refreshVisibility()
            }
        }
    }

    /**
     * The run's own title, except while a `ui_*` tool is on screen.
     *
     * 「正在操作手机」 is what the user wants to read when the agent is the one driving, and the
     * tool's own name says nothing about that. Read off the live line the engine already publishes
     * (`🔧 name…`), so nothing new has to be plumbed through for it.
     */
    private fun consoleStatus(state: RunState, handle: AgentEngine.RunHandle?): String {
        val live = handle?.liveLine.orEmpty()
        if (state is RunState.Running && live.startsWith(TOOL_LIVE_PREFIX)) {
            val tool = live.removePrefix(TOOL_LIVE_PREFIX).trimEnd('…')
            if (tool.startsWith("ui_")) return "正在操作手机（$tool）"
        }
        return state.title()
    }

    /**
     * The conversation the panel is showing, and its last one or two lines.
     *
     * The one running wins over the one used last, because that is the chat a steer typed here
     * would land in: the panel must name the conversation its own box writes to, or the user would
     * be typing into a chat they cannot see. With nothing running it falls back to the top of the
     * list, which is the conversation the app itself would open.
     */
    private fun renderConversation() {
        val conversations = Core.chats.conversations.value
        val running = Core.engine.newestRun()?.chatId
        val conversation: Conversation? =
            conversations.firstOrNull { it.id == running } ?: conversations.firstOrNull()
        if (conversation == null) {
            conversationText.text = "还没有会话"
            recentText.text = "在下面的输入框里说一句，就会开一个新会话"
            return
        }
        conversationText.text = conversation.title.ifBlank { "新会话" }
        val recent = conversation.messages.takeLast(2)
        recentText.text = if (recent.isEmpty()) {
            "这个会话还没有消息"
        } else {
            recent.joinToString("\n") { message ->
                val speaker = if (message.role == Roles.USER) "你：" else "随行："
                speaker + message.text.replace('\n', ' ').trim().take(MESSAGE_PREVIEW_CHARS)
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

    /**
     * Rebuilt whenever a run starts or ends.
     *
     * One run needs no label: there is nothing to disambiguate, so the button is the same one
     * the console has always had. Two or more get a row each, titled, because a single button
     * could only ever stop whichever run happened to be newest — not the one the user was
     * looking at.
     */
    private fun renderStopArea(handles: List<AgentEngine.RunHandle>) {
        stopArea.removeAllViews()
        stopArea.visibility = if (handles.isEmpty()) View.GONE else View.VISIBLE
        if (handles.isEmpty()) return
        if (handles.size == 1) {
            stopArea.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        },
                    )
                    addView(actionButton("停止", accent = true) { stopRun(handles.first()) })
                },
            )
            return
        }
        for (handle in handles) {
            stopArea.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = titleOf(handle)
                            setTextColor(palette.ink)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                        },
                    )
                    addView(actionButton("停止", accent = true) { stopRun(handle) })
                },
            )
        }
    }

    private fun stopRun(handle: AgentEngine.RunHandle) {
        val stopped = Core.engine.cancel(handle.chatId)
        // Named, so a stop aimed at one of several runs says which one it hit — and on its own
        // line, because the run it names is about to leave the list and take its row with it.
        feedbackText.text = if (stopped) "已请求停止：${titleOf(handle)}" else "这个任务已经结束了"
        feedbackText.visibility = View.VISIBLE
    }

    /**
     * The conversation's own title, so a rename is reflected; the title the run started with, for
     * a conversation the list has not caught up with; then a short id, which is at least
     * something the user can match against the chat list.
     */
    private fun titleOf(handle: AgentEngine.RunHandle): String {
        val current = Core.chats.conversations.value.firstOrNull { it.id == handle.chatId }?.title
        return current?.takeIf { it.isNotBlank() }
            ?: handle.title.takeIf { it.isNotBlank() }
            ?: "会话 ${handle.chatId.take(6)}"
    }

    /**
     * The panel's box: 能发 — a line typed here reaches the conversation the panel names.
     *
     * Two cases, one button. A run in flight gets the line as a steer, which is what the console
     * has always done. With nothing running the line becomes that conversation's next turn, and
     * that goes through [Core.engine]'s own [AgentEngine.startRun] with the app's registry, gate
     * and stores — the same call the chat screen makes, not a second sending path that could start
     * a run the chat screen cannot see or stop.
     */
    private fun sendFromPanel() {
        val text = steerInput.text.toString().trim()
        if (text.isEmpty()) return
        val handle = Core.engine.newestRun()
        if (handle != null) {
            // The line needs an id of its own: the engine's queue is keyed by it, and the console
            // has no way to retract or promote what it says, so nothing else ever reads this one.
            val queued = Core.engine.steer(handle.chatId, UUID.randomUUID().toString(), text)
            if (queued) {
                steerInput.setText("")
                statsText.text = "已插话，模型下一轮就能看到"
                hideKeyboard()
            } else {
                statsText.text = "这句话没有发出：任务刚好结束了"
            }
            return
        }
        steerInput.setText("")
        hideKeyboard()
        statsText.text = "已发送，正在等回答…"
        val chatId = Core.chats.conversations.value.firstOrNull()?.id
        // On the app's own scope, not the service's: the answer outlives the panel being collapsed
        // or the console being taken down, and the run has to keep writing its transcript.
        Core.appScope.launch {
            // A run that fails throws, and this scope has no handler of its own: unguarded, one
            // refused endpoint would take the process down with the question already saved.
            runCatching { startRunFor(chatId, text) }
        }
    }

    /**
     * Starts the conversation's next turn and saves what it produced.
     *
     * The shape is the reminder path's, for the same reason: with no chat screen collecting the
     * run's events, the messages the engine emits on its way out are the only copy of the answer
     * there is — so they are gathered here and merged with what the run returned.
     */
    private suspend fun startRunFor(chatId: String?, text: String) {
        val conversation = Core.chats.conversations.value.firstOrNull { it.id == chatId }
            ?: Core.chats.create(text.take(18))
        val title = conversation.title.ifBlank { text.take(18) }
        val question = StoredMessage(id = UUID.randomUUID().toString(), role = Roles.USER, text = text)
        val history = conversation.messages + question
        // The question is saved before the run: a run that is refused, stopped or killed still
        // leaves the user's own line in the transcript to look at.
        Core.chats.save(conversation.copy(title = title, messages = history))
        val emitted = mutableListOf<StoredMessage>()
        try {
            val produced = Core.engine.startRun(
                chatId = conversation.id,
                title = title,
                history = history,
                settings = Core.settings.current(),
                registry = Core.tools,
                capabilities = Core.capabilities,
                skills = Core.skills,
                memory = Core.memory,
                profile = Core.profile,
                gate = Core.gate,
                onEvent = { event ->
                    when (event) {
                        is AgentEvent.AssistantMessage -> emitted += event.message
                        is AgentEvent.ToolMessage -> emitted += event.message
                        else -> Unit
                    }
                },
            )
            // Invariant: `emitted` is `produced` in the same order, plus what the engine emits
            // while ending a stopped run. A short `emitted` falls back to `produced` rather than
            // saving a transcript with the wrong messages in it.
            val transcript = if (emitted.size >= produced.size) {
                produced + emitted.drop(produced.size)
            } else {
                produced
            }
            withContext(NonCancellable) {
                Core.chats.save(conversation.copy(title = title, messages = history + transcript))
            }
        } catch (cancellation: CancellationException) {
            // Stopping the run cancels this coroutine, and a cancelled coroutine refuses to
            // suspend: the save has to run under NonCancellable or the answer the user waited for
            // would be lost with the process still holding it. Rethrown, because swallowing it
            // here would turn a stop into a normal ending.
            withContext(NonCancellable) {
                Core.chats.save(conversation.copy(title = title, messages = history + emitted))
            }
            throw cancellation
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
