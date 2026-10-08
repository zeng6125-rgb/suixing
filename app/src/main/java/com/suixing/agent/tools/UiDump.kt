package com.suixing.agent.tools

/**
 * Reading the view tree through `uiautomator dump`.
 *
 * The dump is a 100 KB+ XML file where most nodes carry nothing a model can use,
 * so it gets reduced to the same one-line-per-view shape the accessibility service
 * produces — the model should not be able to tell which route answered it.
 *
 * Every regex here is built once at file scope; a per-node or per-line `Regex(...)`
 * is what blew up the markdown renderer before.
 */
internal data class UiNode(
    val cls: String,
    val text: String,
    val desc: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val id: String,
) {
    val centerX: Int get() = (left + right) / 2

    val centerY: Int get() = (top + bottom) / 2

    fun matches(query: String, exact: Boolean): Boolean = if (exact) {
        text == query || desc == query
    } else {
        text.contains(query) || desc.contains(query)
    }
}

internal fun parseUiDump(xml: String): List<UiNode> {
    val nodes = ArrayList<UiNode>(64)
    for (match in NODE_TAG.findAll(xml)) {
        val tag = match.value
        val bounds = ATTR_BOUNDS.find(tag)?.groupValues ?: continue
        val left = bounds[1].toIntOrNull() ?: continue
        val top = bounds[2].toIntOrNull() ?: continue
        val right = bounds[3].toIntOrNull() ?: continue
        val bottom = bounds[4].toIntOrNull() ?: continue
        if (right <= left || bottom <= top) continue

        val text = unescape(ATTR_TEXT.find(tag)?.groupValues?.get(1).orEmpty()).trim()
        val desc = unescape(ATTR_DESC.find(tag)?.groupValues?.get(1).orEmpty()).trim()
        val clickable = ATTR_CLICKABLE.containsMatchIn(tag)
        val scrollable = ATTR_SCROLLABLE.containsMatchIn(tag)
        val editable = ATTR_EDITABLE.containsMatchIn(tag)
        if (text.isEmpty() && desc.isEmpty() && !clickable && !scrollable && !editable) continue

        nodes.add(
            UiNode(
                cls = ATTR_CLASS.find(tag)?.groupValues?.get(1).orEmpty().substringAfterLast('.'),
                text = text,
                desc = desc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                clickable = clickable,
                scrollable = scrollable,
                editable = editable,
                id = ATTR_ID.find(tag)?.groupValues?.get(1).orEmpty().substringAfterLast('/'),
            ),
        )
    }
    return nodes
}

internal fun renderUiNodes(nodes: List<UiNode>, truncated: Boolean = false): String {
    if (nodes.isEmpty()) return "(界面上没有可交互元素)"
    val builder = StringBuilder()
    nodes.forEachIndexed { index, node ->
        builder.append('[').append(index).append("] ").append(node.cls.ifEmpty { "View" })
        if (node.text.isNotEmpty()) builder.append(" text=\"").append(node.text.take(120)).append('"')
        if (node.desc.isNotEmpty() && node.desc != node.text) {
            builder.append(" desc=\"").append(node.desc.take(80)).append('"')
        }
        builder.append(" center=(").append(node.centerX).append(',').append(node.centerY).append(')')
        builder.append(" bounds=(").append(node.left).append(',').append(node.top)
            .append(',').append(node.right).append(',').append(node.bottom).append(')')
        val flags = ArrayList<String>(3)
        if (node.clickable) flags.add("clickable")
        if (node.scrollable) flags.add("scrollable")
        if (node.editable) flags.add("editable")
        if (flags.isNotEmpty()) builder.append(' ').append(flags.joinToString(","))
        if (node.id.isNotEmpty()) builder.append(" id=").append(node.id)
        builder.append('\n')
    }
    if (truncated) builder.append("…（节点过多，已截断）")
    return builder.toString().trimEnd()
}

private val NODE_TAG = Regex("<node\\b[^>]*>")
private val ATTR_TEXT = Regex("\\btext=\"([^\"]*)\"")
private val ATTR_DESC = Regex("\\bcontent-desc=\"([^\"]*)\"")
private val ATTR_BOUNDS = Regex("\\bbounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"")
private val ATTR_CLASS = Regex("\\bclass=\"([^\"]*)\"")
private val ATTR_ID = Regex("\\bresource-id=\"([^\"]*)\"")
private val ATTR_CLICKABLE = Regex("\\bclickable=\"true\"")
private val ATTR_SCROLLABLE = Regex("\\bscrollable=\"true\"")
private val ATTR_EDITABLE = Regex("\\bclass=\"[^\"]*(EditText|AutoCompleteTextView)[^\"]*\"")
private val FOCUS_PACKAGE = Regex("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+")

/** Pulls the package name out of `dumpsys window`'s focus lines. */
internal fun focusedPackage(dump: String): String? =
    dump.lineSequence()
        .firstOrNull { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
        ?.let { FOCUS_PACKAGE.find(it)?.groupValues?.get(1) }

private fun unescape(raw: String): String = raw
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&#10;", "\n")
    .replace("&amp;", "&")
