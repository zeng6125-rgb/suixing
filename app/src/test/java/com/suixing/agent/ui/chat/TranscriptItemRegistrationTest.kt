package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptItemRegistrationTest {
    @Test fun `nonblank conversation items wait for viewport preparation`() {
        assertFalse(transcriptItemsRegistered(prepared = false, conversationId = "chat"))
        assertTrue(transcriptItemsRegistered(prepared = true, conversationId = "chat"))
    }

    @Test fun `blank conversation can register its empty transcript`() {
        assertTrue(transcriptItemsRegistered(prepared = false, conversationId = ""))
    }
}
