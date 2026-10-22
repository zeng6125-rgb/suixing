package com.suixing.agent.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #34 缺陷 1：`http` 的 body 里，**字符串位置要转义、数字位置不能转义**。
 *
 * 模板形如 `{"query":"{{q}}","max_results":{{count}}}`：
 *  - `{{q}}` 在字符串里 ⇒ 查询词含双引号时必须转义，否则 JSON 坏掉被接口 400；
 *  - `{{count}}` 是数字位置 ⇒ 转义会让 `8` 变成 `"8"`，同样 400。
 */
class PluginBodyEscapeTest {

    private fun renderBody(template: String, vars: Map<String, String>): String =
        PluginRuntime.render(
            template,
            vars,
            mutableListOf(),
            jsonAware = PluginRuntime::jsonEscapeIfInString,
        )

    @Test
    fun `a quote inside a string value is escaped`() {
        val body = renderBody(
            """{"query":"{{q}}","max_results":{{count}}}""",
            mapOf("q" to "他说\"你好\"", "count" to "8"),
        )
        // 必须能解析成 JSON —— 这是接口 400 的真正判据。
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("他说\"你好\"", parsed["query"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(8), parsed["max_results"])
    }

    @Test
    fun `a number placeholder is not quoted`() {
        val body = renderBody(
            """{"max_results":{{count}}}""",
            mapOf("count" to "8"),
        )
        assertFalse("数字位置不能被转义成字符串", body.contains("\"8\""))
        assertEquals(JsonPrimitive(8), Json.parseToJsonElement(body).jsonObject["max_results"])
    }

    @Test
    fun `backslash and newline in a value do not break the json`() {
        val body = renderBody(
            """{"q":"{{q}}"}""",
            mapOf("q" to "a\b\nc"),
        )
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("a\b\nc", parsed["q"]!!.jsonPrimitive.content)
    }

    @Test
    fun `isInsideJsonString counts only unescaped quotes`() {
        val template = """{"a":"x{{v}}","b":{{n}}}"""
        val inA = template.indexOf("{{v}}")
        val inN = template.indexOf("{{n}}")
        assertTrue("{{v}} 在字符串里", PluginRuntime.isInsideJsonString(template, inA))
        assertFalse("{{n}} 在数字位置", PluginRuntime.isInsideJsonString(template, inN))
    }
}
