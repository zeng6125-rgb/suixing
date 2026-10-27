package com.suixing.agent.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.SuixingDialog
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.suixing.agent.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/**
 * The context entry on the title capsule's right end (user: 「原圆环的内容移至标题胶囊右侧，
 * 可以换个 icon」): a bar-chart glyph, and a popup that opens **downward** — the bar sits at the
 * screen's top, so the ring's upward-opening card would leave the window here. Its content is
 * the ring's original detail card, unchanged.
 */
@Composable
internal fun TopBarContextEntry(
    used: Int,
    estimated: Boolean,
    window: Int,
    sessionTokens: Int,
    sessionEstimate: Int,
    sessionSplit: SessionSplit,
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Double,
    sessionElapsedMs: Long,
    statsFlow: StateFlow<StreamStats?>,
    /** 当前模型名，用于在面板里说明"窗口是多少、属于谁"（issue #49）。 */
    model: String = "",
) {
    var open by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    val density = LocalDensity.current
    val screenPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val edgePx = with(density) { POPUP_EDGE_AIR.toPx() }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { open = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chart_column),
            contentDescription = "上下文用量明细",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // **直接复用 MenuCard**（模型选择等七处同一个组件）：同一个 Popup、同一套卡片外框、
        // 同一套 110ms 进出场。这里需要显式定位，所以传 popupPositionProvider。
        // **provider 必须 remember**（优化清单 chat-P1）：原来每次重组都在组合体里 new 一个
        // 匿名对象 ⇒ `MenuCard` 的参数恒变，弹卡开着时每帧都重跑定位。
        val ringProvider = remember(density) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val gap = with(density) { 8.dp.roundToPx() }
                    val edge = with(density) { POPUP_EDGE_AIR.roundToPx() }
                    val maxLeft = (windowSize.width - edge - popupContentSize.width).coerceAtLeast(edge)
                    val x = anchorBounds.left.coerceIn(edge, maxLeft)
                    val maxTop = (windowSize.height - edge - popupContentSize.height).coerceAtLeast(edge)
                    val y = (anchorBounds.bottom + gap).coerceAtMost(maxTop)
                    return IntOffset(x, y)
                }
            }
        }
        MenuCard(
            visible = open,
            onDismiss = { open = false },
            // **显式定位**：锚点就是这个 28dp 的图表按钮，卡片顶边 = 按钮下沿 + 8dp（向下长），
            // 右缘 = 按钮右缘，横纵都留 16dp 边距。全程只用 Popup 给的 anchorBounds 与卡片实际
            // 尺寸，不依赖 cardWidth 之类的测量状态 ⇒ 打开后位置不会再变一次。
            alignment = Alignment.TopStart,
            offset = IntOffset.Zero,
            popupPositionProvider = ringProvider,
            focusable = false,
        ) {
            val live by statsFlow.collectAsStateWithLifecycle()
            Box {
                ContextRingCard(
                    used = used,
                    estimated = estimated,
                    window = window,
                    model = model,
                    sessionTokens = sessionTokens,
                    sessionEstimate = sessionEstimate,
                    sessionSplit = sessionSplit,
                    sessionTurns = sessionTurns,
                    sessionPerSecond = sessionPerSecond,
                    sessionCachePercent = sessionCachePercent,
                    sessionElapsedMs = sessionElapsedMs,
                    live = live,
                )
            }
        }
    }
}

/**
 * One label/value line of the ring's and the metrics' cards.
 *
 * The value is right-aligned on a shared column rather than set beside the label: a stack of
 * 「输入 83.6k / 缓存读取 82.4k / 未缓存输入 1.2k」 reads as a table when the
 * numbers line up on one edge, and as prose when each number starts wherever its label happened
 * to end. The card itself is given a floor width so that column has room to exist.
 */
