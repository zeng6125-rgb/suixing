package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * `IncrementalLines` 的正确性：**任何输入序列下，结果都必须与 `text.split('\n')` 逐元素相等**。
 *
 * 思考链是按拍变长的，增量切分把每拍一次的全量 `split`（一千行 = 一千次字符串分配）省掉；
 * 但它是"上一次的结果 + 新增段"拼出来的，一旦拼错，界面上就是思考链少一行 / 多一行 / 行内容粘连。
 * 所以这里不做抽查，而是**逐字符喂一遍**再整体比对。
 */
class IncrementalLinesTest {

    @Test
    fun `纯追加一行`() {
        val splitter = IncrementalLines()
        assertEquals(listOf("a"), splitter.linesFor("a"))
        // 换行符本身先到（此时末行变空）
        assertEquals(listOf("a", ""), splitter.linesFor("a\n"))
        assertEquals(listOf("a", "b"), splitter.linesFor("a\nb"))
    }

    @Test
    fun `末行继续增长时替换最后一个元素而不是追加`() {
        val splitter = IncrementalLines()
        splitter.linesFor("hello")
        // 关键：这是「同一行在长」，不是「新的一行」
        assertEquals(listOf("hello world"), splitter.linesFor("hello world"))
    }

    @Test
    fun `文本变短或整段替换时整体重建`() {
        val splitter = IncrementalLines()
        splitter.linesFor("第一段\n第二段\n第三段")
        // 变短（流式回退 / 换了一条消息复用同一个实例）
        assertEquals(listOf("第一段"), splitter.linesFor("第一段"))
        // 不是前缀的替换
        assertEquals(listOf("全新", "内容"), splitter.linesFor("全新\n内容"))
    }

    @Test
    fun `一次追加多行`() {
        val splitter = IncrementalLines()
        splitter.linesFor("a")
        assertEquals(listOf("a", "b", "c", "d"), splitter.linesFor("a\nb\nc\nd"))
    }

    @Test
    fun `空文本与单个换行`() {
        val splitter = IncrementalLines()
        assertEquals(listOf(""), splitter.linesFor(""))
        assertEquals(listOf("", ""), splitter.linesFor("\n"))
    }

    @Test
    fun `逐字符喂入的结果与一次性 split 完全一致`() {
        val sample = buildString {
            append("第一行\n")
            append("第二行很长很长，中间没有任何换行\n")
            append("\n")
            append("```\n代码块\n```\n")
            append("最后一行")
        }
        repeat(20) { round ->
            val random = Random(round)
            val text = buildString { repeat(1 + random.nextInt(3)) { append(sample) } }
            val splitter = IncrementalLines()
            for (cut in 0..text.length) {
                val prefix = text.substring(0, cut)
                assertEquals(
                    "第 $round 轮，截到 $cut 时与 split 不一致",
                    prefix.split('\n'),
                    splitter.linesFor(prefix),
                )
            }
        }
    }
}
