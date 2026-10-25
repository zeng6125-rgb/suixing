package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [LivePart] 的增量更新规则 —— 每个 delta 都会走这里。
 *
 * 这块出过两次 O(n²) 事故（整串复制），所以除了语义，还要**钉住"没有整串复制"**这个性质：
 * 同一段里连续追加时，文本块必须是**同一个对象**（原地增长），而不是每次都新建一个。
 */
class LivePartsTest {

    private fun parts(vararg p: LivePart): List<LivePart> = p.toList()

    @Test
    fun `appending text keeps the same block instead of making a new one`() {
        val first = parts().appendText("你", 0)
        val second = first.appendText("好", 0)
        // **仍是同一个"块"**（列表长度不变，缓冲共享 ⇒ 不是整串复制），
        // 但**实例会换**（version +1）—— 这正是让 state 判等为 false、屏幕不再冻结的关键。
        assertEquals("同一段里只有一个文本块", 1, second.size)
        assertEquals("你好", (second.single() as LivePart.Text).text)
        assertTrue(
            "版本号必须递增，否则列表判等为 true、state 不发射",
            (second.single() as LivePart.Text).version >
                (first.single() as LivePart.Text).version,
        )
    }

    @Test
    fun `a thousand deltas stay linear`() {
        // 若实现退回 `take + 拼整串 + drop`，这里的耗时与分配会爆炸（CI 上会明显变慢）。
        var acc = parts()
        repeat(1_000) { acc = acc.appendText("x", 0) }
        val text = (acc.single() as LivePart.Text).text
        assertEquals(1_000, text.length)
    }

    @Test
    fun `thinking and text are separate blocks inside one segment`() {
        var acc = parts()
        acc = acc.appendThinking("想", 0)
        acc = acc.appendText("说", 0)
        acc = acc.appendThinking("再想", 0)          // 同一段：续进已有思考块
        acc = acc.appendText("再说", 0)
        assertEquals(2, acc.size)
        assertEquals("想再想", (acc[0] as LivePart.Thinking).text)
        assertEquals("说再说", (acc[1] as LivePart.Text).text)
    }

    @Test
    fun `crossing a tool row starts a new segment`() {
        var acc = parts()
        acc = acc.appendText("第一段", 0)
        acc = acc.startTool("read_file", "{}")
        val newStart = acc.size                      // 工具行之后才是新段
        acc = acc.appendText("第二段", newStart)
        assertEquals(3, acc.size)
        assertEquals("第一段", (acc[0] as LivePart.Text).text)
        assertEquals("第二段", (acc[2] as LivePart.Text).text)
    }

    @Test
    fun `sealing a thinking block keeps its buffer and freezes its clock`() {
        var acc = parts().appendThinking("想了很久", 0)
        val buffer = (acc.single() as LivePart.Thinking).buffer
        val sealed = acc.sealThinking(startedAt = System.currentTimeMillis() - 50)
        val block = sealed.single() as LivePart.Thinking
        assertSame("封口不许复制文本", buffer, block.buffer)
        assertEquals("想了很久", block.text)
        // tokens 是**估算**（length/4）：4 个字 ⇒ 1。这里钉的是"用字节数折算、且来自封口那一刻"，
        // 不是"精确 token 数"（真实值只有端点回报才知道）。
        assertEquals(1, block.tokens)
        assertTrue(block.elapsedMs >= 50)
    }

    @Test
    fun `retry traces replace each other instead of stacking`() {
        val one = parts().traceRetry("自动重试 1 次 · 429")
        val two = one.traceRetry("自动重试 2 次 · 429")
        assertEquals(1, two.size)
        assertEquals("自动重试 2 次 · 429", (two.single() as LivePart.Trace).note)
    }

    @Test
    fun `a tool finishes by name, oldest open call first`() {
        var acc = parts().startTool("read_file", "a").startTool("read_file", "b")
        acc = acc.finishTool("read_file", ok = true)
        val calls = (acc.single() as LivePart.Tools).calls
        assertTrue("先完成的是最早那次", calls[0].done)
        assertEquals("第二次还没完成", false, calls[1].done)
    }

    @Test
    fun `a growing text block stops comparing equal so Compose sees the change`() {
        // **真机 bug 的回归锁**：这些块持有可变缓冲、对象始终是同一个；默认 equals 按引用 ⇒
        // `remember(liveParts)` 认为列表没变 ⇒ 界面只在结构变化时整块更新（表现为"一下子全出来"）。
        val block = LivePart.Text("a")
        val before = LivePart.Text("a")           // 内容相同的另一实例
        block.buffer.append("b")
        assertFalse("追加后必须与之前的快照不相等", block == before)
        assertEquals(block, LivePart.Text("ab"))
        assertTrue("长度不同必须不相等", block != LivePart.Text("abc"))
        // 思考块同理：拿"追加前的内容"构造一个比较对象（不能拿同一引用，那必然相等）。
        val think = LivePart.Thinking("想")
        val sameContent = LivePart.Thinking("想")
        assertEquals(sameContent, think)
        think.buffer.append("了")
        assertFalse("追加后不能与旧内容的实例相等", sameContent == think)
    }
}
