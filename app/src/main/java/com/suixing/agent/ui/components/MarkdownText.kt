package com.suixing.agent.ui.components

import kotlinx.coroutines.delay
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.suixing.agent.R

// ---------------------------------------------------------------------------
// Block model + parser. Pure Kotlin: no Android or Compose types are involved,
// so the whole thing is exercised by plain JUnit tests.
// ---------------------------------------------------------------------------

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(
        val items: List<String>,
        val ordered: Boolean,
        /** Nesting level, 0 = top level. One block holds one level's items. */
        val depth: Int = 0,
        /**
         * The number the source's *first* item carried, for an ordered list; 1 for bullets.
         *
         * The literal number has to survive parsing, because the renderer cannot always count:
         * a caller that draws one line at a time (the thinking block does exactly that) hands the
         * parser a single `7. foo` line, and the only place that 7 exists is the source. Numbering
         * such a block from 1 is what made every item of a 51-item chain of thought read "1.".
         */
        val start: Int = 1,
    ) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

// Every pattern is compiled once, here. Building a Regex per line leaks native
// ICU buffers and has aborted the process before, so never move these inward.
private val MD_FENCE = Regex("`{3,}\\s*([^`\\s]*)")

/**
 * A closing fence is a line of nothing but backticks. The opening test is just as
 * strict, so a fence the model wrote on the same line as its content (```echo x)
 * cannot pair with a stray closing fence somewhere below and swallow the reply
 * into an empty code box.
 */
private val MD_FENCE_CLOSE = Regex("^`{3,}\\s*$")
private val MD_HEADING = Regex("(#{1,6})\\s+(.*)")
private val MD_RULE = Regex("\\s*([-*_])(\\s*\\1){2,}\\s*")
private val MD_BULLET = Regex("(\\s*)[-*+]\\s+(.*)")
private val MD_ORDERED = Regex("(\\s*)(\\d+)[.)]\\s+(.*)")
private val MD_TABLE_CELL = Regex(":?-+:?")

/** U+2009. Inline code has no padding of its own, so this is what gives it any. */
private const val THIN = "\u2009"
private const val FENCE = "```"

/** Nesting depth from a list marker's leading whitespace: two spaces or one tab per level. */
private fun indentDepth(whitespace: String): Int {
    var spaces = 0
    whitespace.forEach { spaces += if (it == '\t') 2 else 1 }
    // Capped: a line indented by a screen's width is a formatting accident, and past four
    // levels the text is too narrow to read anyway.
    return (spaces / 2).coerceIn(0, MAX_LIST_DEPTH)
}

private const val MAX_LIST_DEPTH = 4

/**
 * The largest marker a list may start at.
 *
 * A number past this is a formatting accident, not a list position: the marker column is 22dp,
 * and a wider number would push the item text off its own row. It also keeps `start + index`
 * inside an Int for a source that wrote an absurd number.
 */
private const val MAX_LIST_START = 9999

/**
 * The literal number a list marker carried, ready to be a [MdBlock.Bullet.start].
 *
 * Anything that cannot be a sensible start degrades to 1: a run of digits too long for an Int, or
 * one past [MAX_LIST_START].
 */
