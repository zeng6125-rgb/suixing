package com.lingshu.agent.tools

import com.lingshu.agent.capability.CapabilityHub
import com.lingshu.agent.capability.Shell
import com.lingshu.agent.capability.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Shell access. The same command can run as the app, as the shell user through
 * Shizuku, or as root; [run_shell] picks the strongest tier the user has granted
 * unless a tier is named explicitly.
 */
class ShellTools(private val capabilities: CapabilityHub) : ToolGroup() {

    override val tier: String = TIER_SHELL

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "run_shell",
            "执行一条 shell 命令并返回输出。mode=auto 时会自动使用当前可用的最高权限" +
                "（root > Shizuku(shell) > 本应用），可用 am/pm/input/screencap/dumpsys/cat/ls 等命令。",
            Schema.objectOf(
                properties = listOf(
                    "command" to Schema.string("要执行的命令，如 `ls -l /sdcard/Download`"),
                    "mode" to Schema.string(
                        "权限档位：auto（默认）/ local（本应用）/ shizuku（shell 用户）/ root",
                        enumValues = listOf("auto", "local", "shizuku", "root"),
                        default = "auto",
                    ),
                ),
                required = listOf("command"),
            ),
        ),
        ToolSpec(
            "app_action",
            "对某个应用执行特权操作：启动、强停、清除数据、卸载、禁用、启用、查看信息或权限、安装 APK。" +
                "除 info/permissions 外通常需要 Shizuku 或 root。",
            Schema.objectOf(
                properties = listOf(
                    "package" to Schema.string("目标应用包名"),
                    "action" to Schema.string(
                        "操作类型",
                        enumValues = listOf(
                            "start", "force_stop", "clear_data", "uninstall",
                            "disable", "enable", "info", "permissions", "install",
                        ),
                    ),
                    "apk_path" to Schema.string("action=install 时必填，APK 文件路径"),
                ),
                required = listOf("package", "action"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "run_shell" -> runShell(args)
        "app_action" -> appAction(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun runShell(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args.str("command")?.trim().orEmpty()
        if (command.isEmpty()) return@withContext ToolResult.failure("缺少参数 command")
        val mode = args.str("mode")?.trim()?.lowercase() ?: "auto"
        val state = capabilities.state.value
        if (mode == "root" && !state.rootProbed) capabilities.probeRoot()
        val chosen = when (mode) {
            "local" -> "local"
            "shizuku" -> "shizuku"
            "root" -> "root"
            else -> when {
                capabilities.state.value.rootReady -> "root"
                capabilities.state.value.shellReady -> "shizuku"
                else -> "local"
            }
        }
        val result: ShellResult = when (chosen) {
            "root" -> Shell.root(command)
            "shizuku" -> Shell.shizuku(command)
            else -> Shell.local(command)
        }
        val label = when (chosen) {
            "root" -> "root"
            "shizuku" -> "shizuku(shell)"
            else -> "local(app)"
        }
        val prefix = "[$label] exit=${result.exitCode}\n"
        if (result.ok) {
            ToolResult.success(prefix + result.render())
        } else {
            ToolResult.failure(prefix + result.render())
        }
    }

    private suspend fun appAction(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val pkg = args.str("package")?.trim().orEmpty()
        val action = args.str("action")?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return@withContext ToolResult.failure("缺少参数 package")
        val command = when (action) {
            "start" -> "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
            "force_stop" -> "am force-stop $pkg"
            "clear_data" -> "pm clear $pkg"
            "uninstall" -> "pm uninstall $pkg"
            "disable" -> "pm disable-user --user 0 $pkg"
            "enable" -> "pm enable $pkg"
            "info" -> "dumpsys package $pkg | head -n 80"
            "permissions" -> "dumpsys package $pkg | sed -n '/requested permissions/,/install permissions/p'"
            "install" -> {
                val apk = args.str("apk_path")?.trim().orEmpty()
                if (apk.isEmpty()) return@withContext ToolResult.failure("action=install 需要参数 apk_path")
                "pm install -r $apk"
            }
            else -> return@withContext ToolResult.failure("不支持的操作：$action")
        }
        val state = capabilities.state.value
        if (!state.shellReady && !state.rootReady) {
            return@withContext ToolResult.failure(
                "该操作需要 Shizuku（shell 用户）或 root 权限。请到「能力」页开启 Shizuku 并授权灵枢。",
            )
        }
        val result = if (state.rootReady) Shell.root(command) else Shell.shizuku(command)
        val label = if (state.rootReady) "root" else "shizuku(shell)"
        val prefix = "[$label] exit=${result.exitCode}\n"
        if (result.ok) ToolResult.success(prefix + result.render()) else ToolResult.failure(prefix + result.render())
    }
}
