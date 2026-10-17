package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画像变更协议：模型只提交变更，旧条目由代码保管。
 * 核心保证是「增补不需要模型抄全」，以及「对不上的操作整批拒绝、一个字都不写」。
 */
class ProfileOpsTest {

    private val old = """
        ### 沟通风格与语气偏好
        - 喜欢先看结论
        - 说话简短
        ### 兴趣爱好
        - 玩机械键盘
    """.trimIndent()

    @Test
    fun `an added bullet lands in its section and keeps every old one`() {
        val ops = parseProfileOps("+ [兴趣爱好] 喜欢骑行")
        val result = applyProfileOps(old, ops, 8000)

        val text = (result as ProfileApply.Ok).text
        assertTrue(text.contains("喜欢先看结论"))
        assertTrue(text.contains("说话简短"))
        assertTrue(text.contains("玩机械键盘"))
        assertTrue(text.contains("喜欢骑行"))
        assertTrue("新增应在兴趣爱好小节里：$text", text.indexOf("喜欢骑行") > text.indexOf("兴趣爱好"))
    }

    @Test
    fun `a remove that matches nothing rejects the whole batch`() {
        val ops = parseProfileOps("- 不存在的一条")
        val result = applyProfileOps(old, ops, 8000)

        assertTrue("应拒绝：$result", result is ProfileApply.Rejected)
        assertTrue((result as ProfileApply.Rejected).reason.contains("不存在的一条"))
    }

    @Test
    fun `a replace only touches the matching bullet`() {
        val ops = parseProfileOps("~ 说话简短 => 说话非常简短")
        val text = (applyProfileOps(old, ops, 8000) as ProfileApply.Ok).text

        assertTrue(text.contains("说话非常简短"))
        assertTrue(!text.contains("- 说话简短\n"))
        assertTrue("其它条目不能被碰：$text", text.contains("喜欢先看结论"))
    }

    @Test
    fun `explanatory lines around the ops are ignored`() {
        val ops = parseProfileOps(
            """
            好的，我加了这些：
            + [性格特点] 偏理性
            以上。
            """.trimIndent(),
        )

        assertEquals(1, ops.size)
        assertTrue(ops.first() is ProfileOp.Add)
    }

    @Test
    fun `an empty reply means nothing to change`() {
        assertTrue(parseProfileOps("").isEmpty())
        assertTrue(parseProfileOps("没有新信息").isEmpty())
    }

    @Test
    fun `going past the length limit rejects instead of truncating`() {
        val ops = parseProfileOps("+ " + "很长的条目".repeat(200))
        assertTrue(applyProfileOps(old, ops, 500) is ProfileApply.Rejected)
    }
}
