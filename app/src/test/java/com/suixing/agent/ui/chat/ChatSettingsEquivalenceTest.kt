package com.suixing.agent.ui.chat

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住聊天页订阅收窄的「黑名单」语义（`ChatScreen.kt` 的 `sameForChat`）。
 *
 * 两条容易写错的地方：
 * 1. 抹平的字段必须真的**不影响等价性**（否则这些写入会重新把整页拖进重组）；
 * 2. 其余字段必须**照常算变化**（否则页面会读到过期值 —— 这是比性能更严重的错）。
 */
class ChatSettingsEquivalenceTest {

    private val base = AppSettings()

    @Test
    fun `控制台几何的改动不算聊天页的变化`() {
        assertTrue(base.sameForChat(base.copy(consoleWidthDp = 480)))
        assertTrue(base.sameForChat(base.copy(consoleHeightDp = 260)))
        assertTrue(base.sameForChat(base.copy(floatingConsole = !base.floatingConsole)))
    }

    @Test
    fun `语音转写模型必须算变化`() {
        // **不能抹平**：本页不读它，但把整份 settings 传给 `VoiceOverlay`，那里用它判断
        // 「转写是否已配置」（`VoiceOverlay.kt:109`）并显示模型名（`:325-326`）。
        // 抹平 = 用户改完转写模型回来，语音卡还显示旧模型或「未配置」，且不会自愈。
        assertFalse(base.sameForChat(base.copy(asrModel = "whisper-large-v3")))
    }

    @Test
    fun `聊天页会读的字段必须算变化`() {
        // 抽几条本页真的读的：气泡宽度档（`bubbleWidthFraction` 是它的派生 getter，不能 copy）、
        // 思考展开、工具展开、时间戳、回车行为、助手列表。
        // 注意 `assistants`/`providers` 的默认值就是空列表，拿 `copy(x = emptyList())` 当
        // 「有变化」是无效断言 —— 这里必须构造一个真的不同的值。
        assertFalse(base.sameForChat(base.copy(bubbleWidth = if (base.bubbleWidth == 0) 1 else 0)))
        assertFalse(base.sameForChat(base.copy(showReasoning = !base.showReasoning)))
        assertFalse(base.sameForChat(base.copy(expandTools = !base.expandTools)))
        assertFalse(base.sameForChat(base.copy(showTimestamps = !base.showTimestamps)))
        assertFalse(base.sameForChat(base.copy(enterKeySends = !base.enterKeySends)))
        assertFalse(base.sameForChat(base.copy(assistants = listOf(Assistant(id = "a1")))))
    }

    @Test
    fun `抹平字段之外的改动一律算变化`() {
        // 黑名单式的意义：漏写进抹平名单的新字段，最坏是少省一次重组，绝不会被误判成「没变」。
        assertFalse(base.sameForChat(base.copy(model = "some-other-model")))
        assertFalse(base.sameForChat(base.copy(activeAssistantId = "someone-else")))
    }

    @Test
    fun `同一份设置自身等价`() {
        assertTrue(base.sameForChat(base))
    }
}
