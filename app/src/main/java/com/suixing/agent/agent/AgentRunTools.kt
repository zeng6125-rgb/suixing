package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.plugins.ManifestValidator
import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.plugins.HookDecl
import com.suixing.agent.plugins.HookVerdict
import com.suixing.agent.plugins.PluginSpec
import com.suixing.agent.core.Core
import com.suixing.agent.service.TouchFx
import com.suixing.agent.tools.GOAL_TOOL_NAMES
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.FileCancellationSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import com.suixing.agent.agent.AgentRun.Companion.SHARED_TOOL_EXECUTION

/** Execute in request order; only the caller's commit function mutates its wire/transcript. */
internal suspend fun AgentRun.executeToolRequests(
    calls: List<ToolCallRequest>,
    settings: AppSettings,
    registry: ToolRegistry,
    table: ToolTable,
    capabilities: CapabilityHub,
    gate: ApprovalGate,
    onEvent: (AgentEvent) -> Unit,
    commit: suspend (ToolOutcome) -> Unit,
): Boolean {
    // 并行度（对话配置「工具并行数」）：1＝串行，就是今天的行为，语义一字不改；
    // N>1 时一轮内最多 N 条工具**同时**执行，但结果仍按模型请求的顺序回写 wire ——
    // 端点对 tool 消息的顺序敏感，乱序会让下一轮读到错配的结果。
    // **只在 AUTO 档生效**：ASK 档的授权弹窗每个会话只有一个待决位（ApprovalGate 的
    // waiters 按 chatId 单槽），两条调用同时求授权会互相覆盖、死等；ASK 强制串行。
    val parallel = if (settings.approvalMode == ApprovalMode.AUTO &&
        calls.none { it.name in GOAL_TOOL_NAMES }
    ) {
        settings.toolParallel.coerceAtLeast(1)
    } else {
        1
    }
    if (parallel <= 1) {
        for (call in calls) {
            // Checked before every call, not after the batch: a stop means the
            // tools the model asked for in the same breath do not run either.
            if (cancelled.get()) {
                return false
            }
            val outcome = if (call.name == "goal_update" && calls.size > 1) {
                ToolOutcome(call, ToolResult.failure("Call goal_update by itself, after the other tools have returned."),
                    0, call.arguments.take(600))
            } else {
                executeOneTool(
                    call, registry, table.plugins, table.hooks, capabilities, gate, settings, chatId, onEvent,
                ) ?: run {
                    return false
                }
            }
            commit(outcome)
        }
    } else {
        // 分块并发：一块最多 parallel 条同时跑（async 全部起飞），块内按原顺序 await 并
        // 回写，再放下一块。块与块之间是一道屏障 —— 换来的是「顺序回写」这条硬约束的
        // 简单实现，且取消检查落在每块开头与每条执行的开头。coroutineScope 保证块里的
        // async 都收干净才往下走（含异常路径）。
        // **不可并行的工具自成一组**（GUI 手势 / 会话式 shell / 特权写操作）：
        // 它们改的是同一块屏幕或同一个 shell 会话，并发提交会互相踩。纯读类照旧
        // 并行 —— 分块宽度不变，只是把这些调用前后切开了（见 ToolBatching.kt）。
        val groups = batchGroups(calls.map { it.name }, parallel)
        for (chunk in groups.map { g -> g.map { calls[it] } }) {
            if (cancelled.get()) {
                return false
            }
            val outcomes = coroutineScope {
                val deferreds = chunk.map { call ->
                    // **指定 IO**（#36 C）：`async` 不带 dispatcher 会继承 runLoop 的
                    // Main —— 所谓「并行」实际是「在 Main 上交错排队」。gate（ConcurrentHashMap
                    // + StateFlow）与 TouchFx（内部 Handler post Main）都线程安全，可整体下放。
                    async(Dispatchers.IO) {
                        executeOneTool(
                            call, registry, table.plugins, table.hooks, capabilities, gate, settings, chatId, onEvent,
                        )
                    }
                }
                deferreds.map { it.await() }
            }
            for (outcome in outcomes) {
                if (outcome == null) {
                    // 这条在执行开头看到取消：收尾，同块其余的 await 已返回或已取消。
                    return false
                }
                commit(outcome)
            }
        }
    }
    return true
}

/**
 * 跑**一条**工具调用，只产出 [ToolOutcome]，不碰 run 级的可变状态（wire/spans/produced/
 * openCalls 都在 [runLoop] 的局部里，回写由那边的 commitTool 做）—— 这是并行的前提：
 * 执行阶段（可能很慢）可以多条同时飞，回写阶段在主协程里按模型请求的顺序逐条做。
 *
 * 返回 null ＝ 这一条在开跑前看到取消（[cancelled]），调用方据此收尾。
 */
