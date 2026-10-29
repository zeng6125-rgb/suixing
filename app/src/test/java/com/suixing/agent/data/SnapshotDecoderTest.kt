package com.suixing.agent.data

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class SnapshotDecoderTest {
    @Test fun `concurrent readers decode a saved snapshot only once`() {
        var calls = 0
        val decoder = SnapshotDecoder { _ -> calls++; Any() }
        val readers = Executors.newFixedThreadPool(8)
        try {
            val values = readers.invokeAll(List(32) { Callable { decoder.read("saved") } }).map { it.get() }
            assertEquals(1, calls)
            values.forEach { assertSame(values.first(), it) }
        } finally { readers.shutdownNow() }
    }

    @Test fun `changed and removed storage values invalidate the single snapshot`() {
        val inputs = mutableListOf<String?>()
        val decoder = SnapshotDecoder { raw -> inputs.add(raw); raw ?: "default" }
        assertEquals("default", decoder.read(null))
        assertEquals("default", decoder.read(null))
        assertEquals("edited", decoder.read("edited"))
        assertEquals("default", decoder.read(null))
        assertEquals(listOf(null, "edited", null), inputs)
    }

    @Test fun `failed decode never replaces the previous valid snapshot`() {
        var attempts = 0
        val value = Any()
        val decoder = SnapshotDecoder { raw ->
            if (raw == "broken") { attempts++; error("invalid") }
            value
        }
        assertSame(value, decoder.read("valid"))
        repeat(2) { assertTrue(runCatching { decoder.read("broken") }.isFailure) }
        assertSame(value, decoder.read("valid"))
        assertEquals(2, attempts)
    }
}
