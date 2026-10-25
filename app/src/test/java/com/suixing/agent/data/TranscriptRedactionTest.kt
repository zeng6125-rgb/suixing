package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #44 第二项：敏感工具数据不进持久 transcript。
 *
 * 守两条：
 * 1. **该屏蔽的屏蔽**：剪贴板读取结果、用户手打的文本参数，落盘时一律变成占位文案。
 * 2. **不该碰的不碰**：普通工具的读写原样保留 —— 一刀切会把会话记录掏空。
 */
class TranscriptRedactionTest {

    @Test
    fun `剪贴板读取的结果被替换`() {
        val output = TranscriptRedaction.outputForTranscript("clipboard_read", "p@ssw0rd-123456")
        assertEquals(TranscriptRedaction.PLACEHOLDER, output)
    }

    @Test
    fun `用户手打的文本参数被替换`() {
        val args = """{"text":"我的密码是 hunter2"}"""
        val redacted = TranscriptRedaction.argumentsForTranscript("ui_type", args)
        assertFalse("不能把原文写进 transcript", redacted.contains("hunter2"))
        assertTrue("要留下占位说明", redacted.contains(TranscriptRedaction.PLACEHOLDER))
        assertEquals(TranscriptRedaction.PLACEHOLDER, TranscriptRedaction.outputForTranscript("clipboard_read", "x"))
    }

    @Test
    fun `普通工具原样保留`() {
        val args = """{"path":"/sdcard/a.txt"}"""
        assertEquals(args, TranscriptRedaction.argumentsForTranscript("read_file", args))
        assertEquals("文件内容", TranscriptRedaction.outputForTranscript("read_file", "文件内容"))
        // run_shell 刻意不屏蔽：它能读到的东西无限广，一刀切会把会话记录掏空。
        assertEquals("ls 输出", TranscriptRedaction.outputForTranscript("run_shell", "ls 输出"))
        assertFalse(TranscriptRedaction.isRedacted("run_shell"))
    }

    @Test
    fun `判定覆盖三个参数类工具与一个结果类工具`() {
        listOf("ui_type", "ui_type_into", "clipboard_write", "clipboard_read").forEach {
            assertTrue("$it 应被屏蔽", TranscriptRedaction.isRedacted(it))
        }
        listOf("ui_tap", "ui_swipe", "read_file", "write_file", "web_search", "device_info").forEach {
            assertFalse("$it 不该被屏蔽", TranscriptRedaction.isRedacted(it))
        }
    }
}
