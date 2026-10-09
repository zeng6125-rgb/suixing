package com.suixing.agent.ui.components

import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

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
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val item = MD_ORDERED.matchEntire(lines[index]) ?: break
                    if (indentDepth(item.groupValues[1]) != depth) break
                    items += item.groupValues[3]
                    index++
                }
                blocks += MdBlock.Bullet(items, ordered = true, depth = depth)
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
private val BOLD_SPAN = SpanStyle(fontWeight = FontWeight.SemiBold)
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
    // Inline code's size. Unspecified inherits the text around it, which is what the pure
    // entry point wants; the renderer passes the 13sp body slot instead.
    private val codeFontSize: TextUnit = TextUnit.Unspecified,
    // The weight of **bold**. MarkdownTest pins the pure entry point to Bold, and the
    // renderer draws the app's own ruling (semibold), so the two travel as a parameter
    // rather than one of them silently winning.
) {
    private val out = AnnotatedString.Builder()
    private val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
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
    style: TextStyle = markdownBody(),
    color: Color = Color.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = MarkdownPalette(
        codeBackground = scheme.surfaceContainerHigh,
        codeColor = scheme.onSurfaceVariant,
        linkColor = scheme.primary,
        codeFontSize = MaterialTheme.typography.bodySmall.fontSize,
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
) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block, color, palette, onUrlClick)
        is MdBlock.Paragraph -> InlineText(block.text, style, color, palette, onUrlClick)
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
            codeFontSize = palette.codeFontSize,
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
                            text = "${index + 1}.",
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
                    text = block.language,
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
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
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

