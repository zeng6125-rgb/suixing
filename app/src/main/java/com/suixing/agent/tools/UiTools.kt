package com.suixing.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.Shell
import com.suixing.agent.capability.ShellResult
import com.suixing.agent.capability.ShellTier
import com.suixing.agent.service.SuixingAccessibilityService
import com.suixing.agent.service.TouchFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Screen-level control: look at the screen, tap, swipe, type.
 *
 * There are two ways in and the tools use whichever is available:
 *  - the accessibility service, which is instant and can write CJK into fields;
 *  - `input` / `screencap` / `uiautomator` through Shizuku or root, which covers
 *    the same ground when accessibility is off (except CJK typing).
 *
 * Every call says which route it took, so a failure is explainable instead of silent.
 */
class UiTools(
    private val context: Context,
    private val capabilities: CapabilityHub,
) : ToolGroup() {

    override val tier: String = TIER_ACCESSIBILITY

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "ui_dump",
            "读取当前屏幕的可交互元素（文本、描述、中心坐标、是否可点击）。这是了解界面的首选方式，" +
                "比截图更快更省 token。",
            Schema.objectOf(
                properties = listOf(
                    "max_nodes" to Schema.integer("最多返回多少个节点，默认 140", default = 140, minimum = 10, maximum = 500),
                ),
            ),
        ),
        ToolSpec(
            "ui_screenshot",
            "截取当前屏幕，返回图片给模型看（适合界面元素没有文字或 ui_dump 不够时）。",
            Schema.objectOf(
                properties = listOf(
                    "max_width" to Schema.integer("缩放后的最大宽度，默认 1080", default = 1080, minimum = 320, maximum = 2160),
                    "quality" to Schema.integer("JPEG 质量 1-100，默认 72", default = 72, minimum = 1, maximum = 100),
                ),
            ),
        ),
        ToolSpec(
            "ui_tap",
            "点击屏幕上的坐标点。",
            Schema.objectOf(
                properties = listOf(
                    "x" to Schema.integer("横坐标（像素）"),
                    "y" to Schema.integer("纵坐标（像素）"),
                ),
                required = listOf("x", "y"),
            ),
        ),
        ToolSpec(
            "ui_long_press",
            "长按屏幕上的坐标点。",
            Schema.objectOf(
                properties = listOf(
                    "x" to Schema.integer("横坐标（像素）"),
                    "y" to Schema.integer("纵坐标（像素）"),
                ),
                required = listOf("x", "y"),
            ),
        ),
        ToolSpec(
            "ui_swipe",
            "从一点滑动到另一点（滚动列表、翻页、解锁）。",
            Schema.objectOf(
                properties = listOf(
                    "x1" to Schema.integer("起点横坐标"),
                    "y1" to Schema.integer("起点纵坐标"),
                    "x2" to Schema.integer("终点横坐标"),
                    "y2" to Schema.integer("终点纵坐标"),
                    "duration" to Schema.integer("滑动时长（毫秒），默认 300", default = 300, minimum = 60, maximum = 5000),
                ),
                required = listOf("x1", "y1", "x2", "y2"),
            ),
        ),
        ToolSpec(
            "ui_click_text",
            "按屏幕上的文字或控件描述点击（比坐标更稳）。",
            Schema.objectOf(
                properties = listOf(
                    "text" to Schema.string("要点击的文本或描述"),
                    "exact" to Schema.boolean("是否要求完全相等，默认 false（包含即可）", default = false),
                ),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "ui_type",
            "向当前获得焦点的输入框输入文本。走无障碍时支持中文；" +
                "只有 Shizuku / root 时只能输入英文数字。",
            Schema.objectOf(
                properties = listOf(
                    "text" to Schema.string("要输入的文本"),
                    "append" to Schema.boolean("是否追加到已有内容后面，默认 false", default = false),
                ),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "ui_type_into",
            "先点击某个文字/描述所在的输入框，再输入文本，一步完成填表（需要无障碍服务）。",
            Schema.objectOf(
                properties = listOf(
                    "label" to Schema.string("输入框的提示文字、标签或描述"),
                    "text" to Schema.string("要输入的文本"),
                ),
                required = listOf("label", "text"),
            ),
        ),
        ToolSpec(
            "ui_key",
            "发送系统按键：back（返回）、home（桌面）、recents（多任务）、notifications（通知栏）。",
            Schema.objectOf(
                properties = listOf(
                    "key" to Schema.string(
                        "按键名",
                        enumValues = listOf("back", "home", "recents", "notifications"),
                    ),
                ),
                required = listOf("key"),
            ),
        ),
        ToolSpec(
            "ui_current_app",
            "查看当前前台应用的包名。",
            Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            "ui_wait",
            "等待一段时间（等界面加载）。",
            Schema.objectOf(
                properties = listOf(
                    "ms" to Schema.integer("等待毫秒数，默认 1000", default = 1000, minimum = 100, maximum = 30000),
                ),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        if (name == "ui_wait") {
            val ms = args.int("ms", 1000).coerceIn(100, 30_000)
            delay(ms.toLong())
            return ToolResult.success("已等待 $ms 毫秒")
        }

        val service = accessibility()
        if (service == null && !privileged()) return blocked()

        return when (name) {
            "ui_dump" -> dump(service, args)
            "ui_screenshot" -> screenshot(service, args)
            "ui_tap" -> {
                val x = args.int("x", 0).toFloat()
                val y = args.int("y", 0).toFloat()
                gesture(
                    accessibilityAction = { it.tap(x, y) },
                    command = "input tap ${x.toInt()} ${y.toInt()}",
                    successText = "已点击 (${x.toInt()}, ${y.toInt()})",
                    fx = { TouchFx.tap(context, x, y) },
                )
            }

            "ui_long_press" -> {
                val x = args.int("x", 0).toFloat()
                val y = args.int("y", 0).toFloat()
                gesture(
                    accessibilityAction = { it.longPress(x, y) },
                    command = "input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 800",
                    successText = "已长按 (${x.toInt()}, ${y.toInt()})",
                    fx = { TouchFx.longPress(context, x, y) },
                )
            }

            "ui_swipe" -> {
                val duration = args.int("duration", 300).coerceIn(60, 5000)
                val x1 = args.int("x1", 0).toFloat()
                val y1 = args.int("y1", 0).toFloat()
                val x2 = args.int("x2", 0).toFloat()
                val y2 = args.int("y2", 0).toFloat()
                gesture(
                    accessibilityAction = { it.swipe(x1, y1, x2, y2, duration.toLong()) },
                    command = "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $duration",
                    successText = "已滑动",
                    fx = { TouchFx.swipe(context, x1, y1, x2, y2) },
                )
            }

            "ui_click_text" -> clickText(service, args)
            "ui_type" -> type(service, args)
            "ui_type_into" -> typeInto(service, args)
            "ui_key" -> key(service, args)
            "ui_current_app" -> currentApp(service)
            else -> ToolResult.failure("未知工具：$name")
        }
    }

    // ---------------------------------------------------------------- 通道

    private fun accessibility(): SuixingAccessibilityService? = SuixingAccessibilityService.instance()

    /** Whether a shell fallback exists at all under the tier the user fixed; see [ShellTier]. */
    private fun privileged(): Boolean = capabilities.shellTier() != ShellTier.LOCAL

    private fun blocked(): ToolResult = ToolResult.failure(
        "还不能操作屏幕：无障碍服务没开，Shizuku / root 也没授权。请到「能力」页开启任意一个。",
    )

    /** Runs [command] through the resolved channel; null when that is [ShellTier.LOCAL]. */
    private suspend fun shell(command: String): ShellResult? = withContext(Dispatchers.IO) {
        when (capabilities.shellTier()) {
            ShellTier.ROOT -> Shell.root(command)
            ShellTier.SHIZUKU -> Shell.shizuku(command)
            ShellTier.LOCAL -> null
        }
    }

    private fun shellName(): String = when (capabilities.shellTier()) {
        ShellTier.ROOT -> "root"
        ShellTier.SHIZUKU -> "Shizuku"
        ShellTier.LOCAL -> "应用自身权限"
    }

    /** Scratch file in the app's external files dir — writable by the app, shell and root alike. */
    private fun scratch(name: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, name)

    /**
     * Tries the accessibility gesture first and falls back to `input` when the
     * service is missing or the gesture was rejected.
     *
     * The accessibility action suspends until the system confirms the gesture, so a
     * success here means the touch actually landed — the model's next `ui_dump` then
     * sees the result of its own action instead of the screen it was looking at before.
     */
    private suspend fun gesture(
        accessibilityAction: suspend (SuixingAccessibilityService) -> Boolean,
        command: String,
        successText: String,
        fx: () -> Unit,
    ): ToolResult {
        val service = accessibility()
        if (service != null && runCatching { accessibilityAction(service) }.getOrDefault(false)) {
            return ToolResult.success(successText)
        }
        if (!privileged()) {
            return if (service == null) {
                blocked()
            } else {
                ToolResult.failure("操作没被系统接受（无障碍手势被拒绝，也没有 Shizuku / root 可以兜底）")
            }
        }
        val result = shell(command) ?: return blocked()
        if (!result.ok) return ToolResult.failure("$successText 没成功：${result.render(300)}")
        // On the accessibility route the service draws its own effect; nothing draws on this
        // one, and a tap the user cannot see is exactly the complaint the effects exist to
        // fix — the fallback is used when accessibility is off, so there is not even an
        // accessibility indicator to go by. Drawn only once the shell has confirmed the
        // touch landed, so the effect never claims a tap that did not happen.
        fx()
        return ToolResult.success("$successText（经由 ${shellName()}）")
    }

    // ---------------------------------------------------------------- 读屏

    private suspend fun dump(service: SuixingAccessibilityService?, args: JsonObject): ToolResult {
        val maxNodes = args.int("max_nodes", 140).coerceIn(10, 500)
        if (service != null) {
            return withContext(Dispatchers.Default) {
                ToolResult.success(service.dumpScreen(maxNodes = maxNodes))
            }
        }
        val nodes = dumpNodes() ?: return ToolResult.failure(
            "${shellName()} 读界面失败，可以改用 ui_screenshot 看屏幕。",
        )
        return ToolResult.success(screenHeader() + renderUiNodes(nodes.take(maxNodes), nodes.size > maxNodes))
    }

    /**
     * Every coordinate in a dump is absolute, so the screen it belongs to leads the
     * dump: without it the model cannot aim a swipe at empty space, which is how one
     * scrolls a list.
     */
    private fun screenHeader(): String {
        val metrics = context.resources.displayMetrics
        return "屏幕: ${metrics.widthPixels}x${metrics.heightPixels}\n"
    }

    /** Reads the view tree through `uiautomator dump`; null when it could not be read. */
    private suspend fun dumpNodes(): List<UiNode>? {
        val file = scratch("ui.xml")
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        shell("uiautomator dump ${file.absolutePath}") ?: return null
        val xml = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        }
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        if (xml.isBlank()) return null
        return parseUiDump(xml)
    }

    private suspend fun screenshot(service: SuixingAccessibilityService?, args: JsonObject): ToolResult {
        val maxWidth = args.int("max_width", 1080).coerceIn(320, 2160)
        val quality = args.int("quality", 72).coerceIn(1, 100)

        if (service != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bytes = withContext(Dispatchers.IO) { service.screenshotJpeg(maxWidth, quality) }
            if (bytes != null) {
                return ToolResult.image(
                    "已截图（${bytes.size / 1024} KB），图片随下一条消息一起提供。",
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }
        }
        if (!privileged()) {
            return if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                ToolResult.failure("系统截图需要安卓 11 及以上；当前系统版本不支持，请改用 ui_dump。")
            } else {
                ToolResult.failure("截图失败：无障碍服务没连上，也没有 Shizuku / root 可以代拍。")
            }
        }

        val file = scratch("screen.png")
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        val result = shell("screencap -p ${file.absolutePath}") ?: return blocked()
        val png = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) file.readBytes() else ByteArray(0) }.getOrDefault(ByteArray(0))
        }
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
        if (png.isEmpty()) {
            return ToolResult.failure("${shellName()} 截图没有产出内容：${result.render(300)}")
        }

        val bytes = withContext(Dispatchers.IO) { encodeJpeg(png, maxWidth, quality) }
            ?: return ToolResult.failure("截图拿到了，但解码失败。")
        return ToolResult.image(
            "已通过 ${shellName()} 截图（${bytes.size / 1024} KB），图片随下一条消息一起提供。",
            Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    private fun encodeJpeg(png: ByteArray, maxWidth: Int, quality: Int): ByteArray? = runCatching {
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalStateException("decode failed")
        val scaled = if (decoded.width > maxWidth) {
            val height = (decoded.height.toLong() * maxWidth / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, maxWidth, height, true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        out.toByteArray()
    }.getOrNull()

    // ---------------------------------------------------------------- 操作

    private suspend fun clickText(service: SuixingAccessibilityService?, args: JsonObject): ToolResult {
        val text = args.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")
        val exact = args.bool("exact", false)

        if (service != null && runCatching { service.clickByText(text, exact) }.getOrDefault(false)) {
            return ToolResult.success("已点击「$text」")
        }
        if (!privileged()) {
            return if (service == null) blocked() else ToolResult.failure("屏幕上没有找到「$text」")
        }

        val nodes = dumpNodes() ?: return ToolResult.failure("读不到界面，无法按文字点击。")
        val hit = nodes.firstOrNull { it.matches(text, exact) && it.clickable }
            ?: nodes.firstOrNull { it.matches(text, exact) }
            ?: return ToolResult.failure("屏幕上没有找到「$text」，可以先 ui_dump 看界面元素。")
        val result = shell("input tap ${hit.centerX} ${hit.centerY}") ?: return blocked()
        if (!result.ok) return ToolResult.failure("点击没成功：${result.render(300)}")
        // Same reason as in gesture(): this route is the one with no accessibility indicator,
        // so the effect is the only sign the agent touched the screen.
        TouchFx.tap(context, hit.centerX.toFloat(), hit.centerY.toFloat())
        return ToolResult.success("已点击「$text」（经由 ${shellName()}）")
    }

    private suspend fun type(service: SuixingAccessibilityService?, args: JsonObject): ToolResult {
        val text = args.str("text").orEmpty()
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")

        if (service != null) {
            val append = args.bool("append", false)
            val ok = runCatching { if (append) service.appendText(text) else service.setText(text) }
                .getOrDefault(false)
            if (ok) {
                TouchFx.noteDriving(context)
                return ToolResult.success("已输入 ${text.length} 字")
            }
            if (!privileged()) {
                return ToolResult.failure("没有找到可输入的输入框，请先点击输入框再输入。")
            }
        }
        if (!privileged()) return blocked()
        if (text.any { it.code > 127 }) {
            return ToolResult.failure(
                "走 ${shellName()} 的按键注入打不出中文，只有无障碍服务能直接写入输入框。" +
                    "请到「能力」页开启随行的无障碍服务后重试。",
            )
        }
        val escaped = text.replace("\\", "").replace("'", "").replace(" ", "%s")
        val result = shell("input text '$escaped'") ?: return blocked()
        if (!result.ok) return ToolResult.failure("输入没成功：${result.render(300)}")
        // Typing changes what is on screen, so it counts as driving the phone — it just has no
        // point to draw a ring at, which is why the light is the only sign of it.
        TouchFx.noteDriving(context)
        return ToolResult.success("已通过 ${shellName()} 输入 ${text.length} 个字符")
    }

    private suspend fun typeInto(service: SuixingAccessibilityService?, args: JsonObject): ToolResult {
        val label = args.str("label")?.trim().orEmpty()
        val text = args.str("text").orEmpty()
        if (label.isEmpty()) return ToolResult.failure("缺少参数 label")
        // Same check as type(): an empty text would otherwise "succeed" while writing nothing.
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")

        if (service == null) {
            return ToolResult.failure(
                "ui_type_into 需要无障碍服务：它要先点中输入框再写入内容。" +
                    "现在可以改用 ui_click_text 点输入框 + ui_type 输入。",
            )
        }
        val ok = runCatching { service.typeIntoField(label, text) }.getOrDefault(false)
        return if (ok) {
            TouchFx.noteDriving(context)
            ToolResult.success("已在「$label」输入 ${text.length} 字")
        } else {
            ToolResult.failure("没有找到「$label」，可以先 ui_dump 看界面元素。")
        }
    }

    private suspend fun key(service: SuixingAccessibilityService?, args: JsonObject): ToolResult {
        val name = args.str("key")?.trim()?.lowercase().orEmpty()
        val supported = listOf("back", "home", "recents", "notifications")
        if (name !in supported) return ToolResult.failure("不支持的按键：$name")

        val viaAccessibility = service != null && runCatching {
            when (name) {
                "back" -> service.back()
                "home" -> service.home()
                "recents" -> service.recents()
                else -> service.notifications()
            }
        }.getOrDefault(false)
        if (viaAccessibility) {
            TouchFx.noteDriving(context)
            return ToolResult.success("已发送按键 $name")
        }

        if (!privileged()) {
            return if (service == null) blocked() else ToolResult.failure("按键没被系统接受")
        }
        val command = when (name) {
            "back" -> "input keyevent KEYCODE_BACK"
            "home" -> "input keyevent KEYCODE_HOME"
            "recents" -> "input keyevent KEYCODE_APP_SWITCH"
            else -> "cmd statusbar expand-notifications"
        }
        val result = shell(command) ?: return blocked()
        if (!result.ok) return ToolResult.failure("按键没成功：${result.render(300)}")
        // A key press changes the screen, so it is driving; the flash stays the shell's business,
        // where the two keys that hand the phone to another app are the ones worth flashing for.
        TouchFx.noteDriving(context)
        return ToolResult.success("已发送按键 $name（经由 ${shellName()}）")
    }

    private suspend fun currentApp(service: SuixingAccessibilityService?): ToolResult {
        service?.currentPackage()?.let { return ToolResult.success("当前前台应用：$it") }
        if (!privileged()) return blocked()

        val stdout = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")?.stdout
        val pkg = focusedPackage(stdout.orEmpty())
        return if (pkg != null) {
            ToolResult.success("当前前台应用：$pkg")
        } else {
            ToolResult.failure("读不到前台应用，可以改用 ui_dump。")
        }
    }
}
