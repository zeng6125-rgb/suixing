package com.suixing.agent.tools

import kotlinx.serialization.json.JsonObject

/** One-shot app-uid Bash calls share shell execution, not its privileged capability gate. */
class BashTools(private val shellTools: ShellTools) : ToolGroup() {
    override val tier: String = TIER_NONE
    override val group: String = "Shell"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "bash",
            "Run a one-shot command in the bundled GNU Bash as the app uid. Supports Bash syntax, " +
                "arrays and process substitution. No Shizuku/root selection and no fallback to sh. " +
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
