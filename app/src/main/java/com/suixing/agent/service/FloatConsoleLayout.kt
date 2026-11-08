package com.suixing.agent.service

import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.suixing.agent.R
import com.suixing.agent.core.Core
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.suixing.agent.service.FloatConsoleService.Companion.TOUCH_TARGET_DP
import com.suixing.agent.service.FloatConsoleService.Companion.PANEL_DEFAULT_DP
import com.suixing.agent.service.FloatConsoleService.Companion.PANEL_MIN_DP
import com.suixing.agent.service.FloatConsoleService.Companion.PANEL_MAX_DP
import com.suixing.agent.service.FloatConsoleService.Companion.PANEL_MIN_HEIGHT_DP
import com.suixing.agent.service.FloatConsoleService.Companion.PANEL_MAX_HEIGHT_DP

/**
 * The panel's width: whatever the user last dragged it to, or 268dp, and never wider than the
 * window it floats in.
 *
 * A fixed 268dp is 74% of a 360dp screen and 100% of nothing at all in split screen, where the
 * window can be 300dp wide: the card then ran off the right edge and its own text was cut.
 */
internal fun FloatConsoleService.panelWidth(): Int = minOf(
    dp(consoleSettings.widthDp.takeIf { it > 0 } ?: PANEL_DEFAULT_DP),
    windowWidthPx() - dp(24),
).coerceAtLeast(panelMinWidth())

/** The panel's width, re-applied whenever the stored one changes under it. */
internal fun FloatConsoleService.applyPanelWidth() {
    (panel.layoutParams as? LinearLayout.LayoutParams)?.let { layout ->
        val width = panelWidth()
        if (layout.width == width) return
        layout.width = width
        panel.layoutParams = layout
    }
}

internal fun FloatConsoleService.panelMinWidth(): Int = dp(PANEL_MIN_DP)

/** The widest the handle may drag: the ceiling, or the window, whichever is smaller. */
internal fun FloatConsoleService.panelMaxWidth(): Int =
    minOf(dp(PANEL_MAX_DP), windowWidthPx() - dp(24)).coerceAtLeast(panelMinWidth())

/**
 * 内容区高度：用户拖过就是那个固定值（夹在窗口高内），没拖过（0）就是 WRAP_CONTENT 随内容
 * 自适应 —— 面板默认保持紧凑，只有用户主动拖高才写死。
 */
internal fun FloatConsoleService.contentHeightPx(): Int {
    val stored = consoleSettings.heightDp
    return if (stored <= 0) {
        LinearLayout.LayoutParams.WRAP_CONTENT
    } else {
        dp(stored).coerceIn(contentMinHeight(), contentMaxHeight())
    }
}

/** The content area's height, re-applied whenever the stored one changes under it. */
internal fun FloatConsoleService.applyPanelHeight() {
    (contentScroll.layoutParams as? LinearLayout.LayoutParams)?.let { layout ->
        val height = contentHeightPx()
        if (layout.height == height) return
        layout.height = height
        contentScroll.layoutParams = layout
    }
}

internal fun FloatConsoleService.contentMinHeight(): Int = dp(PANEL_MIN_HEIGHT_DP)

/** The tallest the handle may drag: the ceiling, or the window, whichever is smaller. */
internal fun FloatConsoleService.contentMaxHeight(): Int =
    minOf(dp(PANEL_MAX_HEIGHT_DP), windowHeightPx() - dp(24)).coerceAtLeast(contentMinHeight())

/** The height of the window the console floats in, in px — see [windowWidthPx]. */
@Suppress("DEPRECATION")
internal fun FloatConsoleService.windowHeightPx(): Int {
    val manager = getSystemService(WindowManager::class.java)
        ?: return resources.displayMetrics.heightPixels
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val height = runCatching { manager.currentWindowMetrics.bounds.height() }.getOrDefault(0)
        if (height > 0) return height
    }
    // 与 [windowWidthPx] 保持同一套回退（issue #56 R16）：分屏下 `displayMetrics` 给的是
    // 应用窗口尺寸而不是真实屏高，少一级回退会让夹位算在错误的坐标系里。
    val point = Point()
    manager.defaultDisplay?.getRealSize(point)
    return point.y.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
}

/**
 * The width of the window the console floats in, in px — not the display's own.
 *
 * `resources.displayMetrics` reports the whole screen, so in split screen it hands the panel a
 * width the window does not have. `currentWindowMetrics` is the window's own bounds, which is
 * what a width cap has to be measured against; below R that call does not exist and the
 * display's real size is the best answer available.
 */
