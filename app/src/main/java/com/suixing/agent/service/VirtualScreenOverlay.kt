package com.suixing.agent.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.suixing.agent.virtualscreen.VirtualScreenInput
import com.suixing.agent.virtualscreen.VirtualScreenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.hypot

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

    /** 拖过这个距离就算滑动，否则算点击：与系统触摸滑动的常规阈值同量级。 */
    private const val SWIPE_SLOP_DP = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: FrameLayout? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var image: ImageView? = null
    private var title: TextView? = null
    private var frames: Job? = null

    @Volatile
    private var displayId = -1

    val isShowing: Boolean get() = root != null

    /** 显示（或重新显示）预览窗；屏还没建就先建。返回是否成功挂上窗口。 */
    fun show(context: Context, displayIdValue: Int = VirtualScreenManager.state.value.displayId): Boolean {
        if (isShowing) return true
        if (displayIdValue < 0) return false
        displayId = displayIdValue
        VirtualScreenManager.setPreviewEnabled(true)

        val app = context.applicationContext
        // 无障碍服务是系统信任宿主：用它当 Context 建出来的窗口在被其他应用盖住后仍在。
        val host: Context = SuixingAccessibilityService.instance() ?: app
        val type = if (host is SuixingAccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            ?: return false

        val state = VirtualScreenManager.state.value
        val metrics = app.resources.displayMetrics
        val widthPx = (metrics.widthPixels * WIDTH_RATIO).toInt().coerceAtLeast(dp(app, 120))
        val heightPx = if (state.width > 0 && state.height > 0) {
            (widthPx.toFloat() * state.height / state.width).toInt()
        } else {
            (widthPx * 2f).toInt()
        }

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
        }
        attachInput(picture, app)

        container.addView(
            picture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = dp(app, HEADER_DP) },
        )
        container.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(app, HEADER_DP),
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
            x = metrics.widthPixels - widthPx - dp(app, EDGE_DP)
            y = (metrics.heightPixels * 0.22f).toInt()
        }

        val added = runCatching { windowManager.addView(container, layoutParams) }
        if (added.isFailure) return false

        root = container
        manager = windowManager
        params = layoutParams
        image = picture
        title = header.findViewWithTag<TextView>("title")
        drag(header, app)

        frames?.cancel()
        frames = scope.launch {
            VirtualScreenManager.frame.collect { bitmap -> picture.setImageBitmap(bitmap) }
        }
        return true
    }

    /** 收起预览窗。**不停止**虚拟屏：屏上的应用继续跑，再 [show] 就接着看。 */
    fun hide() {
        frames?.cancel()
        frames = null
        val container = root ?: return
        runCatching { manager?.removeView(container) }
        root = null
        manager = null
        params = null
        image = null
        title = null
        displayId = -1
    }

    /** 虚拟屏本身被关掉时连同窗口一起收干净。 */
    fun release() {
        hide()
    }

    /** 标题栏跟着手指走，并把窗口限制在屏幕内，免得拖出边界再也抓不回来。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun drag(header: View, app: Context) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        header.setOnTouchListener { _, event ->
            val layoutParams = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = app.resources.displayMetrics
                    layoutParams.x = (startX + (event.rawX - downX)).toInt()
                        .coerceIn(0, (metrics.widthPixels - layoutParams.width).coerceAtLeast(0))
                    layoutParams.y = (startY + (event.rawY - downY)).toInt()
                        .coerceIn(0, (metrics.heightPixels - layoutParams.height).coerceAtLeast(0))
                    runCatching { root?.let { view -> manager?.updateViewLayout(view, layoutParams) } }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 画面区域的触摸换算成虚拟屏坐标后注入。
     *
     * 视图里的 y 要减掉标题栏；虚拟屏坐标按 `虚拟屏尺寸 / 视图尺寸` 缩放。滑动的方向与
     * 距离按两点的差值投递，交给系统自己算惯性。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachInput(picture: ImageView, app: Context) {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        picture.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val state = VirtualScreenManager.state.value
                    val id = displayId
                    if (id < 0 || state.width <= 0 || state.height <= 0 || view.width <= 0) {
                        return@setOnTouchListener true
                    }
                    val scaleX = state.width.toFloat() / view.width
                    val scaleY = state.height.toFloat() / view.height
                    val x1 = downX * scaleX
                    val y1 = downY * scaleY
                    val x2 = event.x * scaleX
                    val y2 = event.y * scaleY
                    val slop = dp(app, SWIPE_SLOP_DP)
                    val far = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > slop
                    scope.launch {
                        if (far) {
                            val duration = (System.currentTimeMillis() - downAt).coerceIn(80L, 800L)
                            VirtualScreenInput.swipe(id, x1, y1, x2, y2)
                        } else {
                            VirtualScreenInput.tap(id, x1, y1)
                        }
                    }
                    true
                }
                else -> false
            }
        }
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
        val hide = action(host, app, "－") { hide() }
        val close = action(host, app, "×") {
            hide()
            VirtualScreenManager.stop()
        }
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