internal fun listStart(digits: String): Int = (digits.toIntOrNull() ?: 1).coerceIn(0, MAX_LIST_START)

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MdBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        val fence = MD_FENCE.matchEntire(trimmed)
        val heading = MD_HEADING.matchEntire(trimmed)
        val bullet = MD_BULLET.matchEntire(line)
        val ordered = MD_ORDERED.matchEntire(line)
        when {
            trimmed.isEmpty() -> index++
            fence != null -> {
                val body = mutableListOf<String>()
                index++
                while (index < lines.size && !MD_FENCE_CLOSE.matches(lines[index].trim())) {
                    body += lines[index]
                    index++
                }
                // A missing closing fence means the answer is still streaming in.
                if (index < lines.size) index++
                // An empty body never becomes a box: a stray fence line (or a pair of
                // them) used to paint a grey slab with a copy button and nothing in it.
                // A fence written on the same line as its content never reaches this
                // branch at all — the opening test is matchEntire-strict — so it stays
                // prose and nothing leaks into a code block.
                if (body.joinToString("\n").isNotBlank()) {
                    blocks += MdBlock.Code(fence.groupValues[1], body.joinToString("\n"))
                }
            }
            heading != null -> {
                blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                index++
            }
            MD_RULE.matches(trimmed) -> {
                blocks += MdBlock.Rule
                index++
            }
            trimmed.startsWith('>') -> {
                val quoted = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith('>')) {
                    quoted += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                    index++
                }
                blocks += MdBlock.Quote(quoted.joinToString("\n"))
            }
            isTableStart(lines, index) -> {
                val header = splitTableRow(trimmed)
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size) {
                    val row = lines[index].trim()
                    if (row.isEmpty() || !row.contains('|')) break
                    rows += splitTableRow(row)
                    index++
                }
                blocks += MdBlock.Table(header, rows)
            }
            bullet != null -> {
                val depth = indentDepth(bullet.groupValues[1])
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val item = MD_BULLET.matchEntire(lines[index]) ?: break
                    // A change of level ends the block: one Bullet carries one indent, so
                    // a nested list renders as its own indented block underneath.
                    if (indentDepth(item.groupValues[1]) != depth) break
                    items += item.groupValues[2]
                    index++
                }
                blocks += MdBlock.Bullet(items, ordered = false, depth = depth)
            }
            ordered != null -> {
                val depth = indentDepth(ordered.groupValues[1])
                // Read off the first line of the run, before the loop consumes it: the rest of
                // the run's numbers are the source's business, and CommonMark ignores them too.
                val start = listStart(ordered.groupValues[2])
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val item = MD_ORDERED.matchEntire(lines[index]) ?: break
                    if (indentDepth(item.groupValues[1]) != depth) break
                    items += item.groupValues[3]
                    index++
                }
                blocks += MdBlock.Bullet(items, ordered = true, depth = depth, start = start)
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size && !opensBlock(lines, index)) {
                    paragraph += lines[index].trim()
                    index++
                }
                blocks += MdBlock.Paragraph(paragraph.joinToString("\n"))
            }
        }
    }
    return blocks
}

// Must test the same constructs, in the same order, as the dispatch above.
private fun opensBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    val trimmed = line.trim()
    return trimmed.isEmpty() ||
        MD_FENCE.matches(trimmed) ||
        MD_HEADING.matches(trimmed) ||
        MD_RULE.matches(trimmed) ||
        trimmed.startsWith('>') ||
        isTableStart(lines, index) ||
        MD_BULLET.matches(line) ||
        MD_ORDERED.matches(line)
}

private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (!lines[index].contains('|')) return false
    val separator = lines.getOrNull(index + 1)?.trim() ?: return false
    return isTableSeparator(separator)
}

