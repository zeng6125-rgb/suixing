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
    @Test fun `many placeholders preserve JSON string state across escaped quotes`() {
        val template = (0 until 200).joinToString(",", "{", "}") { index ->
            "\"v$index\":\"before\\\"{{q}}\",\"n$index\":{{count}}"
        }
        val body = renderBody(template, mapOf("q" to "a\"b", "count" to "8"))
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals("before\"a\"b", parsed["v199"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(8), parsed["n199"])
    }

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

    // ─── issue #34 补充：锁住"跳过转义"的边界 ───────────────────────────────────

    /** JSON 的引号与反斜杠（用字符码构造模板，避免这一行本身要转义）。 */
    private val Q = 34.toChar()
    private val BS = 92.toChar()

    @Test
    fun `an escaped quote does not change the string boundary`() {
        // 模板：{"q":"a\"{{q}}"}   ← 其中 \" 是字符串**内部**的转义引号
        // 不跳过它就会多数一个引号（3 个，奇数）—— 这条本身区分不出；
        // 所以下面再加一条偶数引号的场景专门卡它。
        val template = "{${Q}q${Q}:${Q}a$BS$Q{{q}}${Q}}"
        val at = template.indexOf("{{q}}")
        assertTrue("转义引号之后仍应在字符串内", PluginRuntime.isInsideJsonString(template, at))
    }

    @Test
    fun `an escaped quote keeps an even count even`() {
        // 模板：{"a":"\"x\"","b":{{n}}}
        // 引号：`"a"` 两个、`\"` `\"` 两个（**不该计入**）、`"b"` 两个 = 实际边界 4 个（偶数）
        // ⇒ {{n}} 在数字位置。如果实现不跳过 `\"`，会数出 6 个（也是偶数）——
        // 所以这一条换成"只有一个转义引号"的场景才能真正区分：
        // 模板：{"a":"\"","b":{{n}}}  —— 边界引号 4 个（偶数），不跳过会数出 6 个（也是偶数）
        // 于是改用"奇数/偶数会翻转"的组合：字符串内一个转义引号 + 一个正常引号。
        // 模板：{"q":"\"b","n":{{n}}}
        //   边界：开头"、q 后"、值后" = 3 个奇数开头；\" 不计数
        //   到 {{n}} 之前的真实边界引号 = 4 个（偶数）⇒ 数字位置
        //   若不跳过 \"：会数出 5 个（奇数）⇒ 误判成字符串内
        val template = "{${Q}q${Q}:${Q}$BS${Q}b${Q},${Q}n${Q}:{{n}}}"
        val at = template.indexOf("{{n}}")
        assertFalse(
            "转义引号不该被计入边界（数错会把它误判成字符串内）",
            PluginRuntime.isInsideJsonString(template, at),
        )
    }

    @Test
    fun `a closed string is not inside`() {
        // 偶数个引号 ⇒ 字符串已闭合 ⇒ 数字位置，不该转义
        val template = """{"a":"x","b":{{n}}}"""
        val at = template.indexOf("{{n}}")
        assertFalse("闭合后是数字位置", PluginRuntime.isInsideJsonString(template, at))
    }

    @Test
    fun `an escaped backslash does not swallow the next quote`() {
        // 模板里出现 \ （一个字面反斜杠）之后，引号仍是边界
        val template = """{"q":"a\\","b":{{n}}}"""
        val at = template.indexOf("{{n}}")
        assertFalse("闭合后是数字位置", PluginRuntime.isInsideJsonString(template, at))
    }

    @Test
    fun `every placeholder in a realistic body is decided correctly`() {
        val template =
            """{"query":"{{q}}","max_results":{{count}},"filter":{"tags":["{{tag}}"]}}"""
        assertTrue("{{q}} 在字符串里", PluginRuntime.isInsideJsonString(template, template.indexOf("{{q}}")))
        assertFalse("{{count}} 是数字", PluginRuntime.isInsideJsonString(template, template.indexOf("{{count}}")))
        assertTrue("{{tag}} 在数组的字符串里", PluginRuntime.isInsideJsonString(template, template.indexOf("{{tag}}")))
    }

    @Test
    fun `a missing value stays literal and is reported`() {
        val missing = mutableListOf<String>()
        val out = PluginRuntime.render(
            """{"query":"{{nope}}"}""",
            emptyMap(),
            missing,
            jsonAware = PluginRuntime::jsonEscapeIfInString,
        )
        assertEquals("""{"query":"{{nope}}"}""", out)
        assertEquals(listOf("nope"), missing)
    }

    @Test
    fun `header values lose their newlines`() {
        // 模型给的参数里带换行很常见；OkHttp 遇到 CR/LF 会抛 IllegalArgumentException。
        val out = PluginRuntime.headerSafe("Bearer a b")
        assertEquals("Bearer a b", out)
        val withNewline = PluginRuntime.headerSafe("a b")
        assertEquals("a b", withNewline)
        val crlf = PluginRuntime.headerSafe("x y")
        assertEquals("x y", crlf)
    }

}
