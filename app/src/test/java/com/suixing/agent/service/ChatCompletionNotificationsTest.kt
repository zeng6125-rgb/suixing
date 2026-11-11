package com.suixing.agent.service

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatCompletionNotificationsTest {
    @Before fun reset() { ChatCompletionNotifications.openRequested.value = null }
    @After fun clear() { ChatCompletionNotifications.openRequested.value = null }

    private fun openIntent(chatId: String): Intent {
        val extras = mutableMapOf(ChatCompletionNotifications.OPEN_CONVERSATION to chatId)
        return mockk {
            every { getStringExtra(any()) } answers { extras[firstArg<String>()] }
            every { removeExtra(any()) } answers { extras.remove(firstArg<String>()); Unit }
        }
    }

    @Test fun `a cold launch retains the requested conversation until the UI can consume it`() = runBlocking {
        val intent = openIntent("chat-a")
        ChatCompletionNotifications.consumeIntent(intent)
        assertEquals("chat-a", ChatCompletionNotifications.openRequested.first())
        ChatCompletionNotifications.openRequested.value = null
        ChatCompletionNotifications.consumeIntent(intent)
        assertNull(ChatCompletionNotifications.openRequested.value)
        verify(exactly = 1) { intent.removeExtra(ChatCompletionNotifications.OPEN_CONVERSATION) }
    }

    @Test fun `new intents in a running process retain the latest conversation identity`() {
        ChatCompletionNotifications.consumeIntent(openIntent("chat-a"))
        ChatCompletionNotifications.consumeIntent(openIntent("chat-b"))
        assertEquals("chat-b", ChatCompletionNotifications.openRequested.value)
    }
}
