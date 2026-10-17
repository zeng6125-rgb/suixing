package com.suixing.agent.capability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自带 bash 在 Android 上会被 app 进程的 seccomp 过滤器杀掉（exit 159 = 128+SIGSYS）。
 * 这里只测「怎么认出这个信号」这一纯逻辑；真机上的回退行为要靠设备验证。
 */
class SeccompFallbackTest {

    @Test
    fun `159 is the seccomp signal`() {
        assertTrue(looksLikeSeccompKill(SECCOMP_SIGSYS_EXIT))
        assertTrue(looksLikeSeccompKill(159))
    }

    @Test
    fun `ordinary exit codes are not treated as seccomp`() {
        assertFalse("成功", looksLikeSeccompKill(0))
        assertFalse("普通失败", looksLikeSeccompKill(1))
        assertFalse("命令不存在", looksLikeSeccompKill(127))
        assertFalse("SIGKILL", looksLikeSeccompKill(137))
        assertFalse("我们的报错码", looksLikeSeccompKill(-1))
    }

    @Test
    fun `the note names the exit code and the shell actually used`() {
        assertTrue(SECCOMP_FALLBACK_NOTE.contains("159"))
        assertTrue("要写明已经改用系统 sh", SECCOMP_FALLBACK_NOTE.contains("系统 sh"))
    }
}
