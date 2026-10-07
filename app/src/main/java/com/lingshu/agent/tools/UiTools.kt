package com.lingshu.agent.tools

import com.lingshu.agent.capability.CapabilityHub
import com.lingshu.agent.service.LingshuAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Screen-level control through the accessibility service: look at the screen,
 * tap, swipe, type. Every call checks that the service is actually connected so
 * the model gets a clear reason instead of a silent failure.
 */
class UiTools(private val capabilities: CapabilityHub) : ToolGroup() {

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
            "向当前获得焦点的输入框输入文本（支持中文，不走按键模拟）。",
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
            "先点击某个文字/描述所在的输入框，再输入文本，一步完成填表。",
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
        val service = LingshuAccessibilityService.instance()
            ?: return ToolResult.failure("无障碍服务未开启。请到「能力」页打开灵枢的无障碍服务后再操作界面。")
        return when (name) {
            "ui_dump" -> withContext(Dispatchers.Default) {
                val maxNodes = args.int("max_nodes", 140).coerceIn(10, 500)
                ToolResult.success(service.dumpScreen(maxNodes = maxNodes))
            }
            "ui_screenshot" -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    return ToolResult.failure("系统截图需要安卓 11 及以上；当前系统版本不支持，请改用 ui_dump。")
                }
                val maxWidth = args.int("max_width", 1080).coerceIn(320, 2160)
                val quality = args.int("quality", 72).coerceIn(1, 100)
                val bytes = service.screenshotJpeg(maxWidth = maxWidth, quality = quality)
                    ?: return ToolResult.failure("截图失败，请稍后重试或改用 ui_dump。")
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                ToolResult.image("已截图（${bytes.size / 1024} KB），图片随下一条消息一起提供。", base64)
            }
            "ui_tap" -> simple(service.tap(args.int("x", 0).toFloat(), args.int("y", 0).toFloat()), "已点击")
            "ui_long_press" -> simple(service.longPress(args.int("x", 0).toFloat(), args.int("y", 0).toFloat()), "已长按")
            "ui_swipe" -> simple(
                service.swipe(
                    args.int("x1", 0).toFloat(),
                    args.int("y1", 0).toFloat(),
                    args.int("x2", 0).toFloat(),
                    args.int("y2", 0).toFloat(),
                    args.int("duration", 300).toLong(),
                ),
                "已滑动",
            )
            "ui_click_text" -> {
                val text = args.str("text")?.trim().orEmpty()
                if (text.isEmpty()) return ToolResult.failure("缺少参数 text")
                simple(service.clickByText(text, args.bool("exact", false)), "已点击「$text」")
            }
            "ui_type" -> {
                val text = args.str("text").orEmpty()
                if (text.isEmpty()) return ToolResult.failure("缺少参数 text")
                val append = args.bool("append", false)
                val ok = if (append) service.appendText(text) else service.setText(text)
                if (ok) ToolResult.success("已输入 ${text.length} 字") else {
                    ToolResult.failure("没有找到可输入的输入框，请先点击输入框再输入。")
                }
            }
            "ui_type_into" -> {
                val label = args.str("label")?.trim().orEmpty()
                val text = args.str("text").orEmpty()
                if (label.isEmpty()) return ToolResult.failure("缺少参数 label")
                val ok = service.typeIntoField(label, text)
                if (ok) ToolResult.success("已在「$label」输入 ${text.length} 字") else {
                    ToolResult.failure("没有找到「$label」，可以先 ui_dump 看界面元素。")
                }
            }
            "ui_key" -> {
                val key = args.str("key")?.trim()?.lowercase().orEmpty()
                val ok = when (key) {
                    "back" -> service.back()
                    "home" -> service.home()
                    "recents" -> service.recents()
                    "notifications" -> service.notifications()
                    else -> return ToolResult.failure("不支持的按键：$key")
                }
                simple(ok, "已发送按键 $key")
            }
            "ui_current_app" -> ToolResult.success("当前前台应用：${service.currentPackage() ?: "未知"}")
            "ui_wait" -> {
                val ms = args.int("ms", 1000).coerceIn(100, 30_000)
                delay(ms.toLong())
                ToolResult.success("已等待 $ms 毫秒")
            }
            else -> ToolResult.failure("未知工具：$name")
        }
    }

    private fun simple(ok: Boolean, successText: String): ToolResult =
        if (ok) ToolResult.success(successText) else ToolResult.failure("操作未被系统接受（无障碍手势可能被拒绝）")
}
