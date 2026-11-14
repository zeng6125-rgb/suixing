package com.suixing.agent.service

import android.content.Context
import android.widget.TextView
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.core.Core
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FloatConsoleConversationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `rendering a long history reads its bodies away from the UI caller`() = runBlocking {
        val readers = mutableListOf<Thread>()
        val body = StoredMessage(id = "answer", role = Roles.ASSISTANT, text = "Long answer\n".repeat(1000))
        val messages = object : AbstractList<StoredMessage>() {
            override val size = 1
            override fun get(index: Int): StoredMessage {
                readers += Thread.currentThread()
                return body
            }
        }
        val caller = Thread.currentThread()
        withConsole(messages) { service, _ ->
            service.renderConversation()
            assertTrue(readers.isNotEmpty())
            assertTrue("body projection must not share the UI caller", readers.none { it === caller })
            assertTrue(service.recentText.text.contains(body.text))
        }
    }

    @Test fun `renaming a conversation does not reproject an unchanged cached history`() = runBlocking {
        var reads = 0
        val body = StoredMessage(id = "answer", role = Roles.ASSISTANT, text = "Answer")
        val messages = object : AbstractList<StoredMessage>() {
            override val size = 1
            override fun get(index: Int): StoredMessage { reads++; return body }
        }
        withConsole(messages) { service, conversations ->
            service.renderConversation()
            reads = 0
            conversations.value = listOf(conversations.value.single().copy(title = "Renamed"))
            service.renderConversation()
            assertEquals("Renamed", service.conversationText.text)
            assertEquals("a metadata update must not walk the body", 0, reads)
            conversations.value = listOf(conversations.value.single().copy(
                messages = listOf(body.copy(text = "Edited")),
            ))
            service.renderConversation()
            assertTrue("same-sized edits must still refresh the body", service.recentText.text.contains("Edited"))
        }
    }

    private suspend fun withConsole(
        messages: List<StoredMessage>,
        block: suspend (FloatConsoleService, MutableStateFlow<List<Conversation>>) -> Unit,
    ) {
        val metadata = Conversation(id = "chat", title = "History", messageCount = messages.size)
        val conversations = MutableStateFlow(listOf(metadata))
        val repository = mockk<ChatRepository>()
        every { repository.conversations } returns conversations
        coEvery { repository.readTail("chat") } returns metadata.copy(messages = messages)
        val engine = mockk<AgentEngine>()
        every { engine.newestRun() } returns null
        val service = FloatConsoleService().apply {
            conversationText = textView()
            recentText = textView()
            liveAnswerText = textView()
            stateText = textView()
            contentScroll = mockk(relaxed = true)
            every { contentScroll.canScrollVertically(any()) } returns true
        }
        mockkObject(Core)
        try {
            every { Core.chats } returns repository
            every { Core.engine } returns engine
            block(service, conversations)
        } finally {
            service.scope.cancel()
            unmockkObject(Core)
        }
    }

    private fun textView(): TextView {
        var value: CharSequence = ""
        return mockk<TextView>(relaxed = true).also { view ->
            every { view.text } answers { value }
            every { view.text = any() } answers { value = firstArg() }
        }
    }

    @Test fun `stored tail remains readable beyond six messages and 160 characters`() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        val chats = ChatRepository(context)
        val answer = "Paragraph one\n\n" + "Complete answer ".repeat(20) + "\nFinal line"
        val messages = (1..7).map { StoredMessage(id = "$it", role = Roles.USER, text = "Question $it") } +
            StoredMessage(id = "answer", role = Roles.ASSISTANT, text = answer)
        chats.save(Conversation(id = "chat", title = "History", messages = messages))
        val metadata = chats.conversations.value.single()
        assertTrue(metadata.messages.isEmpty())
        val loaded = TailReadGate().read(metadata) { chats.readTail(it)?.messages.orEmpty() }
        val rendered = consoleConversationText(loaded)
        assertTrue("the loaded history must include its first question", rendered.contains("Question 1"))
        assertTrue("paragraphs and the end of a long answer must remain readable", rendered.contains(answer))
    }

    @Test fun `thinking and tool only messages retain concise progress information`() {
        val rendered = consoleConversationProgress(listOf(
            StoredMessage(id = "think", role = Roles.ASSISTANT, reasoning = "Inspecting\navailable files"),
            StoredMessage(
                id = "call", role = Roles.ASSISTANT,
                toolCalls = listOf(StoredToolCall(id = "tool", name = "read_file")),
            ),
            StoredMessage(id = "tool", role = Roles.TOOL, toolName = "read_file", toolOk = true),
        ))
        assertTrue(rendered.contains("Inspecting available files"))
        assertTrue(rendered.contains("read_file"))
        assertTrue(rendered.contains("完成"))
        assertEquals(1, rendered.lineSequence().filter { it.isNotBlank() }.count())
    }

    @Test fun `system and attachment information share the progress line while bodies keep paragraphs`() {
        val messages = listOf(
            StoredMessage(id = "system", role = Roles.SYSTEM, text = "Context\nwas compacted"),
            StoredMessage(id = "image", role = Roles.USER, imagesBase64 = listOf("image")),
            StoredMessage(id = "answer", role = Roles.ASSISTANT, text = "First paragraph\n\nSecond paragraph"),
        )
        val rendered = consoleConversationText(messages)
        assertTrue(rendered.contains("First paragraph\n\nSecond paragraph"))
        val progress = consoleConversationProgress(messages)
        assertTrue(progress.contains("Context was compacted") && progress.contains("1 张图片"))
        assertFalse(progress.contains('\n'))
        assertFalse(rendered.contains("Context"))
    }

    @Test fun `old and multiple image only messages retain an attachment indication`() {
        val rendered = consoleConversationProgress(listOf(
            StoredMessage(id = "old", role = Roles.USER, imageBase64 = "old-image"),
            StoredMessage(id = "multiple", role = Roles.USER, imagesBase64 = listOf("first", "second")),
        ))
        assertTrue(rendered.contains("1 张图片"))
        assertTrue(rendered.contains("2 张图片"))
    }

    @Test fun `live preview follows only the current paragraph and bounds a long paragraph`() {
        assertEquals("Current paragraph", consoleLivePreview("Earlier paragraph\n\nCurrent paragraph\n\n"))
        val long = "x".repeat(300)
        val preview = consoleLivePreview("Earlier paragraph\n\n$long")
        assertEquals("…" + "x".repeat(240), preview)
        assertFalse(preview.contains("Earlier"))
        assertEquals("", consoleLivePreview("\n  "))
    }

    @Test fun `an older answer never hides a fresh stream with the same prefix or complete text`() {
        val history = listOf(StoredMessage(id = "old", role = Roles.ASSISTANT, text = "好的，已完成"))
        for (text in listOf("好的", "好的，已完成")) {
            val current = StoredMessage(id = "current", role = Roles.ASSISTANT, text = text)
            assertFalse(consoleHistoryContainsAnswer("chat", history, "chat", current.id))
        }
    }

    @Test fun `only the same saved answer in the same chat replaces its live snapshot`() {
        val saved = StoredMessage(id = "current", role = Roles.ASSISTANT, text = "部分正文\n已中断")
        assertTrue(consoleHistoryContainsAnswer("chat", listOf(saved), "chat", "current"))
        assertFalse(consoleHistoryContainsAnswer("other-chat", listOf(saved), "chat", "current"))
    }
}
