package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RestoreTarget] 的换算与放弃条件。
 *
 * 这条链路出过**三次**同类事故（`messageIds` 冻结、`itemCount` 冻结、`visible`/`feedPrefix` 冻结），
 * 每次都表现成"位置没恢复、退回底部"，且不崩溃不打日志。前两次修的是"取最新值"，
 * 但**换算本身**从来没被断言守过 —— 一旦有人把 `feedPrefix` 的正负弄反，症状与那三次一模一样。
 */
class RestoreTargetTest {

    /** 喂序 = 反转后的 visible，前面再插 `prefix` 条临时行。 */
    private fun fedIndex(visibleIds: List<String>, saved: String?, prefix: Int) =
        RestoreTarget.fedIndex(visibleIds, saved, prefix)

    @Test
    fun `the newest stored row sits right after the temporary rows`() {
        // 喂序是 visible 的**反转**（底锚 reverseLayout：喂序 0 = 视觉最底 = 最新那行），
        // 前面再插 prefix 条临时行。
        val ids = listOf("a", "b", "c")      // a 最旧、c 最新
        assertEquals("最新那行紧挨着临时行", 2, fedIndex(ids, "c", 2)!!)
        assertEquals("没有临时行时，最新那行就是 0", 0, fedIndex(ids, "c", 0)!!)
        assertEquals("最旧那行在最远（size-1 + prefix）", 2 + 5, fedIndex(ids, "a", 5)!!)
    }

    @Test
    fun `the offset is the mirror position, not the visible index`() {
        val ids = listOf("a", "b", "c", "d")
        // 有人把「反转」漏掉时，会算出 1 而不是 2 —— 落到错误的一行上（真机上是"偏一两行"）。
        assertEquals(2, fedIndex(ids, "b", 0)!!)
        assertEquals(3, fedIndex(ids, "a", 0)!!)
        assertEquals(0, fedIndex(ids, "d", 0)!!)
    }

    @Test
    fun `an empty list never returns an index`() {
        // 冷路径第一帧（`loading = true` + 空 messages）就是这个形态。
        // 这里若返回 0，列表会滚到"临时行"上，等于把人放到随机位置 —— 历史事故的形态。
        assertNull(fedIndex(emptyList(), "a", 0))
        assertNull(fedIndex(emptyList(), "a", 5))
    }

    @Test
    fun `a key that is no longer in the transcript returns null`() {
        // 消息被删 / 分支切换：调用方要走"精确校正或退回底部"，不能瞎滚。
        assertNull(fedIndex(listOf("a", "b"), "gone", 1))
    }

    @Test
    fun `a missing saved key returns null`() {
        assertNull(fedIndex(listOf("a"), null, 0))
    }

    @Test
    fun `a negative feed prefix is clamped instead of producing a negative index`() {
        // 喂序下标不能为负（LazyColumn 会崩）—— 防御性钳制。
        assertEquals(0, fedIndex(listOf("a"), "a", -3)!!)
    }
}
