package com.suixing.agent.ui.chat.markdown

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PreparedPromotionTest {
    @Test fun `mounting a prepared settled document promotes that exact parse for later visits`() = runBlocking {
        SettledBlocks.clearForTest()
        val text = "mounted prepared document " + "body ".repeat(100)
        val prepared = requireNotNull(prepareMarkdownTexts(listOf(text))[text])
        assertNull("preparing alone must not fill the settled LRU", SettledBlocks.get(text))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(LocalPreparedMarkdown provides mapOf(text to prepared)) {
                    assertSame(prepared, rememberGrowingMarkdown(text, settled = true))
                }
            }
            assertSame("a mounted prepared document must enter the scroll-back LRU", prepared, SettledBlocks.get(text))
        } finally {
            composition.dispose()
            recomposer.close()
        }
        assertSame(prepared, requireNotNull(prepareMarkdownTexts(listOf(text))[text]))
    }

    @Test fun `a live document cannot promote a prepared prefix`() = runBlocking {
        SettledBlocks.clearForTest()
        val text = "still streaming prefix " + "body ".repeat(100)
        val prepared = parseMarkdown(text)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(LocalPreparedMarkdown provides mapOf(text to prepared)) {
                    rememberGrowingMarkdown(text, settled = false)
                }
            }
            assertNull(SettledBlocks.get(text))
        } finally {
            composition.dispose()
            recomposer.close()
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
