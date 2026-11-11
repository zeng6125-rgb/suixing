@file:Suppress("DEPRECATION")

package com.suixing.agent.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.agent.RunState
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolGroup
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedAssistantNameTest {
    @Test fun `a tool-only run reaching its step limit keeps the custom assistant card title`() = runBlocking {
        withTimeout(30_000) {
            var executions = 0
            val registry = ToolRegistry::class.java.getDeclaredConstructor().let {
                it.isAccessible = true
                it.newInstance()
            }.also {
                it.add(object : ToolGroup() {
                    override val specs = listOf(ToolSpec("echo", "fixture", JsonObject(emptyMap())))
                    override suspend fun execute(name: String, args: JsonObject): ToolResult {
                        executions++
                        return ToolResult.success("executed")
                    }
                })
            }
            val llm = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    AssistantTurn("", listOf(ToolCallRequest("call", "echo", "{}")), "tool_calls")
            }
            val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
            val skills = mockk<SkillStore> {
                coEvery { listHeaders() } returns emptyList<Skill>()
                every { catalog(any()) } returns ""
            }
            val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
            val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
            val history = listOf(StoredMessage("question", Roles.USER, "execute the tool"))
            val engine = AgentEngine(llm = llm)
            val produced = engine.startRun("identity", "Identity", history,
                AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, maxToolRounds = 1),
                registry, capabilities, skills, memory, profile, ApprovalGate(), {})
            assertEquals(1, executions)
            assertEquals(RunState.Failed("已达到最大工具轮数（1），任务未完成。"), engine.stateOf("identity").value)
            val row = groupRows(history + produced, emptyMap(), false).single { it.message.role == Roles.TOOL }
            assertTrue(row.parts.any { it is Part.Tools })
            assertTrue(row.parts.none { it is Part.Text || it is Part.Thinking })

            val nodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
            val root = nodeClass.getDeclaredConstructor().newInstance()
            @Suppress("UNCHECKED_CAST")
            val applier = Class.forName("androidx.compose.ui.node.UiApplier")
                .getDeclaredConstructor(nodeClass).newInstance(root) as Applier<Any>
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(applier, recomposer)
            val runner = launch(clock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
            val vectorClass = "androidx.compose.ui.res.VectorResources_androidKt"
            val vectorResource = Class.forName(vectorClass).methods.single {
                it.name == "vectorResource" && Composer::class.java in it.parameterTypes
            }
            val icon = ImageVector.Builder("fixture", 16.dp, 16.dp, 16f, 16f).build()
            val textClass = "androidx.compose.material3.TextKt"
            val textMethods = Class.forName(textClass).methods.filter {
                !it.isSynthetic && it.name.startsWith("Text-") && Composer::class.java in it.parameterTypes
            }
            val texts = mutableListOf<String>()
            // Chat composables run unchanged; capture their strings only at the framework
            // Text boundary, with Android vector resources supplied separately.
            mockkStatic(vectorClass, textClass)
            try {
                every { vectorResource.invoke(null, ImageVector, any<Int>(), any<Composer>(), any<Int>()) } returns icon
                for (method in textMethods) {
                    every { method.invoke(null, *method.parameterTypes.map { any(it.kotlin) }.toTypedArray()) } answers {
                        val text = firstArg<Any>()
                        texts += if (text is AnnotatedString) text.text else text as String
                        Unit
                    }
                }
                composition.setContent {
                    CompositionLocalProvider(
                        LocalContext provides mockk<Context>(relaxed = true),
                        LocalConfiguration provides Configuration().apply { screenWidthDp = 400; screenHeightDp = 800 },
                        LocalDensity provides Density(1f),
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                        LocalViewConfiguration provides mockk<ViewConfiguration>(relaxed = true),
                        LocalClipboardManager provides mockk<ClipboardManager>(relaxed = true),
                        LocalInSelectable provides true,
                        LocalChatReadOnly provides true,
                    ) {
                        MessageRow(row, assistantName = "自定义助手", assistantFace = "A", showReasoning = true,
                            expandTools = false, reasoningMaxHeight = 180.dp, bubbleMaxWidth = 320.dp,
                            contextWindow = 0, reasoningExpansion = ReasoningExpansion("identity", mutableMapOf()),
                            onBranch = { _, _ -> }, onRegenerate = {}, onEdit = {})
                    }
                }
                repeat(3) { frame ->
                    Snapshot.sendApplyNotifications()
                    repeat(8) { yield() }
                    clock.sendFrame((frame + 1) * 16_000_000L)
                    repeat(8) { yield() }
                }
                assertTrue("framework Text received: $texts", "自定义助手" in texts)
                assertFalse("framework Text received: $texts", "JAgent" in texts)
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
                unmockkStatic(vectorClass, textClass)
            }
        }
    }
}
