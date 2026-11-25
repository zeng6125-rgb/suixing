package com.suixing.agent.ui.files

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.MainActivity
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class FileListDeviceWorkloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test
    fun externalWorkspaceDirectoryClickToFirstFile() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jagentFileList") == "1")
        val workspace = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)) {
            "The workload needs mounted app-specific external storage."
        }
        val root = Files.createTempDirectory(workspace.toPath(), "!jagent-file-").toFile()
        try {
            val directories = listOf(seed(root, 511), seed(root, 7700))
            val results = Bundle()
            ActivityScenario.launch(MainActivity::class.java).use {
                await("打开侧边栏", click = true)
                await("文件", click = true)
                await(root.name, click = true)

                val firstOpen = directories.associateWith { openAndReturn(it) }
                val repeats = directories.associateWith { ArrayList<Long>(12) }
                repeat(12) { iteration ->
                    val order = if (iteration % 2 == 0) directories else directories.asReversed()
                    for (directory in order) repeats.getValue(directory).add(openAndReturn(directory))
                }
                for (directory in directories) {
                    val key = "jagent.files.external.entries_${directory.count}"
                    val samples = repeats.getValue(directory)
                    results.putDouble("$key.first_open_ms", firstOpen.getValue(directory) / 1_000_000.0)
                    results.putInt("$key.repeats", samples.size)
                    results.putDouble("$key.p50_ms", samples.percentileMs(0.50))
                    results.putDouble("$key.p95_ms", samples.percentileMs(0.95))
                }
            }
            instrumentation.addResults(results)
        } finally {
            check(root.deleteRecursively()) { "Could not remove workload directory ${root.name}." }
        }
    }

    private fun seed(root: File, count: Int): Directory {
        val directory = File(root, "entries-$count").apply { check(mkdir()) }
        val firstDirectory = "d-$count"
        check(File(directory, firstDirectory).mkdir())
        val prefix = "f-$count-"
        for (index in 0 until count - 1) {
            val name = prefix + index.toString().padStart(5, '0') + ".txt"
            File(directory, name).writeText("jagent-workload")
        }
        return Directory(directory.name, count, firstDirectory, prefix + "00000.txt")
    }

    private fun openAndReturn(directory: Directory): Long {
        val started = await(directory.name, click = true)
        val firstFile = await(directory.firstFile)
        await(directory.firstDirectory)
        await("上一级", click = true)
        await(directory.firstFile, present = false)
        await(directory.name)
        return firstFile - started
    }

    private fun await(label: String, click: Boolean = false, present: Boolean = true): Long {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val timestamp = automation.rootInActiveWindow?.let { root ->
                try {
                    val found = find(root, label, click)
                    if (present) found else if (found == null) SystemClock.elapsedRealtimeNanos() else null
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
            if (timestamp != null) return timestamp
            SystemClock.sleep(16)
        }
        error("The visible node for '$label' did not reach the requested state (present=$present, click=$click).")
    }

    @Suppress("DEPRECATION")
    private fun find(node: AccessibilityNodeInfo, label: String, click: Boolean): Long? {
        val matches = node.text?.toString() == label || node.contentDescription?.toString() == label
        if (matches && node.isVisibleToUser && (!click || node.isClickable)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val now = SystemClock.elapsedRealtimeNanos()
                if (!click || node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return now
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                find(child, label, click)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun List<Long>.percentileMs(fraction: Double): Double =
        sorted()[ceil(size * fraction).toInt() - 1] / 1_000_000.0

    private data class Directory(val name: String, val count: Int, val firstDirectory: String, val firstFile: String)
}
