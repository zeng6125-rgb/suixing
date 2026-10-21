package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The append-only store under the conversation repository: the append/rewrite split, the commit
 * watermark that survives a kill, and the tail window that lets a 30 MB chat open without being
 * parsed whole.
 */
class ChatStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store: ChatStore get() = ChatStore(folder.root)

    private val storeDir: File get() = File(folder.root, STORE_DIR)

    private fun logFile(gen: Int) = File(storeDir, "$CHAT.$gen$LOG_SUFFIX")

    /** One question and one answer — the smallest thing the UI counts as a turn. */
    private fun turn(index: Int): List<StoredMessage> = listOf(
        StoredMessage(id = "u$index", role = Roles.USER, text = "第 $index 问", timestamp = index.toLong()),
        StoredMessage(
            id = "a$index",
            role = Roles.ASSISTANT,
            text = "第 $index 答",
            runId = "r$index",
            timestamp = index.toLong(),
        ),
    )

    private fun turns(count: Int): List<StoredMessage> = (1..count).flatMap(::turn)

    /** A migrated chat holding the first [count] turns, as the repository would leave it. */
    private fun seed(count: Int): ChatStore.ConversationMeta =
        store.append(emptyMeta(), turns(count))

    private fun emptyMeta() = ChatStore.ConversationMeta(
        id = CHAT,
        title = "一个会话",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_500L,
    )

    // --- appending ---------------------------------------------------------------------------

    @Test
    fun `appended messages read back field for field`() {
        val meta = seed(2)

        val decoded = store.all(meta)

        assertEquals("the log has to give back exactly what was written", turns(2), decoded)
        assertEquals(4, meta.messageCount)
        assertEquals("a2", meta.lastMessageId)
        assertEquals(2, meta.turnCount)
    }

    @Test
    fun `a save appends only the new messages, not the transcript`() {
        val before = seed(2)
        val grown = turns(3)

        val after = store.append(before, grown)

        val expectedTail = store.encode(turn(3)).size.toLong()
        assertEquals(
            "one turn has to cost one turn's bytes",
            before.logBytes + expectedTail,
            after.logBytes,
        )
        assertEquals(
            "the file on disk is the whole transcript",
            store.encode(grown).toList(),
            logFile(0).readBytes().toList(),
        )
    }

    @Test
    fun `a list shorter than the log is refused and leaves the log alone`() {
        val meta = seed(5)
        val onDisk = logFile(0).readBytes()

        try {
            store.append(meta, turns(2))
            fail("a page of the conversation must never be written back as the whole of it")
        } catch (expected: ConversationLogShrunkException) {
            assertTrue(expected.message!!.contains("use rewrite()"))
        }

        assertEquals(
            "a refused save must not have touched a byte",
            onDisk.toList(),
            logFile(0).readBytes().toList(),
        )
    }

    @Test
    fun `a list whose watermark message changed is refused`() {
        val meta = seed(3)
        val edited = turns(4).toMutableList()
        // Index 5 is the message the log ends at; replacing it is the case append cannot absorb.
        edited[5] = edited[5].copy(id = "a3-edited")

        try {
            store.append(meta, edited)
            fail("history that no longer lines up has to be a rewrite, not an append")
        } catch (expected: ConversationLogMismatchException) {
            assertTrue(expected.message!!.contains("history was edited"))
        }
    }

    @Test
    fun `a save that only adds messages appends them`() {
        val meta = seed(2)

        val saved = store.save(meta, turns(3))

        assertEquals(ChatStore.SaveKind.APPEND, saved.kind)
        assertEquals(turns(3), store.all(saved.meta))
    }

    @Test
    fun `an edit in place is a rewrite, however much of the history it leaves alone`() {
        val meta = seed(3)
        // The user fixed a typo in an old question: same count, same last message, different text.
        val edited = turns(3).toMutableList()
        edited[2] = edited[2].copy(text = "改过的问题")

        val saved = store.save(meta, edited)

        assertEquals(
            "the anchor only proves the end of the log, so an in-place edit cannot be an append",
            ChatStore.SaveKind.REWRITE_INPLACE,
            saved.kind,
        )
        assertEquals("改过的问题", store.all(saved.meta)[2].text)
    }

    @Test
    fun `a save that leaves the transcript untouched writes nothing new to read`() {
        val meta = seed(2)

        val saved = store.save(meta, turns(2))

        assertEquals(ChatStore.SaveKind.REWRITE_INPLACE, saved.kind)
        assertEquals(turns(2), store.all(saved.meta))
        assertEquals(saved.meta.logBytes, logFile(saved.meta.gen).length())
    }

    @Test
    fun `a save that would drop history is refused even though a rewrite could absorb it`() {
        val meta = seed(4)
        val onDisk = logFile(0).readBytes()

        try {
            store.save(meta, turns(2))
            fail("a page of the conversation must never be written back as the whole of it")
        } catch (expected: ConversationLogShrunkException) {
            assertTrue(expected.message!!.contains("call rewrite()"))
        }

        assertEquals("a refused save must not have touched a byte", onDisk.toList(), logFile(0).readBytes().toList())
    }

    @Test
    fun `bytes past the commit boundary are not history and the next append drops them`() {
        val meta = seed(3)
        // What a kill between the append and the meta write leaves behind: a torn line.
        logFile(0).appendBytes("{\"id\":\"torn".toByteArray())

        assertEquals("a torn tail must not read as messages", turns(3), store.all(meta))

        val after = store.append(meta, turns(4))

        assertEquals("the half line is gone before the next one is added", turns(4), store.all(after))
        assertEquals(after.logBytes, logFile(0).length())
    }

    @Test
    fun `a log shorter than its meta is reported as damage`() {
        val meta = seed(4)
        logFile(0).writeBytes(logFile(0).readBytes().copyOf(20))

        try {
            store.all(meta)
            fail("a truncated log has to be reported, not read as a shorter chat")
        } catch (expected: ConversationLogDamagedException) {
            assertTrue(expected.message!!.contains("its meta claims"))
        }
    }

    // --- rewriting ---------------------------------------------------------------------------

    @Test
    fun `a rewrite starts a new generation and retires the old one`() {
        val meta = seed(5)

        val after = store.rewrite(meta, turns(2))

        assertEquals(1, after.gen)
        assertTrue("the new generation is on disk", logFile(1).isFile)
        assertFalse("the retired generation is gone", logFile(0).isFile)
        assertEquals(4, after.messageCount)
        assertEquals(2, after.turnCount)
        assertEquals("a2", after.lastMessageId)
        assertEquals("the shorter transcript reads back", turns(2), store.all(after))
    }

    @Test
    fun `an append after a rewrite lands in the newest generation`() {
        val rewritten = store.rewrite(seed(5), turns(2))

        val after = store.append(rewritten, turns(3))

        assertEquals(1, after.gen)
        assertEquals(turns(3), store.all(after))
        assertEquals(after.logBytes, logFile(1).length())
    }

    @Test
    fun `a rewrite of an empty transcript leaves nothing to read`() {
        val after = store.rewrite(seed(2), emptyList())

        assertEquals(0, after.messageCount)
        assertEquals(0, after.turnCount)
        assertEquals("", after.lastMessageId)
        assertEquals(0L, after.logBytes)
        assertEquals(emptyList<StoredMessage>(), store.all(after))
    }

    // --- reading a tail ----------------------------------------------------------------------

    @Test
    fun `the tail holds the newest turns and nothing above them`() {
        val meta = seed(200)

        val window = store.tail(meta, 3)

        assertEquals(turn(198) + turn(199) + turn(200), window.messages)
        assertFalse("there is a lot more above it", window.atStart)
        assertEquals("one turn stays one turn", 2, store.tail(meta, 1).messages.size)
    }

    @Test
    fun `walking earlier windows back to the start rebuilds the whole transcript`() {
        val meta = seed(120)
        val whole = store.all(meta)

        var window = store.tail(meta, 7)
        var collected = window.messages
        var steps = 0
        while (!window.atStart) {
            window = store.window(meta, window.startOffset, 7)
            collected = window.messages + collected
            assertTrue("walking back has to terminate", ++steps < 200)
        }

        assertEquals("no message may be lost or repeated on the way back", whole, collected)
    }

    @Test
    fun `a window grows past a message bigger than its first look`() {
        val meta = seed(1)
        val huge = StoredMessage(
            id = "a-big",
            role = Roles.ASSISTANT,
            text = "x".repeat(400_000),
        )
        val withHuge = store.append(meta, turns(1) + listOf(huge))

        val window = store.tail(withHuge, 1)

        assertEquals(
            "a turn that is larger than the first window still comes back whole",
            turns(1) + listOf(huge),
            window.messages,
        )
    }

    @Test
    fun `a conversation that opens with a system message keeps it`() {
        val opening = StoredMessage(id = "s0", role = Roles.SYSTEM, text = "你是助手")
        val meta = store.append(emptyMeta(), listOf(opening) + turns(1))

        val window = store.tail(meta, 1)

        assertEquals("the leading message is not a turn, but it is not dropped either", 0L, window.startOffset)
        assertTrue(window.atStart)
        assertEquals(listOf(opening) + turns(1), window.messages)
    }

    @Test
    fun `a steer does not open a turn`() {
        val meta = store.append(
            emptyMeta(),
            listOf(
                StoredMessage(id = "u1", role = Roles.USER, text = "问"),
                StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "答"),
                StoredMessage(id = "s1", role = Roles.USER, text = "插一句", steer = true),
                StoredMessage(id = "a2", role = Roles.ASSISTANT, text = "接着说"),
            ),
        )

        assertEquals(1, meta.turnCount)
        assertEquals(4, meta.messageCount)
        assertEquals(
            "a steer belongs to the turn it interrupted",
            4,
            store.tail(meta, 1).messages.size,
        )
    }

    // --- the line format ---------------------------------------------------------------------

    @Test
    fun `a newline inside a message stays inside its line`() {
        val meta = store.append(
            emptyMeta(),
            listOf(
                StoredMessage(id = "u1", role = Roles.USER, text = "第一行\n第二行"),
                StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "答\n\n完"),
            ),
        )

        val lines = logFile(meta.gen).readText().lines().filter { it.isNotEmpty() }

        assertEquals("one message is one physical line, whatever it contains", 2, lines.size)
        assertEquals("第一行\n第二行", store.all(meta).first().text)
    }

    @Test
    fun `the byte count is utf8, not characters`() {
        val meta = store.append(
            emptyMeta(),
            listOf(StoredMessage(id = "u1", role = Roles.USER, text = "中文的问题，还带标点。")),
        )

        assertEquals(
            "logBytes has to be the length of the file, or the commit boundary is wrong",
            logFile(meta.gen).length(),
            meta.logBytes,
        )
        assertTrue(
            "Chinese text is more bytes than characters, so the two must not be confused",
            meta.logBytes > "中文的问题，还带标点。".length,
        )
    }

    @Test
    fun `a message id can be found without parsing everything above it`() {
        val meta = seed(50)

        val offset = store.offsetOf(meta, "u30")

        assertNotNull(offset)
        assertEquals("the offset has to land on the message itself", "u30", store.from(meta, offset!!).first().id)
        assertEquals("the first line has no newline before it", 0L, store.offsetOf(meta, "u1"))
        assertNull("an id that is not in the log has no offset", store.offsetOf(meta, "u999"))
    }

    @Test
    fun `an id mentioned inside a message is not mistaken for the message`() {
        val meta = store.append(
            emptyMeta(),
            listOf(
                StoredMessage(id = "u1", role = Roles.USER, text = "复制了 {\"id\":\"a2\"} 这一段"),
                StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "好"),
            ),
        )

        assertNull(
            "a mention of another line inside a text field is not that line",
            store.offsetOf(meta, "inside-nothing"),
        )
        val offset = store.offsetOf(meta, "a1")
        assertNotNull(offset)
        assertEquals("a1", store.from(meta, offset!!).first().id)
    }

    // --- meta --------------------------------------------------------------------------------

    @Test
    fun `meta carries every field the list screen and the engine need`() {
        val conversation = Conversation(
            id = CHAT,
            title = "带检查点的会话",
            createdAt = 11L,
            updatedAt = 22L,
            pinned = true,
            archived = true,
            assistantId = "persona-1",
            activeRuns = mapOf("u1" to "r9"),
            providerId = "p1",
            model = "m1",
            reasoningEffort = "high",
            checkpoints = listOf(
                ContextCheckpoint(
                    id = "c1",
                    at = 33L,
                    fromMessageId = "u1",
                    toMessageId = "a3",
                    summary = "摘要",
                    messages = 6,
                    promptTokens = 1234,
                    anchorId = "a4",
                ),
            ),
        )

        val meta = store.metaOf(conversation, gen = 0, logBytes = 0)

        assertEquals(conversation.id, meta.id)
        assertEquals(conversation.title, meta.title)
        assertEquals(conversation.createdAt, meta.createdAt)
        assertEquals(conversation.updatedAt, meta.updatedAt)
        assertEquals(conversation.pinned, meta.pinned)
        assertEquals(conversation.archived, meta.archived)
        assertEquals(conversation.assistantId, meta.assistantId)
        assertEquals(conversation.activeRuns, meta.activeRuns)
        assertEquals(conversation.providerId, meta.providerId)
        assertEquals(conversation.model, meta.model)
        assertEquals(conversation.reasoningEffort, meta.reasoningEffort)
        assertEquals(conversation.checkpoints, meta.checkpoints)
        assertEquals("a meta has to read back out of a conversation unchanged", conversation.id, meta.toConversation().id)
        assertEquals(emptyList<StoredMessage>(), meta.toConversation().messages)
    }

    @Test
    fun `meta survives a write and a read`() {
        val meta = store.metaOf(seed(2).toConversation(turns(2)).copy(title = "改过的"), gen = 0, logBytes = 10)
        store.writeMeta(meta)

        assertEquals("every field has to come back", meta, store.readMeta(CHAT))
    }

    @Test
    fun `a chat with no meta reads as no meta`() {
        assertNull("nothing migrated yet is not a failure", store.readMeta("never-seen"))
        assertFalse(store.has("never-seen"))
    }

    @Test
    fun `a meta that does not parse throws instead of reading as absent`() {
        storeDir.mkdirs()
        File(storeDir, "$CHAT$META_SUFFIX").writeText("{ not json")

        try {
            store.readMeta(CHAT)
            fail("a broken meta is different from a missing one and must say so")
        } catch (expected: kotlinx.serialization.SerializationException) {
            assertNotNull("the parse failure carries a message", expected.message)
        }
    }

    @Test
    fun `discard takes the meta and every generation of the log`() {
        val meta = store.rewrite(seed(3), turns(1))
        assertTrue(store.has(CHAT))

        store.discard(CHAT)

        assertFalse("the meta goes", File(storeDir, "$CHAT$META_SUFFIX").exists())
        assertFalse("every generation goes", logFile(meta.gen).exists())
        assertFalse(store.has(CHAT))
    }

    private companion object {
        const val CHAT = "c1"
    }
}
