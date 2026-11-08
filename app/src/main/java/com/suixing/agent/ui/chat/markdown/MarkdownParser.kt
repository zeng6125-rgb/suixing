package com.suixing.agent.ui.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.suixing.agent.ui.chat.IdentityKey
import com.suixing.agent.R
import com.suixing.agent.ui.chat.LocalReadingTextKey
import com.suixing.agent.ui.chat.readingText
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf

internal val MD_FENCE = Regex("`{3,}\\s*([^`\\s]*)")

/**
 * A closing fence is a line of nothing but backticks. The opening test is just as
 * strict, so a fence the model wrote on the same line as its content (```echo x)
 * cannot pair with a stray closing fence somewhere below and swallow the reply
 * into an empty code box.
 */
internal val MD_FENCE_CLOSE = Regex("^`{3,}\\s*$")
internal val MD_HEADING = Regex("(#{1,6})\\s+(.*)")
internal val MD_RULE = Regex("\\s*([-*_])(\\s*\\1){2,}\\s*")
internal val MD_BULLET = Regex("(\\s*)[-*+]\\s+(.*)")
internal val MD_ORDERED = Regex("(\\s*)(\\d+)[.)]\\s+(.*)")
internal val MD_TABLE_CELL = Regex(":?-+:?")

/** U+2009. Inline code has no padding of its own, so this is what gives it any. */
internal const val THIN = "\u2009"
private const val FENCE = "```"

/** Nesting depth from a list marker's leading whitespace: two spaces or one tab per level. */
internal fun indentDepth(whitespace: String): Int {
    var spaces = 0
    whitespace.forEach { spaces += if (it == '\t') 2 else 1 }
    // Capped: a line indented by a screen's width is a formatting accident, and past four
    // levels the text is too narrow to read anyway.
    return (spaces / 2).coerceIn(0, MAX_LIST_DEPTH)
}
