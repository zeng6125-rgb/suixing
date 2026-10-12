package com.suixing.agent.plugins

import com.suixing.agent.core.Core
import com.suixing.agent.tools.ToolSpec
import kotlinx.serialization.json.JsonObject

/** 求值后的 context 文本（M2 注入 system 用）。 */
data class ResolvedContext(val pluginId: String, val text: String)

/**
 * 插件的**读管道**（HOST-DEV §5 接线点 A）：只读消费 [Core.plugins]，给 payload 组装喂
 * 「有哪些插件工具、有哪些 context 文本」。
 *
 * 三条纪律：
 * 1. **实时扫**（v1 不做缓存）：每次组装 payload 都重读一遍目录 —— 用户刚启用的插件下一个请求
 *    就该出现，这也是热插拔的前提。
 * 2. **纯函数尽量多**：凡是能被单测覆盖的都做成吃 `List<PluginSpec>` 的纯函数（[toolsFor] /
 *    [toolSpecs] / [resolve] / [contextsFor]），只有 [enabledPlugins] 碰磁盘。
 * 3. **env 永不进这里**：本文件只产出「工具名 / 描述 / schema / context 文本」，凭据（`env.json`）
 *    只在 M3 执行时由 `PluginStore.env()` 读（§0.5）。
 *
 * `hooksFor()` / `execute()` 属 M3，这里先给空实现，**不要提前接线**。
 */
object PluginRuntime {

    /**
     * 启用中的插件。读盘 ⇒ suspend（[PluginStore.list] 是 suspend 的）。
     *
     * **必须容错**：`Core.plugins` 是 `lateinit`，单元测试里（以及任何 `Core.init` 尚未跑完的
     * 早期调用）它可能还没初始化 —— 那时抛异常会把整轮 run 带崩（实测：InterruptPersistence /
     * ParallelRun 共 7 条用例全挂在 `UninitializedPropertyAccessException`）。读插件失败或未初始化
     * 时返回空表：没有插件工具，这一轮照常跑。
     */
    suspend fun enabledPlugins(): List<PluginSpec> = runCatching {
        Core.plugins.list().filter { it.state.enabled }.mapNotNull { it.spec }
    }.getOrDefault(emptyList())

    /** `(插件, 工具声明)` 的平铺列表 —— 注册名由调用方拼成 `<id>.<tool>`。 */
    fun toolsFor(specs: List<PluginSpec>): List<Pair<PluginSpec, ToolDecl>> =
        specs.flatMap { spec -> spec.tools.map { spec to it } }

    /**
     * 组装进 payload 的工具表：内置工具之后追加插件工具，**重名跳过**。
     *
     * [taken] 是已经在表里的名字（内置工具名，以及前面已经加进去的插件注册名）。内置优先 ——
     * 一个插件不能靠重名顶掉 `run_shell` 这类工具；跳过的名字写一条日志，别静默消失。
     */
    fun toolSpecs(specs: List<PluginSpec>, taken: Set<String>): List<ToolSpec> {
        val added = mutableSetOf<String>()
        val out = mutableListOf<ToolSpec>()
        for ((spec, decl) in toolsFor(specs)) {
            val name = "${spec.id}.${decl.name}"
            if (name in taken || !added.add(name)) {
                PluginLog.skip(name, if (name in taken) "与已有工具重名" else "同一个插件里重复")
                continue
            }
            out += ToolSpec(
                name = name,
                description = decl.description,
                // schema 透传（v1 只做合法性检查，不解释它）。
                parameters = decl.parameters ?: JsonObject(emptyMap()),
            )
        }
        return out
    }

    /** 按注册名反查 —— M3 执行管道用它找 `(插件, 工具)`。 */
    fun resolve(specs: List<PluginSpec>, toolName: String): Pair<PluginSpec, ToolDecl>? =
        toolsFor(specs).firstOrNull { (spec, decl) -> "${spec.id}.${decl.name}" == toolName }

    /**
     * context 求值：`static` 原样返回；`template` 用 [vars] 替换白名单变量。
     *
     * 变量缺失（例如这一轮拿不到电池读数）时**整条跳过**并记日志 —— 宁可不注入，也不能把
     * `{{device.battery}}` 这种字面量塞进系统提示（模型会当成事实读）。
     */
    fun contextsFor(specs: List<PluginSpec>, vars: Map<String, String>): List<ResolvedContext> {
        val out = mutableListOf<ResolvedContext>()
        for (spec in specs) {
            for (context in spec.context) {
                if (context.source != "template") {
                    out += ResolvedContext(spec.id, context.content)
                    continue
                }
                val missing = mutableListOf<String>()
                val text = TEMPLATE_VAR.replace(context.content) { match ->
                    val key = match.groupValues[1]
                    vars[key] ?: run { missing += key; match.value }
                }
                if (missing.isNotEmpty()) {
                    PluginLog.skip("${spec.id} context", "变量没有值：${missing.joinToString("、")}")
                    continue
                }
                out += ResolvedContext(spec.id, text)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- M3（先留空，别提前做）

    /** M3：hooks 匹配（`tools/pre-execute`）。 */
    fun hooksFor(specs: List<PluginSpec>): List<Pair<PluginSpec, HookDecl>> = emptyList()
}

/** `{{x}}` / `{{env.NAME}}` —— 与校验器同一套写法。 */
private val TEMPLATE_VAR = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*}}""")

/** 读管道里的「跳过」也要说出来（§0.4 失败要显式）—— 落到 fx-trace 同级的日志习惯里。 */
private object PluginLog {
    fun skip(name: String, why: String) {
        android.util.Log.w("SuixingPlugins", "跳过 $name：$why")
    }
}
