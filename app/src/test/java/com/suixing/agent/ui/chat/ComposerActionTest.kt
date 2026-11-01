package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun allEightInputStatesPreserveSendingAndStopContracts() {
        for (running in listOf(false, true)) for (text in listOf(false, true)) for (attachment in listOf(false, true)) {
            val expected = if (!text && !attachment) {
                if (running) PrimaryChatAction.STOP else PrimaryChatAction.DISABLED
            } else if (running) PrimaryChatAction.STEER else PrimaryChatAction.SEND
            assertEquals("running=$running text=$text attachment=$attachment", expected,
                primaryChatAction(running, text, attachment))
        }
    }
}
