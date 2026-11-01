package com.suixing.agent.ui.chat

import com.suixing.agent.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 迁出后第一次有测试覆盖的映射（issue #69 P2-C）。
 *
 * 两条行为以前只写在代码里、没有任何断言：空会话要回落到默认助手，读盘的会话不能停在
 * 「正在打开」的骨架态。
 */
class ChatUiStateMappingTest {

    @Test
    fun `a blank assistant falls back to the caller's default`() {
        val state = Conversation(id = "c", title = "t", assistantId = "").toUiState("default-id")
        assertEquals("default-id", state.assistantId)
    }

    @Test
    fun `an explicit assistant is kept`() {
        val state = Conversation(id = "c", title = "t", assistantId = "mine").toUiState("default-id")
        assertEquals("mine", state.assistantId)
    }

    @Test
    fun `a disk read never stays in the loading skeleton`() {
        val state = Conversation(id = "c", title = "t", messageCount = 12, turnCount = 3).toUiState("a")
        assertFalse("读盘后的状态必须撤掉骨架标记", state.loading)
        assertEquals(12, state.messageCount)
        assertEquals(3, state.turnCount)
    }
}
