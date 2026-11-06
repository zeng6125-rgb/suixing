package com.suixing.agent.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #80：虚拟屏必须是「同一块屏按比例缩小」，而不是「换一块小得多的屏」。
 *
 * 判据是 **dp 一致**：`dp = px / (dpi / 160)`，像素和密度必须乘同一个比例。
 * 原来只缩像素、密度沿用主屏，dp 跟着缩到 45%，屏幕从 385x837dp 变成 173x376dp，
 * 低于 `sw360dp` 断点 ⇒ 应用进极窄布局、最小宽度限制被触发、界面挤成一团。
 */
class VirtualScreenDensityTest {

    private fun dp(px: Int, dpi: Int): Float = px / (dpi / 160f)

    @Test fun `a 480dpi phone keeps its dp size after scaling`() {
        // 这台机器的实测值：主屏 1156x2510 @ 480dpi。
        val phoneWidth = 1156
        val phoneHeight = 2510
        val phoneDpi = 480

        val virtualWidth = (phoneWidth * 0.45f).toInt()    // 520
        val virtualHeight = (phoneHeight * 0.45f).toInt()  // 1129
        val virtualDpi = VirtualScreenManager.scaledDensity(phoneDpi)  // 216

        assertEquals(216, virtualDpi)
        assertEquals(dp(phoneWidth, phoneDpi), dp(virtualWidth, virtualDpi), 1.5f)
        assertEquals(dp(phoneHeight, phoneDpi), dp(virtualHeight, virtualDpi), 1.5f)
    }

    @Test fun `the old code produced a screen far below the sw360dp breakpoint`() {
        // 这一条锁的是"改之前有多坏"，而不是"改之后多好"：密度不缩时 dp 只剩 173。
        // 如果有人把 scaledDensity 改回 densityDpi，上面那条会红，这条同时解释了原因。
        val noteDimension = dp(520, 480)
        assertTrue("不缩密度时宽度只有 ${noteDimension}dp", noteDimension < 360f)
    }

    @Test fun `density never drops below the ldpi floor`() {
        // 低密度设备：120dpi * 0.45 = 54，低于 ldpi 会让资源选择退化，所以兜到 120。
        assertEquals(120, VirtualScreenManager.scaledDensity(120))
        assertEquals(120, VirtualScreenManager.scaledDensity(240))
        // 正常密度仍然按比例走。
        assertEquals(216, VirtualScreenManager.scaledDensity(480))
        assertEquals(288, VirtualScreenManager.scaledDensity(640))
    }
}