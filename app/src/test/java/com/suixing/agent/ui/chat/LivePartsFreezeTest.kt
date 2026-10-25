package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **冻结机制与它的修复**（0.8.36x~0.405 用户连续九轮反馈；2026-10-26 复核仍是真凶）。
 *
 * 假说：GrowableText 改造后，一次发布产生的新 liveParts 列表与上一份**逐元素是同一个对象**
 * （只追加、不换实例）。Compose 的 `remember(keys)` 用 equals 比较 key —— 新旧列表逐元素
 * === 相等 ⇒ 列表相等 ⇒ key「没变」⇒ 映射不重算 ⇒ **屏幕冻结**，直到列表结构变化（新块诞生）
 * 才刷新一次并把该块积累的全部文本一次性放出。
 *
 * **修复**：追加时放回**新实例**（version +1），列表判等为 false ⇒ state 真的发射 ⇒ 重组。
 * 缓冲仍然共享，O(1) 追加，没有回到 O(n²) 整串复制。
 */
class LivePartsFreezeTest {

    @Test
    fun `appending text produces a NEW instance so the list compares unequal`() {
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("def", 0)
        // 修复后：元素**不再是同一对象** ⇒ 列表判等为 false ⇒ remember 会失效、state 会发射。
        assertFalse("追加后必须换实例，否则列表判等为 true、屏幕冻结", before[0] === after[0])
        assertFalse("列表必须判不等", before == after)
    }

    @Test
    fun `content change makes the lists compare UNEQUAL`() {
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("defghi", 0)   // 内容从 3 字长到 9 字
        assertFalse("内容变了，列表必须判不等", before == after)
    }

    @Test
    fun `thinking parts behave the same way`() {
        val before = listOf<LivePart>(LivePart.Thinking("想"))
        val after = before.appendThinking("了更多", 0)
        assertFalse(before == after)
    }

    @Test
    fun `the buffer is shared - appending is not a full string copy`() {
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("def", 0)
        // 缓冲共享（O(1) 追加），只是外层实例换了。
        assertTrue(
            "缓冲应当共享",
            (before[0] as LivePart.Text).buffer === (after[0] as LivePart.Text).buffer,
        )
        assertEquals("abcdef", (after[0] as LivePart.Text).text)
    }

    @Test
    fun `structure change still invalidates`() {
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("def", 0) + LivePart.Tools(emptyList())
        assertFalse(before == after)
    }
}
