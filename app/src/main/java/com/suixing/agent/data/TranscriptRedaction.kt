package com.suixing.agent.data

/**
 * 哪些工具的数据不该留在**持久 transcript** 里（issue #44 第二项）。
 *
 * 原则来自 Eta 的 agent runtime：**模型当轮照常拿到原始值，落盘的那一份替换成占位说明**。
 * 剪贴板里的密码、用户手打的密钥这类东西，进 `chats/store/<id>.jsonl` 之后会跟着会话一直
 * 留在磁盘上，还可能随导出/备份走 —— 那与"这一轮需要它"是两件事。
 *
 * 判定只按**工具定义**（这个工具处理的是什么数据），不看运行时权限：
 *
 *  · `clipboard_read` 的**结果**：剪贴板内容 —— 密码、验证码最常见的中转站。
 *  · `ui_type` / `ui_type_into` / `clipboard_write` 的**参数**：用户要输入到别处去的文本，
 *    同样可能是密码或验证码。
 *
 * **刻意没做的**：`run_shell` 与 `read_file` 的输出不屏蔽。它们能读到的东西无限广，一刀切会把
 * 会话记录掏空（模型回头看自己跑过什么命令、看到了什么，全靠这些）—— 真正该做的是 issue #44
 * 第一项：给设置读写这类操作做结构化工具 + 读回核验，再按工具名精确屏蔽。
 */
internal object TranscriptRedaction {

    /** 落盘时的替换文案：说得清"这里本来有内容、为什么没了"，模型下一轮读到也不会困惑。 */
    internal const val PLACEHOLDER =
        "〔敏感内容：仅本轮提供给模型，未写入会话记录〕"

    /** 参数含敏感内容的工具。 */
    private val sensitiveArguments = setOf("ui_type", "ui_type_into", "clipboard_write")

    /** 结果含敏感内容的工具。 */
    private val sensitiveResults = setOf("clipboard_read")

    /** 落盘前要替换的工具参数（原始 JSON）。当轮发给模型的仍然是原文。 */
    internal fun argumentsForTranscript(toolName: String, arguments: String): String =
        if (toolName in sensitiveArguments) """{"redacted":"$PLACEHOLDER"}""" else arguments

    /** 落盘前要替换的工具输出。当轮发给模型的仍然是原文。 */
    internal fun outputForTranscript(toolName: String, output: String): String =
        if (toolName in sensitiveResults) PLACEHOLDER else output

    /** 这条工具调用落盘时是否需要屏蔽（供调用方决定要不要打点/测试断言）。 */
    internal fun isRedacted(toolName: String): Boolean =
        toolName in sensitiveArguments || toolName in sensitiveResults
}
