package com.suixing.agent.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.suixing.agent.virtualscreen.VirtualScreenInput
import com.suixing.agent.virtualscreen.VirtualScreenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 虚拟屏的**悬浮预览窗**。
 *
 * 这块屏活在自己的 display 上，跟页面无关：聊天、终端、别的应用都照常用，预览只是一个
 * 浮在最上层的窗口，收起它不停止屏上的应用（`VirtualScreenManager` 的 ImageReader 一直
 * 挂着）。所以这里不做任何「页面内嵌」——那是另一种东西，也不是这里要的。
 *
 * 窗口类型优先 `TYPE_ACCESSIBILITY_OVERLAY`：无障碍服务本身就是系统信任的宿主，MIUI
 * 不会像 `TYPE_APPLICATION_OVERLAY` 那样在后台把窗口收掉（和 [TouchFx] 同一条理由）。
 * 没有无障碍时退回应用悬浮窗，需要 `SYSTEM_ALERT_WINDOW` —— 清单里已经声明。
 *
 * 画面上的触摸按比例换算成虚拟屏坐标后注入过去（[VirtualScreenInput]），所以这个浮窗
 * 既能看也能摸；标题栏负责拖动，画面区域负责交互，两者不抢。
 */
object VirtualScreenOverlay {

    /** 预览窗默认占主屏宽度的这个比例，够看清列表文字，又不至于挡住聊天。 */
    private const val WIDTH_RATIO = 0.34f

    private const val HEADER_DP = 26
    private const val EDGE_DP = 10

    /** One gesture may run while at most two more wait; old windows never keep an input queue. */
    private const val INPUT_QUEUE_SIZE = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: FrameLayout? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var image: ImageView? = null
    private var title: TextView? = null
    private var frames: Job? = null
    private var inputs: Job? = null
    private var inputQueue: Channel<ManualGesture>? = null
    private var dragHost: View? = null
    private var dragFrame: Runnable? = null
    private var inputNotice: Toast? = null
    private var lastInputNoticeAt = 0L

    private enum class GestureKind { Tap, LongPress, Swipe }
    private data class ManualGesture(
        val kind: GestureKind,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long,
    )

    @Volatile
    private var displayId = -1

    private val _visibility = MutableStateFlow(false)
    val visibility = _visibility.asStateFlow()
    val isShowing: Boolean get() = _visibility.value