@Composable
internal fun RingDetail(label: String, value: String) {
    Row(
        // Fills its parent, and the parent is sized to the widest row (see the cards): that is
        // what lines the numbers up. It deliberately does not ask for the screen's width — the
        // first attempt did, and a popup that should hug its content grew to the full screen.
        // 1.5dp top and bottom is the line spacing the user asked for: two rows meet with 3dp
        // between them, and a card's own 12-14dp of padding swallows the outer two halves.
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        // Centred, and it has to be said: the value is a size smaller than its label, so a
        // Top alignment would hang the number off the label's cap line.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            // A size under the label: the numbers are the detail of the line, and at the
            // label's own size they read as a second heading beside it. SemiBold, so that a
            // size down is still the heavier of the two: the number is what the line is for,
            // and the label carries it in the regular weight.
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            // A shade under the label's own tone: the numbers are the detail, and at full
            // onSurface they read heavier than the labels they belong to.
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 12.3k / 950 / 1.2M — the short form the context readouts use.
 *
 * Past a million the k form stops being readable (a long conversation's own tally runs to
 * hundreds of millions of tokens, and 「143798.2k」 says nothing); M is the unit a person
 * actually reads.
 */
internal fun shortTokens(tokens: Int): String = when {
    tokens < 1000 -> "$tokens"
    tokens < 1_000_000 -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
    else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
}

/**
 * The whole number, thousands separated: 「8,502,144 tok」.
 *
 * [shortTokens] answers "how big is this" at a glance, which is right for a window or a meter.
 * The three rows of the ring's split are read against a bill instead, so they are given in
 * full — a rounded figure cannot be checked against anything.
 */
internal fun fullTokens(tokens: Long): String = String.format(Locale.US, "%,d", tokens)

/** The three parts of a conversation's spend, as the endpoint itself reports them. */
data class SessionSplit(val uncached: Long, val cached: Long, val output: Long)

/**
 * Sums [SessionSplit] off the stored usage rows, the same rows [SessionStats] divides its hit
 * rate by, so the parts and the rate are two readings of one measurement.
 */
internal fun sessionSplitOf(messages: List<StoredMessage>): SessionSplit {
    // **一趟扫完**（原先是同一个 list 上三趟 sumOf）：逐条累加，语义与三次 sumOf 等价
    // （整数加法可交换，最后再 coerceAtLeast）。
    var uncached = 0L
    var cached = 0L
    var output = 0L
    for (m in messages) {
        uncached += m.promptTokens.toLong() - m.cachedTokens.toLong()
        cached += m.cachedTokens.toLong()
        output += m.completionTokens.toLong()
    }
    return SessionSplit(
        uncached = uncached.coerceAtLeast(0L),
        cached = cached,
        output = output,
    )
}

/**
 * The messages a compression record covers, or null when this transcript no longer holds
 * both ends of the span — a branch switch, or a conversation trimmed by hand.
 */
internal fun checkpointSpan(
    messages: List<StoredMessage>,
    checkpoint: ContextCheckpoint,
    positions: Map<String, Int>? = null,
): List<StoredMessage>? {
    val from = if (positions == null) messages.indexOfFirst { it.id == checkpoint.fromMessageId } else positions[checkpoint.fromMessageId] ?: -1
    val to = if (positions == null) messages.indexOfFirst { it.id == checkpoint.toMessageId } else positions[checkpoint.toMessageId] ?: -1
    if (from < 0 || to < from) return null
    return messages.subList(from, to + 1)
}

/**
 * One compression the engine made, drawn where it happened.
 *
 * A separator, not a bubble: it is not something either side said, it is a note about the
 * transcript itself. Folded it is a single line; opened it shows the summary the model
 * produced — bounded, because a summary can be long — and, on request, the messages it
 * replaced. Those originals are still on disk, which is the whole point of keeping a record:
 * the conversation is never actually lost, only folded.
 */
@Composable
internal fun CheckpointCard(checkpoint: ContextCheckpoint, originals: List<StoredMessage>) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    // *和思考块/工具块同一个折叠壳**（用户：「不要直勾勾显示在卡片下方，像工具块思考块一样
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
        title = "已压缩上下文 · ${formatTime(checkpoint.at)}",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        preview = "${originals.size} 条消息 → 摘要",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = checkpoint.summary,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
            )
        }
        Text(
            // 同表头口径：条数用转录计数（[originals]），与「查看原文」展开看到的一致；
            // checkpoint.messages 是 wire 计数，不展示。
            text = "压缩前约 ${shortTokens(checkpoint.promptTokens)} tokens · " +
                "${originals.size} 条消息 · ${formatTime(checkpoint.at)}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { showOriginal = !showOriginal },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (showOriginal) "收起原文" else "查看原文",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (showOriginal) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "original-header", contentType = "header") {
                    Text(
                        text = "被压缩的原文（${originals.size} 条）",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = scheme.onSurfaceVariant,
                    )
                }
                items(originals, key = { it.id }, contentType = { "original-message" }) { original ->
                    Column {
                        Text(
                            text = "${if (original.role == Roles.USER) "我" else "随行"} · " +
                                formatTime(original.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = original.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One queued line, above the composer.
 *
 * A shallow rounded bar rather than a bubble or a card: it is a line waiting its turn, and it
 * is inset from the pill (see [STEER_QUEUE_INSET]) so it reads as belonging to the field
 * below it without being part of it. The three things the user can still do to it are icons
 * on the right, in the order they are likely to want them. Only the newest three are drawn
 * (see [STEER_ROWS_SHOWN]) — a queue long enough to push the field off screen would be worse
 * than the count that replaces it.
 */
@Composable
internal fun SteerQueueRow(
    steer: PendingSteer,
    /** The run is over: this line will never be read unless the user sends it again. */
    undelivered: Boolean,
    /**
     * 排队 mode: this line is waiting to be the **next turn**, not an interruption of the one in
     * flight. The row is otherwise identical — same three actions — but it has to say which of the
     * two it is, or 「立即插话」 reads as a promise the queue does not make.
     */
    nextTurn: Boolean = false,
    onPromote: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A clock, not a speech mark: the line is waiting, and that is the whole point of
            // the bar.
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_clock),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            // A line that is only a picture has no text to show, and an empty bar leaves the user
            // unable to tell what is waiting — so it says what it is.
            val label = steer.text.ifBlank { if (steer.imagesBase64.isNotEmpty()) "（图片）" else steer.text }
            // The picture itself, at the size this 36dp bar can hold. Decoded here rather than
            // through ImageBlock: that one is built for a bubble (its own size plus the full-screen
            // preview dialog), so it would be a second layout path, not a reuse. 多附件只画第一张
            // 缩略图 + 计数，一排 24dp 的格子装不下也不该装下整列。
            val firstImage = steer.imagesBase64.firstOrNull()
            var thumb by remember(firstImage) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(firstImage) {
                thumb = firstImage?.let { decodeAttachment(it, target = THUMB_PX)?.asImageBitmap() }
            }
            // Tapping the thumbnail opens the picture at full size. The decode happens on the tap,
            // not in the row: the 24dp copy above is downsampled to [THUMB_PX] and blowing it up
            // would show exactly the blur the user is trying to avoid, so the base64 is decoded
            // again — on IO — at a size worth looking at.
            val rowScope = rememberCoroutineScope()
            val rowContext = LocalContext.current
            var fullPicture by remember(firstImage) { mutableStateOf<ImageBitmap?>(null) }
            thumb?.let { picture ->
                Image(
                    bitmap = picture,
                    contentDescription = "查看图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) {
                            val base64 = firstImage ?: return@clickable
                            rowScope.launch {
                                val decoded = decodeAttachment(base64, target = FULL_PREVIEW_PX)
                                if (decoded == null) {
                                    Toast.makeText(rowContext, "这张图片打不开", Toast.LENGTH_SHORT).show()
                                } else {
                                    fullPicture = decoded.asImageBitmap()
                                }
                            }
                        },
                )
                Spacer(Modifier.width(8.dp))
            }
            fullPicture?.let { picture ->
                ImagePreviewDialog(
                    bitmap = picture,
                    contentDescription = "排队中的图片",
                    // 与**对话里的图片预览**一致（用户：我要跟对话那样的图片全屏预览）：
                    // keepKeyboard = false ⇒ 走 Dialog，系统遮罩 + 与气泡里那张同一套交互。
                    keepKeyboard = false,
                ) { fullPicture = null }
            }
            Text(
                text = if (undelivered) "$label（未送达）" else label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 排队 rows say what they are: the line has not reached the model at all, so calling
            // it an interruption would be a lie, and 「立即插入」 is the deliberate way to make it
            // one. An undelivered steer has no round left to interrupt, so its action starts a new
            // turn instead and says so.
            QueueAction(
                icon = R.drawable.ic_arrow_up,
                label = when {
                    nextTurn -> "立即插入"
                    undelivered -> "发送"
                    else -> "立即插话"
                },
                onClick = onPromote,
            )
            QueueAction(icon = R.drawable.ic_square_pen, label = "编辑", onClick = onEdit)
            QueueAction(icon = R.drawable.ic_trash, label = "删除", onClick = onDelete)
        }
    }
}

/**
 * One line of the agent's board, above the composer.
 *
 * The same body as [SteerQueueRow] on purpose — 36dp, the same radius and container — because
 * the two stacks sit one above the other and a different shape would read as a different app's
 * card. What differs is the content: the glyph is the item's status (a tick once it is done,
 * the list glyph while it is still ahead), the row being worked on is tinted with the brand
 * colour, and a finished line is dimmed rather than removed mid-drag of the eye.
 *
 * [tally] is 「已完成/总数」, drawn on the board's first row alone.
 */
@Composable
internal fun TodoQueueRow(
    item: TodoItem,
    tally: String?,
    /** 点这一行看完整清单；null 时不可点（只作展示用）。 */
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val done = item.status == TODO_COMPLETED
    val active = item.status == TODO_IN_PROGRESS
    val ink = when {
        active -> scheme.primary
        done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> scheme.onSurfaceVariant
    }
    // Surface 的 onClick 重载：可点时它自己带 ripple 与语义，不用另加 clickable。
    val shape = RoundedCornerShape(14.dp)
    val modifier = Modifier
        .fillMaxWidth()
        .height(36.dp)
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(
                    if (done) R.drawable.ic_circle_check else R.drawable.ic_list_checks,
                ),
                contentDescription = null,
                tint = if (active) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.subject,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tally != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tally,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    if (onClick == null) {
        Surface(shape = shape, color = scheme.surfaceContainerHigh, modifier = modifier) { body() }
    } else {
        // 可点：整行就是「看完整清单」的入口，形状/颜色/高度与不可点时完全一致。
        Surface(
            onClick = onClick,
            shape = shape,
            color = scheme.surfaceContainerHigh,
            modifier = modifier,
        ) { body() }
    }
}

/**
 * 卡片底部的一行原因：中断、上游截断、失败。
 *
 * 属于这一轮的回答，所以跟着卡片走 —— 用户看到「连接中断」时，它就该长在那条半截回答的下面，
 * 而不是屏幕顶上一条与内容无关的浮层。样式用卡片里既有的一档小字（labelSmall）加错误色，
 * 不再新造一种提示形态。
 */
@Composable
internal fun RunReasonLine(reason: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 6.dp, end = 4.dp),
        // 垂直居中而不是顶对齐：顶对齐 + 图标自己再 padding(top = 1dp) 会把 14dp 的图标
        // 压成 12dp 并整条偏上 —— 用户看到的「错误提示和 icon 没有对齐」。
        // 居中之后图标、文字行、右侧复制按钮在同一条中线上。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
            modifier = Modifier.weight(1f),
        )
        // 错误提醒可复制（用户：「错误提醒怎么还不能复制」）：排查要的就是这段原文。
        val clip = LocalClipboardManager.current
        IconButton(
            onClick = { clip.setText(AnnotatedString(reason)) },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                contentDescription = "复制错误提醒",
                tint = scheme.error,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 失败回执：run 最终失败时 VM 落盘的一条 system 消息画成的红色小卡。
 *
 * 与 [RunReasonLine] 的分工：那条是**进行中/刚结束**时挂在最后一张卡片里的小红字（会随横幅
 * 计时消失）；这一条是**留在会话记录里**的那一份 —— 用户原话「如果最终确实是失败了，需要把
 * 失败的提示类似气泡那样作为回复」。全宽、可换行、不截断，重开会话还在。
 */
@Composable
internal fun FailureReceiptRow(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.errorContainer.copy(alpha = 0.5f),
    ) {
        // 图标要与**正文第一行的中线**对齐（用户：「我要你让icon和正文第一行对齐」）。
        // 图标 14dp、正文行高比它高 ⇒ 直接顶对齐会让图标整体偏上；写死一个上边距也只是
        // 让两枚图标互相对齐（上一版就错在这），跟第一行的中线还是差一截。
        // 这里按行高算：图标中心 = 行高的一半 = 第一行的中线。
        val bodySmall = MaterialTheme.typography.bodySmall
        val density = LocalDensity.current
        val lineHeight = with(density) { bodySmall.lineHeight.toDp() }
        // **对准字形的墨迹中心，不是行框中心**（用户：「视觉上没有对齐中心啊」）。
        // 行框（lineHeight）比 em 框高，中文字形只占 em 框的 ~86% 且坐在基线上 ⇒
        // 墨迹中心比行框中心低约 0.06em。真机截图量过（12sp 正文）：差 ~1.5px ≈ 0.9dp，
        // 两枚图标彼此是齐的（都是 51.0），只是都比第一行高。
        // 用 em 的比例算（而不是写死 dp），字体缩放时依然对得上。
        val inkShift = with(density) { (bodySmall.fontSize.value * 0.06f).sp.toDp() }
        val iconTop = ((lineHeight - 14.dp) / 2 + inkShift).coerceAtLeast(0.dp)
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(14.dp).padding(top = iconTop),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            // 同 [RunReasonLine]：回执也要能复制（排查要原文）。
            //
            // **图标与左边那枚同高**（用户：「这两个icon要跟第一行垂直居中啊，你这样都没有对齐」）：
            // IconButton 会把图标在 26dp 的方框里**居中**，于是它比左边的告警图标低了 6dp ——
            // 文字换行成两行时，两枚图标更不在同一条中线上。改成 26dp 的可点区域 + 顶部对齐，
            // 图标自己补 1dp 上边距（与左边那枚一致，正文第一行的中线就在那儿）。
            val clip = LocalClipboardManager.current
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clickable { clip.setText(AnnotatedString(text)) },
                contentAlignment = Alignment.TopCenter,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                    contentDescription = "复制错误提醒",
                    tint = scheme.error,
                    // 同一个 iconTop ⇒ 与左边那枚完全同高，且都在第一行的中线上。
                    modifier = Modifier.size(14.dp).padding(top = iconTop),
                )
            }
        }
    }
}






