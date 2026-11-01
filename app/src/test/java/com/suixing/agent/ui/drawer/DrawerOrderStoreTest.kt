package com.suixing.agent.ui.drawer

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** issue #66 P0-1/P0-2：并发保存只留最新顺序，主文件坏了还能从 `.tmp` 恢复。 */
class DrawerOrderStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun storeIn(dir: File): DrawerOrderStore {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns dir
        return DrawerOrderStore(ctx)
    }

    @Test fun `concurrent saves keep the newest order`() = runBlocking {
        val dir = tmp.newFolder("files")
        val store = storeIn(dir)
        val a = DrawerOrder(normal = listOf("a"))
        val b = DrawerOrder(normal = listOf("b", "c"))
        // 两次提交：不管谁先完成，磁盘上必须是最后提交的那份
        store.save(a)
        store.save(b)
        assertEquals(b, storeIn(dir).load())
    }

    @Test fun `a leftover temp file is used when the main file is missing`() = runBlocking {
        val dir = tmp.newFolder("files2")
        File(dir, "drawer-order.tmp").writeText("""{"pinned":["p"],"normal":["n1","n2"]}""")
        val loaded = storeIn(dir).load()
        assertEquals(listOf("p"), loaded.pinned)
        assertEquals(listOf("n1", "n2"), loaded.normal)
    }

    @Test fun `a corrupt main file falls back to an empty order`() = runBlocking {
        val dir = tmp.newFolder("files3")
        File(dir, "drawer-order").writeText("{not json")
        val loaded = storeIn(dir).load()
        assertTrue(loaded.pinned.isEmpty() && loaded.normal.isEmpty())
    }

    @Test fun `saving leaves no temp file behind`() = runBlocking {
        val dir = tmp.newFolder("files4")
        storeIn(dir).save(DrawerOrder(normal = listOf("x")))
        assertTrue("成功写入后不该留下 .tmp", !File(dir, "drawer-order.tmp").exists())
    }
}
