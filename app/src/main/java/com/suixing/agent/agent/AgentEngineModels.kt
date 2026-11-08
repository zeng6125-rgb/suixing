package com.suixing.agent.agent

import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.tools.ToolResult

/**
 * One line the user slipped in, waiting for the model's next turn.
 *
 * File level rather than a member of the run handle: the parser refuses a class declaration nested
 * inside an `inner class` (`'Class' is prohibited here`), and both this and [WireSpan] are only
 * names for the run's own bookkeeping.
 */
internal data class Steer(
    val id: String,
    val text: String,
    val imagesBase64: List<String> = emptyList(),
    val directTool: ToolCallRequest? = null,
)

internal data class QueuedDirectTool(val call: ToolCallRequest, val steer: Steer? = null)

/**
 * Which stored message a stretch of the wire came from: [from] is where it starts and [to] is one
 * past its last entry.
 *
 * The wire carries no ids of its own, and a checkpoint has to be able to name the messages it
 * folded away — a compression that cannot say what it covered can never be resumed from.
 */
internal data class WireSpan(val id: String, val from: Int, val to: Int)

/**
 * 一条工具调用的执行结果，与「把它写回 wire」分离（[executeOneTool] 只产这个，[runLoop] 里的
 * commitTool 才碰 wire/spans/produced/openCalls）。
 *
 * 分离是并行的前提：执行阶段（可能很慢、可并发）不碰任何 run 级可变状态；回写阶段在主协程里
 * 按模型请求的顺序逐条做，端点对 tool 消息顺序敏感，回写绝不能乱序。
 */
internal data class ToolOutcome(
    val call: ToolCallRequest,
    val result: ToolResult,
    /** 这一条实际跑了多久（毫秒）；被拒/未知工具为 0。 */
    val toolMs: Long,
    /** 给审批与事件用的参数预览（截断过的）。 */
    val argsPreview: String,
)
