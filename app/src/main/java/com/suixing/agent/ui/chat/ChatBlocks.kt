package com.suixing.agent.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.voice.Dictation
import com.suixing.agent.voice.DictationRecorder
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.forAssistant
import com.suixing.agent.data.groupModels
import com.suixing.agent.data.label
import com.suixing.agent.data.modelOffers
import com.suixing.agent.data.choiceFor
import com.suixing.agent.ui.assistant.AssistantAvatar
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.DrawerButton
import com.suixing.agent.ui.components.FlatIconButton
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.LocalPathClick
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MarkdownText
import com.suixing.agent.ui.components.PressPill
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.components.markdownBody
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Composable
internal fun BranchArrow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = scheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * How wide a process block's own name may get before it starts yielding.
 *
 * A name is short by nature — 「思考过程」 is four characters, `run_shell` nine — so it is never
 * given a share of the row to fill: it takes what it needs and hands the rest to the status and
 * the preview beside it. The cap only stops a long MCP name from eating the whole header.
 */
internal val PROCESS_TITLE_MAX = 160.dp

/**
 * 折叠块展开/收起的高度动画时长。
 *
 * 180 → 220（用户：「慢一点点，不要太生硬即可」）：180ms 对整块卡片来说收得太急，末尾像"啪"地
 * 停住；220ms 仍明显短于长按守护窗 [LONG_PRESS_SETTLE_MS]（400ms），所以短按点折叠条时
 * 整段动画依然落在「抬手即放行追底」之前（见 ChatScreen 的手势段注释）。
 */
internal const val PROCESS_EXPAND_MS = 220

/**
 * 折叠块展开/收起的缓动曲线（0.8.297）。
 *
 * 原来用 `LinearOutSlowInEasing`（= `CubicBezierEasing(0, 0, 0.2, 1)`）：它在 t=0 的斜率约 5，
 * 动画**第一帧**就已经走到 12% ⇒ 展开时下方内容一帧内被推开整块高度的 12%（540px 的块 = 65px
 * 一跳），收起时同理 —— 看起来就是「弹一下」。
 *
 * 换成 M3 的 standard 曲线（`CubicBezierEasing(0.2, 0, 0, 1)`）：起点斜率为 0，第一帧只走 1.2%
 * （540px 的块 = 6px），展开是「慢慢长出来」、收尾也是慢慢合上，中间快 —— 头尾都不再跳。
 */
internal val REVEAL_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 主转录的滚动状态：折叠块展开/收起时，若**不在底部**，要按高度增量反向补偿，
 * 才能让块顶边钉住、内容向下展开（见 [RevealHeight]）。
 */
internal val LocalChatListState = compositionLocalOf<androidx.compose.foundation.lazy.LazyListState?> { null }

/**
 * 外观 → 打字机效果（默认开）。只落在**两个**流式渲染点上：正文的 Markdown 与思考块。
 * 用 composition local 而不是逐层传参，是因为这两点离设置有好几层，而参数要穿过折叠卡片、
 * 工具块、轮次渲染器。
 */
internal val LocalTypewriter = compositionLocalOf { true }

/**
 * 卡片级可点块的按下反馈：**整块背景压暗**，不用涟漪。
 *
 * M3 的 `ripple()` 是 bounded 的：半径＝触摸点到本节点**最远角**的距离。放在一个又高又宽的卡片上，
 * 它画出来就是一个大圆（被圆角裁掉一半仍是个圆），与「这一块被按下了」的预期完全不是一回事 ——
 * 用户看到的就是「为什么是个圆形」。这里换成整块 8% 的 onSurface 叠色，形状与可见圆角一致，
 * 松手即恢复。
 *
 * 只给**卡片级**的可点块用（工具行、折叠卡片的表头、检查点卡）；单行小按钮（图标按钮、菜单行、
 * 26dp 的圆形动作）保留 M3 原生涟漪，那才是涟漪本来该有的样子。
 *
 * 没有复用 `suixingClickable`：它的两个重载都把 `indication = ripple()` 写死了，没有留出
 * 「不要涟漪」的入口。
 */
@Composable
internal fun Modifier.blockPress(shape: Shape, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .clip(shape)
        .background(
            if (pressed) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            } else {
                Color.Transparent
            },
        )
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * 「外层流式行正在做滞后补偿」——为 true 时，里面的 [RevealHeight] **不再自己补滚动**。
 *
 * 为什么需要它（issue #17，用户实测「读历史过程中还是有抖动，貌似是思考块流式后再折叠导致的」）：
 * 流式行里的思考块/工具块走 RevealHeight，它有自己的逐帧补偿；而流式行外层又会量到同一次
 * 高度变化并补一次 ⇒ **同一份增量被补两次** ⇒ 过冲/回弹。
 * 两处只能有一处补：外层滞后补偿生效时由外层统一补，RevealHeight 只负责动高度。
 * 贴底（外层不补）时这个 flag 为 false，RevealHeight 行为不变。
 */
internal val LocalLiveRowLag = compositionLocalOf { false }

/**
 * 折叠块的展开/收起：**纯高度动画**。
 *
 * 不用 `AnimatedVisibility(expandVertically)` 是因为这里要按内容自然高度逐帧写 progress，
 * 且展开与收起共用一套裁剪。滚动不在这里做 —— 转录是底锚（reverseLayout），index 0 的
 * 卡片长高天然向上、底边不动；旧版在这里逐帧 `dispatchRawDelta` 同步列表，两处抢同一份
 * 列表才是"先向下、再向上"回弹的来源，已随底锚一起删掉。
 *
 * 展开/收起一律有动画（0.8.319 起不再按「是否在读历史」区分：那个 signal 的唯一
 * 作用变成了「上翻时把正在跑的动画打断重启」，已删除）。
 */
/**
 * ③ **收尾按实测位移对账**：逐帧补偿用的是推算高度，遇到外层约束（钳位、`weight`、嵌套滚动）
 * 就会多补。这里用 `positionInRoot` 量出来的真实位移补差值：逐帧补偿正确时它是空操作，
 * 有偏差时它正好是那份差值。
 */
private fun reconcileRevealTop(
    outer: androidx.compose.foundation.lazy.LazyListState?,
    before: Float,
    after: Float,
) {
    if (outer == null || !outer.canScrollBackward) return
    if (before.isNaN() || after.isNaN()) return
    val drift = after - before
    if (abs(drift) > 1f) outer.dispatchRawDelta(-drift)
}

