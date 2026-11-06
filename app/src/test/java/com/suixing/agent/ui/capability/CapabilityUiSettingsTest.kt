package com.suixing.agent.ui.capability

import com.suixing.agent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * issue #68 P1-C：能力页只依赖这几个字段。
 *
 * 判据不是"感觉重组少了"，而是**投影相等**——投影相同 ⇒ `distinctUntilChanged` 不发射 ⇒
 * 那棵子树不会重组。
 */
class CapabilityUiSettingsTest {

    @Test fun `an unrelated settings change produces an equal projection`() {
        val base = AppSettings()
        val unrelated = base.copy(model = "another-model", temperature = 0.9)
        assertEquals(
            "无关字段变化不该让能力页重组",
            capabilityUiSettings(base),
            capabilityUiSettings(unrelated),
        )
    }

    @Test fun `every field the page reads is part of the projection`() {
        val base = AppSettings()
        assertNotEquals(capabilityUiSettings(base), capabilityUiSettings(base.copy(preferredCapability = "shizuku")))
        assertNotEquals(capabilityUiSettings(base), capabilityUiSettings(base.copy(floatingConsole = !base.floatingConsole)))
        assertNotEquals(capabilityUiSettings(base), capabilityUiSettings(base.copy(consoleWhileRunning = !base.consoleWhileRunning)))
        assertNotEquals(capabilityUiSettings(base), capabilityUiSettings(base.copy(keepScreenOn = !base.keepScreenOn)))
        assertNotEquals(capabilityUiSettings(base), capabilityUiSettings(base.copy(rootCheckedAt = base.rootCheckedAt + 1)))
    }
}
