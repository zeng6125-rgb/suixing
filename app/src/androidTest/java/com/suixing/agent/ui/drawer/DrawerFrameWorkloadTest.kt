package com.suixing.agent.ui.drawer

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

private const val PANEL_WIDTH_DP = 300f

/** Runs the production drawer in a Release activity after explicitly enabling the workload. */
@RunWith(AndroidJUnit4::class)
class DrawerFrameWorkloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun reversalAndReleaseFrameTimings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jagentDrawerFrames") == "1")
        val records = ArrayList<Frame>()
        val lost = AtomicInteger()
        val windows = ArrayList<SampleWindow>()
        var initialRefreshRate = 0f
        val handlerThread = HandlerThread("drawer-frame-metrics").apply { start() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitSidebar(open = false)
                lateinit var geometry: Geometry
                lateinit var window: Window
                scenario.onActivity { activity ->
                    window = activity.window
                    val decor = window.decorView
                    val location = IntArray(2)
                    decor.getLocationOnScreen(location)
                    initialRefreshRate = decor.display?.refreshRate ?: 0f
                    val density = decor.resources.displayMetrics.density
                    geometry = Geometry(
                        location[0].toFloat(), location[1] + decor.height * 0.42f,
                        decor.width.toFloat(), density, PANEL_WIDTH_DP * density,
                    )
                }
                check(geometry.width >= geometry.panel + 24 * geometry.density) {
                    "The workload needs a visible home strip when the drawer is open."
                }
                repeat(2) {
                    swipe(geometry, opening = true)
                    awaitSidebar(open = true)
                    swipe(geometry, opening = false)
                    awaitSidebar(open = false)
                }

                val listener = Window.OnFrameMetricsAvailableListener { _, value, dropped ->
                    val timestamp = value.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                    val duration = value.getMetric(FrameMetrics.TOTAL_DURATION)
                    val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        value.getMetric(FrameMetrics.DEADLINE) else -1L
                    if (timestamp > 0L && duration > 0L) synchronized(records) {
                        records.add(Frame(timestamp, duration, deadline))
                    }
                    lost.addAndGet(dropped)
                }
                scenario.onActivity {
                    window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                }
                try {
                    repeat(12) {
                        windows += swipe(geometry, opening = true)
                        awaitSidebar(open = true)
                        windows += swipe(geometry, opening = false)
                        awaitSidebar(open = false)
                    }
                } finally {
                    scenario.onActivity { window.removeOnFrameMetricsAvailableListener(listener) }
                }
            }
        } finally {
            handlerThread.quitSafely()
            handlerThread.join(2_000)
            check(!handlerThread.isAlive) { "Frame metrics listener did not finish." }
        }

        val frames = synchronized(records) { records.sortedBy { it.timestamp } }
        val results = Bundle().apply {
            putInt("jagent.drawer.metrics_notifications_dropped", lost.get())
            putFloat("jagent.drawer.initial_refresh_hz", initialRefreshRate)
        }
        for (opening in listOf(true, false)) {
            val samples = windows.filter { it.opening == opening }
            val direction = if (opening) "open" else "close"
            addMetrics(results, "jagent.drawer.$direction.drag", frames,
                samples.map { it.start until it.release })
            addMetrics(results, "jagent.drawer.$direction.release600ms", frames,
                samples.map { it.release until it.end })
        }
        instrumentation.addResults(results)
    }

    private fun swipe(geometry: Geometry, opening: Boolean): SampleWindow {
        val x = geometry.left + if (opening) 40 * geometry.density
            else (geometry.panel + geometry.width) / 2f
        val direction = if (opening) 1f else -1f
        val start = System.nanoTime()
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action,
                x + geometry.panel * fraction * direction, geometry.y, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        pointer(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..24) {
            SystemClock.sleep((down + step * 16L - SystemClock.uptimeMillis()).coerceAtLeast(0))
            val fraction = if (step <= 18) 0.75f * step / 18f else 0.75f - 0.10f * (step - 18) / 6f
            pointer(MotionEvent.ACTION_MOVE, fraction)
        }
        SystemClock.sleep(16)
        val release = System.nanoTime()
        pointer(MotionEvent.ACTION_UP, 0.65f)
        SystemClock.sleep(600)
        return SampleWindow(opening, start, release, release + 600_000_000L)
    }

    private fun awaitSidebar(open: Boolean) {
        val timeout = SystemClock.uptimeMillis() + 5_000
        val targetPackage = instrumentation.targetContext.packageName
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        while (SystemClock.uptimeMillis() < timeout) {
            val root = automation.rootInActiveWindow
            val expected = root?.let {
                try {
                    it.packageName?.toString() == targetPackage &&
                        if (open) containsVisible(it, "设置")
                        else !containsVisible(it, "设置") && containsVisible(it, "打开侧边栏")
                } finally {
                    @Suppress("DEPRECATION")
                    it.recycle()
                }
            }
            if (expected == true) return
            SystemClock.sleep(50)
        }
        error("Sidebar did not reach the expected position (open=$open) on the conversation screen.")
    }

    @Suppress("DEPRECATION")
    private fun containsVisible(node: AccessibilityNodeInfo, label: String): Boolean {
        val matches = node.text?.toString() == label || node.contentDescription?.toString() == label
        if (node.isVisibleToUser && node.isClickable && matches) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (containsVisible(child, label)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun addMetrics(results: Bundle, key: String, frames: List<Frame>, windows: List<LongRange>) {
        val samples = windows.map { window -> frames.filter { it.timestamp in window } }
        check(samples.all { it.size >= 2 }) { "Insufficient frame timing samples for $key." }
        val recorded = samples.flatten()
        val gaps = samples.flatMap { sample ->
            sample.zipWithNext { first, second -> second.timestamp - first.timestamp }.filter { it > 0L }
        }
        check(gaps.isNotEmpty()) { "No nonzero vsync intervals for $key." }
        fun List<Long>.percentileMs(fraction: Double): Double {
            val sorted = sorted()
            return sorted[ceil(sorted.size * fraction).toInt() - 1] / 1_000_000.0
        }
        val durations = recorded.map { it.duration }
        val withDeadline = recorded.filter { it.deadline > 0L }
        results.putInt("$key.frames", recorded.size)
        results.putDouble("$key.duration_p50_ms", durations.percentileMs(0.50))
        results.putDouble("$key.duration_p95_ms", durations.percentileMs(0.95))
        results.putDouble("$key.vsync_gap_p95_ms", gaps.percentileMs(0.95))
        results.putInt("$key.frames_with_deadline", withDeadline.size)
        results.putInt("$key.deadline_misses", withDeadline.count { it.duration >= it.deadline })
    }

    private data class Geometry(val left: Float, val y: Float, val width: Float, val density: Float, val panel: Float)
    private data class SampleWindow(val opening: Boolean, val start: Long, val release: Long, val end: Long)
    private data class Frame(val timestamp: Long, val duration: Long, val deadline: Long)
}
