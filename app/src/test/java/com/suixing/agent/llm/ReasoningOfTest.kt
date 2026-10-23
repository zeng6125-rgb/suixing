package com.suixing.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 思考内容的提取顺序。
 *
 * 各家网关把思考放在不同字段：`delta.reasoning_content`（DeepSeek 系）、
 * `delta.reasoning`、`choice.message.reasoning`（非流式回放）、甚至直接在 `choice` 上。
 * 顺序有讲究 —— `delta` 是增量，必须优先于整条 `message`，否则每一块都会收到
 * 已经发过的全文（表现为思考内容重复堆叠）。
 */
class ReasoningOfTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun pick(
        chunk: String = "{}",
        choice: String = "{}",
        delta: String? = null,
        message: String? = null,
    ): String? = reasoningOf(
        chunk = obj(chunk),
        choice = obj(choice),
        delta = delta?.let(::obj),
        message = message?.let(::obj),
    )

    @Test
    fun `delta wins over the whole message`() {
        // 同一条里既有增量又有全文时，必须取增量
        assertEquals(
            "增量",
            pick(delta = """{"reasoning_content":"增量"}""", message = """{"reasoning_content":"全文"}"""),
        )
    }

    @Test
    fun `reasoning_content wins over reasoning on the same object`() {
        assertEquals("a", pick(delta = """{"reasoning_content":"a","reasoning":"b"}"""))
    }

    @Test
    fun `falls back to choice then chunk`() {
        assertEquals("c", pick(choice = """{"reasoning_content":"c"}"""))
        assertEquals("d", pick(chunk = """{"reasoning":"d"}"""))
    }

    @Test
    fun `blank and absent both yield null so no empty block is opened`() {
        assertNull(pick(delta = """{"reasoning_content":"   "}"""))
        assertNull(pick(delta = """{"reasoning_content":null}"""))
        assertNull(pick())
    }
}
