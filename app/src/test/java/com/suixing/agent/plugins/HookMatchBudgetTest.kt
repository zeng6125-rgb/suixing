package com.suixing.agent.plugins

import org.junit.Assert.*
import org.junit.Test

class HookMatchBudgetTest {
    @Test fun matchingUsesFullArgumentsIncludingTheTail() {
        val text = "a".repeat(100_000) + "secret"
        assertTrue(Regex("secret$").containsMatchIn(HookMatchBudget().wrap(text)))
    }
    @Test fun catastrophicBacktrackingCannotExhaustUnlimitedCpu() {
        try {
            Regex("(a+)+b").containsMatchIn(HookMatchBudget(10_000).wrap("a".repeat(1000)))
            fail("Expected the computation budget to interrupt backtracking")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("预算"))
        }
    }
}
