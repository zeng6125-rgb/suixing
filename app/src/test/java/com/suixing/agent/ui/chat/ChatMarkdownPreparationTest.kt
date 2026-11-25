package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.chat.markdown.SettledBlocks
import com.suixing.agent.ui.chat.markdown.parseMarkdown
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownPreparationTest {
    @Test fun `cached, short, and settled-hit rows skip background dispatch while cold rows still run off thread`() = runBlocking {
        SettledBlocks.clearForTest()
        val realDefault = Dispatchers.Default
        val defaultDispatches = AtomicInteger(0)
        val countingDefault = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                defaultDispatches.incrementAndGet()
                realDefault.dispatch(context, block)
            }
        }

        val shortText = "short reply"
        val settledLong = ("settled markdown prose\n\n- a\n- b\n\n").repeat(30)
        val coldLong = ("cold markdown prose\n\n```kt\nval x = 1\n```\n\n").repeat(30)
        val coldWithImage = ("before image\n\n").repeat(40) + "![pic](https://example.com/p.png)\n\n" + ("after image\n\n").repeat(40)
        val preSettledBlocks = parseMarkdown(settledLong)

        val state = ChatUiState(conversationId = "c1", loading = false)
        val screenFlow = MutableStateFlow(state)
        val actions = mockk<ChatScreenActions>(relaxed = true) {
            every { screenState } returns screenFlow
        }
        val rowDocuments = RowMarkdownCache()
        val listState = LazyListState()
        val shortRow = ChatRow(StoredMessage("m-short", Roles.ASSISTANT, shortText), true, listOf(Part.Text(shortText)))
        val settledRow = ChatRow(StoredMessage("m-settled", Roles.ASSISTANT, settledLong), true, listOf(Part.Text(settledLong)))
        val coldRow = ChatRow(StoredMessage("m-cold", Roles.ASSISTANT, coldLong), true, listOf(Part.Text(coldLong)))
        val imageRow = ChatRow(StoredMessage("m-image", Roles.ASSISTANT, coldWithImage), true, listOf(Part.Text(coldWithImage)))
        val allRows = listOf(shortRow, settledRow, coldRow, imageRow)
        val transcript = mockk<ChatTranscriptProjection> {
            every { this@mockk.rowDocuments } returns rowDocuments
            every { this@mockk.listState } returns listState
            every { rowKeyIndices } returns allRows.indices.associate { "message:c1:${allRows[it].message.id}" to it }
            every { visible } returns allRows
        }

        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.Default } returns countingDefault
        try {
            lateinit var prepareRows: suspend (List<ChatRow>) -> Unit
            composition.setContent {
                prepareRows = rememberChatMarkdownPreparation(state, transcript, actions)
            }

            prepareRows(emptyList())
            assertEquals("empty rows must not switch dispatchers", 0, defaultDispatches.get())

            prepareRows(listOf(shortRow))
            assertEquals("short-only uncached rows must not switch dispatchers", 0, defaultDispatches.get())
            assertEquals(emptyMap<String, Any>(), rowDocuments.peek("m-short"))
            assertFalse(rowDocuments.hasPreparedDocuments())

            SettledBlocks.put(settledLong, preSettledBlocks)
            prepareRows(listOf(settledRow))
            assertEquals("SettledBlocks hits must not switch dispatchers", 0, defaultDispatches.get())
            assertSame(preSettledBlocks, rowDocuments.peek("m-settled")?.get(settledLong))
            assertTrue(rowDocuments.hasPreparedDocuments())

            SettledBlocks.clearForTest()
            prepareRows(listOf(coldRow, imageRow))
            assertTrue("cold markdown/image rows must prepare off the calling thread", defaultDispatches.get() > 0)
            assertNotNull(rowDocuments.peek("m-cold")?.get(coldLong))
            val imageSegments = rowDocuments.peekImageSegments("m-image")
            assertTrue(imageSegments.containsKey(coldWithImage))
            assertNull("preparing alone must not fill SettledBlocks", SettledBlocks.get(coldLong))

            val beforeWarmRevisit = defaultDispatches.get()
            prepareRows(listOf(shortRow, coldRow, imageRow))
            assertEquals("warm cached rows must not dispatch or re-split again", beforeWarmRevisit, defaultDispatches.get())
            assertSame(imageSegments, rowDocuments.peekImageSegments("m-image"))

            rowDocuments.retainDocuments(emptySet())
            assertTrue(rowDocuments.peek("m-settled").isNullOrEmpty())
            SettledBlocks.put(settledLong, preSettledBlocks)
            prepareRows(listOf(settledRow))
            assertEquals("evicted rows hitting SettledBlocks must recover without background dispatch", beforeWarmRevisit, defaultDispatches.get())
            assertSame(preSettledBlocks, rowDocuments.peek("m-settled")?.get(settledLong))
        } finally {
            SettledBlocks.clearForTest()
            composition.dispose()
            recomposer.close()
            unmockkAll()
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
