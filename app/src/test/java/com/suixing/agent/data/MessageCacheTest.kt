package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MessageCache] 的容量与淘汰行为。
 *
 * 这些不是"编译能过"层面的断言：0.8.316 之后 `openConversation` 依赖这份缓存，
 * 淘汰顺序错了的表现是"切回某个会话看到别人的消息"或"缓存永远命中不了、还是反复读盘"。
 */
class MessageCacheTest {
    private fun messages(tag: String, count: Int = 2): List<StoredMessage> =
        (0 until count).map { StoredMessage(id = "$tag-$it", role = Roles.USER, text = "$tag $it") }

    @Test
    fun `keeps at most the configured number of chats`() {
        val cache = MessageCache(capacity = 5)
        // **每一份只建一次**：`StoredMessage.timestamp` 默认是 `System.currentTimeMillis()`，
        // 两次 `messages("c6")` 会各自取"当前毫秒" —— 跨一次毫秒边界两个列表就不相等，
        // 这条测试因此偶发失败（真机 CI 上出现过两次）。
        val six = messages("c6")
        for (i in 1..5) cache.put("c$i", messages("c$i"))
        cache.put("c6", six)
        assertEquals("capacity 5 means the oldest is dropped", 5, cache.size())
        assertNull("c1 was evicted", cache.get("c1"))
        assertEquals(six, cache.get("c6"))
    }

    @Test
    fun `eviction follows access order, not insertion order`() {
        val cache = MessageCache(capacity = 3)
        cache.put("a", messages("a"))
        cache.put("b", messages("b"))
        cache.put("c", messages("c"))
        // 读一下 a：它变成最近使用 ⇒ 下一个被淘汰的应该是 b。
        cache.get("a")
        cache.put("d", messages("d"))
        // 顺序语义：put a,b,c ⇒ [a,b,c]；get(a) ⇒ [b,c,a]（a 变最近）；put(d) 淘汰最旧的 b
        // ⇒ [c,a,d]。**旧的（c）在前、新的（a、d）在后。**
        assertEquals(listOf("c", "a", "d"), cache.keys())
        assertNull("b is the least recently used", cache.get("b"))
    }

    @Test
    fun `an empty list is never stored`() {
        val cache = MessageCache(capacity = 5)
        cache.put("c1", emptyList())
        assertEquals("nothing to cache", 0, cache.size())
        assertNull(cache.get("c1"))
        cache.put("", messages("x"))
        assertEquals("an empty id is not a conversation", 0, cache.size())
    }

    @Test
    fun `rewriting the same chat does not grow the cache`() {
        // putSession 在流式期间每 ~120ms 调一次 cacheMessages —— 反复写同一个会话
        // 绝不能把别的会话挤掉。这条钉住它。
        val cache = MessageCache(capacity = 5)
        cache.put("other", messages("other"))
        repeat(500) { cache.put("streaming", messages("streaming", it % 10 + 1)) }
        assertEquals("still two chats", 2, cache.size())
        assertTrue("the other chat survived the stream", cache.get("other") != null)
    }

    @Test
    fun `remove drops one chat`() {
        val cache = MessageCache(capacity = 5)
        cache.put("a", messages("a"))
        cache.put("b", messages("b"))
        cache.remove("a")
        assertEquals(listOf("b"), cache.keys())
    }
}
