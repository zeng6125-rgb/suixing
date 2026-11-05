package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Exercise the real position owner with measurement ahead of child coordinate placement. */
class NavigationAnchorHandoffTest {
    private lateinit var list: LazyListState
    private lateinit var layout: LazyListLayoutInfo
    private lateinit var owner: TranscriptPosition
    private var textY = 100f
    private var reading = true
    private var following = false

    @Before fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 1000L
        mockkStatic("androidx.compose.ui.layout.LayoutCoordinatesKt")
        list = mockk(relaxed = true)
        layout = mockk<LazyListLayoutInfo>()
        val item = mockk<LazyListItemInfo>()
        every { item.key } returns "row"
        every { layout.visibleItemsInfo } returns listOf(item)
        every { layout.beforeContentPadding } returns 0
        every { layout.afterContentPadding } returns 0
        every { layout.viewportSize } returns IntSize(1000, 1000)
        every { layout.totalItemsCount } returns 0
        every { list.layoutInfo } returns layout
        every { list.firstVisibleItemIndex } returns 2
        every { list.firstVisibleItemScrollOffset } returns 100
        every { list.requestScrollToItem(any(), any()) } just Runs
        val root = mockk<LayoutCoordinates>()
        every { root.positionInRoot() } returns Offset.Zero
        val coordinates = mockk<LayoutCoordinates>()
        every { coordinates.isAttached } returns true
        every { coordinates.size } returns IntSize(200, 500)
        every { coordinates.positionInRoot() } answers { Offset(0f, textY) }
        every { coordinates.boundsInRoot() } answers { Rect(0f, textY, 200f, textY + 500f) }
        val text = mockk<TextLayoutResult>()
        val input = mockk<TextLayoutInput>()
        every { input.text } returns AnnotatedString("the text being read")
        every { text.layoutInput } returns input
        every { text.lineCount } returns 1
        every { text.getLineForVerticalPosition(any()) } returns 0
        every { text.getLineStart(0) } returns 0
        every { text.getLineTop(0) } returns 0f
        every { text.getLineForOffset(any()) } returns 0
        owner = TranscriptPosition(list).also {
            it.reading = { reading }
            it.following = { following }
        }
        owner.viewportPlaced(root)
        owner.textPlaced("text", "row", coordinates) { text }
        owner.interactionChanged()
        clearMocks(list, answers = false)
    }

    @After fun tearDown() {
        unmockkStatic(SystemClock::class)
        unmockkStatic("androidx.compose.ui.layout.LayoutCoordinatesKt")
    }

    @Test fun `navigation and delayed interaction notification cannot capture before placement`() {
        owner.rebaseAfterNavigation()
        owner.interactionChanged() // The delayed snapshotFlow callback also arrives before placement.
        textY = 140f // The child now receives the position already described by lazy layoutInfo.
        owner.contentPlaced()
        verify(exactly = 0) { list.requestScrollToItem(any(), any()) }

        textY = 155f // Later genuine content growth must still be anchored normally.
        owner.contentPlaced()
        verify(exactly = 1) { list.requestScrollToItem(2, 115) }
    }

    @Test fun `cancelled navigation waits for a readable placement instead of restoring its old point`() {
        reading = false
        owner.rebaseAfterNavigation()
        textY = 140f
        owner.contentPlaced()
        reading = true
        owner.interactionChanged()
        textY = 170f
        owner.contentPlaced()
        verify(exactly = 0) { list.requestScrollToItem(any(), any()) }
    }

    @Test fun `normal reading interaction still captures immediately`() {
        owner.interactionChanged()
        textY = 120f
        owner.contentPlaced()
        verify(exactly = 1) { list.requestScrollToItem(2, 120) }
    }

    @Test fun `an unreadable press window keeps the point so a settled row is repaired`() {
        // Finger down: reading becomes unavailable. The old behaviour dropped the point
        // here, so a live→settled height change in that window went uncompensated.
        reading = false
        owner.interactionChanged()
        // The run settles while the finger is still down: content grows above the reader.
        textY = 170f
        owner.contentPlaced()
        verify(exactly = 0) { list.requestScrollToItem(any(), any()) }

        // On release the SAME pre-settle point must repair the jump exactly once.
        reading = true
        owner.interactionChanged()
        owner.contentPlaced()
        verify(exactly = 1) { list.requestScrollToItem(2, 170) }
    }

    @Test fun `a real scroll during the unreadable window drops the stale point`() {
        reading = false
        owner.interactionChanged()
        // The user genuinely scrolls: the old Y no longer describes what they read.
        every { list.firstVisibleItemIndex } returns 5
        every { list.firstVisibleItemScrollOffset } returns 40
        textY = 170f
        owner.contentPlaced()
        verify(exactly = 0) { list.requestScrollToItem(any(), any()) }

        reading = true
        owner.interactionChanged()
        owner.contentPlaced()
        // A fresh point is captured from the post-scroll geometry; no pull-back repair.
        verify(exactly = 0) { list.requestScrollToItem(any(), any()) }
    }

    @Test fun `following pins to the tail even when the reader is far from the last item`() {
        // 流式输出把 firstVisibleItemIndex 推得离末尾很远；追底必须仍然发生，
        // 不能被任何「末尾附近才 pin」的守卫挡掉（那会破坏流式追底）。
        following = true
        every { layout.totalItemsCount } returns 40
        every { list.canScrollForward } returns true
        every { list.firstVisibleItemIndex } returns 2
        val root = mockk<LayoutCoordinates>()
        every { root.positionInRoot() } returns Offset.Zero
        owner.viewportPlaced(root)
        verify(exactly = 1) { list.requestScrollToItem(39, TRANSCRIPT_END_OFFSET) }
    }

    @Test fun `following does not pin when the list cannot scroll forward`() {
        following = true
        every { layout.totalItemsCount } returns 40
        every { list.canScrollForward } returns false
        val root = mockk<LayoutCoordinates>()
        every { root.positionInRoot() } returns Offset.Zero
        owner.viewportPlaced(root)
        verify(exactly = 0) { list.requestScrollToItem(any(), any()) }
    }
}
