package com.suixing.agent.virtualscreen

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.suixing.agent.core.Core
import com.suixing.agent.service.VirtualScreenOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI and Agent share the same lifecycle entry points; window operations stay on Main. */
object VirtualScreenControl {
    data class Screenshot(val displayId: Int, val jpeg: ByteArray)
    suspend fun open(context: Context, preview: Boolean = true): VirtualScreenManager.State =
        withContext(Dispatchers.Main.immediate) {
            val state = VirtualScreenManager.start(context)
            if (preview) VirtualScreenOverlay.show(context, state.displayId)
            else VirtualScreenOverlay.hide()
            state
        }

    suspend fun preview(context: Context, visible: Boolean, expectedDisplayId: Int? = null): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val state = requireScreen(expectedDisplayId)
            if (!visible) {
                VirtualScreenOverlay.hide()
                false
            } else {
                check(VirtualScreenOverlay.show(context, state.displayId)) {
                    "无法显示预览，请在权限页开启无障碍或悬浮窗权限。虚拟屏仍在运行。"
                }
                true
            }
        }

    suspend fun close(expectedDisplayId: Int? = null) = withContext(Dispatchers.Main.immediate) {
        val state = VirtualScreenManager.state.value
        if (state.running) requireScreen(expectedDisplayId)
        VirtualScreenOverlay.hide()
        VirtualScreenManager.stop()
    }

    suspend fun screenshot(maxWidth: Int, quality: Int, expectedDisplayId: Int? = null): Screenshot {
        val state = withContext(Dispatchers.Main.immediate) { requireScreen(expectedDisplayId) }
        val bytes = VirtualScreenManager.latestJpeg(
            maxWidth.coerceIn(320, 2160), quality.coerceIn(1, 100), state.displayId,
        )
        withContext(Dispatchers.Main.immediate) { requireScreen(state.displayId) }
        return Screenshot(state.displayId, bytes
            ?: error("虚拟屏尚未收到可用画面，请先启动应用并等待取帧。"))
    }

    suspend fun launchTarget(context: Context, target: String, expectedDisplayId: Int? = null): Int {
        val intent = withContext(Dispatchers.IO) {
            val value = target.trim()
            require(value.isNotEmpty()) { "请指定应用包名或组件。" }
            if ('/' in value) {
                val component = ComponentName.unflattenFromString(value)
                    ?: error("组件格式应为 package/Activity。")
                Intent().setComponent(component)
            } else {
                context.packageManager.getLaunchIntentForPackage(value)
                    ?: error("应用没有启动入口：$value。可以用 list_apps 查询包名。")
            }
        }
        return launchIntent(context, intent, expectedDisplayId)
    }

    suspend fun launchIntent(context: Context, intent: Intent, expectedDisplayId: Int? = null): Int {
        val state = withContext(Dispatchers.Main.immediate) { requireScreen(expectedDisplayId) }
        val component = intent.component ?: error("应用启动入口缺少组件。")
        if (component.packageName == context.packageName) {
            try {
                withContext(Dispatchers.Main.immediate) {
                    requireScreen(state.displayId)
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(state.displayId).toBundle()
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
                }
            } catch (error: SecurityException) {
                check(Core.capabilities.state.value.rootReady) { "该 Activity 需要先授权 root 才能在副屏启动。" }
                check(VirtualScreenManager.launchExternal(component.flattenToString(), state.displayId)) {
                    "该 Activity 不允许普通身份嵌入副屏，需要可用的 root 权限。"
                }
            }
        } else {
            check(Core.capabilities.state.value.rootReady) { "在副屏启动第三方应用需要先授权 root。" }
            check(VirtualScreenManager.launchExternal(component.flattenToString(), state.displayId)) {
                "无法在虚拟屏启动应用，第三方应用需要可用的 root 权限；不会改为在主屏启动。"
            }
        }
        withContext(Dispatchers.Main.immediate) { requireScreen(state.displayId) }
        return state.displayId
    }

    private fun requireScreen(expectedDisplayId: Int?): VirtualScreenManager.State {
        val state = VirtualScreenManager.state.value
        check(state.running && state.displayId >= 0) { "虚拟屏未开启，请先开启虚拟屏。" }
        check(expectedDisplayId == null || expectedDisplayId == state.displayId) {
            "虚拟屏已变更，请重新查询 display_id。"
        }
        return state
    }
}
