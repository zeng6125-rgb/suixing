package com.suixing.agent.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 读管道（HOST-DEV §5 接线点 A）与 M3 执行管道里**不碰磁盘的那一半**的纯逻辑回归网。
 *
 * 覆盖：[PluginRuntime.toolsFor] / [toolSpecs] / [resolve] / [contextsFor]（工具表里有没有
 * `github_repo_search` 的全部判据）、[preExecute]（hook 裁决：三个条件 AND、只能收紧、坏 regex
 * 跳过）、[render]（模板求值与缺值上报）、[shape] / [pickPath]（`pick` 与 `max_chars`）。
 *
 * 没覆盖的只有真的发 http / 跑 shell / 读 `env.json` 那一段：它们要 `Core` 与网络，只能在真机上验。
 * 所以这里每条都要断言**失败的形状**（缺值时报哪个变量、pick 落空时说什么），因为那正是执行段
 * 唯一能拿到的信号。
 */
class PluginRuntimeTest {

    private fun spec(
        id: String,
        vararg tools: String,
        context: List<ContextDecl> = emptyList(),
        hooks: List<HookDecl> = emptyList(),
        deps: List<DepDecl> = emptyList(),
    ) = PluginSpec(
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
        hooks = hooks,
        deps = deps,
    )

    /** v1 只有这一个事件，所以 on 固定写死。 */
    private fun hook(
        action: String,
        tool: String? = null,
        regex: String? = null,
        minRisk: String? = null,
        message: String? = null,
    ) = HookDecl(
        on = "tools/pre-execute",
        `when` = WhenDecl(tool = tool, regex = regex, minRisk = minRisk),
        action = action,
        message = message,
    )

