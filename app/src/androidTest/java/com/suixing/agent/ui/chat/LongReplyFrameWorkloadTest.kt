package com.suixing.agent.ui.chat

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.MainActivity
import com.suixing.agent.core.Core
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

private const val MODEL = "offline-stream-fixture"
private const val PROMPT = "Stream the local benchmark response."
private const val CHUNKS = 200
private const val HISTORY_ID = "jagent-wire-perf-history"
private const val HISTORY_TURNS = 256
private const val SUMMARIZED_TURNS = 216
private const val HISTORY_SUMMARY = "The first 216 benchmark turns were summarized here."
private const val FINISH = "JAgent stream complete marker"
private const val PARAGRAPH_END = "` with Chinese 中文 and emoji 🙂. This is a normal Markdown paragraph.\n\n"

@RunWith(AndroidJUnit4::class)
class LongReplyFrameWorkloadTest {
    @Test
    fun pacedMarkdownStreamThroughProductionChat() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val preSendHistory = arguments.getString("jagentWireHistory") == "1"
        assumeTrue(preSendHistory || arguments.getString("jagentLongReplyFrames") == "1")
        val context = instrument.targetContext
        check(context.packageName == "com.suixing.agent.perf") { "Use the isolated Release performance app." }
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
        Core.chats.load()
        check(Core.chats.conversations.value.isEmpty()) { "Use an empty chat sandbox." }

