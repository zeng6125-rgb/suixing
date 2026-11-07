package com.suixing.agent.virtualscreen

import android.os.Build
import android.view.KeyEvent
import com.suixing.agent.capability.Shell
import com.suixing.agent.service.SuixingAccessibilityService

/**
 * 往副屏上送输入（手动接管）。
 *
 * 触摸走无障碍的手势投递（`GestureDescription.Builder.setDisplayId`）：投递到指定屏，
 * 需要 API 30+，旧系统明确拒绝；不在主屏产生触摸，也不需要 root。
 * 按键走 root 的 `input -d <displayId> keyevent`：
 * `performGlobalAction` 没有屏参数、只作用于当前有焦点的屏，对副屏不可靠。
 */
object VirtualScreenInput {

    fun accessibilityReady(): Boolean = supportsDisplayInput() && SuixingAccessibilityService.isConnected()

    private fun supportsDisplayInput(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun tap(displayId: Int, x: Float, y: Float): Boolean {
        if (!supportsDisplayInput() || displayId < 0) return false
        return SuixingAccessibilityService.instance()?.tap(x, y, displayId = displayId) ?: false
    }

    suspend fun longPress(displayId: Int, x: Float, y: Float): Boolean {
        if (!supportsDisplayInput() || displayId < 0) return false
        return SuixingAccessibilityService.instance()?.longPress(x, y, displayId = displayId) ?: false
    }

    suspend fun swipe(
        displayId: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
    ): Boolean {
        if (!supportsDisplayInput() || displayId < 0) return false
        return SuixingAccessibilityService.instance()
            ?.swipe(x1, y1, x2, y2, durationMs = durationMs, displayId = displayId) ?: false
    }

    suspend fun back(displayId: Int): Boolean = key(displayId, KeyEvent.KEYCODE_BACK)

    suspend fun home(displayId: Int): Boolean = key(displayId, KeyEvent.KEYCODE_HOME)

    suspend fun key(displayId: Int, keyCode: Int): Boolean {
        if (!supportsDisplayInput() || displayId < 0) return false
        return Shell.root("input -d $displayId keyevent $keyCode").ok
    }
}
