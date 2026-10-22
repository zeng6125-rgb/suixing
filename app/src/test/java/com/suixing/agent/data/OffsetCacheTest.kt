package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OffsetCache] 的命中条件与容量。
 *
 * 缓存错了不会崩，只会**读到错误位置**（把 checkpoint 之后的内容当历史发出去，或少发一段）；
 * 容量/淘汰错了只影响命中率 —— 而"来回切会话发消息"是这个应用最常做的事，
 * 单槽设计会让两边互相踢掉（评审 S2）。
 */
class OffsetCacheTest {

    @Test
    fun `hits only for the same chat, generation and message`() {
        val cache = OffsetCache()
        cache.put("c1", 2, "m1", 4_096L)
        assertEquals(4_096L, cache.get("c1", 2, "m1"))
        assertNull("换会话必须不命中（否则拿别人的偏移去读）", cache.get("c2", 2, "m1"))
        assertNull("换代重写之后旧偏移没有意义", cache.get("c1", 3, "m1"))
        assertNull("换了 checkpoint 消息就要重新找", cache.get("c1", 2, "m2"))
    }

    @Test
    fun `two chats keep their own slots`() {
        // 这条是 S2 的核心：A/B 交替发消息，两边都要命中。
        val cache = OffsetCache()
        cache.put("a", 0, "ma", 100L)
        cache.put("b", 0, "mb", 200L)
        assertEquals(100L, cache.get("a", 0, "ma"))
        assertEquals(200L, cache.get("b", 0, "mb"))
        cache.put("a", 0, "ma", 111L)
        assertEquals("重写同一格", 111L, cache.get("a", 0, "ma"))
        assertEquals("另一会话不受影响", 200L, cache.get("b", 0, "mb"))
    }

    @Test
    fun `capacity is bounded and evicts the least recently used`() {
        val cache = OffsetCache(capacity = 2)
        cache.put("a", 0, "m", 1L)
        cache.put("b", 0, "m", 2L)
        cache.get("a", 0, "m")            // a 变最近使用
        cache.put("c", 0, "m", 3L)
        assertEquals(2, cache.sizeForTest())
        assertEquals("刚用过的必须还在", 1L, cache.get("a", 0, "m"))
        assertNull("最久未用的被淘汰", cache.get("b", 0, "m"))
        assertTrue(cache.get("c", 0, "m") != null)
    }

    @Test
    fun `an empty cache never hits`() {
        assertNull(OffsetCache().get("c1", 0, "m1"))
    }
}
