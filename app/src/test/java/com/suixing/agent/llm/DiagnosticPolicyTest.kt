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
