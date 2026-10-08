package com.suixing.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net for the uiautomator-dump reader.
 *
 * Both screen routes (accessibility tree and `uiautomator dump`) reduce to the
 * same one-line-per-view text the model reads, so a parsing slip here silently
 * degrades every UI action the agent takes. These cases pin the shape.
 */
class UiDumpTest {

    private fun node(
        cls: String = "android.widget.Button",
        text: String? = null,
        desc: String? = null,
        bounds: String = "[10,20][110,80]",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        id: String? = null,
    ): String = buildString {
        append("<node class=\"").append(cls).append('"')
        if (text != null) append(" text=\"").append(text).append('"')
        if (desc != null) append(" content-desc=\"").append(desc).append('"')
        append(" bounds=\"").append(bounds).append('"')
        append(" clickable=\"").append(clickable).append('"')
        append(" scrollable=\"").append(scrollable).append('"')
        if (id != null) append(" resource-id=\"").append(id).append('"')
        append(" />")
    }

    @Test
    fun parsesInteractiveNodesAndSkipsEmptyOnes() {
        val xml = "<hierarchy>" +
            node(text = "确定", clickable = true) +
            node() + // no text, no desc, no flags: nothing a model can use
            node(desc = "返回", clickable = true, id = "com.x:id/back") +
            "</hierarchy>"
        val nodes = parseUiDump(xml)
        assertEquals(2, nodes.size)
        assertEquals("确定", nodes[0].text)
        assertEquals("Button", nodes[0].cls)
        assertTrue(nodes[0].clickable)
        assertEquals(60, nodes[0].centerX)
        assertEquals(50, nodes[0].centerY)
        assertEquals("返回", nodes[1].desc)
        assertEquals("back", nodes[1].id)
    }

    @Test
    fun skipsZeroAreaNodes() {
        val xml = node(text = "坏节点", clickable = true, bounds = "[50,50][50,50]") +
            node(text = "好节点", clickable = true)
        val nodes = parseUiDump(xml)
        assertEquals(1, nodes.size)
        assertEquals("好节点", nodes[0].text)
    }

    @Test
    fun unescapesXmlEntities() {
        val nodes = parseUiDump(node(text = "a &lt;b&gt; &amp; &quot;c&quot;", clickable = true))
        assertEquals("a <b> & \"c\"", nodes[0].text)
    }

    @Test
    fun editableDetectedFromEditTextClass() {
        val nodes = parseUiDump(
            node(cls = "android.widget.EditText", text = "", bounds = "[0,0][100,50]"),
        )
        assertEquals(1, nodes.size)
        assertTrue(nodes[0].editable)
    }

    @Test
    fun renderEmptySaysSo() {
        assertEquals("(界面上没有可交互元素)", renderUiNodes(emptyList()))
    }

    @Test
    fun renderOneLinePerNodeWithIndexAndFlags() {
        val nodes = parseUiDump(
            node(text = "确定", clickable = true) +
                node(cls = "android.widget.ScrollView", desc = "列表", scrollable = true),
        )
        val rendered = renderUiNodes(nodes)
        val lines = rendered.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[0] Button text=\"确定\""))
        assertTrue(lines[0].contains("center=(60,50)"))
        assertTrue(lines[0].contains("bounds=(10,20,110,80)"))
        assertTrue(lines[0].endsWith("clickable"))
        assertTrue(lines[1].startsWith("[1] ScrollView desc=\"列表\""))
        assertTrue(lines[1].contains("scrollable"))
    }

    @Test
    fun renderOmitsDescWhenItDuplicatesText() {
        val rendered = renderUiNodes(parseUiDump(node(text = "同一个", desc = "同一个", clickable = true)))
        assertFalse(rendered.contains("desc="))
    }

    @Test
    fun renderTruncationMarker() {
        val rendered = renderUiNodes(parseUiDump(node(text = "x", clickable = true)), truncated = true)
        assertTrue(rendered.contains("已截断"))
    }

    @Test
    fun focusedPackageFromDumpsysLines() {
        val dump = """
            WINDOW MANAGER WINDOWS (dumpsys window windows)
              mCurrentFocus=Window{2f1a3b u0 com.bilibili.app/com.bilibili.ui.MainActivity}
              mFocusedApp=AppWindowToken{abc token=Token{def ActivityRecord{ghi com.example.other/.X}}}
        """.trimIndent()
        assertEquals("com.bilibili.app", focusedPackage(dump))
    }

    @Test
    fun focusedPackageNullWhenNoFocusLine() {
        assertNull(focusedPackage("WINDOW MANAGER WINDOWS (dumpsys window windows)\n  nothing here"))
    }

    @Test
    fun matchesByTextOrDescExactAndPartial() {
        val node = parseUiDump(node(text = "发送弹幕", desc = "send", clickable = true)).single()
        assertTrue(node.matches("发送", exact = false))
        assertTrue(node.matches("sen", exact = false))
        assertFalse(node.matches("发送", exact = true))
        assertTrue(node.matches("发送弹幕", exact = true))
        assertTrue(node.matches("send", exact = true))
    }
}
