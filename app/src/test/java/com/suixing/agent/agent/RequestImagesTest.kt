package com.suixing.agent.agent

import com.suixing.agent.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出站图片预算：只降级**工具截图**，用户上传的附件一张都不动；
 * 只在真超预算时才动，且最老的先降级。
 */
class RequestImagesTest {

    private fun shot(bytes: Int) = ChatMessage.screenshot("（截图）", listOf("x".repeat(bytes)))

    private fun upload(bytes: Int) = ChatMessage.user("看看这张图", listOf("y".repeat(bytes)))

    @Test
    fun `user uploads are never dropped even when the budget is blown`() {
        val wire = listOf(upload(5000), shot(5000), shot(5000))

        val pruned = pruneRequestImages(wire, budgetChars = 1)

        assertTrue("用户附件必须保留：$pruned", pruned[0].allImages.isNotEmpty())
    }

    @Test
    fun `oldest tool screenshots are dropped first`() {
        val wire = listOf(shot(100), shot(100), shot(100))

        // 三张共 300，预算 150：降第 1 张后 200 仍超，再降第 2 张后 100 才达标。
        val pruned = pruneRequestImages(wire, budgetChars = 150)

        assertTrue("最老的应先降级", pruned[0].allImages.isEmpty())
        assertTrue("只降一张还不够，第二张也要降", pruned[1].allImages.isEmpty())
        assertTrue("最新的应保留", pruned[2].allImages.isNotEmpty())
    }

    @Test
    fun `a dropped screenshot says how to get the picture back`() {
        val pruned = pruneRequestImages(listOf(shot(100), shot(100)), budgetChars = 100)

        assertTrue("要写清为什么没了", pruned[0].text.contains("省略"))
        assertTrue("要给出取回办法", pruned[0].text.contains("ui_screenshot"))
    }

    @Test
    fun `a request inside the budget is returned untouched`() {
        val wire = listOf(shot(10), shot(10))

        assertSame("没有超预算就不该新建列表", wire, pruneRequestImages(wire, budgetChars = 1000))
    }

    @Test
    fun `a non-positive budget disables the trim`() {
        val wire = listOf(shot(10), shot(10))

        assertSame(wire, pruneRequestImages(wire, budgetChars = 0))
    }

    @Test
    fun `a demoted screenshot stays identifiable as a screenshot`() {
        val pruned = pruneRequestImages(listOf(shot(100), shot(100)), budgetChars = 100)

        assertTrue("降级后仍要知道它是截图，下一轮才不会把它当附件", pruned[0].toolScreenshot)
        assertFalse(pruned[0].allImages.isNotEmpty())
    }

    @Test
    fun `dropping one screenshot leaves the others intact`() {
        val wire = listOf(shot(100), upload(100), shot(100))

        // 用户附件不计入预算：两张工具截图共 200，预算 100 ⇒ 只降最老的那张。
        val pruned = pruneRequestImages(wire, budgetChars = 100)

        assertEquals("用户附件必须原样保留", 1, pruned[1].allImages.size)
        assertTrue(pruned[0].allImages.isEmpty())
        assertTrue("新的工具截图要保留", pruned[2].allImages.isNotEmpty())
    }
}
