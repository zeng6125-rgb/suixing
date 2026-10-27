package com.suixing.agent.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectedStateFlowTest {
    @Test fun `value stays current with no collector or scope worker`() {
        val source = MutableStateFlow(1)
        val projected = source.projectState { it * 2 }
        assertEquals(0, source.subscriptionCount.value)
        source.value = 7
        assertEquals(14, projected.value)
        assertEquals(listOf(14), projected.replayCache)
        assertEquals(0, source.subscriptionCount.value)
    }

    @Test fun `equal projections coalesce and cancellation releases the source`() = runBlocking {
        val source = MutableStateFlow(1)
        val projected = source.projectState { it / 10 }
        val received = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) { projected.collect { received += it } }
        assertEquals(1, source.subscriptionCount.value)
        source.value = 2
        source.value = 11
        assertEquals(listOf(0, 1), received)
        job.cancelAndJoin()
        assertEquals(0, source.subscriptionCount.value)
    }

    @Test fun `hidden UI updates do not run transformations`() = runBlocking {
        val source = MutableStateFlow(1)
        var transformations = 0
        val projected = source.projectState { transformations++; it }
        repeat(20) { source.value++ }
        assertEquals(0, transformations)
        val job = launch(Dispatchers.Unconfined) { projected.collect {} }
        assertEquals(1, transformations)
        job.cancelAndJoin()
        repeat(20) { source.value++ }
        assertEquals(1, transformations)
        assertEquals(source.value, projected.value)
    }
}