/**
 * The glyph for an approval mode, from the drawables the app already ships.
 *
 * A tick when tools run on their own, a person-with-shield while every call is put to the user,
 * an alert shield when they are refused. The icon is the state, which is what makes the row's
 * button readable without opening it.
 */
internal fun approvalIcon(mode: ApprovalMode): Int = when (mode) {
    ApprovalMode.AUTO -> R.drawable.ic_circle_check
    ApprovalMode.ASK -> R.drawable.ic_shield_user
    ApprovalMode.DENY -> R.drawable.ic_shield_alert
}

/** The reading for the four sources above; 0 means there is nothing to draw at all. */

/** True while the last item is on screen — the state that lets a reply auto-scroll. */
internal fun LazyListState.atEnd(slack: Int = 24): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + slack
}

/**
 * 抬手后守护窗还开多久。选择手柄在抬手那一刻才出现、框架的 bringIntoView 跟着落在抬手后，
 * 400ms 够盖住它那次程序滚动，又短到不会跟用户随后的正常滚动打架。
 */
internal val LONG_PRESS_SETTLE_MS = 400L

/**
 * The air the composer leaves under its pill, in dp.
 *
 * Named because two places have to agree on it: the composer's own bottom padding, and the room
 * the transcript leaves for the stack (the caller's stackFootprint). They were a literal 12.dp
 * in one of them and a measured number in the other, which is how the two drifted a frame apart.
 */