private fun isTableSeparator(line: String): Boolean {
    if (!line.contains('-') || !line.contains('|')) return false
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { MD_TABLE_CELL.matches(it) }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

// ---------------------------------------------------------------------------
// Inline scanner. One left-to-right pass with recursive descent for nesting;
// an unmatched marker is written back out as literal text, which is what
// half-streamed answers look like.
// ---------------------------------------------------------------------------

private val EMPTY_SPAN = SpanStyle()

/**
 * 文件路径的点击回调（LINK-CLICK-SPEC §3.2）。宿主（ChatScreen）在会话页顶层 provide 一次，
 * 所有 [InlineText] 叶子就地读取 —— 四个 MarkdownText 调用点与中间各层 block composable 都
 * **不用加参数**。null（默认）时路径只是带色文本、不可点。
 */
val LocalPathClick = compositionLocalOf<((String) -> Unit)?> { null }
private val BOLD_SPAN = SpanStyle(fontWeight = FontWeight.SemiBold)
private val ITALIC_SPAN = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE_SPAN = SpanStyle(textDecoration = TextDecoration.LineThrough)
private const val URL_HTTPS = "https://"
private const val URL_HTTP = "http://"
private const val URL_STOP = "<>\"`"
private const val URL_TRAILING = ".,;:!?*_~'\""
private const val ESCAPABLE = "*_`~[]()\\"

// 文件路径识别（LINK-CLICK-SPEC §1.3）：只认这几类**绝对路径根**，不认相对路径（歧义太大）。
// 允许中文文件名（pathEnd 在空白/引号/CJK 标点处停，与 URL 同构）。走 InlineScanner 而非独立
// 正则 detect()：复用已有的 [label](url)/裸 URL 识别，天然避开 spec §1.4 警告的重叠 span。
private val PATH_ROOTS = listOf(
    "/sdcard/", "/storage/emulated/", "/storage/self/primary/",
    "/data/data/", "/data/local/tmp/", "/data/user/",
)

/** 一个 link 字符串是不是绝对文件路径（而非 URL）。addRun 用它决定挂 Url 还是 Clickable。 */
private fun isFilePath(s: String): Boolean = PATH_ROOTS.any { s.startsWith(it) }

fun inlineMarkdown(
    text: String,
    codeBackground: Color = Color.Unspecified,
    codeColor: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    baseWeight: FontWeight? = null,
): AnnotatedString = InlineScanner(
    text = text,
    codeBackground = codeBackground,
    codeColor = codeColor,
    linkColor = linkColor,
    baseWeight = baseWeight,
    onUrlClick = null,
).scan()

private data class LinkTarget(val labelStart: Int, val labelEnd: Int, val url: String, val end: Int)

private class InlineScanner(
    private val text: String,
    private val codeBackground: Color,
    private val codeColor: Color,
    private val linkColor: Color,
    private val baseWeight: FontWeight?,
    private val onUrlClick: ((String) -> Unit)?,
    // 文件路径的点击回调（LINK-CLICK-SPEC §3.2）。与 onUrlClick 分开：URL 恒走默认浏览器，
    // 路径才弹菜单。null 时路径只是带色文本、不可点（避免「看着能点却没反应」）。
    private val onPathClick: ((String) -> Unit)? = null,
    // Inline code's size. Unspecified inherits the text around it, which is what the pure
    // entry point wants; the renderer passes the 13sp body slot instead.
    private val codeFontSize: TextUnit = TextUnit.Unspecified,
    // The weight of **bold**. MarkdownTest pins the pure entry point to Bold, and the
    // renderer draws the app's own ruling (semibold), so the two travel as a parameter
    // rather than one of them silently winning.
) {
    private val out = AnnotatedString.Builder()
    private val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    // 文件路径：主色但**无下划线**（LINK-CLICK-SPEC §2.1）——与 URL 区分，符合「链接才带下划线」的直觉。
    private val pathStyle = SpanStyle(color = linkColor)
    private val codeStyle = SpanStyle(
        background = codeBackground,
        color = codeColor,
        fontFamily = FontFamily.Monospace,
        fontSize = codeFontSize,
    )

    fun scan(): AnnotatedString {
        val base = if (baseWeight == null) EMPTY_SPAN else SpanStyle(fontWeight = baseWeight)
        emit(0, text.length, base, null)
        return out.toAnnotatedString()
    }

    private fun emit(from: Int, to: Int, style: SpanStyle, link: String?) {
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                addRun(buffer.toString(), style, link)
                buffer.setLength(0)
            }
        }
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < to && ESCAPABLE.indexOf(text[i + 1]) >= 0 -> {
                    buffer.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val close = findClosing('`', 1, i + 1, to)
                    if (close < 0) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        // Thin spaces either side of the code. A SpanStyle background has no
                        // padding and no corner radius — Compose inline spans cannot express
                        // either — so the breathing room has to be characters. They are real
                        // characters in the output, which the tests assert.
                        addRun(THIN + text.substring(i + 1, close) + THIN, style.merge(codeStyle), link)
                        i = close + 1
                    }
                }
                c == '*' || c == '_' -> {
                    val len = if (runLength(i, to) >= 2) 2 else 1
                    val close = findClosing(c, len, i + len, to)
                    if (close < 0) {
                        repeat(len) { buffer.append(c) }
                        i += len
                    } else {
                        flush()
                        val inner = style.merge(if (len == 2) BOLD_SPAN else ITALIC_SPAN)
                        emit(i + len, close, inner, link)
                        i = close + len
                    }
                }
                c == '~' && i + 1 < to && text[i + 1] == '~' -> {
                    val close = findClosing('~', 2, i + 2, to)
                    if (close < 0) {
                        buffer.append("~~")
                        i += 2
                    } else {
                        flush()
                        emit(i + 2, close, style.merge(STRIKE_SPAN), link)
                        i = close + 2
                    }
                }
                c == '[' -> {
                    val target = parseLink(i, to)
                    if (target == null) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        // 目标可能是 URL 或文件路径（spec §1.1 两种都允许）。路径用 pathStyle（无
                        // 下划线）并加 `file:` 前缀，addRun 据此挂 Clickable 而非 Url。
                        val isPath = isFilePath(target.url)
                        emit(
                            target.labelStart, target.labelEnd,
                            style.merge(if (isPath) pathStyle else linkStyle),
                            if (isPath) "file:${target.url}" else target.url,
                        )
                        i = target.end
                    }
                }
                // Bare urls are only auto-linked outside a label, so a link never nests.
                link == null && c == 'h' && startsUrl(i, to) -> {
                    val end = urlEnd(i, to)
                    flush()
                    val url = text.substring(i, end)
                    addRun(url, style.merge(linkStyle), url)
                    i = end
                }
                // 裸文件路径（spec §1.3）：绝对路径根开头才认，走到路径末尾、剥掉句末标点与尾斜杠。
                // 只在回调存在时才真正可点（addRun 里把关），没回调时它就是一段普通主色文本 —— 但
                // pathStyle 无下划线，不至于伪装成能点的链接。
                link == null && c == '/' && startsPath(i, to) -> {
                    val end = pathEnd(i, to)
                    if (end <= i) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        val path = text.substring(i, end)
                        addRun(path, style.merge(pathStyle), "file:$path")
                        i = end
                    }
                }
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
    }

    private fun addRun(run: String, style: SpanStyle, link: String?) {
        if (run.isEmpty()) return
        val start = out.length
        out.append(run)
        val end = out.length
        if (style != EMPTY_SPAN) out.addStyle(style, start, end)
        // LinkAnnotation.Url is stable in ui-text 1.7.x and Text itself wires the
        // click handling, so no ClickableText and no pointerInput hit testing.
        if (link == null) return
        if (link.startsWith("file:")) {
            // 文件路径（LINK-CLICK-SPEC §1.3/§3.2）：只在有 onPathClick 回调时才可点 —— 没有回调的
            // Clickable 会「看着能点、点了没反应」，比不加更糟。回调解掉 `file:` 前缀后拿到路径，
            // 调用方弹菜单（复制 / 让随行读取）。URL 与路径分属两个回调：URL 恒走默认浏览器。
            val path = link.removePrefix("file:")
            val pathClick = onPathClick
            if (pathClick != null) {
                out.addLink(
                    LinkAnnotation.Clickable(
                        tag = link,
                        linkInteractionListener = LinkInteractionListener { pathClick(path) },
                    ),
                    start,
                    end,
                )
            }
        } else {
            // URL **恒挂** annotation：有回调用回调，没回调时 listener=null，Compose 的 Text 用
            // LocalUriHandler 默认开浏览器（spec §2.1「Text itself wires the click handling」）。
            // 旧代码只在有回调时才挂，而四个调用点都没传回调 ⇒ URL 有下划线却点不动，这是 bug。
            out.addLink(
                LinkAnnotation.Url(
                    url = link,
                    linkInteractionListener = onUrlClick?.let { cb -> LinkInteractionListener { cb(link) } },
                ),
                start,
                end,
            )
        }
    }

    private fun runLength(index: Int, to: Int): Int {
        val c = text[index]
        var end = index
        while (end < to && text[end] == c) end++
        return end - index
    }

    // Position of the delimiter that closes a marker opened with [len] characters,
    // or -1 when the marker never closes. Escaped delimiters do not close.
    private fun findClosing(marker: Char, len: Int, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to) {
                i += 2
                continue
            }
            if (c == marker) {
                val runEnd = i + runLength(i, to)
                val runStartsHere = i == 0 || text[i - 1] != marker
                val available = runEnd - i
                if (runStartsHere && (if (len == 1) available == 1 else available >= len)) return i
                i = runEnd
                continue
            }
            i++
        }
        return -1
    }

    private fun startsUrl(index: Int, to: Int): Boolean {
        if (index > 0 && text[index - 1].isLetterOrDigit()) return false
        if (index + URL_HTTPS.length <= to && text.startsWith(URL_HTTPS, index)) return true
        return index + URL_HTTP.length <= to && text.startsWith(URL_HTTP, index)
    }

    // 一个 '/' 处是不是某类绝对路径根（§1.3）。前一字符是字母数字就不算（避开 http://… 里的 /）。
    private fun startsPath(index: Int, to: Int): Boolean {
        if (index > 0 && text[index - 1].isLetterOrDigit()) return false
        return PATH_ROOTS.any { root ->
            index + root.length <= to && text.regionMatches(index, root, 0, root.length)
        }
    }

    // 路径末尾：吃到空白/引号/尖括号/反引号为止，再剥掉句末标点与尾斜杠（§1.3：末尾 / 视为目录，
    // 但这里不识别目录，只保证不把 '.' 或 '，' 这种吃进路径）。与 urlEnd 同构，但用路径的停止集。
    private fun pathEnd(from: Int, to: Int): Int {
        var end = from
        while (end < to) {
            val c = text[end]
            if (c.isWhitespace() || c == '"' || c == '<' || c == '>' || c == '`' ||
                c == '，' || c == '。' || c == '；' || c == '：' || c == '、' ||
                c == '）' || c == '》' || c == '】' || c == '」'
            ) break
            end++
        }
        while (end > from && (text[end - 1] in URL_TRAILING || text[end - 1] == '/')) end--
        return end
    }

    private fun urlEnd(from: Int, to: Int): Int {
        var end = from
        while (end < to) {
            val c = text[end]
            if (c.isWhitespace() || URL_STOP.indexOf(c) >= 0) break
            end++
        }
        while (end > from) {
            val c = text[end - 1]
            if (URL_TRAILING.indexOf(c) >= 0) {
                end--
                continue
            }
            // A trailing ")" only belongs to the url when it is balanced.
            if (c == ')' && countChar(from, end, '(') < countChar(from, end, ')')) {
                end--
                continue
            }
            break
        }
        return end
    }

    private fun countChar(from: Int, to: Int, c: Char): Int {
        var count = 0
        for (i in from until to) {
            if (text[i] == c) count++
        }
        return count
    }

    private fun parseLink(start: Int, to: Int): LinkTarget? {
        var cursor = start + 1
        while (cursor < to) {
            val c = text[cursor]
            if (c == '\\' && cursor + 1 < to) {
                cursor += 2
                continue
            }
            if (c == '[') return null
            if (c == ']') break
            cursor++
        }
        if (cursor >= to || cursor == start + 1) return null
        if (cursor + 1 >= to || text[cursor + 1] != '(') return null
        var urlEnd = cursor + 2
        while (urlEnd < to && text[urlEnd] != ')') {
            if (text[urlEnd] == '\\' && urlEnd + 1 < to) urlEnd++
            urlEnd++
        }
        if (urlEnd >= to) return null
        val url = text.substring(cursor + 2, urlEnd).trim()
        if (url.isEmpty()) return null
        return LinkTarget(start + 1, cursor, url, urlEnd + 1)
    }
}

