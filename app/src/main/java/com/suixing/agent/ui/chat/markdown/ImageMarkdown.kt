package com.suixing.agent.ui.chat.markdown

/**
 * 助手回复里的一张图：Markdown 图片语法 `![alt](url)`，url 是 http(s) 地址或 `data:image/...;base64,`。
 *
 * 为什么单独抽出来：解析是纯函数，能在 JVM 单测里逐条钉住；渲染层只管把 [MessageSegment.Text]
 * 交给 MarkdownText、把 [MessageSegment.Image] 交给图片块。混在 Compose 里就没法验证了。
 */
internal sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class Image(val alt: String, val url: String) : MessageSegment
}

/** `![alt](url)`；alt 可以为空，url 不允许带空白。 */
private val IMAGE = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

/** 只认这两种：能联网取的地址，与内联的 base64 图片。其它（相对路径、file://）当普通文字。 */
internal fun isRenderableImageUrl(url: String): Boolean =
    url.startsWith("http://", true) ||
        url.startsWith("https://", true) ||
        url.startsWith("data:image/", true)

/** `data:image/png;base64,AAAA` → `AAAA`；不是 data URL 时返回 null。 */
internal fun base64PayloadOf(url: String): String? {
    if (!url.startsWith("data:image/", true)) return null
    val at = url.indexOf(";base64,")
    if (at < 0) return null
    return url.substring(at + ";base64,".length).takeIf { it.isNotBlank() }
}

/**
 * 把一段正文切成「文字段 / 图片段」，保序。
 *
 * 围栏代码块（``` 或 ~~~）里的 `![x](y)` **不算图片**：那是用户/Al 想展示的源码，换成图就把
 * 例子吃掉了。行内代码里的同样不动（同一行里成对的反引号）。
 */
internal fun splitImageSegments(text: String, streaming: Boolean = false): List<MessageSegment> {
    if (text.isEmpty()) return emptyList()
    if (!text.contains("![")) return listOf(MessageSegment.Text(text))

    val out = mutableListOf<MessageSegment>()
    val plain = StringBuilder()
    var fence: String? = null
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out += MessageSegment.Text(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        // 行首的围栏：切换代码块状态，本身当普通文字。
        // 直接 startsWith 判定，不再每行 substring(3) 分配一个小 String —— 这段在流式
        // 每帧都跑，长文一次就是几百次分配；剩余不足 3 字符时 startsWith 同样返回 false。
        if (i == 0 || text[i - 1] == '\n') {
            val marker = when {
                text.startsWith("```", i) -> "```"
                text.startsWith("~~~", i) -> "~~~"
                else -> null
            }
            if (marker != null) {
                fence = if (fence == null) marker else if (fence == marker) null else fence
            }
        }
        if (fence == null && text[i] == '\\' && i + 1 < text.length &&
            (text[i + 1] == '`' || text[i + 1] == '\\' || text[i + 1] == '!')
        ) {
            plain.append(text, i, i + 2)
            i += 2
            continue
        }
        if (fence == null && text[i] == '`') {
            val openingEnd = backtickRunEnd(text, i)
            val lineEnd = text.indexOf('\n', openingEnd).let { if (it < 0) text.length else it }
            var closing = text.indexOf('`', openingEnd)
            var spanEnd = openingEnd
            while (closing in openingEnd until lineEnd) {
                val closingEnd = backtickRunEnd(text, closing)
                if (closingEnd - closing == openingEnd - i) {
                    spanEnd = closingEnd
                    break
                }
                closing = text.indexOf('`', closingEnd)
            }
            // A streamed example can contain a complete image before its closing backticks arrive.
            if (spanEnd == openingEnd && streaming && lineEnd == text.length) spanEnd = lineEnd
            plain.append(text, i, spanEnd)
            i = spanEnd
            continue
        }
        if (fence == null && text.startsWith("![", i)) {
            val m = IMAGE.matchAt(text, i)
            if (m != null) {
                // groupValues 是 lazy 列表，每次 get() 都新建 String：url 取一次，
                // 判定和建段共用；alt 只在真的要建 Image 段时取。
                val url = m.groupValues[2]
                if (isRenderableImageUrl(url)) {
                    flushPlain()
                    out += MessageSegment.Image(alt = m.groupValues[1], url = url)
                    i = m.range.last + 1
                    continue
                }
            }
        }
        plain.append(text[i])
        i++
    }
    flushPlain()
    return out
}

private fun backtickRunEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length && text[end] == '`') end++
    return end
}
