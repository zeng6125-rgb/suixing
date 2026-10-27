package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageCacheKeyTest {
    @Test fun `equal content has the same key across byte array instances`() {
        assertEquals(imageCacheKey(byteArrayOf(1, 2, 3)), imageCacheKey(byteArrayOf(1, 2, 3)))
        assertNotEquals(imageCacheKey(byteArrayOf(1, 2, 3)), imageCacheKey(byteArrayOf(1, 2, 4)))
    }

    @Test fun `key uses a full content digest`() {
        assertEquals("screenshot:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", imageCacheKey(byteArrayOf()))
    }
}
