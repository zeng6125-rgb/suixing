package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportPreparationTest {
    @Test fun `top anchored windows only read nearby chronological rows`() {
        val rows = CountingRows(1_000_000)
        assertEquals((499_995..500_005).toList(), nearbyViewportRows(rows, 1, 500_000, 500_002,
            reverseLayout = false).toList())
        assertEquals(11, rows.reads)
        assertEquals((0..3).toList(), nearbyViewportRows((0..9).toList(), 1, 0, 0,
            reverseLayout = false))
    }

    @Test fun `legacy bottom offsets convert to the same physical row top`() {
        val height = 2_000
        val readingHeight = 600
        for (oldOffset in listOf(0, 100, 700, 1_800)) {
            val topOffset = legacyReadingOffset(oldOffset, height, readingHeight)
            assertEquals(readingHeight + oldOffset - height, -topOffset)
        }
        assertEquals(-500, legacyReadingOffset(0, 100, 600))
    }

    @Test fun `old bookmarks decode with a legacy offset and new bookmarks keep their orientation`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<com.suixing.agent.data.ViewportBookmark>(
            """{"conversationId":"chat","offset":700,"followLatest":false}""")
        assertEquals(false, old.topAnchored)
        val current = old.copy(topAnchored = true, offset = 123)
        assertEquals(current, json.decodeFromString<com.suixing.agent.data.ViewportBookmark>(
            json.encodeToString(com.suixing.agent.data.ViewportBookmark.serializer(), current)))
    }

    @Test fun `opening at newest only reads the four adjacent rows`() {
        val rows = CountingRows(1_000_000)
        assertEquals(listOf(999_996, 999_997, 999_998, 999_999),
            openingViewportRows(rows, rows.lastIndex).toList())
        assertEquals(4, rows.reads)
    }

    @Test fun `historical opening preserves order and a seven row bound`() {
        val rows = CountingRows(1_000_000)
        assertEquals((499_997..500_003).toList(), openingViewportRows(rows, 500_000).toList())
        assertEquals(7, rows.reads)
        assertEquals(listOf(0, 1, 2, 3), openingViewportRows(rows, 0))
    }

    @Test fun `reverse feed translates prefix and margin to ascending transcript order`() {
        val rows = CountingRows(100)
        assertEquals((85..95).toList(), nearbyViewportRows(rows, feedPrefix = 2,
            firstVisible = 10, lastVisible = 12).toList())
        assertEquals(11, rows.reads)
    }

    @Test fun `live prefix and earlier footer clamp at transcript boundaries`() {
        val rows = (0 until 100).toList()
        assertEquals((94..99).toList(), nearbyViewportRows(rows, 3, 0, 4))
        assertEquals((0..2).toList(), nearbyViewportRows(rows, 3, 104, 105))
        assertTrue(nearbyViewportRows(rows, 3, 200, 201).isEmpty())
        assertTrue(nearbyViewportRows(rows, 3, 20, 10).isEmpty())
    }

    @Test fun `empty conversation and obsolete historical anchor stay bounded`() {
        assertTrue(openingViewportRows(emptyList<Int>(), 0).isEmpty())
        assertTrue(nearbyViewportRows(emptyList<Int>(), 0, 0, 0).isEmpty())
        assertEquals(listOf(0, 1, 2, 3), openingViewportRows((0..9).toList(), -1))
        assertEquals(listOf(6, 7, 8, 9), openingViewportRows((0..9).toList(), 100))
    }

    private class CountingRows(override val size: Int) : AbstractList<Int>() {
        var reads = 0
        override fun get(index: Int): Int {
            check(index in 0 until size)
            reads++
            return index
        }
    }
}
