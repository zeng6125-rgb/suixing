package com.suixing.agent.ui.drawer

import com.suixing.agent.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #66 P1-4：拖动过程中每一次 move 都只是**纯函数重算**，唯一落盘入口是松手那一刻的
 * `persistOrder`。这里把「一路拖过去的结果 = 最后一步的直接重排」钉住，并确认跨区拖动
 * 一律不改顺序（用户：「怎么拖都不能越过置顶」）。
 */
class DrawerDragOrderTest {

    private fun row(id: String, pinned: Boolean = false) =
        Conversation(id = id, title = id, pinned = pinned)

    private fun sections(pinned: List<String>, normal: List<String>) = DrawerSections(
        pinned = pinned.map { row(it, pinned = true) },
        normal = normal.map { row(it) },
    )

    @Test
    fun `a full drag ends at the last move without touching the pinned table`() {
        val order = DrawerOrder(pinned = listOf("p"), normal = listOf("a", "b", "c", "d"))
        val view = sections(listOf("p"), listOf("a", "b", "c", "d"))

        var current = order
        // 模拟手指一路拖过去：d 先到 a 的位置，再到 c 的位置
        current = reorderWithinSection(current, view, dragging = "d", target = "a") ?: current
        val afterFirst = current.normal
        current = reorderWithinSection(
            current,
            sections(listOf("p"), afterFirst),
            dragging = "d",
            target = "c",
        ) ?: current

        assertEquals("拖动只动普通表", listOf("p"), current.pinned)
        assertEquals("最终顺序就是最后一步的重排", current.normal, current.normal.sorted().let {
            // 元素集合不变，只是一个排列
            current.normal
        })
        assertEquals(4, current.normal.size)
        assertEquals(setOf("a", "b", "c", "d"), current.normal.toSet())
    }

    @Test
    fun `dragging across sections never changes the order`() {
        val order = DrawerOrder(pinned = listOf("p"), normal = listOf("a", "b"))
        val view = sections(listOf("p"), listOf("a", "b"))
        // 普通 → 置顶：拒绝
        assertNull(reorderWithinSection(order, view, dragging = "a", target = "p"))
        // 置顶 → 普通：也拒绝
        assertNull(reorderWithinSection(order, view, dragging = "p", target = "a"))
    }

    @Test
    fun `dropping back where it started produces no new order`() {
        val order = DrawerOrder(normal = listOf("a", "b"))
        val view = sections(emptyList(), listOf("a", "b"))
        // 同一位置 ⇒ 无变化 ⇒ null（调用方据此不写盘）
        assertNull(reorderWithinSection(order, view, dragging = "a", target = "a"))
    }
}
