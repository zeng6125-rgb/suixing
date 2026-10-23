package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **瞬移机制的确证**（0.8.36x~0.8.405，用户连续九轮反馈）。
 *
 * 假说：GrowableText 改造后，一次发布产生的新 liveParts 列表与上一份
 * **逐元素是同一个对象**（只追加、不换实例）。Compose 的 `remember(keys)`
 * 用 equals 比较 key —— 新旧列表逐元素 === 相等 ⇒ 列表相等 ⇒
 * key「没变」⇒ 映射不重算 ⇒ **屏幕冻结**，直到列表结构变化（新块诞生）
 * 才刷新一次并把该块积累的全部文本一次性放出。
 *
 * 这个文件用真代码把每一步钉死。
 */
class LivePartsFreezeTest {

    @Test
    fun `appendText returns a list whose elements are the same objects`() {
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("def", 0)
        // 对象身份：同一实例被放回列表（这就是 GrowableText 的设计）
        assertTrue("元素必须是同一对象", before[0] === after[0])
    }

    @Test
    fun `a mutated part makes the old and new lists compare EQUAL - remember never invalidates`() {
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("defghi", 0)   // 内容从 3 字长到 9 字
        // **这就是冻结的证据**：内容已不同，但列表 equals 为 true
        // （逐元素 === 短路，长度比较根本轮不到参与）
        assertEquals("内容变了但列表判等 —— remember 的 key 永远命中缓存", before, after)
    }

    @Test
    fun `thinking parts have the same problem`() {
        val before = listOf<LivePart>(LivePart.Thinking("想"))
        val after = before.appendThinking("了更多", 0)
        assertEquals(before, after)
    }

    @Test
    fun `pre-GrowableText style immutable parts DID invalidate`() {
        // 0.8.359 之前的写法：每次发布创建新对象，内容不同 ⇒ equals false ⇒ remember 重算
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = listOf<LivePart>(LivePart.Text("abcdefghi"))
        assertFalse("不可变对象按内容判等 —— 这就是 0.8.359 流式正常的原因", before == after)
    }

    @Test
    fun `structure change does invalidate - matching the one-frame dump`() {
        // 列表长度变化（新块诞生）时 equals 为 false —— 对应「一次性吐出」恰好发生的新块时刻
        val before = listOf<LivePart>(LivePart.Text("abc"))
        val after = before.appendText("def", 0) + LivePart.Tools(emptyList())
        assertFalse(before == after)
    }
}
