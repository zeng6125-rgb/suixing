package com.suixing.agent.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.ui.components.INLINE_ICON_BOX
import com.suixing.agent.ui.components.InlineIconAction
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.chat.markdown.MarkdownText
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.collectLatest

/** 过程块：轮次卡、状态点、转圈、过程注记与压缩块。 */


/**
 * The chrome every folded process block shares: thinking, a tool call and the turn's own
 * summary all draw through this, so a reply that thinks, calls, thinks again reads as one
 * repeating shape instead of three unrelated widgets stacked in a column.
 *
 * The header *is* the summary line — there is no second row above it — which is what
 * keeps a turn that ran ten tools the height of one row.
 */
@Composable
internal fun ProcessCard(
    icon: ImageVector,
    title: String,
    /** 外层留白/宽度：压缩卡要按助手卡片的左右留白收进来，其余卡片用默认。 */
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** First line of the folded content, shown after the meta while the card is closed. */
    preview: String? = null,
    /** Drawn right after [preview]: the streaming cursor for a folded think. */
    previewTail: (@Composable () -> Unit)? = null,
    /** The turn summary owns its seam with the flat tail; other cards keep their usual air. */
    contentBottomPadding: Dp = 6.dp,
    /** false = 没有可展开内容：不响应点击、不画箭头（卡片仍显示，用来报用时）。 */
    expandable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // **表头样式只有一个来源**：标题（已压缩上下文 · 03:44）与预览（68 条消息 → 摘要）
    // 必须是同一个字号 —— 两处各写一遍 `labelMedium` 时已经漂移过一次
    // （预览曾用 metaStyle()＝labelMedium×12/13，比标题小一档）。
    val headerStyle = MaterialTheme.typography.labelMedium
    // *折叠条切换后视口不动** —— 不在这里做任何事：这一项变高变矮会把上方内容推走，而 ChatScreen
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(processHeaderHeight())
                // 整块按下反馈（见 blockPress）：这一行是折叠条的入口，涟漪是一颗小圆角矩形。
                .then(
                    if (expandable) Modifier.blockPress(ProcessHeaderShape, expanded, title) { onToggle() } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                // One slot for all three card families: the icon sits centred in a
                // 24dp box at 16dp, so Psychology, Build and Insights line up exactly
                // instead of each header carrying its own size and tint.
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    // 折叠块统一：标题一律 labelMedium（原来这里是 labelLarge×13/14）。三处
                    // （思考块 / 工具行 / 本轮用量）的表头现在同一行高、同一字号、同一内边距。
                    style = headerStyle,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The name takes what it needs, not half the row. `weight(1f, fill = false)`
                    // used to hand it a share of the width and keep it even when the name was
                    // short: the leftover sat right after 「思考过程」 as a band of nothing, and a
                    // name as short as `run_shell` could come back cut in half inside it. With a
                    // preview beside it the name yields at [PROCESS_TITLE_MAX]; without one it
                    // still holds the row, which is what lets the summary card's long header
                    // ellipsize instead of the chip after it.
                    // 标题吃满剩余宽度 ⇒ 箭头贴行最右（有 preview 的卡片才收缩）。
                    modifier = if (preview != null) {
                        Modifier.widthIn(max = PROCESS_TITLE_MAX)
                    } else {
                        // **`fill = false`**（用户：「大总结条右侧折叠常驻icon改为贴内容右侧摆放」）：
                        // `weight(1f)` 会把标题撑满剩余宽度，箭头因此被顶到卡片右边缘；
                        // `fill = false` 只给它「可用份额」的上限、实际宽度按内容算，箭头就紧跟在
                        // 标题后面。标题过长时仍受份额与 `maxLines`/省略号约束，不会溢出。
                        //
                        // 只有**大总结条**走这个分支（`preview == null` 的 ProcessCard 调用点只有它）；
                        // 思考过程 / 工具 / 压缩卡都传了 preview，走上面那支，位置不变。
                        Modifier.weight(1f, fill = false)
                    },
                )
                if (trailing != null) {
                    // The title and the status are two different facts; without this gap
                    // they read as one word — 「run_shell1 个失败」.
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
                if (preview != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = preview,
                        // **与标题同一个变量**（用户：68 条消息 → 摘要 这个字体要和标题一致）。
                        // 原来这里用 metaStyle()＝labelMedium×12/13，比标题小一档。
                        style = headerStyle,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The row's one flexible slot: it eats whatever the name, the status and
                        // the chevron leave, so the chevron stays on the card's right edge and
                        // nothing is left over behind it. It used to be `fill = false` as well,
                        // which is what left that leftover band sitting after the chevron.
                        modifier = Modifier.weight(1f),
                    )
                    previewTail?.invoke()
                }
                if (expandable) ExpandChevron(expanded)
            }
            // 展开体：高度动画由 [RevealHeight] 驱动（滚动交给底锚，不再逐帧同步列表）。
            // 前两版都没成：只用 expandVertically + 追底＝两套逐帧动画，谁先谁后不确定，总会
            // 露出一帧位移；干脆不做高度动画＝不闪，但"啪"地出现，生硬（用户：「还是会闪一下
            // ……动画也生硬」）。这一版只做高度：逐帧把进度写进 progress，卡片底边由底锚
            // 钉住，长高只向上。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                Column {
                    // Header and content are one card; the hairline is their only seam.
                    if (expandable) HorizontalDivider(color = scheme.outlineVariant)
                    // The grid the expanded rows share: 12dp of side air, 8dp above and
                    // below, and every row inside lines its own column up to this edge.
                    Column(
                        // 水平 0dp（用户：「展开时原样展开」）：运行时那些块是顶到卡片左右边缘的，
                        // 12dp 内边距让展开后的内容整体变窄 —— 就是「又窄又短」的那一半。
                        // **上方 0dp**（用户：「折叠条分割线下方还有一处空白，不能去掉吗」）：
                        // 那 8dp 就是分割线下面那道空白，所有折叠块共用这一处，所以在这里一次去掉。
                        // 下方仍留 8dp：展开内容与卡片下沿之间需要一点收尾空气。
                        Modifier.padding(bottom = contentBottomPadding),
                        content = content,
                    )
                }
            }
        }
}

