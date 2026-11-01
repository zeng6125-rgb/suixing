package com.suixing.agent.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for #56 R20: recovery must not reuse stale payloads of equal length. */
class DraftPayloadReuseTest {
    private val original = listOf(
        StoredMessage(id = "user", role = Roles.USER, text = "question"),
        StoredMessage(id = "tool", role = Roles.TOOL, toolOutput = "Aa"),
        StoredMessage(id = "tail", role = Roles.ASSISTANT, text = "done"),
    )

    @Test fun `an unchanged reconstructed list may reuse its durable payload`() {
        assertTrue(sameSavedDraftMessages(original, original.map { it.copy() }))
    }

    @Test fun `same length change to last text must be saved`() {
        val changed = original.dropLast(1) + original.last().copy(text = "DONE")
        assertFalse(sameSavedDraftMessages(original, changed))
    }

    @Test fun `middle tool output changes even when list count tail id and length match`() {
        val changed = original.toMutableList()
        changed[1] = changed[1].copy(toolOutput = "BB") // same length and Java hash collision with Aa
        assertFalse(sameSavedDraftMessages(original, changed))
    }

    @Test fun `changing an image or a previous message cannot reuse a payload`() {
        val changed = original.toMutableList()
        changed[0] = changed[0].copy(imagesBase64 = listOf("different-image"))
        assertFalse(sameSavedDraftMessages(original, changed))
    }

    @Test fun `gc of weak snapshot must conservatively trigger a new payload`() {
        assertFalse(sameSavedDraftMessages(null, original))
    }
}
