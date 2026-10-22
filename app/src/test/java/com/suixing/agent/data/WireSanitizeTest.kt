package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WireSanitize] 守的两种 **400 形状**（都是真实发生过的，不是假想）：
 *  1. 助手声明了 N 个 tool call，结果只回来 M < N 个（并行里一半没回来就停了/被杀）；
 *  2. 工具结果还在，但它的助手已经不在了（单独一条 `tool` 消息）。
 *
 * 这两种在端点侧的表现都是「发送失败」，用户看不出原因 —— 所以用断言钉住。
 */
class WireSanitizeTest {

    private fun user(id: String) = StoredMessage(id = id, role = Roles.USER, text = id)
    private fun ask(id: String) = StoredMessage(id = id, role = Roles.ASSISTANT, text = id)
    private fun call(id: String, vararg callIds: String) = StoredMessage(
        id = id, role = Roles.ASSISTANT, text = "",
        toolCalls = callIds.map { StoredToolCall(id = it, name = "read_file", arguments = "{}") },
    )
    private fun result(id: String, callId: String?) = StoredMessage(
        id = id, role = Roles.TOOL, text = id, toolCallId = callId, toolName = "read_file",
    )

    @Test
    fun `a matched pair survives untouched`() {
        val wire = listOf(user("u1"), call("a1", "c1"), result("t1", "c1"), ask("a2"))
        assertEquals("成对的消息不该被改动", wire.map { it.id }, WireSanitize.apply(wire).map { it.id })
    }

    @Test
    fun `an assistant whose tool result never came is dropped`() {
        // 形状 1：呼叫了 c1，但结果没回来（模型被停/被杀在这一步之后）。
        val wire = listOf(user("u1"), call("a1", "c1"))
        assertEquals(listOf("u1"), WireSanitize.apply(wire).map { it.id })
    }

    @Test
    fun `a half-answered parallel round drops both the call and the orphan result`() {
        // 形状 1 的现实版本：c1 回来了、c2 没有。助手要丢，那条孤儿结果也要丢。
        val wire = listOf(user("u1"), call("a1", "c1", "c2"), result("t1", "c1"))
        assertEquals("只留下真正的用户轮", listOf("u1"), WireSanitize.apply(wire).map { it.id })
    }

    @Test
    fun `a tool result with no caller is dropped`() {
        // 形状 2：结果在，但问它的助手已经不在了。
        val wire = listOf(user("u1"), result("t1", "gone"), ask("a2"))
        assertEquals(listOf("u1", "a2"), WireSanitize.apply(wire).map { it.id })
    }

    @Test
    fun `a tool result without an id is dropped`() {
        val wire = listOf(user("u1"), result("t1", null))
        assertEquals(listOf("u1"), WireSanitize.apply(wire).map { it.id })
    }

    @Test
    fun `plain conversation is returned as is`() {
        val wire = listOf(user("u1"), ask("a1"), user("u2"), ask("a2"))
        assertEquals(wire.map { it.id }, WireSanitize.apply(wire).map { it.id })
    }

    @Test
    fun `order is preserved`() {
        val wire = listOf(user("u1"), call("a1", "c1"), result("t1", "c1"), user("u2"))
        assertEquals(listOf("u1", "a1", "t1", "u2"), WireSanitize.apply(wire).map { it.id })
    }
}
