package com.suixing.agent.tools

import com.suixing.agent.voice.Speaker
import kotlinx.serialization.json.JsonObject

/**
 * Lets the model talk out loud. Useful for hands-free use: the phone can read a
 * result to the user while the screen stays in a pocket.
 */
class VoiceTools(private val speaker: Speaker) : ToolGroup() {

    override val group: String = "语音"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "speak",
            description = "Read text aloud through the phone's system speech engine (Chinese is supported).",
            parameters = Schema.objectOf(
                listOf("text" to Schema.string("Text to read aloud; keep it under about 500 characters.")),
                listOf("text"),
            ),
        ),
        ToolSpec(
            name = "stop_speaking",
            description = "Stop the current read-aloud immediately.",
            parameters = Schema.objectOf(emptyList()),
        ),
    )

    override val tier: String = TIER_NONE

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "speak" -> {
            val text = args.str("text")?.trim().orEmpty()
            if (text.isEmpty()) ToolResult.failure("Missing argument: text") else {
                speaker.speak(text)
                ToolResult.success("Started reading aloud.")
            }
        }

        "stop_speaking" -> {
            speaker.stop()
            ToolResult.success("Stopped reading aloud.")
        }

        else -> ToolResult.failure("Unknown voice tool: $name")
    }
}
