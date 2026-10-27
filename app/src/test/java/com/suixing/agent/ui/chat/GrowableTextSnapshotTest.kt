package com.suixing.agent.ui.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowableTextSnapshotTest {
    @Test fun `unchanged UI and writer reads share a string without freezing later appends`() {
        val text = GrowableText("first")
        val first = text.toString()
        assertSame(first, text.toString())
        text.append(" next")
        val next = text.toString()
        assertNotSame(first, next)
        assertEquals("first", first)
        assertEquals("first next", next)
        assertSame(next, text.toString())
    }

    @Test fun `background snapshots cannot see a partially appended surrogate pair`() {
        val text = GrowableText()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writer = pool.submit { start.await(); repeat(2_000) { text.append("🟦") } }
            val reader = pool.submit {
                start.await()
                repeat(2_000) {
                    val snapshot = text.toString()
                    assertTrue(snapshot.length % 2 == 0)
                    assertTrue(snapshot.chunked(2).all { it == "🟦" })
                }
            }
            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
            assertEquals("🟦".repeat(2_000), text.toString())
        } finally {
            pool.shutdownNow()
        }
    }
}
