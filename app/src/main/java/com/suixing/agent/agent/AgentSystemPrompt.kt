package com.suixing.agent.agent

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.tools.ToolRegistry

/**
 * 「压缩上下文」虚拟工具的名字（用户：「要有压缩上下文的指令工具」）。
 *
 * 它不在 [com.suixing.agent.tools.ToolRegistry] 里：没有要跑的实现、没有能力档，只在
 * [AgentRun.executeOneTool] 分发前被拦截（置 compressNow，本轮边界消费）。名字进
 * 请求的工具表（接线点 A 追加 [COMPRESS_CONTEXT_SPEC]），模型才调得到它。
 */
const val COMPRESS_CONTEXT_TOOL = "compress_context"

/**
 * 第 0 段：内置底座，**一句话 + 工作规则**，永远排在用户人设之前。
 *
 * 顺序：先说自己是谁（一句话），再说这个产品对模型的硬要求（工作规则）。
 * 为什么规则并进这一段而不是单列一段：它们是同一件事 —— 产品默认的、不随人设变的那部分；
 * 分开写只会在两段之间多一个空段落，读起来像两条互不相干的声明。
 *
 * 英文且固定：与人设用什么语言写无关；用户自己的文字（人设、记忆、画像）仍按原语言出现。
 * 规则内容是原「Tool calls / Planning / Writing files / Context」四段合并，再加目标一项 ——
 * 怎么调工具、怎么规划、怎么写大文件、什么时候压缩上下文、目标与步骤如何分工。
 */
private const val SYSTEM_PROMPT_BASE = "You are a helpful software engineer assistant.\n\n" +
    "## Working rules\n" +
    "- Tool calls: arguments must be a valid JSON object, and several tools may be called in parallel. " +
    "When a tool fails, read the error and adjust — do not repeat the same call. " +
    "When the work is done, answer the user in Chinese, briefly saying what you did and what happened.\n" +
    "- Planning: for multi-step work, call todo_write first and rewrite the complete list after each step " +
    "(rewrite, not append) — your context may be compacted, the board will not be.\n" +
    "- Goals: the user starts or resumes goal mode. Report progress, verified completion or a blocker with goal_update.\n" +
    "- Writing files: use write_file. For content over roughly 200 lines or 8KB, write it in parts — " +
    "the head first, then append with `append: true`; do not emit a whole large file in one call " +
    "(that hits the model's own output limit and the round gets truncated).\n" +
    "- Context: when it gets long (a long run, or the user says responses are slow or the context is " +
    "too long), call " + COMPRESS_CONTEXT_TOOL + " — the oldest span is folded into a summary and later " +
    "requests carry that summary plus the recent messages. The current round continues normally."

/** 虚拟工具的规格：无参数。schema 用 OpenAI 的 object 形状，与 [com.suixing.agent.tools.Schema] 同款。 */
internal val COMPRESS_CONTEXT_SPEC = com.suixing.agent.tools.ToolSpec(
    name = COMPRESS_CONTEXT_TOOL,
    description = "Compact the context: fold the oldest part of this conversation into a summary, so " +
        "later requests carry the summary plus the recent messages. Call it when a long run has been " +
        "going for a while, or when the user says responses are slow or the context is too long. " +
        "The current round continues normally afterwards.",
    parameters = com.suixing.agent.tools.Schema.objectOf(emptyList()),
)

