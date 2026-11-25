package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EarlierPageAlignmentTest {
    private fun fakeItem(key: Any, index: Int, offset: Int): LazyListItemInfo =
        object : LazyListItemInfo {
            override val index: Int = index
            override val key: Any = key
            override val offset: Int = offset
            override val size: Int = 100
        }

    @Test fun `capture ignores load-earlier header and picks first real message row`() {
        val visible = listOf(
            fakeItem("load-earlier", index = 0, offset = -18),
            fakeItem("msg-10", index = 1, offset = 126),
            fakeItem("msg-11", index = 2, offset = 240),
        )
        val anchor = captureEarlierPageAnchor(visible)
        assertEquals(EarlierPageAnchor("msg-10", 126), anchor)
    }

    @Test fun `capture returns null when only header is visible`() {
        val visible = listOf(fakeItem("load-earlier", index = 0, offset = 0))
        assertNull(captureEarlierPageAnchor(visible))
    }

    @Test fun `drift measures exact offset shift or null when pushed offscreen`() {
        val anchor = EarlierPageAnchor("msg-10", 126)
        assertEquals(0, earlierAnchorDriftPx(listOf(fakeItem("msg-10", 25, 126)), anchor))
        assertEquals(210, earlierAnchorDriftPx(listOf(fakeItem("msg-10", 25, 336)), anchor))
        assertNull(earlierAnchorDriftPx(listOf(fakeItem("msg-5", 20, 40)), anchor))
    }
}