    @Test
    fun `注册名带插件前缀`() {
        val specs = listOf(spec("github", "repo_search"))
        assertEquals(listOf("github_repo_search"), PluginRuntime.toolSpecs(specs, emptySet()).map { it.name })
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
        val out = PluginRuntime.toolSpecs(specs, setOf("evil_run_shell"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `插件之间重名只保留第一个`() {
        val specs = listOf(spec("a", "x"), spec("b", "y"))
        // taken 里没有 a.x，但第二个插件若也叫 a.x 就会被跳过（这里用同 id 模拟）
        val out = PluginRuntime.toolSpecs(specs, emptySet())
        assertEquals(listOf("a_x", "b_y"), out.map { it.name })
    }

    @Test
    fun `resolve 按注册名反查`() {
        val specs = listOf(spec("github", "repo_search", "code_search"))
        val hit = PluginRuntime.resolve(specs, "github_code_search")
        assertEquals("github", hit?.first?.id)
        assertEquals("code_search", hit?.second?.name)
        assertNull(PluginRuntime.resolve(specs, "github_nope"))
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
    fun `hooksFor 平铺出每条 hook`() {
        val specs = listOf(
            spec("github", "repo_search", hooks = listOf(hook(action = "log"))),
            spec("other", "a", hooks = listOf(hook(action = "deny"), hook(action = "log"))),
        )
        val out = PluginRuntime.hooksFor(specs)
        assertEquals(listOf("github", "other", "other"), out.map { it.first.id })
        assertEquals(listOf("log", "deny", "log"), out.map { it.second.action })
    }

    @Test
    fun `声明了 deps 的工具仍进工具表`() {
        // 0.8.107 起 deps 不再禁用工具：闸门误杀了自带产物的插件（video-frame 的 bin/ 里就躺着
        // ffmpeg）。依赖真缺时 shell 自己的报错更真。
        val specs = listOf(
            spec("withdeps", "x", deps = listOf(DepDecl(id = "ffmpeg", url = "https://x/f", sha256 = "a", install = "true"))),
            spec("plain", "y"),
        )
        assertEquals(
            listOf("withdeps_x", "plain_y"),
            PluginRuntime.toolSpecs(specs, emptySet()).map { it.name },
        )
    }

    // ---------------------------------------------------------------- M3 hooks

    @Test
    fun `deny 立刻返回且带上 message`() {
        val hooks = listOf(spec("github", "x") to hook(action = "deny", message = "这个插件不许删东西"))
        val verdict = PluginRuntime.preExecute(hooks, "run_shell", """{"command":"rm -rf /"}""", risk = null)
        assertTrue(verdict.deny)
        assertEquals("这个插件不许删东西", verdict.message)
    }

    @Test
    fun `没有 hook 命中就是空裁决`() {
        val verdict = PluginRuntime.preExecute(emptyList(), "run_shell", "{}", risk = null)
        assertFalse(verdict.deny)
        assertFalse(verdict.requireConfirm)
    }

    @Test
    fun `when_tool 只对那个工具生效`() {
        val hooks = listOf(spec("github", "x") to hook(action = "deny", tool = "run_shell"))
        assertTrue(PluginRuntime.preExecute(hooks, "run_shell", "{}", null).deny)
        assertFalse(PluginRuntime.preExecute(hooks, "github_x", "{}", null).deny)
    }

    @Test
    fun `when_regex 匹配的是参数原文`() {
        val hooks = listOf(spec("github", "x") to hook(action = "deny", regex = "rm\\s+-rf"))
        assertTrue(PluginRuntime.preExecute(hooks, "run_shell", """{"command":"rm -rf /sdcard"}""", null).deny)
        assertFalse(PluginRuntime.preExecute(hooks, "run_shell", """{"command":"ls"}""", null).deny)
    }

    @Test
    fun `min_risk 对没有风险档的调用不成立`() {
        // 内置工具只有能力档（tier），风险档是 null：一条 min_risk=high 的 hook 不该拦到它 ——
        // 要拦内置工具得用 when.tool + when.regex。
        val hooks = listOf(spec("github", "x") to hook(action = "deny", minRisk = "high"))
        assertFalse(PluginRuntime.preExecute(hooks, "run_shell", "{}", risk = null).deny)
        assertFalse(PluginRuntime.preExecute(hooks, "github_x", "{}", risk = "medium").deny)
        assertTrue(PluginRuntime.preExecute(hooks, "github_x", "{}", risk = "high").deny)
    }

    @Test
    fun `tool 与 regex 是 AND 关系`() {
        val hooks = listOf(spec("github", "x") to hook(action = "deny", tool = "run_shell", regex = "pm clear"))
        assertFalse(PluginRuntime.preExecute(hooks, "run_shell", """{"command":"ls"}""", null).deny)
        assertFalse(PluginRuntime.preExecute(hooks, "github_x", """{"command":"pm clear a"}""", null).deny)
        assertTrue(PluginRuntime.preExecute(hooks, "run_shell", """{"command":"pm clear a"}""", null).deny)
    }

    @Test
    fun `require_confirm 扫完所有 hook 才返回`() {
        val hooks = listOf(
            spec("a", "x") to hook(action = "require_confirm"),
            spec("b", "y") to hook(action = "log"),
        )
        val verdict = PluginRuntime.preExecute(hooks, "github_x", "{}", null)
        assertTrue(verdict.requireConfirm)
        assertFalse(verdict.deny)
    }

    @Test
    fun `regex 编译失败就跳过这条 hook`() {
        // 安装时校验器已经拦过（REGEX_INVALID），走到这里说明目录是手拷进去的：一条坏 pattern
        // 不该让整轮调用失败，也不该把后面合法的 hook 一起吃掉。
        val hooks = listOf(
            spec("a", "x") to hook(action = "deny", regex = "([unclosed"),
            spec("b", "y") to hook(action = "require_confirm"),
        )
        val verdict = PluginRuntime.preExecute(hooks, "github_x", "{}", null)
        assertFalse(verdict.deny)
        assertTrue(verdict.requireConfirm)
    }

    // ---------------------------------------------------------------- M3 模板求值

    @Test
    fun `render 替换参数与 env 并保留未知变量`() {
        val missing = mutableListOf<String>()
        val out = PluginRuntime.render(
            "https://api.github.com/search?q={{query}}&t={{env.TOKEN}}&d={{plugin_dir}}&x={{nope}}",
            mapOf(
                "query" to "kotlin",
                "env.TOKEN" to "secret",
                "plugin_dir" to "/data/data/x/files/plugins/github",
            ),
            missing,
        )
        assertEquals(
            "https://api.github.com/search?q=kotlin&" +
                "t=secret&d=/data/data/x/files/plugins/github&x={{nope}}",
            out,
        )
        // 缺值要说出来是哪一个，调用方才能报「去填 X」而不是发一个空 token。
        assertEquals(listOf("nope"), missing)
    }

    @Test
    fun `url 里的值百分号编码 header 里原样`() {
        val missing = mutableListOf<String>()
        val vars = mapOf("q" to "a b&c", "env.TOKEN" to "Bearer x")
        // url 不编码就会变成两个查询参数。
        assertEquals(
            "https://x/y?q=a+b%26c",
            PluginRuntime.render("https://x/y?q={{q}}", vars, missing, ::urlEncodeForTest),
        )
        // 编码会把 `Bearer x` 变成 `Bearer+x`，header 必须原样。
        assertEquals(
            "Bearer x",
            PluginRuntime.render("{{env.TOKEN}}", vars, missing),
        )
        assertTrue(missing.isEmpty())
    }

    // ---------------------------------------------------------------- M3 响应整形

    @Test
    fun `非 2xx 直接算失败且带上状态码`() {
        val result = PluginRuntime.shape("""{"message":"Not Found"}""", code = 404, ok = false, response = null)
        assertFalse(result.ok)
        assertTrue(result.output.startsWith("[HTTP 404]"))
        assertTrue(result.output.contains("Not Found"))
    }

    @Test
    fun `pick 取子集`() {
        val raw = """{"items":[{"name":"a"},{"name":"b"}],"total":2}"""
        val result = PluginRuntime.shape(raw, 200, true, ResponseDecl(pick = "items.1.name"))
        assertTrue(result.ok)
        assertTrue(result.output.contains("\"b\""))
        assertFalse(result.output.contains("\"a\""))
    }

    @Test
    fun `pick 落空不回退成整份响应`() {
        // 回退成整份正是 pick 要防的体积失控，而且「路径不对」和「返回了别的东西」是两种失败。
        val result = PluginRuntime.shape("""{"items":[]}""", 200, true, ResponseDecl(pick = "items.0.name"))
        assertFalse(result.ok)
        assertTrue(result.output.contains("items.0.name"))
    }

    @Test
    fun `响应不是 JSON 时说明而不是静默返回全文`() {
        val result = PluginRuntime.shape("<html>boom</html>", 200, true, ResponseDecl(pick = "a.b"))
        assertFalse(result.ok)
        // 断言的是**给模型看的那句话**，所以 message 里带上它 —— 无 message 的 assertTrue 在报告
        // 里只有一行栈，看不出「不是 JSON」和「路径落空」这两种失败到底说成了哪一种。
        assertTrue("shape 输出：${result.output}", result.output.contains("不是 JSON"))
    }

    @Test
    fun `max_chars 截断并说明截了多少`() {
        val raw = "x".repeat(300)
        val result = PluginRuntime.shape(raw, 200, true, ResponseDecl(maxChars = 100))
        assertTrue("shape 输出：${result.output}", result.ok)
        // 留了 100 字、没有第 101 个。output 带 `[HTTP 200]\n` 前缀，所以只能按「有没有 101 个 x」
        // 判，不能按 startsWith 判。
        assertTrue("shape 输出：${result.output}", result.output.contains("x".repeat(100)))
        assertFalse("shape 输出：${result.output}", result.output.contains("x".repeat(101)))
        assertTrue("shape 输出：${result.output}", result.output.contains("已截断"))
        assertTrue("shape 输出：${result.output}", result.output.contains("300"))
    }

    @Test
    fun `pickPath 走对象键与数组下标`() {
        val root = Json.parseToJsonElement("""{"a":{"b":[{"c":1}]}}""")
        assertEquals(JsonPrimitive(1), PluginRuntime.pickPath(root, "a.b.0.c"))
        assertNull(PluginRuntime.pickPath(root, "a.b.1.c"))
        assertNull(PluginRuntime.pickPath(root, "a.z"))
        // 在标量上继续往下取也是 null，而不是抛异常。
        assertNull(PluginRuntime.pickPath(root, "a.b.0.c.d"))
    }
}

/** 与 PluginRuntime 私有的 urlEncode 同口径（`java.net.URLEncoder`），只为在测试里调得到。 */
private fun urlEncodeForTest(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
