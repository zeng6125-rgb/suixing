package com.suixing.agent.agent

/**
 * Which tool calls may run **at the same time** as the others in one batch.
 *
 * 引擎从 0.8.381 起把同一批工具调用并发执行。但有两类工具没有「可安全并发」的通用语义
 * （Eta 的 agent runtime 也是这么划的，见 issue #44 第四项）：
 *
 *  · **GUI 手势**（`ui_tap` / `ui_swipe` / `ui_long_press` / `ui_type` / `ui_key`…）：
 *    它们改的是同一块屏幕、同一个前台状态。两个手势并发 = 一个按在另一个刚滑走的位置上。
 *  · **会话式 Shell / 特权写操作**（`run_shell` / `app_action`）：同一个 shell 会话里并发提交
 *    两条命令，输出会互相穿插；特权写操作还可能在彼此的过渡状态里读到中间值。
 *
 * 纯读类（`read_file` / `grep` / `device_info` / `ui_dump`…）仍然可以并行 —— 这正是 #36 里
 * 并行的收益来源，不能因为这几类工具就把并行整个关掉。
 *
 * 判据是**名字**而不是运行时能力：能力（root/Shizuku/无障碍）随时可能变，而"这个工具动了什么"
 * 是它的定义决定的，与当下有没有权限无关。
 */
internal fun isParallelizable(toolName: String): Boolean = when (toolName) {
    // 会话式 shell：同一会话里并发提交两条命令，stdout 会互相插队。
    "run_shell" -> false
    // 特权写操作：安装/卸载/强停/权限授予，彼此之间有过渡状态。
    "app_action" -> false
    // 屏幕快照是读，但它读的是"当下的屏幕"，与手势并发时结果取决于时序 —— 允许并行。
    "ui_dump", "ui_screenshot" -> true
    // 其余 GUI 手势一律串行。
    else -> !toolName.startsWith("ui_")
}

/**
 * 把一批工具调用切成**顺序执行的组**：组与组之间是屏障，组内并发。
 *
 * - 连续的并行工具按 [parallel] 打包（保持原有的分块并发）；
 * - 任何一个不可并行的工具**自成一组**，它前后都不与别的工具交叠。
 *
 * 返回的是**下标**，调用方据此从原列表取调用 —— 下标而不是对象，是为了保持「按模型给出的
 * 顺序回写结果」这条硬约束不被重新排序破坏。
 */
internal fun batchGroups(names: List<String>, parallel: Int): List<List<Int>> {
    val groups = mutableListOf<List<Int>>()
    val width = parallel.coerceAtLeast(1)
    var run = mutableListOf<Int>()

    fun flush() {
        if (run.isNotEmpty()) {
            groups += run
            run = mutableListOf()
        }
    }

    for ((index, name) in names.withIndex()) {
        if (isParallelizable(name)) {
            run += index
            if (run.size == width) flush()
        } else {
            flush()
            groups += listOf(index)
        }
    }
    flush()
    return groups
}
