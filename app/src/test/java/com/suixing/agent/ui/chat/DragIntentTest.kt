package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #131 的方向判定：侧滑（水平主导）不该被当成阅读。
 *
 * 这些用例锁的是**边界**，不是手感：阈值仍是 4px（历史上的"轻微纵滑也停跟"必须保住），
 * 但横向主导时不能再动追底状态。
 */
class DragIntentTest {

    @Test
    fun `tiny jitter in any direction stays idle`() {
        assertEquals(DragIntent.Idle, dragIntent(0f, 0f))
        assertEquals(DragIntent.Idle, dragIntent(3.9f, 0f))
        assertEquals(DragIntent.Idle, dragIntent(0f, 3.9f))
        assertEquals(DragIntent.Idle, dragIntent(-3f, 3f))
    }

    @Test
    fun `vertical past threshold is a scroll`() {
        assertEquals(DragIntent.VerticalScroll, dragIntent(0f, 4.1f))
        assertEquals(DragIntent.VerticalScroll, dragIntent(0f, -40f))
        // 纵向占优的斜滑也算滚动（内容确实在上下走）
        assertEquals(DragIntent.VerticalScroll, dragIntent(6f, 30f))
    }

    @Test
    fun `horizontal dominant drag never cancels follow`() {
        // 侧滑抽屉：主体是横向，带一点纵向抖动
        assertEquals(DragIntent.Moved, dragIntent(60f, 5f))
        assertEquals(DragIntent.Moved, dragIntent(-120f, 20f))
        // 只有横向、没有纵向
        assertEquals(DragIntent.Moved, dragIntent(30f, 0f))
    }

    @Test
    fun `45 degrees counts as scroll so content is not yanked back`() {
        assertEquals(DragIntent.VerticalScroll, dragIntent(20f, 20f))
        assertEquals(DragIntent.Moved, dragIntent(30f, 29f))
    }

    @Test
    fun `threshold stays at four pixels`() {
        // 抬高阈值会把"轻微纵滑"重新变成松手被贴回底部（历史回归），这里钉住它
        assertEquals(4f, CANCEL_FOLLOW_PX)
        assertEquals(DragIntent.Idle, dragIntent(0f, 4f))
        assertEquals(DragIntent.VerticalScroll, dragIntent(0f, 4.0001f))
    }
}
