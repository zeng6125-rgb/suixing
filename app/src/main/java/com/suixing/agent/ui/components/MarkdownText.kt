package com.suixing.agent.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Block model + parser. Pure Kotlin: no Android or Compose types are involved,
// so the whole thing is exercised by plain JUnit tests.
// ---------------------------------------------------------------------------

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

// Every pattern is compiled once, here. Building a Regex per line leaks native
// ICU buffers and has aborted the process before, so never move these inward.
private val MD_FENCE = Regex("`{3,}\\s*([^`\\s]*)")
private val MD_HEADING = Regex("(#{1,6})\\s+(.*)")
private val MD_RULE = Regex("\\s*([-*_])(\\s*\\1){2,}\\s*")
private val MD_BULLET = Regex("(\\s*)[-*+]\\s+(.*)")
private val MD_ORDERED = Regex("(\\s*)(\\d+)[.)]\\s+(.*)")
private val MD_TABLE_CELL = Regex(":?-+:?")
private const val FENCE = "```"

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
                while (index < lines.size && !lines[index].trim().startsWith(FENCE)) {
                    body += lines[index]
                    index++
                }
                // A missing closing fence means the answer is still streaming in.
                if (index < lines.size) index++
                blocks += MdBlock.Code(fence.groupValues[1], body.joinToString("\n"))
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
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val item = MD_BULLET.matchEntire(lines[index]) ?: break
                    items += item.groupValues[1] + item.groupValues[2]
                    index++
                }
                blocks += MdBlock.Bullet(items, ordered = false)
            }
            ordered != null -> {
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val item = MD_ORDERED.matchEntire(lines[index]) ?: break
                    items += item.groupValues[1] + item.groupValues[3]
                    index++
                }
                blocks += MdBlock.Bullet(items, ordered = true)
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
private val BOLD_SPAN = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC_SPAN = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE_SPAN = SpanStyle(textDecoration = TextDecoration.LineThrough)
private const val URL_HTTPS = "https://"
private const val URL_HTTP = "http://"
private const val URL_STOP = "<>\"`"
private const val URL_TRAILING = ".,;:!?*_~'\""
private const val ESCAPABLE = "*_`~[]()\\"

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
) {
    private val out = AnnotatedString.Builder()
    private val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    private val codeStyle = SpanStyle(
        background = codeBackground,
        color = codeColor,
        fontFamily = FontFamily.Monospace,
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
                        addRun(text.substring(i + 1, close), style.merge(codeStyle), link)
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
                        emit(target.labelStart, target.labelEnd, style.merge(linkStyle), target.url)
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
        val click = onUrlClick
        if (link != null && click != null) {
            out.addLink(
                LinkAnnotation.Url(
                    url = link,
                    linkInteractionListener = LinkInteractionListener { click(link) },
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
private val HeadingGap = 12.dp
private val ListGap = 6.dp

// A data class so the inline-scan cache key compares by value, not identity.
private data class MarkdownPalette(
    val codeBackground: Color,
    val codeColor: Color,
    val linkColor: Color,
)

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    MarkdownBlocks(blocks, modifier, style, color, onUrlClick)
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
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = MarkdownPalette(
        codeBackground = scheme.surfaceVariant.copy(alpha = 0.5f),
        codeColor = scheme.onSurfaceVariant,
        linkColor = scheme.primary,
    )
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(Modifier.height(gapBetween(blocks[index - 1], block)))
            }
            MarkdownBlock(block, style, color, palette, onUrlClick)
        }
    }
}

/** One parsed block, for a caller that is driving its own virtualised list. */
@Composable
fun MarkdownBlock(
    block: MdBlock,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    MarkdownBlock(
        block = block,
        style = style,
        color = color,
        palette = MarkdownPalette(
            codeBackground = scheme.surfaceVariant.copy(alpha = 0.5f),
            codeColor = scheme.onSurfaceVariant,
            linkColor = scheme.primary,
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
) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block, color, palette, onUrlClick)
        is MdBlock.Paragraph -> InlineText(block.text, style, color, palette, onUrlClick)
        is MdBlock.Bullet -> BulletBlock(block, style, color, palette, onUrlClick)
        is MdBlock.Code -> CodeBlock(block)
        is MdBlock.Quote -> QuoteBlock(block, style, color, palette, onUrlClick)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is MdBlock.Table -> TableBlock(block, style, color, palette, onUrlClick)
    }
}

private fun gapBetween(previous: MdBlock, current: MdBlock): Dp = when {
    previous is MdBlock.Heading || current is MdBlock.Heading -> HeadingGap
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
) {
    val annotated = remember(text, palette, onUrlClick) {
        InlineScanner(
            text = text,
            codeBackground = palette.codeBackground,
            codeColor = palette.codeColor,
            linkColor = palette.linkColor,
            // The block style already carries the base weight; spans only add to it.
            baseWeight = null,
            onUrlClick = onUrlClick,
        ).scan()
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
    )
}

@Composable
private fun HeadingBlock(
    block: MdBlock.Heading,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
) {
    val base = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    InlineText(
        text = block.text,
        style = base.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        palette = palette,
        onUrlClick = onUrlClick,
    )
}

@Composable
private fun BulletBlock(
    block: MdBlock.Bullet,
    style: TextStyle,
    color: Color,
    palette: MarkdownPalette,
    onUrlClick: ((String) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (block.ordered) "${index + 1}." else "•",
                    modifier = Modifier.width(22.dp),
                    style = style,
                    color = color,
                )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(
            text = block.code,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                .background(MaterialTheme.colorScheme.primary),
        )
        InlineText(
            text = block.text,
            style = style.copy(fontStyle = FontStyle.Italic),
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
            palette = palette,
            onUrlClick = onUrlClick,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
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
    Column(Modifier.fillMaxWidth()) {
        TableRow(
            cells = block.header,
            style = style.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            palette = palette,
            onUrlClick = onUrlClick,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        block.rows.forEach { row ->
            TableRow(
                cells = row,
                style = style,
                color = color,
                palette = palette,
                onUrlClick = onUrlClick,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        cells.forEach { cell ->
            InlineText(
                text = cell,
                style = style,
                color = color,
                palette = palette,
                onUrlClick = onUrlClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
        }
    }
}
