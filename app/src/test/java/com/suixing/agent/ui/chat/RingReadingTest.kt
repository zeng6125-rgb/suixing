package com.suixing.agent.ui.chat

import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.foldTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingReadingTest {
    @Test
    fun `newly recorded reports win over estimates and older meta`() {
        val reading = ringReading(newestStored = 12_000, persistedContext = 9_000, estimate = 11_000)
        assertEquals(RingReading(12_000, estimated = false), reading)
    }

    @Test
    fun `meta supplies an older measured report when the loaded tail has none`() {
        val reading = ringReading(newestStored = 0, persistedContext = 9_000, estimate = 11_000)
        assertEquals(RingReading(9_000, estimated = false), reading)
    }

    @Test
    fun `an estimate is shown when there is no measured report`() {
        val reading = ringReading(newestStored = 0, persistedContext = 0, estimate = 11_000)
        assertEquals(RingReading(11_000, estimated = true), reading)
    }

    @Test
    fun `loading and an empty context have distinct readings`() {
        assertNull(ringReading(newestStored = 0, persistedContext = 0, estimate = null))
        assertEquals(RingReading(0, estimated = false), ringReading(0, 0, estimate = 0))
    }

    @Test
    fun `negative sources are treated as missing`() {
        assertEquals(RingReading(0, estimated = false), ringReading(-5, -1, estimate = -9))
    }

    @Test
    fun `a tool-only round stays authoritative after a later failure`() {
        val messages = listOf(
            StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "上一轮回答", promptTokens = 40_000),
            StoredMessage(
                id = "a2", role = Roles.ASSISTANT, promptTokens = 730_000,
                toolCalls = listOf(StoredToolCall("call-2", "run-shell", "{}")),
            ),
            StoredMessage(id = "error", role = Roles.SYSTEM, text = "这一轮最终失败：网络错误"),
        )
        val latest = latestContextPrompt(messages)
        assertEquals(foldTotals(messages).contextTokens, latest)
        assertEquals(RingReading(730_000, estimated = false), ringReading(latest, 40_000, estimate = null))
    }

    @Test
    fun `a new message or completion-only report cannot erase the last measured prompt`() {
        val messages = listOf(
            StoredMessage(id = "a1", role = Roles.ASSISTANT, promptTokens = 32_000),
            StoredMessage(id = "u2", role = Roles.USER, text = "继续"),
            StoredMessage(id = "a2", role = Roles.ASSISTANT, completionTokens = 10),
        )
        assertEquals(RingReading(32_000, estimated = false), ringReading(latestContextPrompt(messages), 0, estimate = 40_000))
    }

    @Test
    fun `compression ignores older reports until the first new recorded usage`() {
        val checkpoint = ContextCheckpoint("cp", 10, "u1", "a1", "摘要", 2, 80_000, anchorId = "a1")
        val before = StoredMessage(id = "a1", role = Roles.ASSISTANT, promptTokens = 80_000, timestamp = 10)
        val after = StoredMessage(id = "a2", role = Roles.ASSISTANT, promptTokens = 20_000, timestamp = 10)
        val beforeNewReport = latestContextPrompt(listOf(before), checkpoint)
        assertEquals(RingReading(18_000, estimated = true), ringReading(beforeNewReport, 80_000, 18_000, compressed = true))
        val afterNewReport = latestContextPrompt(listOf(before, after), checkpoint)
        assertEquals(RingReading(20_000, estimated = false), ringReading(afterNewReport, 80_000, 18_000, compressed = true))
    }

    @Test
    fun `legacy compression uses its timestamp when the anchor is unavailable`() {
        val checkpoint = ContextCheckpoint("cp", 10, "u1", "a1", "摘要", 2, 80_000)
        val before = StoredMessage(id = "a1", role = Roles.ASSISTANT, promptTokens = 80_000, timestamp = 9)
        val after = StoredMessage(id = "a2", role = Roles.ASSISTANT, promptTokens = 20_000, timestamp = 11)
        assertEquals(0, latestContextPrompt(listOf(before), checkpoint))
        assertEquals(20_000, latestContextPrompt(listOf(before, after), checkpoint))
    }
}
