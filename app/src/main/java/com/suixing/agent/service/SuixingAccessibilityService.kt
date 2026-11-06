package com.suixing.agent.service

import android.os.SystemClock
import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.suixing.agent.core.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The only component that can actually look at and touch other apps.
 * Everything it exposes is deliberate: gestures, screenshots, a node dump and
 * text entry. No background automation happens on its own.
 */
class SuixingAccessibilityService : AccessibilityService() {

    private val screenshotJob = SupervisorJob()
    private val screenshotScope = CoroutineScope(screenshotJob + Dispatchers.Default)

    companion object {
        private const val MAX_SEARCH_NODES = 3000

        /**
         * `dumpScreen` 的整树遍历上限：访问节点数与时间预算。
         *
         * 与 [MAX_SEARCH_NODES] 分开 —— 那是「查找」的预算，这里是「遍历」的预算。
         * 正常页面（几十到几百节点）远用不到；它只兜住装饰容器极多或 WebView 的极端树，
         * 避免 `ui_dump` 出现秒级延迟。
         */
        private const val DUMP_MAX_VISITS = 3000
        private const val DUMP_TIME_BUDGET_MS = 800L
        private const val TAG = "SuixingA11y"

        @Volatile
        private var active: SuixingAccessibilityService? = null

        fun isConnected(): Boolean = active != null

        fun instance(): SuixingAccessibilityService? = active
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Core.startup.backgroundStarting()
        active = this
        // Gives the effects an accessibility-overlay host: from here on they need no
        // SYSTEM_ALERT_WINDOW permission and MIUI keeps them visible while the app is in the
        // background, which is when the agent is operating another app. See TouchFx.overlay.
        TouchFx.bindService(this)
        runCatching { Core.capabilities.onAccessibilityChanged(true) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        screenshotJob.cancelChildren()
        active = null
        // The system destroys the effects' accessibility window with this service; TouchFx has to
        // drop its reference or every later effect silently draws into a dead view.
        TouchFx.unbindService(this)
        runCatching { Core.capabilities.onAccessibilityChanged(false) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        screenshotJob.cancel()
        active = null
        TouchFx.unbindService(this)
        runCatching { Core.capabilities.onAccessibilityChanged(false) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- gestures

    // Each of these draws itself before dispatching: the effect is the only sign the user
    // gets that the agent is touching their screen rather than sitting there.

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60L, displayId: Int = -1): Boolean {
        // 副屏上的点击不在主屏留痕：TouchFx 画的是主屏坐标，指错屏比不画更容易误解。
        if (displayId < 0) TouchFx.tap(this, x, y)
        return gesture(Path().apply { moveTo(x, y) }, durationMs, displayId)
    }

    suspend fun longPress(x: Float, y: Float, displayId: Int = -1): Boolean {
        if (displayId < 0) TouchFx.longPress(this, x, y)
        return gesture(Path().apply { moveTo(x, y) }, 700L, displayId)
    }

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
        displayId: Int = -1,
    ): Boolean {
        if (displayId < 0) TouchFx.swipe(this, x1, y1, x2, y2)
        return gesture(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(60L, 5_000L),
            displayId,
        )
    }

    /**
     * Dispatches the gesture and waits for the system to say it finished.
     *
     * `dispatchGesture` returning true means only that the gesture was *accepted* —
     * the touch events are delivered over the following [durationMs]. Reporting
     * success at that point lets the next step read the screen before this tap has
     * landed, which shows up as 「点了没反应」 followed by a redundant second tap.
     * Waiting also serialises gestures: a second one dispatched while the first is in
     * flight is rejected by the system, and now it simply cannot happen.
     */
    private suspend fun gesture(path: Path, durationMs: Long, displayId: Int = -1): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(durationMs.coerceAtLeast(30L) + 2_000L) {
        suspendCancellableCoroutine { continuation ->
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(30L))
            val builder = GestureDescription.Builder().addStroke(stroke)
            // 副屏注入：-1 表示主屏，保持原路径不变；≥0 时按 displayId 投递（API 30+）。
            if (displayId >= 0) builder.setDisplayId(displayId)
            val description = builder.build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val dispatched = runCatching { dispatchGesture(description, callback, null) }.getOrDefault(false)
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
        } ?: false

    fun global(action: Int): Boolean = runCatching { performGlobalAction(action) }.getOrDefault(false)

    fun back(): Boolean = global(GLOBAL_ACTION_BACK)

    fun home(): Boolean = global(GLOBAL_ACTION_HOME)

    fun recents(): Boolean = global(GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean = global(GLOBAL_ACTION_NOTIFICATIONS)

    // -------------------------------------------------------------- text input

    /** Types into the focused editable field. Works for CJK because it does not use key events. */
    fun setText(text: String, displayId: Int = -1): Boolean = runCatching {
        val node = focusedEditable(displayId) ?: return false
        try { setTextOn(node, text) } finally { node.recycleIfLegacy() }
    }.getOrDefault(false)

    fun appendText(text: String, displayId: Int = -1): Boolean = runCatching {
        val node = focusedEditable(displayId) ?: return false
        try { setTextOn(node, node.text?.toString().orEmpty() + text) }
        finally { node.recycleIfLegacy() }
    }.getOrDefault(false)

    fun clickFocused(): Boolean = runCatching {
        var node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        try {
            while (node != null) {
                if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val parent = node.parent
                node.recycleIfLegacy()
                node = parent
            }
            false
        } finally { node?.recycleIfLegacy() }
    }.getOrDefault(false)

    private fun focusedEditable(displayId: Int = -1): AccessibilityNodeInfo? {
        // 副屏走 `windows` 筛窗口，主屏才用 `rootInActiveWindow`（issue #80，同 findNodeByText）。
        val root = if (displayId >= 0) windowRootOn(displayId) else rootInActiveWindow ?: return null
        var result: AccessibilityNodeInfo? = null
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                result = focused
                return result
            }
            if (focused !== root) focused?.recycleIfLegacy()
            result = firstEditable(root, 0)
            return result
        } finally { if (result !== root) root.recycleIfLegacy() }
    }

    /** The caller owns node; ownership of only the returned child is transferred. */
    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int, remaining: IntArray = intArrayOf(MAX_SEARCH_NODES)): AccessibilityNodeInfo? {
        if (depth > 30 || remaining[0]-- <= 0) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            if (remaining[0] <= 0) break
            val child = node.getChild(index) ?: continue
            var found: AccessibilityNodeInfo? = null
            try {
                found = firstEditable(child, depth + 1, remaining)
                if (found != null) return found
            } finally { if (found !== child) child.recycleIfLegacy() }
        }
        return null
    }

    // -------------------------------------------------------------- node lookup

    /**
     * Breadth-first search over the active window for a node whose text or
     * content description carries [text]. Bounded so a pathological layout
     * cannot stall the agent.
     */
    fun findNodeByText(text: String, exact: Boolean = false, displayId: Int = -1): AccessibilityNodeInfo? {
        val needle = text.trim()
        if (needle.isEmpty()) return null
        // **副屏必须走 `windows`**：`rootInActiveWindow` 只给当前有焦点的那一屏，副屏上的
        // 应用永远拿不到 ⇒ 按文字点击/输入在虚拟屏上从来不会命中（issue #80）。
        val root = if (displayId >= 0) windowRootOn(displayId) else rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            // 命中就交出去：**不能回收**，调用方还要拿它去点击。
            if (labelMatches(label, needle, exact) || labelMatches(description, needle, exact)) {
                while (queue.isNotEmpty()) queue.removeFirst().recycleIfLegacy()
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.addLast(it) }
            }
            // 没命中、子节点也已经入队：这个实例用完就还（issue #9）。API 33 起 recycle 是空操作，
            // 旧设备上才是真的释放 —— 所以按版本判断，避免在新系统上调用已废弃的 API。
            node.recycleIfLegacy()
        }
        // 提前收尾（节点数上限）时队列里还剩一堆，一并还掉。
        while (queue.isNotEmpty()) queue.removeFirst().recycleIfLegacy()
        return null
    }

    /**
     * API < 33 才需要手动回收 `AccessibilityNodeInfo`；33 起系统自己管理，调用已废弃的
     * `recycle()` 没有意义（也容易在后续版本变成异常）。issue #9 第 2 条。
     */
    private fun AccessibilityNodeInfo.recycleIfLegacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        runCatching { recycle() }
    }

    private fun labelMatches(label: String, needle: String, exact: Boolean): Boolean {
        if (label.isEmpty()) return false
        return if (exact) label.equals(needle, ignoreCase = true) else label.contains(needle, ignoreCase = true)
    }

    /** Clicks the node, the nearest clickable ancestor, or its centre as a last resort. */
    suspend fun clickNode(node: AccessibilityNodeInfo, displayId: Int = -1): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        var current: AccessibilityNodeInfo? = node
        try {
            while (current != null) {
                if (current.isClickable) {
                    val candidate = current
                    if (runCatching { candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                        // 触摸特效（TouchFx）画在主屏的悬浮层上，副屏的点击不该在主屏闪一下。
                        if (displayId < 0 && bounds.width() > 0 && bounds.height() > 0) {
                            TouchFx.tap(this, bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        }
                        return true
                    }
                    break
                }
                val parent = current.parent
                if (current !== node) current.recycleIfLegacy()
                current = parent
            }
        } finally { if (current !== node) current?.recycleIfLegacy() }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), displayId = displayId)
    }

    suspend fun clickByText(text: String, exact: Boolean = false, displayId: Int = -1): Boolean {
        val node = findNodeByText(text, exact, displayId) ?: return false
        return try { clickNode(node, displayId) } finally { node.recycleIfLegacy() }
    }

    suspend fun typeIntoField(label: String, text: String, displayId: Int = -1): Boolean {
        val node = findNodeByText(label, exact = false, displayId = displayId) ?: return false
        try { clickNode(node, displayId) } finally { node.recycleIfLegacy() }
        delay(350L)
        // Clicking may replace the entire window. Query its current focused/editable node once.
        if (setText(text, displayId)) return true
        // 兜底（issue #56 R10）：点击没把焦点落到可编辑框上（点在 label / 容器、WebView 内、
        // 焦点异步到位）时，原本到此直接返回 false，能力比改动前更弱。现在在**重新取的树**里
        // 找同 label 节点的可编辑子树 —— 不复用点击前的旧节点，免得重犯「用旧树」那类错误。
        val fresh = findNodeByText(label, exact = false, displayId = displayId) ?: return false
        try {
            val editable = firstEditable(fresh, 0) ?: return false
            try { return setTextOn(editable, text) } finally { editable.recycleIfLegacy() }
        } finally {
            fresh.recycleIfLegacy()
        }
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean = runCatching {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }.getOrDefault(false)

    // ------------------------------------------------------------------ reading

    fun currentPackage(): String? = runCatching {
        rootInActiveWindow?.let { node ->
            try { node.packageName?.toString() } finally { node.recycleIfLegacy() }
        }
    }.getOrNull()

    /**
     * 副屏上最上层的那个应用。
     *
     * `rootInActiveWindow` 只代表**当前有焦点**的那一屏，用它问副屏会得到主屏的答案。
     * 这里从 `windows` 里按 `displayId` 筛，取第一个非系统装饰窗口的包名 —— 与
     * [windowRootOn] 的筛法一致，免得"读屏读到 A、问应用却答 B"。
     */
    fun currentPackageOn(displayId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            windows.firstOrNull { window ->
                window.displayId == displayId &&
                    window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }?.root?.let { root ->
                try { root.packageName?.toString() } finally { root.recycleIfLegacy() }
            }
        }.getOrNull()
    }

    fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Flattens the visible window into compact lines the model can reason about.
     * Only nodes a human could interact with are kept, otherwise the dump drowns
     * in layout containers.
     *
     * The screen size leads the dump because every coordinate in it is absolute: the
     * model cannot aim a swipe at empty space — scrolling a list, for instance —
     * without knowing how large the screen it is aiming at actually is.
     */
    fun dumpScreen(maxNodes: Int = 140, maxDepth: Int = 26, displayId: Int = -1): String {
        val root = if (displayId >= 0) windowRootOn(displayId) else rootInActiveWindow
        if (root == null) {
            return if (displayId >= 0) "(副屏上没有可读窗口)" else "(当前无法读取窗口内容)"
        }
        val (width, height) = screenSize()
        val builder = StringBuilder()
        builder.append("屏幕: ").append(width).append('x').append(height).append('\n')
        val windowBounds = Rect().also { root.getBoundsInScreen(it) }
        builder.append("活动窗口 bounds=(").append(windowBounds.left).append(',').append(windowBounds.top)
            .append(',').append(windowBounds.right).append(',').append(windowBounds.bottom).append(")；坐标为屏幕绝对坐标\n")
        builder.append("包名: ").append(root.packageName?.toString() ?: "未知").append('\n')
        val counter = intArrayOf(0)
        // 访问计数与时间预算（issue #57 §2）：`counter` 只在 `describe` 返回非空时递增，
        // 「有趣的少、总数多」的页面（装饰容器堆叠、WebView）会一路遍历到 maxDepth，
        // 而每个没命中预取缓存的 `getChild` 可能是一次跨进程调用。这里给整棵树加硬闸。
        val visits = intArrayOf(0)
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + DUMP_TIME_BUDGET_MS
        try { walk(root, 0, maxDepth, maxNodes, builder, counter, visits, deadline) }
        finally { root.recycleIfLegacy() }
        if (counter[0] == 0) builder.append("(界面上没有可交互元素)")
        val cost = SystemClock.uptimeMillis() - startedAt
        val timedOut = SystemClock.uptimeMillis() >= deadline
        val visitCapped = visits[0] >= DUMP_MAX_VISITS
        when {
            counter[0] >= maxNodes -> builder.append("\n…（节点过多，已截断）")
            timedOut -> builder.append("\n…（超时，已截断）")
            visitCapped -> builder.append("\n…（访问节点过多，已截断）")
        }
        Log.d(TAG, "dumpScreen pkg=${root.packageName} lines=${counter[0]} visits=${visits[0]} cost=${cost}ms timedOut=$timedOut visitCapped=$visitCapped")
        return builder.toString()
    }

    /**
     * 副屏上那个窗口的根节点。
     *
     * `rootInActiveWindow` 只给**当前有焦点**的那一屏，副屏上的应用永远拿不到，所以副屏
     * 走 `windows`：这是 `flagRetrieveInteractiveWindows` 打开的窗口列表，按 `displayId`
     * 筛出目标屏、丢掉系统装饰窗口（状态栏/输入法），剩下的就是屏上的应用窗口。
     */
    private fun windowRootOn(displayId: Int): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val candidates = runCatching { windows }.getOrNull().orEmpty()
        for (window in candidates) {
            if (window.displayId != displayId) continue
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            return root
        }
        return null
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        builder: StringBuilder,
        counter: IntArray,
        visits: IntArray,
        deadline: Long,
    ) {
        if (depth > maxDepth || counter[0] >= maxNodes) return
        // 每个访问到的节点都计数并查时间预算 —— 这是原来唯一缺的闸（有没有输出都算访问）。
        if (visits[0]++ >= DUMP_MAX_VISITS || SystemClock.uptimeMillis() >= deadline) return
        val line = describe(node, counter[0])
        if (line != null) {
            builder.append(line).append('\n')
            counter[0]++
        }
        for (index in 0 until node.childCount) {
            if (counter[0] >= maxNodes || visits[0] >= DUMP_MAX_VISITS) return
            if (SystemClock.uptimeMillis() >= deadline) return
            val child = node.getChild(index) ?: continue
            try { walk(child, depth + 1, maxDepth, maxNodes, builder, counter, visits, deadline) }
            finally { child.recycleIfLegacy() }
        }
    }

    private fun describe(node: AccessibilityNodeInfo, index: Int): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val editable = node.isEditable
        val interesting = text.isNotEmpty() || description.isNotEmpty() ||
            node.isClickable || node.isScrollable || editable || node.isCheckable
        if (!interesting) return null

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        val builder = StringBuilder()
        builder.append('[').append(index).append("] ")
        builder.append(node.className?.toString()?.substringAfterLast('.') ?: "View")
        if (text.isNotEmpty()) builder.append(" text=\"").append(text.take(120)).append('"')
        if (description.isNotEmpty() && description != text) {
            builder.append(" desc=\"").append(description.take(80)).append('"')
        }
        builder.append(" center=(").append(bounds.centerX()).append(',').append(bounds.centerY()).append(')')
        builder.append(" bounds=(").append(bounds.left).append(',').append(bounds.top)
            .append(',').append(bounds.right).append(',').append(bounds.bottom).append(')')
        val flags = ArrayList<String>(4)
        if (node.isClickable) flags.add("clickable")
        if (node.isScrollable) flags.add("scrollable")
        if (editable) flags.add("editable")
        if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "unchecked")
        if (flags.isNotEmpty()) builder.append(' ').append(flags.joinToString(","))
        node.viewIdResourceName?.substringAfterLast('/')?.let { if (it.isNotEmpty()) builder.append(" id=").append(it) }
        return builder.toString()
    }

    // -------------------------------------------------------------- screenshots

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshotJpeg(
        maxWidth: Int = 1080,
        quality: Int = 72,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // **整段解码/缩放/压缩挪到后台线程**（优化清单 tools-P0）：
                    // 回调注册在 `mainExecutor` 上，原来这一整串（wrapHardwareBuffer →
                    // copy(ARGB_8888) → createScaledBitmap → JPEG 压缩）都在**主线程**跑，
                    // 一张全尺寸位图就是几十毫秒，期间 UI 完全不动。
                    // ScreenshotResult 的 hardwareBuffer 在 close 之前有效，可以跨线程使用。
                    val buffer = screenshot.hardwareBuffer
                    // A cancelled launch may never enter its body: completion owns the buffer
                    // from the callback onward, including cancellation before dispatch.
                    val work = screenshotScope.launch {
                        val bytes = runCatching {
                            var wrapped: Bitmap? = null
                            var software: Bitmap? = null
                            var scaled: Bitmap? = null
                            try {
                                if (!continuation.isActive) return@runCatching null
                                wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                software?.let { bitmap ->
                                    scaled = scaleTo(bitmap, maxWidth.coerceAtLeast(1))
                                    toJpeg(scaled!!, quality)
                                }
                            } finally {
                                if (scaled !== software) scaled?.recycle()
                                software?.recycle()
                                wrapped?.recycle()
                            }
                        }.getOrNull()
                        if (continuation.isActive) continuation.resume(bytes)
                    }
                    continuation.invokeOnCancellation { work.cancel() }
                    work.invokeOnCompletion {
                        buffer.close()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            runCatching { takeScreenshot(displayId, mainExecutor, callback) }
                .onFailure { if (continuation.isActive) continuation.resume(null) }
        }

    private fun scaleTo(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    }

    private fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), stream)) { "JPEG compression failed" }
        return stream.toByteArray()
    }
}
