package com.suixing.agent.tools

import kotlinx.serialization.json.JsonObject

/** One-shot Bash calls resolve the user's configured tier through the shared shell path. */
class BashTools(private val shellTools: ShellTools) : ToolGroup() {
    override val tier: String = TIER_NONE
    override val group: String = "Shell"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "bash",
            "Run a one-shot command in the bundled GNU Bash using the privilege tier allowed in Settings " +
                "(root, Shizuku, or app uid). Supports Bash syntax, arrays and process substitution. " +
                "Never falls back to sh or exceeds the configured privilege tier. " +
                "Returns stdout, stderr and exit code; commands have a 45-second limit. " +
                "Use terminal tools for interactive sessions.",
            Schema.objectOf(
                properties = listOf("command" to Schema.string("Bash command to execute"),
                    "observe_paths" to Schema.arrayOf("Optional absolute files/directories to observe outside the app workspace; bounded filesystem verification, no inference from stdout.", Schema.string("Absolute path"))),
                required = listOf("command"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "bash" -> shellTools.runBash(args)
        else -> ToolResult.failure("未知工具：$name")
    }
}
