package com.suixing.agent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘光的两道门（用户 2026-10-23：
 * 「边缘光的开关要全局，开了之后 UI 自动化操作显示边缘光，关了之后一律不显示；
 *   再加一个前台是否显示的开关，不独立，只在边缘光开启后才起作用」）。
 *
 * - **总开关关** ⇒ 一律不显示（不管第二个开关怎么设、不管前后台）。
 * - **总开关开 + 前台显示关** ⇒ 随行在前台不亮、后台照常亮。
 */
class EdgeLightTest {

    @Test
    fun `the master switch off hides everything`() {
        // 第二个开关无论怎么设、前后台都算，一律不显示。
        assertFalse(edgeLightVisible(masterOn = false, showInForeground = true, inForeground = true))
        assertFalse(edgeLightVisible(masterOn = false, showInForeground = true, inForeground = false))
        assertFalse(edgeLightVisible(masterOn = false, showInForeground = false, inForeground = true))
        assertFalse(edgeLightVisible(masterOn = false, showInForeground = false, inForeground = false))
    }

    @Test
    fun `with the second switch on the glow shows in both states`() {
        // 默认（保持 issue #6：随行自己在前台时也要显示）
        assertTrue(edgeLightVisible(masterOn = true, showInForeground = true, inForeground = true))
        assertTrue(edgeLightVisible(masterOn = true, showInForeground = true, inForeground = false))
    }

    @Test
    fun `with the second switch off the glow hides only in the foreground`() {
        assertFalse("前台不亮", edgeLightVisible(masterOn = true, showInForeground = false, inForeground = true))
        assertTrue("后台照常亮", edgeLightVisible(masterOn = true, showInForeground = false, inForeground = false))
    }
}
