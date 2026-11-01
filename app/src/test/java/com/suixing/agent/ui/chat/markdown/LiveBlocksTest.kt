package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * issue #44「块级复用打字机」的正确性判据。
 *
 * 流式期间每次发布都整段 `parseMarkdown` 是掉帧来源之一；[LiveBlocks] 只重解析最后一个
 * 未闭合的块。**增量结果必须与整段解析完全一致**，否则就是把省下来的时间换成了错内容。
 */
class LiveBlocksTest {

    /** 两行 = 一个段落分隔，写成常量避免源码里出现转义序列。 */
    private val N2 = run {
        val lf = 10.toChar().toString()
        lf + lf
    }

    @Test fun `nonzero tail offsets keep final newlines and prior list snapshots stable`() {
        val live = LiveBlocks()
        val firstSource = "first\n\nsecond\n\nthird"
        val first = live.blocksFor(firstSource)
        for (suffix in listOf("\n", "\n\n", "\n\nfourth", "\n\nfourth\n\nlast")) {
            val source = firstSource + suffix
            assertEquals(parseMarkdown(source), live.blocksFor(source))
            assertEquals(parseMarkdown(firstSource), first)
        }
    }

    @Test fun `newly completed paragraphs retain existing parsed blocks`() {
        val parser = LiveBlocks()
        val first = parser.blocksFor("first\n\nsecond")
        val source = "first\n\nsecond\n\nthird"
        val next = parser.blocksFor(source)
        assertEquals(parseMarkdown(source), next)
        assertSame(first.first(), next.first())
    }

    @Test fun `completing a fenced block retains earlier prose and all internal blank lines`() {
        val parser = LiveBlocks()
        val first = parser.blocksFor("intro\n\n```kt\na\n\nb")
        val source = "intro\n\n```kt\na\n\nb\n```\n\nafter"
        val next = parser.blocksFor(source)
        assertEquals(parseMarkdown(source), next)
        assertSame(first.first(), next.first())
    }

    @Test fun `four backtick fences never retain an internal blank line as a boundary`() {
        val parser = LiveBlocks()
        parser.blocksFor("````kt\na\n\nb")
        val complete = "````kt\na\n\nb\n````\n\nend"
        assertEquals(parseMarkdown(complete), parser.blocksFor(complete))
        feed(complete)
    }

    @Test fun `literal backticks in code do not close a fence or split its body`() {
        feed("intro\n\n```sh\necho `id`\n\necho done\n```\n\nend")
        feed("intro\n\n```sh\necho `\n\necho done\n```\n\nend")
    }

    @Test fun `fence looking table rows do not change code block boundaries`() {
        val parser = LiveBlocks()
        parser.blocksFor("| h |\n| - |\n```|\n\n```\na\n\nb")
        val complete = "| h |\n| - |\n```|\n\n```\na\n\nb\n```\n\nend"
        assertEquals(parseMarkdown(complete), parser.blocksFor(complete))
        feed(complete)
    }

    @Test fun `nested lists grow into one parent block at every streaming prefix`() {
        feed("intro\n\n3. parent\n  - child\n    7. grandchild\n  - child2\n4. next\n\nend")
    }

    @Test fun `nested lists retain completed prefix blocks when the next paragraph grows`() {
        val live = LiveBlocks()
        val first = live.blocksFor("3. a\n  - child\n4. b\n\nnext")
        val next = live.blocksFor("3. a\n  - child\n4. b\n\nnext paragraph")
        assertEquals(2, first.size)
        assertSame(first.first(), next.first())
        assertEquals(parseMarkdown("3. a\n  - child\n4. b\n\nnext paragraph"), next)
    }

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

    // ---- issue #59 A1：受信任追加游标 ----

    /** 同来源、版本递增 ⇒ 走信任分支，结果必须与整段解析一致（省掉的比较不能换来内容差异）。 */
    @Test fun `trusted append cursor produces the same blocks as a full parse`() {
        val live = LiveBlocks()
        val sourceId = 0x5EED
        val first = "first" + N2 + "second"
        live.blocksFor(first, appendFrom = 1, sourceId = sourceId)
        var source = first
        for (version in 2..4) {
            source = source + N2 + "para$version"
            assertEquals(
                "trusted append must match a full parse (v$version)",
                parseMarkdown(source),
                live.blocksFor(source, appendFrom = version, sourceId = sourceId),
            )
        }
    }

    /** 版本倒退（重试 / 清屏重放）必须回落全量校验，且把稳定块重新算对。 */
    @Test fun `a version going backwards falls back to full verification`() {
        val live = LiveBlocks()
        val sourceId = 0x5EED
        live.blocksFor("alpha" + N2 + "beta", appendFrom = 5, sourceId = sourceId)
        val rewritten = "alpha" + N2 + "GAMMA"
        assertEquals(parseMarkdown(rewritten), live.blocksFor(rewritten, appendFrom = 2, sourceId = sourceId))
    }

    /** 换来源（实例不同）⇒ 游标作废，即使版本相同也不能信任。 */
    @Test fun `a different source id is never trusted`() {
        val live = LiveBlocks()
        live.blocksFor("one" + N2 + "two", appendFrom = 3, sourceId = 0xAAAA)
        val other = "one" + N2 + "TWO"
        assertEquals(parseMarkdown(other), live.blocksFor(other, appendFrom = 3, sourceId = 0xBBBB))
    }

    /** 没有来源（历史消息、默认参数）一律走全量校验，行为与改动前一致。 */
    @Test fun `default cursor keeps the original verification`() {
        val live = LiveBlocks()
        live.blocksFor("x" + N2 + "y")
        val changed = "x" + N2 + "Z"
        assertEquals(parseMarkdown(changed), live.blocksFor(changed))
    }

    /** 打字机形态：同来源、同版本、文本变长（揭示更长前缀）⇒ 可信。 */
    @Test fun `same version but a longer prefix from the same source is trusted`() {
        val live = LiveBlocks()
        val sourceId = 0x1234
        val full = "para one" + N2 + "para two"
        for (length in listOf(8, 9, 17, full.length)) {
            val prefix = full.take(length)
            assertEquals(
                "prefix of length $length must match a full parse",
                parseMarkdown(prefix),
                live.blocksFor(prefix, appendFrom = 7, sourceId = sourceId),
            )
        }
    }
}
