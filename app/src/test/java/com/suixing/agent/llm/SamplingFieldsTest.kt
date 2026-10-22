package com.suixing.agent.llm

import com.suixing.agent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样参数**默认都不发**（用户 2026-10-23：「温度和 top-p 都默认不发」）。
 *
 * 三个请求构造器（OpenAI chat / Anthropic messages / OpenAI responses）都调
 * [samplingFields] 往 body 里塞这两个键，所以**断言这个函数返回空**，
 * 就等于断言三个请求体里都没有 `temperature` / `top_p`。
 */
class SamplingFieldsTest {

    @Test
    fun `defaults send neither temperature nor top_p`() {
        val fields = samplingFields(AppSettings())
        assertTrue("默认不该有采样参数，实际：$fields", fields.isEmpty())
        assertFalse(fields.any { it.first == "temperature" })
        assertFalse(fields.any { it.first == "top_p" })
    }

    @Test
    fun `temperature is sent only when explicitly switched on`() {
        val fields = samplingFields(AppSettings(sendTemperature = true, temperature = 0.4))
        assertEquals(listOf("temperature"), fields.map { it.first })
    }

    @Test
    fun `top_p is sent only when the slider is off the not-send stop`() {
        // 滑杆最左那一档 = 不发送（sendTopP = false）。**值存在也不发。**
        assertTrue(samplingFields(AppSettings(topP = 0.9f, sendTopP = false)).isEmpty())
        // 滑到具体数值才发。
        assertEquals(
            listOf("top_p"),
            samplingFields(AppSettings(topP = 0.9f, sendTopP = true)).map { it.first },
        )
    }

    @Test
    fun `both can be absent even when values exist`() {
        // 两个滑杆都停在最左「不发」—— 这是默认状态：值存在也不会泄漏进请求。
        val fields = samplingFields(
            AppSettings(temperature = 0.85, topP = 0.9f, sendTemperature = false, sendTopP = false),
        )
        assertTrue(fields.isEmpty())
    }
}
