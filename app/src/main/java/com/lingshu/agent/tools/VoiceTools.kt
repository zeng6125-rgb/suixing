package com.lingshu.agent.tools

import com.lingshu.agent.voice.Speaker
import kotlinx.serialization.json.JsonObject

/**
 * Lets the model talk out loud. Useful for hands-free use: the phone can read a
 * result to the user while the screen stays in a pocket.
 */
class VoiceTools(private val speaker: Speaker) : ToolGroup() {

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "speak",
            description = "用手机朗读一段文字（走系统语音引擎，支持中文）。",
            parameters = Schema.objectOf(
                listOf("text" to Schema.string("要朗读的内容，建议控制在 500 字以内")),
                listOf("text"),
            ),
        ),
        ToolSpec(
            name = "stop_speaking",
            description = "立即停止正在进行的朗读。",
            parameters = Schema.objectOf(emptyList()),
        ),
    )

    override val tier: String = TIER_NONE

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "speak" -> {
            val text = args.str("text")?.trim().orEmpty()
            if (text.isEmpty()) ToolResult.failure("缺少参数 text") else {
                speaker.speak(text)
                ToolResult.success("已开始朗读。")
            }
        }

        "stop_speaking" -> {
            speaker.stop()
            ToolResult.success("已停止朗读。")
        }

        else -> ToolResult.failure("未知语音工具：$name")
    }
}
