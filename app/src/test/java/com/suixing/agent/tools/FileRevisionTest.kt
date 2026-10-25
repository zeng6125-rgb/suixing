package com.suixing.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** issue #44 第八项：文件乐观锁的 revision 语义。 */
class FileRevisionTest {

    @Test
    fun `same content gives the same revision`() {
        assertEquals(revisionOf("hello"), revisionOf("hello"))
    }

    @Test
    fun `changed content gives a different revision`() {
        assertNotEquals(revisionOf("hello"), revisionOf("hello "))
        assertNotEquals(revisionOf("a"), revisionOf("b"))
    }

    @Test
    fun `revision is not based on mtime`() {
        // 同长度不同内容也必须区分：只按长度判等会漏掉"改了一个字"的情况。
        assertNotEquals(revisionOf("abc"), revisionOf("abd"))
    }
}
