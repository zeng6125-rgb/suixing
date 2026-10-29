package com.suixing.agent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBoundsTest {
    @Test fun ballStaysReachableAfterDraggingAndResizing() {
        assertEquals(0, boundedOverlayOffset(-120, 800, 44))
        assertEquals(756, boundedOverlayOffset(900, 800, 44))
        assertEquals(156, boundedOverlayOffset(700, 200, 44))
        assertEquals(0, boundedOverlayOffset(700, 200, 300))
        assertEquals(120, boundedOverlayOffset(120, 800, 44))
    }

    @Test fun traceTrimsMultibyteTextByBytesWithoutSplittingCodePoints() {
        val text = "启动🙂随行".repeat(60_000)
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (budget in listOf(0, 1, 2, 3, 4, 2048, 131072)) {
            val head = utf8Prefix(bytes, budget)
            val tail = utf8Suffix(bytes, budget)
            assertTrue(head.toByteArray(Charsets.UTF_8).size <= budget)
            assertTrue(tail.toByteArray(Charsets.UTF_8).size <= budget)
            assertTrue(text.startsWith(head))
            assertTrue(text.endsWith(tail))
            assertFalse(head.contains('\uFFFD'))
            assertFalse(tail.contains('\uFFFD'))
        }
    }
}
