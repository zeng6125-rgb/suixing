package com.suixing.agent.agent

import com.suixing.agent.data.ApprovalMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SubagentApprovalTest {
    @Test fun `child ASK ignores remembered allowance and cannot change global remembered decisions`() = runBlocking {
        val gate = ApprovalGate()
        val parent = async(start = CoroutineStart.UNDISPATCHED) { gate.request("parent", "read_file", "{}", ApprovalMode.ASK) }
        gate.resolve("parent", true, always = true)
        assertTrue(parent.await())
        assertEquals(setOf("read_file"), gate.alwaysAllowedTools.value)

        val child = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request("child", "read_file", "{}", ApprovalMode.ASK, rememberDecisions = false)
        }
        val request = gate.pending.value.getValue("child")
        assertFalse(request.rememberDecisions)
        assertFalse(child.isCompleted)
        gate.resolve("child", false, always = true, expectedRequestId = request.id)
        assertFalse(withTimeout(2_000) { child.await() })
        assertEquals(setOf("read_file"), gate.alwaysAllowedTools.value)
        assertTrue(gate.alwaysDeniedTools.value.isEmpty())
    }

    @Test fun `an old dialog decision cannot answer the next request of the same run`() = runBlocking {
        val gate = ApprovalGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.request("child", "read_file", "first", ApprovalMode.ASK) }
        val oldId = gate.pending.value.getValue("child").id
        gate.cancelPending("child")
        assertFalse(first.await())
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.request("child", "read_file", "second", ApprovalMode.ASK) }
        val currentId = gate.pending.value.getValue("child").id
        assertNotEquals(oldId, currentId)
        gate.resolve("child", true, expectedRequestId = oldId)
        assertFalse(second.isCompleted)
        assertEquals(currentId, gate.pending.value.getValue("child").id)
        gate.resolve("child", false, expectedRequestId = currentId)
        assertFalse(withTimeout(2_000) { second.await() })
    }
}
