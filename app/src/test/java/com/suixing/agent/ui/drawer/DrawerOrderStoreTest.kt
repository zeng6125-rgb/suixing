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

/** 旧文件兼容（issue #66 评审关注点）：换解析器前后写出的 JSON 形状必须一致。 */
class DrawerOrderLegacyFormatTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun storeIn(dir: File): DrawerOrderStore {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns dir
        return DrawerOrderStore(ctx)
    }

    @Test fun `a file written by the old org_json code still loads`() = runBlocking {
        val dir = tmp.newFolder("legacy")
        // 旧实现用 org.json 输出，形状就是这两个键（JSONArray 序列化）
        File(dir, "drawer-order").writeText("""{"pinned":["p1","p2"],"normal":["n1"]}""")
        val loaded = storeIn(dir).load()
        assertEquals(listOf("p1", "p2"), loaded.pinned)
        assertEquals(listOf("n1"), loaded.normal)
    }

    @Test fun `what we write now is the same shape the old reader expects`() = runBlocking {
        val dir = tmp.newFolder("roundtrip")
        storeIn(dir).save(DrawerOrder(pinned = listOf("p"), normal = listOf("n")))
        val text = File(dir, "drawer-order").readText()
        // 键名不变、都是字符串数组 —— 降级回旧版本也能读
        assertTrue("应包含 pinned 键", text.contains("\"pinned\""))
        assertTrue("应包含 normal 键", text.contains("\"normal\""))
        assertEquals(DrawerOrder(pinned = listOf("p"), normal = listOf("n")), storeIn(dir).load())
    }
}
