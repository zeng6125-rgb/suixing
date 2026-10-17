package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 画像的「整篇替换」协议下，模型抄漏旧条目＝旧信息消失。`writeGuarded` 就是那道闸：
 * 丢得太多整次拒绝、写之前留备份，用户手改（`write`）不受限。
 */
class ProfileGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): UserProfileStore {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return UserProfileStore(context)
    }

    private fun file(): File = File(folder.root, "profile.md")

    private val old = """
        ### 沟通风格
        - 喜欢先看结论
        - 说话简短
        - 不接受含糊其辞
        - 常用英文术语
    """.trimIndent()

    @Test
    fun `keeping every old bullet is written`() = runBlocking {
        file().writeText(old)
        val next = old + "\n- 喜欢直接给方案\n"

        val reason = store().writeGuarded(next)

        assertNull("应写入：$reason", reason)
        assertTrue(file().readText().contains("喜欢直接给方案"))
    }

    @Test
    fun `dropping most old bullets is refused`() = runBlocking {
        file().writeText(old)
        val next = "### 沟通风格\n- 喜欢先看结论\n"

        val reason = store().writeGuarded(next)

        assertTrue("应拒绝，实际：$reason", reason != null)
        assertTrue("要说清丢了几条：$reason", reason!!.contains("3/4"))
        assertTrue("拒绝时文件不能被改", file().readText() == old)
    }

    @Test
    fun `a previous version is kept as backup`() = runBlocking {
        file().writeText(old)

        store().writeGuarded(old + "\n- 喜欢直接给方案\n")

        val backup = File(folder.root, "profile.md.bak")
        assertTrue("应留备份", backup.exists())
        assertTrue("备份应是旧内容", backup.readText() == old)
    }

    @Test
    fun `too long is refused before anything is written`() = runBlocking {
        file().writeText(old)

        val reason = store().writeGuarded("x".repeat(UserProfileStore.MAX_LENGTH + 1))

        assertTrue("应拒绝：$reason", reason != null)
        assertTrue("拒绝时文件不能被改", file().readText() == old)
    }

    @Test
    fun `an empty profile is never blocked`() = runBlocking {
        val reason = store().writeGuarded("- 第一条\n- 第二条\n")

        assertNull("第一次写没有旧条目可丢：$reason", reason)
        assertFalse(file().readText().isBlank())
    }
}
