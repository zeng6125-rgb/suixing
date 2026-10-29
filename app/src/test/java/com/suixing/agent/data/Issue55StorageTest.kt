package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Issue55StorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val chats get() = File(folder.root, "chats")
    private fun repository() = ChatRepository(mockk<Context>().also { context ->
        every { context.filesDir } returns folder.root
    })

    @Test fun `rewrite and migration preserve longest turn across multiple completions`() {
        val store = ChatStore(chats)
        val messages = listOf(
            StoredMessage(id = "u", role = Roles.USER),
            StoredMessage(id = "a", role = Roles.ASSISTANT, runId = "r", promptTokens = 100, completionTokens = 20),
            StoredMessage(id = "b", role = Roles.ASSISTANT, runId = "r", promptTokens = 150, completionTokens = 30),
        )
        val conversation = Conversation(id = "chat", title = "title", messages = messages)
        assertEquals(200L, store.metaOf(conversation, 0, 0).totals.longestTurnTokens)
        val first = store.append(store.newMeta(conversation), messages.take(2))
        val appended = store.appendNew(first, messages.takeLast(1))
        assertEquals(200L, appended.totals.longestTurnTokens)
        val rewritten = store.rewrite(appended, messages)
        assertEquals(200L, rewritten.totals.longestTurnTokens)
        assertEquals(200L, ChatStore(chats).readMeta("chat")!!.totals.longestTurnTokens)
    }

    @Test fun `offset lookup handles prefix and id split at reverse scan block boundaries`() {
        val store = ChatStore(chats)
        val target = StoredMessage(id = "target", role = Roles.USER)
        val head = StoredMessage(id = "head", role = Roles.ASSISTANT, text = "before")
        val headBytes = store.encode(listOf(head)).size.toLong()
        val targetBytes = store.encode(listOf(target)).size
        val suffix = StoredMessage(id = "suffix", role = Roles.ASSISTANT)
        val suffixOverhead = store.encode(listOf(suffix)).size
        for (split in listOf(0, 1, 7, 13)) {
            // The reverse scan's first boundary is at targetOffset + split.
            val suffixLength = (1 shl 20) + split - targetBytes - suffixOverhead
            val messages = listOf(head, target, suffix.copy(text = "x".repeat(suffixLength)))
            val meta = store.rewrite(store.newMeta(Conversation(id = "chat", title = "title")), messages)
            assertEquals("split=$split", headBytes, store.offsetOf(meta, "target"))
            assertNull(store.offsetOf(meta, "absent"))
        }
    }

    @Test fun `expanded history window preserves complete huge turns and paging boundary`() {
        val store = ChatStore(chats)
        val messages = (1..4).flatMap { turn -> listOf(
            StoredMessage(id = "u$turn", role = Roles.USER, text = "question"),
            StoredMessage(id = "a$turn", role = Roles.ASSISTANT, text = "x".repeat(400_000)),
        ) }
        val meta = store.append(store.newMeta(Conversation(id = "chat", title = "title")), messages)
        val recent = store.tail(meta, turns = 2)
        assertEquals(messages.takeLast(4), recent.messages)
        assertFalse(recent.atStart)
        val earlier = store.window(meta, recent.startOffset, turns = 2)
        assertEquals(messages.take(4), earlier.messages)
        assertTrue(earlier.atStart)
    }

    @Test fun `unpriced incremental tail retains its known generation byte watermark`() {
        val priced = StoredMessage(id = "a", role = Roles.ASSISTANT, promptTokens = 10)
        val initial = accumulateSlices(UsageSnapshot(), listOf(UsageSlice("chat", listOf(priced), 0, listOf(200L), 3)))
        val unpriced = StoredMessage(id = "tool", role = Roles.TOOL)
        val after = accumulateSlices(initial, listOf(UsageSlice("chat", listOf(unpriced), 1, listOf(300L), 3)))
        assertEquals(200L, after.chats.getValue("chat").scannedBytes)
        assertEquals(initial, accumulateSlices(after, listOf(UsageSlice("chat", emptyList(), 1, emptyList(), 3))))
    }

    @Test fun `live message payload is reused across ticks and recoverable after repository recreation`() = runBlocking {
        val repository = repository()
        val message = StoredMessage(id = "tool", role = Roles.TOOL, toolOutput = "x".repeat(600_000))
        val first = LiveDraft("chat", "run", 1, "first", messages = listOf(message))
        repository.saveLive(first)
        val payload = chats.listFiles { f -> f.name.endsWith(".messages") }!!.single()
        val bytes = payload.readBytes()
        repository.saveLive(first.copy(at = 2, text = "second"))
        assertEquals(listOf(payload.name), chats.listFiles { f -> f.name.endsWith(".messages") }!!.map { it.name })
        assertArrayEquals(bytes, payload.readBytes())
        assertTrue(File(chats, "chat.live.json").length() < 2_000)
        assertEquals(first.copy(at = 2, text = "second"), repository().readLive("chat"))
        repository.clearLive("chat")
        assertFalse(payload.exists())
    }

    @Test fun `legacy inline live payload remains recoverable`() = runBlocking {
        chats.mkdirs()
        val draft = LiveDraft("legacy", "run", 1, "answer", messages = listOf(StoredMessage(id = "m", role = Roles.TOOL)))
        // Written the way the repository writes on disk (encodeDefaults = true). Bare `Json` uses
        // encodeDefaults = false, which omits a defaulted property whenever its value still equals
        // the default expression — so a `timestamp = System.currentTimeMillis()` constructed and
        // encoded inside the same millisecond vanished from the file and the decode re-stamped it,
        // making this assertion fail only when the clock did not tick.
        val legacyFormat = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        File(chats, "legacy.live.json").writeText(legacyFormat.encodeToString(LiveDraft.serializer(), draft))
        assertEquals(draft, repository().readLive("legacy"))
    }

    @Test fun `failed live manifest replacement preserves the preceding durable payload`() = runBlocking {
        val repository = repository()
        val old = LiveDraft("chat", "run", 1, "old", messages = listOf(
            StoredMessage(id = "tool", role = Roles.TOOL, toolOutput = "old result")))
        repository.saveLive(old)
        // Fail after writing a new immutable payload, while opening the manifest temp file.
        File(chats, "chat.live.json.tmp").mkdir()
        val changed = old.copy(at = 2, text = "new", messages = listOf(
            old.messages.single().copy(toolOutput = "new result")))
        assertTrue(runCatching { repository.saveLive(changed) }.isFailure)
        assertEquals(old, repository().readLive("chat"))
    }
}