// ---------------------------------------------------------------------------
// Composable
// ---------------------------------------------------------------------------

private val BlockGap = 8.dp
private val HeadingGapAbove = 12.dp
private val HeadingGapBelow = 6.dp
private val ListGap = 6.dp
private val ListItemGap = 4.dp
private val BulletDot = 4.dp
private val ListIndent = 16.dp
private val MarkerWidth = 22.dp
private val RuleGap = 12.dp

/**
 * The renderer's own body metrics: 15sp on a 24sp leading.
 *
 * Because the theme scales every slot, the app's 字号 setting
 * still applies to the transcript.
 */
@Composable
internal fun markdownBody(): TextStyle = MaterialTheme.typography.bodyMedium

/** [this] with both sizes times [ratio], so a scaled slot keeps its own leading. */
private fun TextStyle.scaled(ratio: Float): TextStyle =
    copy(fontSize = fontSize * ratio, lineHeight = lineHeight * ratio)

// A data class so the inline-scan cache key compares by value, not identity.
private data class MarkdownPalette(
    val codeBackground: Color,
    val codeColor: Color,
    val linkColor: Color,
    val codeFontSize: TextUnit,
)

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = markdownBody(),
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
    /**
     * 在**最后一个字符之后**画一个闪烁光标（流式输出用）。
     *
     * 光标必须内联进文本：以前它是一个独立的 `Text("▍")`，于是会自成一行或挤到块外
     * （用户截图里那根竖条悬在正文下方）。这里把它作为 `AnnotatedString` 的最后一段追加，
     * 换行、对齐都跟着正文走。
     *
     * 只在 `Paragraph` 上追加：标题/列表/代码追加光标会让排版变怪，而流式正文几乎总是段落。
     */
    cursor: Boolean = false,
) {
    val blocks = rememberGrowingMarkdown(text)
    MarkdownBlocks(blocks, modifier, style, color, onUrlClick, cursor)
}

