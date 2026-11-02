package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlierPagePositionTest {
    private data class Item(
        override val key: Any,
        override val offset: Int,
        override val index: Int = 0,
        override val size: Int = 100,
        override val contentType: Any? = null,
    ) : LazyListItemInfo

    @Test fun `paging header is not the reading anchor`() {
        val visible = listOf(Item("load-earlier", 0), Item("message", 60, 1))
        assertEquals(EarlierPageTarget(25, -60), earlierPageTarget(visible, mapOf("message" to 25)))
    }

    @Test fun `partly clipped message keeps its scroll offset sign`() {
        assertEquals(EarlierPageTarget(17, 84),
            earlierPageTarget(listOf(Item("message", -84, 3)), mapOf("message" to 17)))
    }

    @Test fun `last page header removal need not increase total item count`() {
        val visible = listOf(Item("load-earlier", -15), Item("message", 45, 1))
        // One added row replaces the header, so the total count is unchanged.
        assertEquals(EarlierPageTarget(1, -45), earlierPageTarget(visible, mapOf("message" to 1)))
    }

    @Test fun `tail status changes cannot shift the message target`() {
        val visible = listOf(Item("message", -32, 1), Item("error", 200, 2))
        assertEquals(EarlierPageTarget(9, 32), earlierPageTarget(visible, mapOf("message" to 9)))
    }

    @Test fun `current viewport at commit wins over where reading started`() {
        val incoming = mapOf<Any, Int>("first" to 8, "later" to 9)
        assertEquals(EarlierPageTarget(8, 12), earlierPageTarget(listOf(Item("first", -12)), incoming))
        assertEquals(EarlierPageTarget(9, 40), earlierPageTarget(listOf(Item("later", -40)), incoming))
    }

    @Test fun `a removed row falls back to the next surviving visible message`() {
        assertEquals(EarlierPageTarget(4, -90), earlierPageTarget(
            listOf(Item("removed", -10), Item("kept", 90)), mapOf("kept" to 4)))
        assertNull(earlierPageTarget(listOf(Item("load-earlier", 0)), emptyMap()))
    }

    @Test fun `disk page waits for both state projection and expanded window`() {
        val head = StoredMessage(id = "same", role = Roles.USER, text = "newer")
        val older = head.copy(text = "older") // Repeated legacy IDs are valid.
        val reveal = EarlierPageReveal(24, head)
        assertFalse(reveal.isReady(12, older))
        assertFalse(reveal.isReady(24, head))
        assertTrue(reveal.isReady(24, older))
    }

    @Test fun `memory page needs no disk publication`() {
        val reveal = EarlierPageReveal(24, null)
        assertFalse(reveal.isReady(12, null))
        assertTrue(reveal.isReady(24, null))
    }
}
