package com.suixing.agent.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切会话入场的手感，锁成数字。
 *
 * 这段动效用户看得见却很难描述，所以把「柔和」拆成可测的性质：
 * **不比从前突兀、落位不撞停、透明度先于位移收尾、半程就该看清**。
 *
 * 曲线只有一条（[ConversationEntrance.EASING]），透明度与位移都由**已缓动的进度**派生，
 * 所以下面所有断言都把 `EASING.transform(t)` 当作「时间 t 时的进度」。
 *
 * 「旧曲线」在这里被当作**基线**写进测试：改动手感时，新曲线必须在每个时刻都不比旧曲线
 * 更早显现 —— 这比「我觉得更柔和」可核对得多。
 */
class ConversationEntranceTest {

    /** 0.8.554 之前的实现：线性时钟 + 一条 bezier（透明度在进度 1/3 处走完）。 */
    private val oldFade = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    private fun oldAlpha(t: Float): Float = oldFade.transform((t * 3f).coerceIn(0f, 1f))

    /** 时间 t（0..1）对应的已缓动进度。 */
    private fun at(t: Float): Float = ConversationEntrance.EASING.transform(t.coerceIn(0f, 1f))

    @Test
    fun `透明度从 0 到 1 且单调不减`() {
        assertEquals(0f, ConversationEntrance.alpha(at(0f)), 1e-4f)
        assertEquals(1f, ConversationEntrance.alpha(at(1f)), 1e-4f)
        var previous = -1f
        for (i in 0..100) {
            val value = ConversationEntrance.alpha(at(i / 100f))
            assertTrue("透明度在 ${i}% 处回退了", value >= previous)
            previous = value
        }
    }

    @Test
    fun `剩余位移从 1 到 0 且单调不增`() {
        assertEquals(1f, ConversationEntrance.remainingTravel(at(0f)), 1e-4f)
        assertEquals(0f, ConversationEntrance.remainingTravel(at(1f)), 1e-4f)
        var previous = 2f
        for (i in 0..100) {
            val value = ConversationEntrance.remainingTravel(at(i / 100f))
            assertTrue("剩余位移在 ${i}% 处变多了", value <= previous)
            previous = value
        }
    }

    @Test
    fun `任何时刻都不比旧曲线更早显现`() {
        // 这是「更柔和」最硬的判据：新曲线在每个采样点的显现量都 ≤ 旧曲线。
        // （1.5 这个系数会在这里反超 0.0008 —— 所以它被否掉了。）
        for (i in 0..100) {
            val t = i / 100f
            val now = ConversationEntrance.alpha(at(t))
            val before = oldAlpha(t)
            assertTrue(
                "t=${"%.2f".format(t)} 处比旧曲线更早显现：$now > $before",
                now <= before + 1e-4f,
            )
        }
    }

    @Test
    fun `显现收尾不早于全程的四成`() {
        // 旧实现是 1/3（100ms / 300ms）—— 那一下「内容凭空到全不透明」是发硬的来源。
        var finishedAt = 1f
        for (i in 0..100) {
            if (ConversationEntrance.alpha(at(i / 100f)) >= 1f) {
                finishedAt = i / 100f
                break
            }
        }
        assertTrue("显现在 $finishedAt 就收尾了，太早", finishedAt >= 0.4f)
    }

    @Test
    fun `落位是稳的：末段位移几乎不再变化`() {
        // 主曲线末端导数为 0 ⇒ 内容是「落位」，不是「撞到位再停」。
        val tail = ConversationEntrance.remainingTravel(at(0.99f))
        assertTrue("末段仍在明显移动：还剩 $tail", tail < 0.01f)
    }

    @Test
    fun `透明度先于位移收尾：存在「已看清但仍在沉降」的窗口`() {
        // 旧实现最刺眼的地方：内容 100ms 就全不透明，却还要往上飘 200ms。
        var found = false
        for (i in 0..100) {
            val p = at(i / 100f)
            if (ConversationEntrance.alpha(p) >= 1f && ConversationEntrance.remainingTravel(p) > 0.05f) {
                found = true
                break
            }
        }
        assertTrue("透明度应先于位移收尾，留出一段沉降余量", found)
    }

    @Test
    fun `内容在半程就该看清，不能迟钝`() {
        // 「柔和」不等于「慢」：一半时间过去必须基本看清，否则就是反应迟钝。
        val visible = ConversationEntrance.alpha(at(0.5f))
        assertTrue("半程时只显现了 $visible，太迟钝", visible > 0.9f)
    }

    @Test
    fun `位移幅度为正且不超过屏幕尺度`() {
        assertTrue(ConversationEntrance.TRAVEL_DP > 0f)
        assertTrue("位移 ${ConversationEntrance.TRAVEL_DP}dp 太大了", ConversationEntrance.TRAVEL_DP <= 24f)
    }
}
