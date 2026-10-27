package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRepositorySnapshotTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `opening committed history does not wait for the repository writer mutex`() = runBlocking {
        val messages = listOf(
            StoredMessage(id = "u", role = Roles.USER, text = "question"),
            StoredMessage(id = "a", role = Roles.ASSISTANT, text = "answer"),
        )
        val store = ChatStore(File(folder.root, "chats"))
        store.append(store.newMeta(Conversation(id = "chat", title = "test", messages = messages)), messages)
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        val repository = ChatRepository(context)
        repository.load()
        // Emulate a slow writer deterministically, without a timing benchmark or large files.
        val lock = ChatRepository::class.java.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(repository) as Mutex
        }
        lock.lock()
        try {
            val window = withTimeout(5_000) { repository.readTailWindow("chat", 1) }
            assertEquals(messages, window!!.first.messages)
        } finally {
            lock.unlock()
        }
    }
}