internal val COMPOSER_BOTTOM_PAD = 12.dp

/**
 * The size of a viewport change that is a jump rather than a frame of an animation.
 *
 * The platform animates the IME inset frame by frame where it can — deltas of a few pixels, and
 * the list follows them one for one, which is what makes the motion match the keyboard. A ROM
 * that hands the whole inset over at once produces one delta of several hundred pixels instead,
 * and that is the one worth animating.
 */
internal const val KEYBOARD_JUMP_PX = 40f

/** Matches the platform's own keyboard animation, so the two read as one motion. */
internal const val KEYBOARD_ANIM_MS = 220

/** How many queued lines are drawn before the rest collapse into one count. */
internal const val STEER_ROWS_SHOWN = 3






















/** How far a queued line is inset from the pill on each side, so it reads as narrower. */
internal val STEER_QUEUE_INSET = 12.dp

/**
 * Where the context went.
 *
 * The three shares come from the engine's own estimate for the run in flight; a stored
 * reply has no such numbers, so for it the dialog shows only the header — better a short
 * dialog than three invented rows.
 */
@Composable
internal fun ContextBreakdownDialog(
    tokens: Int,
    window: Int,
    live: StreamStats?,
    onDismiss: () -> Unit,
) {
    val percent = if (window > 0) tokens * 100 / window else 0
    SuixingDialog(
        onDismissRequest = onDismiss,
        confirmButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("知道了") } },
        title = { Text(if (window > 0) "上下文已用 $percent%" else "上下文用量") },
        text = {
            Column {
                Text(
                    text = if (window > 0) {
                        "~${tokens / 1000}k / ${window / 1000}k"
                    } else {
                        "~${tokens / 1000}k"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (window > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                live?.let { stats ->
                    val parts = listOf(
                        Triple("系统提示词", stats.breakSystem, MaterialTheme.colorScheme.primary),
                        Triple("工具定义", stats.breakTools, MaterialTheme.colorScheme.secondary),
                        Triple("对话消息", stats.breakHistory, MaterialTheme.colorScheme.tertiary),
                    )
                    // Shares are measured against their own sum, not against the endpoint's
                    // prompt count: the engine's estimate covers messages and tools, and
                    // forcing it onto that other total would invent a remainder.
                    val total = parts.sumOf { it.second }
                    Spacer(Modifier.height(14.dp))
                    parts.forEach { (label, value, tone) ->
                        ContextBreakdownRow(label, value, total, tone)
                    }
                }
            }
        },
    )
}

@Composable
internal fun ContextBreakdownRow(label: String, value: Int, total: Int, tone: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tone),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "~${value / 1000}k",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(10.dp))
        // One bar per row, sized by its share of the three.
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val fraction = if (total > 0) value.toFloat() / total else 0f
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(tone),
            )
        }
    }
}