/**
 * [parseMarkdown], but only for the part of [text] that is new.
 *
 * A streamed answer grows by a few characters at a time, and the render cache is keyed by the
 * whole string, so every publish re-parsed the whole document: for a long answer at a dozen
 * publishes a second that is a dozen full parses of a growing text, and the frames that lose
 * that race are the ghosting the user reported.
 *
 * The trick is that a finished block never changes. Everything before the last blank line is
 * therefore stable once parsed, so this keeps that prefix and its blocks, and a publish that
 * only extends the current block parses just the tail. The prefix is recomputed only when a new
 * blank line lands — a few times per answer, not a dozen times a second.
 */
@Composable
private fun rememberGrowingMarkdown(text: String): List<MdBlock> {
    // A plain holder rather than state: the blocks are returned from this call, so nothing has
    // to recompose when the cache moves on, and writing state during composition is exactly
    // the kind of thing that recomposes forever.
    val cache = remember { MarkdownCache() }
    return cache.blocksFor(text)
}

/**
 * The last parse, kept so the next one can start where this one stopped.
 *
 * [prefix] is the settled part of the text (everything before its last blank line) and
 * [prefixBlocks] is that part's parse: the two together are what makes appending a freshly
 * parsed tail correct — reusing the *whole* previous block list would append the tail to a list
 * that already contained the old one.
 */
