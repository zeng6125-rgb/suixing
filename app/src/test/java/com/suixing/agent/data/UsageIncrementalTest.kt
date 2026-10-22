package com.suixing.agent.data

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
import java.time.Instant
import java.time.ZoneId

/**
 * 增量用量扫描（#30）：原来打开「用量」页会把**每份记录整个解析一遍**（10 个会话 54 MB），
 * 只为了看水位之后的那几条。现在只读水位之后的字节。
 *
 * 这份测试盯两件会**悄悄算错**的事：
 *  1. 增量扫描的 totals 必须与整份扫描**完全一致**（不多不少）；
 *  2. 字节水位必须落在**最后一条已计价消息**之后 —— 未计价的那段要留到下次再看
 *     （否则它的 token 永远不会被计入）。
 */
class UsageIncrementalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utc: ZoneId = ZoneId.of("UTC")
    private val day1 = Instant.parse("2026-03-01T10:00:00Z").toEpochMilli()

    /**
     * 只有 prompt 一个桶（completion 保持 0）—— 两条桶都填的话每个数字都会翻倍，
     * 期望值就得算两遍，容易看错。
     */
    private fun msg(id: String, prompt: Int = 0, priced: Boolean = true) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        promptTokens = prompt,
        completionTokens = 0,
        model = "m",
        timestamp = day1,
    )

    private fun slice(id: String, messages: List<StoredMessage>, base: Int = 0) = UsageSlice(
        id = id,
        messages = messages,
        baseIndex = base,
        endOffsets = null,
        gen = -1,
    )

    @Test
    fun `a slice with a base index keeps the watermark absolute`() {
        // 会话有 2 条已计入，现在只给第 2 条之后的那一段。
        val previous = accumulateSlices(
            UsageSnapshot(),
            listOf(slice("c1", listOf(msg("m0", 5), msg("m1", 7)))),
            utc,
        )
        val after = accumulateSlices(
            previous,
            listOf(slice("c1", listOf(msg("m2", 11)), base = 2)),
            utc,
        )
        val usage = after.chats.getValue("c1")
        assertEquals("水位是绝对下标", 3, usage.scanned)
        assertEquals("三段 token 都要算进去", 5 + 7 + 11, usage.days.getValue("2026-03-01").total)
    }

    @Test
    fun `the byte watermark stops after the last priced message`() {
        // 最后一条没计价（token 全 0 ⇒ usageKnown 为假）⇒ 它的字节水位不能推进，
        // 否则它永远不会被计入。
        val endOffsets = listOf(100L, 200L, 300L)
        val slice = UsageSlice(
            id = "c1",
            messages = listOf(msg("m0", 5), msg("m1", 7), msg("m2", 0, priced = false)),
            baseIndex = 0,
            endOffsets = endOffsets,
            gen = 3,
        )
        val after = accumulateSlices(UsageSnapshot(), listOf(slice), utc)
        val usage = after.chats.getValue("c1")
        assertEquals("两条已计价的消息都算进去", 2, usage.scanned)
        assertEquals("水位停在最后一条已计价消息之后", 200L, usage.scannedBytes)
        assertEquals("代数要记下来", 3, usage.scannedGen)
        assertEquals("只有已计价的计入总额", 5 + 7, usage.days.getValue("2026-03-01").total)
    }

    @Test
    fun `an incremental scan totals the same as a full scan`() {
        val all = listOf(msg("m0", 3), msg("m1", 4), msg("m2", 5), msg("m3", 6))
        val full = accumulateSlices(UsageSnapshot(), listOf(slice("c1", all)), utc)

        // 先扫前两条，再扫后两条（base=2），结果必须一样。
        val first = accumulateSlices(UsageSnapshot(), listOf(slice("c1", all.take(2))), utc)
        val second = accumulateSlices(first, listOf(slice("c1", all.drop(2), base = 2)), utc)

        assertEquals(
            "增量与整份必须完全一致",
            full.chats.getValue("c1").days.getValue("2026-03-01").total,
            second.chats.getValue("c1").days.getValue("2026-03-01").total,
        )
        assertEquals(full.chats.getValue("c1").scanned, second.chats.getValue("c1").scanned)
    }

    // ─── 端到端：真的落盘、真的走增量路径 ─────────────────────────────────────

    private fun contextFor(dir: File): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns dir
        every { ctx.getExternalFilesDir(null) } returns dir
        return ctx
    }

    @Test
    fun `the second scan only reads what came after the watermark`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = File(dir, "chats").apply { mkdirs() }
        val store = ChatStore(chats)

        // 第一次落盘：两条消息。
        val first = listOf(msg("m0", 5), msg("m1", 7))
        val convo = Conversation(id = "c1", title = "t", messages = first)
        val meta0 = store.newMeta(convo)
        store.save(meta0, first)

        val usage = UsageStore(contextFor(dir))
        val afterFirst = usage.refresh()
        assertEquals("第一次扫要把两条都算进去", 12, afterFirst.totals.total)
        val bytesAfterFirst = afterFirst.chats.getValue("c1").scannedBytes
        assertTrue("第一次扫完要留下字节水位", bytesAfterFirst > 0)

        // 再追加两条（追加写，代数不变）。
        val second = first + listOf(msg("m2", 11), msg("m3", 13))
        store.save(store.readMeta("c1")!!, second)

        val afterSecond = usage.refresh()
        assertEquals("第二次扫要加上新来的两条", 12 + 11 + 13, afterSecond.totals.total)
        val usage2 = afterSecond.chats.getValue("c1")
        assertEquals("水位是绝对下标", 4, usage2.scanned)
        assertTrue("字节水位要往前走", usage2.scannedBytes > bytesAfterFirst)
        assertEquals("代数没变（只是追加）", afterFirst.chats.getValue("c1").scannedGen, usage2.scannedGen)
    }

    @Test
    fun `a rewritten log is read from the start again`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = File(dir, "chats").apply { mkdirs() }
        val store = ChatStore(chats)

        val first = listOf(msg("m0", 5), msg("m1", 7))
        store.save(store.newMeta(Conversation(id = "c1", title = "t", messages = first)), first)

        val usage = UsageStore(contextFor(dir))
        val afterFirst = usage.refresh()
        val genBefore = afterFirst.chats.getValue("c1").scannedGen
        val bytesBefore = afterFirst.chats.getValue("c1").scannedBytes

        // **改写**（编辑重答）：代数会变 ⇒ 旧的字节偏移指向别的文件 ⇒ 必须从头读。
        val rewritten = listOf(msg("m0", 5), msg("m2", 13))
        val meta = store.readMeta("c1")!!
        store.rewrite(meta, rewritten)
        val genAfter = store.readMeta("c1")!!.gen
        assertTrue("改写必须换代数", genAfter != genBefore)

        val afterSecond = usage.refresh()
        val usage2 = afterSecond.chats.getValue("c1")
        assertEquals("代数要更新", genAfter, usage2.scannedGen)
        assertEquals("旧世代的字节水位必须丢掉", 0L, usage2.scannedBytes)
        assertTrue("丢掉水位是为了下次从头读", bytesBefore > 0L)
        // 总额不能翻倍。改写后 count 水位**不回退**（既有语义：水位只前进），
        // 所以这份会话的合计保持改写前那个 —— 但绝不能是两份。
        assertEquals(
            "总额不能因为读了两次而翻倍",
            12,
            usage2.days.getValue("2026-03-01").total,
        )
    }
}
