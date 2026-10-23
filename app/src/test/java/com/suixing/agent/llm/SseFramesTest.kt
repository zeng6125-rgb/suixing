package com.suixing.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 多行 JSON 的括号配平。
 *
 * 背景：网关有时把一个 JSON 拆成多行 `data:` 送达。原实现每收到一行就把整个 pending 重新
 * parse 一遍试探完整性 —— O(行数2)（30KB 的 chunk 约等于 600 行，十几万字符反复解析）。
 * 现在用括号配平：每行只扫本行，完整时仍会真正 parse 一次。
 *
 * 守的是容易被想当然写错的四种情形：字符串里的括号、转义引号、
 * 「闭合后又来一个新事件」、以及"拆行位置落在括号/引号中间"。
 */
class SseFramesTest {

    /** 把 chunks 依次喂进同一个状态（配平是跨行的），返回每步是否触发。 */
    private fun feed(vararg chunks: String): List<Boolean> {
        val state = SseFrames.State()
        return chunks.map { SseFrames.feed(state, it) }
    }

    @Test
    fun `a complete object on one line closes immediately`() {
        assertEquals(listOf(true), feed("""{"a":1}"""))
    }

    @Test
    fun `a second event after the first closes again`() {
        // 两个完整事件连着来：两次都该触发（closed 标记被新的 { 清零）
        assertEquals(listOf(true, true), feed("""{"a":1}""", """{"b":2}"""))
    }

    @Test
    fun `blank lines between events do not fire`() {
        // 闭合后的空行不该再触发一次（否则一次连接被切成几十个空事件）
        assertEquals(listOf(true, false, true), feed("""{"a":1}""", "", """{"b":2}"""))
    }

    @Test
    fun `a pretty-printed object closes only on its final brace`() {
        val head = arrayOf("{", "  \"choices\": [", "    {\"delta\": {\"content\": \"hi\"", "    }", "  ]")
        // 收尾要 `}}`（内层对象 + 外层对象），差一个都闭合不了
        assertFalse("还差最外层收尾", feed(*head).last())
        assertTrue("补齐最外层的 } 才闭合", feed(*(head + arrayOf("}", "}"))).last())
    }

    @Test
    fun `braces inside a string do not count`() {
        // 工具参数里带 JSON 片段是常态（write_file 的 content 就是）
        assertTrue(feed("""{"arguments": "{\"path\": \"a.kt\"}"}""").last())
        // 字符串里只有一个 }：不算闭合
        assertFalse(feed("""{"a": "}}""").last())
    }

    @Test
    fun `an escaped quote does not end the string`() {
        // `\"` 不是字符串结束 —— 误判会把字符串里的 { 算进深度
        // 第一行字符串还没结束（未闭合），空行不算，下一行才闭合
        // 拆行落在字符串中间：第二行把字符串收尾并闭合整个对象
        assertFalse(feed("""{"a": "他说\"你好\"{还没完""", "").last())
        assertTrue(feed("""{"a": "他说\"你好\"{还没完""", "\"", "}").last())
    }

    @Test
    fun `depth returns to zero across many tiny lines`() {
        // 模拟 SSE 一行只有几个字符的极端拆分
        val json = """{"tool_calls":[{"id":"c1","function":{"name":"read_file","arguments":"{}"}}]}"""
        val state = SseFrames.State()
        var fired = 0
        for (ch in json) if (SseFrames.feed(state, ch.toString())) fired++
        assertEquals("逐字符喂完只该闭合一次", 1, fired)
    }

    @Test
    fun `an unbalanced tail is never reported complete`() {
        // 连接被掐断在半途：深度不是 0，不能当成完整事件发出去
        assertFalse(feed("""{"choices":[{"delta":""").last())
        assertFalse(feed("""{"choices":[{"delta":""", """{"content":"abc""").last())
    }
}
