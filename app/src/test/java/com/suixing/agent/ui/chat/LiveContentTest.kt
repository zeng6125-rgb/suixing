package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveContentTest {
    @Test fun `delta changes do not change the screen snapshot`() {
        // 思考正文以修订号参与判等（issue #57 §3），这里用修订号代替原来的正文字符串。
        val first = ChatUiState(conversationId = "a", liveRunId = "r", streaming = "a", reasoningRevision = 1)
        val next = first.copy(streaming = "abc", reasoningRevision = 2)
        assertEquals(first.screenSnapshot(), next.screenSnapshot())
    }

    @Test fun `metadata changes still invalidate the screen`() {
        val first = ChatUiState(conversationId = "a", liveRunId = "r")
        assertNotEquals(first.screenSnapshot(), first.copy(status = "calling tool").screenSnapshot())
        assertNotEquals(first.screenSnapshot(), first.copy(conversationId = "b").screenSnapshot())
    }

    @Test fun `live projection preserves the actual part instances and run ownership`() {
        val parts = emptyList<LivePart>().appendText("tail", 0)
        val state = ChatUiState(conversationId = "a", liveRunId = "r", liveParts = parts)
        assertSame(parts, state.liveContent().parts)
        assertEquals("a", state.liveContent().chatId)
        assertEquals("r", state.liveContent().runId)
    }
}
