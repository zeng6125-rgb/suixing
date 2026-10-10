package com.suixing.agent.service

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The only component that can actually look at and touch other apps.
 * Everything it exposes is deliberate: gestures, screenshots, a node dump and
 * text entry. No background automation happens on its own.
 */
class SuixingAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_SEARCH_NODES = 3000

        @Volatile
        private var active: SuixingAccessibilityService? = null

        fun isConnected(): Boolean = active != null

        fun instance(): SuixingAccessibilityService? = active
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        // Gives the effects an accessibility-overlay host: from here on they need no
        // SYSTEM_ALERT_WINDOW permission and MIUI keeps them visible while the app is in the
        // background, which is when the agent is operating another app. See TouchFx.overlay.
        TouchFx.bindService(this)
        runCatching { Core.capabilities.onAccessibilityChanged(true) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        active = null
        // The system destroys the effects' accessibility window with this service; TouchFx has to
        // drop its reference or every later effect silently draws into a dead view.
        TouchFx.unbindService(this)
        runCatching { Core.capabilities.onAccessibilityChanged(false) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        TouchFx.tap(this, x, y)
        return gesture(Path().apply { moveTo(x, y) }, durationMs)
    }

    suspend fun longPress(x: Float, y: Float): Boolean {
        TouchFx.longPress(this, x, y)
        return gesture(Path().apply { moveTo(x, y) }, 700L)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        TouchFx.swipe(this, x1, y1, x2, y2)
        return gesture(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(60L, 5_000L),
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
    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(30L))
            val description = GestureDescription.Builder().addStroke(stroke).build()
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

    fun global(action: Int): Boolean = runCatching { performGlobalAction(action) }.getOrDefault(false)

    fun back(): Boolean = global(GLOBAL_ACTION_BACK)

    fun home(): Boolean = global(GLOBAL_ACTION_HOME)

    fun recents(): Boolean = global(GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean = global(GLOBAL_ACTION_NOTIFICATIONS)

    // -------------------------------------------------------------- text input

    /** Types into the focused editable field. Works for CJK because it does not use key events. */
    fun setText(text: String): Boolean = runCatching {
        val node = focusedEditable() ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }.getOrDefault(false)

    fun appendText(text: String): Boolean = runCatching {
        val node = focusedEditable() ?: return false
        val existing = node.text?.toString().orEmpty()
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }.getOrDefault(false)

    fun clickFocused(): Boolean = runCatching {
        var node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        while (node != null) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        false
    }.getOrDefault(false)

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        return firstEditable(root, 0)
    }

    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = firstEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    // -------------------------------------------------------------- node lookup

    /**
     * Breadth-first search over the active window for a node whose text or
     * content description carries [text]. Bounded so a pathological layout
     * cannot stall the agent.
     */
    fun findNodeByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val needle = text.trim()
        if (needle.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (labelMatches(label, needle, exact) || labelMatches(description, needle, exact)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun labelMatches(label: String, needle: String, exact: Boolean): Boolean {
        if (label.isEmpty()) return false
        return if (exact) label.equals(needle, ignoreCase = true) else label.contains(needle, ignoreCase = true)
    }

    /** Clicks the node, the nearest clickable ancestor, or its centre as a last resort. */
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                if (runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                    // An accessibility click never goes through dispatchGesture, but the user
                    // still asked to see what the agent is touching, so the ring is drawn
                    // from the node's own box instead.
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        TouchFx.tap(this, bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    }
                    return true
                }
                break
            }
            current = current.parent
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    suspend fun clickByText(text: String, exact: Boolean = false): Boolean {
        val node = findNodeByText(text, exact) ?: return false
        return clickNode(node)
    }

    /** Focuses the field that carries [label], waits for the keyboard, then writes [text]. */
    suspend fun typeIntoField(label: String, text: String): Boolean {
        val node = findNodeByText(label, exact = false) ?: return false
        clickNode(node)
        delay(350L)
        if (setText(text)) return true
        val editable = if (node.isEditable) node else firstEditable(node, 0)
        if (editable == null) return false
        return setTextOn(editable, text)
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): Boolean = runCatching {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }.getOrDefault(false)

    // ------------------------------------------------------------------ reading

    fun currentPackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

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
    fun dumpScreen(maxNodes: Int = 140, maxDepth: Int = 26): String {
        val root = rootInActiveWindow ?: return "(当前无法读取窗口内容)"
        val (width, height) = screenSize()
        val builder = StringBuilder()
        builder.append("屏幕: ").append(width).append('x').append(height).append('\n')
        builder.append("包名: ").append(root.packageName?.toString() ?: "未知").append('\n')
        val counter = intArrayOf(0)
        walk(root, 0, maxDepth, maxNodes, builder, counter)
        if (counter[0] == 0) builder.append("(界面上没有可交互元素)")
        if (counter[0] >= maxNodes) builder.append("\n…（节点过多，已截断）")
        return builder.toString()
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        builder: StringBuilder,
        counter: IntArray,
    ) {
        if (depth > maxDepth || counter[0] >= maxNodes) return
        val line = describe(node, counter[0])
        if (line != null) {
            builder.append(line).append('\n')
            counter[0]++
        }
        for (index in 0 until node.childCount) {
            if (counter[0] >= maxNodes) return
            val child = node.getChild(index) ?: continue
            walk(child, depth + 1, maxDepth, maxNodes, builder, counter)
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
    suspend fun screenshotJpeg(maxWidth: Int = 1080, quality: Int = 72): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bytes = runCatching {
                        val buffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        software?.let { bitmap ->
                            val scaled = scaleTo(bitmap, maxWidth)
                            toJpeg(scaled, quality)
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resume(bytes)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            runCatching { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback) }
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
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
