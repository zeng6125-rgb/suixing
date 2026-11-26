package com.suixing.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeUiDumpTest {
    private fun writeUtf16String(out: ByteBuffer, value: String) {
        out.putInt(value.length)
        for (ch in value) out.putChar(ch)
    }

    @Test
    fun `decodeNodes decodes packed utf16 fields and flags accurately`() {
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x31555853)
        buf.putInt(1)
        buf.putInt(-10)
        buf.putInt(20)
        buf.putInt(110)
        buf.putInt(80)
        buf.putInt(0b101) // clickable + editable
        writeUtf16String(buf, "EditText")
        writeUtf16String(buf, "确定😀")
        writeUtf16String(buf, "desc")
        writeUtf16String(buf, "input_box")
        val bytes = ByteArray(buf.position()).also {
            buf.flip()
            buf.get(it)
        }
        val nodes = NativeUiDump.decodeNodes(bytes)
        assertEquals(1, nodes.size)
        val node = nodes[0]
        assertEquals("EditText", node.cls)
        assertEquals("确定😀", node.text)
        assertEquals("desc", node.desc)
        assertEquals("input_box", node.id)
        assertEquals(-10, node.left)
        assertEquals(20, node.top)
        assertEquals(110, node.right)
        assertEquals(80, node.bottom)
        assertTrue(node.clickable)
        assertFalse(node.scrollable)
        assertTrue(node.editable)
    }

    @Test
    fun `decodeFocus distinguishes empty outcome from package hit`() {
        val emptyBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x31555853).putInt(0).array()
        assertEquals(NativeUiDump.FocusOutcome.Empty, NativeUiDump.decodeFocus(emptyBuf))

        val pkg = "com.example.app"
        val foundBuf = ByteBuffer.allocate(12 + pkg.length * 2).order(ByteOrder.LITTLE_ENDIAN)
        foundBuf.putInt(0x31555853).putInt(1)
        writeUtf16String(foundBuf, pkg)
        assertEquals(
            NativeUiDump.FocusOutcome.Found(pkg),
            NativeUiDump.decodeFocus(foundBuf.array()),
        )
    }

    @Test
    fun `decodeNodes rejects corrupt buffers`() {
        assertThrows(IOException::class.java) {
            NativeUiDump.decodeNodes(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun `parseUiDump handles word boundaries, negative bounds, overflows, and entity order`() {
        val xml = "<hierarchy>" +
            "<nodex text=\"skip\" clickable=\"true\" bounds=\"[0,0][10,10]\" />" +
            "<node mytext=\"ignored\" foo-text=\"&amp;lt;ok&gt;\" class=\"android.widget.EditText\" bounds=\"[-5,10][95,50]\" resource-id=\"com.a:id/edit\" />" +
            "<node text=\"bad\" bounds=\"[999999999999,0][10,10]\" bounds=\"[0,0][10,10]\" clickable=\"true\" />" +
            "</hierarchy>"
        val nodes = parseUiDump(xml)
        assertEquals(1, nodes.size)
        assertEquals("&lt;ok>", nodes[0].text)
        assertTrue(nodes[0].editable)
        assertEquals(-5, nodes[0].left)
        assertEquals("edit", nodes[0].id)
        val rendered = renderUiNodes(nodes)
        assertTrue(rendered.contains("editable"))
    }
}
