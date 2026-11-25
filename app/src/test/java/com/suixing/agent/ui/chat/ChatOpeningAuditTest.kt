package com.suixing.agent.ui.chat

import android.content.Context
import com.suixing.agent.agent.RunState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatOpeningAuditTest {
    @get:Rule val temp = TemporaryFolder()

    private fun transcript(id: String) = Conversation(
        id = id,
        title = "Conversation $id",
        messages = (0 until 64).flatMap { turn ->
            listOf(
                StoredMessage("$id-$turn-user", "user", "问题 $turn"),
                StoredMessage("$id-$turn-assistant", "assistant", "回答 $turn\n" + "资料🙂 ".repeat(2_048)),
            )
        },
    )

    @Test fun disk_open_then_warm_switch_reuses_the_loaded_transcript() = runBlocking {
        val context = mockk<Context> { every { filesDir } returns temp.root }
        val repository = ChatRepository(context)
        val first = transcript("first")
        val second = transcript("second")
        repository.save(first)
        repository.save(second)
        val source = repository.conversations.value.single { it.id == first.id }
        val target = repository.conversations.value.single { it.id == second.id }
        val cold = ArrayList<Long>()
        val warm = ArrayList<Long>()

        repeat(10) { round ->
            val scope = CoroutineScope(SupervisorJob() + checkNotNull(coroutineContext[ContinuationInterceptor]))
            try {
                val calls = mockk<ChatRepository>(relaxed = true) {
                    every { conversations } returns repository.conversations
                    coEvery { readTailWindow(any(), any()) } coAnswers {
                        repository.readTailWindow(firstArg(), secondArg())
                    }
                }
                val settings = mockk<SettingsRepository>(relaxed = true) {
                    every { flow } returns MutableStateFlow(AppSettings())
                }
                val goals = mockk<GoalStore>(relaxed = true) {
                    every { changes } returns MutableStateFlow(null)
                }
                val environment = mockk<ChatRuntimeEnvironment>(relaxed = true) {
                    every { chats } returns calls
                    every { this@mockk.settings } returns settings
                    every { this@mockk.goals } returns goals
                    every { appScope } returns scope
                }
                every { environment.engine.stateOf(any()) } returns MutableStateFlow<RunState>(RunState.Idle)
                val runtime = ChatRuntime(scope, environment)

                suspend fun open(conversation: Conversation): ChatUiState {
                    runtime.openConversation(conversation)
                    return withTimeout(5_000) {
                        runtime.state.first { it.conversationId == conversation.id && !it.loading }
                    }
                }

                val begin = System.nanoTime()
                val openedFirst = open(source)
                val coldTime = System.nanoTime() - begin
                val openedSecond = open(target)
                val next = System.nanoTime()
                val reopened = open(source)
                val warmTime = System.nanoTime() - next

                assertEquals(first.messages.takeLast(24), openedFirst.messages)
                assertEquals(second.messages.takeLast(24), openedSecond.messages)
                assertEquals(first.messages.size, reopened.messageCount)
                assertEquals(source.id, reopened.conversationId)
                assertFalse(reopened.loading)
                assertSame(openedFirst.messages, reopened.messages)
                assertTrue(checkNotNull(runtime.windowStarts[source.id]).second > 0)
                coVerify(exactly = 1) { calls.readTailWindow(source.id, any()) }
                coVerify(exactly = 1) { calls.readTailWindow(target.id, any()) }
                coVerify(exactly = 0) { calls.readTail(any(), any()) }
                if (round >= 4) {
                    cold.add(coldTime)
                    warm.add(warmTime)
                }
            } finally {
                scope.cancel()
            }
        }
        fun List<Long>.medianMs() = sorted()[size / 2] / 1_000_000.0
        println("transcript_messages=128 tail_messages=24 cold_state_ms=${cold.medianMs()} warm_state_ms=${warm.medianMs()}")
    }
}