@Composable
private fun RevealHeight(
    visible: Boolean,
    durationMs: Int,
    content: @Composable () -> Unit,
) {
    // 外层流式行正在做滞后补偿时，这里只动高度、不碰滚动（见 [LocalLiveRowLag]）。
    // **必须经 `rememberUpdatedState` 读**（评审 M1）：帧循环所在的 `LaunchedEffect(visible)`
    // 只以 `visible` 为 key，局部 `val` 变了**不会重启协程** ⇒ 读到的是动画开始那一刻的值。
    // 后果：钉住期间（外层已把高度钉死、补偿必须为 0）它仍是旧值 false ⇒ 帧循环继续
    // `dispatchRawDelta(comp)`，把内容按剩余补偿量推走 —— 正是 #17 要治的那份漂移。
    val suppressOuterCompensation by rememberUpdatedState(LocalLiveRowLag.current)
    var progress by remember { mutableFloatStateOf(if (visible) 1f else 0f) }
    // 内容的自然高度（px）。普通数组而不是 State：只在动画循环里读，不参与组合。
    val natural = remember { intArrayOf(0) }
    val outer = LocalChatListState.current
    // **这个块在根坐标系里的 y**（issue #22）：补偿量不再靠"推算长了多少"，而是量
    // "动画前后这个块实际移动了多少"。任何布局约束（`heightIn(max)`、嵌套滚动、`weight`）
    // 让实际高度 ≠ 自然高度时，推算都会错，测量不会。
    //
    // 用普通数组而不是 State：它**每帧**写一次，State 每写一次都要发一次快照通知
    // （TODO #29 列的嫌疑项之一）。数组读出来就是最新值，语义与 State 一致。
    val topInRoot = remember { floatArrayOf(Float.NaN) }

    LaunchedEffect(visible) {
        val target = if (visible) 1f else 0f
        val start = progress
        if (start == target) return@LaunchedEffect
        // 展开的第一帧还没量过内容：先让出一帧让 Layout 量一次，再决定怎么动。
        var px = natural[0]
        if (px <= 0) {
            withFrameNanos { }
            px = natural[0]
        }
        // **一律有动画**（用户：「折叠展开一会有一会没有」）：原来是
        // `if (pinned) 0 else durationMs` —— 贴底时有动画，往上翻过之后变成瞬间展开，
        // 于是同一个动作时有时无。位置稳定交给 ChatScroll 的阅读锚点，不再靠"取消动画"。
        val dur = durationMs
        if (dur <= 0 || px <= 0) {
            progress = target
            return@LaunchedEffect
        }
        // ① 先让一帧落地，记下动画开始前的真实位置。
        withFrameNanos { }
        val beforeTop = topInRoot[0]
        // ② 高度动画 + 逐帧补偿，**恢复成原来的 `px * progress` 增量式**。
        //
        // 上一版改成"逐帧按实测位移补"，结果第一层也偏了 —— 原因有两个：
        //  · 丢了 `canScrollBackward` 这个门：贴底时（底锚天然钉住底边）本来就不该补，
        //    现在照补，于是第一层一展开就把画面推下去；
        //  · `topInRoot` 是 `onGloballyPositioned` 在**布局之后**写的，动画循环里读到的是
        //    上一帧的值，补偿永远慢一拍，形成"补一点、位置又变、再补"的回路。
        // 所以逐帧回到原来的写法（对第一层已验证有效），把"跟实际位移的偏差"交给 ③ 的收尾对账。
        val begin = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - begin) / 1_000_000f / dur).coerceIn(0f, 1f)
            val oldH = px * progress
            progress = start + (target - start) * REVEAL_EASING.transform(t)
            // **这一帧该补多少**：必须和高度变化落在**同一帧**，晚一拍就一直在抖。
            //
            // 真机埋点（0.8.295，153 帧）实测：补偿晚一帧时，块顶边每帧正好偏「一帧的高度增量」
            // （5~75px），最后靠收尾对账拉回来 ⇒ 观感就是「展开/收起时位置一直在变，最后位置是对的」。
            //
            // 补偿量 = 这个块自己的高度增量（0.8.298 起**所有层**都这样）。
            //
            // 以前嵌套层要另外算「外层容器的高度增量」，是因为工具条那个容器**有高度上限**：
            // 容器顶到上限后不再跟着内容长，块自己长高 ≠ 容器长高，差多少得看钳位
            // （真机实测 0.8.296：跨上限那一帧差 6~7px ⇒ 顶边跳一下）。
            // 现在工具条的外层改成**普通 Column、没有上限**（用户：工具条不该是虚拟列表，
            // 也不该被截断）⇒ 容器高度 == 内容高度 ⇒ 容器长高 == 这个块长高 ⇒ 一套算法通吃。
            val growth = px * progress - oldH
            val comp = growth
            // 外层流式行在补时这里绝不能补（否则同一份增量补两次 ⇒ 抖动）。
            if (!suppressOuterCompensation && comp != 0f && outer != null && outer.canScrollBackward) {
                outer.dispatchRawDelta(comp)
            }
            if (t >= 1f) break
        }
        // ③ 收尾按实测位移对账。
        withFrameNanos { }
        withFrameNanos { }
        if (!suppressOuterCompensation) {
            reconcileRevealTop(outer = outer, before = beforeTop, after = topInRoot[0])
        }
    }

    Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            // 高度在动画中间时内容会超出自己的框，裁掉，否则它会画到下一行上。
            // **0.8.285 回退**：0.8.277~0.8.284 曾改用 `graphicsLayer { clip = … }`（为了少一层
            // saveLayer），但实测展开/收起会出现残影；这里回到最初的 `clipToBounds()`。
            .clipToBounds()
            .onGloballyPositioned { coords -> topInRoot[0] = coords.positionInRoot().y },
    ) { measurables, constraints ->
        // 收起且没有动画时不量：一列收起的长思维链没有理由每次都测一遍。
        if (!visible && progress <= 0f) {
            layout(constraints.maxWidth, 0) { }
        } else {
            val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
            // **注意：不能用 `constraints.maxHeight` 钳**（issue #24 情况 A 在这里不成立）。
            // `LazyColumn` 测量子项时，滚动方向给的是 `Constraints.Infinity` —— 两层 RevealHeight
            // 都嵌在 LazyColumn 的 item 里，所以拿到的 maxHeight 都是无穷大，钳了等于没钳（实测
            // 加过这行、是空操作，已撤回）。
            natural[0] = placeable.height
            val h = (placeable.height * progress).roundToInt().coerceIn(0, placeable.height)
            layout(constraints.maxWidth, h) { placeable.place(0, 0) }
        }
    }
}

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
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** First line of the folded content, shown after the meta while the card is closed. */
    preview: String? = null,
    /** Drawn right after [preview]: the streaming cursor for a folded think. */
    previewTail: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // *折叠条切换后视口不动** —— 不在这里做任何事：这一项变高变矮会把上方内容推走，而 ChatScreen
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Exactly 36dp folded: the fourth time the user has nailed this
                // card's height down, so it is a fixed height, not a minimum —
                // nothing in the row wraps (every text is maxLines 1).
                .height(36.dp)
                // 整块按下反馈（见 blockPress）：这一行是折叠条的入口，涟漪是一颗小圆角矩形。
                .blockPress(RoundedCornerShape(8.dp)) {
                    onToggle()
                }
                .padding(horizontal = 0.dp),
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
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The name takes what it needs, not half the row. `weight(1f, fill = false)`
                    // used to hand it a share of the width and keep it even when the name was
                    // short: the leftover sat right after 「思考过程」 as a band of nothing, and a
                    // name as short as `run_shell` could come back cut in half inside it. With a
                    // preview beside it the name yields at [PROCESS_TITLE_MAX]; without one it
                    // still holds the row, which is what lets the summary card's long header
                    // ellipsize instead of the chip after it.
                    modifier = if (preview != null) {
                        Modifier.widthIn(max = PROCESS_TITLE_MAX)
                    } else {
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
                        style = metaStyle(),
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
                Icon(
                    imageVector = if (expanded) {
                        ImageVector.vectorResource(R.drawable.ic_chevron_up)
                    } else {
                        ImageVector.vectorResource(R.drawable.ic_chevron_down)
                    },
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // 展开体：高度动画由 [RevealHeight] 驱动（滚动交给底锚，不再逐帧同步列表）。
            // 前两版都没成：只用 expandVertically + 追底＝两套逐帧动画，谁先谁后不确定，总会
            // 露出一帧位移；干脆不做高度动画＝不闪，但"啪"地出现，生硬（用户：「还是会闪一下
            // ……动画也生硬」）。这一版只做高度：逐帧把进度写进 progress，卡片底边由底锚
            // 钉住，长高只向上。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                Column {
                    // Header and content are one card; the hairline is their only seam.
                    HorizontalDivider(color = scheme.outlineVariant)
                    // The grid the expanded rows share: 12dp of side air, 8dp above and
                    // below, and every row inside lines its own column up to this edge.
                    Column(
                        // 水平 0dp（用户：「展开时原样展开」）：运行时那些块是顶到卡片左右边缘的，
                        // 12dp 内边距让展开后的内容整体变窄 —— 就是「又窄又短」的那一半。
                        // **上方 0dp**（用户：「折叠条分割线下方还有一处空白，不能去掉吗」）：
                        // 那 8dp 就是分割线下面那道空白，所有折叠块共用这一处，所以在这里一次去掉。
                        // 下方仍留 8dp：展开内容与卡片下沿之间需要一点收尾空气。
                        Modifier.padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 6.dp),
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
@Composable
internal fun metaStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(
    fontSize = MaterialTheme.typography.labelMedium.fontSize * (12f / 13f),
)

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
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
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
internal fun CompressionBlock(checkpoint: ContextCheckpoint) {
    var expanded by remember { mutableStateOf(false) }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
        title = "已压缩上下文",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        preview = "${checkpoint.messages} 条消息 → 摘要 · 压缩前约 ${shortTokens(checkpoint.promptTokens)}",
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
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Which thinking blocks the user has opened or closed, for the conversation on screen.
 *
 * A block's state cannot live inside the block: the live think and the stored one it turns
 * into are two different call sites, so a `remember` there started from scratch the moment
 * the reply was saved (and with 思考过程默认展开 on, that meant a think never folded). The
 * conversation is part of every key, so two chats never share a block's switch.
 */
@Stable
internal class ReasoningExpansion(
    private val conversationId: String,
    private val entries: MutableMap<String, Boolean>,
) {
    /** Null while this block has no opinion of its own. */
    fun stored(blockId: String): Boolean? = entries["$conversationId:$blockId"]

    fun set(blockId: String, expanded: Boolean) {
        entries["$conversationId:$blockId"] = expanded
    }
}

/**
 * Which slot a thinking block occupies in the screen's table of open blocks.
 *
 * The key is the run that produced the think plus how many thinks that run had already
 * produced — not the composable's position, and not the message id, because neither of
 * those is shared by the live block and the stored message that replaces it. The run id is:
 * the engine stamps it on the message it saves, so the same think answers to the same key
 * on both sides of the hand-over. [fallback] covers a message stored before runs were keyed.
 */
internal fun thinkSlot(parts: List<Part>, index: Int, fallback: String): String {
    val part = parts.getOrNull(index) as? Part.Thinking ?: return index.toString()
    val before = parts.take(index).count { it is Part.Thinking && it.runId == part.runId }
    return "${part.runId.ifEmpty { fallback }}:$before"
}

/**
 * The model's thinking, folded into the reply's own card.
 *
 * It is drawn in its own [LazyColumn] so a 10k-character chain of thought only ever
 * composes the handful of lines on screen — laying the whole string out as one [Text] is
 * what makes a long think stutter. Whether it is open is not remembered here: it is a row
 * of the screen's [ReasoningExpansion] table, so the choice outlives this composable and
 * survives the hand-over from the live row to the stored message.
 */
@Composable
internal fun ReasoningBlock(
    text: String,
    streaming: Boolean,
    /** This block's slot in the screen's table: see [thinkSlot]. */
    blockId: String,
    expansion: ReasoningExpansion,
    reasoningTokens: Int = 0,
    elapsedMs: Long = 0,
    /**
     * 外观 → 历史思考块默认展开 ([AppSettings.expandReasoning]): what a block does when the
     * user has never touched its header. A block that is still arriving is always open — it is
     * the reason to run a thinking model, and watching it arrive is the point. One that has
     * settled opens only if this is on: with it off, the transcript stays a transcript, and the
     * header tap is the way into any think the reader wants in full.
     */
    expandReasoning: Boolean = true,
    /**
     * Inside an expanded summary card the block is a row of the card's grid, not a
     * card of its own: no surface, no second header height — just the icon slot, the
     * title and the chain, lined up with the tool rows beside it.
     */
    nested: Boolean = false,
    /** How tall the chain's inner scroll may grow; the appearance page owns it. */
    maxHeight: Dp = 180.dp,
    /**
     * 外观 → 思考块跟随最新: whether the chain's own scroll chases its tail while the think
     * streams. A parameter rather than a composition local because this is the one value the
     * nested rows already carry [maxHeight] for, and a second hidden channel for a second
     * boolean is how two sources of the same truth start to disagree. Defaults to true, which
     * is what the block did before the switch existed.
     */
    followReasoning: Boolean = true,
) {
    // Open unless the user closed it. Both halves of the report are answered here: a block used
    // to fold itself the moment its answer landed, and — past 200 lines — to draw only its first
    // 200 with a 「展开全部 N 行」 button, so the chain being read was replaced by a control the
    // moment it grew. The chain is now drawn whole (its height cap below is the bound, not its
    // length), and the only thing that closes a block is a tap on its own header — recorded in
    // the expansion table, which outlives the hand-over from the live row to the stored message.
    //
    // What a block nobody has touched does is the one thing the appearance setting still decides:
    // a think that is still arriving is open (watching it arrive is why the model was asked to
    // think), and one that has settled follows 历史思考块默认展开. Off, a finished think folds to
    // its header — the reader's tap is the way in, and the table remembers it per conversation.
    val expanded = expansion.stored(blockId) ?: (streaming || expandReasoning)
    val lines = remember(text) { text.split('\n') }
    val meta = buildList {
        if (reasoningTokens > 0) add("$reasoningTokens tokens")
        if (elapsedMs > 0) add(formatDuration(elapsedMs))
    }
    // The folded header says what the think starts with, so a closed block is still
    // readable; a streaming block adds the cursor right after that line.
    // 光标挂在最新的一条**非空**行上：末行是空行时顺延，避免光标消失一帧。
    // **在 items 外算一次**（原来每个可见项都重算一遍 Sequence+Iterator）。
    //
    // **必须是「视觉行号」**（0 = 最新行，因为底锚 reverseLayout 下 `items` 的 index 就是视觉号）。
    // 绝对行号（`indexOfLast`）与它差一个 `lastIndex - i` 的换算，混用会让光标挂到最旧那一行 ——
    // 长的思考链里甚至直接移出视口（真机症状：「光标没有跟在思考链后面」）。
    // 全空行时 `indexOfLast` 为 -1 ⇒ cursorAt = lastIndex + 1，永远不会等于任何 index（正确）。
    val cursorAt = remember(lines) { lines.lastIndex - lines.indexOfLast { it.isNotBlank() } }
    val firstLine = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    // The chain itself, shared by both shapes: a think with no text at all (the
    // endpoint reported time but no chain) must not render as an empty slab, and a
    // monster chain stays bounded at 320dp of lazy list that follows its tail while
    // the think streams.
    val chain: @Composable () -> Unit = {
        if (lines.none { it.isNotBlank() }) {
            Text(
                text = "（本段无思考文本）",
                style = thinkingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // *底锚倒序**（与主转录同一套画法）：index 0 是视觉最底，贴尾＝scrollToItem(0)。
            val inner = rememberLazyListState()
            // 追尾开关：手指往上拖就停跟，真滚到底再跟回来。底锚下判据是 canScrollBackward ——
            // 顶锚时内容一长高 canScrollForward 就变真，追一次就再也不追（用户：「思维链的追底呢」）。
            var innerFollow by remember { mutableStateOf(true) }
            var innerWasScrolling by remember { mutableStateOf(false) }
            LaunchedEffect(inner) {
                snapshotFlow { inner.isScrollInProgress to inner.canScrollBackward }
                    .collect { (scrolling, canBack) ->
                        if (!canBack) {
                            innerFollow = true
                        } else if (innerWasScrolling && !scrolling) {
                            // 一次滚动结束在非底部 ⇒ 用户拖上去的（程序化贴底只会停在底部）。
                            innerFollow = false
                        }
                        innerWasScrolling = scrolling
                    }
            }
            // 追尾：底锚下最新一行长高向上、底边不动，不逐帧滚；只有新行插在最前把锚点
            // 顶离 (0, 0) 时才补一次。
            LaunchedEffect(inner, expanded, followReasoning) {
                if (!expanded || !followReasoning) return@LaunchedEffect
                snapshotFlow {
                    val offBottom =
                        inner.firstVisibleItemIndex != 0 || inner.firstVisibleItemScrollOffset != 0
                    offBottom to innerFollow
                }.collect { (offBottom, follow) ->
                    if (follow && offBottom) inner.scrollToItem(0)
                }
            }
            LazyColumn(
                state = inner,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // Configurable in 外观: a chain of thought is context, and the
                    // answer has to stay on screen while it is being read.
                    .heightIn(max = maxHeight),
                // 首行上边距放在**列表内部**（contentPadding），不是容器上：容器内边距在滚动区
                // 外面，滑到哪它都留在那儿 —— 用户说的「列表滑下去那个空白条还在」就是它。
                // contentPadding 属于可滚动内容，第一行滚上去时这段空白一起滚走。
                contentPadding = PaddingValues(top = BLOCK_CONTENT_TOP),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    count = lines.size,
                    // **绝对行号当 key**（性能）：思考链是打字机 25 Hz 逐字放出来的，每冒出一个
                    // 换行 `lines` 就多一行；底锚下 index 0 是最新行 ⇒ **所有位置的"视觉第 i 行"
                    // 都换成上一行**。用位置键时可见的十几行全部重组 + 重新测量文本；
                    // 用绝对行号后已有行的身份不变，只有新行和光标行重组。
                    key = { index -> lines.lastIndex - index },
                ) { index ->
                    // 底锚：index 0 是**最新一行**（视觉最底），越往上 index 越大。
                    val line = lines[lines.lastIndex - index]
                    if (line.isBlank()) {
                        // 视觉首行（倒序里的最后一条）是空行时不占位：那 4dp 就是「分割线
                        // 下面还有一点空白」的来源。
                        if (index < lines.lastIndex) Spacer(Modifier.height(4.dp))
                    } else {
                        // 思考链每一行都可选中复制（用户：「确实所有情况都要可复制」）。
                        SelectionContainer {
                        MarkdownText(
                            text = line,
                            // 13sp with roomier leading than bodySmall's: a long chain of
                            // thought is read, not skimmed.
                            style = thinkingStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 不再额外加上边距：thinkingStyle 是 13sp 字号配 20sp 行高
                            // （ChatBlocks.thinkingStyle），首行上方已经有约 3.5dp 的半行距，
                            // 再叠 padding 就成了用户看到的那点「空白」。
                            modifier = Modifier,
                            // **光标直接拼在这一行的末字符后面**（用户：「根本没有跟在思考链
                            // 的后面」）：以前是一个独立的 `BlinkingCursor()` 跟在 item 后面，
                            // 它自成一行、位置和文字对不上。现在作为 AnnotatedString 的尾段，
                            // 换行和对齐都跟着正文走。
                            // 挂在**最新一行**（index 0），空行时顺延到第一条非空行。
                            cursor = streaming && index == cursorAt,
                        )
                        }
                    }
                    // 光标已内联进上一段 MarkdownText（见 cursor 参数），这里不再另起一行。
                }
            }
        }
    }
    val toggle = {
        // Written to the table, not to a local: the choice has to survive the hand-over
        // from the live block to the stored one.
        // 贴底这件事不在这里做：底锚下卡片长高天然向上、底边不动，这里再滚一次就会滚两倍。
        expansion.set(blockId, !expanded)
    }
    val status: @Composable () -> Unit = {
        // The gap in front of the first item belongs to the caller: [ProcessCard] already puts
        // 8dp between the name and this trailing slot, so a second one here doubled it to 16dp —
        // the band of nothing the user pointed at after 「思考过程」. The nested row below has no
        // ProcessCard and adds its own.
        // Item by item with a real gap: joining them into one " · " string is what made
        // "256 tokens · 25.1s" read as a single glued-together word at 13sp.
        meta.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.width(7.dp))
            ProcessStatus(item)
        }
        if (streaming) {
            // 用户口径三次叠加：先「运行中的 icon 左右间距不一致」⇒ 放进方槽居中；
            // 再「左右边距小一点」⇒ 7dp → 4dp；再「再小一点」⇒ 4dp → 2dp。
            // **这一处故意不跟 meta 项的 7dp 走**：spinner 是图标不是文字，和「256 tokens」读成一组太松。
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier.size(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProcessSpinner(MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(2.dp))
        }
    }
    if (nested) {
        // **平铺的折叠块**（用户：「思考块就是折叠块的啊，展开还是虚拟列表啊」+「要的就是平铺」）：
        // 外层不再套卡片底（见 ProcessCard），这一块自己是一条折叠条 —— 表头（图标 + 思考过程 +
        // 段信息 + 箭头）+ 展开后仍是虚拟列表（chain 里的 LazyColumn，逐行虚拟化、高度有上限）。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 折叠块统一（与 ProcessCard 表头、ToolRow 同一套）：固定 36dp 行高 + 12dp
                    // 水平内边距 + labelMedium 标题。折叠态每行都是 maxLines=1，固定高度不会裁字。
                    .height(36.dp)
                    .padding(horizontal = 0.dp)
                    // 整块按下反馈（见 blockPress）：平铺后这一行就是折叠条本体，涟漪是小圆角矩形。
                    .blockPress(MaterialTheme.shapes.small, onClick = toggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_brain),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) Spacer(Modifier.width(8.dp))
                status()
                if (!expanded && firstLine.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = firstLine,
                        style = metaStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The row's one flexible slot, exactly as in [ProcessCard]: the preview
                        // eats the leftover so the chevron sits on the right edge.
                        modifier = Modifier.weight(1f),
                    )
                    if (streaming) BlinkingCursor()
                }
                Icon(
                    imageVector = if (expanded) {
                        ImageVector.vectorResource(R.drawable.ic_chevron_up)
                    } else {
                        ImageVector.vectorResource(R.drawable.ic_chevron_down)
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // 和第一层（ProcessCard）走同一条路径（用户：「第一层折叠能自适应，多个工具展开
            // 就全都往上展」）：`AnimatedVisibility(expandVertically)` 只改高度、不碰列表，
            // 底锚下长出来的那一截只能往上顶；RevealHeight 每帧把增长量反向补进列表，块顶边
            // 才不动。淡入淡出随之取消 —— 第一层本来就没有，两层的观感才一致。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                // 虚拟列表照旧（用户：「展开还是虚拟列表啊」）：chain 内部就是 LazyColumn，
                // 逐行虚拟化 + heightIn(max = 外观里的思考块高度) 上限。
                // top 归 0：首行边距已经放进 chain 内部列表的 contentPadding（会随滚动消失），
                // 这里再留一层就是固定不动的空白条。
                Column(Modifier.padding(start = 44.dp, top = 0.dp)) { chain() }
            }
        }
    } else {
        ProcessCard(
            icon = ImageVector.vectorResource(R.drawable.ic_brain),
            title = "思考过程",
            expanded = expanded,
            onToggle = toggle,
            preview = firstLine.takeIf { it.isNotEmpty() },
            // **折叠条不再挂光标**（用户截图里同一屏出现两个光标）：展开体里已经有那个
            // 跟在末字符后面的光标了，折叠条这个纯属重复。
            previewTail = null,
            trailing = status,
        ) {
            // 思考块**不加**首行间距：正文自带行高，再加一条就是用户看到的那道空白
            // （用户：「思考块分割线下面那个空白」）。工具块仍用 BLOCK_CONTENT_TOP ——
            // 那边是等宽命令/输出，字符本身贴得紧，需要一点 padding。
            chain()
        }
    }
}

