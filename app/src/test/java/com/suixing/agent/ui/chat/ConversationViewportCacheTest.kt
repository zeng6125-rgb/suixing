package com.suixing.agent.ui.chat

import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewportCacheTest {
    private val geometry = Triple(400, 1f, 14f)
    private fun state(id: String) = ChatUiState(
        conversationId = id, loading = false,
        messages = listOf(StoredMessage("$id-message", "assistant", "$id body")),
    )

    private fun ConversationViewport.displayRows(state: ChatUiState, from: Int) =
        projectRows(state, from).also { transcriptRows = it }

    @Test fun returningToAnUnchangedConversationRetainsItsReadyViewportAndRowDocuments() {
        val cache = ConversationViewportCache()
        val a = state("a")
        val opened = cache.get(a, null, 12, geometry, true)
        opened.prepared = true
        opened.presented = true
        opened.rowDocuments.publish("a-message", mapOf("a body" to emptyList()))
        val documents = opened.rowDocuments.documentsFor("a-message")
        val rows = opened.displayRows(a, 0)

        cache.get(state("b"), null, 12, geometry, true)
        val restored = cache.get(a.copy(title = "Renamed"), null, 12, geometry, true)

        assertSame(opened, restored)
        assertTrue(restored.prepared && restored.presented)
        assertSame(documents, restored.rowDocuments.documentsFor("a-message"))
        assertSame(rows, restored.projectRows(a.copy(messagesRevision = 10), 0))
        assertTrue(restored.rowDocuments.peek("a-message")!!.containsKey("a body"))
    }

    @Test fun aBackgroundTranscriptUpdateRequiresRestorationInsteadOfShowingTheOldReadyViewport() {
        val cache = ConversationViewportCache()
        val a = state("a")
        val opened = cache.get(a, null, 12, geometry, true)
        opened.prepared = true
        opened.presented = true
        cache.get(state("b"), null, 12, geometry, true)

        val updated = cache.get(a.copy(messages = a.messages +
            StoredMessage("new-message", "user", "New question")), null, 12, geometry, true)

        assertNotSame(opened, updated)
        assertFalse(updated.prepared || updated.presented)
    }

    @Test fun activeStreamingUpdatesKeepTheirMountedViewport() {
        val cache = ConversationViewportCache()
        val a = state("a")
        val opened = cache.get(a, null, 12, geometry, true)
        val updated = cache.get(a.copy(runningHere = true, liveRunId = "run"), null, 12, geometry, true)
        assertSame(opened, updated)
    }

    @Test fun returningToAStreamingConversationRestoresItsCurrentLivePosition() {
        val cache = ConversationViewportCache()
        val a = state("a").copy(runningHere = true, liveRunId = "run")
        val opened = cache.get(a, null, 12, geometry, true)
        opened.prepared = true
        opened.presented = true
        cache.get(state("b"), null, 12, geometry, true)

        val restored = cache.get(a, null, 12, geometry, true)
        assertNotSame(opened, restored)
        assertFalse(restored.prepared || restored.presented)
    }

    @Test fun returningAfterAWidthChangeRequiresANewLayout() {
        val cache = ConversationViewportCache()
        val a = state("a")
        val opened = cache.get(a, null, 12, geometry, true)
        cache.get(state("b"), null, 12, geometry, true)
        assertNotSame(opened, cache.get(a, null, 12, Triple(800, 1f, 14f), true))
    }

    @Test fun openingAThirdConversationReleasesTheOldestViewport() {
        val cache = ConversationViewportCache()
        val a = state("a")
        val opened = cache.get(a, null, 12, geometry, true)
        cache.get(state("b"), null, 12, geometry, true)
        cache.get(state("c"), null, 12, geometry, true)
        assertNotSame(opened, cache.get(a, null, 12, geometry, true))
    }

    @Test fun changingTheVisibleWindowOrTranscriptRebuildsRowsAndKeys() {
        val a = ChatUiState(
            conversationId = "a", loading = false,
            messages = listOf(
                StoredMessage("u", "user", "Question"),
                StoredMessage("a1", "assistant", "First", runId = "r1"),
                StoredMessage("a2", "assistant", "Second", runId = "r2"),
                StoredMessage("u2", "user", "Next"),
                StoredMessage("a3", "assistant", "Third", runId = "r3"),
            ),
            activeRuns = mapOf("u" to "r1"),
        )
        val viewport = ConversationViewportCache().get(a, null, 12, geometry, true)
        var previous = viewport.displayRows(a, 0)
        assertEquals(listOf("u", "a1", "u2", "a3"), previous.rows.map { it.message.id })
        val edited = a.copy(messages = a.messages.map { if (it.id == "a3") it.copy(text = "Edited") else it })
        val branch = edited.copy(activeRuns = mapOf("u" to "r2"))
        val running = branch.copy(runningHere = true, liveRunId = "r3")
        for ((state, from) in listOf(edited to 0, branch to 0, running to 0, running to 3, branch to 3)) {
            val projected = viewport.displayRows(state, from)
            val expected = groupRows(state.messages.subList(from, state.messages.size),
                state.activeRuns, state.runningHere, state.liveRunId.ifEmpty { null })
            val keys = presentationKeys(state.conversationId, expected)
            assertNotSame(previous, projected)
            assertEquals(expected, projected.rows)
            assertEquals(keys, projected.keys)
            assertEquals(keys.withIndex().associate { it.value to it.index }, projected.keyIndices)
            previous = projected
        }
    }

    @Test fun switchingBetweenPagedConversationsRetainsGroupedRows() {
        for (turns in listOf(12, 400)) {
            fun chat(id: String) = ChatUiState(
                conversationId = id,
                loading = false,
                messages = buildList {
                    repeat(turns) { turn ->
                        val run = "$id-$turn"
                        add(StoredMessage("$run-question", "user", "问题 $turn"))
                        add(StoredMessage("$run-first", "assistant", "先看记录", runId = run))
                        add(StoredMessage("$run-tool", "tool", toolName = "inspect", runId = run))
                        add(StoredMessage("$run-tail", "assistant", "结果", runId = run))
                    }
                },
            )
            val chats = listOf(chat("a"), chat("b"))
            val cache = ConversationViewportCache()
            val expectedSize = chats.associate { chat ->
                val initial = cache.get(chat, null, turns, geometry, true).displayRows(chat, 0)
                chat.conversationId to initial.rows.size
            }
            val cold = ArrayList<Long>()
            val warm = ArrayList<Long>()
            repeat(10) { round ->
                for (reuse in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    var total = 0
                    val start = System.nanoTime()
                    repeat(96) { index ->
                        val chat = chats[index % chats.size]
                        val viewport = cache.get(chat, null, turns, geometry, true)
                        val rows = if (reuse) viewport.displayRows(chat, 0) else ViewportTranscriptRows(chat, 0)
                        total += rows.keyIndices.size
                    }
                    val elapsed = System.nanoTime() - start
                    assertEquals(48 * expectedSize.values.sum(), total)
                    if (round >= 4) (if (reuse) warm else cold).add(elapsed)
                }
            }
            fun List<Long>.medianUs() = sorted()[size / 2] / 96_000.0
            println("native=${NativeCore.available} turns=$turns messages=${turns * 4} " +
                "regroup_and_key_us=${cold.medianUs()} reuse_us=${warm.medianUs()}")
        }
    }
}
