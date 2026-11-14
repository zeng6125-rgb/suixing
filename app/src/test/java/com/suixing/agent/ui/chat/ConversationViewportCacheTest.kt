package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage
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

    @Test fun returningToAnUnchangedConversationRetainsItsReadyViewportAndRowDocuments() {
        val cache = ConversationViewportCache()
        val a = state("a")
        val opened = cache.get(a, null, 12, geometry, true)
        opened.prepared = true
        opened.presented = true
        opened.rowDocuments.publish("a-message", mapOf("a body" to emptyList()))
        val documents = opened.rowDocuments.documentsFor("a-message")

        cache.get(state("b"), null, 12, geometry, true)
        val restored = cache.get(a.copy(title = "Renamed"), null, 12, geometry, true)

        assertSame(opened, restored)
        assertTrue(restored.prepared && restored.presented)
        assertSame(documents, restored.rowDocuments.documentsFor("a-message"))
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
}
