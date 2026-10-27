package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R3 的**冷**读路径：`committedMetas` 未命中时的无锁快照。
 *
 * 与 [ChatRepositorySnapshotTest] 的区别是那里先 `load()`，读的是已索引的**热**快照；
 * 真实界面里正文缓存未命中并不等于元数据冷未命中（`load()` 会把所有 meta 放进
 * `committedMetas`），所以要证明冷分支必须在**新建且未 load** 的 repository 上做。
 *
 * 用法是反证：把仓库自己的互斥锁占住（等同另一会话正在整份写盘），再用超时读；
 * 冷分支若退化回 `lock.withLock`，这里会超时失败而不是悄悄变慢。
 */
class ChatRepositoryColdReadTest {
    @get:Rule val folder = TemporaryFolder()

    private val messages = listOf(
        StoredMessage(id = "u1", role = Roles.USER, text = "first question"),
        StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "first answer"),
        StoredMessage(id = "u2", role = Roles.USER, text = "second question"),
        StoredMessage(id = "a2", role = Roles.ASSISTANT, text = "second answer"),
    )

    /** 种一个会话并返回它的已提交元数据；不经过 repository，所以不会预热任何索引。 */
    private fun seed(store: ChatStore): ChatStore.ConversationMeta {
        val meta = store.newMeta(Conversation(id = "chat", title = "test", messages = messages))
        return store.append(meta, messages)
    }

    private suspend fun repositoryWithLockedWriter(): Pair<ChatRepository, Mutex> {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        // **不调用 load()**：committedMetas 必须保持空，读才走冷分支。
        val repository = ChatRepository(context)
        val lock = ChatRepository::class.java.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(repository) as Mutex
        }
        lock.lock()
        return repository to lock
    }

    @Test fun `cold tail read returns the whole tail while a writer holds the repository mutex`() = runBlocking {
        val store = ChatStore(File(folder.root, "chats"))
        seed(store)
        val (repository, lock) = repositoryWithLockedWriter()
        try {
            // 对照断言：readWire 只走 lock.withLock ⇒ 占锁期间必然拿不到结果。
            // 这条若不成立，下面那句「通过」就说明不了任何事（锁其实没挡住任何路径）。
            assertNull(withTimeoutOrNull(300) { repository.readWire("chat") })
            val window = withTimeout(5_000) { repository.readTailWindow("chat", 1) }
            assertEquals(messages.takeLast(2), window!!.first.messages)
        } finally {
            lock.unlock()
        }
    }

    @Test fun `cold earlier page returns the previous turn while a writer holds the repository mutex`() = runBlocking {
        val store = ChatStore(File(folder.root, "chats"))
        val meta = seed(store)
        val (repository, lock) = repositoryWithLockedWriter()
        try {
            // expectGen 命中才走冷分支；代次不符会故意回落到加锁路径（因此这里同时守住 gen 校验）。
            val window = withTimeout(5_000) {
                repository.readEarlier("chat", endOffset = meta.logBytes, turns = 1, expectGen = meta.gen)
            }
            assertEquals(messages.takeLast(2), window!!.messages)
        } finally {
            lock.unlock()
        }
    }
}
