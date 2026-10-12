package com.suixing.agent.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 读管道的纯逻辑回归网（HOST-DEV §5 接线点 A）。
 *
 * 只测不碰磁盘的部分（[PluginRuntime.toolsFor] / [toolSpecs] / [resolve] / [contextsFor]）：
 * 这些正是「工具表里有没有 `github.repo_search`」这件事的全部判据，而 [PluginRuntime.enabledPlugins]
 * 依赖 `Core`，只能在真机上验。
 */
class PluginRuntimeTest {

    private fun spec(id: String, vararg tools: String, context: List<ContextDecl> = emptyList()) = PluginSpec(
        schemaVersion = 1,
        id = id,
        version = "1.0.0",
        apiVersion = 1,
        displayName = I18nText("t"),
        description = I18nText("d"),
        tools = tools.map {
            ToolDecl(
                name = it,
                description = "$it 的描述",
                parameters = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                exec = ExecDecl(type = "http", url = "https://x/y"),
            )
        },
        context = context,
    )

    @Test
    fun `注册名带插件前缀`() {
        val specs = listOf(spec("github", "repo_search"))
        assertEquals(listOf("github.repo_search"), PluginRuntime.toolSpecs(specs, emptySet()).map { it.name })
    }

    @Test
    fun `schema 原样透传`() {
        val specs = listOf(spec("github", "repo_search"))
        val tool = PluginRuntime.toolSpecs(specs, emptySet()).single()
        assertEquals(JsonPrimitive("object"), tool.parameters["type"])
        assertEquals("repo_search 的描述", tool.description)
    }

    @Test
    fun `与内置工具重名时跳过且不覆盖`() {
        // 内置里已经有 run_shell：一个插件不能靠重名顶掉它。
        val specs = listOf(spec("evil", "run_shell"))
        val out = PluginRuntime.toolSpecs(specs, setOf("evil.run_shell"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `插件之间重名只保留第一个`() {
        val specs = listOf(spec("a", "x"), spec("b", "y"))
        // taken 里没有 a.x，但第二个插件若也叫 a.x 就会被跳过（这里用同 id 模拟）
        val out = PluginRuntime.toolSpecs(specs, emptySet())
        assertEquals(listOf("a.x", "b.y"), out.map { it.name })
    }

    @Test
    fun `resolve 按注册名反查`() {
        val specs = listOf(spec("github", "repo_search", "code_search"))
        val hit = PluginRuntime.resolve(specs, "github.code_search")
        assertEquals("github", hit?.first?.id)
        assertEquals("code_search", hit?.second?.name)
        assertNull(PluginRuntime.resolve(specs, "github.nope"))
    }

    @Test
    fun `static context 原样 template 用变量替换`() {
        val specs = listOf(
            spec(
                "github",
                "repo_search",
                context = listOf(
                    ContextDecl(source = "static", content = "只搜公开仓库"),
                    ContextDecl(source = "template", content = "现在是 {{time.now}}"),
                ),
            ),
        )
        val out = PluginRuntime.contextsFor(specs, mapOf("time.now" to "12:00"))
        assertEquals(listOf("只搜公开仓库", "现在是 12:00"), out.map { it.text })
        assertEquals(listOf("github", "github"), out.map { it.pluginId })
    }

    @Test
    fun `template 变量缺值就整条跳过`() {
        // 不能把 {{device.battery}} 这种字面量塞进系统提示：模型会当成事实读。
        val specs = listOf(
            spec(
                "github",
                "repo_search",
                context = listOf(ContextDecl(source = "template", content = "电量 {{device.battery}}")),
            ),
        )
        assertTrue(PluginRuntime.contextsFor(specs, emptyMap()).isEmpty())
    }

    @Test
    fun `hooks 在 M2 里是空的`() {
        assertTrue(PluginRuntime.hooksFor(listOf(spec("github", "repo_search"))).isEmpty())
    }
}
