package com.suixing.agent.data

import android.content.Context
import com.suixing.agent.ui.chat.toUiState
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationApprovalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun repository(): ChatRepository = ChatRepository(mockk<Context> {
        every { filesDir } returns folder.root
    })

    private fun messages(count: Int) = (1..count).map {
        StoredMessage(id = "message-$it", role = Roles.USER, text = "message $it", timestamp = it.toLong())
    }

    @Test
    fun `a conversation approval survives restart without changing another chat or its messages`() = runBlocking {
        val repo = repository()
        val first = Conversation("first", "First", messages = messages(3))
        repo.save(first)
        repo.save(Conversation("second", "Second", messages = messages(2)))
        val log = File(folder.root, "chats/store/first.0.jsonl")
        val bytes = log.readBytes()

        repo.updateMeta("first", approvalMode = ApprovalMode.DENY)

        assertArrayEquals("approval changes must not rewrite messages", bytes, log.readBytes())
        val restored = repository()
        restored.load()
        val chats = restored.conversations.value.associateBy { it.id }
        assertEquals(ApprovalMode.DENY, chats.getValue("first").toUiState("").approvalMode)
        assertNull(chats.getValue("second").toUiState("").approvalMode)
        assertEquals(first.messages, restored.readAll("first"))
    }

    @Test
    fun `choosing the current global value still pins the conversation when the default changes`() = runBlocking {
        val repo = repository()
        val chat = repo.create()
        val initial = AppSettings(approvalMode = ApprovalMode.ASK)
        repo.updateMeta(chat.id, approvalMode = initial.approvalMode)
        val changedGlobal = initial.copy(approvalMode = ApprovalMode.AUTO)
        val chosen = repo.conversations.value.first { it.id == chat.id }
        val newChat = repo.create()

        assertEquals(ApprovalMode.ASK, changedGlobal.withConversationApproval(chosen.approvalMode).approvalMode)
        assertEquals(ApprovalMode.AUTO, changedGlobal.withConversationApproval(newChat.approvalMode).approvalMode)
        assertEquals(ApprovalMode.AUTO, changedGlobal.approvalMode)
        assertNull(newChat.approvalMode)
    }

    @Test
    fun `all approval choices round trip and unrelated metadata edits retain them`() = runBlocking {
        val repo = repository()
        for (mode in ApprovalMode.entries) {
            val chat = Conversation(mode.name, mode.name, approvalMode = mode)
            repo.save(chat)
            repo.updateMeta(chat.id, title = "renamed")
        }
        val restored = repository()
        restored.load()
        for (mode in ApprovalMode.entries) {
            val chat = restored.conversations.value.first { it.id == mode.name }
            assertEquals(mode, chat.approvalMode)
            assertEquals("renamed", chat.title)
        }
    }

    @Test
    fun `an old running snapshot cannot overwrite a newer approval when messages are saved`() = runBlocking {
        val repo = repository()
        val old = Conversation("chat", "Chat", messages = messages(3), approvalMode = ApprovalMode.AUTO)
        repo.save(old)
        repo.updateMeta(old.id, approvalMode = ApprovalMode.DENY)
        repo.save(old.copy(messages = messages(4)))

        assertEquals(ApprovalMode.DENY, repo.conversations.value.single().approvalMode)
        assertEquals(4, repo.readAll(old.id).size)
    }

    @Test
    fun `a paged save retains the independently chosen approval and all earlier messages`() = runBlocking {
        val repo = repository()
        val old = Conversation("chat", "Chat", messages = messages(8), approvalMode = ApprovalMode.AUTO)
        repo.save(old)
        repo.updateMeta(old.id, approvalMode = ApprovalMode.ASK)
        repo.save(old.copy(messages = messages(9).takeLast(3)))

        assertEquals(ApprovalMode.ASK, repo.conversations.value.single().approvalMode)
        assertEquals(messages(9), repo.readAll(old.id))
    }

    @Test
    fun `saving before the conversation index is loaded still retains the persisted approval`() = runBlocking {
        val old = Conversation("chat", "Chat", messages = messages(2), approvalMode = ApprovalMode.AUTO)
        val repo = repository()
        repo.save(old)
        repo.updateMeta(old.id, approvalMode = ApprovalMode.DENY)

        val cold = repository()
        cold.save(old.copy(messages = messages(3)))
        val restored = repository()
        restored.load()
        assertEquals(ApprovalMode.DENY, restored.conversations.value.single().approvalMode)
        assertEquals(messages(3), restored.readAll(old.id))
    }

    @Test
    fun `legacy metadata follows the global default without a migration`() {
        val legacy = """{"id":"old","title":"Old","createdAt":1,"updatedAt":2}"""
        val meta = Json.decodeFromString(ChatStore.ConversationMeta.serializer(), legacy)
        val shown = meta.toConversation().toUiState("")
        assertNull(shown.approvalMode)
        for (mode in ApprovalMode.entries) {
            val global = AppSettings(approvalMode = mode)
            assertEquals(mode, global.withConversationApproval(shown.approvalMode).approvalMode)
        }
    }
}
