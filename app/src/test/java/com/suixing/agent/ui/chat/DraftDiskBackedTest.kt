package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `draftIsDiskBacked` —— 决定"能不能删草稿"的那条判据（S1）。
 *
 * 不可逆方向：判成 true 就删草稿，而草稿是进程被杀后唯一的副本。最容易漏的是
 * **冷启动**：base 恰恰是刚从盘上读回来的窗口（`shown != null`），只按 `shown == null`
 * 判断会把它当成"内存来源" ⇒ 草稿永不清（每次打开白读，且残留草稿将来会变成过时卡片）。
 */
class DraftDiskBackedTest {

    @Test
    fun `a cold-start window read back from disk counts as disk-backed`() {
        assertTrue(draftIsDiskBacked(shownPresent = true, baseFromDisk = true))
    }

    @Test
    fun `the plain cache path is not disk-backed`() {
        // 命中 LRU 且水位没立起来：base 来自内存（可能含保存失败的消息）⇒ 不能删草稿。
        assertFalse(
            draftIsDiskBacked(shownPresent = true, baseFromDisk = false, cacheDiskBacked = false),
        )
    }

    @Test
    fun `nothing on screen falls back to the stored conversation`() {
        assertTrue(draftIsDiskBacked(shownPresent = false, baseFromDisk = false))
    }

    @Test
    fun `a proof of an earlier successful write is enough on the cache path`() {
        // S1 的水位：缓存路径原来恒判"非磁盘来源" ⇒ 判重命中时草稿整个进程内都不清。
        assertTrue(
            draftIsDiskBacked(shownPresent = true, baseFromDisk = false, cacheDiskBacked = true),
        )
    }

    @Test
    fun `the watermark does not rescue the case where nothing was read from disk`() {
        // 组合补齐：`shown == null` 且声明来自磁盘 ⇒ true（与水位无关，避免两个参数互相掩盖）。
        assertTrue(draftIsDiskBacked(shownPresent = false, baseFromDisk = true, cacheDiskBacked = false))
    }
}