internal suspend fun AgentRun.executeOneTool(
    call: ToolCallRequest,
    registry: ToolRegistry,
    pluginSpecs: List<PluginSpec>,
    hooks: List<Pair<PluginSpec, HookDecl>>,
    capabilities: CapabilityHub,
    gate: ApprovalGate,
    settings: AppSettings,
    chatId: String,
    onEvent: (AgentEvent) -> Unit,
    userInvoked: Boolean = false,
): ToolOutcome? {
    // Checked before the call, not after the batch: a stop means the tools the model
    // asked for in the same breath do not run either.
    if (cancelled.get()) return null
    val argsPreview = call.arguments.take(if (call.name == "terminal_write") 64 * 1024 else 600)
    val args = try {
        parseToolArguments(call.arguments)
    } catch (failure: IllegalArgumentException) {
        return ToolOutcome(call, ToolResult.failure("工具参数不是合法 JSON 对象，请重新生成调用"), 0, argsPreview)
    }
    childConfig?.denialReason(call.name, args)?.let { reason ->
        return ToolOutcome(call, ToolResult.failure(reason), 0, argsPreview)
    }
    if (registry.isHidden(call.name)) return ToolOutcome(call,
        ToolResult.failure("当前人设已禁用此工具"), 0, argsPreview)
    val startsGoal = call.name == "goal_resume" ||
        (call.name == "goal" && (args["text"] as? JsonPrimitive)?.content?.isNotBlank() == true)
    if (userInvoked && startsGoal && registry.find("goal_update") == null) {
        return ToolOutcome(call, ToolResult.failure("当前助手禁用了目标进度工具，无法启动目标"), 0, argsPreview)
    }
    if (call.name in SUBAGENT_TOOL_NAMES) {
        val pool = subagents
        if (pool == null || registry.isHidden(call.name)) return ToolOutcome(call,
            ToolResult.failure("当前运行未启用此子任务工具"), 0, argsPreview)
        onEvent(AgentEvent.ToolStarted(call.name, argsPreview))
        val started = System.currentTimeMillis()
        val result = pool.execute(call.name, args)
        return ToolOutcome(call, result, System.currentTimeMillis() - started, argsPreview)
    }
    // 「压缩上下文」是引擎自己的虚拟工具（用户：「要有压缩上下文的指令工具」）：不进 registry
    // （没有要跑的实现、没有能力档），在分发前拦截 —— 置 [compressNow]，本轮边界上由
    // [compressContext] 消费。它不碰手机也不发网络请求，所以不发 ToolStarted、不进 TouchFx
    // 埋点（边缘光的语义是「在操作手机」，压缩不是）。
    if (call.name == COMPRESS_CONTEXT_TOOL) {
        compressNow = true
        return ToolOutcome(
            call = call,
            result = ToolResult.success(
                "已请求压缩上下文：本轮结束后，最旧的一段对话会被折成摘要，之后的请求只带摘要与近期消息。",
            ),
            toolMs = 0,
            argsPreview = argsPreview,
        )
    }
    val tool = registry.find(call.name)
    // 接线点 B（HOST-DEV §5）：内置找不到这个名字才落到插件（`<id>_<tool>`）——
    // 与工具表拼装（接线点 A）同一个顺序，内置优先，插件顶不掉 `run_shell` 这类。
    // resolve 也兜底：与 risk/preExecute 同一条理由 —— 插件链路的类静态初始化抛 Error 时，
    // 不该把整轮 run 打死（FEEDBACK v0.8.112 P0#1 真机出现过 ExceptionInInitializerError）。
    val pluginTool = if (tool == null) {
        runCatching { PluginRuntime.resolve(pluginSpecs, call.name) }
            .getOrElse { error ->
                PluginRuntime.logPluginError("插件工具解析失败（已兜底跳过）", error)
                null
            }
    } else {
        null
    }
    // 谁真的跑这一条。null ＝ 两边都没有这个名字（模型编了个工具名）。
    val runner: (suspend (JsonObject) -> ToolResult)? = when {
        tool != null && goalTools != null && goalRun != null && call.name in GOAL_TOOL_NAMES -> { args ->
            goalTools.executeForRun(call.name, args, chatId, goalRun.id, userInvoked, goalRun::activate)
        }
        tool != null -> { args -> tool.run(args) }
        pluginTool != null -> { args ->
            PluginRuntime.execute(pluginTool.first, pluginTool.second, args)
        }
        else -> null
    }
    // 能力档：插件工具的档由 exec 决定 —— `needs_root` 才要 root，其余固定 local
    // （＝本应用身份，不需要额外能力），所以只有 root 那一档会被 blockedReason 拦住。
    val tier = when {
        pluginTool != null ->
            if (pluginTool.second.exec.needsRoot) TIER_ROOT else TIER_NONE
        tool != null -> tool.tier
        else -> null
    }
    // 风险档只有插件工具有（内置工具只有能力档），所以 min_risk 条件对内置一律
    // 不成立；要拦内置工具用 `when.tool` + `when.regex`。
    // **容错兜底**（FEEDBACK v0.8.112 P0#1 的机制面）：risk 推断与 hook 裁决在 runCatching
    // **外面** —— 插件链路上任何类的静态初始化抛 Error（真机出现过
    // ExceptionInInitializerError）都会穿透 executeOneTool、把整轮 run 打死成「这一轮最终
    // 失败」。插件是第三方代码，它的坏不该是整轮的坏：这里兜成一次工具失败，run 照常继续。
    val risk = runCatching { pluginTool?.let { ManifestValidator.riskOf(it.second) } }
        .getOrElse { error ->
            // 兜底吞掉的同时留证：clinit 类 Error 的真因在 cause 链里，不记下来下次复现
            // 还是「没有更多信息」。PluginLog 落插件自己的日志文件。
            PluginRuntime.logPluginError("risk 推断失败（已兜底跳过）", error)
            null
        }
    // **hook 裁决要离开调用方线程**（issue #56 R19）：`preExecute` 对每个启用的 hook 拿
    // 正则去扫**完整参数**（修 5.1 后从 600 字预览改成了全文，`write_file` 的 content
    // 可达 4MB），是 O(参数长度 × hook 数) 的 CPU 活儿；而 `executeOneTool` 只把工具本体
    // 切到 IO，这里仍跑在调用方（Main）线程上。
    val verdict = runCatching {
        withContext(Dispatchers.IO) {
            PluginRuntime.preExecute(hooks, call.name, call.arguments, risk)
        }
    }
        .getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            PluginRuntime.logPluginError("hook 裁决失败（拒绝本次调用）", error)
            HookVerdict(deny = true, message = "插件规则检查失败，本次调用未执行。")
        }
    var toolMs = 0L
    val result = if (runner == null) {
        ToolResult.failure("未知工具：${call.name}")
    } else if (verdict.deny) {
        // 与「能力不足」同一类：跑都没跑，所以不发 ToolStarted。
        ToolResult.failure(verdict.message ?: "插件 hook 拒绝了这次调用。")
    } else {
        val blocked = tier?.let { blockedReason(it, capabilities) }
        if (blocked != null) {
            ToolResult.failure(blocked)
        } else {
            liveLine = "🔧 ${call.name}…"
            onEvent(AgentEvent.ToolStarted(call.name, argsPreview))
            // 边缘光的**单一埋点**（v3 会话制）：所有真正开跑的工具调用（内置 + 插件）都过这里，
            // TouchFx 据此判定「UI 操作＝开会话 / 非 UI 工具＝显式结束会话」。以前埋点散在
            // ShellTools/UiTools 两处，read_file / web_search / 插件工具都不经过 ⇒ 覆盖洞会让
            // 「不控制就不灭」失效。参数原文（未掩码）给 TouchFx 判 run_shell 里是否真碰 UI。
            runCatching { TouchFx.noteToolActivity(Core.appContext, call.name, call.arguments) }
            // AUTO 档**不弹任何授权**（用户多次明确要求）：插件的 require_confirm
            // 不再把 AUTO 升级成「问一次」——它降级为日志（preExecute 里已记）。
            // deny 仍拦（不弹窗，只让那次调用失败并说明原因）；ASK/DENY 档与
            // 「总是允许」照旧。要守卫插件真拦人，把审批档设成「每次询问」。
            val mode = settings.approvalMode
            val approvalArgs = if (call.name == "terminal_write") call.arguments else argsPreview
            if (!gate.request(chatId, call.name, approvalArgs, mode, approvalSourceTitle,
                    rememberDecisions = childConfig == null)) {
                ToolResult.failure("用户拒绝了这次调用。")
            } else {
                val startedAt = System.currentTimeMillis()
                // **工具本体跑在 IO 上**（#36 的第一小步）：`startRun` 的协程是
                // `withContext(runJob)`，而 `runJob` 是 `Job` **不是调度器** ⇒ 整段 run loop
                // 继承调用方（`viewModelScope` = Main.immediate）。工具里既有 `ProcessBuilder`
                // 这类阻塞调用，也有遍历目录/解析大文本这类纯 CPU ⇒ 都落在主线程上，
                // 正是「A 会话在跑工具、切到 B 滑动卡」的直接来源。
                //
                // **只把工具本体移走**，不动事件顺序：`withContext` 返回后仍回到调用方
                // （Main）继续发事件、写回写，所以 `ToolStarted` → `ToolFinished` 的次序、
                // 以及 `onEvent` 的线程假设全部不变。
                runCatching {
                    withContext(Dispatchers.IO + terminalCaller + FileCancellationSink { changes ->
                        val interrupted = changes.attach(ToolResult.failure("工具已中断；已验证的文件变更仍予保留"))
                        interruptedFileTools[call.id] = ToolOutcome(call, interrupted, System.currentTimeMillis() - startedAt, argsPreview)
                    }) {
                        SHARED_TOOL_EXECUTION.run(call.name) { runner(args) }
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    ToolResult.failure("工具执行异常：${error.message ?: error::class.java.simpleName}")
                }.also { toolMs = System.currentTimeMillis() - startedAt }
            }
        }
    }
    return ToolOutcome(call = call, result = result, toolMs = toolMs, argsPreview = argsPreview)
}
