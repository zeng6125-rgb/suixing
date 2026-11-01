package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #67 P1-3：热 A→B→A 不重读正文。判据是"有没有可用的现成正文"，
 * 而不是"有没有走过冷路径"。
 */
class ColdOpenCacheTest {

    @Test
    fun `a cached conversation is never read from disk again`() {
        // 切回 A：正文还在缓存里 ⇒ 不读盘（这就是热 A→B→A 不重读）
        assertFalse(needsColdRead(hasLiveSession = false, cachedIsPresent = true))
    }

    @Test
    fun `a live session wins over disk`() {
        assertFalse(needsColdRead(hasLiveSession = true, cachedIsPresent = false))
        assertFalse(needsColdRead(hasLiveSession = true, cachedIsPresent = true))
    }

    @Test
    fun `only a cold miss reads the tail from disk`() {
        assertTrue(needsColdRead(hasLiveSession = false, cachedIsPresent = false))
    }
}
