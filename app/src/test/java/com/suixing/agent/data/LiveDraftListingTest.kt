package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Both readers of `filesDir/chats` have to skip a draft by *name*, not by hoping it fails to
 * parse: `<id>.live.json` sits in the same folder, ends in `.json`, and is written by a run
 * that is still going. The draft below is a valid conversation on purpose — that is what
 * tells the name filter apart from "it would not have parsed anyway".
 */
class LiveDraftListingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")

    private fun context(): Context {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return context
    }

    @Test
    fun `a draft is not a conversation, for the chat list or for the usage roll-up`() = runBlocking {
        val context = context()
        val chats = ChatRepository(context)
        val real = Conversation(
            id = "real",
            title = "真会话",
            messages = listOf(
                StoredMessage(
                    id = "m1",
                    role = "assistant",
                    text = "答",
                    model = "some/model",
                    promptTokens = 10,
                    completionTokens = 5,
                ),
            ),
        )
        chats.save(real)

        val draft = Conversation(
            id = "draft",
            title = "半截回答",
            messages = listOf(
                StoredMessage(
                    id = "m2",
                    role = "assistant",
                    text = "还没写完",
                    model = "some/model",
                    promptTokens = 100,
                    completionTokens = 100,
                ),
            ),
        )
        chatsDir.mkdirs()
        val draftFile = File(chatsDir, "draft$LIVE_SUFFIX")
        draftFile.writeText(Json.encodeToString(Conversation.serializer(), draft))

        chats.load()
        assertEquals(
            "the chat list must hold the conversation and not the draft",
            listOf(real.id),
            chats.conversations.value.map { it.id },
        )
        assertTrue("and it must leave the draft file where it is", draftFile.isFile)

        val snapshot = UsageStore(context).refresh()
        assertEquals(
            "the usage roll-up must count the conversation and not the draft",
            setOf(real.id),
            snapshot.chats.keys,
        )
    }
}