@Suppress("DEPRECATION")
internal fun FloatConsoleService.windowWidthPx(): Int {
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
internal fun FloatConsoleService.clampX(extraWidth: Int = 0) {
    val width = maxOf(root.width, extraWidth, dp(TOUCH_TARGET_DP))
    params.x = boundedOverlayOffset(params.x, windowWidthPx(), width)
    clampY()
}

internal fun FloatConsoleService.clampY() {
    // Keep the ball and the expanded panel reachable after a drag or configuration change.
    val height = if (panel.visibility == View.VISIBLE) {
        maxOf(root.height, dp(TOUCH_TARGET_DP))
    } else {
        dp(TOUCH_TARGET_DP)
    }
    params.y = boundedOverlayOffset(params.y, windowHeightPx(), height)
}

/**
 * The panel's resize handle: drag sideways to widen or narrow the card.
 *
 * 二维（0.8.108 起）：横向拖改宽度，纵向拖改内容区高度。高度落在 [contentScroll] 这个可滚
 * 容器上，拖矮也不会裁掉输入框/停止按钮（它们在容器之外）—— 所以「固定高度会裁掉 stop 行」
 * 那个老顾虑不再成立，用户要的「能调高度」就落在这里。默认（没拖过）高度仍随内容自适应。
 */
internal fun FloatConsoleService.resizeHandle(): View = View(this).apply {
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
internal fun FloatConsoleService.onResizeTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
    MotionEvent.ACTION_DOWN -> {
        resizeStartX = event.rawX
        resizeStartWidth = panel.width
        resizeStartY = event.rawY
        // 从自适应起步时，用当前实测高度当锚点，这样第一下拖动是连续的、不会跳。
        resizeStartHeight = contentScroll.height
        resizeMinWidth = panelMinWidth()
        resizeMaxWidth = panelMaxWidth()
        resizeMinHeight = contentMinHeight()
        resizeMaxHeight = contentMaxHeight()
        true
    }

    MotionEvent.ACTION_MOVE -> {
        val width = (resizeStartWidth + (event.rawX - resizeStartX)).toInt()
            .coerceIn(resizeMinWidth, resizeMaxWidth)
        (panel.layoutParams as? LinearLayout.LayoutParams)?.let { layout ->
            layout.width = width
            panel.layoutParams = layout
        }
        // 纵向：手柄在底部，手指往下拖＝要看更多内容＝内容区变高。夹在 [min,max] 内。
        val height = (resizeStartHeight + (event.rawY - resizeStartY)).toInt()
            .coerceIn(resizeMinHeight, resizeMaxHeight)
        (contentScroll.layoutParams as? LinearLayout.LayoutParams)?.let { layout ->
            layout.height = height
            contentScroll.layoutParams = layout
        }
        true
    }

    MotionEvent.ACTION_UP -> {
        rememberPanelWidth(panel.width)
        rememberPanelHeight()
        // A wider panel may no longer fit where a narrower one did, so the window is pulled
        // back inside the screen with the width it just got.
        clampX(panelWidth())
        if (attached) runCatching { windowManager.updateViewLayout(root, params) }
        true
    }

    else -> false
}

/** Remembers the dragged width in dp, so a density change cannot resize the card on its own. */
internal fun FloatConsoleService.rememberPanelWidth(px: Int) {
    val widthDp = (px / resources.displayMetrics.density).roundToInt()
    Core.appScope.launch {
        Core.settings.update { it.copy(consoleWidthDp = widthDp.coerceIn(PANEL_MIN_DP, PANEL_MAX_DP)) }
    }
}

/**
 * Remembers the dragged content height in dp, same reason as the width.
 *
 * 存的是 [contentScroll] 的高度；重启后 [contentHeightPx] 把它读回来，面板保持用户拖的高度。
 */
internal fun FloatConsoleService.rememberPanelHeight() {
    val heightDp = (contentScroll.height / resources.displayMetrics.density).roundToInt()
    Core.appScope.launch {
        Core.settings.update {
            it.copy(consoleHeightDp = heightDp.coerceIn(PANEL_MIN_HEIGHT_DP, PANEL_MAX_HEIGHT_DP))
        }
    }
}

/**
 * Expanding makes the window focusable, which is the only way an overlay's text
 * field can receive typing; collapsing hands the keyboard back to the app behind.
 */
internal fun FloatConsoleService.setExpanded(expanded: Boolean) {
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
        // **夹位要等布局跑完**（issue #56 R9）：刚把 panel 置 VISIBLE、宽度刚改，
        // `clampY` 读到的 `root.height` 还是收起态的 44dp ⇒ 靠近底部展开时按过期高度
        // 算出的 Y 会把面板吊在屏幕外。放到 post 里，等新测量结果出来再夹一次。
        clampX(panelWidth())
        root.post {
            if (attached) {
                clampX(panelWidth())
                runCatching { windowManager.updateViewLayout(root, params) }
            }
        }
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
