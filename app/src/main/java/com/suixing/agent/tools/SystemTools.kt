package com.suixing.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.service.TouchFx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Things every phone agent needs and that need no extra privilege: what device
 * this is, what is installed, launching apps, the clipboard and long-term notes.
 */
class SystemTools(
    private val context: Context,
    private val memory: MemoryStore,
    private val profile: UserProfileStore,
) : ToolGroup() {

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "device_info",
            "获取设备信息：型号、安卓版本、屏幕分辨率、当前时间、电量、存储空间、本应用版本。",
            Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            "list_apps",
            "列出手机上已安装且可启动的应用（名称 + 包名），可用 query 过滤。",
            Schema.objectOf(
                properties = listOf(
                    "query" to Schema.string("按名称或包名过滤，可省略"),
                    "limit" to Schema.integer("最多返回多少个，默认 80", default = 80, minimum = 1, maximum = 300),
                ),
            ),
        ),
        ToolSpec(
            "open_app",
            "打开一个应用。优先按包名，也可以给应用名称（如「微信」）。",
            Schema.objectOf(
                properties = listOf("target" to Schema.string("包名或应用名称")),
                required = listOf("target"),
            ),
        ),
        ToolSpec(
            "open_url",
            "用系统浏览器或对应应用打开一个网址。",
            Schema.objectOf(
                properties = listOf("url" to Schema.string("要打开的网址")),
                required = listOf("url"),
            ),
        ),
        ToolSpec(
            "clipboard_read",
            "读取剪贴板文本。注意：安卓 10 以上只有本应用在前台时才能读。",
            Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            "clipboard_write",
            "写入剪贴板。常用于把长文本或中文安全地送进其它应用的输入框。",
            Schema.objectOf(
                properties = listOf("text" to Schema.string("要写入剪贴板的文本")),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "memory_write",
            "把一条长期备忘写入本地记忆（跨会话保留），例如用户的偏好、常用路径。",
            Schema.objectOf(
                properties = listOf(
                    "key" to Schema.string("备忘的键名"),
                    "value" to Schema.string("备忘内容"),
                ),
                required = listOf("key", "value"),
            ),
        ),
        ToolSpec(
            "memory_read",
            "读取本地长期备忘；不传 key 则返回全部。",
            Schema.objectOf(properties = listOf("key" to Schema.string("要读取的键名，可省略"))),
        ),
        ToolSpec(
            "profile_write",
            "整篇覆盖写入用户画像：这是关于用户的长期画像（称呼/偏好/习惯/环境），" +
                "不要写一次性任务信息。先 profile_read 看现有内容，避免覆盖掉还成立的旧内容。",
            Schema.objectOf(
                properties = listOf("text" to Schema.string("画像全文，会覆盖旧内容")),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "profile_read",
            "读取用户画像全文。它已经随每次请求带上，需要原样引用或准备覆盖写入时再读。",
            Schema.objectOf(emptyList()),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "device_info" -> deviceInfo()
        "list_apps" -> listApps(args)
        "open_app" -> openApp(args)
        "open_url" -> openUrl(args)
        "clipboard_read" -> clipboardRead()
        "clipboard_write" -> clipboardWrite(args)
        "memory_write" -> memoryWrite(args)
        "memory_read" -> memoryRead(args)
        "profile_write" -> profileWrite(args)
        "profile_read" -> profileRead()
        else -> ToolResult.failure("未知工具：$name")
    }

    private suspend fun deviceInfo(): ToolResult = withContext(Dispatchers.IO) { readDeviceInfo() }

    private fun readDeviceInfo(): ToolResult = try {
        val metrics = context.resources.displayMetrics
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        val totalGb = stat.totalBytes / 1024.0 / 1024.0 / 1024.0
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).format(Date())
        ToolResult.success(buildString {
            appendLine("厂商/型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("安卓版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("屏幕: ${metrics.widthPixels} x ${metrics.heightPixels}, 密度 ${metrics.densityDpi}dpi")
            appendLine("当前时间: $time")
            if (battery >= 0) appendLine("电量: $battery%")
            appendLine("内部存储: 可用 ${"%.1f".format(freeGb)} GB / 共 ${"%.1f".format(totalGb)} GB")
            appendLine("本应用: ${context.packageName} v$versionName")
        }.trim())
    } catch (error: Exception) {
        ToolResult.failure("读取设备信息失败：${error.message ?: error.javaClass.simpleName}")
    }

    private suspend fun listApps(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = args.str("query")?.trim()?.lowercase()
            val limit = args.int("limit", 80).coerceIn(1, 300)
            val manager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = LinkedHashMap<String, String>()
            for (info in manager.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (seen.containsKey(pkg)) continue
                val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(pkg)
                seen[pkg] = label
            }
            val filtered = seen.entries
                .filter { (pkg, label) ->
                    query.isNullOrEmpty() || label.lowercase().contains(query) || pkg.lowercase().contains(query)
                }
                .sortedBy { it.value }
            if (filtered.isEmpty()) {
                ToolResult.success("没有匹配的应用（共扫描到 ${seen.size} 个可启动应用）")
            } else {
                val text = filtered.take(limit).joinToString("\n") { (pkg, label) -> "$label  ($pkg)" }
                val suffix = if (filtered.size > limit) "\n…共 ${filtered.size} 个，已截断" else ""
                ToolResult.success("共 ${filtered.size} 个：\n$text$suffix")
            }
        } catch (error: Exception) {
            ToolResult.failure("列出应用失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun openApp(args: JsonObject): ToolResult = runCatching {
        val target = args.str("target")?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult.failure("缺少参数 target")
        val manager = context.packageManager
        val direct = manager.getLaunchIntentForPackage(target)
        val intent = direct ?: run {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = manager.queryIntentActivities(launcher, 0).firstOrNull { info ->
                val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault("")
                val pkg = info.activityInfo?.packageName.orEmpty()
                label.equals(target, ignoreCase = true) || label.contains(target, ignoreCase = true) ||
                    pkg.equals(target, ignoreCase = true)
            } ?: return ToolResult.failure("找不到应用「$target」，可以先用 list_apps 查包名")
            manager.getLaunchIntentForPackage(match.activityInfo?.packageName.orEmpty())
                ?: return ToolResult.failure("找到应用但没有启动入口：${match.activityInfo?.packageName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        // The screen is about to belong to another app. There is no touch point to draw a ring
        // around, so the edge flash is the whole signal that the agent did this.
        TouchFx.handoff(context)
        ToolResult.success("已打开 ${intent.component?.packageName ?: target}")
    }.getOrElse { ToolResult.failure("打开应用失败：${it.message ?: it.javaClass.simpleName}") }

    private fun openUrl(args: JsonObject): ToolResult = runCatching {
        val url = args.str("url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.failure("缺少参数 url")
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        // A URL usually lands in a browser, which is the same handoff as launching an app.
        TouchFx.handoff(context)
        ToolResult.success("已打开 $url")
    }.getOrElse { ToolResult.failure("打开网址失败：${it.message ?: it.javaClass.simpleName}") }

    private suspend fun clipboardRead(): ToolResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult.failure("无法访问剪贴板服务")
        val text = onMainThread {
            runCatching {
                val clip = manager.primaryClip
                if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context).toString()
            }.getOrDefault("")
        }
        return if (text.isNullOrEmpty()) {
            ToolResult.failure("剪贴板为空，或本应用不在前台（安卓 10 以上限制）")
        } else {
            ToolResult.success(text)
        }
    }

    private suspend fun clipboardWrite(args: JsonObject): ToolResult {
        val text = args.str("text").orEmpty()
        if (text.isEmpty()) return ToolResult.failure("缺少参数 text")
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult.failure("无法访问剪贴板服务")
        val done = onMainThread {
            runCatching { manager.setPrimaryClip(ClipData.newPlainText("Suixing", text)) }.isSuccess
        }
        return if (done) ToolResult.success("已写入剪贴板（${text.length} 字）") else ToolResult.failure("写入剪贴板失败")
    }

    private suspend fun memoryWrite(args: JsonObject): ToolResult {
        val key = args.str("key")?.trim().orEmpty()
        val value = args.str("value").orEmpty()
        if (key.isEmpty()) return ToolResult.failure("缺少参数 key")
        // Checked here instead of leaving it to the store: `put` can only answer false, and the
        // generic "write failed, check storage" would send the model looking in the wrong place
        // for a note that is merely too long.
        if (value.length > MemoryStore.MAX_VALUE_LENGTH) {
            return ToolResult.failure(
                "内容过长（${value.length}/${MemoryStore.MAX_VALUE_LENGTH} 字），请精简后再记",
            )
        }
        val ok = memory.put(key, value)
        return if (ok) {
            ToolResult.success("已记住「$key」")
        } else {
            ToolResult.failure("写入记忆失败，检查应用存储空间")
        }
    }

    private suspend fun memoryRead(args: JsonObject): ToolResult {
        val key = args.str("key")?.trim()
        val map = memory.entries.value
        if (map.isEmpty()) return ToolResult.success("（还没有任何备忘）")
        if (!key.isNullOrEmpty()) {
            val entry = map[key] ?: return ToolResult.success("没有找到「$key」")
            return ToolResult.success("$key = ${entry.value}")
        }
        return ToolResult.success(
            map.entries.joinToString("\n") { "${it.key} = ${it.value.value}" },
        )
    }

    private suspend fun profileWrite(args: JsonObject): ToolResult {
        val text = args.str("text").orEmpty()
        // Checked here instead of leaving it to the store: `write` can only answer false, and
        // the generic "写入失败" would send the model looking in the wrong place for a profile
        // that is merely too long.
        if (text.length > UserProfileStore.MAX_LENGTH) {
            return ToolResult.failure(
                "内容过长（${text.length}/${UserProfileStore.MAX_LENGTH} 字），请精简后再写",
            )
        }
        val ok = profile.write(text)
        return if (ok) {
            ToolResult.success("已更新用户画像（${text.length} 字）")
        } else {
            ToolResult.failure("写入用户画像失败，检查应用存储空间")
        }
    }

    private suspend fun profileRead(): ToolResult {
        val text = profile.text.value
        return ToolResult.success(text.ifBlank { "（用户画像还是空的）" })
    }

    private suspend fun <T> onMainThread(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val result = runCatching { block() }
            if (continuation.isActive) {
                result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWith(Result.failure(it)) },
                )
            }
        }
    }
}
