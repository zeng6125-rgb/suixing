package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The draft a run leaves behind when the process dies mid-answer: it lives in the chats
 * directory as `<chatId>.live.json`, so it has to survive a read, disappear on clear, and
 * never be mistaken for a conversation.
 */
class ChatRepositoryLiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Where the repository itself writes, because that is where the draft has to land. */
    private val chatsDir: File get() = File(folder.root, "chats")

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    private fun liveFile(chatId: String) = File(chatsDir, "$chatId.live.json")

    @Test
    fun `a draft reads back field for field and clear takes it off disk`() = runBlocking {
        val chats = repository()
        val draft = LiveDraft(
            chatId = "c1",
            runId = "r1",
            at = 1_700_000_000_000L,
            text = "第一行\n第二行 —— 半截回答",
        )

        chats.saveLive(draft)
        assertEquals("a saved draft has to read back unchanged", draft, chats.readLive("c1"))

        chats.clearLive("c1")
        assertNull("a cleared draft must not come back", chats.readLive("c1"))
        assertFalse("a cleared draft must not stay on disk", liveFile("c1").exists())
    }

    @Test
    fun `a draft that no longer parses is dropped and deleted`() = runBlocking {
        val chats = repository()
        chatsDir.mkdirs()
        liveFile("c1").writeText("{ not json at all")

        assertNull("an unreadable draft reads as nothing", chats.readLive("c1"))
        assertFalse("an unreadable draft must not be kept", liveFile("c1").exists())
    }

    @Test
    fun `a draft is never listed as a conversation`() = runBlocking {
        val chats = repository()
        val kept = chats.create(title = "真会话")
        chats.saveLive(LiveDraft(chatId = "c1", runId = "r1", at = 1L, text = "半截回答"))
        // writeAtomically's temp file, left behind when a kill lands mid-save.
        File(chatsDir, "c1.live.json.tmp").writeText("orphan")

        chats.load()

        assertEquals(
            "only real conversations may be listed",
            listOf(kept.id),
            chats.conversations.value.map { it.id },
        )
    }
}
