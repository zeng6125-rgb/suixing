package com.suixing.agent.ui.settings

import android.Manifest
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.MainActivity
import com.suixing.agent.core.Core
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class UsageRefreshDeviceWorkloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test
    fun firstUsageAndIncrementalRefreshAtCalendarBoundaries() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jagentUsageRefresh") == "1")
        val context = instrumentation.targetContext
        check(context.packageName == "com.suixing.agent.perf") { "Use the isolated Release performance app." }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        check(Core.settings.update { it.copy(onboardingSeen = true) })
        Core.chats.load()
        check(Core.chats.conversations.value.isEmpty() &&
            File(context.filesDir, "deleted-usage").list().isNullOrEmpty()) { "Use a fresh usage sandbox." }

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val histories = List(12) { ArrayList<StoredMessage>(280) }
        val totals = LongArray(3)
        val ranges = listOf(7, 30, 90)
        val body = "Saved reply with Markdown **text**, 中文 and 🙂. ".repeat(40)

        fun append(chat: Int, serial: Int, daysAgo: Int) {
            val at = today.minusDays(daysAgo.toLong()).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
            val prompt = 190 + chat * 3 + daysAgo + serial % 23
            val completion = 23 + chat % 5 + daysAgo % 11
            val prefix = "usage-$chat-$serial"
            histories[chat] += StoredMessage(id = "$prefix-user", role = Roles.USER, text = "Fixture $serial", timestamp = at)
            histories[chat] += StoredMessage(id = "$prefix-answer", role = Roles.ASSISTANT, text = body,
                timestamp = at, model = "usage-model-${(chat + serial) % 24}", promptTokens = prompt,
                completionTokens = completion, cachedTokens = 8, reasoningTokens = 4)
            for ((index, range) in ranges.withIndex()) {
                if (daysAgo < range) totals[index] += (prompt + completion).toLong()
            }
        }

        suspend fun save(chat: Int) {
            val id = "jagent-usage-fixture-$chat"
            assertEquals(ChatRepository.SaveResult.Written,
                Core.chats.save(Conversation(id = id, title = "Usage fixture $chat", messages = histories[chat])))
        }

        for (chat in histories.indices) {
            for (day in 0 until 120) append(chat, serial = day, daysAgo = day)
            save(chat)
        }
        val standbyId = "jagent-usage-fixture-standby"
        assertEquals(ChatRepository.SaveResult.Written, Core.chats.save(Conversation(
            id = standbyId, title = "Usage fixture standby", messages = listOf(
                StoredMessage(id = "usage-standby-question", role = Roles.USER, text = "Usage fixture standby."),
            ),
        )))
        Core.chats.saveLastOpened(standbyId)

        val result = Bundle()
        fun totalLabel(index: Int) = "${NumberFormat.getIntegerInstance().format(totals[index])} tokens"
        ActivityScenario.launch(MainActivity::class.java).use {
            await("打开侧边栏", click = true)
            await("设置", click = true)
            await("返回")
            val launch = openUsage()
            val first = await(totalLabel(1))
            result.putDouble("jagent.usage.first_open_30_days_ms", (first - launch) / 1_000_000.0)
            val samples = List(3) { ArrayList<Long>(6) }
            repeat(6) { round ->
                for ((index, range) in ranges.withIndex()) {
                    val before = totalLabel(index)
                    await("近 $range 天", click = true)
                    await(before)
                    val chat = (round * ranges.size + index) % histories.size
                    for ((offset, day) in listOf(6, 29, 89, 90).withIndex()) {
                        append(chat, serial = 120 + round * ranges.size * 4 + index * 4 + offset, daysAgo = day)
                    }
                    save(chat)
                    val refresh = await("刷新用量", click = true)
                    val displayed = await(totalLabel(index))
                    samples[index].add(displayed - refresh)
                }
            }
            for ((index, range) in ranges.withIndex()) {
                val intervals = samples[index].sorted()
                val prefix = "jagent.usage.refresh_${range}_days"
                result.putInt("$prefix.samples", intervals.size)
                result.putDouble("$prefix.p50_ms", intervals[ceil(intervals.size * 0.5).toInt() - 1] / 1_000_000.0)
                result.putDouble("$prefix.p95_ms", intervals[ceil(intervals.size * 0.95).toInt() - 1] / 1_000_000.0)
            }
            for ((index, range) in ranges.withIndex()) {
                await("近 $range 天", click = true)
                await(totalLabel(index))
                result.putLong("jagent.usage.final_${range}_day_tokens", totals[index])
            }
        }
        instrumentation.addResults(result)
    }

    private fun openUsage(): Long {
        repeat(8) {
            query { find(it, "用量", click = true) }?.let { return it }
            query { root -> perform(root) { it.isVisibleToUser && it.isScrollable && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } }
            SystemClock.sleep(250)
        }
        return await("用量", click = true)
    }

    private fun await(label: String, click: Boolean = false): Long {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            query { find(it, label, click) }?.let { return it }
            SystemClock.sleep(16)
        }
        error("No visible node for '$label' (click=$click).")
    }

    @Suppress("DEPRECATION")
    private fun <T> query(search: (AccessibilityNodeInfo) -> T): T? {
        val root = automation.rootInActiveWindow ?: return null
        return try { search(root) } finally { root.recycle() }
    }

    private fun find(root: AccessibilityNodeInfo, label: String, click: Boolean): Long? {
        var timestamp: Long? = null
        perform(root) { node ->
            val matches = node.text?.toString() == label || node.contentDescription?.toString() == label
            if (!node.isVisibleToUser || !matches || (click && (!node.isEnabled || !node.isClickable))) return@perform false
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return@perform false
            val now = SystemClock.elapsedRealtimeNanos()
            if (click && !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@perform false
            timestamp = now
            true
        }
        return timestamp
    }

    @Suppress("DEPRECATION")
    private fun perform(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (match(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (perform(child, match)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}
