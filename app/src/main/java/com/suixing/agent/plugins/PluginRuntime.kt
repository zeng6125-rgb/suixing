package com.suixing.agent.plugins

import com.suixing.agent.capability.Shell
import com.suixing.agent.core.Core
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 求值后的 context 文本（M2 注入 system 用）。 */
data class ResolvedContext(val pluginId: String, val text: String)

/**
 * 一次 `tools/pre-execute` 的裁决（M3）。
 *
 * **只有收紧的两个方向**：[deny]（不许调）与 [requireConfirm]（本来会自动跑的这一档改成问一次）。
 * 没有任何 action 能放宽审批 —— 一个插件不能替用户放行另一个插件的调用。
 */
data class HookVerdict(
    val deny: Boolean = false,
    val requireConfirm: Boolean = false,
    /** deny 时给模型看的原因（取第一条命中的 hook 的 message）。 */
    val message: String? = null,
)

/**
 * 插件的运行时：**读管道**（HOST-DEV §5 接线点 A：工具表与 context 注入）+ **执行管道**
 * （接线点 B：hook 裁决与实际发 http / 跑 shell）。只读消费 [Core.plugins]。
 *
 * 四条纪律：
 * 1. **实时扫**（v1 不做缓存）：每次组装 payload 都重读一遍目录 —— 用户刚启用的插件下一个请求
 *    就该出现，这也是热插拔的前提。
 * 2. **纯函数尽量多**：凡是能被单测覆盖的都做成不碰磁盘的函数（[toolsFor] / [toolSpecs] /
 *    [resolve] / [contextsFor] / [preExecute] / [render] / [shape] / [pickPath]），碰磁盘的只有
 *    [enabledPlugins] 与 [execute]。
 * 3. **env 永不进读管道**：上面那批纯函数只产出「工具名 / 描述 / schema / context 文本」；
 *    凭据（`env.json`）只在 [execute] 里读出来用掉，不返回、不打日志（§0.5）。
 * 4. **失败要显式**：模板缺值、pick 落空、exec.type 不认识，都返回一句说得清原因的失败文本，
 *    绝不静默降级成「发了个空值」或「返回整份响应」。
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
     *
     * 声明了 deps 的插件的工具也跳过（[blockedByDeps]）：一个必然失败的工具出现在表里，模型就会
     * 一轮轮去调它，把整轮时间花在注定拿不到结果的调用上。
     */
    fun toolSpecs(specs: List<PluginSpec>, taken: Set<String>): List<ToolSpec> {
        val added = mutableSetOf<String>()
        val out = mutableListOf<ToolSpec>()
        for ((spec, decl) in toolsFor(specs)) {
            val name = "${spec.id}.${decl.name}"
            if (blockedByDeps(spec) != null) {
                PluginLog.skip(name, "声明了 deps（v1 不安装依赖），工具已禁用")
                continue
            }
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
     * v1 不装 deps，所以声明了 deps 的插件的工具**一律禁用**：跑它会拿本应用的身份去执行一条
     * 依赖没装上的命令，失败信息还会是一句和真实原因无关的报错。
     *
     * 校验器在安装白盒页上早就这么写了（`DEPS_DISABLED`：「deps 在 v1 不安装，依赖它的工具会被
     * 禁用」）—— M3 之前调用无论如何都会失败，那句话是空口承诺也无害；执行管道接通之后它就成了
     * 必须兑现的闸门，否则白盒页说的话和实际行为对不上。
     *
     * 返回 null ＝ 可以跑；返回字符串 ＝ 给模型看的失败原因。
     */
    internal fun blockedByDeps(spec: PluginSpec): String? =
        if (spec.deps.isEmpty()) {
            null
        } else {
            "插件「${spec.displayName.zh}」声明了 ${spec.deps.size} 条依赖（deps），" +
                "v1 还不安装依赖，它的工具已被禁用：${spec.deps.joinToString("、") { it.id }}"
        }

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

    // ---------------------------------------------------------------- M3 执行管道

    /** 全部启用插件的 hooks。引擎每轮取一次，交给 [preExecute] 裁决。 */
    fun hooksFor(specs: List<PluginSpec>): List<Pair<PluginSpec, HookDecl>> =
        specs.flatMap { spec -> spec.hooks.map { spec to it } }

    /**
     * 一次调用前的 hook 裁决（§5，事件 `tools/pre-execute`）。
     *
     * **对每一次工具调用生效，内置工具也算** —— 三个 action 全都只能收紧（deny / 多问一次 /
     * 记一条日志），没有任何一个能放宽审批，所以「管得更宽」不会替用户放行任何东西。
     *
     * [risk] 只有插件工具有（内置工具只有能力档 `tier`，没有风险档），传 null 时 `min_risk`
     * 条件一律不成立 —— 拦内置工具请用 `when.tool` + `when.regex`。
     *
     * 三个条件是 **AND**；同一条 hook 的 `when` 全空就是「所有调用」。命中的第一条 `deny`
     * 立刻返回（后面的 hook 不再看），`require_confirm` 与 `log` 继续扫完。
     */
    fun preExecute(
        hooks: List<Pair<PluginSpec, HookDecl>>,
        toolName: String,
        args: String,
        risk: String?,
    ): HookVerdict = runCatching {
        preExecuteUnsafe(hooks, toolName, args, risk)
    }.getOrElse { error ->
        // 0.8.100 真机教训：裁决本身抛异常（当时是门面类 <clinit> 失败变成的
        // NoClassDefFoundError）会掀翻整轮 run —— 用户看到「对话自己停了」。裁决失败时**落到
        // require_confirm** 而不是放行或炸轮：AUTO 档会变成「问一次」（人在环里，坏掉的守卫不会
        // 静默失效），ASK/DENY 档行为不变；同时记一条日志说明为什么。
        PluginLog.skip("hook 裁决（$toolName）", "${error::class.java.simpleName}: ${error.message}")
        HookVerdict(requireConfirm = true)
    }

    private fun preExecuteUnsafe(
        hooks: List<Pair<PluginSpec, HookDecl>>,
        toolName: String,
        args: String,
        risk: String?,
    ): HookVerdict {
        var requireConfirm = false
        for ((spec, hook) in hooks) {
            if (hook.on != HOOK_EVENT) continue
            val condition = hook.`when`
            if (condition.tool != null && condition.tool != toolName) continue
            val minRisk = condition.minRisk
            if (minRisk != null && (risk == null || riskRank(risk) < riskRank(minRisk))) continue
            val pattern = condition.regex
            if (pattern != null) {
                // 每条 pattern 只编译一次（仓库规矩：不在循环里 `Regex(...)`）。编译失败的
                // pattern 装不进缓存，所以拿不到就再试一次、失败就跳过这条 hook 并说出来 ——
                // 校验器在安装时已经拦过一遍（REGEX_INVALID），走到这里说明目录是手拷进去的。
                val regex = HOOK_REGEX[pattern]
                    ?: runCatching { Regex(pattern) }.getOrNull()?.also { HOOK_REGEX[pattern] = it }
                if (regex == null) {
                    PluginLog.skip("${spec.id} hook", "regex 编译失败：$pattern")
                    continue
                }
                if (!regex.containsMatchIn(args)) continue
            }
            when (hook.action) {
                "deny" -> return HookVerdict(
                    deny = true,
                    message = hook.message ?: "插件「${spec.displayName.zh}」禁止调用 $toolName。",
                )
                "require_confirm" -> requireConfirm = true
                "log" -> PluginLog.hook(spec.id, hook.message ?: toolName)
            }
        }
        return HookVerdict(requireConfirm = requireConfirm)
    }

    /**
     * 执行一个插件工具（§5 接线点 B）：求值模板 → 发 http / 跑 shell → 按 `response` 整形返回。
     *
     * **env 的值只在这个函数里出现**（§0.5）：调用方（引擎）只交出 `(spec, decl, args)`，拿不到
     * `env.json`，也就没有机会把它写进日志或 payload。失败信息只说**哪个变量没值**，绝不回显
     * 渲染后的 url / header / command —— 那里面可能正躺着凭据。
     */
    suspend fun execute(spec: PluginSpec, decl: ToolDecl, args: JsonObject): ToolResult {
        val depsBlocked = blockedByDeps(spec)
        if (depsBlocked != null) return ToolResult.failure(depsBlocked)
        val store = runCatching { Core.plugins }.getOrNull()
            ?: return ToolResult.failure("插件系统还没初始化，${spec.id}.${decl.name} 现在跑不了。")
        val dir = store.dirOf(spec.id)
        // 存下来的 env 覆盖 manifest 里的默认值；两者都没有的**不放进表**，好让渲染时报「没值」
        // 而不是悄悄发一个空 token 出去。
        val stored = runCatching { store.env(spec.id) }.getOrDefault(emptyMap())
        val vars = buildMap {
            for ((key, value) in args) put(key, jsonText(value))
            for (declared in spec.env) {
                val value = stored[declared.name] ?: declared.defaultValue
                if (!value.isNullOrEmpty()) put("env.${declared.name}", value)
            }
            put("plugin_dir", dir.absolutePath)
        }
        return when (decl.exec.type) {
            "http" -> http(spec, decl, vars)
            "shell" -> shell(spec, decl, vars, dir)
            // 校验器在安装时就拒了这两种以外的 type（EXEC_TYPE_UNSUPPORTED）；能走到这里说明
            // 目录是手拷进去的、没过安装校验 —— 所以这是一条真实的失败，不是兜底。
            else -> ToolResult.failure("exec.type=「${decl.exec.type}」v1 不支持（只有 http / shell）")
        }
    }

    private suspend fun http(spec: PluginSpec, decl: ToolDecl, vars: Map<String, String>): ToolResult {
        val exec = decl.exec
        if (exec.url.isNullOrBlank()) {
            return ToolResult.failure("插件「${spec.displayName.zh}」的 ${decl.name} 是 http 但没写 url")
        }
        val missing = mutableListOf<String>()
        // url 里的替换值百分号编码，header / body 原样：编码会把 `Bearer x` 变成 `Bearer+x`。
        val url = render(exec.url, vars, missing, ::urlEncode)
        val headers = exec.headers.entries.associate { (name, value) ->
            render(name, vars, missing) to render(value, vars, missing)
        }
        val body = exec.body?.let { render(it, vars, missing) }
        if (missing.isNotEmpty()) return missingFailure(spec, decl, missing)

        val method = (exec.method ?: "GET").uppercase()
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        // OkHttp 不允许 GET/HEAD 带 body；其余方法必须带（空的也带，很多接口要求 Content-Length）。
        val permitsBody = method in setOf("POST", "PUT", "PATCH", "DELETE")
        if (permitsBody) {
            val declaredType = headers.keys.any { it.equals("Content-Type", ignoreCase = true) }
            val mediaType = if (declaredType) null else JSON_MEDIA_TYPE
            builder.method(method, (body ?: "").toRequestBody(mediaType))
        } else {
            builder.method(method, null)
        }

        val timeoutMs = exec.timeoutSeconds.coerceIn(1, 600) * 1000L
        // 每次调用单独设 callTimeout：`timeout_seconds` 是每个工具自己的（与 MCP 那边同一个办法）。
        val client = HTTP_CLIENT.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(builder.build())
        // 点停即停：阻塞的 execute() 挂在 IO 线程上，只有 cancel() 能把它拽回来（与 LlmClient
        // 的 inFlight 同一个口径）。取消在 OkHttp 里以 IOException 现形，所以下面要把它翻回取消。
        val job = currentCoroutineContext()[Job]
        val cancelHook = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        return withContext(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    val raw = String(readCapped(response.body), Charsets.UTF_8)
                    shape(raw, response.code, response.isSuccessful, exec.response)
                }
            } catch (error: IOException) {
                if (job?.isCancelled == true) throw CancellationException("已停止")
                ToolResult.failure("请求失败：${error.message ?: error::class.java.simpleName}")
            } finally {
                cancelHook?.dispose()
            }
        }
    }

    private suspend fun shell(
        spec: PluginSpec,
        decl: ToolDecl,
        vars: Map<String, String>,
        dir: File,
    ): ToolResult {
        val exec = decl.exec
        if (exec.command.isNullOrBlank()) {
            return ToolResult.failure("插件「${spec.displayName.zh}」的 ${decl.name} 是 shell 但没写 command")
        }
        val missing = mutableListOf<String>()
        // shell 里的替换值一律单引号包住（与 ShellTools 的 quote 同一套）：模型给的字符串不该
        // 变成第二条命令。代价是「一个参数里塞多个 flag」的模板不管用了 —— 那种插件得把固定
        // 部分写在 command 里。
        val command = render(exec.command, vars, missing, ::shellQuote)
        val cwd = exec.cwd?.takeIf { it.isNotBlank() }?.let { render(it, vars, missing, ::shellQuote) }
            ?: shellQuote(dir.absolutePath)
        if (missing.isNotEmpty()) return missingFailure(spec, decl, missing)

        val label = if (exec.needsRoot) "root" else "local(app)"
        // 固定 local 档，只有 needsRoot 才走 root（引擎那边已经按 TIER_ROOT 把过关）。cwd 用
        // `cd` 前缀实现：capability/Shell 的三条通道都不收工作目录参数，不去动它。
        val full = "cd $cwd && $command"
        val result = try {
            withTimeout(exec.timeoutSeconds.coerceIn(1, 600) * 1000L) {
                if (exec.needsRoot) Shell.root(full) else Shell.local(full)
            }
        } catch (timeout: TimeoutCancellationException) {
            // 必须在这里翻成普通失败：TimeoutCancellationException 也是 CancellationException，
            // 抛上去会被引擎当成「用户点了停止」，整轮 run 就没了。
            return ToolResult.failure("[$label] 命令超时（${exec.timeoutSeconds}s）已终止")
        }
        val limit = (exec.response?.maxChars ?: DEFAULT_MAX_CHARS).coerceAtLeast(100)
        val prefix = "[$label] exit=${result.exitCode}\n"
        // render() 自己带截断标记，所以这里只给它一个上限，不自己再截一次。
        val rendered = result.render(limit)
        return if (result.ok) {
            ToolResult.success(prefix + rendered)
        } else {
            ToolResult.failure(prefix + rendered)
        }
    }

    /**
     * 把响应整形成给模型看的文本：非 2xx 直接算失败，`pick` 取子集，最后按 `max_chars` 截断。
     *
     * `pick` 落空**不回退成整份响应** —— 那正是 pick 要防的体积失控，而且「路径不对」和「接口
     * 返回了别的东西」是两种要分开看的失败。引擎另有 8000 字的总上限（`MAX_TOOL_OUTPUT`）。
     */
    internal fun shape(raw: String, code: Int, ok: Boolean, response: ResponseDecl?): ToolResult {
        val limit = (response?.maxChars ?: DEFAULT_MAX_CHARS).coerceAtLeast(100)
        val prefix = "[HTTP $code]\n"
        if (!ok) return ToolResult.failure(prefix + truncate(raw, limit))
        val path = response?.pick
        if (path.isNullOrBlank()) {
            return ToolResult.success(prefix + truncate(if (raw.isBlank()) "(无响应体)" else raw, limit))
        }
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        // 「解析成功」不等于「是 JSON」：kotlinx 会把 `<html>boom</html>` 这种裸文本也读成一个
        // 字面量 JsonPrimitive（实测），于是它会落到下面「路径落空」那一支，报成「响应里没有
        // pick=items.0 这条路径」—— 模型读到的是「接口结构变了」，而真相是接口压根没返回 JSON。
        // 所以容器类型才算可 pick，两种失败必须分开说。
        if (element !is JsonObject && element !is JsonArray) {
            return ToolResult.failure(
                prefix + "响应不是 JSON，没法按 pick=「$path」取值。开头 200 字：${raw.take(200)}",
            )
        }
        val picked = pickPath(element, path)
            ?: return ToolResult.failure(
                prefix + "响应里没有 pick=「$path」这条路径。开头 200 字：${raw.take(200)}",
            )
        return ToolResult.success(prefix + truncate(picked.toString(), limit))
    }

    /**
     * 点路径取子集：`items.0.name`。数组下标就是数字段，没有 `[]` 语法（v1 只做点路径）。
     * 任一段落空返回 null。
     */
    internal fun pickPath(root: JsonElement, path: String): JsonElement? {
        var node: JsonElement? = root
        for (step in path.split('.')) {
            val key = step.trim()
            if (key.isEmpty()) continue
            node = when (val current = node) {
                is JsonObject -> current[key]
                is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
            if (node == null) return null
        }
        return node
    }

    /**
     * 模板求值。[transform] 是「值进模板之前怎么处理」：url 用百分号编码、shell 用单引号、
     * header / body 原样。缺值时**保留字面量**并把键收进 [missing]，由调用方一次性报错 ——
     * 与 [contextsFor] 同一个纪律：宁可不发，也不把 `{{env.TOKEN}}` 这种字面量发出去。
     */
    internal fun render(
        template: String,
        vars: Map<String, String>,
        missing: MutableList<String>,
        transform: (String) -> String = { it },
    ): String = TEMPLATE_VAR.replace(template) { match ->
        val key = match.groupValues[1]
        val value = vars[key]
        if (value == null) {
            if (key !in missing) missing += key
            match.value
        } else {
            transform(value)
        }
    }

    private fun missingFailure(spec: PluginSpec, decl: ToolDecl, missing: List<String>): ToolResult {
        val names = missing.joinToString("、") { "{{$it}}" }
        val envNames = missing.filter { it.startsWith("env.") }.map { it.removePrefix("env.") }
        val hint = if (envNames.isEmpty()) "" else "（去插件页填：${envNames.joinToString("、")}）"
        return ToolResult.failure("插件「${spec.displayName.zh}」的 ${decl.name} 缺少变量 $names$hint")
    }

    private fun truncate(text: String, limit: Int): String =
        if (text.length > limit) {
            text.take(limit) + "\n…（响应共 ${text.length} 字，已截断：只保留前 $limit 字）"
        } else {
            text
        }

    private fun riskRank(risk: String): Int = ManifestValidator.rank(risk)

    /** 模型实参进模板时的字面量：字符串取内容（不带引号），其余用紧凑 JSON。 */
    private fun jsonText(element: JsonElement): String = when (element) {
        is JsonPrimitive -> if (element.isString) element.content else element.toString()
        else -> element.toString()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 单引号包住；唯一不能被单引号容纳的字符闭引号、转义、再开引号。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 最多读进内存多少字节。`body.string()` 会先把整份响应拉进来再谈截断，一个指向大文件的
     * url 就能把应用 OOM 掉 —— 与 WebTools 的 `MAX_FETCH_BYTES` 同一个理由、同一个量级。
     */
    private fun readCapped(body: ResponseBody?): ByteArray {
        if (body == null) return ByteArray(0)
        return body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < MAX_RESPONSE_BYTES) {
                val room = (MAX_RESPONSE_BYTES - out.size()).coerceAtMost(buffer.size)
                val read = input.read(buffer, 0, room)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }
}

/** `{{x}}` / `{{env.NAME}}` —— 与校验器同一套写法。 */
private val TEMPLATE_VAR = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*}}""")

/** hook 的 `when.regex` 编译缓存：同一条 pattern 只编译一次。 */
private val HOOK_REGEX = ConcurrentHashMap<String, Regex>()

private const val HOOK_EVENT = "tools/pre-execute"

private const val DEFAULT_MAX_CHARS = 8000

private const val MAX_RESPONSE_BYTES = 1024 * 1024

/**
 * **必须 `by lazy`**（0.8.100 真机实测的教训）：顶层 val 会进本文件门面类 `PluginRuntimeKt` 的
 * `<clinit>`。触发点在引擎分发处：每条工具调用先过 [preExecute]，它碰顶层 [HOOK_REGEX]；[execute]
 * 里的 [render]/[http] 碰 [TEMPLATE_VAR]/[HTTP_CLIENT] —— 任一处都会触发门面类初始化。
 * `OkHttpClient.Builder().build()` / `toMediaType()` 在这台真机的 ART 上初始化失败 ⇒ 第一次
 * `ExceptionInInitializerError`、之后每次引用都是 `NoClassDefFoundError: …PluginRuntimeKt`，
 * 而 [preExecute] 不在任何 `runCatching` 里 ⇒ **整轮 run 被掀翻**（用户看到「对话自己停了」+ 红字）。
 * lazy 把 okhttp 的初始化推迟到真正发 http 那一刻：那一步在引擎的 `runCatching` 里，失败只是一条
 * 工具失败消息，不会带走整轮。JVM 单测里这段初始化是好的（453/453），所以这条只能靠真机发现。
 */
private val JSON_MEDIA_TYPE by lazy { "application/json; charset=utf-8".toMediaType() }

/**
 * 插件 http 的公共客户端。`followSslRedirects = false` 是故意的：https → http 的跳转会把
 * `Authorization` 一起带过去（OkHttp 只在同源时剥 header），而插件的 url 是别人写的。
 * 同样 `by lazy`，理由见 [JSON_MEDIA_TYPE] 的注释。
 */
private val HTTP_CLIENT by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
}

/** 读管道里的「跳过」也要说出来（§0.4 失败要显式）—— 落到 fx-trace 同级的日志习惯里。 */
private object PluginLog {
    fun skip(name: String, why: String) {
        android.util.Log.w("SuixingPlugins", "跳过 $name：$why")
    }

    fun hook(pluginId: String, message: String) {
        android.util.Log.i("SuixingPlugins", "hook（$pluginId）：$message")
    }
}
