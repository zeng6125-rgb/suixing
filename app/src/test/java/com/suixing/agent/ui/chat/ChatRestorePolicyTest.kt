package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRestorePolicyTest {

    private val queued = PendingSteer(id = "s1", text = "排队那句话")

    @Test
    fun `an empty field takes the queued text as it is`() {
        val merged = mergeRestoredDraft(Draft(), queued, restoredImages = 0)

        assertEquals("排队那句话", merged.text)
        assertEquals(emptyList<String>(), merged.imageNames)
    }

    @Test
    fun `text typed while decoding is kept and the line is appended`() {
        val latest = Draft(text = "用户新输入  ", attachmentUri = "uri", attachmentName = "file.pdf")

        val merged = mergeRestoredDraft(latest, queued, restoredImages = 2)

        assertEquals("用户新输入 排队那句话", merged.text)
        assertEquals("uri", merged.attachmentUri)
        assertEquals("file.pdf", merged.attachmentName)
        assertEquals(listOf("图片", "图片"), merged.imageNames)
    }

    @Test
    fun `already restored pictures are kept in front`() {
        val latest = Draft(text = "x", imageNames = listOf("图片"))

        val merged = mergeRestoredDraft(latest, queued, restoredImages = 1)

        assertEquals(listOf("图片", "图片"), merged.imageNames)
    }
}
