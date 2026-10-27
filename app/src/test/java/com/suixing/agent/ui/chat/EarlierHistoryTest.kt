package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class EarlierHistoryTest {
    private fun message(id: String, text: String = id) =
        StoredMessage(id = id, role = Roles.USER, text = text, timestamp = 0L)

    @Test fun `a stream snapshot taken before paging keeps history and the new answer`() {
        val old = message("old")
        val tail = message("tail")
        val answer = message("answer")
        assertEquals(listOf(old, tail, answer), carryEarlierPages(listOf(tail, answer), 0, listOf(old)))
    }

    @Test fun `a snapshot between two pages receives only the missing page`() {
        val first = message("first")
        val second = message("second")
        val tail = message("tail")
        assertEquals(listOf(first, second, tail), carryEarlierPages(listOf(second, tail), 1, listOf(first, second)))
    }

    @Test fun `current snapshots retain intentional edits without readding the original page`() {
        val original = message("old", "before")
        val edited = listOf(original.copy(text = "after"), message("tail"))
        assertSame(edited, carryEarlierPages(edited, 1, listOf(original)))
    }

    @Test fun `current snapshots retain intentional deletion of a loaded message`() {
        val deleted = message("old")
        val remaining = listOf(message("tail"))
        assertSame(remaining, carryEarlierPages(remaining, 1, listOf(deleted)))
    }

    @Test fun `legacy duplicate ids are distinct occurrences and are not deduplicated`() {
        val older = message("same", "older occurrence")
        val newer = message("same", "newer occurrence")
        assertEquals(listOf(older, newer), carryEarlierPages(listOf(newer), 0, listOf(older)))
    }

    @Test fun `a stream-published page is not prepended twice when boundary ids repeat`() {
        val head = message("same", "original tail head")
        val older = listOf(message("same", "older occurrence"))
        val runMessages = prependEarlierPage(listOf(head), head, older)!!
        val streamPublished = carryEarlierPages(listOf(head, message("answer")), 0, older)
        assertEquals(runMessages + message("answer"), streamPublished)
        assertNull(prependEarlierPage(streamPublished, head, older))
    }

    @Test fun `an append does not invalidate the captured page head`() {
        val head = message("head")
        val older = listOf(message("older"))
        val afterAppend = listOf(head, message("answer"))
        assertEquals(older + afterAppend, prependEarlierPage(afterAppend, head, older))
    }
}
