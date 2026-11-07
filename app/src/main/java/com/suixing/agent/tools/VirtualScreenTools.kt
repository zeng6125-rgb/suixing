package com.suixing.agent.tools

import android.content.Context
import android.util.Base64
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.service.VirtualScreenOverlay
import com.suixing.agent.virtualscreen.VirtualScreenControl
import com.suixing.agent.virtualscreen.VirtualScreenManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** No accessibility requirement for display lifecycle; normal tool approval still applies. */
class VirtualScreenTools(private val context: Context, private val capabilities: CapabilityHub) : ToolGroup() {
    override val group = "UI自动化"
    override val specs = listOf(
        ToolSpec(
            "ui_virtual_screen",
            "Control JAgent's independent virtual screen. status reads its state; open creates/reuses it; " +
                "preview shows or hides the floating preview without stopping apps; close stops the display; " +
                "screenshot returns its ImageReader frame without requiring accessibility; " +
                "open_app launches a package/component on it (third-party apps need root). While it is open, " +
                "ui_* and open_app target this display. Closing it restores main-screen targeting. " +
                "If open cannot show the requested preview, the display stays running and the result includes a warning. " +
                "Use display_id from status to reject commands for an outdated display.",
            Schema.objectOf(listOf(
                "action" to Schema.string("Operation", enumValues = listOf("status", "open", "preview", "close", "screenshot", "open_app")),
                "preview" to Schema.boolean("For open: show floating preview, default true", default = true),
                "visible" to Schema.boolean("Required for preview: true shows it, false hides it"),
                "target" to Schema.string("For open_app: package name or package/Activity; use list_apps to find package names"),
                "display_id" to Schema.integer("For preview/close/screenshot/open_app: optional expected display ID from status"),
                "max_width" to Schema.integer("For screenshot: maximum image width, default 1080", default = 1080, minimum = 320, maximum = 2160),
                "quality" to Schema.integer("For screenshot: JPEG quality, default 72", default = 72, minimum = 1, maximum = 100),
            ), required = listOf("action")),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = try {
        require(name == "ui_virtual_screen") { "未知的虚拟屏工具。" }
        val expected = args["display_id"]?.let { it.jsonPrimitive.intOrNull
            ?: error("display_id 必须为整数。") }
        var image: VirtualScreenControl.Screenshot? = null
        var warning: String? = null
        when (args.str("action")) {
            "status" -> Unit
            "open" -> {
                val preview = args["preview"]?.let { it.jsonPrimitive.booleanOrNull
                    ?: error("preview 必须为布尔值。") } ?: true
                VirtualScreenControl.open(context, preview)
                if (preview && !VirtualScreenOverlay.isShowing) {
                    warning = "虚拟屏已开启，但预览未显示。请在权限页开启无障碍或悬浮窗权限。"
                }
            }
            "preview" -> {
                val visible = args["visible"]?.jsonPrimitive?.booleanOrNull
                    ?: error("preview 需要布尔值 visible 参数。")
                VirtualScreenControl.preview(context, visible, expected)
            }
            "close" -> VirtualScreenControl.close(expected)
            "screenshot" -> image = VirtualScreenControl.screenshot(args.int("max_width", 1080), args.int("quality", 72), expected)
            "open_app" -> VirtualScreenControl.launchTarget(context, args.str("target").orEmpty(), expected)
            else -> error("action 应为 status/open/preview/close/screenshot/open_app。")
        }
        val state = VirtualScreenManager.state.value
        val shot = image
        if (shot != null) ToolResult.image("已从虚拟屏 ${shot.displayId} 取帧（${shot.jpeg.size / 1024} KB）。",
            Base64.encodeToString(shot.jpeg, Base64.NO_WRAP))
        else ToolResult.success(buildJsonObject {
            put("running", state.running)
            put("display_id", state.displayId)
            put("width", state.width)
            put("height", state.height)
            put("preview_visible", VirtualScreenOverlay.isShowing)
            put("has_frame", VirtualScreenManager.hasFrame())
            put("root_available", capabilities.state.value.rootReady)
            warning?.let { put("warning", it) }
        }.toString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolResult.failure(error.message ?: "虚拟屏操作失败。")
    }
}
