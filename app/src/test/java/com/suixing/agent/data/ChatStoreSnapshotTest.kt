package com.suixing.agent.data

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreSnapshotTest {
    @get:Rule val folder = TemporaryFolder()
    private val store by lazy { ChatStore(folder.root) }
    private fun messages(turns: Int) = (1..turns).flatMap { i ->
        listOf(
            StoredMessage(id = "u$i", role = Roles.USER, text = "question$i", timestamp = i.toLong()),
            StoredMessage(id = "a$i", role = Roles.ASSISTANT, text = "answer$i", timestamp = i.toLong()),
        )
    }
    private fun seed(turns: Int) = store.append(ChatStore.ConversationMeta(id = "chat", title = "test", createdAt = 1, updatedAt = 1), messages(turns))
    private fun log(meta: ChatStore.ConversationMeta) = File(folder.root, "$STORE_DIR/chat.${meta.gen}$LOG_SUFFIX")

    @Test fun `an open snapshot stays at its commit boundary after an append`() {
        val before = seed(3)
        store.committedRead("chat")!!.use { old ->
            store.append(before, messages(4))
            assertEquals(messages(3).takeLast(2), old.tail(1).messages)
        }
        store.committedRead("chat")!!.use { fresh ->
            assertEquals(messages(4).takeLast(2), fresh.tail(1).messages)
        }
    }

    @Test fun `rewriting a generation cannot mix old metadata with new log contents`() {
        val before = seed(3)
        store.openRead(before).use { old ->
            val changed = messages(2).map { it.copy(text = "edited " + it.text) }
            store.rewrite(before, changed)
            assertEquals(messages(3), old.tail(10).messages)
            store.committedRead("chat")!!.use { fresh ->
                assertEquals(changed, fresh.tail(10).messages)
            }
        }
    }

    @Test fun `uncommitted or torn bytes beyond the watermark are invisible`() {
        val before = seed(2)
        log(before).appendText("{torn write without newline")
        store.committedRead("chat")!!.use { snapshot ->
            assertEquals(messages(2), snapshot.tail(10).messages)
        }
    }

    @Test(expected = ConversationLogDamagedException::class)
    fun `missing committed bytes fail instead of returning a shortened history`() {
        val before = seed(2)
        RandomAccessFile(log(before), "rw").use { it.setLength(before.logBytes - 5) }
        store.committedRead("chat")!!.use { it.tail(10) }
    }

    @Test fun `only indexed metadata is used and discarded chats leave no snapshot`() {
        seed(2)
        val reopened = ChatStore(folder.root)
        assertNull(reopened.committedRead("chat"))
        reopened.readMeta("chat")
        reopened.committedRead("chat")!!.use { assertEquals(messages(2), it.tail(10).messages) }
        reopened.discard("chat")
        assertNull(reopened.committedRead("chat"))
    }

    @Test fun `metadata changed by another store invalidates the indexed snapshot`() {
        seed(2)
        val other = ChatStore(folder.root)
        val next = other.readMeta("chat")!!.copy(title = "a different title from an external restore")
        other.writeMeta(next)
        assertNull(store.committedRead("chat"))
        assertEquals(next, store.readMeta("chat"))
        store.committedRead("chat")!!.use { assertEquals(next, it.meta) }
    }
}
