package com.suixing.agent.llm

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPolicyTest {
    @Test fun freshMarkerExpiresAndClockRollbackFailsClosed() {
        assertFalse(fullDiagnosticAllowed(0, 1000))
        assertTrue(fullDiagnosticAllowed(1000, 1000 + 15 * 60_000L))
        assertFalse(fullDiagnosticAllowed(1000, 1001 + 15 * 60_000L))
        assertFalse(fullDiagnosticAllowed(2000, 1000))
    }
}

class DiagnosticFileExpiryTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun staleOnlyAfterTheSameWindowAsTheMarker() {
        val t = 1_000_000L
        assertEquals(false, staleDiagnosticFile(0, t))
        assertEquals(false, staleDiagnosticFile(t, t + DIAGNOSTIC_FILE_TTL_MS))
        assertEquals(true, staleDiagnosticFile(t, t + DIAGNOSTIC_FILE_TTL_MS + 1))
        // 时钟回拨：不当作过期，避免误删证据
        assertEquals(false, staleDiagnosticFile(t + 5_000, t))
    }

    @Test fun startupCleanupRemovesOnlyExpiredDiagnostics() {
        val dir = tmp.newFolder("files")
        val stale = java.io.File(dir, "last-http-error.json").apply { writeText("{}") }
        stale.setLastModified(1_000L)
        val fresh = java.io.File(dir, "http-dump-full").apply { writeText("") }
        val keep = java.io.File(dir, "settings.json").apply { writeText("{}") }

        val removed = expireDiagnosticFiles(dir, now = 1_000L + DIAGNOSTIC_FILE_TTL_MS + 1)

        assertEquals(listOf("last-http-error.json"), removed)
        assertEquals(false, stale.exists())
        assertEquals(true, fresh.exists())
        assertEquals("真数据不能被误删", true, keep.exists())
    }
}
