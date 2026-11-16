package com.termux.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixing.agent.agent.NativeWireText
import com.suixing.agent.agent.renderForCompaction
import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.NativeChatLog
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.ui.chat.NativeTranscript
import com.suixing.agent.ui.chat.groupRowsJvm
import com.suixing.agent.ui.chat.markdown.LiveBlocks
import com.suixing.agent.ui.chat.markdown.MdBlock
import com.suixing.agent.ui.chat.markdown.MessageSegment
import com.suixing.agent.ui.chat.markdown.NativeMarkdown
import com.suixing.agent.ui.chat.markdown.parseMarkdown
import com.suixing.agent.ui.chat.markdown.scanInlineMarkdown
import com.suixing.agent.ui.chat.markdown.splitImageSegments
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMigrationDeviceTest {
    @Test fun streamingBlocksMatchTheExistingParser() {
        assertTrue(NativeCore.available)
        val sources = listOf(
            "第一段🙂\n第二行\n\n下一段 **粗体**",
            "# 标题\n\n- 第一项\n  - 子项\n- 第二项\n\n---\n结尾",
            "```rust\nfn main() {\n    println!(\"hello\");\n}\n```\n\n结束",
            "| 名称 | 值 |\n| --- | --- |\n| 第一行 | 1 |\n| 第二行 | 2 |\n\n普通文字",
            "> 引用\n> 第二行\n\n1. 项目一\n2. 项目二",
            "段落\r\n下一行\r\n\r\n结束",
        )
        for ((id, source) in sources.withIndex()) {
            val parser = LiveBlocks()
            var version = 0
            for (end in (7 until source.length step 7).toList() + source.length) {
                val prefix = source.substring(0, end)
                assertEquals(prefix, parseMarkdown(prefix), parser.blocksFor(prefix, version++, id + 1))
            }
        }
        assertNotNull(NativeMarkdown.tail("# Native\n\nbody", 0))
    }

    @Test fun completedTableRowsKeepTheirIdentity() {
        val parser = LiveBlocks()
        val first = "| a | b |\n| --- | --- |\n| one | two |"
        val before = parser.blocksFor(first, 0, 1).single() as MdBlock.Table
        val after = parser.blocksFor(first + "\n| three | four |", 1, 1).single() as MdBlock.Table
        assertSame(before.rows[0], after.rows[0])
        assertEquals(parseMarkdown(first + "\n| three | four |"), parser.blocksFor(first + "\n| three | four |", 1, 1))
    }

    @Test fun inlineAndImageParsingUseNativeResults() {
        assertTrue(NativeCore.available)
        val text = "**粗体** 与 *斜体*，`code` [链接](https://example.com)"
        assertNotNull(NativeMarkdown.parseInline(text))
        val rendered = scanInlineMarkdown(text)
        assertEquals("粗体 与 斜体，\u2009code\u2009 链接", rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
        val source = "前🙂 ![图](https://example.com/p.png) 后"
        assertEquals(listOf(MessageSegment.Text("前🙂 "), MessageSegment.Image("图", "https://example.com/p.png"), MessageSegment.Text(" 后")), splitImageSegments(source))
        for (literal in listOf("`![x](https://example.com/p.png)`", "```\n![x](https://example.com/p.png)\n```", "![x](relative.png)")) {
            assertEquals(listOf(MessageSegment.Text(literal)), splitImageSegments(literal, streaming = true))
        }
    }

    @Test fun transcriptPlansMatchBranchAndToolGrouping() {
        val messages = listOf(
            StoredMessage("u1", Roles.USER, "问题一", timestamp = 1),
            StoredMessage("a1", Roles.ASSISTANT, "开始", reasoning = "思考", runId = "r1", timestamp = 2),
            StoredMessage("t1", Roles.TOOL, toolName = "read_file", toolArgs = "{}", toolOutput = "结果", runId = "r1", timestamp = 3),
            StoredMessage("s1", Roles.USER, "插话", steer = true, timestamp = 4),
            StoredMessage("a2", Roles.ASSISTANT, "完成", runId = "r1", timestamp = 5),
            StoredMessage("e1", Roles.SYSTEM, "错误回执", runId = "r1", timestamp = 6),
            StoredMessage("u2", Roles.USER, "问题二", timestamp = 7),
            StoredMessage("a3", Roles.ASSISTANT, "旧回答", runId = "r2", timestamp = 8),
            StoredMessage("a4", Roles.ASSISTANT, "新回答", runId = "r3", timestamp = 9),
        )
        for (pick in listOf(emptyMap(), mapOf("u2" to "r2"), mapOf("u2" to "r3"))) {
            assertEquals(groupRowsJvm(messages, pick, false), NativeTranscript.rows(messages, pick, false, null))
        }
        assertEquals(groupRowsJvm(messages, mapOf("u2" to "r4"), true, "r4"), NativeTranscript.rows(messages, mapOf("u2" to "r4"), true, "r4"))
        assertEquals(2, NativeTranscript.page(messages, 1)?.turns)
        assertEquals(6, NativeTranscript.page(messages, 1)?.from)
        val profile = List(600) { index ->
            val role = when (index % 6) {
                0, 4 -> Roles.USER
                2 -> Roles.TOOL
                3 -> Roles.SYSTEM
                else -> Roles.ASSISTANT
            }
            StoredMessage(
                id = "00000000-0000-0000-0000-%012d".format(index),
                role = role, text = "示例正文", steer = index % 6 == 4,
                runId = "10000000-0000-0000-0000-%012d".format(index / 6),
                toolName = if (role == Roles.TOOL) "read_file" else null,
                toolOutput = if (role == Roles.TOOL) "结果" else null,
                timestamp = index.toLong(),
            )
        }
        for (size in listOf(24, 600)) {
            val input = profile.take(size)
            fun measure(name: String, build: () -> List<com.suixing.agent.ui.chat.ChatRow>) {
                val allocated = java.lang.Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.bytes-allocated"))
                val started = android.os.Debug.threadCpuTimeNanos()
                var rows = 0
                repeat(10) { rows += build().size }
                val cpu = (android.os.Debug.threadCpuTimeNanos() - started) / 10
                val bytes = (java.lang.Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")) - allocated) / 10
                android.util.Log.i("NativeMigrationPerf", "size=$size mode=$name cpu_ns=$cpu java_bytes=$bytes rows=${rows / 10}")
            }
            repeat(3) {
                groupRowsJvm(input, emptyMap(), false)
                NativeTranscript.rows(input, emptyMap(), false, null)
            }
            repeat(3) {
                measure("jvm") { groupRowsJvm(input, emptyMap(), false) }
                measure("native") { requireNotNull(NativeTranscript.rows(input, emptyMap(), false, null)) }
            }
        }
    }

    @Test fun compactionTextRetainsTheExistingFormat() {
        val messages = listOf(
            StoredMessage("u", Roles.USER, "问题", imageBase64 = "image"),
            StoredMessage("a", Roles.ASSISTANT, "调用", toolCalls = listOf(StoredToolCall("call", "read_file", "{\"path\":\"x\"}"))),
            StoredMessage("t", Roles.TOOL, toolOutput = "🙂".repeat(1100)),
        )
        val expected = "system: [上下文摘要] 已有摘要\n" + messages.joinToString("\n", transform = ::renderForCompaction)
        assertEquals(expected, NativeWireText.compaction(messages, "已有摘要"))
    }

    @Test fun jsonlReadsUseThePinnedCommitAndOriginalProbeLimit() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val messages = List(40) { i -> StoredMessage("m$i", if (i % 3 == 0) Roles.USER else Roles.ASSISTANT, if (i == 0) "a".repeat(5 * 1024 * 1024) else "内容🙂 $i", timestamp = i.toLong()) }
        val encoded = messages.joinToString("\n", postfix = "\n") { json.encodeToString(StoredMessage.serializer(), it) }.toByteArray()
        val file = File.createTempFile("native-log", ".jsonl", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(encoded + "uncommitted".toByteArray())
            RandomAccessFile(file, "r").use { input ->
                val entries = NativeChatLog.read(input, file.name, 0, encoded.size.toLong(), json)
                assertNotNull(entries); assertEquals(messages, entries!!.map { it.message })
                assertEquals(encoded.size.toLong(), NativeChatLog.nextLine(input, 1, encoded.size.toLong(), 65536))
                val at = NativeChatLog.locate(input, 0, encoded.size.toLong(), "{\"id\":\"m17\"".toByteArray())
                assertNotNull(at); assertTrue(at!! > 70_000)
                assertEquals(36, NativeChatLog.cut(entries, 2, false))
            }
        } finally { file.delete() }
    }

    @Test fun nativeTerminalRunsOnAndroidAndReleasesItsBuffers() {
        val replies = ByteArrayOutputStream()
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) { replies.write(data, offset, count) }
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) { fail("clipboard mutation") }
            override fun onPasteTextFromClipboard() { fail("clipboard read") }
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        TerminalEmulator(output, 80, 24, 1, 1, 2000, null).use { emulator ->
            assertNotNull(emulator.nativeMemory())
            val bytes = "shell 中文🙂\u001b[?1049hother\u001b[?1049l".toByteArray()
            for (byte in bytes) emulator.append(byteArrayOf(byte), 1)
            assertEquals("shell 中文🙂", emulator.screen.transcriptText)
            emulator.resize(40, 30, 1, 1)
            assertEquals("shell 中文🙂", emulator.screen.transcriptText)
            val colors = emulator.mColors.mCurrentColors
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xff123456.toInt()
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff234567.toInt()
            colors[TextStyle.COLOR_INDEX_CURSOR] = 0xff345678.toInt()
            val query = "\u001b]10;?\u0007\u001b]11;?\u0007\u001b]12;?\u0007".toByteArray()
            emulator.append(query, query.size)
            assertEquals("\u001b]10;rgb:1212/3434/5656\u0007\u001b]11;rgb:2323/4545/6767\u0007\u001b]12;rgb:3434/5656/7878\u0007", replies.toString(Charsets.UTF_8.name()))
            emulator.clearScreenAndHistory()
            assertEquals("", emulator.screen.transcriptText)
            emulator.close()
            assertArrayEquals(longArrayOf(0, 0, 0), emulator.nativeMemory())
        }
    }
}
