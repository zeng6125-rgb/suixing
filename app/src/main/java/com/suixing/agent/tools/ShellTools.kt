package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.Shell
import com.suixing.agent.capability.ShellResult
import com.suixing.agent.capability.ShellTier
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

    override val group: String = "Shell"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "run_shell",
            "Run a shell command and return its output. mode=auto picks the highest privilege available " +
                "(root > shizuku > app). The app tier runs the bundled GNU bash, not Android's toybox sh — " +
                "[[ ]], arrays and process substitution work; the bundled BusyBox is \$BB " +
                "(e.g. \"\$BB\" grep -rn foo .).",
            Schema.objectOf(
                properties = listOf(
                    "command" to Schema.string("Command to run, e.g. ls -l /sdcard/Download"),
                    "shell" to Schema.string(
                        "Shell used by the app tier: auto (bundled bash when present) | bash | sh (system toybox).",
                        enumValues = listOf("auto", "bash", "sh"),
                        default = "auto",
                    ),
                    "mode" to Schema.string(
                        "Privilege tier: auto | local (app uid) | shizuku (shell uid) | root. A request only — " +
                            "it cannot exceed the tier fixed in Settings; the output prefix says which ran.",
                        enumValues = listOf("auto", "local", "shizuku", "root"),
                        default = "auto",
                    ),
                ),
                required = listOf("command"),
            ),
        ),
        ToolSpec(
            "app_action",
            "Privileged operation on an installed app. Needs Shizuku or root except for info/permissions.",
            Schema.objectOf(
                properties = listOf(
                    "package" to Schema.string("Target package name"),
                    "action" to Schema.string(
                        "Operation",
                        enumValues = listOf(
                            "start", "force_stop", "clear_data", "uninstall",
                            "disable", "enable", "info", "permissions", "install",
                        ),
                    ),
                    "apk_path" to Schema.string("Required when action=install: path to the APK"),
                ),
                required = listOf("package", "action"),
            ),
        ),
        ToolSpec(
            "settings_get",
            "Read one system setting. Cheaper and safer than assembling `settings get` in run_shell: " +
                "the namespace is validated here and the value comes back without shell quoting to worry about.",
            Schema.objectOf(
                properties = listOf(
                    "namespace" to Schema.string(
                        "Which settings table",
                        enumValues = listOf("system", "secure", "global"),
                    ),
                    "key" to Schema.string("Setting name, e.g. screen_brightness"),
                ),
                required = listOf("namespace", "key"),
            ),
        ),
        ToolSpec(
            "settings_put",
            "Write one system setting and **read it back** to prove it took effect. `settings put` " +
                "returning 0 does not mean the write landed (SELinux denial, wrong namespace, wrong user). " +
                "The reply always carries expected/actual/verified.",
            Schema.objectOf(
                properties = listOf(
                    "namespace" to Schema.string(
                        "Which settings table",
                        enumValues = listOf("system", "secure", "global"),
                    ),
                    "key" to Schema.string("Setting name"),
                    "value" to Schema.string("New value, exactly as `settings put` should receive it"),
                ),
                required = listOf("namespace", "key", "value"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "run_shell" -> runShell(args)
        "app_action" -> appAction(args)
        "settings_get" -> settingsGet(args)
        "settings_put" -> settingsPut(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun runShell(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args.str("command")?.trim().orEmpty()
        if (command.isEmpty()) return@withContext ToolResult.failure("缺少参数 command")
        val mode = args.str("mode")?.trim()?.lowercase() ?: "auto"
        val shell = args.str("shell")?.trim()?.lowercase() ?: "auto"
        drawInputFx(command, tool = "run_shell")
        val state = capabilities.state.value
        if (mode == "root" && !state.rootProbed) capabilities.probeRoot()
        val chosen = shellChannel(mode, capabilities.shellTier())
        // shell 只在 local 档有意义：root/shizuku 用的是系统里的 sh（那两个身份本来就能跑任何
        // 系统命令）；local 档默认优先用随包分发的静态 bash（见 Shell.local）。
        val result: ShellResult = when (chosen) {
            "root" -> Shell.root(command)
            "shizuku" -> Shell.shizuku(command)
            else -> Shell.local(command, shell = shell)
        }
        val label = when (chosen) {
            // root 档现在也走随包 bash（见 Shell.root），前缀照实标出来：没接上时仍写 root。
            "root" -> if (Shell.bundledBash() != null) "root+bash" else "root"
            "shizuku" -> "shizuku(shell)"
            else -> if (chosen == "local" && Shell.bundledBash() != null && shell != "sh") {
                "local(app)+bash"
            } else {
                "local(app)"
            }
        }
        val prefix = "[$label] exit=${result.exitCode}\n"
        if (result.ok) {
            // **写系统状态要读回核验**（issue #44 第一项）：`exit=0` 不等于生效 ——
            // SELinux 拒绝、namespace 写错（system/secure/global）、wrong user 都可能静默失败。
            // 模型看到 success 就继续下一步，要等几轮后才暴露。
            val verify = verifySettingWrite(command, chosen)
            ToolResult.success(prefix + result.render() + (verify ?: ""))
        } else {
            ToolResult.failure(prefix + result.render())
        }
    }

    /**
     * [command] 若是 `settings put <ns> <key> <value>`，读回一次并把核对结果附在输出里。
     *
     * 读回走**同一档位**（root 写就 root 读）：换身份读会读不到或读到别人的值，误报成"未生效"。
     * 不匹配写命令时返回 null（读命令、普通命令不加任何东西，避免噪音）。
     */
    private suspend fun verifySettingWrite(command: String, chosen: String): String? {
        val write = parseSettingWrite(command) ?: return null
        val getCmd = "settings get ${write.ns} ${write.key}"
        val actual = runCatching {
            val r = when (chosen) {
                "root" -> Shell.root(getCmd)
                "shizuku" -> Shell.shizuku(getCmd)
                else -> Shell.local(getCmd)
            }
            r.stdout.trim().trim('"')
        }.getOrNull()
        if (actual == null) {
            return "\n[verify] ${write.ns}/${write.key}: 读回失败，无法确认是否生效 —— 请勿假设已修改"
        }
        // `settings get` 对不存在的键返回 null；写了空值也会是 null，都算"达到了预期"。
        val verified = actual == write.value || actual == "null"
        return "\n[verify] ${write.ns}/${write.key}: expected=${write.value} actual=$actual " +
            "verified=$verified" +
            if (!verified) " — 写入未生效，请勿假设已修改" else ""
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
        val channel = capabilities.shellTier()
        if (channel == ShellTier.LOCAL) {
            return@withContext ToolResult.failure(
                "该操作需要 Shizuku（shell 用户）或 root 权限。请到「能力」页开启 Shizuku 并授权随行。",
            )
        }
        // `action=start` runs `monkey`, which launches an app: the same handoff `open_app` draws.
        // After the privilege check, so nothing flashes for a command that never runs.
        drawInputFx(command, tool = "app_action")
        val root = channel == ShellTier.ROOT
        val result = if (root) Shell.root(command) else Shell.shizuku(command)
        val label = if (root) "root" else "shizuku(shell)"
        val prefix = "[$label] exit=${result.exitCode}\n"
        if (!result.ok) return@withContext ToolResult.failure(prefix + result.render())
        // **enable/disable 要读回核验**（issue #44 第一项的延伸）：`pm disable-user` 返回 0
        // 也不代表状态真的变了（用户 0 之外的 profile、系统应用受保护等）。读回一次
        // `pm list packages -d`（＝已停用列表）对照，不匹配就把"未生效"明确说出来。
        val verify = when (action) {
            "disable", "enable" -> {
                val disabled = runCatching {
                    val r = if (root) Shell.root("pm list packages -d") else Shell.shizuku("pm list packages -d")
                    r.render()
                }.getOrNull()
                if (disabled == null) {
                    "\n[verify] 读回失败，无法确认是否生效 —— 请勿假设已修改"
                } else {
                    val isDisabled = disabled.lineSequence().any { it.trim() == "package:$pkg" }
                    val expected = action == "disable"
                    val ok = isDisabled == expected
                    "\n[verify] $pkg disabled=$isDisabled expected=$expected verified=$ok" +
                        if (!ok) " — 未生效，请勿假设已修改" else ""
                }
            }
            else -> ""
        }
        ToolResult.success(prefix + result.render() + verify)
    }

    /**
     * 结构化读一条系统设置（issue #44 第八项「系统 API」）。
     *
     * 为什么要有它：模型原先只能在 `run_shell` 里拼 `settings get ...` —— 命名空间写错、
     * 引号/空格处理失误都会静默出错，而且回执只是一坨 stdout。这里校验命名空间、
     * 走同一套档位（root/shizuku），并且**顺便把写命令也做成结构化工具**（见 [settingsPut]）。
     */
    private suspend fun settingsGet(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val ns = args.str("namespace")?.trim()?.lowercase().orEmpty()
        val key = args.str("key")?.trim().orEmpty()
        if (ns !in SETTING_NAMESPACES) return@withContext ToolResult.failure("namespace 必须是 system / secure / global")
        if (key.isEmpty()) return@withContext ToolResult.failure("缺少参数 key")
        if (!SETTING_KEY.matches(key)) {
            return@withContext ToolResult.failure("key 不合法：$key（只允许字母、数字、下划线、点、短横线）")
        }
        val channel = capabilities.shellTier()
        if (channel == ShellTier.LOCAL) return@withContext privilegeNeeded()
        val raw = runSetting("settings get $ns $key", channel)
            ?: return@withContext ToolResult.failure("读取失败：命令没有返回")
        ToolResult.success("$ns/$key = ${raw.ifEmpty { "(空)" }}")
    }

    /**
     * 结构化写一条系统设置，**并读回核验**（issue #44 第八项 + 第一项）。
     *
     * `settings put` 返回 0 不代表生效：SELinux 拒绝、命名空间写错、用户不对都可能静默失败。
     * 回执固定给 `expected / actual / verified`，后者为 false 时明确告诉模型"别假设已修改"。
     */
    private suspend fun settingsPut(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val ns = args.str("namespace")?.trim()?.lowercase().orEmpty()
        val key = args.str("key")?.trim().orEmpty()
        val value = args.str("value") ?: return@withContext ToolResult.failure("缺少参数 value")
        if (ns !in SETTING_NAMESPACES) return@withContext ToolResult.failure("namespace 必须是 system / secure / global")
        if (key.isEmpty()) return@withContext ToolResult.failure("缺少参数 key")
        if (!SETTING_KEY.matches(key)) {
            return@withContext ToolResult.failure("key 不合法：$key（只允许字母、数字、下划线、点、短横线）")
        }
        val channel = capabilities.shellTier()
        if (channel == ShellTier.LOCAL) return@withContext privilegeNeeded()
        val before = runSetting("settings get $ns $key", channel)
        val quoted = "'" + value.replace("'", "'\\''") + "'"
        val write = runSetting("settings put $ns $key $quoted", channel)
            ?: return@withContext ToolResult.failure("写入失败：命令没有返回")
        val actual = runSetting("settings get $ns $key", channel)
        val verified = actual == value || actual == "null"
        ToolResult.success(
            buildString {
                appendLine("$ns/$key")
                appendLine("before=${before.orEmpty().ifEmpty { "(空)" }}")
                appendLine("expected=$value")
                appendLine("actual=${actual.orEmpty().ifEmpty { "(空)" }}")
                append("verified=$verified")
                if (!verified) append(" — 写入未生效，请勿假设已修改；可检查 SELinux 或改用 root 档")
                // 写命令自己的 stdout/stderr 也留着：SELinux 拒绝的话原因通常只在这里。
                if (write.isNotBlank()) append("\noutput=$write")
            },
        )
    }

    /** 同档位跑一条 settings 命令，返回去掉引号与换行的输出。 */
    private suspend fun runSetting(command: String, channel: ShellTier): String? = runCatching {
        val r = if (channel == ShellTier.ROOT) Shell.root(command) else Shell.shizuku(command)
        r.render().trim().trim('"').trim()
    }.getOrNull()

    private fun privilegeNeeded(): ToolResult = ToolResult.failure(
        "该操作需要 Shizuku（shell 用户）或 root 权限。请到「能力」页开启 Shizuku 并授权随行。",
    )

    /**
     * Single quotes around a path that is about to become an argument: a name with a space under
     * `/sdcard/Download` is common, and an unquoted one would arrive as two arguments. The only
     * character single quotes cannot hold is closed, escaped and reopened.
     */
    private fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}

/**
 * The channel a `run_shell` call runs through: **the lower of what was asked for and what the
 * device resolved to**.
 *
 * A top-level function, and not a step inside `runShell`, for one reason: this is a *rule*, and the
 * rule is one sentence — an explicit tier is a request, not a permission. It can never go above the
 * tier the user fixed (naming `shizuku` while 标准档 is fixed runs as the app), and it is never
 * raised above what was asked for either (naming `shizuku` while Root is fixed runs Shizuku, not
 * root). Naming `root` on a device where root is not ready is the same idea one step further: the
 * request is clamped to what `CapabilityState.shellTier` resolved.
 *
 * The comparison uses [ShellTier]'s own declaration order (LOCAL < SHIZUKU < ROOT, ascending
 * privilege), so the two orderings cannot drift apart.
 */
/**
 * 一条 `settings put <ns> <key> <value>` 写命令（issue #44 第一项）。
 *
 * [value] 是**去掉引号后**的期望值，与 `settings get` 的输出直接可比。
 */
internal data class SettingWrite(val ns: String, val key: String, val value: String)

/**
 * 识别 `settings put <namespace> <key> <value>`。
 *
 * 只认这一条写路径（随行最常用的），`settings delete` / `cmd` / `wm` / `pm` 不认 ——
 * 不认识的命令不加核验，避免给读命令和普通命令添噪音。
 * 值两侧的引号要去掉：`settings put global adb_enabled "1"` 的期望值是 `1`。
 */
/** settings 的合法命名空间（结构化读写工具共用）。 */
private val SETTING_NAMESPACES = setOf("system", "secure", "global")

/** settings 键名的合法形状：挡掉带空格/引号/分号的注入面。 */
private val SETTING_KEY = Regex("""^[A-Za-z0-9_.\-]+$""")

/**
 * `settings put` 的识别正则。
 *
 * **提到文件级**：它在**每次 `run_shell` 成功之后**都会被调用一次（写状态读回核验），
 * 原来每次都编译一遍正则。行为完全相同，只是不再重复编译。
 */
private val SETTING_PUT = Regex("""^\s*settings\s+put\s+(system|secure|global)\s+(\S+)\s+(.+)$""")

internal fun parseSettingWrite(command: String): SettingWrite? {
    val m = SETTING_PUT.find(command) ?: return null
    val raw = m.groupValues[3].trim()
    return SettingWrite(
        ns = m.groupValues[1],
        key = m.groupValues[2],
        value = raw.trim('"').trim('\'').trim(),
    )
}

internal fun shellChannel(mode: String, resolved: ShellTier): String {
    // What the caller asked for, as a tier. Nothing asked for (auto, or a value this build does not
    // know) asks for exactly what the device resolved.
    val requested = when (mode) {
        "local" -> ShellTier.LOCAL
        "shizuku" -> ShellTier.SHIZUKU
        "root" -> ShellTier.ROOT
        else -> resolved
    }
    return minOf(requested, resolved).channel
}

/** The string `runShell` carries around for a resolved tier. */
private val ShellTier.channel: String
    get() = when (this) {
        ShellTier.ROOT -> "root"
        ShellTier.SHIZUKU -> "shizuku"
        ShellTier.LOCAL -> "local"
    }
