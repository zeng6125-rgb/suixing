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
 * 触摸特效的全部可调参数与几条纯策略函数（尺寸、时长、光环档位、边缘光阈值）。
 * 从 TouchFx 搬出来：它们只描述「多大、多久、多亮」，不碰窗口与状态。
 */

/**
 * dp, not px: the effects sit next to touches, which the system reports in px, but their sizes
 * belong to the panel's own scale. These were raw pixels — a 96px ring is 48dp on a 2x phone and
 * 24dp on a 4x one — so the same gesture read as a different gesture from one device to the next.
 */
internal const val FX_RING_MAX_RADIUS_DP = 32f
internal const val FX_RING_HOLD_RADIUS_DP = 8f
internal const val FX_RING_DURATION_MS = 260L
internal const val FX_LONG_PRESS_HOLD_MS = 600L
internal const val FX_SWIPE_DURATION_MS = 320L
/**
 * 边缘光此刻该不该亮（纯函数，便于单测）。
 *
 * 两条门：
 *  1. **总开关** [masterOn] —— 关掉就一律不显示（用户：「关了之后一律不显示」）。
 *  2. **前台开关** [showInForeground] —— 关掉时，JAgent自己在前台就不亮（后台照常亮）。
 *
 * 第二个开关**不独立**：总开关为 false 时，它无论怎么设都不亮。
 */
internal fun edgeLightVisible(
    masterOn: Boolean,
    showInForeground: Boolean,
    inForeground: Boolean,
): Boolean = masterOn && (showInForeground || !inForeground)

internal const val FX_EDGE_DURATION_MS = 350L

/**
 * A gesture lasts a fraction of a second, so without this a burst of taps leaves the edge
 * lit continuously and the flash stops meaning "something just happened".
 */
internal const val FX_EDGE_THROTTLE_MS = 800L

// 边缘光的墨色不再写死旧墨绿：由 TouchFx 按当前配色刷新。文件级字段是因为绘制它的
// FxView 是另一个类，读不到 object TouchFx 的私有成员。
internal const val FX_COLOR_BRAND_FALLBACK = 0xFF1F6B4C.toInt()
@Volatile
internal var fxBrandColor = FX_COLOR_BRAND_FALLBACK
internal const val FX_RING_STROKE_DP = 2f
internal const val FX_TRAIL_STROKE_DP = 1.5f

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
internal const val FX_HALO_WIDTH_FRACTION = 0.046f
internal const val FX_HALO_BANDS = 16
internal const val FX_HALO_FALLOFF = 1.55f
// Each band's falloff is constant; only the overall alpha changes during drawing.
internal val FX_HALO_LEVELS = FloatArray(FX_HALO_BANDS) { band ->
    (1f - band / FX_HALO_BANDS.toFloat()).pow(FX_HALO_FALLOFF)
}

/**
 * Ceiling for the band above, in dp.
 *
 * The fraction alone scales with the short side, which is right on a phone and wrong on anything
 * wider: a tablet's short side hands the band a width no phone ever showed, and the light stops
 * reading as a glow and starts reading as a bar. The cap is the widest band worth drawing.
 */
internal const val FX_HALO_MAX_WIDTH_DP = 24f

/**
 * 彩虹环的颜色：绕屏幕中心一圈的角度渐变，**首尾同色**，所以 0° 那里没有接缝。
 *
 * 红 → 橙 → 黄 → 绿 → 青 → 蓝 → 品红 → 红，八段等分（`SweepGradient` 的 positions 传 null）。
 * 颜色不透明，亮度由 `Paint.alpha` 调（shader 的输出会被 paint 的 alpha 调制，所以呼吸与闪光
 * 的 alpha 仍然有效）。
 */
