package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一次失败只提示一次：回执（落盘）与实时红行（横幅）不能同时出现。
 * 回执由两条路径写，尾串可以不同，所以去重必须容忍前缀之外的小差异。
 */
class FailureNoticeTest {

    private val event = "连接中断，这一轮的回答可能不完整。"

    @Test
    fun `receipt written from the event matches it`() {
        assertTrue(isSameFailure(FAILURE_RECEIPT_PREFIX + event, event))
    }

    @Test
    fun `receipt written from the transport description matches it`() {
        val described = "连接超时：地址或端口可能不对。"
        assertTrue(isSameFailure(FAILURE_RECEIPT_PREFIX + described, described))
    }

    @Test
    fun `receipt carrying an extra note still matches`() {
        // 传输层那条会把重试说明接在后面（describeError + note）。
        assertTrue(isSameFailure(FAILURE_RECEIPT_PREFIX + event + "\n（已重试 2 次）", event))
        assertTrue(isSameFailure(FAILURE_RECEIPT_PREFIX + event, event + "\n"))
    }

    @Test
    fun `a different failure is not swallowed`() {
        assertFalse(isSameFailure(FAILURE_RECEIPT_PREFIX + "服务器返回 400。", event))
    }

    @Test
    fun `a blank error never draws the live line`() {
        assertFalse(isSameFailure(FAILURE_RECEIPT_PREFIX + event, "   "))
        assertFalse(isSameFailure("", event))
    }
}
