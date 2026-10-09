package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.Shell
import com.suixing.agent.capability.ShellResult
import com.suixing.agent.service.TouchFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `input tap X Y` and friends, when the agent drives another app through the shell
 * instead of through [UiTools]. Those taps land on the screen with no other sign that
 * the agent did them, so the same rings the UI tools draw are drawn here too.
 *
 * File-scope and built once: these run on every shell command, and a fresh Regex per
 * call is what took the app down once before.
 */
private const val FX_INPUT = """\binput(?:\s+-\S+\s+\S+)*\s+"""

private val FX_TAP = Regex(FX_INPUT + """tap\s+(\d+)\s+(\d+)""")
private val FX_LONG_PRESS = Regex(FX_INPUT + """(?:longpress|draganddrop)\s+(\d+)\s+(\d+)""")

/**
 * `input swipe X Y X Y 800` is a long press spelled out — it is what [UiTools] sends for one —
 * and it has to be recognised before the plain swipe, which would otherwise draw a drag of zero
 * length instead of a finger held down.
 */
private val FX_SWIPE_HOLD = Regex(FX_INPUT + """swipe\s+(\d+)\s+(\d+)\s+\1\s+\2(?:\s|$)""")
private val FX_SWIPE = Regex(FX_INPUT + """swipe\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)""")

/**
 * Commands that hand the screen to another app: `am start`, `am start-activity`, `monkey`.
 * `am startservice` is not one of them — the `\b` after `start` keeps it out.
 */
private val FX_HANDOFF = Regex("""\b(?:am\s+start(?:-activity)?|monkey)\b""")

/**
 * The same handoff, spelled as the two key events that send the user elsewhere. Every other
 * key `input keyevent` can send stays unlit: it neither moves the user to another app nor
 * has a point on screen to draw at. The trailing `\b` keeps `KEYCODE_HOMESCREEN` out.
 */
private val FX_HANDOFF_KEY = Regex(FX_INPUT + """keyevent\s+(?:3|187|KEYCODE_HOME|KEYCODE_APP_SWITCH)\b""")

/**
 * `input text` types into whatever has focus, and `input keyevent` presses a key. Neither has
 * coordinates, so neither draws anything — but both change what is on screen, so both light the
 * breathing edge. `keyevent` is matched with any argument, options included (`--longpress 3`).
 */
private val FX_TEXT = Regex(FX_INPUT + """text\s+\S""")
private val FX_KEYEVENT = Regex(FX_INPUT + """keyevent\s+\S""")

/**
 * The one argument that goes into a command which may run as root. A package name has a fixed
 * shape, so anything else — spaces, `;`, `$` — is refused instead of escaped.
 */
private val PACKAGE_NAME = Regex("""^[A-Za-z0-9._]+$""")

/**
 * Shell access. The same command can run as the app, as the shell user through
 * Shizuku, or as root; [run_shell] picks the strongest tier the user has granted
 * unless a tier is named explicitly.
 */
class ShellTools(
    private val context: Context,
    private val capabilities: CapabilityHub,
) : ToolGroup() {

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
        drawInputFx(command, tool = "run_shell")
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

    /**
     * Effects for what a shell command is about to do, if it does anything visible.
     *
     * Drawn before the command runs rather than after: a ring is supposed to land with the
     * tap, and the command itself blocks until the gesture is over. A key event has no
     * coordinates and draws nothing.
     *
     * Every command is traced with what it matched, so a user report of "nothing appeared" can be
     * answered from the file: no match at all, or a match that drew nothing.
     */
    private fun drawInputFx(command: String, tool: String) {
        val matched = mutableListOf<String>()
        FX_TAP.find(command)?.let {
            matched += "tap"
            TouchFx.tap(context, it.groupValues[1].toFloat(), it.groupValues[2].toFloat())
        }
        FX_LONG_PRESS.find(command)?.let {
            matched += "longpress"
            TouchFx.longPress(context, it.groupValues[1].toFloat(), it.groupValues[2].toFloat())
        }
        val hold = FX_SWIPE_HOLD.find(command)
        if (hold != null) {
            matched += "swipe-hold"
            TouchFx.longPress(context, hold.groupValues[1].toFloat(), hold.groupValues[2].toFloat())
        } else {
            FX_SWIPE.find(command)?.let {
                matched += "swipe"
                TouchFx.swipe(
                    context,
                    it.groupValues[1].toFloat(), it.groupValues[2].toFloat(),
                    it.groupValues[3].toFloat(), it.groupValues[4].toFloat(),
                )
            }
        }
        // No ring for a handoff: the agent is not touching the screen, it is handing it over.
        val handoff = FX_HANDOFF.containsMatchIn(command) || FX_HANDOFF_KEY.containsMatchIn(command)
        if (handoff) {
            matched += "handoff"
            TouchFx.handoff(context)
        }
        // Typing and key presses are driving too, but there is nothing to draw at: the light is
        // the whole signal. The handoff branch above has already lit it for the keys that flash.
        if (!handoff && (FX_TEXT.containsMatchIn(command) || FX_KEYEVENT.containsMatchIn(command))) {
            matched += "text-or-key"
            TouchFx.noteDriving(context)
        }
        TouchFx.traceCall(context, tool = tool, detail = command, matched = matched)
    }

    private suspend fun appAction(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {        val pkg = args.str("package")?.trim().orEmpty()
        val action = args.str("action")?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return@withContext ToolResult.failure("缺少参数 package")
        if (!PACKAGE_NAME.matches(pkg)) {
            return@withContext ToolResult.failure("包名不合法：$pkg（只允许字母、数字、点和下划线）")
        }
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
                "pm install -r ${quote(apk)}"
            }
            else -> return@withContext ToolResult.failure("不支持的操作：$action")
        }
        val state = capabilities.state.value
        if (!state.shellReady && !state.rootReady) {
            return@withContext ToolResult.failure(
                "该操作需要 Shizuku（shell 用户）或 root 权限。请到「能力」页开启 Shizuku 并授权随行。",
            )
        }
        // `action=start` runs `monkey`, which launches an app: the same handoff `open_app` draws.
        // After the privilege check, so nothing flashes for a command that never runs.
        drawInputFx(command, tool = "app_action")
        val result = if (state.rootReady) Shell.root(command) else Shell.shizuku(command)
        val label = if (state.rootReady) "root" else "shizuku(shell)"
        val prefix = "[$label] exit=${result.exitCode}\n"
        if (result.ok) ToolResult.success(prefix + result.render()) else ToolResult.failure(prefix + result.render())
    }

    /**
     * Single quotes around a path that is about to become an argument: a name with a space under
     * `/sdcard/Download` is common, and an unquoted one would arrive as two arguments. The only
     * character single quotes cannot hold is closed, escaped and reopened.
     */
    private fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
