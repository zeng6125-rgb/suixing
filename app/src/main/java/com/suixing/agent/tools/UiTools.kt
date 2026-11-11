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
import com.suixing.agent.virtualscreen.VirtualScreenManager
import com.suixing.agent.service.TouchFx
import com.suixing.agent.service.uiOperationLabel
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
 *  - Shizuku or root provides main-screen `uiautomator` / `screencap` and coordinate
 *    input. Virtual-screen element reads and text-based clicks require accessibility;
 *    `uiautomator` cannot select a virtual display. Shell typing only supports ASCII.
 *
 * Every call says which route it took, so a failure is explainable instead of silent.
 */
/** 落盘的截图只留最近这么多张：够回溯几步，又不会把存储吃光。 */

/** 无障碍调用失败就当"做不到"，但**取消必须原样抛出**（issue #54 A-2）。 */
private inline fun attemptAction(block: () -> Boolean): Boolean = try {
    block()
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (_: Throwable) {
    false
}


private const val SHOTS_KEPT = 20

/** `ui_key` 支持的按键（与 spec 的 enumValues 同一份事实来源）。 */
private val SUPPORTED_KEYS = listOf("back", "home", "recents", "notifications")

class UiTools(
    private val context: Context,
    private val capabilities: CapabilityHub,
) : ToolGroup() {

    override val tier: String = TIER_ACCESSIBILITY

    override val group: String = "界面操作"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "ui_dump",
            "Read the interactive elements on the current screen (text, description, centre " +
                "coordinates, whether it is clickable). This is the first way to understand a screen — " +
                "faster and cheaper in tokens than a screenshot. On the main screen, Shizuku/root " +
                "can read elements when accessibility is off; virtual-screen elements require accessibility. " +
                "Without it, use ui_screenshot or ui_virtual_screen screenshot, then coordinate-based ui_tap.",
            Schema.objectOf(
                properties = listOf(
                    "max_nodes" to Schema.integer("Maximum nodes to return, default 140", default = 140, minimum = 10, maximum = 500),
                ),
            ),
        ),
        ToolSpec(
            "ui_screenshot",
            "Capture the current screen and return the image to the model (for screens whose elements " +
                "carry no text, or when ui_dump is not enough).",
            Schema.objectOf(
                properties = listOf(
                    "max_width" to Schema.integer("Maximum width after scaling, default 1080", default = 1080, minimum = 320, maximum = 2160),
                    "quality" to Schema.integer("JPEG quality 1-100, default 72", default = 72, minimum = 1, maximum = 100),
                    "path" to Schema.string(
                        "Optional: reload a previously saved screenshot from this path instead of taking a new one",
                    ),
                ),
            ),
        ),
        ToolSpec(
            "ui_tap",
            "Tap a point on the screen.",
            Schema.objectOf(
                properties = listOf(
                    "x" to Schema.integer("Horizontal coordinate (pixels)"),
                    "y" to Schema.integer("Vertical coordinate (pixels)"),
                ),
                required = listOf("x", "y"),
            ),
        ),
        ToolSpec(
            "ui_long_press",
            "Long-press a point on the screen.",
            Schema.objectOf(
                properties = listOf(
                    "x" to Schema.integer("Horizontal coordinate (pixels)"),
                    "y" to Schema.integer("Vertical coordinate (pixels)"),
                ),
                required = listOf("x", "y"),
            ),
        ),
        ToolSpec(
            "ui_swipe",
            "Swipe from one point to another (scroll a list, turn a page, unlock).",
            Schema.objectOf(
                properties = listOf(
                    "x1" to Schema.integer("Start X"),
                    "y1" to Schema.integer("Start Y"),
                    "x2" to Schema.integer("End X"),
                    "y2" to Schema.integer("End Y"),
                    "duration" to Schema.integer("Swipe duration in milliseconds, default 300", default = 300, minimum = 60, maximum = 5000),
                ),
                required = listOf("x1", "y1", "x2", "y2"),
            ),
        ),
        ToolSpec(
            "ui_click_text",
            "Click by the text or content description on screen (more reliable than coordinates). " +
                "On the virtual screen this requires accessibility; without it, use a screenshot and ui_tap coordinates.",
            Schema.objectOf(
                properties = listOf(
                    "text" to Schema.string("Text or description to click"),
                    "exact" to Schema.boolean("Require an exact match, default false (substring is enough)", default = false),
                ),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "ui_type",
            "Type text into the input field that currently has focus. Chinese works over the " +
                "accessibility channel; with Shizuku or root only ASCII letters and digits can be typed.",
            Schema.objectOf(
                properties = listOf(
                    "text" to Schema.string("Text to type"),
                    "append" to Schema.boolean("Append after the existing content, default false", default = false),
                ),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "ui_type_into",
            "Click the input field that carries this text or description and type into it — one step " +
                "for filling a form (needs the accessibility service).",
            Schema.objectOf(
                properties = listOf(
                    "label" to Schema.string("Hint text, label or description of the input field"),
                    "text" to Schema.string("Text to type"),
                ),
                required = listOf("label", "text"),
            ),
        ),
        ToolSpec(
            "ui_key",
            "Send a system key: back, home, recents, notifications. Notifications are unavailable on the virtual screen.",
            Schema.objectOf(
                properties = listOf(
                    "key" to Schema.string(
                        "Key name",
                        enumValues = listOf("back", "home", "recents", "notifications"),
                    ),
                ),
                required = listOf("key"),
            ),
        ),
        ToolSpec(
            "ui_current_app",
            "Show the package name of the app currently in the foreground. Virtual-screen app identification requires accessibility.",
            Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            "ui_wait",
            "Wait for a while (for a screen to load).",
            Schema.objectOf(
                properties = listOf(
                    "ms" to Schema.integer("Milliseconds to wait, default 1000", default = 1000, minimum = 100, maximum = 30000),
                ),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        if (name == "ui_wait") {
            val ms = args.int("ms", 1000).coerceIn(100, 30_000)
            return TouchFx.withUiOperation(context, uiOperationLabel(name, virtualDisplay())) {
                delay(ms.toLong())
                ToolResult.success("已等待 $ms 毫秒")
            }
        }

        val service = accessibility()
        if (service == null && !privileged()) return blocked()

        // 虚拟屏开着的时候，这一整套 UI 工具就作用在那块屏上：读屏、截图、点滑都按它的
        // displayId 走。否则 agent 会在主屏上操作，用户在浮窗里看到的却是另一块屏 ——
        // 那正是"agent 用不了"的来源。
        val vd = virtualDisplay()
        fun input(command: String): String = if (vd >= 0) "input -d $vd $command" else "input $command"
        val fx: (() -> Unit)? = if (vd >= 0) null else { { } }

        return TouchFx.withUiOperation(context, uiOperationLabel(name, vd, args.str("path"))) {
            when (name) {
                "ui_dump" -> dump(service, args, vd)
                "ui_screenshot" -> screenshot(service, args, vd)
                "ui_tap" -> {
                    val x = args.int("x", 0).toFloat()
                    val y = args.int("y", 0).toFloat()
                    gesture(
                        accessibilityAction = { it.tap(x, y, displayId = vd) },
                        command = input("tap ${x.toInt()} ${y.toInt()}"),
                        successText = "已点击 (${x.toInt()}, ${y.toInt()})",
                        fx = if (vd >= 0) null else ({ TouchFx.tap(context, x, y) }),
                    )
                }

                "ui_long_press" -> {
                    val x = args.int("x", 0).toFloat()
                    val y = args.int("y", 0).toFloat()
                    gesture(
                        accessibilityAction = { it.longPress(x, y, displayId = vd) },
                        command = input("swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 800"),
                        successText = "已长按 (${x.toInt()}, ${y.toInt()})",
                        fx = if (vd >= 0) null else ({ TouchFx.longPress(context, x, y) }),
                    )
                }

                "ui_swipe" -> {
                    val duration = args.int("duration", 300).coerceIn(60, 5000)
                    val x1 = args.int("x1", 0).toFloat()
                    val y1 = args.int("y1", 0).toFloat()
                    val x2 = args.int("x2", 0).toFloat()
                    val y2 = args.int("y2", 0).toFloat()
                    gesture(
                        accessibilityAction = { it.swipe(x1, y1, x2, y2, duration.toLong(), displayId = vd) },
                        command = input("swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $duration"),
                        successText = "已滑动",
                        fx = if (vd >= 0) null else ({ TouchFx.swipe(context, x1, y1, x2, y2) }),
                    )
                }

                // 带 displayId：虚拟屏开着时这几个也必须作用在那块屏上，否则按文字点击/输入/按键
                // 会落到主屏（issue #80）。
                "ui_click_text" -> clickText(service, args, vd)
                "ui_type" -> type(service, args, vd)
                "ui_type_into" -> typeInto(service, args, vd)
                "ui_key" -> key(service, args, vd)
                "ui_current_app" -> currentApp(service, vd)
                else -> ToolResult.failure("未知工具：$name")
            }
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
        fx: (() -> Unit)?,
    ): ToolResult {
        val service = accessibility()
        if (service != null && attemptAction { accessibilityAction(service) }) {
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
        fx?.invoke()
        return ToolResult.success("$successText（经由 ${shellName()}）")
    }

    // ---------------------------------------------------------------- 读屏

    private suspend fun dump(
        service: SuixingAccessibilityService?,
        args: JsonObject,
        displayId: Int,
    ): ToolResult {
        virtualTargetFailure(displayId)?.let { return it }
        if (displayId >= 0 && service == null) {
            return ToolResult.failure(
                "副屏读取界面元素需要 JAgent 无障碍服务，uiautomator 只能读取主屏。" +
                    "请在权限页开启无障碍；也可用 ui_screenshot 或 ui_virtual_screen screenshot 看副屏画面，" +
                    "再用 ui_tap / ui_swipe 按坐标操作。",
            )
        }
        // 读屏也是一次 UI 操作（spec v2 §2）：回合照常开启/续期，边缘光因此亮 ~3s 后淡出。
        if (displayId < 0) TouchFx.noteToolActivity(context, "ui_dump")
        val maxNodes = args.int("max_nodes", 140).coerceIn(10, 500)
        if (service != null) {
            val content = withContext(Dispatchers.Default) {
                service.dumpScreen(maxNodes = maxNodes, displayId = displayId)
            }
            virtualTargetFailure(displayId)?.let { return it }
            return ToolResult.success(content)
        }
        val metrics = context.resources.displayMetrics
        // uiautomator has no display selector: this tree and its header always describe the main screen.
        val header = screenHeader(metrics.widthPixels, metrics.heightPixels, -1, "uiautomator")
        val nodes = dumpNodes() ?: return ToolResult.failure(
            "${shellName()} 读界面失败，可以改用 ui_screenshot 看屏幕。",
        )
        return ToolResult.success(header + renderUiNodes(nodes.take(maxNodes), nodes.size > maxNodes))
    }

    /**
     * Every coordinate in a dump is absolute, so the screen it belongs to leads the
     * dump: without it the model cannot aim a swipe at empty space, which is how one
     * scrolls a list.
     */
    private fun screenHeader(width: Int, height: Int, displayId: Int, source: String): String =
        "屏幕: ${width}x${height}（" +
            (if (displayId >= 0) "虚拟屏 displayId=$displayId" else "主屏") + "，$source）\n"

    /** 正在运行的虚拟屏 displayId；没开就是 -1，所有 UI 工具回到主屏。 */
    private fun virtualDisplay(): Int =
        VirtualScreenManager.state.value.let { if (it.running) it.displayId else -1 }

    private fun virtualTargetFailure(displayId: Int): ToolResult? =
        if (displayId >= 0 && virtualDisplay() != displayId) {
            ToolResult.failure("虚拟屏已关闭或变更，请重新查询屏幕后再操作。")
        } else null

    /** Reads the main-screen tree through `uiautomator dump`; callers must reject virtual displays. */
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

    private suspend fun screenshot(
        service: SuixingAccessibilityService?,
        args: JsonObject,
        displayId: Int,
    ): ToolResult {
        // 用 path 重新加载一张旧截图：出站时更早的截图会被省略（省带宽），占位里给出路径，
        // 模型需要再看一眼时用它把原图重新挂回 wire —— 不必重新截图、也不必让用户重做界面。
        args.str("path")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val file = resolveUserPath(context, raw)
            if (!file.exists()) return ToolResult.failure("截图文件不存在：${file.absolutePath}")
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                ?: return ToolResult.failure("读不了这个文件：${file.absolutePath}")
            return ToolResult.image(
                "已重新加载截图 ${file.absolutePath}（${bytes.size / 1024} KB），图片随下一条消息一起提供。",
                Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
        val maxWidth = args.int("max_width", 1080).coerceIn(320, 2160)
        val quality = args.int("quality", 72).coerceIn(1, 100)

        // 虚拟屏优先：直接取副屏 ImageReader 的最新一帧。不依赖窗口焦点与系统截图冷却，
        // 副屏上没有前台窗口时也拿得到画面（Eta / Mika 的截图同样建在 ImageReader 上）。
        if (displayId >= 0) {
            val fromScreen = VirtualScreenManager.latestJpeg(maxWidth, quality, displayId)
            if (fromScreen != null) {
                val kept = withContext(Dispatchers.IO) { saveShot(fromScreen) }
                if (virtualDisplay() != displayId) {
                    return ToolResult.failure("虚拟屏已关闭或变更，请重新查询屏幕后再截图。")
                }
                return ToolResult.image(
                    "已从虚拟屏 $displayId 取帧（${fromScreen.size / 1024} KB）" +
                        (kept?.let { "，原图：${it.absolutePath}（重看用 ui_screenshot path=$it）" } ?: "") +
                        "，图片随下一条消息一起提供。",
                    Base64.encodeToString(fromScreen, Base64.NO_WRAP),
                )
            }
            if (virtualDisplay() != displayId) {
                return ToolResult.failure("虚拟屏已关闭或变更，请重新查询屏幕后再截图。")
            }
        }

        if (service != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bytes = withContext(Dispatchers.IO) { service.screenshotJpeg(
                maxWidth,
                quality,
                if (displayId >= 0) displayId else android.view.Display.DEFAULT_DISPLAY,
            ) }
            if (bytes != null) {
                val kept = withContext(Dispatchers.IO) { saveShot(bytes) }
                if (displayId >= 0 && virtualDisplay() != displayId) {
                    return ToolResult.failure("虚拟屏已关闭或变更，请重新查询屏幕后再截图。")
                }
                return ToolResult.image(
                    "已截图（${bytes.size / 1024} KB）" +
                        (kept?.let { "，原图：${it.absolutePath}（重看用 ui_screenshot path=$it）" } ?: "") +
                        "，图片随下一条消息一起提供。",
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }
        }
        if (displayId >= 0) {
            if (virtualDisplay() != displayId) {
                return ToolResult.failure("虚拟屏已关闭或变更，请重新查询屏幕后再截图。")
            }
            return ToolResult.failure("暂时没有可用的虚拟屏截图，请等待副屏取帧后重试。")
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
        var result: ShellResult? = null
        val png = try {
            result = TouchFx.withoutOperationStatus { shell("screencap -p ${file.absolutePath}") } ?: return blocked()
            withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) file.readBytes() else ByteArray(0) }.getOrDefault(ByteArray(0))
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { file.delete() } }
        }
        if (png.isEmpty()) {
            return ToolResult.failure("${shellName()} 截图没有产出内容：${result?.render(300)}")
        }

        val bytes = withContext(Dispatchers.IO) { encodeJpeg(png, maxWidth, quality) }
            ?: return ToolResult.failure("截图拿到了，但解码失败。")
        val kept = withContext(Dispatchers.IO) { saveShot(bytes) }
        return ToolResult.image(
            "已通过 ${shellName()} 截图（${bytes.size / 1024} KB）" +
                (kept?.let { "，原图：${it.absolutePath}（重看用 ui_screenshot path=$it）" } ?: "") +
                "，图片随下一条消息一起提供。",
            Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    /**
     * 把截图存到 `filesDir/shots/`，只留最近 [SHOTS_KEPT] 张。
     *
     * 落盘的理由不是"给用户看"，而是**让省略可回溯**：出站时更早的截图会被降级成一行占位
     * （见 AgentEngine.pruneOldImages），占位里带着这个路径，模型需要时能把它重新挂回 wire。
     */
    private fun saveShot(bytes: ByteArray): File? = runCatching {
        val dir = File(context.filesDir, "shots").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        file.writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(SHOTS_KEPT)?.forEach { it.delete() }
        file
    }.getOrNull()

    private fun encodeJpeg(png: ByteArray, maxWidth: Int, quality: Int): ByteArray? = runCatching {
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalStateException("decode failed")
        var scaled: Bitmap? = null
        try {
        scaled = if (decoded.width > maxWidth) {
            val height = (decoded.height.toLong() * maxWidth / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, maxWidth, height, true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG compression failed" }
        out.toByteArray()
        } finally {
            if (scaled !== decoded) scaled?.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 操作

    private suspend fun clickText(service: SuixingAccessibilityService?, args: JsonObject, displayId: Int = -1): ToolResult {
        val text = args.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")
        val exact = args.bool("exact", false)

        virtualTargetFailure(displayId)?.let { return it }
        val clicked = service != null && attemptAction { service.clickByText(text, exact, displayId) }
        virtualTargetFailure(displayId)?.let { return it }
        if (clicked) {
            return ToolResult.success("已点击「$text」")
        }

        // **副屏没有 uiautomator 兜底**：`uiautomator dump` 没有 display 参数，实测会静默读主屏
        // （`--display 3` 被忽略，dump 出来的 bounds 是主屏的 1156x2510）。在这里继续走下去，
        // 就会按主屏坐标去点副屏上想点的地方 —— 用户看到的是"点了但没反应"甚至点错东西。
        // 与其猜，不如把话说清楚：副屏上按文字点击只走无障碍那条路。
        if (displayId >= 0) {
            val reason = if (service == null) "副屏按文字点击需要开启 JAgent 无障碍服务。"
                else "副屏上未找到「$text」或无法读取目标窗口。"
            return ToolResult.failure(
                reason + "请在权限页确认无障碍已开启；也可用 ui_screenshot 或 ui_virtual_screen screenshot 看画面，" +
                    "再用 ui_tap 按坐标点击。",
            )
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

    private suspend fun type(service: SuixingAccessibilityService?, args: JsonObject, displayId: Int = -1): ToolResult {
        val text = args.str("text").orEmpty()
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")

        if (service != null) {
            val append = args.bool("append", false)
            val ok = runCatching {
                if (append) service.appendText(text, displayId) else service.setText(text, displayId)
            }.getOrDefault(false)
            if (ok) {
                if (displayId < 0) TouchFx.noteDriving(context)
                return ToolResult.success("已输入 ${text.length} 字")
            }
            if (!privileged()) {
                return ToolResult.failure("没有找到可输入的输入框，请先点击输入框再输入。")
            }
        }
        if (!privileged()) return blocked()
        // 副屏的按键注入带 `-d`（input 的 display 参数），主屏保持原样。
        val displayArg = if (displayId >= 0) "-d $displayId " else ""
        if (text.any { it.code > 127 }) {
            return ToolResult.failure(
                "走 ${shellName()} 的按键注入打不出中文，只有无障碍服务能直接写入输入框。" +
                    "请到「能力」页开启JAgent的无障碍服务后重试。",
            )
        }
        val escaped = text.replace("\\", "").replace("'", "").replace(" ", "%s")
        val result = shell("input ${displayArg}text '$escaped'") ?: return blocked()
        if (!result.ok) return ToolResult.failure("输入没成功：${result.render(300)}")
        // Typing changes what is on screen, so it counts as driving the phone — it just has no
        // point to draw a ring at, which is why the light is the only sign of it.
        if (displayId < 0) TouchFx.noteDriving(context)
        return ToolResult.success("已通过 ${shellName()} 输入 ${text.length} 个字符")
    }

    private suspend fun typeInto(service: SuixingAccessibilityService?, args: JsonObject, displayId: Int = -1): ToolResult {
        val label = args.str("label")?.trim().orEmpty()
        val text = args.str("text").orEmpty()
        if (label.isEmpty()) return ToolResult.failure("缺少参数 label")
        // Same check as type(): an empty text would otherwise "succeed" while writing nothing.
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")

        virtualTargetFailure(displayId)?.let { return it }
        if (service == null) {
            val guidance = if (displayId >= 0) {
                "请在权限页开启无障碍；也可用副屏截图与 ui_tap 坐标定位，再用 ui_type 输入（shell 通道只支持 ASCII）。"
            } else {
                "现在可以改用 ui_click_text 点输入框 + ui_type 输入。"
            }
            return ToolResult.failure(
                "ui_type_into 需要无障碍服务：它要先点中输入框再写入内容。" + guidance,
            )
        }
        val ok = attemptAction { service.typeIntoField(label, text, displayId) }
        virtualTargetFailure(displayId)?.let { return it }
        return if (ok) {
            if (displayId < 0) TouchFx.noteDriving(context)
            ToolResult.success("已在「$label」输入 ${text.length} 字")
        } else {
            ToolResult.failure("没有找到「$label」，可以先 ui_dump 看界面元素。")
        }
    }

    private suspend fun key(service: SuixingAccessibilityService?, args: JsonObject, displayId: Int = -1): ToolResult {
        val name = args.str("key")?.trim()?.lowercase().orEmpty()
        // 常量复用（优化清单 tools-P2）：原来每次 ui_key 都新建这个 list。
        val supported = SUPPORTED_KEYS
        if (name !in supported) return ToolResult.failure("不支持的按键：$name")
        if (displayId >= 0 && name == "notifications") {
            return ToolResult.failure("虚拟屏没有独立通知栏，不能发送 notifications；该操作会展开主屏通知栏。")
        }

        // **副屏只能走 shell 的 `input -d`**：`performGlobalAction`（back/home/recents）没有屏参数，
        // 只作用于当前有焦点的屏，对副屏不可靠（见 VirtualScreenInput 的同一段说明）。所以副屏
        // 跳过无障碍这条路，直接落到下面带 `-d` 的命令上。
        val viaAccessibility = displayId < 0 && service != null && runCatching {
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
        val displayArg = if (displayId >= 0) "-d $displayId " else ""
        val command = when (name) {
            "back" -> "input ${displayArg}keyevent KEYCODE_BACK"
            "home" -> "input ${displayArg}keyevent KEYCODE_HOME"
            "recents" -> "input ${displayArg}keyevent KEYCODE_APP_SWITCH"
            // 状态栏展开是全局动作，没有按屏区分的语义，副屏上也只作用于主屏。
            else -> "cmd statusbar expand-notifications"
        }
        val result = shell(command) ?: return blocked()
        if (!result.ok) return ToolResult.failure("按键没成功：${result.render(300)}")
        // A key press changes the screen, so it is driving; the flash stays the shell's business,
        // where the two keys that hand the phone to another app are the ones worth flashing for.
        if (displayId < 0) TouchFx.noteDriving(context)
        return ToolResult.success("已发送按键 $name（经由 ${shellName()}）")
    }

    private suspend fun currentApp(service: SuixingAccessibilityService?, displayId: Int = -1): ToolResult {
        // 主屏才认 `rootInActiveWindow`：它对副屏永远给主屏那套，答出来的会是错误的应用。
        // 副屏用 windows 列表里那块屏最上层的包名。
        if (displayId >= 0) {
            virtualTargetFailure(displayId)?.let { return it }
            if (service == null) {
                return ToolResult.failure(
                    "副屏前台应用识别需要 JAgent 无障碍服务。请在权限页开启；也可用副屏截图确认当前画面。",
                )
            }
            val onScreen = service.currentPackageOn(displayId)
            virtualTargetFailure(displayId)?.let { return it }
            return if (onScreen != null) {
                ToolResult.success("当前前台应用：$onScreen")
            } else {
                ToolResult.failure("副屏上没有可读的窗口，可以改用副屏截图确认当前画面。")
            }
        }
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
