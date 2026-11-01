package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionStartTest {

    @Test fun `starts when idle`() {
        assertTrue(canStartCompression(hasRunningJob = false, alreadyCompressing = false))
    }

    @Test fun `a running turn defers to the engine`() {
        // 有 run 时由引擎在轮次边界压，UI 不并行再压一次
        assertFalse(canStartCompression(hasRunningJob = true, alreadyCompressing = false))
    }

    @Test fun `double tap never starts two compressions`() {
        assertFalse(canStartCompression(hasRunningJob = false, alreadyCompressing = true))
        assertFalse(canStartCompression(hasRunningJob = true, alreadyCompressing = true))
    }
}