/**
 * 12sp for the trailing status of a [ProcessCard].
 *
 * Expressed off labelMedium rather than as a bare 12.sp so the user's 字号 still applies:
 * the theme scales every slot, and a hard size would opt this text out of it.
 */
/** 折叠条表头统一圆角：文件级常量，避免每次组合分配。 */
private val ProcessHeaderShape = RoundedCornerShape(8.dp)
@Composable
internal fun metaStyle(): TextStyle {
    val base = MaterialTheme.typography.labelMedium
    return remember(base) { base.copy(fontSize = base.fontSize * (12f / 13f)) }
}

/** The trailing status of a [ProcessCard]: a short phrase, never a sentence. */
@Composable
internal fun ProcessStatus(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = metaStyle(),
        color = tone,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The spinner both process blocks show while they are the one being waited on. */
@Composable
internal fun ProcessSpinner(tone: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(13.dp),
        strokeWidth = 2.dp,
        color = tone,
    )
}

/**
 * 一条过程留痕（「自动重试 1 次 · 服务器限流（429）」）。
 *
 * 刻意只做**一行小字 + 图标**，不是 [ProcessCard]：它没有可展开的内容，也不该看起来像一次
 * 工具调用。但它必须留在转录里 —— 这一轮是成功的，用户回头要能看出"这次慢是因为重试过"，
 * 而不是只看到一张说这轮失败了的红卡（用户：「重试成功要在过程态中留痕……整轮成功就不要有
 * 错误提示卡片」）。
 *
 * **[modifier] 由调用方给，块自己不带任何上下留白。** 两条渲染路径的间距来源不一样：流式那条
 * 每个 part 之间已经有 6dp 的 Spacer，转录那条没有、靠各 part 自己的内边距。写死在这里，两边的
 * 上下边距必然有一边对不上（用户截的就是这个：重试行贴着上面、离下面远）。
 */
@Composable
internal fun TraceBlock(note: String, modifier: Modifier = Modifier) {
    // **与折叠条同一套栅格**（用户：「你把它改成和思考块 工具块这种不行吗？这样的嵌入 icon
    // 标题什么都没有对齐」）：36dp 行高、图标居中在 24dp 槽里（16dp）、8dp 之后接标题，
    // 标题用 labelMedium —— 与 ProcessCard 的表头逐项相同，所以思考块 / 工具块 / 重试留痕
    // 三者的图标和文字左边线在一条竖线上。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(processHeaderHeight()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                // `material-icons-extended` 的最后一个非必要用途（issue #57 P1-b）：
                // 仓库现成的 Lucide `ic_rotate_cw` 就是同一个图形，去掉那 36MB 依赖。
                painter = painterResource(R.drawable.ic_rotate_cw),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 运行中的一次上下文压缩：画成过程块，位置就是它发生的那一刻。
 *
 * 与转录里那张 [CheckpointCard] 是同一件事的两种形态 —— 那张按"被折叠的最后一条消息"定位，
 * 负责回看；这一块属于正在跑的这轮，所以出现在「思考 → 工具 → 压缩 → 继续」的顺序里。
 * 不再往气泡下面塞一行提示（用户：「不需要这种在卡片下的提示」）。
 */
@Composable
internal fun CompressionBlock(
    checkpoint: ContextCheckpoint,
    expansion: ReasoningExpansion,
    blockId: String,
) {
    val expanded = expansion.stored(blockId) ?: false
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
        title = "已压缩上下文",
        expanded = expanded,
        onToggle = { expansion.set(blockId, !expanded) },
        preview = "${checkpoint.messages} 条消息 → 摘要 · 压缩前约 ${shortTokens(checkpoint.promptTokens)}",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(remember(expansion, blockId) { expansion.viewport(blockId).scrollState }),
        ) {
            // 同样按 Markdown 渲染（摘要里有粗体与有序列表）
            MarkdownText(
                text = checkpoint.summary,
                style = thinkingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
