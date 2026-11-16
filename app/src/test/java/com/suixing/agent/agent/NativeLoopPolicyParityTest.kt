package com.suixing.agent.agent

import com.suixing.agent.core.NativeCore
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeLoopPolicyParityTest {
    @Test fun nativeAndKotlinTerminalPlansAgree() {
        assumeTrue("Native loop policy library is unavailable", NativeCore.available)
        val cases = (NativeLoopPolicy.DONE..NativeLoopPolicy.FAILED).flatMap { reason ->
            val retries = if (reason == NativeLoopPolicy.FAILED) listOf(0, 1, 2) else listOf(0)
            retries.map { reason to it }
        }
        val native = cases.map { (reason, retries) -> NativeLoopPolicyJni.nativePlan(reason, retries) }
        mockkObject(NativeCore)
        try {
            every { NativeCore.available } returns false
            cases.forEachIndexed { index, (reason, retries) ->
                val fallback = NativeLoopPolicy.plan(reason, retries)
                assertArrayEquals("reason=$reason retries=$retries", intArrayOf(
                    0x3150584c, reason, retries,
                    fallback.prepare, fallback.message, fallback.event, fallback.state, fallback.throwAction,
                ), native[index])
            }
        } finally {
            unmockkObject(NativeCore)
        }
    }
}
