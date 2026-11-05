package com.suixing.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalPolicyTest {
    @Test fun manualSessionRequiresTheExactTaskGrant() {
        val policy = TerminalPolicy(null)
        val run = TerminalCaller("chat", "run-1")
        assertFalse(policy.allow(run))
        policy.grant(run)
        assertTrue(policy.allow(run))
        assertFalse(policy.allow(TerminalCaller("chat", "run-2")))
        assertFalse(policy.allow(TerminalCaller("other", "run-1")))
    }
    @Test fun takeoverRevokesTheGrantAndAdvancesThePendingWriteGeneration() {
        val policy = TerminalPolicy(null)
        val run = TerminalCaller("chat", "run")
        policy.grant(run)
        val queuedGeneration = policy.revision.value
        policy.takeOver()
        assertFalse(policy.allow(run))
        policy.grant(run)
        assertTrue(policy.allow(run))
        assertTrue(policy.revision.value > queuedGeneration)
    }
    @Test fun agentSessionIsOwnedByOneChatAndHumanCanRevokeIt() {
        val policy = TerminalPolicy("owner")
        assertTrue(policy.allow(TerminalCaller("owner", "one")))
        assertFalse(policy.allow(TerminalCaller("other", "one")))
        policy.takeOver()
        assertFalse(policy.allow(TerminalCaller("owner", "two")))
        policy.grantOrResume(TerminalCaller("owner", "two"))
        assertTrue(policy.allow(TerminalCaller("owner", "two")))
        assertFalse(policy.canGrant(TerminalCaller("other", "two")))
    }
}