/**
 * 折叠块展开后，**内容第一行**与折叠条分割线之间的间距 —— 所有块共用这一个值。
 *
 * 为什么必须是一个常量：思考块（13sp/20sp 行高）、工具块的命令（12sp 等宽）、「输出」行
 * 原本各自用自己的内边距或行高凑间距，于是三个块展开后首行上方的空隙互不相同
 * （用户：「所有块基本的东西都要保持一致」）。现在统一取它。
 */
internal val BLOCK_CONTENT_TOP = 6.dp

/** 13sp / 20sp leading, scaled off bodySmall so 字号 still applies to both. */
@Composable
internal fun thinkingStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return body.copy(
        lineHeight = body.fontSize * (20f / 13f),
        // 两个补偿缺一不可：
        //  · includeFontPadding = false 去掉**字体自带**的上下 padding；
        //  · Trim.FirstLineTop 去掉**首行**分摊到的那半截行距（20sp 行高配 13sp 字号，
        //    首行上方原本还有约 3.5dp）。
        // 少了第二条，思考块的首行空隙就是 BLOCK_CONTENT_TOP + 3.5dp，而工具块只有
        // BLOCK_CONTENT_TOP —— 仍然是「不一致」，只是数值换了。
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.FirstLineTop,
        ),
    )
}