internal fun buildSystemPrompt(
    settings: AppSettings,
    registry: ToolRegistry,
    skillCatalog: String,
    memory: Map<String, MemoryEntry>,
    profile: String,
    goal: String,
    goalMode: Boolean = false,
): String = buildString {
    // 顺序是固定的，**按当前次序编号**（旧文档里的「1 人设」是移动前的号，别再按旧号找）：
    //   0 底座（一句话 + 工作规则）→ 1 工具表 → 2 插件 → 3 禁工具 → 4 技能
    //   → 5 人设 → 6 画像 → 7 记忆
    // 0-4 是产品对模型的硬要求，排在**人设之前**：不该被换一个人设就丢掉，也不该随人设的
    // 语言变；5-7 是「用户自己的文字」，保持原语言。
    append(SYSTEM_PROMPT_BASE)
    append("\n\n## Available tools\n")
    append(registry.promptSummary())
    // The user's own objective, not a generated task. It rides just after the tool table
    // so a long run cannot lose sight of it when history is compacted.
    if (goal.isNotBlank()) {
        append("\n\n## Current goal\n")
        append(goal)
        if (goalMode) {
            append("\nThis is an active objective. Act on it instead of only describing a plan. ")
            append("Use todo_write for multi-step work and goal_update for concrete progress. ")
            append("If the objective is ambiguous, ask what to verify and mark it blocked; do not infer a task from unrelated history. ")
            append("Verify the outcome before marking completed. If user input or unavailable access blocks further work, ")
            append("mark blocked and explain what is needed. Call goal_update by itself, after other tools return.")
        }
    }
    if (settings.subagentsEnabled && settings.approvalMode != ApprovalMode.DENY && !registry.isHidden("spawn_agent")) append(
        "\n\nFor substantial independent tasks, you may use spawn_agent to work in parallel. " +
            "Keep simple work local. Pass only relevant context: children do not inherit this conversation. " +
            "Use wait_agents to collect results, verify them, and cancel unneeded children. " +
            "Completed child results may also arrive automatically as wait_agents responses between rounds. " +
            "Read them as evidence, verify and integrate them into your final reply. " +
            "Normal completion waits for accepted children and resumes you with unread results; stopping the parent cancels them. " +
            "Use wait_agents before finishing while required results are outstanding.")
    // 接线点 A（HOST-DEV §5）：让模型知道插件工具也能调，命名与内置一致。
    // 这句是**固定文案**：插件列表是实时变的，而 systemPrompt() 不是 suspend（读盘要 suspend），
    // 所以不在这里读插件；没有启用插件时多说一句也无害（模型看不到那个名字就不会去调）。
    // 英文是刻意的：固定文案的语言不随插件作者的语言变（插件描述本身是什么语言就照原样出现）。
    append(
        "\n\nThe user has enabled plugin-provided tools (named `id.tool`); " +
            "call them the same way as built-in tools.",
    )
    // 禁止档下每一次调用都必然失败（[request] 直接返回 false，模型只会拿到「用户拒绝了
    // 这次调用。」），而上面那张工具表照旧列着。不在这里说明，模型就会一轮轮撞墙、把整轮
    // 时间花在被拒的调用上，而不是直接回答用户。
    if (settings.approvalMode == ApprovalMode.DENY) {
        append(
            "\n\nNote: the user has disabled tool calls. You cannot run any tool this round. " +
                "Answer with text only, and do not attempt to call a tool.",
        )
    }
    if (skillCatalog.isNotBlank()) {
        append("\n\n")
        append(skillCatalog)
    }
    // 人设。空则整段不出现（只剩底座），不留一个空行。
    val persona = settings.systemPrompt.trim()
    if (persona.isNotEmpty()) {
        append("\n\n")
        append(persona)
    }
    // 画像在记忆**之前**（用户：「画像 在记忆前面吧」）：先交代"这是谁"，再说"我记得什么"。
    // 两段都是模型该知道的事，但一段是用户写的、一段是模型自己写的，分开才不会让模型把自己的
    // 笔记当成用户的自述。空画像整段不出现，不留一个空标题。
    // **两个开关在「对话配置」里**（[AppSettings.injectProfile] / [AppSettings.injectMemory]）：
    // 关掉＝整段不出现，**连小标题一起**不留 —— 留一个空标题会让模型以为"这里本来该有内容"。
    append(injectedUserContext(profile, memory, settings.injectProfile, settings.injectMemory))
}
