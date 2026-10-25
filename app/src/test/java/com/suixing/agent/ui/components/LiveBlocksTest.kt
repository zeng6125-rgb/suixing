package com.suixing.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #44「块级复用打字机」的正确性判据。
 *
 * 流式期间每次发布都整段 `parseMarkdown` 是掉帧来源之一；[LiveBlocks] 只重解析最后一个
 * 未闭合的块。**增量结果必须与整段解析完全一致**，否则就是把省下来的时间换成了错内容。
 */
class LiveBlocksTest {

    /** 逐字符喂入，每一步都必须与整段解析一致 —— 这是"任何流式前缀都安全"的证明。 */
    private fun feed(charByChar: String): LiveBlocks {
        val live = LiveBlocks()
        val sb = StringBuilder()
        for (c in charByChar) {
            sb.append(c)
            assertEquals(
                "前缀不一致：'${sb}'",
                parseMarkdown(sb.toString()),
                live.blocksFor(sb.toString()),
            )
        }
        return live
    }

    @Test
    fun `纯段落逐字喂入与整段解析一致`() {
        feed("第一段文字。\n\n第二段文字，比较长一些，还会继续增长。")
    }

    @Test
    fun `多块混合（标题 列表 代码 引用）逐字一致`() {
        feed("# 标题\n\n- 一\n- 二\n\n```kt\nval a = 1\n```\n\n> 引用\n\n最后一段。")
    }

    @Test
    fun `没有空行的长段落也一致`() {
        feed("这是一段没有任何空行的很长文本，" + "继续增长".repeat(20) + "。")
    }

    @Test
    fun `变短或换块时整体重建`() {
        val live = LiveBlocks()
        live.blocksFor("第一段。\n\n第二段。")
        // 换成了完全不同的内容（不是前缀）：必须重建
        val other = "完全不同的内容"
        assertEquals(parseMarkdown(other), live.blocksFor(other))
    }

    @Test
    fun `同一文本重复调用返回同一结果`() {
        val live = LiveBlocks()
        val t = "段落一。\n\n段落二。"
        val a = live.blocksFor(t)
        val b = live.blocksFor(t)
        assertEquals(parseMarkdown(t), a)
        assertEquals(a, b)
    }
}
