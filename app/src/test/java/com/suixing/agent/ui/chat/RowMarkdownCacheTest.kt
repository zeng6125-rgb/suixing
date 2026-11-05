package com.suixing.agent.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.suixing.agent.ui.chat.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMarkdownCacheTest {
    private fun documents(text: String): RowMarkdownDocuments = mapOf(text to emptyList<MdBlock>())

    @Test fun preparingAnotherMessageInvalidatesOnlyItsOwnReader() {
        val cache = RowMarkdownCache()
        val a = cache.documentsFor("a")
        val b = cache.documentsFor("b")
        var aChanges = 0
        var bChanges = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("a", { _: String -> aChanges++ }) { a.value }
            observer.observeReads("b", { _: String -> bChanges++ }) { b.value }
            Snapshot.withMutableSnapshot { cache.publish("b", documents("new message")) }
            assertEquals(0, aChanges)
            assertEquals(1, bChanges)
            assertEquals(setOf("new message"), b.value.keys)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun sharedMapControlDemonstratesThePreviousCrossRowInvalidation() {
        val shared = mutableStateMapOf("a" to documents("a"), "b" to documents("b"))
        var changes = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("a", { _: String -> changes++ }) { shared["a"] }
            observer.observeReads("b", { _: String -> changes++ }) { shared["b"] }
            Snapshot.withMutableSnapshot { shared["b"] = documents("changed b") }
            assertEquals(2, changes)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun evictedDocumentsCanBePreparedAgainThroughTheSameMountedSlot() {
        val cache = RowMarkdownCache()
        val slot = cache.documentsFor("a")
        cache.publish("a", documents("first"))
        cache.retainDocuments(emptySet())
        assertTrue(slot.value.isEmpty())
        cache.publish("a", documents("prepared again"))
        assertSame(slot, cache.documentsFor("a"))
        assertEquals(setOf("prepared again"), slot.value.keys)
    }

    @Test fun removedRowsLoseTheirSlotButRetainedRowsKeepTheirDocuments() {
        val cache = RowMarkdownCache()
        val a = cache.documentsFor("a")
        val b = cache.documentsFor("b")
        cache.publish("a", documents("keep"))
        cache.publish("b", documents("remove"))
        cache.retainRows(setOf("a"))
        assertSame(a, cache.documentsFor("a"))
        assertNotSame(b, cache.documentsFor("b"))
        assertEquals(setOf("keep"), a.value.keys)
        assertTrue(cache.documentsFor("b").value.isEmpty())
    }
}
