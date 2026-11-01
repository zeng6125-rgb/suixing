package com.suixing.agent.llm

import io.mockk.*
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CancellableCallDeliveryTest {
    @Test fun cancelledCallClosesLateResponseAndIgnoresLateFailure() = runBlocking {
        val callback = slot<Callback>()
        val call = mockk<okhttp3.Call>(relaxed = true)
        every { call.enqueue(capture(callback)) } just Runs
        var reads = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.readCancellable { reads++; "unused" } }
        job.cancelAndJoin()
        val response = mockk<Response>(relaxed = true)
        callback.captured.onResponse(call, response)
        callback.captured.onFailure(call, IOException("late"))
        assertEquals(0, reads)
        verify(exactly = 1) { response.close() }
        verify(exactly = 1) { call.cancel() }
    }

    @Test fun bodyFailureClosesResponseAndDeliversOriginalExceptionOnce() = runBlocking {
        val callback = slot<Callback>()
        val call = mockk<okhttp3.Call>(relaxed = true)
        every { call.enqueue(capture(callback)) } just Runs
        val expected = IOException("read failed")
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { call.readCancellable<String> { throw expected } }
        }
        val response = mockk<Response>(relaxed = true)
        callback.captured.onResponse(call, response)
        val actual = result.await().exceptionOrNull()
        assertTrue(actual is IOException)
        assertEquals(expected.message, actual?.message)
        assertTrue(actual === expected || actual?.cause === expected)
        verify(exactly = 1) { response.close() }
    }
}
