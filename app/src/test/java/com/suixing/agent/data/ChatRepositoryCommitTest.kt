package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRepositoryCommitTest {
    @get:Rule val folder = TemporaryFolder()

    private val initial = listOf(StoredMessage("u1", Roles.USER, "question", timestamp = 1L))
    private val tail = listOf(
        StoredMessage("a1", Roles.ASSISTANT, "tool call", runId = "r1", timestamp = 2L),
        StoredMessage("t1", Roles.TOOL, toolOutput = "result", runId = "r1", timestamp = 3L),
        StoredMessage("a2", Roles.ASSISTANT, "finished", runId = "r1", timestamp = 4L),
    )

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    private fun storeSpy(repository: ChatRepository): ChatStore {
        val field = ChatRepository::class.java.getDeclaredField("store").apply { isAccessible = true }
        return spyk(field.get(repository) as ChatStore).also { field.set(repository, it) }
    }

    private suspend fun seed(): ChatRepository {
        val repository = repository()
        repository.save(Conversation(id = "chat", title = "old", messages = initial))
        return repository
    }

    @Test fun `a multi-message tail commits its final metadata once and survives reopening`() = runBlocking {
        val repository = seed()
        val store = storeSpy(repository)
        assertTrue(repository.save(Conversation(
            id = "chat", title = "new", messages = initial + tail,
            providerId = "provider", model = "model", reasoningEffort = "high",
        )) is ChatRepository.SaveResult.Written)
        // writeMeta includes file sync and atomic rename: one durable publication per tail,
        // rather than first publishing stale fields and then syncing the same watermark again.
        verify(exactly = 1) { store.writeMeta(any()) }
        val fresh = repository().apply { load() }
        assertEquals(initial + tail, fresh.readAll("chat"))
        val meta = fresh.conversations.value.single()
        assertEquals("new", meta.title)
        assertEquals("provider", meta.providerId)
        assertEquals("model", meta.model)
        assertEquals("high", meta.reasoningEffort)
        assertEquals(4, meta.messageCount)
    }

    @Test fun `metadata-only tail save still commits new fields once without touching log bytes`() = runBlocking {
        val repository = seed()
        val log = File(folder.root, "chats/store/chat.0.jsonl")
        val bytes = log.readBytes()
        val store = storeSpy(repository)
        assertTrue(repository.save(Conversation(id = "chat", title = "renamed", messages = initial))
            is ChatRepository.SaveResult.Written)
        verify(exactly = 1) { store.writeMeta(any()) }
        assertArrayEquals(bytes, log.readBytes())
        assertEquals("renamed", repository().apply { load() }.conversations.value.single().title)
    }

    @Test fun `failed metadata publication leaves old commit readable and retry appends batch once`() = runBlocking {
        val repository = seed()
        val store = storeSpy(repository)
        val before = store.readMeta("chat")!!
        every { store.writeMeta(any()) } throws IOException("simulated commit failure")
        assertTrue(runCatching {
            repository.save(Conversation(id = "chat", title = "new", messages = initial + tail))
        }.isFailure)
        // Bytes may have reached disk but meta never advanced, so reopening sees the old
        // complete commit. Retrying must truncate only the uncommitted suffix, never history.
        assertEquals(initial, repository().readAll("chat"))
        assertEquals(before.logBytes, store.readMeta("chat")!!.logBytes)
        every { store.writeMeta(any()) } answers { callOriginal() }
        assertTrue(repository.save(Conversation(id = "chat", title = "new", messages = initial + tail))
            is ChatRepository.SaveResult.Written)
        assertEquals(initial + tail, repository().readAll("chat"))
        assertEquals("new", repository().apply { load() }.conversations.value.single().title)
    }
}
