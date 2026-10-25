package com.suixing.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #44 第一项：`run_shell` 里 `settings put` 的读回核验**依赖这个解析器**。
 *
 * 解析失败不会报错 —— 只是核验静默不生效，模型继续以为写成功了。所以边界要钉死。
 */
class SettingWriteParseTest {

    @Test
    fun `三个命名空间都能识别`() {
        assertEquals(SettingWrite("system", "screen_brightness", "128"), parseSettingWrite("settings put system screen_brightness 128"))
        assertEquals(SettingWrite("secure", "enabled_accessibility_services", "a.b/c"), parseSettingWrite("settings put secure enabled_accessibility_services a.b/c"))
        assertEquals(SettingWrite("global", "adb_enabled", "1"), parseSettingWrite("settings put global adb_enabled 1"))
    }

    @Test
    fun `值两侧的引号会被去掉`() {
        assertEquals(SettingWrite("global", "k", "1"), parseSettingWrite("settings put global k \"1\""))
        assertEquals(SettingWrite("global", "k", "hello world"), parseSettingWrite("settings put global k 'hello world'"))
    }

    @Test
    fun `读命令与其它命令不识别`() {
        assertNull(parseSettingWrite("settings get global adb_enabled"))
        assertNull(parseSettingWrite("settings delete global adb_enabled"))
        assertNull(parseSettingWrite("pm list packages -d"))
        assertNull(parseSettingWrite("wm size 1080x2340"))
    }

    @Test
    fun `非法命名空间不识别`() {
        assertNull(parseSettingWrite("settings put vendor foo 1"))
    }
}
