package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OffsetCache] 的命中条件。
 *
 * 缓存错了不会崩，只会**读到错误位置**（把 checkpoint 之后的内容当历史发出去，或少发一段）——
 * 所以这里逐条钉住四个失效条件：换会话 / 换代次 / 换消息 id / 没存过。
 */
class OffsetCacheTest {

    @Test
    fun `hits only for the same chat, generation and message`() {
        val cache = OffsetCache()
        cache.put(chatId = "c1", gen = 2, messageId = "m1", offset = 4_096L)
        assertEquals(4_096L, cache.get("c1", 2, "m1"))
    }

    @Test
    fun `a different chat never hits`() {
        val cache = OffsetCache()
        cache.put("c1", 2, "m1", 4_096L)
        assertNull("换会话必须不命中（否则拿别人的偏移去读）", cache.get("c2", 2, "m1"))
    }

    @Test
    fun `a rewritten log never hits`() {
        val cache = OffsetCache()
        cache.put("c1", 2, "m1", 4_096L)
        assertNull("换代重写之后旧偏移没有意义", cache.get("c1", 3, "m1"))
    }

    @Test
    fun `a new checkpoint never hits`() {
        val cache = OffsetCache()
        cache.put("c1", 2, "m1", 4_096L)
        assertNull("换了 checkpoint 消息就要重新找", cache.get("c1", 2, "m2"))
    }

    @Test
    fun `an empty cache never hits`() {
        assertNull(OffsetCache().get("c1", 0, "m1"))
    }

    @Test
    fun `clear drops the entry`() {
        val cache = OffsetCache()
        cache.put("c1", 2, "m1", 4_096L)
        cache.clear()
        assertNull(cache.get("c1", 2, "m1"))
    }
}
