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
/** 主线程 Handler：进程内复用，避免每次调用都 new（见 [SystemTools.onMainThread]）。 */
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

class SystemTools(
    private val context: Context,
    private val memory: MemoryStore,
    private val profile: UserProfileStore,
) : ToolGroup() {

    override val group: String = "系统"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "device_info",
            "Device information: model, Android version, screen resolution, current time, battery, " +
                "storage and this app's version.",
            Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            "list_apps",
            "List the launchable apps installed on the phone (name + package); query filters them.",
            Schema.objectOf(
                properties = listOf(
                    "query" to Schema.string("Filter by name or package; optional"),
                    "limit" to Schema.integer("Maximum results, default 80", default = 80, minimum = 1, maximum = 300),
                ),
            ),
        ),
        ToolSpec(
            "open_app",
            "Open an app: by package name first, or by its display name (e.g. WeChat).",
            Schema.objectOf(
                properties = listOf("target" to Schema.string("Package name or app name")),
                required = listOf("target"),
            ),
        ),
        ToolSpec(
            "open_url",
            "Open a URL with the system browser or the matching app.",
            Schema.objectOf(
                properties = listOf("url" to Schema.string("URL to open")),
                required = listOf("url"),
            ),
        ),
        ToolSpec(
            "clipboard_read",
            "Read the clipboard text. Note: on Android 10+ this only works while this app is in the foreground.",
            Schema.objectOf(emptyList()),
        ),
        ToolSpec(
            "clipboard_write",
            "Write to the clipboard — the reliable way to get long or Chinese text into another app's input field.",
            Schema.objectOf(
                properties = listOf("text" to Schema.string("Text to put on the clipboard")),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "memory_write",
            "Save a long-term note to local memory (kept across sessions), e.g. the user's preferences " +
                "or frequently used paths.",
            Schema.objectOf(
                properties = listOf(
                    "key" to Schema.string("Key of the note"),
                    "value" to Schema.string("Note content"),
                ),
                required = listOf("key", "value"),
            ),
        ),
        ToolSpec(
            "memory_read",
            "Read local long-term notes; without key it returns all of them.",
            Schema.objectOf(properties = listOf("key" to Schema.string("Key to read; optional"))),
        ),
        ToolSpec(
            "profile_write",
            "Overwrite the whole user profile: the long-term picture of this user — communication style " +
                "and tone, personality, long-term goals, interests, work and life background, explicit " +
                "preferences and taboos. Do not put one-off task details here. Read it with profile_read " +
                "first so still-valid content is not lost.",
            Schema.objectOf(
                properties = listOf("text" to Schema.string("The whole profile; replaces the old one")),
                required = listOf("text"),
            ),
        ),
        ToolSpec(
            "profile_read",
            "Read the whole user profile. It already rides along with every request; read it when you " +
                "need to quote it verbatim or are about to overwrite it.",
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
        else -> ToolResult.failure("Unknown tool: $name")
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
            appendLine("内部存储: 可用 ${"%.1f".format(Locale.US, freeGb)} GB / 共 ${"%.1f".format(Locale.US, totalGb)} GB")
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
                    query.isNullOrEmpty() || label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)
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

    private suspend fun openApp(args: JsonObject): ToolResult {
        val target = args.str("target")?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult.failure("缺少参数 target")
        // The lookup scans every installed app (queryIntentActivities + loadLabel), which is a
        // visible stall on the main thread; only the launch itself stays there.
        val found = withContext(Dispatchers.IO) { runCatching { resolveLaunchIntent(target) } }
        val intent = found.getOrElse { error ->
            return ToolResult.failure(error.message ?: "打开应用失败：${error.javaClass.simpleName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        // The screen is about to belong to another app. There is no touch point to draw a ring
        // around, so the edge flash is the whole signal that the agent did this.
        TouchFx.handoff(context)
        return ToolResult.success("已打开 ${intent.component?.packageName ?: target}")
    }

    /** PackageManager lookups only, off the main thread; throws when nothing matches. */
    private fun resolveLaunchIntent(target: String): Intent {
        val manager = context.packageManager
        manager.getLaunchIntentForPackage(target)?.let { return it }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = manager.queryIntentActivities(launcher, 0).firstOrNull { info ->
            val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault("")
            val pkg = info.activityInfo?.packageName.orEmpty()
            label.equals(target, ignoreCase = true) || label.contains(target, ignoreCase = true) ||
                pkg.equals(target, ignoreCase = true)
        } ?: throw IllegalStateException("找不到应用「$target」，可以先用 list_apps 查包名")
        return manager.getLaunchIntentForPackage(match.activityInfo?.packageName.orEmpty())
            ?: throw IllegalStateException("找到应用但没有启动入口：${match.activityInfo?.packageName}")
    }

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
        if (!done) return ToolResult.failure("写入剪贴板失败")
        // **读回核验**（issue #44 第一条）：`setPrimaryClip` 不抛异常不代表写进去了 ——
        // 部分 ROM（尤其带剪贴板隐私保护的）会静默丢弃，模型看到"成功"就继续下一步，
        // 用户粘贴出来是旧内容。写完全文读回比对，不一致就如实报出来。
        val readBack = onMainThread {
            runCatching {
                manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            }.getOrNull()
        }
        return when {
            readBack == text -> ToolResult.success("已写入剪贴板（${text.length} 字，已读回校验）")
            readBack == null -> ToolResult.failure(
                "剪贴板写入后读不回内容（写入 ${text.length} 字，读回为空）—— " +
                    "这台设备可能禁止了剪贴板读取，请改用其它方式把文本送过去",
            )
            else -> ToolResult.failure(
                "剪贴板内容与写入不一致（写入 ${text.length} 字，读回 ${readBack.length} 字）—— 写入未生效",
            )
        }
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
        if (!ok) return ToolResult.failure("写入记忆失败，检查应用存储空间")
        // **读回核验**（issue #44 第一条）：`put` 返回 true 只代表写文件成功，
        // 读回来比一次才知道模型下次能不能取到同样的内容。
        val stored = memory.entries.value[key]?.value
        return when {
            stored == value -> ToolResult.success("已记住「$key」（已读回校验）")
            stored == null -> ToolResult.failure("写入记忆后读不回「$key」—— 本次未记住")
            else -> ToolResult.failure(
                "记忆内容与写入不一致（写入 ${value.length} 字，读回 ${stored.length} 字）—— 本次未记住",
            )
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
        // 模型整篇重写时最容易发生的事：没把旧条目抄全 ⇒ 旧信息被抹掉。闸门在 store 里，
        // 这里只把拒绝原因原样交给模型，让它重写一份带着旧条目的完整版。
        val refused = profile.writeGuarded(text)
        if (refused != null) return ToolResult.failure("未写入：$refused")
        // **读回核验**（issue #44 第一条）：画像每轮请求都会带上，写坏了影响的是之后所有对话，
        // 所以这里必须确认存进去的就是刚写的那份，而不是"调用没报错"。
        val stored = profile.text.value
        return if (stored == text) {
            ToolResult.success("已更新用户画像（${text.length} 字，已读回校验）")
        } else {
            ToolResult.failure(
                "画像写入后读回不一致（写入 ${text.length} 字，读回 ${stored.length} 字）—— 本次未生效",
            )
        }
    }

    private suspend fun profileRead(): ToolResult {
        val text = profile.text.value
        return ToolResult.success(text.ifBlank { "（用户画像还是空的）" })
    }

    private suspend fun <T> onMainThread(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        // 主线程 Handler 复用（优化清单 tools-P2）：原来每次调用都 new 一个 Handler。
        mainHandler.post {
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