/**
 * Every tool call of one turn behind one [ProcessCard]: the header is the summary line,
 * so ten calls cost one row until the user asks for more.
 *
 * Two levels on purpose. The header opens the list of calls; a single call opens its own
 * arguments and output. Arguments are the noisy part, so they stay behind the second tap
 * even once the list is open.
 */
@Composable
internal fun ToolBundle(
    tools: List<ToolLine>,
    runningTool: String? = null,
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    // **每个工具独立展开**（issue #20）：原来是单个 `String?`，展开 B 会覆盖 A ——
    // 想同时对照「第 1 个的输入」和「第 3 个的输出」就得来回点。用 Set 记住所有打开的，
    // 展开新的不影响已开的。
    // 代价（issue #20 自己提的）：多个 `RevealHeight` 会同时跑动画，位置补偿的负载上升，
    // 与 #13 是同一处风险 —— 若实测位置跳动更明显，再考虑限制「最多同时开 N 个」。
    var openTools by remember { mutableStateOf(emptySet<String>()) }
    val failures = tools.count { it.done && !it.ok }
    // One call is its own name; several are a count, because the names are what the
    // expanded list is for.
    val title = if (tools.size == 1) {
        tools.first().name.ifBlank { "调用 1 个工具" }
    } else {
        "调用 ${tools.size} 个工具"
    }
    // Folded, the header still says what the call is doing: the first argument line of
    // the newest call (a shell command, a path), which is the row's own content anyway.
    val preview = tools.lastOrNull()
        ?.args
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "{}" }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_wrench),
        title = title,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        preview = preview,
        trailing = {
            when {
                // The name of the tool being waited on is the only useful thing the header
                // can say while it is waiting.
                runningTool != null -> {
                    ProcessStatus(runningTool, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    ProcessSpinner(MaterialTheme.colorScheme.primary)
                }

                failures > 0 -> ProcessStatus(
                    text = "$failures 个失败",
                    tone = MaterialTheme.colorScheme.error,
                )

                else -> ProcessStatus("完成", MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        // One call: the header already IS that call's row, so expanding shows its
        // detail straight away — an inner row repeating the header's own name is
        // what drew one call as two.
        if (tools.size == 1) {
            ToolDetail(line = tools.first())
        } else {
            // **这里不用虚拟列表，也不用高度上限**（用户：3 个工具条里不该有虚拟列表，
            // 具体工具里面设虚拟列表即可，和单个工具一样）。
            // 原来是 `LazyColumn(heightIn(max = 320.dp))`：代价有两个 ——
            //  ① 工具条被 320dp 截断，第 2、3 个工具看不全，只能在条内滑动；
            //  ② 条内一滑，容器里的内容和主列表的滚动就脱节，接着折叠会让位置跳一下。
            // 改成普通 Column 后：工具条高度 == 内容高度（不被截断、不能滑动），
            // 里面的块长高多少，容器就长高多少 ⇒ 补偿和第一层用同一套算法。
            // 具体的工具内容仍然是虚拟列表（ToolDetail 自己的 180dp 上限，没动）。
            //
            // 不再加 8dp 行距：多工具里每个工具点开后的样子必须和「单个工具」一致，
            // 多一层间距就是多一层它没有的边距。
            Column(Modifier.fillMaxWidth()) {
                tools.forEachIndexed { index, tool ->
                    // 每个工具**各自**点开（用户：「不是点击多个工具马上平铺各个工具」），
                    // 但彼此**不互斥**：展开第二个不会收起第一个（issue #20）。
                    // 展开后的内容与单个工具同一套（ToolRow 内部不再加缩进）。
                    val key = tool.message?.id ?: "live-$index"
                    key(key) {
                        ToolRow(
                            line = tool,
                            expanded = key in openTools,
                            onToggle = {
                                openTools = if (key in openTools) openTools - key else openTools + key
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a call actually did: its arguments, then its output behind a copy button, then
 * any screenshot it took. Shared by the two-level row and the single-call bundle, so
 * a live call and a settled one show the same thing.
 */
@Composable
internal fun ToolDetail(line: ToolLine, maxHeight: Dp = 180.dp) {
    val scheme = MaterialTheme.colorScheme
    val args = line.args.takeIf { it.isNotBlank() && it != "{}" }
    val output = line.message?.toolOutput?.takeIf { it.isNotBlank() }
    // 行表在这里算（`LazyColumn` 的 content lambda **不是** `@Composable`，里面不能用 remember）。
    // 不 remember 的话，工具块在流式期间每个发布节拍重组都会重跑一次 lines()。
    val rows = remember(output) { output?.lines().orEmpty() }
    // 工具输出的「复制」：写剪贴板 + Toast（用户：「复制后给个提示」）。
    val copyOutput = rememberCopyToClipboard()
    // 与思考块同一套形态：**虚拟列表 + 高度上限**，内容比上限短时按真实高度渲染。
    // 两处刻意的差别（用户要求）：
    //  · 命令**不截断**（原来 maxLines = 2 + Ellipsis，长命令根本读不全）；
    //  · 输出不再用「固定 132dp 的滚动 Text」，而是逐行进入虚拟列表，长输出不会一次性排版。
    // 命令与输出都可选中复制（用户：「确实所有情况都要可复制」）。
    SelectionContainer {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        if (args != null) {
            item(key = "args") {
                Text(
                    text = args,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    // 上 6dp：卡片内容区顶部现在是 0，命令直接贴着折叠条分割线太挤
                    // （用户：「输入那个指令就相当于贴边了」）。只给 args 自己留，不给整块内容区留 ——
                    // 这样「只有输出」的卡片上方仍然是干净的 2dp。
                    modifier = Modifier.fillMaxWidth().padding(top = BLOCK_CONTENT_TOP),
                )
            }
        }
        if (output != null) {
            if (args != null) {
                // 输入（命令/参数）与输出之间要有一条分割线：两段都是等宽小字，没有这条线
                // 读起来是连续的一大段，分不清哪是让它做的、哪是它返回的。
                item(key = "divider") {
                    // 只留上方 6dp：下方再留就成了「分割线与「输出」之间 6+8=14dp」的空白
                    // （用户看出来的「这一行离分割线的间距很大」）。
                    HorizontalDivider(
                        color = scheme.outlineVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = "output-head") {
                // 垂直居中对齐：28dp 的复制按钮和「输出」两字的视觉中线必须重合，用 Top 对齐时
                // 图标会显得比文字高一截（用户看出来的）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 卡片内容区上方已经是 0dp，所以这里固定 2dp：有输入时距分割线 2dp、
                    // 只有输出时距卡片分割线也是 2dp —— 两种卡片看起来一样。
                    modifier = Modifier.padding(top = BLOCK_CONTENT_TOP),
                ) {
                    if (!line.ok) {
                        // A failed call's output is the evidence; the bar marks it as such.
                        Box(
                            Modifier
                                .width(3.dp)
                                .heightIn(min = 16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.error),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    // 左边标出「输出」：复制按钮在右边，左侧空着时那一行看不出是什么区段
                    // （用户：「显得左侧很空，可以在左侧写上输出二字」）。
                    Text(
                        text = "输出",
                        style = metaStyle(),
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { copyOutput(output) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                            contentDescription = "复制输出",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            // 逐行进虚拟列表：行数再多也只排版可见的那些。
            items(rows.size, key = { index -> "out-$index" }) { index ->
                Text(
                    text = rows[index],
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (line.ok) 0.dp else 11.dp),
                )
            }
        }
    }
    }
    line.message?.allImages?.forEach { ImageBlock(it) }
}

@Composable
internal fun ToolRow(line: ToolLine, expanded: Boolean, onToggle: () -> Unit) {
    val tone = when {
        !line.done -> MaterialTheme.colorScheme.primary
        line.ok -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        Modifier
            .fillMaxWidth()
            // 整块按下反馈（见 blockPress）：这一列**展开后包含整段参数与输出**，是有多高就多高
            // 的节点 —— 涟漪的半径按「到最远角的距离」算，于是画出来正是一个大圆，也就是用户
            // 截图里那个「为什么是个圆形」。
            .blockPress(MaterialTheme.shapes.small, onClick = onToggle)
            // 折叠块统一：水平 12dp 内边距与另外两处表头一致；行高加在**表头那一行**上（这一列
            // 展开后还有内容，给 Column 定高会把详情裁掉）。
            .padding(horizontal = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same 24dp slot the thinking rows use, so both icon columns share one
            // left edge inside the expanded card.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (line.done) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_wrench),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    // A call in flight is a spinner, not a dot: the row has to say "still
                    // working" while the model waits on it.
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = tone,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = line.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The name is short and fixed — it takes what it needs and yields the rest to
                // the argument preview. Holding a half-row share cut it in the middle instead:
                // `run_shell` came back as 「run_sh…」, which is the fragment the user read as
                // 「sh」 sitting in the header.
                modifier = Modifier.widthIn(max = PROCESS_TITLE_MAX),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    !line.done -> "运行中…"
                    line.elapsedMs > 0 -> (if (line.ok) "✓ " else "✗ ") + formatDuration(line.elapsedMs)
                    line.ok -> "✓"
                    else -> "✗"
                },
                style = metaStyle(),
                color = tone,
                maxLines = 1,
                softWrap = false,
            )
            // What the call is doing, on the row itself: a folded tool row that only
            // says 「完成」 is exactly what the user called 「执行什么内容也没有」.
            val firstArg = line.args.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.takeIf { it.isNotEmpty() && it != "{}" }
            if (firstArg != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = firstArg,
                    style = metaStyle(),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Icon(
                imageVector = if (expanded) ImageVector.vectorResource(R.drawable.ic_chevron_up) else ImageVector.vectorResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        // 同 ReasoningBlock：和第一层一样走 RevealHeight，展开时块顶边不动、内容往下长。
        RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
            // 与「单个工具」同一套边距：这里**不加** 44dp 缩进 —— 那是多工具独有的、用户
            // 明确指出的多余边距（「要像单个工具一样没有边距」）。
            Column(Modifier.fillMaxWidth()) {
                ToolDetail(line = line)
            }
        }
    }
}
/**
 * The turn's own summary: how much it thought, how many tools it ran, and what it cost,
 * folded into the same card family as the blocks above it.
 *
 * It replaced a bare line of small print under the reply, which read as a footnote rather
 * than as the answer's own record — and it is where the per-segment numbers live, so the
 * thinking and tool blocks above stay one line each.
 */
@Composable
internal fun TurnSummary(
    parts: List<Part>,
    usage: Usage?,
    live: StreamStats?,
    contextWindow: Int,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 历史思考块默认展开; passed on to each think in this card. */
    expandReasoning: Boolean = true,
    /** 外观 → 工具块默认展开; 与运行时同一套（[ToolBundle.startExpanded]）。 */
    expandTools: Boolean = false,
    /**
     * The run's own note — the compression line, when there was one. It belongs in this
     * header rather than only in the status line: the status line is gone the moment the run
     * ends, and "the context was folded here" is a fact about this turn. It rides inside the
     * card, so it folds away with the rest of it.
     */
    note: String = "",
    /** The message these blocks belong to; part of every block's key in the table. */
    messageId: String = "",
    reasoningExpansion: ReasoningExpansion,
    onEdit: (StoredMessage) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // **remember**（性能）：这两行在**参数变化时**才需要重算；记住后同一实例能让
    // `AbstractList.equals` 首行 `o === this` 短路，省掉逐条 Part 深比较。
    val thoughts = remember(parts) { parts.filterIsInstance<Part.Thinking>() }
    val calls = remember(parts) { parts.filterIsInstance<Part.Tools>().flatMap { it.calls } }
    val settled = usage?.takeIf { it.known }
    // The prompt size of the newest round is what is actually sitting in the window now.
    // While a run streams, the engine's copy of the endpoint's last report is fresher than
    // anything already stored, so it wins.
    val context = live?.lastPromptTokens?.takeIf { it > 0 } ?: usage?.prompt ?: 0
    val headline = buildList {
        if (thoughts.isNotEmpty()) add("思考 ${thoughts.size} 段")
        if (calls.isNotEmpty()) add("工具 ${calls.size} 个")
    }
    // While a run streams the header shows the numbers that move; a settled reply shows
    // the ones the endpoint reported.
    val tail = buildList {
        if (live != null) {
            add("输出 ${live.tokensOut}")
            // 官方取整（≥10 整数、<10 一位小数），与统计卡、悬浮窗一致 —— 不是统一的 %.1f。
            if (live.tokPerSec > 0) add("${Tps.format(live.tokPerSec.toDouble())} tok/s")
            if (live.elapsedMs > 0) add(formatDuration(live.elapsedMs))
        } else if (settled != null) {
            if (settled.prompt > 0) add("输入 ${settled.prompt}")
            if (settled.completion > 0) add("输出 ${settled.completion}")
            if (settled.perSecond > 0) {
                add("${Tps.format(settled.perSecond)} tok/s")
            }
            if (settled.elapsedMs > 0) add(formatDuration(settled.elapsedMs))
        }
    }
    // The numbers ride inside the title rather than in their own slot, so they shorten
    // with it and the chip after them is never the thing that gets squeezed.
    // 标题串只在输入变化时重算（每次重组 3 个 buildList + joinToString 是纯浪费）。
    val header = remember(headline, tail, note) {
        buildList {
        add(if (headline.isEmpty()) "本轮用量" else "本轮：${headline.joinToString(" · ")}")
        addAll(tail)
        // After the numbers, before nothing else: the compression line is a fact about this
        // turn, so it rides in the same title and folds away with the card.
        if (note.isNotBlank()) add(note)
        }.joinToString(" · ")
    }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_chart_column),
        title = header,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        trailing = {
            if (live != null) {
                Spacer(Modifier.width(8.dp))
                ProcessSpinner(MaterialTheme.colorScheme.primary)
            }
            // In the header, not only in the details: how full the window is decides
            // whether the next question even fits, so it cannot hide behind a tap.
            if (context > 0) {
                Spacer(Modifier.width(8.dp))
                ContextChip(tokens = context, window = contextWindow, live = live)
            }
        },
    ) {
        // Expanding means the run as it happened: every piece in arrival order —
        // thinking blocks (foldable, full text), tool rows, every stretch of prose
        // including the mid-run transitions, steer bubbles — the same sequence the
        // live bubble drew. The usage detail closes the list.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The answer itself is already drawn by the bubble below this card (the settled
            // path keeps the last stretch of prose). Leaving a second copy in here is what
            // made one reply read as a stack of the same text; the mid-run transitions — the
            // model's "let me check" before a tool — stay, because the bubble does not show
            // those and the card is their only record.
            val lastText = parts.indexOfLast { it is Part.Text }
            parts.forEachIndexed { index, part ->
                // Guarded before key(index), never inside it: a non-local return out of a key
                // group leaves the group unclosed and Compose aborts with "Start/end
                // imbalance" — the crash this file has already paid for once.
                if (part is Part.Text && index == lastText) return@forEachIndexed
                key(index) {
                    when (part) {
                        is Part.Thinking -> ReasoningBlock(
                            text = part.text,
                            streaming = false,
                            // One message can hold several thinks (think → tool → think);
                            // the slot is the run plus the think's place inside it.
                            blockId = thinkSlot(parts, index, messageId.ifEmpty { "summary" }),
                            expansion = reasoningExpansion,
                            reasoningTokens = part.tokens,
                            elapsedMs = part.elapsedMs,
                            // A think from an earlier launch is a settled one, so it opens only if
                            // 历史思考块默认展开 is on; a tap on its header decides it per
                            // conversation either way (the expansion table is keyed by chat).
                            expandReasoning = expandReasoning,
                            // *原样展开**（用户：「折叠条只把最后那段最终文本之外的收起，展开时原样
                            nested = false,
                            maxHeight = reasoningMaxHeight,
                            followReasoning = followReasoning,
                        )

                        is Part.Tools -> {
                            // **与运行时同一套渲染**（用户：「展开时原样展开，不对 UI 做任何更改」）：
                            // 这里原来每个调用单独一行，运行时是合并成「调用 N 个工具」的 ToolBundle。
                            ToolBundle(
                                tools = part.calls,
                                startExpanded = expandTools,
                            )
                        }

                        // Prose keeps the card's own left edge: it is read as the answer's
                        // body, not as a row of the grid, and 2dp of vertical air plus the
                        // column's 8dp keeps it off its neighbours.
                        is Part.Text -> Column(Modifier.padding(vertical = 2.dp)) {
                            SelectionContainer { MarkdownText(text = part.text) }
                        }

                        is Part.Steer -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            UserBubble(message = part.message, onEdit = onEdit)
                        }

                        // 重试留痕：与思考块/工具块同一套栅格（见 TraceBlock），留白不再自己加。
                        is Part.Trace -> TraceBlock(part.note)

                        // 压缩块：位置就是它发生的地方（思考 → 工具 → 压缩 → 继续）。
                        is Part.Compress -> CompressionBlock(part.checkpoint)
                    }
                }
            }
            val detail = buildList {
                if (settled != null) {
                    if (settled.prompt > 0) add("输入 ${settled.prompt}")
                    if (settled.completion > 0) add("输出 ${settled.completion}")
                    if (settled.reasoning > 0) add("思考 ${settled.reasoning}")
                    if (settled.cached > 0) add("缓存 ${settled.cached}")
                } else if (live != null) {
                    add("输出 ${live.tokensOut}")
                    if (live.thinkingTokens > 0) add("思考 ${live.thinkingTokens}")
                }
            }
            if (detail.isNotEmpty() || context > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail.joinToString(" · "),
                            style = metaStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // fill = false, so this text takes only what it needs and gives
                            // way to the chip: an unconstrained Text in a Row eats the whole
                            // width, wraps, and leaves the chip zero width — which then stacks
                            // its own characters one per line and stretches the row.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (context > 0) {
                        if (detail.isNotEmpty()) Spacer(Modifier.width(6.dp))
                        ContextChip(tokens = context, window = contextWindow, live = live)
                    }
                }
            }
        }
    }
}

/** One line inside [TurnSummary] is gone with the segment rows; the expanded card now
 * draws full thinking blocks and two-level tool rows instead. */

/** 上下文 Xk, or Xk / Yk once the window is known; it turns amber near the ceiling. */
@Composable
internal fun ContextChip(tokens: Int, window: Int, live: StreamStats? = null) {
    var open by remember { mutableStateOf(false) }
    val full = window > 0 && tokens * 100 / window >= 80
    val tone = if (full) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = { open = true },
        shape = CircleShape,
        color = if (full) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Text(
            text = if (window > 0) {
                "上下文 ${tokens / 1000}k / ${window / 1000}k"
            } else {
                "上下文 ${tokens / 1000}k"
            },
            style = metaStyle(),
            color = tone,
            // One line, never wrapped: a squeezed chip used to stack its characters
            // vertically and stretch the row it sat in.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }

    if (open) {
        ContextBreakdownDialog(tokens = tokens, window = window, live = live, onDismiss = { open = false })
    }
}

/**
 * One horizontal line per turn, stacked on the list's right edge: the user asked for
 * 「一条一条横线」 in place of the old ◀ i/N ▶ arrows, which also fought the status
 * line for width inside the composer.
 *
 * Only the newest few turns are drawn, one line each — no sampling, so every line is a real
 * turn and a tap lands where the eye aims — and the window slides up when the user is
 * reading something older than it, so "where am I" is always on screen. The turn being read
 * is the longest line and the only brand-coloured one; turns already read keep a weaker
 * brand tint, and the ones still ahead are outline grey.
 *
 * Dragging to the top line unlocks what is above it: the window slides up for as long as the
 * finger keeps pushing past the rail's top edge, one turn per row of travel, so a long
 * conversation is reachable in a single drag without the strip ever changing size. The unlocked
 * amount belongs to the conversation it was pulled in and is dropped when another is opened.
 *
 * Holding the strip brings up a label naming the turn under the finger, and dragging then
 * walks that label along the lines; lifting commits the jump. The whole rail is one gesture
 * handler rather than a tap target per row, which is what lets the rows sit close together:
 * the finger is matched to the nearest line, so it never has to land inside one.
 * (Interaction and the drag/exit thresholds are borrowed from Aether's ConversationTimeline;
 * the code here is our own.)
 */
@Composable
internal fun TurnScrubber(
    /** The conversation whose window this is: opening another one resets it. */
    conversationId: String,
    turns: Int,
    // **延迟读**（issue #26 原因一）：值随滚动每跨一个 item 就变；若由调用方在组合体里算好再传，
    // 每次变化都会让整个聊天页重组。改成 lambda 后只有本组件自己读、自己重组。
    current: () -> Int,
    modifier: Modifier = Modifier,
    labelOf: (Int) -> String,
    onJumpTo: (Int) -> Unit,
) {
    if (turns <= 1) return
    val scheme = MaterialTheme.colorScheme
    // How far above the window the finger has pulled the strip — the turns it has unlocked in
    // this conversation. Kept for as long as the chat is open, dropped the moment another is
    // opened: a deep conversation unlocked in one chat must not decide what the next one shows.
    var unlocked by remember(conversationId) { mutableIntStateOf(0) }
    // The last few turns, always including the one being read: sampling the whole
    // conversation into five evenly spaced lines is what made "which line is which turn"
    // impossible to tell, and made the jump land somewhere the user did not point at.
    val currentTurn = current()
    val window = remember(turns, currentTurn, unlocked) {
        scrubberWindow(turns, currentTurn, unlocked)
    }
    val rows = remember(window) { window.toList() }
    val rowCount = rows.size
    // A gesture outlives the recomposition it started in, so it reads the turns and the
    // jump through these instead of closing over values a new message has replaced.
    val latestRows by rememberUpdatedState(rows)
    val latestJump by rememberUpdatedState(onJumpTo)
    // Which row the finger is on, which row the label names, and whether the label is out.
    // All three are cleared when the finger lifts, so no frame can leave the rail stuck open.
    var pressRow by remember { mutableIntStateOf(-1) }
    var selectionRow by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val selected = selectionRow.coerceIn(0, rowCount - 1)

    val density = LocalDensity.current
    val rowPx = with(density) { SCRUBBER_ROW.toPx() }
    val gapPx = with(density) { SCRUBBER_GAP.toPx() }
    val railPx = with(density) { SCRUBBER_RAIL.toPx() }
    val labelGapPx = with(density) { SCRUBBER_LABEL_GAP.toPx() }
    // The label rides beside its own row, and only the rows know where they sit, so it is
    // measured off the rail's vertical centre: the strip itself is centred on the reading
    // area and its height changes with the number of turns in the window.
    val railHeightPx = rowPx * rowCount + gapPx * (rowCount - 1)
    // Animated, not computed straight from the row: dragging the finger down three lines then
    // has the label glide to each one instead of teleporting, which is what makes the drag read
    // as moving along the strip rather than as three separate taps.
    val labelDyPx by animateFloatAsState(
        targetValue = selected * (rowPx + gapPx) + rowPx / 2f - railHeightPx / 2f,
        animationSpec = tween(SCRUBBER_SELECT_MS, easing = SCRUBBER_EASING),
        label = "scrubber_label_dy",
    )

    // Full width for the *measuring constraint only*: the label is a child of this box, and a
    // child can never be measured wider than its parent — at the rail's own 24dp the bubble was
    // squeezed into a 24dp-wide square with 9dp of padding either side, which left about 6dp
    // for the text and ellipsized it to a single character. That square is the dark blob the
    // user photographed. The gesture lives on the rail itself below (24dp wide, the height of
    // the drawn lines), because a full-width column here turned every touch across the
    // transcript's right edge into a scrubber press: a long press meant to select text landed
    // on a turn, the jump on the lift moved the list, and the selection went with it.
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                // The touch area is the rail and nothing else: exactly the width of the longest
                // line drawn, and the height of the rows plus their gaps.
                .width(SCRUBBER_RAIL)
                .align(Alignment.CenterEnd)
                // One handler for the whole rail instead of a clickable per row: the finger
                // is matched to the nearest line, so a press between two lines still lands
                // on one of them and a drag can cross rows without dropping the gesture.
                .pointerInput(Unit) {
                    val hitRowPx = SCRUBBER_ROW.toPx()
                    val hitGapPx = SCRUBBER_GAP.toPx()
                    val hitPitchPx = SCRUBBER_ROW.toPx() + SCRUBBER_GAP.toPx()
                    val railWidthPx = SCRUBBER_RAIL.toPx()
                    val dragPx = SCRUBBER_DRAG_THRESHOLD.toPx()
                    val exitPx = SCRUBBER_EXIT_TOLERANCE.toPx()
                    awaitEachGesture {
                        val railRows = latestRows
                        if (railRows.isEmpty()) return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startRow =
                            scrubberRowFor(down.position.y, hitRowPx, hitPitchPx, railRows.size)
                        pressRow = startRow
                        selectionRow = startRow
                        shown = false
                        var travel = 0f
                        // Only a gesture that really moved is eligible for the off-rail cancel
                        // below; a tap and a still hold always jump.
                        var dragged = false
                        var pointer = down.position
                        // True only until the hold has run out; after that the pointer is
                        // awaited without a deadline, so a slow drag is never cut short.
                        var waiting = true
                        var pressed = true
                        while (pressed) {
                            val event = if (waiting) {
                                withTimeoutOrNull(SCRUBBER_LONG_PRESS_MS) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // The hold ran out: the label comes up and stays up until the
                                // finger lifts. This is the "press and hold to see it" half.
                                waiting = false
                                shown = true
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                pressed = false
                                continue
                            }
                            // Both axes, but only the dominant one per event: summing them
                            // doubled the jitter during a still hold and could pop the label
                            // before the 220ms hold, while counting Y alone let a purely
                            // horizontal drag off the rail slip through as a jump.
                            travel += max(
                                abs(change.position.y - pointer.y),
                                abs(change.position.x - pointer.x),
                            )
                            pointer = change.position
                            if (travel >= dragPx) {
                                // Dragging is an answer in itself, so it skips the rest of the
                                // hold and starts previewing turns at once.
                                waiting = false
                                dragged = true
                                shown = true
                                selectionRow = scrubberRowFor(
                                    change.position.y,
                                    hitRowPx,
                                    hitPitchPx,
                                    railRows.size,
                                )
                                // On the top line the strip gives ground: a finger that keeps
                                // pushing up unlocks the turns above the window, one row of
                                // travel at a time, so the whole conversation is reachable in
                                // one drag. The window slides rather than grows, so the rows
                                // never move under the hand and the line the finger is on stays
                                // the line it is on.
                                unlocked = scrubberPull(
                                    y = change.position.y,
                                    rowPx = hitRowPx,
                                    pitchPx = hitPitchPx,
                                    pull = unlocked,
                                )
                            }
                            pressed = change.pressed
                            // Consumed here so the transcript under the rail never scrolls
                            // along: dragging the strip previews turns, it does not move the
                            // conversation. The jump happens once, on the lift.
                            change.consume()
                        }
                        val destination = selectionRow
                        // A finger that has really dragged and then wanders off the strip was
                        // after the transcript rather than a turn, so the jump is dropped and the
                        // strip springs back. Tested on the two sides and the bottom of the rail —
                        // not the top. Pushing up past the first line is precisely how the turns
                        // above the window are unlocked (see [scrubberPull]), and cancelling there
                        // made the user's own gesture work against itself: one turn of unlock is
                        // about 17dp of travel, and this tolerance killed the whole gesture at
                        // 28dp — so the harder they pulled for more history, the more certainly
                        // the lift did nothing at all. Which is what "导航条条数没增加" is: the
                        // strip had unlocked a line or two and then thrown the jump away.
                        //
                        // Measured here rather than read from the composition's own rail height:
                        // this handler is keyed on Unit and never restarts, so a window that has
                        // since gained or lost a turn would be judged against a stale box. It
                        // cannot: the window is a fixed number of lines that slides, so its size —
                        // the only thing this needs — is the same at the lift as at the press.
                        val railBottomPx =
                            hitRowPx * railRows.size + hitGapPx * (railRows.size - 1)
                        val cancelled = dragged && (
                            pointer.x < -exitPx ||
                                pointer.x > railWidthPx + exitPx ||
                                pointer.y > railBottomPx + exitPx
                            )
                        pressRow = -1
                        shown = false
                        // The strip recentres on wherever the jump lands: the unlocked turns
                        // were the ones this drag could reach, and the window goes back to
                        // naming the turn being read instead of staying slid up over turns the
                        // reader has not left yet.
                        unlocked = 0
                        // [latestRows], not the snapshot the hit test was matched against: a
                        // drag that unlocked its way up the conversation is aimed at a turn
                        // that snapshot never held.
                        if (!cancelled) latestRows.getOrNull(destination)?.let(latestJump)
                    }
                },
            verticalArrangement = Arrangement.spacedBy(SCRUBBER_GAP),
            horizontalAlignment = Alignment.End,
        ) {
            rows.forEachIndexed { row, turn ->
                val read = turn < currentTurn - 1
                val active = turn == currentTurn - 1
                val chosen = shown && row == selected
                val scale by animateFloatAsState(
                    targetValue = when {
                        chosen -> SCRUBBER_PRESS_SCALE
                        row == pressRow -> SCRUBBER_TOUCH_SCALE
                        else -> 1f
                    },
                    animationSpec = tween(110, easing = SCRUBBER_EASING),
                    label = "scrubber_row_scale",
                )
                Box(
                    modifier = Modifier.height(SCRUBBER_ROW),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            // The turn being read is the longest and the only full-strength
                            // brand line. It used to fall into the "not reached" branch and come
                            // out grey, which is exactly backwards.
                            .width(if (active) 24.dp else if (read) 20.dp else 12.dp)
                            .height(if (active) 3.dp else 2.dp)
                            // Grown out of its right edge: the enlargement then never walks the
                            // line off the 4dp edge gap, and never re-lays-out the rows under a
                            // finger that is still dragging.
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            }
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                when {
                                    // Lit while it is the one being previewed, so dragging
                                    // onto a turn that has not been read yet still shows up.
                                    chosen -> scheme.primary
                                    active -> scheme.primary
                                    read -> scheme.primary.copy(alpha = 0.45f)
                                    else -> scheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                // Layout-neutral on purpose: the label hangs off the rail's left edge and must
                // not widen it, or the strip would drift out over the bubbles it clears today.
                .offset { IntOffset(-(railPx + labelGapPx).roundToInt(), labelDyPx.roundToInt()) },
            enter = fadeIn(tween(SCRUBBER_LABEL_IN_MS)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(190, easing = SCRUBBER_EASING),
            ),
            exit = fadeOut(tween(SCRUBBER_LABEL_OUT_MS)) + scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(130, easing = SCRUBBER_EASING),
            ),
        ) {
            Text(
                text = latestRows.getOrNull(selected)?.let(labelOf).orEmpty(),
                // labelMedium rather than labelSmall, and a roomier box: the bubble floats
                // over the transcript for as long as a finger holds the rail, so it has to be
                // read at a glance. Still one line, and still capped at SCRUBBER_LABEL_MAX, so
                // a long opening words the line short rather than wrapping it into a block.
                style = MaterialTheme.typography.labelMedium,
                color = scheme.inverseOnSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = scrubberLabelMax(LocalConfiguration.current.screenWidthDp.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.inverseSurface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

