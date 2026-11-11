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

/** 承载触摸特效的自绘 View（环、拖尾、边缘光、光环）。从 TouchFx 搬出来。 */

/**
 * The full-screen, untouchable canvas the effects are drawn on.
 *
 * 它常驻（spec v2 改动四）：一次 addView 之后不再随效果挂卸，亮灭全靠 alpha —— 亮着 25fps 呼吸，灭时
 * 逐帧淡出，淡尽后既不排帧也不绘制。窗口的生死只由 [TouchFx.release] 决定。
 *
 * [screenBounds] is the display's real extent, captured by [TouchFx.overlay] when the window was
 * created and refreshed in [onSizeChanged] when the display is rotated or resized, and is what the
 * edge light is drawn on rather than this view's own measured size — see
 * [TouchFx.displayBounds]. [cornerPx] is the panel's own corner radius, so the ring follows the
 * screen's shape rather than a guess at it.
 */
internal class FxView(
    context: Context,
    initialBounds: Rect,
    private val cornerPx: Float,
    // 重新问一次显示器的真实尺寸 —— 常驻窗口不随旋转重建，构造期的快照会过期（issue #55 5.15）。
    private val currentBounds: (Context) -> Rect,
) : View(context) {

    /** 边缘光画在这个矩形上；旋转 / 分屏 / 折叠后由 [onSizeChanged] 换成新的。 */
    private var screenBounds: Rect = initialBounds

    private class Ring(val x: Float, val y: Float, val startAt: Long, val holdMs: Long, val delayMs: Long)

    private class Trail(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val startAt: Long)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The panel's own scale. Every size this view draws is written in dp and converted here, so
     * the ring a finger gets is the same ring on a 2x phone and on a 4x one.
     */
    private val density = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

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

    /** 光晕内圈的复用 Path；外圈的各 band 形状走下面的 haloBandCache，几何不变就一直复用。 */
    private val haloInner = Path()
    /** 预计算好的 16 个 band 形状；几何不变就一直复用（issue #26）。 */
    private val haloBandCache = arrayOfNulls<Path>(FX_HALO_BANDS)
    /** 缓存对应的几何签名：屏幕矩形 + 圆角 + 光晕宽度，任何一项变了才重建。 */
    // 几何缓存键：**数字字段直接比较**，不拼字符串（每帧一次插值＝每帧一次分配）。
    private var haloKeyLeft = Float.NaN
    private var haloKeyTop = Float.NaN
    private var haloKeyRight = Float.NaN
    private var haloKeyBottom = Float.NaN
    private var haloKeyCorner = Float.NaN
    private var haloKeyFade = Float.NaN
    private var edgeStart = 0L

    /** The ring's shader, rebuilt only when the screen's shape changes — see [rainbow]. */
    private var rainbowShader: SweepGradient? = null
    private val rainbowRect = RectF()
    private val rainbowMatrix = Matrix()
    private var rainbowAppliedAt = Long.MIN_VALUE

    /** 回合是否亮着，由 [TouchFx] 的回合状态机开关 —— 是状态，不是有固定时长的动画。 */
    /** Where the light is heading (see [setTarget]); [drawnAlpha] chases it frame by frame. */
    private var targetAlpha = 0f
    private var edgeEnabled = false
    private var litStart = SystemClock.uptimeMillis()

    /**
     * 当前实际亮度（0 = 全灭）。onDraw 写、日志从别的线程读，所以 volatile。名字不叫 `alpha`：
     * `View.alpha` 是平台自己的属性，占了会改掉窗口的合成语义。
     */
    @Volatile
    private var drawnAlpha = 0f

    val lit: Boolean get() = drawnAlpha > 0.01f

    /** 实际亮度，供 trace 的 `alpha=` 读 —— 见 [TouchFx.trace]。 */
    val edgeAlpha: Float get() = drawnAlpha

    /**
     * What the light is aiming at, 0…1. Set by [TouchFx] (the decision), eased toward by [onDraw]
     * (the drawing). Not a boolean on purpose: two hard states is exactly what made the light blink
     * whenever the model thought for longer than the old 3s window.
     *
     * Idempotent, and re-aiming does not restart anything — a run that keeps operating the phone
     * simply stays at the same target, so a burst of actions cannot make it flicker.
     */
    fun setEdgeEnabled(enabled: Boolean) {
        if (edgeEnabled == enabled) return
        edgeEnabled = enabled
        if (!enabled) {
            targetAlpha = 0f
            drawnAlpha = 0f
            edgeStart = 0L
            invalidate()
        }
    }

    fun setTarget(value: Float) {
        val next = if (edgeEnabled) value.coerceIn(0f, 1f) else 0f
        // **短路必须看两个值**（issue #7）：只看 target 时，一旦出现「target 已经是 0、drawnAlpha
        // 还冻在 1.0」（窗口停摆的那段），之后每次 apply 都在这里 return，再也没有任何一帧去
        // 把它降下来 —— 灯就那么亮着几分钟。
        if (next == targetAlpha && drawnAlpha == next) return
        targetAlpha = next
        invalidate()
    }

    /**
     * 把这块画布的状态清干净（issue #7）。
     *
     * 窗口脱离窗口树之后 `postInvalidateDelayed` 不再生效，`onDraw` 停摆，`drawnAlpha` 会永久
     * 冻结在最后一帧的值上 —— 日志里 `target=0.0 alpha=1.0` 挂 4 分半就是这么来的。
     * 新实例本来就是干净的，所以这里也负责「窗口被系统收走、我们又拿回同一个实例」的情形。
     */
    fun reset() {
        targetAlpha = 0f
        drawnAlpha = 0f
        rings.clear()
        trails.clear()
        // 0 让 edgeElapsed 变成极大值 ⇒ 不再画那圈扩散的边光。
        edgeStart = 0L
        litStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun addRing(x: Float, y: Float, holdMs: Long, delayMs: Long) {
        rings += Ring(x, y, SystemClock.uptimeMillis(), holdMs, delayMs)
        invalidate()
    }

    fun addTrail(x1: Float, y1: Float, x2: Float, y2: Float) {
        trails += Trail(x1, y1, x2, y2, SystemClock.uptimeMillis())
        invalidate()
    }

    fun flashEdge() {
        if (!edgeEnabled) return
        edgeStart = SystemClock.uptimeMillis()
        invalidate()
    }

    /**
     * 窗口常驻，只 addView 一次（见类注释），所以构造期的 [screenBounds] 会一直用到下次重建 ——
     * 旋转、分屏、折叠屏展开之后它还停在旧尺寸上，边缘光于是短在一边或者溢出屏幕（issue #55 5.15）。
     * 这里重新问一次显示器：只有真的变了才换，首次布局两者本来就相等，等于空操作。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val actual = currentBounds(getContext())
        if (actual != screenBounds) {
            screenBounds = actual
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val edgeElapsed = now - edgeStart
        // 零绘制早退（spec v2 改动四）：目标为 0、已淡尽、也没有手势动画时，一帧都不画。窗口常驻着空转，
        // 代价只是一次什么都不画的回调，比反复 addView/removeView 便宜得多。
        if (targetAlpha <= 0f && drawnAlpha <= 0f && rings.isEmpty() && trails.isEmpty() &&
            edgeElapsed >= FX_EDGE_DURATION_MS
        ) {
            return
        }
        super.onDraw(canvas)

        // 倒序手工删，不用 `removeAll { … }`（issue #56 R32）：捕获 `now` 的 lambda 每帧各分配
        // 一个 Function1 并装箱，而这里是 onDraw 热路径；同文件的 drawGlow 早已做过零分配优化，
        // 这两处漏网。
        var ringIndex = rings.size - 1
        while (ringIndex >= 0) {
            val ring = rings[ringIndex]
            if (now - ring.startAt > ring.delayMs + ring.holdMs + FX_RING_DURATION_MS) {
                rings.removeAt(ringIndex)
            }
            ringIndex--
        }
        for (ring in rings) drawRing(canvas, ring, now)

        var trailIndex = trails.size - 1
        while (trailIndex >= 0) {
            val trail = trails[trailIndex]
            if (now - trail.startAt > FX_SWIPE_DURATION_MS) trails.removeAt(trailIndex)
            trailIndex--
        }
        for (trail in trails) drawTrail(canvas, trail, now)

        // 浅呼吸只调节当前亮度；目标归零时随之淡出，不在回合中途熄灭。
        val phase = ((now - litStart) % FX_BREATH_PERIOD_MS).toFloat() / FX_BREATH_PERIOD_MS
        val wave = (1f - cos(phase * 2f * PI.toFloat())) / 2f
        val aim = targetAlpha * (1f - FX_BREATH_DEPTH * wave)
        drawnAlpha = if (aim > drawnAlpha) {
            (drawnAlpha + FX_FADE_STEP).coerceAtMost(aim)
        } else {
            (drawnAlpha - FX_FADE_STEP).coerceAtLeast(aim)
        }
        // Under the flash, so an action still reads as brighter than the state it happened in.
        if (edgeEnabled && drawnAlpha > 0f) drawGlow(canvas, drawnAlpha, now)
        if (edgeEnabled && edgeElapsed < FX_EDGE_DURATION_MS) drawEdge(canvas, edgeElapsed, now)

        val animating = rings.isNotEmpty() || trails.isNotEmpty() || edgeElapsed < FX_EDGE_DURATION_MS
        when {
            // A gesture is a burst of frames; the breath is a slow glow and 25fps is plenty for
            // it, so the two run at their own rates instead of the window spinning at 60fps.
            animating -> postInvalidateOnAnimation()
            // 还有目标、或还没淡尽 ⇒ 继续排帧；淡尽之后这里什么也不排，窗口就安静下来了。
            targetAlpha > 0f || drawnAlpha > 0f -> postInvalidateDelayed(FX_BREATH_FRAME_MS)
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
        paint.color = fxBrandColor
        paint.alpha = (alpha * 0.2f * 255).toInt()
        canvas.drawCircle(ring.x, ring.y, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(FX_RING_STROKE_DP)
        paint.color = fxBrandColor
        paint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(ring.x, ring.y, radius, paint)
    }

    private fun drawTrail(canvas: Canvas, trail: Trail, now: Long) {
        val progress = ((now - trail.startAt).toFloat() / FX_SWIPE_DURATION_MS).coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(FX_TRAIL_STROKE_DP)
        paint.color = fxBrandColor
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
        // Proportional to the short side, then capped: the fraction alone keeps growing with the
        // panel, and past [FX_HALO_MAX_WIDTH_DP] the light reads as a bar laid on the screen.
        // **缓存键拿「输入」比，不能拿被自己写的输出比**（issue #56 R3）。
        // 原判据是 `haloKeyLeft != edgeRect.left` 这类 —— 而 `edgeRect` 的唯一写入点就是
        // 缓存未命中分支里的 `edgeShape()`，等于拿自己的上一次结果比自己 ⇒ 首帧之后恒「命中」，
        // `onSizeChanged` 改了 `screenBounds` 也不重建 ⇒ 旋转/分屏后边缘光仍按旧屏幕尺寸绘制
        // （5.15 因此实际未修）。现在由 `screenBounds` 直接推目标矩形来比，并且**先刷新几何再取
        // shader** —— 原来的顺序会让 `rainbow(edgeRect, …)` 在尺寸刚变的那一帧读到旧矩形。
        val inset = FX_EDGE_INSET_PX
        val edgeLeft = screenBounds.left + inset
        val edgeTop = screenBounds.top + inset
        val edgeRight = screenBounds.right - inset
        val edgeBottom = screenBounds.bottom - inset
        val fadeWidth =
            (minOf(edgeRight - edgeLeft, edgeBottom - edgeTop) * FX_HALO_WIDTH_FRACTION)
                .coerceAtMost(dp(FX_HALO_MAX_WIDTH_DP))
        // **band 形状与时间/透明度无关**（只依赖屏幕几何），所以缓存在几何变化前绝不重建。
        // 原来每帧 16 次「复制外轮廓 + 重建内轮廓 + Path.op」是要掉帧的主因（issue #26 原因二）；
        // 现在每帧只剩「设 alpha + drawPath」，画出来的东西逐像素不变。
        //
        // 两处每帧白付的代价（优化清单 tools-P1）已改掉：
        //  · `edgeShape()` 每帧重建外轮廓路径 —— 它只依赖几何，移进缓存分支；
        //  · 每帧用字符串插值拼 6 个数值做相等比较 —— 改为直接比较数字字段，零分配。
        if (haloKeyLeft != edgeLeft || haloKeyTop != edgeTop ||
            haloKeyRight != edgeRight || haloKeyBottom != edgeBottom ||
            haloKeyCorner != cornerPx || haloKeyFade != fadeWidth
        ) {
            haloKeyLeft = edgeLeft
            haloKeyTop = edgeTop
            haloKeyRight = edgeRight
            haloKeyBottom = edgeBottom
            haloKeyCorner = cornerPx
            haloKeyFade = fadeWidth
            val outer = edgeShape()
            for (band in 0 until FX_HALO_BANDS) {
                val endT = (band + 1) / FX_HALO_BANDS.toFloat()
                val path = haloBandCache[band] ?: Path().also { haloBandCache[band] = it }
                // Each band begins at the previous inner boundary, so their opacity does not stack.
                path.set(if (band == 0) outer else haloInner)
                haloInnerBoundary(fadeWidth * endT)
                path.op(haloInner, Path.Op.DIFFERENCE)
            }
        }
        glowPaint.style = Paint.Style.FILL
        // 几何已在上面刷新，这里读到的 `edgeRect` 一定是本次屏幕尺寸对应的那个。
        glowPaint.shader = rainbow(edgeRect, now)
        for (band in 0 until FX_HALO_BANDS) {
            val level = (FX_HALO_LEVELS[band] * alpha * 255f).toInt().coerceIn(0, 255)
            if (level <= 0) continue
            val path = haloBandCache[band] ?: continue
            glowPaint.alpha = level
            canvas.drawPath(path, glowPaint)
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
            rainbowAppliedAt = Long.MIN_VALUE
        }
        // The state glow and gesture flash share the same shader and timestamp in one draw.
        if (rainbowAppliedAt != now) {
            val degrees = (now % FX_HUE_PERIOD_MS).toFloat() / FX_HUE_PERIOD_MS * 360f
            rainbowMatrix.setRotate(degrees, rect.centerX(), rect.centerY())
            shader.setLocalMatrix(rainbowMatrix)
            rainbowAppliedAt = now
        }
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
