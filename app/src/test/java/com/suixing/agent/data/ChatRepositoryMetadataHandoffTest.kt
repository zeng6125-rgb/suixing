package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRepositoryMetadataHandoffTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `pinning does not republish a stale metadata snapshot`() = runBlocking {
        verifyHandoff { id -> setPinned(id, true) }
    }

    @Test fun `renaming does not republish a stale metadata snapshot`() = runBlocking {
        verifyHandoff { id -> rename(id, "first title") }
    }

    @Test fun `archiving does not republish a stale metadata snapshot`() = runBlocking {
        verifyHandoff { id -> setArchived(id, true) }
    }

    private suspend fun verifyHandoff(change: suspend ChatRepository.(String) -> Unit) = coroutineScope {
        withTimeout(5_000L) {
            val context = mockk<Context>()
            every { context.filesDir } returns folder.root
            val repository = ChatRepository(context)
            val created = repository.create(title = "initial title")
            val caller = PausedDispatcher()
            val mutation = launch(caller, CoroutineStart.UNDISPATCHED) {
                repository.change(created.id)
            }
            try {
                // Metadata is committed on IO, while the caller has not resumed yet.
                caller.queued.await()
                val latest = repository.updateMeta(created.id, title = "latest title")!!
                caller.resume()
                mutation.join()

                assertEquals("the returning caller must retain the newer publication",
                    latest, repository.conversations.value.single())
                val reopened = ChatRepository(context)
                reopened.load()
                assertEquals(reopened.conversations.value, repository.conversations.value)
            } finally {
                caller.resume()
            }
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        val queued = CompletableDeferred<Unit>()
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
            queued.complete(Unit)
        }

        fun resume() {
            while (true) (tasks.poll() ?: return).run()
        }
    }
}