private class MarkdownCache {
    private var text = ""
    private var prefix = ""
    private var prefixBlocks: List<MdBlock> = emptyList()
    private var blocks: List<MdBlock> = emptyList()

    fun blocksFor(next: String): List<MdBlock> {
        if (next == text) return blocks
        val settled = settledPrefix(next)
        if (settled.isNotEmpty() && settled == prefix) {
            blocks = prefixBlocks + parseMarkdown(next.removePrefix(settled))
        } else {
            prefixBlocks = if (settled.isEmpty()) emptyList() else parseMarkdown(settled)
            blocks = if (settled.isEmpty()) {
                parseMarkdown(next)
            } else {
                prefixBlocks + parseMarkdown(next.removePrefix(settled))
            }
            prefix = settled
        }
        text = next
        return blocks
    }

    /**
     * The part of [text] that can never change again, ending just past a blank line.
     *
     * A blank line normally ends a block, which is what makes the split safe — except inside a
     * fenced code block, where it does not: cutting a fence in half would render one code block
     * as two, one of them unterminated. So the last blank line with an even number of fences
     * before it is the one taken, and a text whose only blank lines are inside fences simply has
     * no settled part (it is re-parsed whole, which is what it did before any of this).
     */
    private fun settledPrefix(text: String): String {
        var searchFrom = text.length
        while (true) {
            val at = text.lastIndexOf("\n\n", searchFrom - 1)
            // at == 0 is a blank line at the very top of the answer, and "\n\n" is a settled
            // prefix like any other (its parse is empty). Treating it as "nothing settled"
            // re-parsed the whole answer on every publish for as long as it was there.
            if (at < 0) return ""
            val candidate = text.substring(0, at + 2)
            if (candidate.count { it == '`' } % 2 == 0) return candidate
            searchFrom = at
        }
    }
}

/**
 * The same rendering, for a caller that already owns the block list.
 *
 * The chat screen parses the model's thinking once and lays the blocks out through
 * its own [androidx.compose.foundation.lazy.LazyColumn], so a chain of thought that
 * grows to thousands of lines never composes more than a screenful at a time.
 */
@Composable
fun MarkdownBlocks(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    style: TextStyle = markdownBody(),
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
    /** 见 [MarkdownText] 的 `cursor`：只有最后一个段落会带上它。 */
    cursor: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = MarkdownPalette(
        codeBackground = scheme.surfaceContainerHigh,
        codeColor = scheme.onSurfaceVariant,
        linkColor = scheme.primary,
        codeFontSize = MaterialTheme.typography.bodySmall.fontSize,
    )
    // 光标挂在**最后一个 block 是段落**时；否则不画（列表/代码块尾部挂光标排版会怪）。
    val cursorAt = if (cursor) blocks.indexOfLast { it is MdBlock.Paragraph } else -1
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(Modifier.height(gapBetween(blocks[index - 1], block)))
            }
            MarkdownBlock(
                block = block,
                style = style,
                color = color,
                palette = palette,
                onUrlClick = onUrlClick,
                cursor = index == cursorAt && cursorAt == blocks.lastIndex,
            )
        }
    }
}

/** One parsed block, for a caller that is driving its own virtualised list. */
@Composable
fun MarkdownBlock(
    block: MdBlock,
    style: TextStyle = markdownBody(),
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    MarkdownBlock(
        block = block,
        style = style,
        color = color,
        palette = MarkdownPalette(
            codeBackground = scheme.surfaceContainerHigh,
            codeColor = scheme.onSurfaceVariant,
            linkColor = scheme.primary,
            codeFontSize = MaterialTheme.typography.bodySmall.fontSize,
        ),
        onUrlClick = onUrlClick,
    )
}

