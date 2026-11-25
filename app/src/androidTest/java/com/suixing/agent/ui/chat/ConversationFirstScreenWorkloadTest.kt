package com.suixing.agent.ui.chat

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.MainActivity
import com.suixing.agent.core.Core
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class ConversationFirstScreenWorkloadTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test
    fun coldConversationAndWarmSwitch() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("jagentConversationFrames") == "1")
        val packageName = instrumentation.targetContext.packageName
        check(packageName == "com.suixing.agent.perf") {
            "Seed conversations only in the isolated Release app (com.suixing.agent.perf)."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            automation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        check(Core.settings.update { it.copy(onboardingSeen = true) })
        val standby = Fixture("standby", 1)
        val targets = listOf(Fixture("alpha", 64), Fixture("beta", 64))
        Core.chats.load()
        check(Core.chats.conversations.value.isEmpty()) { "Use an empty performance sandbox." }
        val seeded = ArrayList<Fixture>(3)
        val cold = ArrayList<Sample>(16)
        val warm = ArrayList<Sample>(48)
        try {
            for (fixture in listOf(standby) + targets) {
                seeded += fixture
                assertEquals(ChatRepository.SaveResult.Written, Core.chats.save(fixture.conversation()))
            }
            // Reload the index as metadata only before opening through the actual drawer.
            Core.chats.load()
            Core.chats.saveLastOpened(standby.id)
            repeat(8) { round ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: ChatViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[ChatViewModel::class.java] }
                    await(standby.lastAnswer)
                    val order = if (round % 2 == 0) targets else targets.asReversed()
                    for (target in order) cold += switchTo(model, target)
                    repeat(3) {
                        for (target in order) warm += switchTo(model, target)
                    }
                    // End each activity on the standby session because disposal saves its id.
                    switchTo(model, standby)
                }
            }
        } finally {
            for (fixture in seeded) Core.chats.delete(fixture.id)
        }
        val results = Bundle()
        record(results, "jagent.chat.cold", cold)
        record(results, "jagent.chat.warm", warm)
        instrumentation.addResults(results)
    }

    private suspend fun switchTo(model: ChatViewModel, target: Fixture): Sample = coroutineScope {
        check(model.state.value.conversationId != target.id)
        await("打开侧边栏", click = true)
        val viewport = async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) {
                model.presentedConversation.first { it == target.id }
                SystemClock.elapsedRealtimeNanos()
            }
        }
        val start = await(target.title, click = true)
        val ready = viewport.await()
        val visible = await(target.lastAnswer)
        check(ready >= start && visible >= ready)
        val opened = model.state.value
        assertEquals(target.id, opened.conversationId)
        assertEquals(target.turns * 2, opened.messageCount)
        assertEquals(target.lastAnswer, opened.messages.last().text)
        Sample(ready - start, visible - start)
    }

    private fun await(label: String, click: Boolean = false): Long {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val timestamp = automation.rootInActiveWindow?.let { root ->
                try {
                    find(root, label, click)
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
            if (timestamp != null) return timestamp
            SystemClock.sleep(16)
        }
        error("No visible node for '$label' (click=$click).")
    }

    @Suppress("DEPRECATION")
    private fun find(node: AccessibilityNodeInfo, label: String, click: Boolean): Long? {
        val matches = node.text?.toString() == label || node.contentDescription?.toString() == label
        if (matches && node.isVisibleToUser && (!click || node.isClickable)) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (!click || node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return now
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

    private fun record(results: Bundle, key: String, samples: List<Sample>) {
        fun List<Long>.ms(fraction: Double): Double = sorted()[ceil(size * fraction).toInt() - 1] / 1_000_000.0
        val ready = samples.map { it.viewport }
        val visible = samples.map { it.visible }
        results.putInt("$key.samples", samples.size)
        results.putDouble("$key.viewport_p50_ms", ready.ms(0.50))
        results.putDouble("$key.viewport_p95_ms", ready.ms(0.95))
        results.putDouble("$key.answer_visible_p50_ms", visible.ms(0.50))
        results.putDouble("$key.answer_visible_p95_ms", visible.ms(0.95))
    }

    private data class Sample(val viewport: Long, val visible: Long)

    private data class Fixture(val name: String, val turns: Int) {
        val id = "jagent-perf-$name"
        val title = "Benchmark $name"
        val lastAnswer = "JAgent benchmark final answer $name"

        fun conversation(): Conversation {
            val body = "## Section\n\n**资料🙂** `code` and plain text.\n\n".repeat(384)
            return Conversation(id = id, title = title, messages = (1..turns).flatMap { turn ->
                listOf(
                    StoredMessage(id = "$id-user-$turn", role = Roles.USER, text = "Question $name $turn"),
                    StoredMessage(
                        id = "$id-assistant-$turn",
                        role = Roles.ASSISTANT,
                        text = if (turn == turns) lastAnswer else body,
                    ),
                )
            })
        }
    }
}