        val history = if (preSendHistory) historyFixture() else null
        val fragments = (0 until CHUNKS).map(::fragment) + (PARAGRAPH_END + FINISH)
        val expected = fragments.joinToString("")
        val wire = fragments.map { text ->
            val chunk = """{"choices":[{"index":0,"delta":{"content":${JSONObject.quote(text)}}}]}"""
            "data: $chunk\n\n".toByteArray(Charsets.UTF_8)
        }
        val frames = ArrayList<Frame>()
        val dropped = AtomicInteger()
        val result = Bundle()
        val handler = HandlerThread("chat-frame-metrics").apply { start() }
        try {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val url = "http://127.0.0.1:${server.localPort}/v1"
                val provider = ProviderConfig(id = MODEL, name = MODEL, baseUrl = url, apiKey = "fixture",
                    models = listOf(MODEL), enabled = listOf(MODEL))
                check(Core.settings.update {
                    it.copy(onboardingSeen = true, baseUrl = url, apiKey = "fixture", model = MODEL,
                        providers = listOf(provider), providersInitialized = true, retryOnFailure = false, autoProfile = false)
                })
                val stream = async(Dispatchers.IO) { serve(server, wire, history) }
                try {
                    var chatId = history?.let { HISTORY_ID }.orEmpty()
                    try {
                        if (history != null) {
                            check(Core.chats.save(history.conversation) == ChatRepository.SaveResult.Written)
                            Core.chats.load()
                            Core.chats.saveLastOpened(HISTORY_ID)
                        }
                        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                            lateinit var window: Window
                            lateinit var model: ChatViewModel
                            scenario.onActivity {
                                window = it.window
                                model = ViewModelProvider(it)[ChatViewModel::class.java]
                                result.putFloat("jagent.reply.initial_refresh_hz", window.decorView.display?.refreshRate ?: 0f)
                            }
                            val presented = withTimeout(10_000) {
                                model.presentedConversation.first {
                                    if (history == null) !it.isNullOrEmpty() else it == HISTORY_ID
                                }!!
                            }
                            chatId = presented
                            if (history != null) {
                                val opened = model.state.value
                                check(opened.messageCount == HISTORY_TURNS * 2 && opened.messages.size < opened.messageCount) {
                                    "The visible conversation must be a partial history window."
                                }
                            }
                            val listener = Window.OnFrameMetricsAvailableListener { _, timing, lost ->
                                val ts = timing.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                                val duration = timing.getMetric(FrameMetrics.TOTAL_DURATION)
                                val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                    timing.getMetric(FrameMetrics.DEADLINE) else -1L
                                if (ts > 0 && duration > 0) synchronized(frames) { frames += Frame(ts, duration, deadline) }
                                dropped.addAndGet(lost)
                            }
                            scenario.onActivity {
                                window.addOnFrameMetricsAvailableListener(listener, Handler(handler.looper))
                            }
                            try {
                                var sentAt = 0L
                                scenario.onActivity {
                                    sentAt = System.nanoTime()
                                    check(model.send(PROMPT, emptyList()))
                                }
                                val timing = withTimeout(25_000) { stream.await() }
                                if (history != null) {
                                    check(timing.requestReceivedAt >= sentAt)
                                    result.putDouble("jagent.wire.send_to_request_ms",
                                        (timing.requestReceivedAt - sentAt) / 1_000_000.0)
                                    result.putInt("jagent.wire.history_rows", HISTORY_TURNS * 2)
                                    result.putInt("jagent.wire.sent_conversation_messages", history.tail.size + 1)
                                }
                                withTimeout(15_000) {
                                    model.state.first {
                                        it.conversationId == chatId && !it.runningHere &&
                                            it.messages.lastOrNull()?.text == expected
                                    }
                                    var stored = Core.chats.readAll(chatId)
                                    while (stored.lastOrNull()?.text != expected) {
                                        delay(40)
                                        stored = Core.chats.readAll(chatId)
                                    }
                                    if (history != null) {
                                        check(stored.size == HISTORY_TURNS * 2 + 2)
                                        check(stored[HISTORY_TURNS * 2].text == PROMPT)
                                    }
                                }
                                val recorded = synchronized(frames) {
                                    frames.filter { it.timestamp in timing.responseWindow }.sortedBy { it.timestamp }
                                }
                                record(result, recorded)
                                awaitLastAnswer(automation)
                            } finally {
                                scenario.onActivity { window.removeOnFrameMetricsAvailableListener(listener) }
                            }
                        }
                    } finally {
                        if (chatId.isNotEmpty()) Core.chats.delete(chatId)
                    }
                } finally {
                    server.close()
                }
            }
        } finally {
            handler.quitSafely()
            handler.join(2_000)
            check(!handler.isAlive)
        }
        result.putInt("jagent.reply.metrics_notifications_dropped", dropped.get())
        result.putInt("jagent.reply.content_bytes", expected.toByteArray(Charsets.UTF_8).size)
        result.putInt("jagent.reply.sse_content_events", wire.size)
        instrument.addResults(result)
    }

    @Suppress("DEPRECATION")
    private fun serve(server: ServerSocket, wire: List<ByteArray>, history: HistoryFixture?): StreamTiming {
        server.soTimeout = 15_000
        server.accept().use { connection ->
            connection.soTimeout = 10_000
            val request = DataInputStream(connection.getInputStream().buffered())
            check(request.readLine()?.startsWith("POST /v1/chat/completions HTTP/1.") == true)
            var length = 0
            while (true) {
                val header = checkNotNull(request.readLine())
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true))
                    length = header.substringAfter(':').trim().toInt()
            }
            val body = ByteArray(length)
            request.readFully(body)
            val requestReceivedAt = System.nanoTime()
            val json = JSONObject(body.toString(Charsets.UTF_8))
            check(json.getString("model") == MODEL && json.getBoolean("stream"))
            val messages = json.getJSONArray("messages")
            if (history != null) {
                var summaryCount = 0
                val actualTail = ArrayList<Pair<String, String>>()
                for (index in 0 until messages.length()) {
                    val message = messages.getJSONObject(index)
                    val role = message.getString("role")
                    val content = message.optString("content")
                    if (role == "system" && content == "[上下文摘要] $HISTORY_SUMMARY") summaryCount++
                    if (role == Roles.USER || role == Roles.ASSISTANT) actualTail += role to content
                }
                check(summaryCount == 1 && actualTail == history.tail + (Roles.USER to PROMPT)) {
                    "The outgoing request did not contain exactly the verified summary and contiguous conversation tail."
                }
            } else {
                check(messages.getJSONObject(messages.length() - 1).getString("content") == PROMPT)
            }
            val out = connection.getOutputStream().buffered()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            val start = System.nanoTime()
            for (chunk in wire) {
                out.write(chunk)
                out.flush()
                SystemClock.sleep(40)
            }
            out.write(("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n").toByteArray())
            out.flush()
            return StreamTiming(requestReceivedAt, start..System.nanoTime())
        }
    }

    private fun historyFixture(): HistoryFixture {
        val archived = "历史正文 backup payload 🙂.\n".repeat(1100)
        val active = "Saved active history 数据🙂. ".repeat(60)
        val history = ArrayList<StoredMessage>(HISTORY_TURNS * 2)
        val tail = ArrayList<Pair<String, String>>((HISTORY_TURNS - SUMMARIZED_TURNS) * 2)
        for (turn in 1..HISTORY_TURNS) {
            val question = "History question $turn"
            val answer = "History answer $turn\n" + if (turn <= SUMMARIZED_TURNS) archived else active
            history += StoredMessage(id = "wire-$turn-user", role = Roles.USER, text = question)
            history += StoredMessage(id = "wire-$turn-assistant", role = Roles.ASSISTANT, text = answer)
            if (turn > SUMMARIZED_TURNS) {
                tail += Roles.USER to question
                tail += Roles.ASSISTANT to answer
            }
        }
        val checkpoint = ContextCheckpoint(id = "wire-perf-checkpoint", at = 1,
            fromMessageId = "wire-1-user", toMessageId = "wire-$SUMMARIZED_TURNS-assistant",
            summary = HISTORY_SUMMARY, messages = SUMMARIZED_TURNS * 2, promptTokens = 0)
        return HistoryFixture(Conversation(id = HISTORY_ID, title = "Wire performance history",
            messages = history, checkpoints = listOf(checkpoint)), tail)
    }

    private fun fragment(group: Int): String = buildString {
        if (group == 0) append("# Streaming Markdown fixture\n\n") else append(PARAGRAPH_END)
        repeat(6) { part ->
            val section = group * 6 + part + 1
            append("Section $section uses **强调** and `fixed data")
            if (part < 5) append(PARAGRAPH_END)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitLastAnswer(automation: UiAutomation) = withTimeout(5_000) {
        while (true) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    val matches = root.findAccessibilityNodeInfosByText(FINISH)
                    try {
                        if (matches.any { it.isVisibleToUser }) return@withTimeout
                    } finally {
                        matches.forEach { it.recycle() }
                    }
                } finally {
                    root.recycle()
                }
            }
            delay(25)
        }
    }

    private fun record(result: Bundle, frames: List<Frame>) {
        check(frames.size >= 2) { "No usable Android frames during the stream." }
        val gaps = frames.zipWithNext { first, second -> second.timestamp - first.timestamp }.filter { it > 0 }
        val deadline = frames.filter { it.deadline > 0 }
        fun List<Long>.ms(fraction: Double): Double = sorted()[ceil(size * fraction).toInt() - 1] / 1_000_000.0
        result.putInt("jagent.reply.frames", frames.size)
        result.putDouble("jagent.reply.duration_p50_ms", frames.map { it.duration }.ms(0.50))
        result.putDouble("jagent.reply.duration_p95_ms", frames.map { it.duration }.ms(0.95))
        result.putDouble("jagent.reply.vsync_gap_p95_ms", gaps.ms(0.95))
        result.putInt("jagent.reply.frames_with_deadline", deadline.size)
        result.putInt("jagent.reply.deadline_misses", deadline.count { it.duration >= it.deadline })
    }

    private data class HistoryFixture(val conversation: Conversation, val tail: List<Pair<String, String>>)
    private data class StreamTiming(val requestReceivedAt: Long, val responseWindow: LongRange)
    private data class Frame(val timestamp: Long, val duration: Long, val deadline: Long)
}
