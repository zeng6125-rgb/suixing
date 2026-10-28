package com.suixing.agent.ui.drawer

import com.suixing.agent.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DrawerPreviewTest {
    private fun chat(id: String, pinned: Boolean = false) = Conversation(id = id, title = id, pinned = pinned)

    @Test fun `preview changes only the dragged section and leaves original order intact`() {
        val original = DrawerSections(listOf(chat("p", true)), listOf(chat("a"), chat("b"), chat("c")))
        val preview = previewDrawerReorder(original, "a", "c")!!
        assertEquals(listOf("b", "c", "a"), preview.normal.map { it.id })
        assertEquals(listOf("a", "b", "c"), original.normal.map { it.id })
        assertSame(original.pinned, preview.pinned)
    }

    @Test fun `preview refuses cross section and absent targets`() {
        val original = DrawerSections(listOf(chat("p", true)), listOf(chat("a")))
        assertNull(previewDrawerReorder(original, "a", "p"))
        assertNull(previewDrawerReorder(original, "p", "a"))
        assertNull(previewDrawerReorder(original, "a", "gone"))
        assertNull(previewDrawerReorder(original, "a", "a"))
    }

    @Test fun `successive preview moves follow displayed slots without sorting`() {
        val original = DrawerSections(emptyList(), listOf(chat("a"), chat("b"), chat("c")))
        val down = previewDrawerReorder(original, "a", "b")!!
        val back = previewDrawerReorder(down, "a", "b")!!
        assertEquals(original, back)
    }
}
