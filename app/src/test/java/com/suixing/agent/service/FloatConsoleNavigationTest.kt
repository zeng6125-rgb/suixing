package com.suixing.agent.service

import android.app.PendingIntent
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatConsoleNavigationTest {
    @Test fun `returning to chat requests the conversation displayed by the panel`() {
        val service = FloatConsoleService().apply { renderedConversationId = "panel-chat" }
        val opening = slot<Intent>()
        val extras = mutableMapOf<String, String?>()
        val pending = mockk<PendingIntent>()
        ChatCompletionNotifications.openRequested.value = null
        mockkConstructor(Intent::class)
        mockkStatic(PendingIntent::class)
        try {
            every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers {
                extras[firstArg()] = secondArg()
                self as Intent
            }
            every { anyConstructed<Intent>().getStringExtra(any()) } answers { extras[firstArg<String>()] }
            every { anyConstructed<Intent>().removeExtra(any()) } answers {
                extras.remove(firstArg<String>())
            }
            every { PendingIntent.getActivity(service, 1, capture(opening), any()) } returns pending
            every { pending.send() } answers { ChatCompletionNotifications.consumeIntent(opening.captured) }

            service.openChat()

            assertEquals("panel-chat", ChatCompletionNotifications.openRequested.value)
        } finally {
            ChatCompletionNotifications.openRequested.value = null
            service.scope.cancel()
            unmockkStatic(PendingIntent::class)
            unmockkConstructor(Intent::class)
        }
    }
}
