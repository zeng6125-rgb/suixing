package com.suixing.agent.agent

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.ToolCallRequest

/**
 * wire 的文字侧：把存储消息写回请求、给它们估字符/摘要、以及重试与中断那行过程记录。
 *
 * 这些函数只吃参数、不读引擎或某个 run 的可变状态，所以从 [AgentEngine.RunHandle] 里搬出来。
 */

    internal fun renderForSummary(message: ChatMessage): String = buildString {
        append(message.role).append(": ")
        append(message.text)
        message.toolCalls.forEach { call ->
            append("\n  [调用工具 ").append(call.name).append(' ').append(call.arguments).append(']')
        }
        if (message.imageCount > 0) append("\n  （含截图，已省略）")
    }

    internal fun wireChars(wire: List<ChatMessage>): Int = wire.sumOf { messageChars(it) }

    /**
     * What one message costs in characters: its text and the arguments of any tool call it asks
     * for. Both travel to the endpoint, and leaving the arguments out would undercount the part
     * of a history that is made almost entirely of tool calls.
     *
     * A screenshot is deliberately *not* counted here, even though its base64 also travels: it is
     * priced per image by [estimatePromptTokens] instead. Counting it as characters made one
     * screenshot — hundreds of thousands of base64 characters, roughly a thousand real tokens —
     * look like a quarter of a million tokens of context.
     */
    internal fun messageChars(message: ChatMessage): Int =
        promptMessageChars(message)

    /**
     * Writes one stored message onto the wire.
     *
     * This is the *rebuild* path, and it has to produce exactly what the live path produced for the
     * same message: a rebuilt prompt that differs by so much as a wrapper sentence is not the request
     * the model answered, and the prompt cache keys on that prefix. **Any text the live path wraps
     * around a message — the steer marker, the screenshot notes — must be mirrored here**, and any
     * new one added live must be added here in the same commit.
     */
    internal fun appendToWire(
        wire: MutableList<ChatMessage>,
        message: StoredMessage,
        settings: AppSettings,
    ) {
        // Images only go out when the configured model can read them; a text-only
        // endpoint rejects the whole request otherwise.
        val imageList = message.allImages.takeIf { settings.imageInput }.orEmpty()
        val note = if (message.imageCount > 0 && imageList.isEmpty()) {
            "\n（图片已省略：当前未开启「模型支持图片输入」）"
        } else {
            ""
        }
        when (message.role) {
            // A steer reads as an interruption live, so it reads as one here too — the wrapper is
            // what tells the model this was said mid-task rather than asked as a new question.
            Roles.USER ->
                if (message.steer) {
                    // Replayed from a stored steer: same wrapper, and now the same picture and the
                    // same omitted-image note as the live path. Leaving the image out here is what
                    // would make a steer with a photo work in the run it was sent in and silently
                    // lose it in every run that rebuilds this history afterwards.
                    wire += ChatMessage.user("（用户在任务进行中说：${message.text}）$note", imageList)
                } else {
                    wire += ChatMessage.user(message.text + note, imageList)
                }
            Roles.SYSTEM -> wire += ChatMessage.system(message.text)
            Roles.ASSISTANT -> wire += ChatMessage.assistant(
                message.text,
                message.toolCalls.map { ToolCallRequest(it.id, it.name, it.arguments) },
            )
            Roles.TOOL -> {
                wire += ChatMessage.tool(
                    message.toolCallId ?: message.toolName.orEmpty(),
                    message.toolOutput.orEmpty(),
                )
                // The same two sentences the live path writes, chosen by the same condition: with
                // image input on the picture itself goes out, and with it off the model is told a
                // picture existed and that it cannot see it.
                val tool = message.toolName.orEmpty()
                if (imageList.isNotEmpty()) {
                    wire += ChatMessage.screenshot("（这是上一步 $tool 返回的截图）", imageList)
                } else if (message.imageCount > 0) {
                    wire += ChatMessage.user(
                        "（上一步 $tool 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）",
                    )
                }
            }
        }
    }

    /**
     * 这一轮**成功**、但中途重试过时，留在消息上的那条过程记录。
     *
     * 与 [LoopExitPolicy] 的分工：终态策略保留失败回执的「已重试 2 次」，说明为什么这轮这么久，
     * 这条是**成功**的过程块（用户：「重试成功要在过程态中留痕……整轮成功就不要有错误提示卡片」）。
     * 没有重试就返回 null —— 不留空块。
     */
    internal fun retryTraceNote(retries: Int, reason: String): String? = when {
        retries <= 0 -> null
        reason.isBlank() -> "自动重试 $retries 次后成功"
        else -> "自动重试 $retries 次 · $reason"
    }

    /**
     * 一轮里可能同时发生两件事：先重试，最后又被上游切断。块只有一行，两件事都得说，
     * 所以按发生顺序用「；」连起来；都没有就是 null（不留空块）。
     */
    internal fun traceNoteOf(retries: Int, reason: String, cut: String?): String? =
        listOfNotNull(retryTraceNote(retries, reason), cut)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("；")