/** How much air a block gets above it, given what came before. */
fun markdownGap(previous: MdBlock?, current: MdBlock): Dp =
    if (previous == null) 0.dp else gapBetween(previous, current)

@Composable
private fun MarkdownBlock(
    block: MdBlock,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
    cursor: Boolean = false,
) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block, color, palette, onUrlClick)
        is MdBlock.Paragraph -> InlineText(block.text, style, color, palette, onUrlClick, cursor = cursor)
        is MdBlock.Bullet -> BulletBlock(block, style, color, palette, onUrlClick)
        is MdBlock.Code -> CodeBlock(block)
        is MdBlock.Quote -> QuoteBlock(block, style, color, palette, onUrlClick)
        // No padding of its own: the 12dp above and below comes from gapBetween, so a
        // caller spacing blocks by markdownGap() gets the same rhythm.
        MdBlock.Rule -> HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        is MdBlock.Table -> TableBlock(block, style, color, palette, onUrlClick)
    }
}

private fun gapBetween(previous: MdBlock, current: MdBlock): Dp = when {
    // A rule is a hard break, so it keeps its 12dp even beside a heading or a list.
    previous is MdBlock.Rule || current is MdBlock.Rule -> RuleGap
    // A heading is closer to the text it introduces than to the block above it, which is
    // what makes it read as a title for what follows rather than for what came before.
    previous is MdBlock.Heading -> HeadingGapBelow
    current is MdBlock.Heading -> HeadingGapAbove
    previous is MdBlock.Bullet || current is MdBlock.Bullet -> ListGap
    else -> BlockGap
}

@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 在**这段文本的最后一个字符后面**追加一个闪烁光标。
     *
     * 追加发生在 `InlineScanner.scan()` **之后**，所以 markdown 的解析边界完全不受影响：
     * `▍` 不会被当成语法的一部分，也不会打断行内代码/链接的配对。
     */
    cursor: Boolean = false,
) {
    // 路径回调从 CompositionLocal 就地读（叶子层），调用点与中间层零改动。URL 仍走 onUrlClick。
    val pathClick = LocalPathClick.current
    val annotated = remember(text, palette, onUrlClick, pathClick) {
        InlineScanner(
            text = text,
            codeBackground = palette.codeBackground,
            codeColor = palette.codeColor,
            linkColor = palette.linkColor,
            // The block style already carries the base weight; spans only add to it.
            baseWeight = null,
            onUrlClick = onUrlClick,
            onPathClick = pathClick,
            codeFontSize = palette.codeFontSize,
        ).scan()
    }
    // 光标：解析完成后再往末尾追加，markdown 边界不受影响（见 cursor 的说明）。
    // 用 AnnotatedString 拼接而不是外面再放一个 Text —— 后者会自成一行或挤出块外。
    val alpha = if (cursor) blinkingCursorAlpha() else 1f
    val shown = if (cursor) {
        remember(annotated, alpha) {
            buildAnnotatedString {
                append(annotated)
                withStyle(SpanStyle(color = palette.linkColor.copy(alpha = alpha))) {
                    append(CURSOR_GLYPH)
                }
            }
        }
    } else {
        annotated
    }
    Text(
        text = shown,
        modifier = modifier,
        style = style,
        color = color,
    )
}

/** 光标字符：细竖条，和正文同一行高。 */
private const val CURSOR_GLYPH = "▍"

/** 与 [com.suixing.agent.ui.chat.BlinkingCursor] 同一节奏（480ms 往返），但作用于文字颜色。 */
@Composable
private fun blinkingCursorAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "inlineCursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inlineCursorAlpha",
    )
    return alpha
}

@Composable
private fun HeadingBlock(
    block: MdBlock.Heading,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
) {
    // One ramp, scaled off titleLarge: 20 / 18 / 16, with 16 medium for anything deeper.
    // Leading scales with the size, so a heading keeps the 22/29 ratio it was drawn with
    // instead of every level inheriting titleLarge's airy 29sp.
    val large = MaterialTheme.typography.titleLarge
    val base = when (block.level) {
        1 -> large.scaled(20f / 22f).copy(fontWeight = FontWeight.Bold)
        2 -> large.scaled(18f / 22f).copy(fontWeight = FontWeight.Bold)
        3 -> large.scaled(16f / 22f).copy(fontWeight = FontWeight.SemiBold)
        else -> large.scaled(16f / 22f).copy(fontWeight = FontWeight.Medium)
    }
    InlineText(
        text = block.text,
        style = base,
        color = color,
        palette = palette,
        onUrlClick = onUrlClick,
    )
}

