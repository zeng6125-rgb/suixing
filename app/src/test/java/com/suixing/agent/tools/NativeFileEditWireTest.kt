package com.suixing.agent.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFileEditWireTest {
    private val decoder = NativeFileEdits::class.java.declaredMethods.single { it.name == "decode" }
        .apply { isAccessible = true }

    private fun decode(bytes: ByteArray, before: String, replacement: String): NativeFileEditResult =
        decoder.invoke(NativeFileEdits, bytes, before.length, before.length, replacement.length,
            false, 4 * 1024 * 1024, EmptyCoroutineContext) as NativeFileEditResult

    private fun response(kind: Int, count: Int, changed: String, counts: Pair<Int, Int>?): ByteArray {
        val lines = if (count == 0) emptyList() else listOf(1)
        val output = ByteBuffer.allocate(32 + lines.size * 4 + changed.length * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        listOf(0x32455853, kind, count, lines.size, changed.length, changed.hashCode(),
            counts?.first ?: -1, counts?.second ?: -1).forEach { output.putInt(it) }
        lines.forEach { output.putInt(it) }
        changed.forEach { output.putChar(it) }
        return output.array()
    }

    @Test fun changedResponseDecodesCountsTextAndRevisionTogether() {
        for ((before, after, counts) in listOf(
            Triple("old", "new\r\nmore", 2 to 1),
            Triple("a\uD800\n", "b\uD800\n", 1 to 1),
            Triple("old", "", 0 to 1),
        )) {
            val result = decode(response(1, 1, after, counts), before, after)
            assertEquals(after, result.changed)
            assertEquals(counts, result.activityCounts)
            assertEquals(revisionOf(after), result.revision)
            assertEquals(listOf(1), result.lines)
            assertEquals(1, result.count)
        }
    }

    @Test fun unknownCountsStayDistinctFromZeroChanges() {
        val before = "old\n" + "same\n".repeat(4_000)
        val after = before.replaceFirst("old", "new")
        val unknown = decode(response(1, 1, after, null), before, after)
        assertNull(unknown.activityCounts)
        assertEquals(after, unknown.changed)
        val equal = decode(response(1, 1, before, 0 to 0), before, before)
        assertEquals(0 to 0, equal.activityCounts)
        assertEquals(before, equal.changed)
    }

    @Test fun refusedAndOversizedResponsesKeepUnknownCounts() {
        val missing = decode(response(0, 0, "", null), "old", "new")
        assertEquals(0, missing.count)
        assertNull(missing.changed)
        assertNull(missing.activityCounts)
        val oversized = decode(response(2, 1, "", null), "old", "new")
        assertTrue(oversized.tooLarge)
        assertNull(oversized.changed)
        assertNull(oversized.activityCounts)
    }
}
