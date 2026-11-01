package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 压缩卡与助手卡的对齐口径（用户提过三次"宽度对不上"）。
 *
 * 这几个数字以前只存在于一行 `if (cardMode) 16.dp else flatPad` 里，没有测试；
 * 现在钉住：卡片模式恒为 16，扁平模式跟随设置，且负数不会变成负内边距。
 */
class CheckpointInsetTest {

    @Test
    fun `card mode always uses the card inner padding`() {
        assertEquals(16f, checkpointInsetDp(cardMode = true, flatPad = 0f))
        // 扁平留白不该影响卡片模式
        assertEquals(16f, checkpointInsetDp(cardMode = true, flatPad = 24f))
    }

    @Test
    fun `flat mode follows the appearance setting`() {
        assertEquals(0f, checkpointInsetDp(cardMode = false, flatPad = 0f))
        assertEquals(12f, checkpointInsetDp(cardMode = false, flatPad = 12f))
        assertEquals(24f, checkpointInsetDp(cardMode = false, flatPad = 24f))
    }

    @Test
    fun `negative padding can never be produced`() {
        assertEquals(0f, checkpointInsetDp(cardMode = false, flatPad = -8f))
    }
}
