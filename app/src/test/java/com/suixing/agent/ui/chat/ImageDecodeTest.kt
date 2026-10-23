package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图解码的两个纯逻辑（issue #38 方向 7）。
 *
 * 背景：`remember` 随组合销毁 ⇒ 列表项滚出视口就丢解码结果，滚回来重新解一张 1080p（几十毫秒）。
 * 于是"上下滑动很慢"。这里用**降采样**把解码成本按平方降下来，再用**小缓存**避免重复解码。
 */
class ImageDecodeTest {

    @Test
    fun `an image smaller than the cap is not downsampled`() {
        assertEquals(1, ImageDecode.sampleSizeFor(width = 720, height = 480, maxHeight = 720))
        assertEquals(1, ImageDecode.sampleSizeFor(width = 1080, height = 720, maxHeight = 720))
    }

    @Test
    fun `a tall screenshot is downsampled to a power of two that stays above the cap`() {
        // 1080p（1920 高）→ 除以 2 仍 >=720 ⇒ 2；除以 4 就 480 < 720 ⇒ 停。
        assertEquals(2, ImageDecode.sampleSizeFor(width = 1080, height = 1920, maxHeight = 720))
        // 4K 高（3840）→ 2 (=1920) → 4 (=960) → 8 (=480 <720 停) ⇒ 4。
        assertEquals(4, ImageDecode.sampleSizeFor(width = 2160, height = 3840, maxHeight = 720))
    }

    @Test
    fun `a degenerate size still yields a usable sample`() {
        assertEquals(1, ImageDecode.sampleSizeFor(width = 0, height = 0, maxHeight = 720))
        assertEquals(1, ImageDecode.sampleSizeFor(width = 100, height = 100, maxHeight = 0))
    }

    @Test
    fun `the cache keeps at most its capacity, evicting the least recently used`() {
        val cache = DecodedImageCache(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        assertEquals("A", cache.get("a"))          // a 变最近使用
        cache.put("c", "C")
        assertEquals(2, cache.sizeForTest())
        assertEquals("A", cache.get("a"))
        assertNull("最久未用的被淘汰", cache.get("b"))
        assertEquals("C", cache.get("c"))
    }

    @Test
    fun `two different images never share a key`() {
        val cache = DecodedImageCache()
        val one = cache.keyOf("x".repeat(100) + "AAA")
        val two = cache.keyOf("x".repeat(100) + "BBB")
        assertFalse("尾部不同 ⇒ 必须是不同的键", one == two)
        assertTrue("同一个内容必须得到同一个键", one == cache.keyOf("x".repeat(100) + "AAA"))
    }
}
