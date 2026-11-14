@file:Suppress("UNCHECKED_CAST")

package com.suixing.agent.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.internal.ComposableLambda
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ConversationViewports
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.ui.theme.LocalDarkTheme
import io.mockk.every
import io.mockk.ConstantMatcher
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class ChatFootprintHandoffTest {
    @Test fun switchingEqualHeightConversationsKeepsTheNewReadersComposerSpaceFrozen() = runBlocking {
        val screen = mutableStateOf(ChatUiState(conversationId = "a", loading = false))
        val screenFlow = MutableStateFlow(screen.value)
        val viewModel = mockk<ChatViewModel>(relaxed = true) {
            every { screenState } returns screenFlow
            every { liveActive } returns MutableStateFlow(false)
        }
        val settings = mockk<SettingsRepository> {
            every { flow } returns MutableStateFlow(AppSettings())
        }
        val viewports = mockk<ConversationViewports> {
            every { ready } returns MutableStateFlow(false)
        }
        val registry = mockk<ToolRegistry> {
            every { catalogRevision } returns MutableStateFlow(0L)
            every { excluding(any()) } answers { self as ToolRegistry }
            every { all() } returns emptyList()
        }
        val configuration = Configuration().apply { screenWidthDp = 400; screenHeightDp = 800 }
        val focusManager = mockk<FocusManager>(relaxed = true)
        val viewConfiguration = mockk<ViewConfiguration>(relaxed = true)
        val collected = IdentityHashMap<Any, State<*>>()
        val settingState = mutableStateOf(AppSettings())
        val inputs = mockk<ChatInputBindings>(relaxed = true)
        val context = mockk<ChatContextReadout>(relaxed = true)
        val presentation = mockk<ChatRunPresentation>(relaxed = true)
        val position = mockk<TranscriptPosition>(relaxed = true)
        val guard = mockk<BringIntoViewSpec>(relaxed = true)
        val earlier = ChatEarlierPaging(mutableStateOf(false)) {}
        val prepare: suspend (List<ChatRow>) -> Unit = {}
        var setHeight: ((Dp) -> Unit)? = null
        lateinit var held: () -> Dp?
        lateinit var scroll: ChatScroll
        var stack = 0.dp
        val mocks = mutableListOf<String>()
        val calledBoundaries = mutableListOf<String>()
        fun stub(owner: String, name: String, answer: (Method, List<Any?>) -> Any?) {
            if (owner !in mocks) {
                mockkStatic(owner)
                mocks += owner
            }
            val methods = Class.forName(owner).methods.filter {
                !it.isSynthetic && (it.name == name || it.name.startsWith("$name-")) &&
                    Composer::class.java in it.parameterTypes
            }
            check(methods.isNotEmpty()) { "Missing composable $owner.$name" }
            for (method in methods) {
                every { method.invoke(null, *method.parameterTypes.map {
                    match(ConstantMatcher<Any>(true), it.kotlin as KClass<Any>)
                }.toTypedArray()) } answers {
                    calledBoundaries += "$owner.$name"
                    answer(method, invocation.args)
                }
            }
        }
        fun chat(name: String, method: String, answer: (Method, List<Any?>) -> Any? = { _, _ -> Unit }) =
            stub("com.suixing.agent.ui.chat.${name}Kt", method, answer)

        val nodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
        val root = nodeClass.getDeclaredConstructor().newInstance()
        val applier = Class.forName("androidx.compose.ui.node.UiApplier")
            .getDeclaredConstructor(nodeClass).newInstance(root) as Applier<Any>
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(applier, recomposer)
        val runner = launch(clock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        var nanos = 0L
        suspend fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                repeat(8) { yield() }
            }
        }
        mockkObject(Core)
        try {
            every { Core.settings } returns settings
            every { Core.conversationViewports } returns viewports
            every { Core.tools } returns registry
            // Keep ChatScreen, its real viewport/scroll identity and its effects intact.
            // Supply lifecycle inputs directly and replace only unrelated UI boundaries.
            stub("androidx.lifecycle.compose.FlowExtKt", "collectAsStateWithLifecycle") { _, args ->
                val flow = args.first()!!
                when {
                    flow === screenFlow -> screen
                    flow is StateFlow<*> -> collected.getOrPut(flow) { mutableStateOf(flow.value) }
                    else -> settingState
                }
            }
            stub("androidx.compose.foundation.layout.WindowInsets_androidKt", "getNavigationBars") { _, _ ->
                WindowInsets(0, 0, 0, 0)
            }
            chat("ChatAccessibility", "rememberChatAccessibility") { _, _ -> false }
            chat("ChatScreenInput", "rememberChatInputBindings") { _, _ -> inputs }
            chat("ChatContextReadout", "rememberChatContextReadout") { _, _ -> context }
            chat("ChatRunPresentation", "rememberChatRunPresentation") { _, _ -> presentation }
            chat("ChatReadingPosition", "rememberChatReadingPosition") { _, _ -> position }
            chat("ChatEarlierPaging", "rememberChatEarlierPaging") { _, _ -> earlier }
            chat("ChatMarkdownPreparation", "rememberChatMarkdownPreparation") { _, _ -> prepare }
            chat("ConversationViewport", "BindConversationViewport")
            chat("ChatSelectionGuard", "rememberChatSelectionGuard") { _, _ -> guard }
            chat("ChatRunPresentation", "PruneChatPresentation")
            chat("ChatScreenHeader", "ChatScreenHeader")
            chat("ChatTurnNavigation", "ChatTurnNavigation")
            chat("ChatScreenComposer", "JumpToLatestButton")
            chat("ChatScreenDialogs", "ChatInlineOverlays")
            chat("ChatScreenDialogs", "ChatDialogs")
            chat("ChatScreenComposer", "ChatComposerPanel") { method, args ->
                setHeight = args[method.parameterTypes.indexOf(Composer::class.java) - 1] as (Dp) -> Unit
                Unit
            }
            chat("ChatTranscriptViewport", "ChatTranscriptViewport") { _, args ->
                scroll = args[10] as ChatScroll
                held = args[18] as () -> Dp?
                stack = (args[19] as Float).dp
                Unit
            }
            stub("androidx.compose.material3.ScaffoldKt", "Scaffold") { method, args ->
                val composer = args[method.parameterTypes.indexOf(Composer::class.java)] as Composer
                val content = args[method.parameterTypes.indexOf(Function3::class.java)] as ComposableLambda
                content.invoke(PaddingValues(0.dp), composer, 0)
            }
            withTimeout(15_000) {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(1f),
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                        LocalFocusManager provides focusManager,
                        LocalViewConfiguration provides viewConfiguration,
                        LocalDarkTheme provides true,
                    ) { ChatScreen(viewModel) }
                }
                frames()
                requireNotNull(setHeight) { "Composer not mounted: $calledBoundaries" }(56.dp)
                frames()
                val originalSpace = held() ?: stack
                val firstScroll = scroll

                screen.value = screen.value.copy(conversationId = "b")
                frames()
                assertNotEquals(firstScroll, scroll)
                scroll.stickToBottom = false
                frames()
                requireNotNull(setHeight)(96.dp)
                frames()

                assertEquals("reading history in B must keep the space captured when B opened",
                    originalSpace, held() ?: stack)
            }
        } finally {
            withContext(NonCancellable) {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
                mocks.asReversed().forEach { unmockkStatic(it) }
                unmockkObject(Core)
            }
        }
    }
}
