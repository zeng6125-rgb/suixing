package com.suixing.agent.data

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 温度默认**不发送**（用户 2026-10-23：「感觉默认不加了吧，可以在高级调节进入有开关供打开」）。
 *
 * 理由：推理型端点（OpenAI 推理系等）会直接拒收 `temperature`（400），其余的多半忽略；
 * 而 agent 场景要的是确定性，不是采样多样性。要让它在高级参数里手动打开。
 */
class TemperatureDefaultTest {

    @Test
    fun `temperature is not sent by default`() {
        assertFalse("默认不发送 temperature", AppSettings().sendTemperature)
    }
}