/**
 * The marker drawn for item [index] of an ordered list: the source's own start plus the item's
 * place in the block.
 *
 * Counting from 1 instead is what turned a 51-item chain of thought into 51 lines all reading
 * "1.": that caller renders one line at a time, so every block it parses holds exactly one item
 * and `${index + 1}` is always 1. Taken from the source, the number is right however the text was
 * cut up, and a list written `3. a` / `4. b` keeps its 3 and 4 rather than being renumbered.
 */
internal fun orderedMarker(block: MdBlock.Bullet, index: Int): String = "${block.start + index}."

@Composable
private fun BulletBlock(
    block: MdBlock.Bullet,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
) {
    // The marker sits on the first line's centre, not on the row's top: a 4dp dot level
    // with the top edge reads as floating above the text it belongs to.
    val markerOffset = with(LocalDensity.current) {
        ((style.lineHeight.takeIf { it.isSp }?.toDp() ?: 20.dp) - BulletDot) / 2
    }
    Column(
        Modifier
            .fillMaxWidth()
            // One indent per level, marker included: the dot has to move with its text or
            // a nested list reads as a second top-level one.
            .padding(start = ListIndent * block.depth),
    ) {
        block.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ListItemGap / 2),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier.width(MarkerWidth),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (block.ordered) {
                        // The number is a text line of its own, so it needs no offset: the
                        // row's top edge already puts it on the item's first-line baseline.
                        Text(
                            text = orderedMarker(block, index),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(
                            Modifier
                                .padding(top = markerOffset)
                                .size(BulletDot)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                }
                InlineText(
                    text = item,
                    style = style,
                    color = color,
                    palette = palette,
                    onUrlClick = onUrlClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.code) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // A fenced block with no info string — a plain ``` — has an empty language,
                    // and the title row is drawn for every block so the copy button always sits
                    // in the same place. An empty label left that row blank and the whole block
                    // read as if something had failed to render; the user said so. 「代码」 is
                    // what a block with no language is, and it keeps the row's height honest.
                    text = block.language.ifBlank { "Markdown" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Copying a code block is the one thing anyone does with one, and the
                // tick is the only confirmation there is room for.
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (copied) ImageVector.vectorResource(R.drawable.ic_check) else ImageVector.vectorResource(R.drawable.ic_copy),
                        contentDescription = if (copied) "已复制" else "复制代码",
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (copied) {
                LaunchedEffect(block.code) {
                    delay(1500)
                    copied = false
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = block.code,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                // bodySmall is already 13sp monospace-sized; only the leading differs,
                // and it is scaled off the font size so 字号 still applies.
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = MaterialTheme.typography.bodySmall.fontSize * (20f / 13f),
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun QuoteBlock(
    block: MdBlock.Quote,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        )
        InlineText(
            text = block.text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            palette = palette,
            onUrlClick = onUrlClick,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
    }
}

@Composable
private fun TableBlock(
    block: MdBlock.Table,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
) {
    val cellStyle = MaterialTheme.typography.bodySmall
    // 12sp semibold, scaled off bodySmall so the header still follows the 字号 setting.
    val headerStyle = cellStyle.copy(
        fontSize = cellStyle.fontSize * (12f / 13f),
        lineHeight = cellStyle.lineHeight * (12f / 13f),
        fontWeight = FontWeight.SemiBold,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        TableRow(
            cells = block.header,
            style = headerStyle,
            color = color,
            palette = palette,
            onUrlClick = onUrlClick,
            header = true,
        )
        block.rows.forEachIndexed { index, row ->
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            TableRow(
                cells = row,
                style = cellStyle,
                color = color,
                palette = palette,
                onUrlClick = onUrlClick,
                header = false,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
    header: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The row, not the separator, asks for the intrinsic height: an empty Box has
            // none of its own, so a separator that measured itself would collapse to 0dp.
            .height(IntrinsicSize.Min)
            .background(
                if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            ),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            InlineText(
                text = cell,
                style = style,
                color = color,
                palette = palette,
                onUrlClick = onUrlClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