    /** 显示（或重新显示）当前副屏的预览窗，成功挂载后才启预览取帧。 */
    fun show(context: Context, displayIdValue: Int = VirtualScreenManager.state.value.displayId): Boolean {
        val state = VirtualScreenManager.state.value
        if (!state.running || displayIdValue < 0 || state.displayId != displayIdValue) return false
        // addView may return before the first attach; a repeated open must reuse that pending window.
        if (isShowing && displayIdValue == displayId && root != null) return true
        if (root != null || isShowing) hide()

        val app = context.applicationContext
        // 无障碍服务是系统信任宿主：用它当 Context 建出来的窗口在被其他应用盖住后仍在。
        val host: Context = SuixingAccessibilityService.instance() ?: app
        val type = if (host is SuixingAccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false

        val metrics = app.resources.displayMetrics
        val headerPx = dp(app, HEADER_DP)
        val edgePx = dp(app, EDGE_DP)
        val availableWidth = (metrics.widthPixels - edgePx * 2).coerceAtLeast(1)
        val availableHeight = (metrics.heightPixels - edgePx * 2 - headerPx).coerceAtLeast(1)
        val preferredWidth = (metrics.widthPixels * WIDTH_RATIO).roundToInt()
            .coerceAtLeast(dp(app, 120)).coerceAtMost(availableWidth)
        val scale = minOf(preferredWidth.toFloat() / state.width, availableHeight.toFloat() / state.height)
        val widthPx = (state.width * scale).roundToInt().coerceIn(1, availableWidth)
        val pictureHeight = (state.height * scale).roundToInt().coerceIn(1, availableHeight)
        val heightPx = pictureHeight + headerPx

        val container = FrameLayout(host).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(app, 12).toFloat()
                setColor(0xF2FFFFFF.toInt())
                setStroke(dp(app, 1), 0x1F000000)
            }
            // 圆角裁剪：图片按 FIT_XY 铺满，不裁会从圆角外面漏出直角。
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(app, 12).toFloat())
                }
            }
        }

        val header = buildHeader(host, app)
        val picture = ImageView(host).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.WHITE)
            contentDescription = "虚拟屏预览"
        }
        attachInput(picture, container, displayIdValue, app)
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { clearWindow(container) }
        })

        container.addView(
            picture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = headerPx },
        )
        container.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                headerPx,
            ),
        )

        val layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - widthPx - edgePx
            y = (metrics.heightPixels * 0.22f).toInt()
                .coerceIn(edgePx, (metrics.heightPixels - heightPx - edgePx).coerceAtLeast(edgePx))
        }

        val added = runCatching { windowManager.addView(container, layoutParams) }
        if (added.isFailure) {
            VirtualScreenManager.setPreviewEnabled(false)
            return false
        }

        root = container
        manager = windowManager
        params = layoutParams
        image = picture
        title = header.findViewWithTag<TextView>("title")
        displayId = displayIdValue
        _visibility.value = true
        drag(header, container, app)
        startInputs(container, displayIdValue, app)

        frames?.cancel()
        frames = scope.launch {
            VirtualScreenManager.frame.collect { bitmap ->
                if (root === container) picture.setImageBitmap(bitmap)
            }
        }
        VirtualScreenManager.setPreviewEnabled(true)
        return true
    }

    /** 收起预览窗。**不停止**虚拟屏：屏上的应用继续跑，再 [show] 就接着看。 */
    fun hide() {
        val container = root
        val windowManager = manager
        clearWindow(container)
        if (container != null) runCatching { windowManager?.removeView(container) }
    }

    private fun clearWindow(container: FrameLayout?) {
        if (root !== container) return
        frames?.cancel()
        frames = null
        inputQueue?.cancel()
        inputQueue = null
        inputs?.cancel()
        inputs = null
        dragFrame?.let { dragHost?.removeCallbacks(it) }
        dragHost = null
        dragFrame = null
        image?.setImageDrawable(null)
        inputNotice?.cancel()
        inputNotice = null
        root = null
        manager = null
        params = null
        image = null
        title = null
        displayId = -1
        _visibility.value = false
        VirtualScreenManager.setPreviewEnabled(false)
    }

    /** 虚拟屏本身被关掉时连同窗口一起收干净。 */
    fun release() {
        hide()
    }

    /** 标题栏跟着手指走，并把窗口限制在屏幕内，免得拖出边界再也抓不回来。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun drag(header: View, container: FrameLayout, app: Context) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var pendingX = 0
        var pendingY = 0
        var posted = false
        var held = false
        val update = Runnable {
            posted = false
            if (root === container) {
                params?.let { layoutParams ->
                    if (layoutParams.x != pendingX || layoutParams.y != pendingY) {
                        layoutParams.x = pendingX
                        layoutParams.y = pendingY
                        runCatching { manager?.updateViewLayout(container, layoutParams) }
                    }
                }
            }
        }
        dragHost = header
        dragFrame = update
        fun move(event: MotionEvent, layoutParams: WindowManager.LayoutParams) {
            val metrics = app.resources.displayMetrics
            pendingX = (startX + (event.rawX - downX)).roundToInt()
                .coerceIn(0, (metrics.widthPixels - layoutParams.width).coerceAtLeast(0))
            pendingY = (startY + (event.rawY - downY)).roundToInt()
                .coerceIn(0, (metrics.heightPixels - layoutParams.height).coerceAtLeast(0))
            if (!posted && (pendingX != layoutParams.x || pendingY != layoutParams.y)) {
                posted = true
                header.postOnAnimation(update)
            }
        }
        header.setOnTouchListener { _, event ->
            if (root !== container) return@setOnTouchListener false
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = if (posted) pendingX else layoutParams.x
                    startY = if (posted) pendingY else layoutParams.y
                    held = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (held) move(event, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (held) move(event, layoutParams)
                    held = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    held = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 画面区域的触摸换算成虚拟屏坐标后注入。
     *
     * MotionEvent 已是图片局部坐标，按 `虚拟屏尺寸 / 图片尺寸` 缩放。
     * 松手后串行投递点击、长按或滑动；滑动保留用户实际时长。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachInput(picture: ImageView, container: FrameLayout, expectedId: Int, app: Context) {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var held = false
        var moved = false
        val slop = ViewConfiguration.get(app).scaledTouchSlop.toFloat()
        val slopSquared = slop * slop
        fun beyondSlop(x: Float, y: Float): Boolean {
            val dx = x - downX
            val dy = y - downY
            return dx * dx + dy * dy > slopSquared
        }
        picture.setOnTouchListener { view, event ->
            if (!currentWindow(container, expectedId)) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = event.eventTime
                    held = true
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (held && beyondSlop(event.x, event.y)) moved = true
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    held = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!held) return@setOnTouchListener true
                    held = false
                    val state = VirtualScreenManager.state.value
                    if (state.width <= 0 || state.height <= 0 || view.width <= 0 || view.height <= 0) {
                        return@setOnTouchListener true
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        inputFailed(app, "副屏触摸需要 Android 11 或更新版本。")
                        return@setOnTouchListener true
                    }
                    if (!VirtualScreenInput.accessibilityReady()) {
                        inputFailed(app, "操作虚拟屏需要先开启无障碍服务。")
                        return@setOnTouchListener true
                    }
                    val scaleX = state.width.toFloat() / view.width
                    val scaleY = state.height.toFloat() / view.height
                    val far = beyondSlop(event.x, event.y)
                    // Moving out and returning is not a tap or a stationary long press.
                    if (moved && !far) return@setOnTouchListener true
                    val duration = (event.eventTime - downAt).coerceAtLeast(1L)
                    val kind = when {
                        far -> GestureKind.Swipe
                        duration >= ViewConfiguration.getLongPressTimeout() -> GestureKind.LongPress
                        else -> GestureKind.Tap
                    }
                    val gesture = ManualGesture(kind,
                        (downX * scaleX).coerceIn(0f, (state.width - 1).toFloat()),
                        (downY * scaleY).coerceIn(0f, (state.height - 1).toFloat()),
                        (event.x * scaleX).coerceIn(0f, (state.width - 1).toFloat()),
                        (event.y * scaleY).coerceIn(0f, (state.height - 1).toFloat()),
                        duration,
                    )
                    if (inputQueue?.trySend(gesture)?.isSuccess != true) {
                        inputFailed(app, "操作较快，请稍后再试。")
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun currentWindow(container: FrameLayout, expectedId: Int): Boolean {
        val state = VirtualScreenManager.state.value
        return root === container && isShowing && container.isAttachedToWindow &&
            displayId == expectedId && state.running && state.displayId == expectedId
    }

    private fun startInputs(container: FrameLayout, expectedId: Int, app: Context) {
        val queue = Channel<ManualGesture>(INPUT_QUEUE_SIZE)
        inputQueue = queue
        inputs = scope.launch {
            for (gesture in queue) {
                if (!currentWindow(container, expectedId)) break
                val success = try {
                    when (gesture.kind) {
                        GestureKind.Tap -> VirtualScreenInput.tap(expectedId, gesture.x1, gesture.y1)
                        GestureKind.LongPress -> VirtualScreenInput.longPress(expectedId, gesture.x1, gesture.y1)
                        GestureKind.Swipe -> VirtualScreenInput.swipe(expectedId, gesture.x1, gesture.y1,
                            gesture.x2, gesture.y2, gesture.durationMs.coerceIn(60L, 5_000L))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!success && currentWindow(container, expectedId)) {
                    inputFailed(app, "虚拟屏操作未完成，请检查无障碍状态后重试。")
                }
            }
        }
    }

    private fun inputFailed(app: Context, message: String) {
        val now = SystemClock.uptimeMillis()
        if (inputNotice != null && now - lastInputNoticeAt < 1_500L) return
        lastInputNoticeAt = now
        inputNotice?.cancel()
        inputNotice = Toast.makeText(app, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    /** 标题：一个「虚拟屏」标签 + 收起 + 关闭。收起只是收起窗，关闭才停这块屏。 */
    private fun buildHeader(host: Context, app: Context): LinearLayout {
        val header = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(app, 8), 0, dp(app, 4), 0)
            background = GradientDrawable().apply { setColor(0xFFF1F0EA.toInt()) }
        }
        val label = TextView(host).apply {
            text = "虚拟屏"
            setTextColor(0xFF22211E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            tag = "title"
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hide = action(host, app, "－") { hide() }.apply { contentDescription = "收起虚拟屏预览" }
        val close = action(host, app, "×") {
            hide()
            VirtualScreenManager.stop()
        }.apply { contentDescription = "关闭虚拟屏" }
        header.addView(label)
        header.addView(hide)
        header.addView(close)
        return header
    }

    private fun action(host: Context, app: Context, text: String, onClick: () -> Unit): TextView =
        TextView(host).apply {
            this.text = text
            setTextColor(0xFF22211E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(app, HEADER_DP), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }

    private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    /** 让「重新打开预览」这类入口能拿到当前标题，避免外部自己拼字符串。 */
    fun label(): String = title?.text?.toString().orEmpty()
}