internal val FX_RAINBOW = intArrayOf(
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
internal const val FX_HUE_PERIOD_MS = 2000L

/**
 * The breathing edge: the same stroke, turned up until it is seen at a glance without becoming
 * a band that fights the screen for attention, and taken through a slow wave.
 *
 * The user's verdict on the first attempt was 「不太明显」, and the numbers are the whole of
 * that judgement: a 10px stroke at 0.16~0.40 alpha on a dark wallpaper is a rumour. Wider
 * stroke, higher floor, and a ceiling that is still short of solid.
 */
internal const val FX_BREATH_PERIOD_MS = 2400L

/**
 * The two brightness levels the light aims at, and the ripple it breathes around them.
 *
 * Brightness is a **continuous quantity**, not a switch: the view eases toward the target every
 * frame (see `FxView.onDraw`), so nothing ever steps from dark to bright or back. The ripple rides
 * on top of whatever the target is, which is what makes a long run read as *alive* rather than as a
 * lamp that was left on.
 *
 * 1.0 while the phone is actually being operated, 0.26 while the run that drove it is still going —
 * dim enough to be peripheral, bright enough to say "don't touch it, I am still working".
 */
internal const val FX_ALPHA_HOT = 1f
internal const val FX_BREATH_DEPTH = 0.10f

/**
 * 边缘光的语义（v3，会话制）：**亮 = 一次 UI 控制会话还活着**。
 *
 * 会话由任意 UI 操作（含读屏）开启/续期；**非 UI 工具开始 = 显式结束会话**（灯灭）；整轮 run
 * 结束也收尾。这一版取代了 v2 的「全亮 / 暗呼吸」两档 + 3 秒静默：两档让控制序列每 3 秒降一档
 * （看着像中断），而「驱动过手机且 run 没结束就挂暗光」让灯在 agent 转去跑 shell / 思考的几分钟里
 * 一直亮（用户报的「一直显示」）。会话制下：控制就亮、不控制就灭、一轮控制内不中断 —— 与 Operit
 * 把光绑在「接管会话」生命周期上的思路一致（它是模式状态机加/移除光窗口，我们用会话 latch）。
 *
 * 桥接窗 [EPISODE_QUIET_MS] 只用来划日志里的回合边界（真机实测 UI→UI 间隔 p50=18.9s、
 * 最长 191.7s —— 任何超时窗都追不上这条长尾，所以它**不再决定灯亮不亮**，见 [apply]）。
 */
internal const val EPISODE_QUIET_MS = 20000L

/** 开启/续期回合的工具（读屏也是 UI 操作）。 */
internal val FX_UI_EPISODE_TOOLS = setOf(
    "ui_dump", "ui_screenshot",
    "ui_tap", "ui_long_press", "ui_swipe", "ui_click_text",
    "ui_type", "ui_type_into", "ui_key",
    "open_app", "app_action",
)

/** Shell commands only drive the UI when they actually contain a screen operation. */
internal val FX_SHELL_UI_RE = Regex(
    """\binput\s+(tap|swipe|text|keyevent|drag|roll)|\bam\s+start\b""",
)

internal fun shellToolDrivesUi(tool: String, detail: String?): Boolean =
    (tool == "run_shell" || tool == "bash") &&
        detail?.let { FX_SHELL_UI_RE.containsMatchIn(it) } == true

/**
 * How far the drawn brightness moves toward its target per frame.
 *
 * The light is a continuous quantity now, so this is the slope of every rise and fall: at
 * [FX_BREATH_FRAME_MS] per frame it takes about 0.8s for a full 0→1 sweep and about 0.2s to reach
 * the idle level — fast enough to feel like a response, slow enough that nothing "pops". A frame
 * that vanished instantly was read as 「坏了」, and a frame that jumped to full was read as a flash.
 */
internal const val FX_FADE_STEP = 0.05f

/**
 * 「目标已经归零，但亮度还挂在上面」多久算停摆（issue #7）。
 *
 * 正常淡出 0.8 秒走完，所以 2 秒足够区分「还在淡」和「onDraw 根本没跑」。超过就强制清零：
 * 窗口脱离窗口树之后 `postInvalidateDelayed` 不再排帧，`drawnAlpha` 会永远停在最后一帧。
 */
internal const val FX_STALL_MS = 2000L

/**
 * 窗口常驻、没有会话时 ticker 的间隔（issue #8）。
 *
 * 不能停表：`apply()` 里的停摆兜底只有在 `apply()` 被调用时才有效，停表就等于让它变成死代码。
 * 5 秒一跳，代价可以忽略，却能把「目标归零却还亮着」和「removeView 失败留下的孤儿窗口」捞回来。
 */
internal const val FX_IDLE_TICK_MS = 5000L

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
internal const val FX_EDGE_RADIUS_PX = 96f
internal const val FX_EDGE_INSET_PX = 0f

/**
 * The light is a state the user reads at a glance, not an animation to admire: 25fps while it
 * is on, and one poll per second to notice the run ending. Both stop the moment it goes out.
 */
internal const val FX_BREATH_FRAME_MS = 40L
internal const val FX_BREATH_TICK_MS = 1000L
