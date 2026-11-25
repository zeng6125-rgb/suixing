package com.suixing.agent.ui.terminal

import android.Manifest
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.MainActivity
import com.suixing.agent.core.Core
import com.suixing.agent.service.TerminalForegroundService.Companion.OPEN_TERMINAL
import com.suixing.agent.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class TerminalFrameWorkloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test
    fun pacedPtyOutputAndHistoryScroll() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jagentTerminalFrames") == "1")
        val context = instrumentation.targetContext
        check(context.packageName == "com.suixing.agent.perf") { "Use the isolated Release performance app." }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        check(Core.settings.update { it.copy(onboardingSeen = true) })
        val manager = Core.terminals
        manager.load()
        check(manager.entries.value.isEmpty()) { "Use an empty terminal sandbox." }

        val frames = ArrayList<Frame>()
        val losses = AtomicInteger()
        val outputWindows = ArrayList<LongRange>()
        val scrollWindows = ArrayList<LongRange>()
        val handlerThread = HandlerThread("terminal-frame-metrics").apply { start() }
        val result = Bundle()
        val intent = Intent(context, MainActivity::class.java).putExtra(OPEN_TERMINAL, true)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val entry = withTimeout(10_000) { manager.entries.first { it.singleOrNull()?.session != null }.single() }
                val session = checkNotNull(entry.session)
                try {
                    lateinit var window: Window
                    lateinit var view: TerminalView
                    val bounds = Rect()
                    withTimeout(5_000) {
                        while (bounds.isEmpty) {
                            scenario.onActivity { activity ->
                                window = activity.window
                                findTerminal(window.decorView)?.let {
                                    view = it
                                    if (view.isShown && view.getLocalVisibleRect(bounds)) {
                                        val location = IntArray(2)
                                        view.getLocationOnScreen(location)
                                        bounds.offset(location[0], location[1])
                                    }
                                }
                            }
                            if (bounds.isEmpty) delay(25)
                        }
                    }
                    check(bounds.height() >= 120)
                    // Disable line echo and synchronize on an actual shell reply. The marker is
                    // split in the command so the echoed command cannot satisfy the wait.
                    session.write("stty -echo; printf '%s%s\\n' 'JAGENT-' 'READY'\r", null)
                    var cursor = readUntil(session, null, "JAGENT-READY").cursor
                    scenario.onActivity {
                        result.putFloat("jagent.terminal.initial_refresh_hz", view.display?.refreshRate ?: 0f)
                    }
                    val listener = Window.OnFrameMetricsAvailableListener { _, metric, dropped ->
                        val timestamp = metric.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                        val duration = metric.getMetric(FrameMetrics.TOTAL_DURATION)
                        val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            metric.getMetric(FrameMetrics.DEADLINE) else -1L
                        if (timestamp > 0 && duration > 0) {
                            synchronized(frames) { frames += Frame(timestamp, duration, deadline) }
                        }
                        losses.addAndGet(dropped)
                    }
                    scenario.onActivity {
                        window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                    }
                    try {
                        repeat(3) { run ->
                            val begin = "JAGENT-BEGIN-$run"
                            val marker = "JAGENT-DONE-$run"
                            val start = System.nanoTime()
                            session.write(outputCommand(run), null)
                            val output = readUntil(session, cursor, marker)
                            val finished = System.nanoTime()
                            check(finished - start >= 5_500_000_000L) { "The shell workload finished without pacing." }
                            outputWindows += start..finished
                            val measuredOutput = output.text.substringAfter(begin, "").substringBefore(marker)
                                .trim('\r', '\n').lines()
                            val expected = (1..120).flatMap {
                                listOf(
                                    String.format(Locale.ROOT, "jagent %03d 中文🙂", it),
                                    "\u001b[36m状态🙂 完成\u001b[0m",
                                    "\u001b[1m预览界面\u001b[0m",
                                )
                            }
                            check(measuredOutput == expected) { "Unexpected numbered or styled PTY output." }
                            withTimeout(5_000) {
                                var transcript = session.transcript()
                                while (!transcript.contains(marker)) {
                                    delay(25)
                                    transcript = session.transcript()
                                }
                                check(transcript.contains("jagent 120 中文🙂")) { "The last numbered line was not rendered." }
                            }
                            cursor = output.cursor
                            delay(250)
                        }
                        repeat(6) {
                            var before = 0
                            scenario.onActivity { before = view.topRow }
                            scrollWindows += dragIntoHistory(bounds)
                            scenario.onActivity {
                                check(view.topRow < before) { "The swipe did not move the terminal into older output." }
                            }
                            delay(160)
                        }
                    } finally {
                        scenario.onActivity { window.removeOnFrameMetricsAvailableListener(listener) }
                    }
                } finally {
                    val id = entry.info.id
                    manager.stop(id)
                    withTimeout(5_000) {
                        manager.entries.first { entries -> entries.none { it.info.id == id && it.info.phase == "running" } }
                    }
                    manager.remove(id)
                }
            }
        } finally {
            handlerThread.quitSafely()
            handlerThread.join(2_000)
            check(!handlerThread.isAlive) { "Frame metrics listener did not finish." }
        }
        val recorded = synchronized(frames) { frames.sortedBy { it.timestamp } }
        result.putInt("jagent.terminal.metrics_notifications_dropped", losses.get())
        record(result, "jagent.terminal.output", recorded, outputWindows)
        record(result, "jagent.terminal.scroll", recorded, scrollWindows)
        instrumentation.addResults(result)
    }

    private fun outputCommand(run: Int): String =
        "printf '%s%s\\n' 'JAGENT-' 'BEGIN-$run'; " +
            "i=1; while [ \"\$i\" -le 120 ]; do " +
            "printf 'jagent %03d 中文🙂\\n' \"\$i\"; " +
            "printf '\\033[36m状态🙂 完成\\033[0m\\n\\033[1m预览界面\\033[0m\\n' ; " +
            "i=\$((i+1)); sleep 0.05; done; printf '%s%s\\n' 'JAGENT-' 'DONE-$run'\r"

    private suspend fun readUntil(session: TerminalSession, after: Long?, marker: String): Captured =
        withTimeout(25_000) {
            val text = StringBuilder()
            var cursor = after
            while (marker !in text) {
                val part = session.read(cursor, 8192)
                check(!part.truncated) { "The workload's PTY output was truncated." }
                text.append(part.output)
                cursor = part.cursor
                if (marker !in text) delay(25)
            }
            Captured(checkNotNull(cursor), text.toString())
        }

    private fun findTerminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findTerminal(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun dragIntoHistory(bounds: Rect): LongRange {
        val x = bounds.exactCenterX()
        val from = bounds.top + bounds.height() * 0.25f
        val to = bounds.top + bounds.height() * 0.72f
        val now = System.nanoTime()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true)) { "Terminal touch injection failed." }
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..18) {
            SystemClock.sleep(12)
            send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 18)
        }
        SystemClock.sleep(65)
        send(MotionEvent.ACTION_UP, to)
        return now..System.nanoTime()
    }

    private fun record(results: Bundle, key: String, frames: List<Frame>, windows: List<LongRange>) {
        val samples = windows.map { window -> frames.filter { it.timestamp in window } }
        check(samples.all { it.size >= 2 }) { "Insufficient frame timing samples for $key." }
        val recorded = samples.flatten()
        val gaps = samples.flatMap { sample -> sample.zipWithNext { a, b -> b.timestamp - a.timestamp } }.filter { it > 0 }
        fun List<Long>.p95Ms(): Double = sorted()[ceil(size * 0.95).toInt() - 1] / 1_000_000.0
        val withDeadline = recorded.filter { it.deadline > 0 }
        results.putInt("$key.frames", recorded.size)
        results.putDouble("$key.duration_p95_ms", recorded.map { it.duration }.p95Ms())
        results.putDouble("$key.vsync_gap_p95_ms", gaps.p95Ms())
        results.putInt("$key.frames_with_deadline", withDeadline.size)
        results.putInt("$key.deadline_misses", withDeadline.count { it.duration >= it.deadline })
    }

    private data class Captured(val cursor: Long, val text: String)
    private data class Frame(val timestamp: Long, val duration: Long, val deadline: Long)
}
