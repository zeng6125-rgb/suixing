package com.suixing.agent.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationReadsTest {
    @get:Rule val folder = TemporaryFolder()
    private val owners = mutableListOf<CoroutineScope>()
    private val key = stringSetPreferencesKey("completed_unread")

    private fun owner() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { owners += it }
    private fun preferences(scope: CoroutineScope) = RecordedPreferences(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(folder.root, "read.preferences_pb") },
        ),
    )
    private suspend fun unread(reads: ConversationReads, expected: Set<String>) = withTimeout(5_000) {
        reads.unread.first { it == expected }
    }
    private suspend fun persisted(store: RecordedPreferences, expected: Set<String>) = withTimeout(5_000) {
        store.written.first { it != null && it[key].orEmpty() == expected }
    }

    // Observe the actual durable update completion. The in-memory badge intentionally emits
    // before it; a second cold data collector during rename races Windows file sharing.
    private class RecordedPreferences(private val disk: DataStore<Preferences>) : DataStore<Preferences> by disk {
        val written = MutableStateFlow<Preferences?>(null)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            disk.updateData(transform).also { written.value = it }
    }

    @After fun closeScopes() = runBlocking {
        owners.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun `unread completion survives a store restart and opening that chat persists acknowledgement`() = runBlocking<Unit> {
        val firstOwner = owner()
        val firstData = preferences(firstOwner)
        val first = ConversationReads(firstData, firstOwner)
        first.completed("a")
        unread(first, setOf("a"))
        persisted(firstData, setOf("a"))
        firstOwner.coroutineContext[Job]!!.cancelAndJoin()

        val secondOwner = owner()
        val secondData = preferences(secondOwner)
        val second = ConversationReads(secondData, secondOwner)
        unread(second, setOf("a"))
        second.viewing("a")
        unread(second, emptySet())
        persisted(secondData, emptySet())
    }

    @Test fun `events arriving during startup load retain completion and read ordering`() = runBlocking<Unit> {
        val scope = owner()
        val disk = preferences(scope)
        val releaseLoad = CompletableDeferred<Unit>()
        val writes = Channel<Set<String>>(Channel.UNLIMITED)
        val delayed = object : DataStore<Preferences> {
            override val data = flow {
                releaseLoad.await()
                emitAll(disk.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                disk.updateData(transform).also { writes.trySend(it[key].orEmpty()) }
        }
        val reads = ConversationReads(delayed, scope)
        reads.viewing("a")
        reads.completed("a")
        reads.viewing("b")
        reads.completed("a")
        reads.viewing("a")
        releaseLoad.complete(Unit)
        assertEquals(setOf("a"), withTimeout(5_000) { writes.receive() })
        assertEquals(emptySet<String>(), withTimeout(5_000) { writes.receive() })
        assertEquals(emptySet<String>(), reads.unread.value)
    }

    @Test fun `deleting one unread chat leaves other completions persisted`() = runBlocking<Unit> {
        val scope = owner()
        val disk = preferences(scope)
        val reads = ConversationReads(disk, scope)
        reads.completed("a")
        reads.completed("b")
        unread(reads, setOf("a", "b"))
        reads.forget("a")
        unread(reads, setOf("b"))
        persisted(disk, setOf("b"))
    }
}
