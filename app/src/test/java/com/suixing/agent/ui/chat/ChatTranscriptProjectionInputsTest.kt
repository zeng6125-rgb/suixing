package com.suixing.agent.ui.chat

import android.content.res.Configuration
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatTranscriptProjectionInputsTest {
    private fun checkpoint(anchor: String) = ContextCheckpoint(
        id = "checkpoint", at = 0L, fromMessageId = "", toMessageId = "",
        summary = "summary", messages = 1, promptTokens = 0, anchorId = anchor,
    )

    @Test fun equalRebuiltCheckpointsUseTheirLatestInstancesWithoutComparingSummaries() = withProjection(
        ChatUiState(
            conversationId = "chat", loading = false,
            messages = listOf(StoredMessage("answer", Roles.ASSISTANT, "body")),
            checkpoints = CountedCheckpoints(listOf(checkpoint("answer"))),
        ),
    ) {
        val before = state.checkpoints as CountedCheckpoints
        val next = before.single().copy()
        val after = CountedCheckpoints(listOf(next))
        before.comparisons = 0

        state = state.copy(checkpoints = after)
        frames(3)

        assertEquals("checkpoint input must not deep-compare its summary", 0,
            before.comparisons + after.comparisons)
        assertSame(next, projection.checkpointsByRow.getValue("answer").single())
    }

    @Test fun replacingTheLatestRoundKeepsItsEarlierRoundCheckpointOnTheNewMessageId() = withProjection(
        groupedState("latest", "middle"),
    ) {
        assertEquals(setOf("latest"), projection.checkpointsByRow.keys)
        state = groupedState("replacement", "middle").copy(messagesRevision = 1)
        frames(3)
        assertEquals(setOf("replacement"), projection.checkpointsByRow.keys)
    }

    @Test fun replacingAnInteriorRoundRepositionsItsNowMissingCheckpoint() = withProjection(
        groupedState("latest", "middle"),
    ) {
        assertEquals(setOf("latest"), projection.checkpointsByRow.keys)
        state = groupedState("latest", "changed").copy(messagesRevision = 1)
        frames(3)
        assertEquals(setOf("question"), projection.checkpointsByRow.keys)
    }

    @Test fun runElapsedOnlyIncludesTheDrawnFullTurnSuffix() = withProjection(elapsedState()) {
        val total = state.turnCount
        val first = total - PAGE_TURNS + 1
        val expectedRuns = (first..total).flatMap { listOf("run-$it", "branch-$it") }.toSet()

        assertEquals("question-$first", projection.visible.first().message.id)
        assertEquals(expectedRuns, projection.runElapsed.keys)
        for (turn in first..total) {
            assertEquals(60L, projection.runElapsed.getValue("run-$turn"))
            assertEquals(40L, projection.runElapsed.getValue("branch-$turn"))
        }
    }

    @Test fun expandingDrawnTurnsRestoresTheirRunElapsed() = withProjection(elapsedState()) {
        assertFalse(projection.runElapsed.containsKey("run-1"))
        projection.windowState.value = mapOf(state.conversationId to state.turnCount)
        frames(3)

        assertEquals("question-1", projection.visible.first().message.id)
        assertEquals(state.turnCount * 2, projection.runElapsed.size)
        assertEquals(60L, projection.runElapsed.getValue("run-1"))
        assertEquals(40L, projection.runElapsed.getValue("branch-1"))
    }

    @Test fun updatingAnInteriorToolMessageRefreshesRunElapsedWithoutChangingRowShape() = withProjection(elapsedState()) {
        val total = state.turnCount
        val beforeRowsKey = projection.rowsKey
        state = state.copy(
            messages = state.messages.map { message ->
                if (message.id == "tool-$total") message.copy(elapsedMs = 45L) else message
            },
            messagesRevision = state.messagesRevision + 1,
        )
        frames(3)

        assertEquals(beforeRowsKey, projection.rowsKey)
        assertEquals(85L, projection.runElapsed.getValue("run-$total"))
        assertEquals(40L, projection.runElapsed.getValue("branch-$total"))
        assertEquals(60L, projection.runElapsed.getValue("run-${total - 1}"))
    }

    @Test fun switchingBackReusesTheProjectedTranscriptInComposition() = withProjection(elapsedState()) {
        val source = state
        val opened = projection
        state = ChatUiState(
            conversationId = "other", loading = false, messagesRevision = 1,
            messages = listOf(StoredMessage("other-question", Roles.USER, "Another conversation")),
        )
        frames(3)
        assertEquals(listOf("other-question"), projection.visible.map { it.message.id })

        state = source.copy(messagesRevision = 2)
        frames(3)
        assertSame(opened.viewport, projection.viewport)
        assertSame(opened.visible, projection.visible)
        assertSame(opened.rowKeys, projection.rowKeys)
        assertSame(opened.rowKeyIndices, projection.rowKeyIndices)
    }

    @Test fun receivingTheStoredPositionKeepsTheAlreadyProjectedTranscript() = withProjection(elapsedState()) {
        storageReady = false
        frames(3)
        val before = projection
        storageReady = true
        frames(3)

        assertNotSame(before.viewport, projection.viewport)
        assertSame(before.visible, projection.visible)
        assertSame(before.rowKeys, projection.rowKeys)
    }

    private fun elapsedState(): ChatUiState {
        val total = PAGE_TURNS + 2
        return ChatUiState(
            conversationId = "chat", loading = false, turnCount = total,
            activeRuns = (1..total).associate { "question-$it" to "run-$it" },
            messages = (1..total).flatMap { turn ->
                listOf(
                    StoredMessage("question-$turn", Roles.USER, "question"),
                    StoredMessage("step-$turn", Roles.ASSISTANT, "step", runId = "run-$turn", elapsedMs = 10L),
                    StoredMessage("tool-$turn", Roles.TOOL, "", runId = "run-$turn", elapsedMs = 20L,
                        toolName = "read", toolOutput = "result"),
                    StoredMessage("answer-$turn", Roles.ASSISTANT, "answer", runId = "run-$turn", elapsedMs = 30L),
                    StoredMessage("alternative-$turn", Roles.ASSISTANT, "alternative", runId = "branch-$turn", elapsedMs = 40L),
                )
            },
        )
    }

    private fun groupedState(latestId: String, middleId: String) = ChatUiState(
        conversationId = "chat", loading = false,
        messages = listOf(
            StoredMessage("question", Roles.USER, "question"),
            StoredMessage("first", Roles.ASSISTANT, "first", runId = "run"),
            StoredMessage(middleId, Roles.ASSISTANT, "middle", runId = "run"),
            StoredMessage(latestId, Roles.ASSISTANT, "latest", runId = "run"),
        ),
        checkpoints = listOf(checkpoint("middle")),
    )

    private class CountedCheckpoints(private val items: List<ContextCheckpoint>) :
        AbstractList<ContextCheckpoint>() {
        var comparisons = 0
        override val size get() = items.size
        override fun get(index: Int) = items[index]
        override fun equals(other: Any?): Boolean {
            comparisons++
            return super.equals(other)
        }
    }

    private class Harness(scope: CoroutineScope, initial: ChatUiState) {
        var state by mutableStateOf(initial, referentialEqualityPolicy())
        var storageReady by mutableStateOf(true)
        lateinit var projection: ChatTranscriptProjection
            private set
        private val configuration = Configuration().apply { screenWidthDp = 400; screenHeightDp = 800 }
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(UnitApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        suspend fun start() {
            composition.setContent {
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(1f),
                ) {
                    val current = rememberChatTranscriptProjection(state, storageReady, null, false)
                    SideEffect { projection = current }
                }
            }
            frames(3)
        }

        suspend fun frames(count: Int) {
            repeat(count) {
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
            }
        }

        suspend fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }

    private fun withProjection(initial: ChatUiState, block: suspend Harness.() -> Unit) = runBlocking {
        withTimeout(5000) {
            val harness = Harness(this, initial)
            try {
                harness.start()
                harness.block()
            } finally {
                harness.close()
            }
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
