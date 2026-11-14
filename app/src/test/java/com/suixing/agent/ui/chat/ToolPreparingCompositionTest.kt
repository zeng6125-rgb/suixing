@file:Suppress("UNCHECKED_CAST", "DEPRECATION")

package com.suixing.agent.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.view.Choreographer
import android.view.View
import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.R
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.SettingsRepository
import io.mockk.ConstantMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ToolPreparingCompositionTest {
    @Test fun preparationFoldsTheUntouchedThoughtBeforeExecutionAndKeepsTheSameToolCard() = withFixture {
        await { headers["思考过程"]?.expanded == true }
        send(AgentEvent.ToolPrepared(0, "read_file"))
        await { calls.singleOrNull()?.preparing == true && "read_file" in headers }
        assertFalse(headers.getValue("思考过程").expanded)
        assertEquals("准备调用", headers.getValue("read_file").status)
        assertEquals(0, headers.getValue("read_file").spinners)
        assertEquals("", calls.single().args)
        assertEquals(0L, calls.single().elapsedMs)

        headers.getValue("read_file").onToggle()
        await { headers.getValue("read_file").expanded }
        send(AgentEvent.ToolStarted("read_file", "{\"path\":\"/sdcard/a.txt\"}", callId = "call0", streamIndex = 0))
        await { calls.singleOrNull()?.preparing == false && headers["read_file"]?.status == "read_file" }
        assertTrue(headers.getValue("read_file").expanded)
        assertFalse(calls.single().done)
        assertEquals(1, calls.size)

        send(AgentEvent.ToolFinished("read_file", true, "read", callId = "call0", streamIndex = 0))
        await { calls.singleOrNull()?.done == true && headers["read_file"]?.status == "完成" }
        assertTrue(headers.getValue("read_file").expanded)
        assertEquals(1, calls.size)
    }

    @Test fun manuallyOpenedThoughtStaysOpenAndPreparedRowsDoNotClaimTheyAreExecuting() = withFixture {
        await { headers["思考过程"]?.expanded == true }
        headers.getValue("思考过程").onToggle()
        await { headers["思考过程"]?.expanded == false }
        headers.getValue("思考过程").onToggle()
        await { headers["思考过程"]?.expanded == true }

        send(AgentEvent.ToolPrepared(0, "run_shell"))
        send(AgentEvent.ToolPrepared(1, "run_shell"))
        await { calls.size == 2 && calls.all { it.preparing } && "调用 2 个工具" in headers }
        assertEquals("准备调用", headers.getValue("调用 2 个工具").status)
        assertEquals(0, headers.getValue("调用 2 个工具").spinners)
        send(AgentEvent.ToolStarted("run_shell", "{\"command\":\"id\"}", callId = "call0", streamIndex = 0))
        await { calls.size == 2 && !calls[0].preparing && calls[1].preparing &&
            headers["调用 2 个工具"]?.status == "run_shell" }
        assertTrue(headers.getValue("思考过程").expanded)
        assertEquals("run_shell", headers.getValue("调用 2 个工具").status)
        assertEquals(0L, calls[1].elapsedMs)
        texts.clear()
        vectors.clear()
        headers.getValue("调用 2 个工具").onToggle()
        await { headers.getValue("调用 2 个工具").expanded && "运行中…" in texts }
        assertTrue("the later same-name row is still preparing: $texts", "准备调用" in texts)
        assertTrue("the preparing row keeps its static tool icon: $vectors", R.drawable.ic_terminal in vectors)
    }

    private fun withFixture(body: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.start()
            withTimeout(15_000) { fixture.body() }
        } finally { fixture.close() }
    }

    private class Header(val expanded: Boolean, val onToggle: () -> Unit) {
        var status: String? = null
        var spinners = 0
    }

    private class Fixture(scope: CoroutineScope) {
        val headers = mutableMapOf<String, Header>()
        val texts = mutableListOf<String>()
        val vectors = mutableListOf<Int>()
        private var activeHeader: Header? = null
        private var rendered = emptyList<Part>()
        val calls get() = rendered.filterIsInstance<Part.Tools>().flatMap { it.calls }
        private val work = SupervisorJob(scope.coroutineContext[Job])
        private val runtimeScope = CoroutineScope(scope.coroutineContext + work)
        private val runtime = ChatRuntime(runtimeScope, environment(runtimeScope))
        private val actions = mockk<ChatScreenActions>()
        private val expansion = ReasoningExpansion("chat", mutableStateMapOf())
        private val context = mockk<Context>(relaxed = true)
        private val view = mockk<View>(relaxed = true)
        private val viewTags = mutableMapOf<Int, Any?>()
        private val choreographer = mockk<Choreographer>(relaxed = true)
        private val configuration = Configuration().apply { screenWidthDp = 400; screenHeightDp = 800 }
        private val viewConfiguration = mockk<ViewConfiguration>(relaxed = true)
        private val clipboard = mockk<ClipboardManager>(relaxed = true)
        private val graphics = mockk<GraphicsContext>(relaxed = true)
        private val lifecycle = ChatUiLifecycleOwner(true, LifecycleRegistry::createUnsafe)
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val nodes = Class.forName("androidx.compose.ui.node.LayoutNode")
        private val root = nodes.getDeclaredConstructor().newInstance()
        private val applier = Class.forName("androidx.compose.ui.node.UiApplier")
            .getDeclaredConstructor(nodes).newInstance(root) as Applier<Any>
        private val composition = Composition(applier, recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        private var nanos = 0L

        init {
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val background = Dispatchers.Default
            val io = Dispatchers.IO
            val unconfined = Dispatchers.Unconfined
            val main = object : MainCoroutineDispatcher() {
                override val immediate: MainCoroutineDispatcher get() = this
                override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context, block)
            }
            mockkStatic(Dispatchers::class)
            mockkStatic(Choreographer::class)
            every { Dispatchers.Main } returns main
            every { Dispatchers.Default } returns background
            every { Dispatchers.IO } returns io
            every { Dispatchers.Unconfined } returns unconfined
            every { Choreographer.getInstance() } returns choreographer
            every { view.context } returns context
            every { view.display } returns null
            every { view.getTag(any()) } answers { viewTags[firstArg<Int>()] }
            every { view.setTag(any(), any()) } answers {
                viewTags[firstArg<Int>()] = secondArg()
            }
            every { actions.liveContent } returns runtime.liveContent
            every { actions.currentLiveContent } returns this::currentLiveContent
            lifecycle.parentChanged(Lifecycle.State.RESUMED)
            val parts = listOf<LivePart>(LivePart.Thinking("current thought"))
            runtime.sessions["chat"] = RunSession("chat", "Chat", "", runId = "run", messages = emptyList(),
                activeRuns = emptyMap(), parts = parts, thinkingStartedAt = System.currentTimeMillis() - 1)
            runtime._state.value = ChatUiState(conversationId = "chat", loading = false, runningHere = true,
                liveRunId = "run", liveParts = parts)
        }

        private fun currentLiveContent() = runtime.currentLiveContent()
        fun send(event: AgentEvent) = runtime.handleEvent("chat", event)

        fun start() {
            mockkStatic(VECTORS, TEXT, PROCESS)
            val vector = Class.forName(VECTORS).methods.single {
                it.name == "vectorResource" && Composer::class.java in it.parameterTypes
            }
            val icon = ImageVector.Builder("fixture", 16.dp, 16.dp, 16f, 16f).build()
            every { vector.invoke(null, ImageVector, any<Int>(), any<Composer>(), any<Int>()) } answers {
                vectors += secondArg<Int>()
                icon
            }
            for (method in Class.forName(TEXT).methods.filter {
                !it.isSynthetic && it.name.startsWith("Text-") && Composer::class.java in it.parameterTypes
            }) {
                every { method.invoke(null, *method.parameterTypes.map {
                    match(ConstantMatcher<Any>(true), it.kotlin as KClass<Any>)
                }.toTypedArray()) } answers {
                    texts += firstArg<Any>().let { if (it is androidx.compose.ui.text.AnnotatedString) it.text else it as String }
                    Unit
                }
            }
            for (method in Class.forName(PROCESS).methods.filter {
                !it.isSynthetic && '$' !in it.name && Composer::class.java in it.parameterTypes &&
                    listOf("ProcessCard", "ProcessStatus", "ProcessSpinner").any(it.name::startsWith)
            }) {
                every { method.invoke(null, *method.parameterTypes.map {
                    match(ConstantMatcher<Any>(true), it.kotlin as KClass<Any>)
                }.toTypedArray()) } answers {
                    val previous = activeHeader
                    when {
                        method.name.startsWith("ProcessCard") -> {
                            val title = invocation.args[1] as String
                            activeHeader = Header(invocation.args[3] as Boolean, invocation.args[4] as () -> Unit).also {
                                it.status = headers[title]?.status
                                it.spinners = headers[title]?.spinners ?: 0
                                headers[title] = it
                            }
                        }
                        method.name.startsWith("ProcessStatus") -> activeHeader?.status = firstArg()
                        else -> activeHeader?.let { it.spinners++ }
                    }
                    try { callOriginal() } finally { activeHeader = previous }
                }
            }
            composition.setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycle,
                    LocalContext provides context,
                    LocalView provides view,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(1f),
                    LocalGraphicsContext provides graphics,
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                    LocalViewConfiguration provides viewConfiguration,
                    LocalClipboardManager provides clipboard,
                    LocalInSelectable provides true,
                    LocalChatReadOnly provides true,
                ) {
                    val screen by runtime.screenState.collectAsStateWithLifecycle()
                    LiveMessageParts(actions, "chat", "run", true) { parts ->
                        SideEffect { rendered = parts }
                        AssistantRow(parts, streaming = true, name = "Agent", face = "A",
                            runningTool = screen.runningTool, runId = "run", expandReasoning = false,
                            expandTools = false, bubbleMaxWidth = 320.dp, reasoningExpansion = expansion)
                    }
                }
            }
        }

        suspend fun await(condition: () -> Boolean) {
            while (!condition()) {
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
            }
        }

        suspend fun close() = withContext(NonCancellable) {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            work.cancelAndJoin()
            lifecycle.destroy()
            unmockkStatic(PROCESS, TEXT, VECTORS)
            unmockkStatic(Choreographer::class)
            unmockkStatic(Dispatchers::class)
        }

        companion object {
            private const val VECTORS = "androidx.compose.ui.res.VectorResources_androidKt"
            private const val TEXT = "androidx.compose.material3.TextKt"
            private const val PROCESS = "com.suixing.agent.ui.chat.ChatProcessBlocksKt"

            private fun environment(scope: CoroutineScope): ChatRuntimeEnvironment {
                val chats = mockk<ChatRepository>(relaxed = true) {
                    every { conversations } returns MutableStateFlow(emptyList<Conversation>())
                }
                val settings = mockk<SettingsRepository>(relaxed = true) {
                    every { flow } returns MutableStateFlow(AppSettings())
                }
                val goals = mockk<GoalStore>(relaxed = true) {
                    every { changes } returns MutableStateFlow(null)
                }
                return mockk(relaxed = true) {
                    every { this@mockk.chats } returns chats
                    every { this@mockk.settings } returns settings
                    every { this@mockk.goals } returns goals
                    every { appScope } returns scope
                }
            }
        }
    }
}
