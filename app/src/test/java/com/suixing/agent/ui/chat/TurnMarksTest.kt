package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TurnMarks] 的坐标换算。
 *
 * 守两件事（两种错都只表现成"高亮位置不对"，不报错）：
 *  - **喂序 → 可见要倒置**（漏了会跳到下一轮）；
 *  - **比较时要补 rowOffset**（漏了会在一屏内偏一行）。
 */
class TurnMarksTest {

    // 4 轮：可见下标 0、3、6、9 是轮首；turnIndices 已含 rowOffset（这里 rowOffset = 0）。
    private val marks = listOf(0, 3, 6, 9)

    @Test
    fun `the bottom of the feed is the newest turn`() {
        // 喂序 0 = 视觉最底。visible.size=10 ⇒ 可见下标 9 = 最后一轮。
        assertEquals(4, TurnMarks.current(0, 0, 10, 0, marks))
    }

    @Test
    fun `scrolling up walks back through the turns`() {
        // 轮首：0→第1轮、3→第2轮、6→第3轮、9→第4轮；每轮**向后**延伸到下一个轮首之前。
        // 换算：可见 = 9 - 喂序（size=10）。
        assertEquals("喂序 0 ⇒ 可见 9 ⇒ 第 4 轮", 4, TurnMarks.current(0, 0, 10, 0, marks))
        assertEquals("喂序 1 ⇒ 可见 8 ⇒ 落在第 3 轮（6..8 区间）", 3, TurnMarks.current(1, 0, 10, 0, marks))
        assertEquals("喂序 2 ⇒ 可见 7 ⇒ 第 3 轮", 3, TurnMarks.current(2, 0, 10, 0, marks))
        assertEquals("喂序 3 ⇒ 可见 6 ⇒ 正好第 3 轮的开头", 3, TurnMarks.current(3, 0, 10, 0, marks))
        assertEquals("喂序 6 ⇒ 可见 3 ⇒ 第 2 轮", 2, TurnMarks.current(6, 0, 10, 0, marks))
        assertEquals("喂序 9 ⇒ 可见 0 ⇒ 第 1 轮", 1, TurnMarks.current(9, 0, 10, 0, marks))
    }

    @Test
    fun `the feed prefix is subtracted before the mirror`() {
        // 有 2 条临时行时，喂序 2 才是"最新那一行"。
        assertEquals(4, TurnMarks.current(2, 2, 10, 0, marks))
        assertEquals(3, TurnMarks.current(5, 2, 10, 0, marks))
    }

    @Test
    fun `rowOffset is added on both sides so the comparison stays aligned`() {
        // turnIndices 已含 rowOffset=1 ⇒ [1,4,7,10]；比较值也要 +1。
        val withEarlier = marks.map { it + 1 }
        assertEquals(4, TurnMarks.current(0, 0, 10, 1, withEarlier))
        // 若实现漏掉 +rowOffset，喂序 3（可见 6，+1=7）会算成 4 而不是 3 —— 这条会抓到。
        assertEquals(3, TurnMarks.current(3, 0, 10, 1, withEarlier))
    }

    @Test
    fun `an out of range first visible is clamped`() {
        assertEquals(4, TurnMarks.current(-5, 0, 10, 0, marks))
        assertEquals(1, TurnMarks.current(999, 0, 10, 0, marks))
    }

    @Test
    fun `empty conversations report turn one`() {
        assertEquals(1, TurnMarks.current(0, 0, 0, 0, emptyList()))
        assertEquals(1, TurnMarks.current(0, 0, 5, 0, emptyList()))
    }
}
