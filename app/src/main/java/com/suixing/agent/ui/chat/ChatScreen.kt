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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import com.suixing.agent.ui.components.SettledBlocks
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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




internal val SUGGESTIONS = listOf(
    "看看现在屏幕上是什么",
    "把下载目录里最新的三个文件列出来",
    "搜一下今天有什么科技新闻",
    "打开设置看看还剩多少存储空间",
)

/**
 * **A/B 实验开关**：读历史时要不要「钉住流式行的布局高度」（#17 的方案）。
 *
 * `true` = 现状（钉住，防止被推着走）；`false` = 关掉钉住（只测量、不冻结高度）。
 *
 * 用途：用户报「往上滑、再快速下滑会出现白色块」。白块是近期才高频出现的，而钉住正是
 * `dd4f998`（0.8.320）引入的 —— 出一版 `false` 的包做**单变量对照**：
 * 白块消失 ⇒ 与钉住这套有关；白块照旧 ⇒ 无关，回到基础列表找。
 * 对照做完把它改回 `true`（或删掉这个开关）。
 */
/**
 * 钉住状态机的**触发信号**。
 *
 * 为什么要一个类型而不是 `Triple`：评审第三轮指出「`livePin` 实例被 `LaunchedEffect` 捕获」
 * 与「`itemCount` 被冻结成常量」是同一类事故 —— 判定的所有输入都必须出现在
 * `snapshotFlow` 的读取集里。把输入收成一个类型，漏一个就编译不过（而不是静默冻结）。
 */
private data class PinSignals(
    val chatId: String,
    val notAtBottom: Boolean,
    val hasLiveContent: Boolean,
    val pinActive: Boolean,
)

// **关掉"钉住布局高度"**（用户明确要求）：它会让流式行只露出半截/或只剩提示条，
// 而用户要的是「正常生长」。代价是回到"读历史时新内容会把上面的位置推走"，
// 那要靠"把流式内容移出列表"来根治（issue #25 的做法，只移流式那一行）。
private const val PIN_LIVE_HEIGHT = false

/** 分批填充：首批行数 / 之后每帧补几行（打开会话的 150ms 摊到几帧里）。 */
private const val CHUNK_FIRST = 4
private const val CHUNK_STEP = 3

/** 自适应填充每帧补几行的上限（下限是 1）。 */
private const val CHUNK_MAX_STEP = 5

/** 切换动画期间的填充步长上限：动画本身要合成整条列表，再叠大块组合就会掉到 60Hz。 */
private const val SWITCH_MAX_STEP = 2

/** 填充的帧预算（ms）：超过它就说明这一帧补多了，下一帧少补一行。 */
private const val STALL_FRAME_MS = 12L

/**
 * #17 验收用的**轻量痕迹**：只在状态翻转时写一行。
 *
 * 为什么不用 logcat：这台机器上 MIUI 把 logcat 通道限得很死（`adb logcat` 读不到），
 * 而 `run-as` 对 release 包不可用。写文件至少能用 root 的 `adb shell su -c cat` 捞出来。
 *
 * **只在翻转时调用**（一轮对话通常 3~5 行），并带大小上限，绝不做逐帧 IO
 * —— 0.8.333 已经吃过一次"逐帧写盘反而造成卡顿"的教训。
 */
private val NL_PIN = 10.toChar().toString()

// 写盘放到自己的 IO 作用域（`SupervisorJob` 保证一条写失败不影响后续）：
// 调用点在 Compose 的 effect 里、跑在**主线程**上，直接 `appendText` 就是一次主线程 IO。
// 只在状态翻转时触发（一轮几条），但没理由让它占主线程。
private val PIN_TRACE_SCOPE =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

private fun pinTrace(message: String) {
    PIN_TRACE_SCOPE.launch {
        runCatching {
            val dir = Core.appContext.filesDir
            val file = java.io.File(dir, "pin.log")
            if (file.length() > 64 * 1024) file.writeText("")
            file.appendText(System.currentTimeMillis().toString() + " " + message + NL_PIN)
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    /** The generation popup's empty-list escape hatch into the model settings page. */
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 顶层拿一次剪贴板：文件路径菜单的「复制」用（见 [PathActionSheet] 接线处）。
    val clipboard = LocalClipboardManager.current
    // The unsent text is the view model's, keyed by conversation: typing here, opening
    // another chat and coming back restores exactly what was left, and nothing leaks
    // across the switch.
    val draft = state.draft
    val input = draft.text
    // The conversation is a key as well: the cache is per conversation, and two chats that both
    // hold a 「修改」-restored picture carry the *same* marker uri and label — without this, coming
    // back from one to the other kept the previous chat's decoded bitmap (the chip would show, and
    // send, the wrong picture).
    val attachments = remember(
        state.conversationId,
        draft.attachmentUri,
        draft.attachmentName,
        draft.imageNames,
    ) {
        viewModel.attachmentsFor(state.conversationId)
    }
    fun setDraft(
        text: String = input,
        uri: String? = draft.attachmentUri,
        name: String? = draft.attachmentName,
        imageNames: List<String> = draft.imageNames,
    ) {
        viewModel.onDraftChange(state.conversationId, Draft(text, uri, name, imageNames))
    }

    /**
     * 「修改」 on a queued line: the line comes back to the composer, text **and picture**.
     *
     * The text is appended rather than substituted — the field may hold something the user is in
     * the middle of typing, and taking a queued line back must not throw that away without a word.
     *
     * The picture needs three things, which is why this is not a one-liner: the bytes have to be
     * decoded off the main thread, they have to go into the view model's attachment cache (that is
     * what the chip and the send path read), and the draft's `attachmentUri`/`attachmentName` have
     * to change so the composer's `remember` re-reads that cache. [QUEUED_ATTACHMENT_URI] is a
     * marker, not a real uri: this picture exists only in memory, so after a restart the chip is
     * simply absent (the cache is gone) while the text survives. That is the honest outcome — the
     * alternative would be a draft pointing at a uri that cannot be read.
     *
     * A decode that fails still returns the text: losing the picture is a smaller loss than losing
     * the sentence the user typed.
     */
    suspend fun restoreQueuedSteer(steer: PendingSteer) {
        val text = if (input.isBlank()) steer.text else "${input.trimEnd()} ${steer.text}"
        // 多附件：排队行的图逐张解回缓存列；一张解不开就跳过它（丢一张图比丢整句话小）。
        var restored = 0
        for (base64 in steer.imagesBase64) {
            val bitmap = decodeAttachment(base64) ?: continue
            viewModel.addAttachment(state.conversationId, bitmapAttachment(bitmap, QUEUED_IMAGE_LABEL))
            restored++
        }
        setDraft(text = text, imageNames = draft.imageNames + List(restored) { QUEUED_IMAGE_LABEL })
    }
    // **按会话重置**（评审 S4）：它持有的是某条消息对象，切会话后若还能被读到，
    // `editMessage(id, …)` 会把 A 的编辑改到 B 上。目前靠"编辑弹窗是模态"侥幸挡住，
    // 但任何非模态切换路径（通知/deep link/分屏）都会踩。
    var editing by remember(state.conversationId) { mutableStateOf<StoredMessage?>(null) }
    // The composer stack's own measured height: the node inside all of its paddings, so the
    // gesture bar's strip and its own 12dp of air are not in it (they are added back below).
    var composerH by remember { mutableStateOf(0.dp) }
    // The keyboard's clamped inset, and the gesture bar's own: the composer's bottom padding is
    // the difference between them (see the stack's own modifier), and it is the one term of the
    // stack's footprint that moves while the keyboard does.
    // **键盘高度量化到 4px 再读**：`heldImePx` 在键盘动画的每一帧都会变，直接读它就是整页
    // 每帧重组（ChatScreen 有几十个子组合）。量化后只有跨过 4px 才重组一次 —— 一次 ~200px 的
    // 抬起仍有 50 档，视觉上看不出台阶，但重组次数降到约 1/4。
    // **量化已经在生产处做过一次**（`SuixingApp.kt` 的 `IME_QUANTUM_PX`）：这里直接读，
    // 不再二次量化。二次量化会让输入框（布局期读原始值）和列表（组合期读量化值）看到
    // 两个不同的数，键盘动画期间两者间距逐帧变化 —— 用户报的「边距在抖」。
    val imePxState = LocalHeldImePx.current
    // 量化已经在生产处做过一次（SuixingApp 的 IME_QUANTUM_PX），这里直接读状态本身。
    // 原来还套了一层 `remember(imePxState){ derivedStateOf { imePxState.value } }` —— 恒等转发，
    // 只多一层对象与一次空转的失效判断。
    val heldIme by imePxState
    val navBarPx = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this) }
    fun navBarPad(ime: Int): Int = (navBarPx - ime).coerceAtLeast(0)
    // 让位＝**只用一个来源**：输入框自己的高度 + 它的下内边距 + 手势条。键盘动画中唯一在变的量是
    // heldIme，而它正是输入框自身位置的同一个驱动量 ⇒ 两者同步移动，可见空隙在任何一帧都一样
    // （用户要恒定）。原来用「列表底 − 输入框顶」两个节点的测量值相减，两个 onGloballyPositioned
    // 不同帧到位 ⇒ 动画中间态抖动。
    // 读历史期间沿用的底边距（见 LazyColumn 的 `bottom`）。
    // **null = 还没种过**，不是 0.dp（评审确证）：用 0.dp 当哨兵时，恢复位置进会话会先以
    // 0 当作底边距、等 `composerH` 量到才补成真实值 ⇒ 底边距 0 → ~一个输入框高，
    // 而 `scrollToItem` 已经执行过 ⇒ 整段恢复位置被推移一次。
    // **按会话 key**（评审）：底边距是"读历史期间冻结的那一个"，不同会话的输入框/让位不同，
    // 不 key 的话切到另一个会话会沿用上一个会话冻结值。
    var heldFootprint by remember(state.conversationId) { mutableStateOf<Dp?>(null) }
    val stackFootprint = with(LocalDensity.current) {
        // `composerH` 是**内边距以内**的高度：`onSizeChanged` 挂在
        // `.padding(bottom = COMPOSER_BOTTOM_PAD)` **之后**（TurnScrubber.kt:757-758），
        // Compose 的修饰符顺序让 padding 先吃掉空间，所以测到的不含这 12dp。
        // 这里把它加回来才是输入框真实的占位 —— **不要删**（我删过一次，是错的）。
        composerH + COMPOSER_BOTTOM_PAD + navBarPad(heldIme).toDp() +
            // **恒定 12dp**（用户定案：所有垂直间距统一 12dp —— 静态项间距、状态行上下、
            // 底部让位全一致；顺带保持「run 结束时几何完全不变」）。
            12.dp
    }

    var voiceOpen by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceOpen = true
        } else {
            scope.launch { snackbarHostState.showSnackbar("没有麦克风权限，语音输入用不了") }
        }
    }

    var assistantsOpen by remember { mutableStateOf(false) }
    // 助手编辑器的开关（主窗口全屏覆盖层，结构路 0.8.105）：id 而非对象，转屏/重组不丢。
    var editorAssistantId by remember { mutableStateOf<String?>(null) }
    // 任务清单：输入框上方那一行点开就是它（完整清单，可滚动）。
    var todoSheetOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun attach(uri: Uri?, kind: AttachKind) {
        if (uri == null) return
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readAttachment(context, uri, kind) }
            if (picked == null) {
                snackbarHostState.showSnackbar(if (kind == AttachKind.FILE) "读不到这个文件" else "读不到这张图片")
            } else {
                // 多附件：追加而不是覆盖（用户：「图片为什么只能添加一张」）。draft 的 imageNames
                // 是 remember 的重读触发器，标签列与缓存列同序。
                viewModel.addAttachment(state.conversationId, picked)
                setDraft(imageNames = draft.imageNames + picked.label)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        // 多附件：PickMultipleVisualMedia 让用户一次连选多张（用户选了「不限张数」）。
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> uris.forEach { attach(it, AttachKind.IMAGE) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) {
            val picked = bitmapAttachment(bitmap)
            viewModel.addAttachment(state.conversationId, picked)
            setDraft(imageNames = draft.imageNames + picked.label)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> attach(uri, AttachKind.FILE) }

    // How much of the conversation is drawn. A long chat used to be grouped, measured and
    // turned into rows in full on every state change, however far back the reader was —
    // the whole transcript priced on every streamed round. What is drawn instead is a
    // suffix, in whole turns: always the newest ones, so a streaming answer keeps its place
    // in it, and 加载更早 only ever grows it. The window is a property of the chat being read,
    // so the count is kept **per conversation** (issue #16): `rememberSaveable(key)` did not
    // keep one per chat, it re-initialised on every switch — coming back to A collapsed it to
    // the newest page, the saved scroll index then pointed past the end of a much shorter
    // transcript, and LazyColumn clamped it to the top. Kept saveable for the same reason as
    // before: the composition can be rebuilt (activity recreation) and the reader's 加载更早
    // taps must survive it.
    var shownTurnsByConversation by rememberSaveable { mutableStateOf(emptyMap<String, Int>()) }
    val shownTurns = shownTurnsByConversation[state.conversationId] ?: PAGE_TURNS
    // What every derivation below is keyed on: which conversation, how many rows it has, and which
    // row is last.
    //
    // Not the list itself. `remember` compares its keys with `equals`, and a message carries its
    // screenshot as base64 — one picture made each comparison a string compare of hundreds of
    // kilobytes, on every recomposition, which is once per publish (twelve a second) while an
    // answer streams. These walks only move when a row lands, the last one is replaced, or the
    // screen switches conversation; the id is in the key because two conversations can otherwise
    // agree on a shape (two empty ones always do).
    val rowsKey = Triple(
        state.conversationId,
        state.messages.size,
        state.messages.lastOrNull()?.id,
    )
    // [turnPage] reads nothing but each message's role and steer flag, so this key says everything
    // its answer can depend on.
    // **轮数总数要按磁盘上的真实值报**（#30）：`state.messages` 现在只是"最近 N 轮"这个窗口，
    // 用它算出来的 `turns` 就等于窗口里的轮数 ⇒ `earlier` 恒为 0 ⇒ 「加载更早」不出现、
    // 而且「第 N 轮」永远显示成第 1 轮（用户能直接看见的错）。
    // 总数来自落盘 meta 的 `turnCount`（由 ChatStore 维护，**是轮数不是条数**）。
    val page = remember(rowsKey, shownTurns, state.turnCount) {
        val drawn = turnPage(state.messages, shownTurns)
        if (state.turnCount > drawn.turns) drawn.withTotal(state.turnCount) else drawn
    }
    // Tool-only assistant turns carry no text of their own, so their tool calls are
    // folded into the bubble above them instead of standing alone in the transcript.
    // Only the branch the user picked is drawn; the other answers stay on disk.
    // liveRunId is a key AND a filter: the rounds of the run still streaming are
    // already in `messages` (every event is appended there as it lands), so without
    // the filter groupRows drew them as their own settled bubble beside the live one
    // — the double bubble. The filter is what makes the settle frame atomic: the
    // rounds leave the stored list in the same state update that empties liveParts.
    // The transcript itself is keyed by [ChatUiState.messagesRevision], not by the list: `remember`
    // compares keys with `equals`, and a message carries its screenshot as base64 — keyed on the
    // list, every recomposition walked the whole conversation (twelve times a second while an
    // answer streams). The revision is stamped by the view model at the one point every publish
    // goes through, so an edit to a message in the middle of the transcript still lands here.
    val visible = remember(
        state.conversationId,
        state.messagesRevision,
        page,
        state.activeRuns,
        state.runningHere,
        state.liveRunId,
    ) {
        groupRows(state.messages.drop(page.from), state.activeRuns, state.runningHere)
            .filterNot { it.message.runId == state.liveRunId && state.liveRunId.isNotEmpty() }
    }
    // 底锚的喂序是反的；反转结果记住，别每次重组都新建包装对象。
    val reversedVisible = remember(visible) { visible.asReversed() }
    // 每行实测高度（visible 正序，已含 12dp 行间距）：单 item 化后
    // `firstVisibleItemIndex` 恒为 0，读数导航改为「像素偏移 → 第几轮」反查。
    val rowHeights = remember(visible) { IntArray(visible.size) }
    // 回执（落盘的 system 消息）**内联画在它那一轮后面**（用户：「一轮错误就跟在一轮后面」）——
    // MessageRow 的分发里画（Roles.SYSTEM → FailureReceiptRow）。这里只取一遍供去重：同文案的
    // 实时行不再画，一次失败只通知一次（用户报的「两个提醒」）。
    val receipts = remember(state.conversationId, state.messagesRevision, page) {
        state.messages.drop(page.from).filter { it.role == Roles.SYSTEM }
    }
    // 没有回执的失败（发送阶段错误等）的实时行，画在最后一轮下面。去重见 [isSameFailure]：
    // 只按完全相等比会漏，同一次失败被两条路径各写一遍时屏幕上是两张提示。
    val errorLine = state.error?.let { err -> receipts.none { isSameFailure(it.text, err) } } ?: false
    // Whatever the run produces first — a tool call, a thought or a word — opens the live
    // bubble. Waiting for text is what used to leave a tool call stranded on its own until
    // the reply arrived and merged the two into one.
    // 流式气泡开关与 pin 信号都只需要「有没有活内容」这个布尔：
    // 收 VM 的 liveActive（distinct 去抖），思考期的 40ms 批次不再重组整屏。
    val live by viewModel.liveActive.collectAsState()
    val assistant = settings.assistant(state.assistantId) ?: settings.activeAssistant()
    // The chain a run takes, resolved once for the whole screen. The context readouts
    // below divide by the window of the model that actually sends, which is not the
    // app-wide default's window once a conversation or a persona picks its own.
    // 每次重组都要扫 provider×model（流式期 12.5 Hz），而这三个输入很少变 —— 记住它。
    val contextWindow = remember(settings, state.providerId, state.model, assistant) {
        settings.contextWindowFor(settings.choiceFor(state.providerId, state.model, assistant).model)
    }
    // Which endpoint a stored round ran on. A message keeps its model id and nothing else, so
    // its owner is whichever endpoint lists that id — the same question the picker asks. Built
    // once per settings change rather than per row: a long transcript redraws far more often.
    // First endpoint wins, so the user's own order settles an id two of them both list.
    val providerOfModel = remember(settings.providers) {
        val owners = mutableMapOf<String, ProviderConfig>()
        settings.providers.forEach { provider ->
            (provider.enabled + provider.models + provider.aliases.keys).forEach { id ->
                owners.putIfAbsent(id, provider)
            }
        }
        owners
    }
    // What the ring left of the mic draws: the newest round's own count, or the live run's
    // estimate while it streams — the same pair the summary card reads, so the two agree.
    // The cache count is the newest round's too: context is cumulative, so an older round's
    // cache figure says nothing about the window as it stands now.
    // **包 remember**（性能优化，值不变）：这是对可见行的 O(n) 反查，原来每次 ChatScreen
    // 重组都跑一遍 —— 滚动时每秒好几次、每次扫几百行。`visible` 本身已经按 revision 记忆，
    // 所以用 `visible` 当键是稳定的。
    val newestUsage = remember(visible) { visible.lastOrNull { it.usage != null }?.usage }
    // What the endpoint never priced, sized off the text: the ring's floor for a conversation
    // 键用 **messagesRevision**，不是 rowsKey：`rowsKey` 是（会话, 条数, 末条 id），
    // 编辑某条消息、或流式把文本追加进已落盘消息时，条数与末条 id 都不变 ⇒ 估算值不刷新，
    // 圆环读数会停在旧数字。revision 是 ViewModel 在每次发布时统一盖的章。
    // **逐条缓存 token 估算**（审计 1§2）：`estimateTokens` 对正文做逐字符 CJK 判定，
    // 12 轮 + 工具输出可达 5~20 万字符 ⇒ 每次 revision 一次几 ms 的主线程尖峰，
    // 与 frame.log 里「成对 24ms+16ms、每 100~350 帧一次」的形态吻合。
    // 消息是不可变值对象 ⇒ 按 id 缓存，用 text.hashCode()（String 自带缓存）验证内容未变。
    val tokenMemo = remember(state.conversationId) { HashMap<String, LongArray>() }
    val transcriptEstimate = remember(state.messagesRevision, state.checkpoints) {
        val last = state.checkpoints.lastOrNull()
        val start = last?.let { cp ->
            val i = state.messages.indexOfFirst { it.id == cp.toMessageId }
            if (i >= 0) i + 1 else 0
        } ?: 0
        val kept = if (start in 1..state.messages.lastIndex) {
            state.messages.subList(start, state.messages.size)
        } else {
            state.messages
        }
        var sum = 0
        kept.forEach { message ->
            val stamp = message.text.hashCode().toLong() * 31 +
                message.toolOutput.orEmpty().length +
                message.toolArgs.orEmpty().length * 7
            val cached = tokenMemo[message.id]
            val tokens = if (cached != null && cached[0] == stamp) {
                cached[1].toInt()
            } else {
                estimateTokens(message).also { tokenMemo[message.id] = longArrayOf(stamp, it.toLong()) }
            }
            sum += tokens
        }
        sum + (last?.summary?.length ?: 0) / 3
    }
    // 圆环读数＝**真实数字**：当前会话的上下文大小 / 模型窗口（用户：「就是按照真实数字啊…
    // 当前会话上下文/上下文窗口的数字」）。分子只认实测口径：网关回报的本轮 prompt_tokens
    // （含按会话保持的最近一次）→ 落盘回报；只有整个会话从没被网关报过用量时才用 /4 估算兜底
    // 并标「约」。跳变的老根因是两个口径轮流上屏，所以估算值绝不与实测值并列成链。
    val heldPrompt = rememberSaveable(state.conversationId) { mutableIntStateOf(0) }
    // 请求级快照（prompt/breaks 变才发射）——body 不再订阅 120ms 的 elapsedMs。
    val runStatsNow by viewModel.requestStats.collectAsState()
    // **实时统计属于"正在跑的那一次请求"**：它挂在 ViewModel 上全局共享，切会话/新建会话时
    // 不会自己清空。原来直接拿它当圆环读数 ⇒ 新建的空会话会显示上一个会话的 40% / 409.5K
    // （用户实测截图）。所以只在**这个会话自己正在跑**时才认它；其余场合交给下面三档
    // （本会话的实测快照 heldPrompt / 落盘实测 persistedContext / 估算兜底）。
    val liveStatsHere = runStatsNow.takeIf { state.runningHere }
    val reported = liveStatsHere?.lastPromptTokens ?: 0
    LaunchedEffect(reported) {
        if (reported > 0) heldPrompt.intValue = reported
    }
    // 落盘的实测上下文（issue #41 真问题 1）：run 结束瞬间前两级归零，这一档接住圆环。
    // meta 里 O(1) 命中（totalsAt==messageCount 时），每轮落盘自动更新。
    var persistedContext by remember(state.conversationId) { mutableIntStateOf(0) }
    LaunchedEffect(state.conversationId, state.messagesRevision) {
        persistedContext = withContext(Dispatchers.IO) {
            runCatching {
                Core.chats.sessionTotals(state.conversationId)?.contextTokens ?: 0
            }.getOrDefault(0)
        }
    }
    val reading = ringReading(
        liveReported = if (reported > 0) reported else heldPrompt.intValue,
        newestStored = newestUsage?.prompt ?: 0,
        liveEstimate = liveStatsHere?.let { it.breakSystem + it.breakTools + it.breakHistory } ?: 0,
        transcriptEstimate = transcriptEstimate,
        persistedContext = persistedContext,
    )
    // 切会话时算一次兜底读数（网关没报过用量时那两行占比要有数）。
    LaunchedEffect(state.conversationId) {
        viewModel.refreshContextBreakdown()
    }
    val contextUsed = reading.tokens
    // 圆环读数诊断（用户选的「加日志抓，不再猜」）：任一候选源或 run 状态变化时记一行，含全部
    val session = viewModel.sessionStats.collectAsState(initial = null).value
    val sessionTokens = session?.tokens ?: 0
    val sessionTurns = session?.turns ?: 0
    // The cumulative row's own floor, for the same conversations as above: every turn re-sent the
    // context it had, so the transcript's size times the turns is the shape of the total. It is a
    // floor — a tool-heavy run re-sends more often than it has questions — which is why the card
    // writes 「约」 in front of it and nothing else about the split is invented.
    val sessionEstimate = if (sessionTokens > 0) 0 else transcriptEstimate * sessionTurns.coerceAtLeast(0)
    // What each run actually took, summed over its own messages. A message's own `elapsedMs`
    // is one round's call — the model thinking, then calling a tool, then thinking again are
    // several messages in one run — while the status line the user watched counted the whole
    // run. Showing the round's number under the card is what made the two disagree.
    val runElapsed = remember(rowsKey) {
        state.messages.asSequence()
            .filter { !it.runId.isNullOrEmpty() && it.elapsedMs > 0 }
            .groupBy { it.runId }
            .mapValues { (_, rows) -> rows.sumOf { it.elapsedMs } }
    }
    // The conversation's own clock, summed off the very rows [SessionStats] divides its speed
    // by — so 「会话总用时」 and 「会话输出速度」 are two readings of one measurement rather
    // than two tallies that can drift apart. The tally carries the speed but not the total.
    // **整个会话**的墙钟合计（原来是 `state.messages.sumOf{…}`，那只是 12 轮窗口）。
    val sessionElapsedMs = session?.elapsedMs ?: 0L
    // The conversation's spend, split the way the endpoint reports it, for the ring's card: the
    // prompt the cache could not answer, the cache reads, and the output. The card used to give
    // the total and the hit rate only; the user asked for the three parts by name —
    // 「未缓存输入」「缓存读取」「输出」 — and in full, because these are the figures a bill is
    // checked against and a rounded one cannot be checked against anything.
    // **整个会话**的三块花费（原来 `sessionSplitOf(state.messages)` 只算窗口）。
    val sessionSplit = session?.split ?: SessionSplit(0, 0, 0)
    // Which compression records belong under which row. A record names the first and last
    // message it folded away, so it is drawn under the row that holds that last message —
    // the place in the transcript where the model stopped seeing the conversation verbatim.
    val checkpointsByRow = remember(state.checkpoints, visible) {
        val byId = visible.associateBy { it.message.id }
        val indexOf = visible.withIndex().associate { (index, row) -> row.message.id to index }
        val roundOwner = HashMap<String, Int>()
        visible.forEachIndexed { index, row ->
            row.rounds.forEach { round -> roundOwner[round.id] = index }
        }
        val orphanToTop = mutableListOf<ContextCheckpoint>()
        val placed = state.checkpoints.mapNotNull { checkpoint ->
            // **锚点是压缩发生的位置**（anchorId），不是被折叠掉的最后一条（toMessageId）：
            // 按后者定位，压缩块会跑到旧历史里，跑完这一轮就"消失"在屏幕上方（用户反馈）。
            // 旧盘没有 anchorId（空串）⇒ 回落到 toMessageId，行为与以前一致。
            val anchor = checkpoint.anchorId.ifEmpty { checkpoint.toMessageId }
            // **rounds 反查用索引**（审计 1§2）：原来是每个 checkpoint 都
            // `visible.firstOrNull { row.rounds.any { … } }` = O(checkpoints×rows×rounds)，
            // 一张图里几十个 checkpoint 时是纯重复劳动。建一次索引即可。
            val owner = byId[anchor] ?: roundOwner[anchor]?.let { visible.getOrNull(it) }
            if (owner == null) {
                // 锚点落在当前页窗口之外（转录按页后缀加载）：旧行为是**丢掉不画** —— 用户看到
                // 的就是「压缩记录时有时无、过几轮就消失」。改挂到**本页最旧一行**顶上：折叠发生
                // 在它们之前，这里就是折叠历史在当前页的起点，画在这里永远比消失诚实。
                orphanToTop += checkpoint
                return@mapNotNull null
            }
            val id = owner.message.id
            val at = indexOf[id] ?: return@mapNotNull null
            at to checkpoint
        }.groupBy({ it.first }, { it.second })
            .mapKeys { (index, _) -> visible[index].message.id }
            .toMutableMap()
        if (orphanToTop.isNotEmpty()) {
            visible.firstOrNull()?.let { top ->
                placed[top.message.id] = (placed[top.message.id].orEmpty()) + orphanToTop
            }
        }
        placed
    }
    // 压缩的告知就是过程区里那一块（[CompressionBlock]，位置就是它发生的地方），不再往气泡
    // 下面塞一行提示（用户：「不需要这种在卡片下的提示」）。
    val liveStatusText = state.status.ifBlank {
        // No ellipsis: 「正在思考…」 was read as a truncation of something longer. The line
        // already says the state, and the clock that follows says how long it has held.
        "正在思考中"
    }
    // When the live run began, remembered per run so a switch away and back does not
    // restart the caption's clock. Written in an effect, never during composition.
    val runStartedAt = remember { mutableStateMapOf<String, Long>() }
    // Keyed by the run, not by the conversation: a run that has not reported its id yet
    // would otherwise land on the conversation's key and read the previous run's start.
    val runKey = "${state.conversationId}:${state.liveRunId}"
    LaunchedEffect(state.runningHere, runKey) {
        if (state.runningHere) {
            runStartedAt.getOrPut(runKey) { System.currentTimeMillis() }
        } else {
            // The run is over, so its clock has no reader left — and a table that only ever
            // grows would hand the conversation's next run the start time of this one.
            runStartedAt.keys
                .filter { it.startsWith("${state.conversationId}:") }
                .forEach { runStartedAt.remove(it) }
        }
    }
    // Which thinking blocks are open, per conversation. One table for the screen, because
    // the live block and the stored block it becomes are two different composables — a
    // remember inside the block was born fresh on that hand-over, which is why a think
    // never folded when it settled — and because the live row's key was shared by every
    // conversation, so two chats were reading the same switch.
    val reasoningExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val reasoningExpansion = remember(state.conversationId) {
        ReasoningExpansion(state.conversationId, reasoningExpanded)
    }

    // The 「加载更早」 line sits above the transcript as an item of its own, so every row
    // below it is one item further down than its place in [visible].
    val rowOffset = if (page.hasEarlier) 1 else 0
    // The live card (with its progress caption inside) is one item; the error line and the
    // 「加载更早」 line are items of their own. 底锚下这个数只用于「有没有内容」与插入回底。
    // 槽位账本走纯函数（`ListSlots`，带单测）：这两个数原来是**各写一遍**的，而且已经数得不一致
    // —— `feedPrefix` 数了「压缩中」那一行、`itemCount` 没数。今天少一项只影响 `<= 0` 这类判据，
    // 但列表以后再加一行，另一处必然漏（这条链路已经出过三次同类事故）。
    val slots = ListSlots.of(
        hasLiveCard = live || state.runningHere,
        runningHere = state.runningHere,
        compressing = state.compressingSince > 0L,
        hasErrorLine = errorLine,
        visibleRows = visible.size,
        hasLoadEarlier = page.hasEarlier,
    )
    val itemCount = slots.total

    // The user's own messages are the landmarks of a conversation, so the status line can
    // say "turn 2 of 5" and jump between them without the engine knowing anything about it.
    // Steered lines ride inside the turn they steer, so counting them would draw one
    // landmark per interjection — a question plus two steers is still one turn.
    // The indices are visible coordinates with [rowOffset] included; [fedIndexOf] turns them
    // into the bottom-anchored list's feed indices when the strip jumps. The drawn turns are
    // the tail of the conversation, which is why the labels that name them are numbered from
    // [TurnPage.earlier] rather than from one.
    // Keyed on the drawn rows' shape rather than on the rows: this walk reads each row's role and
    // steer flag and nothing else, while `remember(visible, …)` would compare every row — and a
    // row carries its message, screenshot and all. The two ids catch a list that was rebuilt with
    // the same length but different ends (a branch switch, a paged window that moved).
    val turnIndices = remember(
        visible.size,
        visible.firstOrNull()?.message?.id,
        visible.lastOrNull()?.message?.id,
        rowOffset,
    ) {
        visible.indices
            .filter {
                visible[it].message.role == Roles.USER && !visible[it].message.steer
            }
            .map { it + rowOffset }
    }
    // 喂序（底锚倒序，视觉从下往上）：live? → compress-status? → error? → visible 倒序 → load-earlier?。
    // 所以 visible 第 i 行的喂序索引 = 前缀项数 + (visible.size - 1 - i)。
    val feedPrefix = slots.feedPrefix
    fun fedIndexOf(oldVisible: Int) = feedPrefix + (visible.size - 1 - oldVisible)

    // **不在 ChatScreen 组合体里读 `.value`**（issue #26 原因一）：`firstVisibleItemIndex` 滚动时
    // 每跨一个 item 就变一次，一旦在这里读，整个 ChatScreen（1600 行、几十个 Composable 调用）
    // 就要重跑一遍。把读取推迟到 `TurnScrubber` 内部（见下面 `current = { … }`），
    // 跨边界时只有导航条自己重组。用 `remember` 持有 State 对象而不是 `by` —— `by` 一取就立刻读了。
    // **单 item 化之后 `firstVisibleItemIndex` 恒为 0**，读数导航必须改口径：
    // 用「距最新的像素偏移 + 视口高」反查视口顶部是哪一行（`TurnMarks.topFedIndex`，
    // 带单测）。仍然用 `remember` 持有 State、把 `.value` 的读取推迟到 `TurnScrubber`
    // 内部（issue #26 原因一），滚动时只有导航条自己重组。
    val firstVisibleFed = remember {
        derivedStateOf {
            TurnMarks.topFedIndex(
                offsetFromBottom = listState.firstVisibleItemScrollOffset,
                viewport = listState.layoutInfo.viewportSize.height,
                heightsOldestFirst = rowHeights,
            )
        }
    }

    // ===== 滚动与阅读位置：底锚列表，逻辑全在 ChatScroll.kt =====
    // 底锚（index 0 = 视觉最底）下长高向上、底边不动；追底＝回到 index 0；取消＝手指轻扫 4dp。
    // 这一层没有任何"纠正"代码。
    var pressed by remember { mutableStateOf(false) }
    var pressUpAt by remember { mutableLongStateOf(0L) }
    // 用 `mutableStateOf` 持有而不是 `by remember`：守护标记还要被下面的 bring-into-view 规格读到
    // （它不在组合里跑，只能拿状态对象）。
    val pressGuardState = remember { mutableStateOf(false) }
    var pressGuardOn by pressGuardState
    /**
     * 这一次按下里手指移动过没有（issue #14）。
     *
     * 守护协程要用它区分「长按选字」和「按住拖动」：前者要锁住列表，后者锁住就是跟手指打架。
     * 以前 `moved` 只是 pointerInput 里的局部变量，外面看不到。
     */
    var pressMoved by remember { mutableStateOf(false) }
    // **长按才 arm**（跟手性）：400ms 的守护窗口是给「长按选文字」用的（选区建立期间不许列表
    // 自己动），但原来每次按下都 arm ⇒ 每个拖动手势之后 400ms 都有一个逐帧协程 + 冻结 IME clamp，
    // 正好落在惯性滚动期，观感就是「滑完发涩」。
    // 长按阈值：composable 作用域里没有 `PointerInputScope.viewConfiguration`，要取 Local。
    val viewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
    // **组合期不订阅 `pressed`**（审计 1§3）：原来 `LaunchedEffect(pressed)` 的 key
    // 是状态读 —— 每次按下/抬起都把整个 ChatScreen（58 个子组合）重组一遍，反向手势
    // 100ms 内 3~4 次。改成在协程里 snapshotFlow 订阅：读发生在协程，组合期零订阅。
    LaunchedEffect(Unit) {
        snapshotFlow { pressed }.collectLatest { down ->
            if (!down) return@collectLatest
            delay(viewConfig.longPressTimeoutMillis)
            if (!pressMoved) pressGuardOn = true
        }
    }
    // composition local 只能在组合里读：先在组合层捕获，守护协程里用这个引用。
    val setSelectionActive = LocalSetSelectionActive.current
    // **临时诊断（查完就删）**：长按复制会跳位置，且只有本次进程第一次出现。
    // 记下"按下 / 抬手 / 守护撤销"几个时刻的滚动坐标，就能看出那次跳动是程序滚的、
    // 还是列表自己重新锚定的。
    val traceCtx = LocalContext.current

    // **分批填充**（打开/切换会话时 12 轮一次性组合 = 100~150ms，frame.log 实测：
    // 掉帧全在 `scroll=false off=0` 且 dt 108~149ms）。先出最近几轮、之后每帧补一批，
    // 把那 150ms 摊到几帧里。只在「贴底打开」时分批 —— 要恢复具体位置时必须一次填满，
    // 否则偏移对应的内容还没组合出来，恢复会落空。
    val filledRows = remember(visible) { mutableIntStateOf(0) }
    val scroll = rememberChatScroll(
        listState = listState,
        autoFollow = settings.autoFollow,
        pressedNow = { pressed },
        guardNow = { pressGuardOn },
        // `listState` 来自 `rememberLazyListState()`（第 273 行），**全应用共用一个实例**，
        // 切会话时不重建 —— 所以阅读锚点必须自己按会话隔离，否则旧锚会去纠正新会话。
        conversationId = state.conversationId,
    )

    // 贴底时持续记住当前占位；离开底部（开始读历史）就冻住它 ——
    // 输入框高度在流式期间会变（状态行出现/消失），不冻住就会把整列内容推几 dp。
    LaunchedEffect(stackFootprint, scroll.stickToBottom) {
        // 贴底时持续跟随；**另外**只要还没种过（0.dp）就种一次 —— 恢复位置进会话时
        // `stickToBottom` 一开始就是 false，条件永远不成立 ⇒ 整个阅读期间底边距是 0。
        // 贴底时持续跟随；没种过（null）也种一次 —— 恢复位置进会话时 `stickToBottom` 一开始就是
        // false，否则整个阅读期间都取不到值。
        if (scroll.stickToBottom || heldFootprint == null) heldFootprint = stackFootprint
    }
    // **#14 的根因（真机日志 2026-10-21 00:25 坐实，不是推断）**：
    // 长按建立选区后，框架会为「把选中的内容滚进视口」发起一次 bring-into-view，走的就是
    // `LocalBringIntoViewSpec`。日志证据：`guard-on` 在 0/0，365ms 后开始逐帧位移
    // （d=148/137/59/52/44/36/30 … 1），同期请求序列 `want=-621 → -473 → -336 → … → -1`，
    // 合计 621px，与 `lock-would-correct offset-drift=621` 完全吻合；全程 `moved=false`（手指没动）。
    // `size=2507 / container=2510` —— 被选中的消息几乎有整屏高，框架试图把它整个露出来。
    //
    // 修法是**在源头拒绝**，不是事后纠回去（事后纠会先跳一下再跳回来）：
    // 按下期间让「要滚多远」恒为 0，选区的 bring-into-view 就推不动列表。
    //
    // 为什么还要一段「安静期」：请求是逐帧来的，只挡到抬手的话动画还没收完，
    // 之后的帧照样把剩下的距离滚掉。所以每次被挡的请求都续期，请求停止 500ms 后才放行。
    // 选区按需武装（#27/#42③）：guard 武装（长按建立选区）→ true；
    // 滚动开始且手指不在屏上 → false（滚动会取消文本选区，解除安全）。
    val revealBlocked = remember { mutableStateOf(false) }
    val lastRevealRequestAt = remember { mutableLongStateOf(0L) }
    val defaultBringIntoView = LocalBringIntoViewSpec.current
    val revealGuardSpec = remember(defaultBringIntoView) {
        // **严格等价**：不拦的时候，两个成员都原样转发 —— 位移量 `calculateScrollDistance`
        // 与动画规格 `scrollAnimationSpec`。少转发后者会落到接口默认实现上，
        // 只要原规格用的不是接口默认，滚动动画就变了。
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float>
                get() = defaultBringIntoView.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                if (!revealBlocked.value) {
                    return defaultBringIntoView.calculateScrollDistance(offset, size, containerSize)
                }
                // 逐帧续期：被挡的请求还在来，说明这套 reveal 动画没结束，别提前放行。
                lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
                return 0f
            }
        }
    }
    // 安静期：请求停止 500ms（远长于 60fps 的一帧）才放行，避免动画收尾时把剩下的距离滚掉。
    // **组合期不读 `revealBlocked`**：原来 `LaunchedEffect(revealBlocked.value)` 把
    // 一个 State 当 key —— 每次长按武装/解除各重组一次整屏，而这台机器上长按/甩动都在
    // 手势热路径上。改成协程内 snapshotFlow 订阅：读发生在协程，组合期零订阅。
    LaunchedEffect(Unit) {
        snapshotFlow { revealBlocked.value }.collectLatest { blocked ->
            if (!blocked) return@collectLatest
            while (revealBlocked.value) {
                delay(100)
                if (SystemClock.uptimeMillis() - lastRevealRequestAt.longValue > 500L) {
                    revealBlocked.value = false
                }
            }
        }
    }
    LaunchedEffect(pressGuardOn) {
        if (!pressGuardOn) return@LaunchedEffect
        // 按下就开始拦「把选中内容滚进视口」的请求（#14）：框架的这次滚动是为了露出选区，
        // 而用户按住的正是眼前这段文字，露出它没有意义，只会把列表推走。
        lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
        revealBlocked.value = true
        setSelectionActive?.invoke(true)
        // **只在结束时纠一次**（issue #14 建议）：v0.8.238 那版是逐帧纠 + 用数组下标当 item index，
        // 把列表甩到了底部。这里三个关键区别：
        //  · 只在 `finally` 里纠正一次，不逐帧写滚动位置；
        //  · 用 `target.index`（LazyColumn 给的全局 index），不是 `indexOfFirst` 的数组下标；
        //  · 不判断 `pressMoved` —— 选区扩展也是手指移动，那种情况同样需要纠。
        val lockKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        val lockOffset = listState.firstVisibleItemScrollOffset
        try {
            while (pressGuardOn) {
                if (!pressed && SystemClock.uptimeMillis() - pressUpAt >= LONG_PRESS_SETTLE_MS) {
                    pressGuardOn = false
                }
                withFrameNanos { }
            }
        } finally {
            // **只测量，不改行为**：现在无法判断这段纠正在用户报的场景里到底有没有触发。
            // `guard-off` 那行写在 `scrollToItem` 之后，而它是挂起函数 ⇒ 读到的 idx 可能是
            // 纠正前也可能是纠正后，日志自己说不清（issue #14 要求"先定位再改"，正是这个意思）。
            // 这里把决策本身写清楚：纠到了哪儿 / 为什么没纠。查完删。
            // **行为保留、日志去掉**：按下到抬手后 400ms 内，如果列表位置被推走
            // （选区重排造成，手指没动），就关掉追底 —— 而不是把位置纠回去（0.8.242 的教训）。
            if (lockKey != null) {
                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == lockKey }
                when {
                    target == null -> Unit
                    target.index == listState.firstVisibleItemIndex -> {
                        val drift = listState.firstVisibleItemScrollOffset - lockOffset
                        if (abs(drift) > 1 && !pressMoved) scroll.stickToBottom = false
                    }
                    else -> if (!pressMoved) scroll.stickToBottom = false
                }
            }
            // 组合被带走（切会话/退页）也要解冻，否则外壳的 IME clamp 永远停摆。
            setSelectionActive?.invoke(false)
        }
    }
    // 切换会话时给转录一个入场（issue #12）。**不做 crossfade**：那会同时持有两份
    // composition，大会话的内存峰值翻倍。
    //
    // 病根先说清楚：被否掉的三版（320ms 纯 alpha「怪」/ 100ms「太快、恶心」/
    // 260ms + 24dp 位移 + 0.97「还是不行」）**全跑在同一个有缺陷的实现上** ——
    // `snapTo(0f)` 之前先 `withFrameNanos` 等两帧，而这两帧 alpha 仍然是 1，新内容已经按
    // 全不透明画出来了，之后才归零重演一遍。用户原话「切会话你为什么还要显示一下旧会话的页
    // 然后再显示动画」，指的就是这两帧。只要「先满不透明、再归零」还在，任何形态都会被看成
    // 「先闪一下再动」—— 所以那三次差评**不能算对形态的判决**。
    //
    // 修法：Animatable **按会话重建、初值在组合期直接取 0** —— 不经过协程、不等帧，
    // 换会话的第一帧 alpha 必然是 0，不可能先露出内容。（`snapTo` 是 suspend，调用它必然
    // 晚一帧，这就是上一版怎么调数值都改不掉那个毛病的原因。）
    // 冷启动那次给 1：`conversationId` 从 `""` 变成真实 id 不算切会话，骨架屏 → 内容
    // 已经有它自己的过渡，再叠一层淡入就是注释里骂过的「开了个新会话又跳过去」。
    //
    // 形态：按 issue #12 评论 5 的方案 A —— **淡入 + 从下方 24dp 升起 + 缩放落定**，
    // 三样同时跑。纯 alpha 之所以被骂「不优雅、动画效果烂」，是缺位移（没有方向感）
    // 和缺缩放（没有层次），不是时长问题（评论 5 自己纠正过这一点）。
    //  · alpha 0 → 1；
    //  · translationY 24dp → 0：内容从下方升上来；
    //  · scale 0.92 → 1.0：起点取**本仓库自己的**入场约定 —— `ChatComposer.kt:334` 的弹层
    //    就是 `fadeIn + scaleIn(initialScale = 0.92f)`，不另造一套数值；上一版的 0.97
    //    没有来源，且 3% 的形变量肉眼几乎读不出来；
    //  · 210ms 夹在被否的 100 / 320 之间，且贴近本仓库结构性变化的既有时长
    //    （`PROCESS_EXPAND_MS` = 220、`KEYBOARD_ANIM_MS` = 220，见 ChatBlocks/ChatContext）；
    //  · `LinearOutSlowInEasing` 实测为 CubicBezierEasing(0, 0, 0.2, 1)（decelerate，
    //    从 animation-core 1.7.5 字节码核对），本仓库的展开补偿 `ChatBlocks.kt:404` 也用它。
    // alpha / translationY / scale 只写在 graphicsLayer 里 = 只改绘制，不触发重组，
    // 也不动布局与滚动（滚动核心是冻结的）。
    //
    // 一个待真机验证的保留意见：24dp 位移是整块转录在动，而顶栏与输入框钉着不动，
    // 画面可能读成「中间那块在滑」而不是「换了一页」。若如此，把 translationY 那行删掉
    // 即可退回纯「淡入 + 缩放」，其余不受影响。
    //
    // 冷启动判据必须**无条件从 false 起**：这段 `remember` 在 `if (state.loading)` 之外，
    // 首次组合时 conversationId 可能已经是恢复出来的真实 id（不是 ""），拿
    // `isNotEmpty()` 当初始值会把那一次误判成「已经见过」以外的东西，判据就不成立了。
    // BooleanArray 而不是 mutableStateOf：组合期写它不触发快照通知，不会引起重组循环。
    val alphaHistory = remember { booleanArrayOf(false) }
    val switchAlpha = remember(state.conversationId) {
        val coldStart = state.conversationId.isNotEmpty() && !alphaHistory[0]
        if (state.conversationId.isNotEmpty()) alphaHistory[0] = true
        androidx.compose.animation.core.Animatable(if (coldStart) 1f else 0f)
    }
    // **入场层是常驻的，不按 `switching` 条件挂载**（记录一次已经走过的弯路）：
    // v0.8.276 曾按 issue #27 的建议做成"只在 260ms 动画期间挂"，v0.8.284 实测**出现残影**
    // （条件挂载让整个 LazyColumn 的修饰符链在 `switching` 翻转时重建，展开/收起折叠块时可见），
    // 于是回退了。回退时这条注释没跟着改，`switching` 也没删，所以曾经出现过
    // "注释说 260ms、代码却是常驻"的矛盾 —— 现在以**代码为准**：常驻。
    //
    // 常驻的代价是一个 RenderNode 参与合成；省掉它的做法是让层在恒等状态不再改变像素
    // （见下面 `graphicsLayer` 里的 `p >= 1f` 分支），而不是摘掉它。
    // 切换动画是否在跑：只在开始/结束时各变一次（**不在组合期逐帧读**
    // `switchAlpha.value`，那会把整页重组拉到 60fps）。它决定要不要挂离屏层。
    var switchRunning by remember(state.conversationId) { mutableStateOf(switchAlpha.value < 1f) }
    LaunchedEffect(state.conversationId) {
        // 初值已经是 0，这里只负责把它推到 1：从 0 起跑，不需要 `snapTo`，
        // 也就没有「snapTo 生效之前先露一帧」的窗口。
        // 260ms（用户 2026-10-21 定）：210ms 显得赶，260ms 的淡入+上浮更从容；
        // 这是**唯一的时长来源**，改这里就够（位移 24dp / 缩放 0.92 在同一段的 graphicsLayer 里）。
        // 冷启动时 Animatable 初值就是 1f：不播动画，也不挂层。
        if (switchAlpha.value >= 1f) {
            switchRunning = false
            return@LaunchedEffect
        }
        // **先让新会话的内容组合/布局落地，再起动画**（用户：「切换会话的进场动画掉帧和卡顿」）：
        // 原来这里立刻 `animateTo`，而这一帧同时要做整页冷组合 —— 新会话的可见行都要
        // 重新 parseMarkdown（长答案 2~10ms/条）、重新测量，动画的头几帧就和这个撞在一起，
        // 于是 260ms 的动画整体掉帧。等一帧（`withFrameNanos`）让内容先落，
        // 动画从第二帧起跑，帧预算就归它自己。
        // 注意：`switchRunning = true` 仍在等帧**之前**——离屏层与大批量布局步长要在
        // 冷组合那一帧就生效，晚一帧反而更慢。
        switchRunning = true
        androidx.compose.runtime.withFrameNanos { }
        switchAlpha.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
        switchRunning = false
    }
    // **按 item key 锚定阅读位置**（issue #11，取代 #10 的「钉住高度」）。
    //
    // 走过的三条路，都记在这里免得下次再试一遍：
    //  · 逐帧 `dispatchRawDelta(delta)`（0.8.233~235）：条件里 `firstVisibleItemIndex == 0` 与
    //    `!isScrollInProgress` 每帧翻转，补偿一开一关 ⇒ 1~2px 的颤；且会消耗 offset。
    //  · 钉住高度（0.8.236）：高度不变就不抖，但 `heightIn(max)` + 裁剪把后续输出全裁掉 —— 死路。
    //  · 现在：**内容照常长**，只在「停下、不在底部、锚点真的偏了」时用 `scrollToItem` 绝对定位纠一次。
    //    key 是消息 id，不受 index 翻转 / live 开关影响；`scrollToItem` 不消耗 offset，不会累积误差。
    // **锚点必须按会话隔离**（issue #15）：以前是两个全局 `remember`，切到 B 时还拿着 A 的 key。
    // 消息 id 不撞，`?: return` 挡得住；但 `load-earlier` / `live-status:` 这类**非消息 key** 会撞上，
    // 于是拿 A 的位置去滚 B —— #4 的「串味」就这么回来了。
    val anchors = remember { mutableStateMapOf<String, Pair<Any?, Int>>() }
    // **不要在这里读 `anchors`**（性能）：`SnapshotStateMap.get` 是**整表级**失效，而下面那段
    // 「滚动停止时记锚点」会写这张表 —— 一次写入就让整个 ChatScreen 重启组失效（所有可见项
    // 的 content lambda 重跑、live 气泡全量重跑），表现就是「滑到位顿一下」。
    // 这里以前有一行 `val anchorKey = anchors[state.conversationId]?.first`，
    // 全文件**没有任何读取者**（恢复位置用的是 `LaunchedEffect` 里的 `anchors[...]`）—— 已删。
    // **只记「消息行」的 key**（issue #15 的失败原因之一）：首可见项经常是 `load-earlier`、
    // `streaming:<id>`、`live-status:<id>` 这类临时行，它们的 key 在切回来时**找不到对应消息**
    // （`visible.indexOfFirst { it.message.id == saved.first }` 恒为 -1），于是恢复直接掉回底部。
    // 现在记锚点时跳过这些行，取第一个真正的消息 id。
    val messageIds = remember(visible) { visible.mapTo(HashSet()) { it.message.id } }
    // **必须经 `rememberUpdatedState` 进收集器**（issue #16 真机取证，2026-10-21）：
    // 下面那个 `LaunchedEffect(listState)` 只启动一次，闭包里捕获的 `messageIds` 会永远停在
    // **首次组合**那份 —— 而首帧会话往往还没加载完，那时集合是空的 ⇒ 过滤条件永不成立 ⇒
    // 锚点一次都写不上。取证日志里就是「35 次 anchor-eval / 0 次 anchor-set」。
    val messageIdsState = rememberUpdatedState(messageIds)
    // 记锚点：**不能看 `isScrollInProgress`**（issue #11 补充评论）。流式内容长高时 LazyColumn 的
    // 内部重排会被算成「正在滚动」，于是这个值在流式期间一直是 true —— 锚点永远记不下来，
    // 纠正逻辑也就永远检测不到 drift（用户实测：「抖动没了，但位置仍会持续漂移」）。
    //
    // 现在改成「**手指抬起那一刻记**」（issue #16 的建议）。上一版是
    // `snapshotFlow(位置).collectLatest { delay(120); … }` 的 debounce：
    // 位置稳定 120ms 才会写一次。流式期间位置每 40ms 变一次 ⇒ collectLatest 一直取消 ⇒
    // **锚点不会被更新**（它不是"永远是 null"：用户停手之后仍会写一次；但如果他只在流式期间
    // 滚动过，就可能一次都没写上）。用 `pressed` 的下降沿触发，与位置变化频率无关。
    // 锚点在「**手指没按 + 不在滚（含惯性）**」时才记（issue #24）。
    // 上一版只看 `pressed`：松手那一刻就记，但**惯性滚动才刚开始**，还会滚 200px ——
    // 锚点记的是惯性"开始前"的位置，切回当然不对。`combine` 没有 debounce，不会被流式打断。
    LaunchedEffect(listState) {
        combine(
            snapshotFlow { pressed },
            snapshotFlow { listState.isScrollInProgress },
        ) { isPressed, scrolling -> isPressed to scrolling }
            .collect { (isPressed, scrolling) ->
                if (isPressed || scrolling) return@collect
                // 恢复位置的那几帧不记：那时首可见项还是上一个会话留下的布局（issue #15）。
                if (scroll.stickToBottom || scroll.restoring) return@collect
            // **只记消息行**：首可见项常是 `load-earlier` / `streaming:` 这类临时行，
            // 它们的 key 切回来时找不到对应消息（issue #16 的核心）。
            val first = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key in messageIdsState.value } ?: return@collect
            // **偏移量要记「这条消息在视口里的位置」，不是「首可见项的滚动偏移」**（issue #16 二次取证）。
            // 依据（真机日志里两次实测）：`scrollToItem(i, x)` 之后 item i 的 offset 恰好是 `-x`
            // （`idx=0/0` ⇒ `@0`；`idx=0/606` ⇒ `@-606`）。所以锚点记 `-first.offset`。
            // 以前对「锚点被上面临时行压着」的分支写死 0，等于把「这条消息已经被滚过几千 px」
            // 抹成 0 —— 切回来就跳到这条消息的开头，正是用户说的「第一次就偏了」。
            // `coerceAtLeast(0)`：消息在视口起点下方时 offset 为正，回填负值给 scrollToItem 没有意义。
            val offset = (-first.offset).coerceAtLeast(0)
            anchors[state.conversationId] = first.key to offset
        }
    }
    // ─── #17 流式行高度「滞后一帧 + 同帧补偿」 ────────────────────────────────
    //
    // **为什么"看到偏差再纠正"必败**（三版都失败）：流式行每长高 δ，列表在**同一帧的测量**
    // 里就把上面的内容顶上去 δ；任何"测量之后才知道 δ"的纠正都发生在下一帧 ⇒ 永远慢一拍 ⇒
    // 用户看到的是「内容一直在爬」，而不是「跳一下就停」（末态对只是收尾对账抹掉了残差）。
    //
    // **做法**：把流式行的**高度变化推迟一帧**（布局报的是上一帧的自然高度），然后在
    // **帧回调里**（下一帧测量之前）同时做两件事：① 把报出的高度前进到新值；② 把同样的 δ
    // 交给列表 `dispatchRawDelta`。两件事落在**同一帧的测量**里 ⇒ 净位移 0 ⇒ 不漂。
    // 这正是 `RevealHeight` 已验证的铁律（补偿量与高度变化必须同帧生效，真机实测 ±1px）。
    //
    // **不是冻结**：内容照常生长、照常可见，只是高度晚一帧生效（最多裁掉 16ms 宽的一条边缘）。
    // **不是移出列表**：流式行仍在 LazyColumn 里。
    // 只在「用户上翻读历史」时启用；贴底时高度直接跟自然值，行为与以前完全一致。
    val lagNatural = remember { intArrayOf(0) }
    // #17 定位用：流式行在根坐标系里的 y，以及「钉住那一刻的 y」。
    // 只有这两个数才能回答**用户到底看到了什么**：「钉住了没动」还是「钉住了但还在动」。
    // 普通数组（不进快照）⇒ 每帧写也不会触发重组；`onGloballyPositioned` 本来就在布局后回调。
    val liveRowTop = remember { floatArrayOf(Float.NaN) }
    val armedTop = remember { floatArrayOf(Float.NaN) }
    // 状态行（「思考中 · 12.3s」/ 重试提示）的高度。它在**裁剪之外**（要一直可见），
    // 所以钉住值要含它、并且气泡报出的高度要把它扣掉 —— 见 `liveRowLagHeight` 的 `outside`。
    val statusHeight = remember { intArrayOf(0) }
    // ─── #17 修复（v0.8.319）：**读历史时把流式行的布局高度钉住** ──────────────
    //
    // 前面三版（dispatchRawDelta / heightIn(max) / anchorKey+scrollToItem）都死在同一个地方：
    // 「**检测偏差 → 纠正**」追不上流式的**连续推力**。这一版不检测、不纠正 ——
    // 它让流式行在读历史期间**根本不长高**（布局层面），于是上面的内容**一帧都不动**。
    //
    // 做法（issue #17 自己列的方向 1「在布局前把锚点钉住」）：
    //  - `pinnedLiveHeight` = 用户开始读历史那一刻，流式行的自然高度；**此后不再变**。
    //  - 流式行用 `liveRowLagHeight(reported = pinnedLiveHeight, natural = lagNatural)`：
    //    **报的**高度是钉住的那个，**量的**高度永远是自然高度（内容照常长、照常可见）。
    //  - 回到底部（stickToBottom）时把 `pinnedLiveHeight` 清零 ⇒ 高度跟自然值 ⇒ 行为与以前完全一致。
    //
    // 为什么这能治本：
    //  - 漂移的**唯一来源**是「流式行布局高度增长 ⇒ 上面的内容被顶走」。
    //    高度不增长 ⇒ 顶不动 ⇒ 漂移为 0。这不是「追」，是「不发生」。
    //  - 完全不碰滚动位置：不写 `firstVisibleItemIndex/Offset`、不 `dispatchRawDelta`、
    //    不 `scrollToItem`。LazyColumn 的测量与我们的逻辑互不干扰 ⇒ 不会「飞到列表最顶部」。
    //
    // 与「冻结内容」（issue #17 方案 3、v0.8.236 的 heightIn(max)）的区别：
    // 那些方案把**内容**也冻住 ⇒ 用户看不到后续输出（用户当时就否了）。
    // 这里只冻**布局高度**，内容照常流、照常看得到，只是列表不因它而重新排版。
    //
    // 代价：读历史期间，若流式行已经比钉住时高，多出来的部分不占布局高度（内容仍在，
    // 但那一块的下方是空白）。回到底部立刻恢复。**这是"读历史"场景下的合理取舍** ——
    // 用户在那段时间本来就在读旧内容，不是在看新输出。
    var pinnedLiveHeight by remember { mutableIntStateOf(0) }
    // 纯状态机（`LivePinState`，带单测）：这条链连续几版"改了没生效"，两次都是状态机问题
    // （判定值被一次性捕获、解除时 latch 没清）。逻辑移出去之后可以用断言守住。
    val livePin = remember(state.conversationId) { LivePinState() }
    // **切会话必须清零**（评审确证）：`pinnedLiveHeight` 与 pin effect 里的 `pinned` 都不随会话变，
    // 而 `stickToBottom` 是按会话存的 —— A 里钉住 H_A 之后切到 B，若 B 也「不在底部且有活 run」，
    // `lagging` 恒 true ⇒ **不会重新捕获** ⇒ B 的流式行按 H_A 报高（去掉 `minOf` 之后没有钳制，
    // 会留下大片空白或裁掉内容）。
    LaunchedEffect(state.conversationId) {
        pinnedLiveHeight = 0
        scroll.pinActive = false
        livePin.reset()
        // 自然高度也必须清：`lagNatural` 是普通数组，切会话后要到**下一次布局**才被写；
        // 若不清，状态机可能在拿到新值之前先用旧会话的高度钉住（评审指出的顺序洞）。
        lagNatural[0] = 0
        // 状态行高度同理：它是普通数组、只在状态行**存在**时才被写，不清就会拿上个会话/上轮的
        // 幽灵值参与「钉住总值」与「气泡扣减」（评审第三轮 B 段）。
        statusHeight[0] = 0
    }
    // **不做「变短就解钉」的逐帧判断**（第二轮评审确证这个思路错）：220ms 是**时长**不是降幅，
    // 而降幅等于折叠块高度 —— 思考链上限 180dp，收起后 natural/pinned ≈ 40%，必然穿过 60% 采样点
    // ⇒ 每次手动收起折叠块都会解除钉住。真正需要兜底的场景（`RoundRestarted`、一轮结束）由
    // `liveForPin`（活内容清空 ⇒ 解钉）覆盖；高度不足时的空白由下一条「钉住值不超过自然高度」兜住。
    // **钉住的高度**（0 = 不钉）。
    //
    // 捕获时机：**从「贴底」切到「读历史」的那一帧**，把当时的自然高度钉住，之后不再变。
    //
    // 为什么不在每帧更新：每帧更新就等于「高度继续长」⇒ 还在顶 ⇒ 还在漂。
    // 为什么不用 `laggingNow` 做 key 的 LaunchedEffect：`laggingNow` 是 `rememberUpdatedState`，
    // 它的**值**变化不重组，而 key 用的是当时的快照 ⇒ 转换点会漏。
    // 所以直接监听 `scroll.stickToBottom` 这个真正的状态源。
    //
    // **释放时机**（v0.8.319 二次修正）：不是"回到底部"，而是**流式行滚出可视范围**。
    //
    // 原因：钉住 = 流式行的**布局高度**冻结，而内容照常长 ⇒ 超出冻结高度的那部分
    // 在布局里没有位置。流式行在读历史时位于屏幕下方（底锚 + 上翻 ⇒ 它在视口外），
    // 裁掉的部分用户看不见；可一旦它**进入视野**（用户往回滚），裁掉的就是正在显示的
    // 新输出 —— 用户会看到"输出少了一截"。
    //
    // 所以：**钉住期间只要流式行还在视野内就立刻释放**（宁可那一刻跳一下，也不要持续显示被裁的输出）。
    // 跳的那一刻内容整体上移/下移，是"一次性"的；持续的漂移才是用户受不了的。

    // **切会话前把正文解析预热掉**（换会话后第一帧的主要成本就是这十几份冷解析）。
    // 键用「会话 + 消息版本」：同一会话里流式更新不会反复触发；后台线程做，命中后进组合即已就绪。
    // 预热只是把同一份纯函数计算挪出主线程，渲染结果不变。
    LaunchedEffect(state.conversationId, state.messagesRevision) {
        val texts = state.messages.mapNotNull { m -> m.text.takeIf { it.isNotBlank() } }
        if (texts.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.Default) { SettledBlocks.prewarm(texts) }
    }

    LaunchedEffect(visible, scroll.stickToBottom) {
        val total = visible.size
        if (total == 0) return@LaunchedEffect
        // 切换动画的第一帧 alpha 还是 0（看不见内容）：这时一次补 4 行纯属白花一帧的预算，
        // 动画期间先补 2 行，剩下的按 SWITCH_MAX_STEP 在动画里补齐。非动画场合维持原来 4 行。
        val firstChunk = if (switchRunning) 2 else CHUNK_FIRST
        filledRows.intValue = if (scroll.stickToBottom) firstChunk.coerceAtMost(total) else total
        // **按上一帧节奏自适应**：固定每帧补 3 条时，遇到大卡片就是 16~41ms 的超时帧
        // （实测切会话后连续几帧）。上一帧超预算就少补一条、宽裕就多补一条：峰值被压平，
        // 填完的总条数与最终布局完全不变（只是先后节奏）。
        var step = CHUNK_STEP
        var prevFrame = 0L
        while (filledRows.intValue < total) {
            val frameAt = withFrameNanos { it }
            if (prevFrame != 0L) {
                val dtMs = (frameAt - prevFrame) / 1_000_000L
                step = when {
                    dtMs > STALL_FRAME_MS -> (step - 1).coerceAtLeast(1)
                    // **切换动画期间不许加速**（实测结论，见下）：原来只看"上一帧很快"就一帧多补
                    // 几行（最多 5 行），可切换动画本身正在吃 GPU（整条列表带 alpha/scale 的层要
                    // 逐帧合成），这时再叠 5 行的组合+测量，正好把 260ms 的动画压进 14ms 的帧
                    // —— dumpsys gfxinfo 里切会话 p90=14ms、超预算帧占 39%，而滚动只有 0.48%。
                    // 动画期间压到 2 行/帧：多花两帧补完，但每一帧都留在 120Hz 预算内。
                    switchRunning -> (step + 1).coerceAtMost(SWITCH_MAX_STEP)
                    dtMs < STALL_FRAME_MS / 2 -> (step + 1).coerceAtMost(CHUNK_MAX_STEP)
                    else -> step
                }
            }
            prevFrame = frameAt
            filledRows.intValue = (filledRows.intValue + step).coerceAtMost(total)
        }
    }

    val streamingKey = "streaming:${state.conversationId}"
    // 有活内容（正文/思考/工具）才值得钉住 —— 空的行不钉。
    // 正文只存一份（#38）：`streaming` 是派生值、不再单独保存 ⇒ "有没有活内容"看 liveParts。
    // 这个子表达式下面还要用一次，别写两遍 —— 现在它就是 VM 的 liveActive（布尔 flow，
    // distinct 去重）：思考期的 40ms 批次不再把 body 拖进重组。
    val liveForPin by viewModel.liveActive.collectAsState()
    // **`liveForPin` 必须用 rememberUpdatedState 取最新值**（第二轮评审的关键发现）：
    // 它是个普通 `val`，若被 `snapshotFlow` 的 lambda 直接捕获，捕获的是**首次组合**那一帧的值，
    // 而 ChatScreen 常驻、不随会话重建（`SuixingApp.kt:569`）⇒ 首帧没有活内容时它永远是 false
    // ⇒ `snapshotFlow` 恒 false ⇒ **钉住从来不会生效**（这才是「钉了还在动」的真正原因）。
    val liveForPinNow by rememberUpdatedState(liveForPin)
    // **`livePin` 也不能被直接捕获**：`LaunchedEffect(listState)` 的 body 是首次组合那一句
    // lambda，key 永不变 ⇒ 里面拿到的永远是 ChatScreen 第一次组合时的那个实例。
    // 后果：`livePin.reset()` 作用在新建的空实例上（空操作），而 collector 用旧实例跑
    // ⇒ 旧实例 active 时会把上一个会话的高度写回来，正好推翻切会话的复位。
    val livePinNow by rememberUpdatedState(livePin)
    LaunchedEffect(listState) {
        snapshotFlow {
            // 四个量一起进读取集：任一变化都要重新判定（少一个就会出现"清 0 之后不再发一次"）。
            // 第一个是**会话身份**：它决定 `livePin` 是哪个实例，必须参与触发。
            PinSignals(
                chatId = state.conversationId,
                // **arm 迟滞带**（issue #42 补充的振荡环）：pin 的 arm 收缩布局会把用户
                // 弹回 index 0，弹回触发 release，release 又推出去 —— 自激振荡（pin.log
                // 同位置 4 对 arm/release 循环）。要求离底 ≥2 个 item 才算 notAtBottom：
                // 被 arm 弹回 index 0 时不再立刻 re-arm，环断。
                notAtBottom = !scroll.stickToBottom && !scroll.restoring &&
                    listState.firstVisibleItemIndex >= 2,
                hasLiveContent = liveForPinNow,
                pinActive = scroll.pinActive,
            )
        }.collect { signals ->
            val notAtBottom = signals.notAtBottom
            val live = signals.hasLiveContent
            var needWait = livePinNow.update(
                notAtBottom = notAtBottom,
                hasLiveContent = live,
                natural = lagNatural[0],
                outside = statusHeight[0],
            )
            // 高度要到**布局之后**才有；`lagNatural` 是普通数组（不进快照，避免测量期写 state
            // 触发重测），所以这里最多等 30 帧把它拿到 —— 只在状态翻转时发生一次。
            var tries = 0
            while (needWait && tries < 30) {
                withFrameNanos { }
                needWait = livePinNow.update(
                    notAtBottom = notAtBottom,
                    hasLiveContent = live,
                    natural = lagNatural[0],
                    outside = statusHeight[0],
                )
                tries++
            }
            // 等了 30 帧还没量到高度 ⇒ **这一轮不会钉住**（这正是历史上"钉了还是动"的形态之一）。
            if (needWait) {
                android.util.Log.i("suixing-pin", "arm-timeout tries=$tries")
                pinTrace("arm-timeout tries=$tries natural=${lagNatural[0]}")
            }
            val wasActive = pinnedLiveHeight > 0
            pinnedLiveHeight = livePinNow.height
            // 以滚动层的 pinActive 为**唯一真值**（它还要被"到底是否自动打开贴底"的 collector 读）。
            if (scroll.pinActive != livePinNow.active) scroll.pinActive = livePinNow.active
            // **只在翻转时打一行**（#17 验收用）：不逐帧、不写盘。下次连上手机时
            // `adb logcat -s suixing-pin` 就能直接回答"到底钉上了没有、什么时候解除的"。
            val nowActive = pinnedLiveHeight > 0
            if (nowActive != wasActive) {
                if (nowActive) {
                    armedTop[0] = liveRowTop[0]
                    android.util.Log.i("suixing-pin", "arm h=${pinnedLiveHeight} status=${statusHeight[0]}")
                    pinTrace("arm h=${pinnedLiveHeight} status=${statusHeight[0]} top=${liveRowTop[0]}")
                } else {
                    // **位移**：钉住期间流式行在屏幕上挪了多少像素。
                    // 0（或 ±1）＝ 钉住真的把位置定住了；明显非 0 ＝ 还有别的东西在推。
                    // 这一列就是「用户报的还在动」到底发生在哪一层」的判据。
                    val drift = if (armedTop[0].isNaN() || liveRowTop[0].isNaN()) {
                        Float.NaN
                    } else {
                        liveRowTop[0] - armedTop[0]
                    }
                    // **区分解除原因**：这一轮结束时状态行会消失，那一瞬的位移（≈状态行高度）
                    // 并不代表"钉住期间内容在漂"。只有 `streaming`（这轮还活着）时的 drift 才有判读价值。
                    val why = when {
                        !live -> "runEnd"
                        !notAtBottom -> "atBottom"
                        else -> "other"
                    }
                    android.util.Log.i("suixing-pin", "release reason=$why drift=$drift")
                    pinTrace("release reason=$why notAtBottom=$notAtBottom live=$live drift=$drift")
                }
            }
        }
    }    // 门与 RevealHeight 同一套：只在**不在底部**时滞后+补偿；贴底时高度直接跟自然值。
    // 不额外看 `pressed` —— RevealHeight 在手指按着时也照补，那条路已实测 ±1px。
    // **抑制信号必须是"外层真的在冻结高度"**，而不是"用户在读历史"（我引入的不一致）：
    // `LocalLiveRowLag` 用来告诉流式行里的 `RevealHeight`「别自己补，外层统一补」——
    // 但外层只有在**钉住生效**时才冻结高度（`pinnedLiveHeight > 0` ⇒ `scroll.pinActive`）。
    // 两者不等价时（读历史了、但钉住没量到高度/没活内容），流式行里的折叠块会**两边都不补** ⇒ 展开时跳。
    val laggingNow by rememberUpdatedState(scroll.pinActive)
    /** 用户是否在读历史（不在底部、也不在恢复位置）。 */
    val readingHistory = !scroll.stickToBottom && !scroll.restoring
    /**
     * **滚动锚定**：流式行就地正常长高，长出来的那一点用滚动位置补回去，
     * 于是"内容照常长、你读的那一行不动"。
     *
     * 与之前几版的差别：补偿量由**实测位移**（`liveRowTop`）驱动，不是按 item 高度推算，
     * 所以不依赖 index/offset 的换算；方向若猜错会在下一帧被观测到并自动翻转。
     */

    // #17 的历史结论（别再重试同类方案）：真机 d6.log 754 帧显示**高度与位移不在同一帧** ——
    // 帧 N 高度 +84、偏移 0；帧 N+1 高度 0、偏移 +84。所以「每帧改写滚动位置」既追不上，
    // 又会与 LazyColumn 自己的 `updateFromMeasureResult` 互相覆盖。真解法只有结构性的：
    // 阅读态下让流式行**不参与列表测量**（现做法：停靠到输入框上方）。

    // #17 的逐帧埋点已经用完，**整段删掉**（2026-10-23）：它原来是一个常驻的
    // `while (true) { withFrameNanos {} }` —— 每帧跑一次，而且「不在底部」（正是用户往上滑
    // 的时候）会往 `drift17.log` 写盘。实测表现就是「往上滑再快速往下滑出现白块、滑动卡」。
    // 结论已记录：① 读历史时钉住布局高度有效；② 底边距随输入框高度变化会把内容推几 dp，
    // 用 heldFootprint 冻住即可。需要再查时用 systrace/Perfetto，不要再往主线程加逐帧 IO。


    // **设计 A 的每帧对账：暂时停用**（issue #18 之后）。
    //
    // 它每帧都在 `dispatchRawDelta` —— 那是目前唯一还会在「用户没动」时写滚动位置的地方。
    // v0.8.238 已经证明：这类"看到偏差就纠"的逻辑一旦判断有误，表现就是乱飘，而且无法归因。
    // 现在把它整段停掉，让列表回到「只有用户在滚、只有底锚在跟」的状态：
    // 静止时没有任何东西会动它，先把「不乱飘」这个基线拿回来，再谈漂移怎么修。
    //
    // 流式上翻的漂移因此**回到未修状态**（#11 仍未解决），但那是已知的、可接受的代价 ——
    // 比"静止也乱飘"轻得多。下一版若要重做，方向是 issue #17 说的：不要"检测偏差→纠正"，
    // 而是在布局前把锚点钉住（例如给流式行预留高度 / 用 LazyColumn 的 key 锚定重建）。
    // 一轮结束（实时行 → 存储行）那一帧里同时发生四件事：实时行被替换、状态行消失、指标行出现、
    // 让位 12→16dp。贴底协程跑在布局定型之前，读到的还是旧高度 ⇒ 结束后停在离底部差一截的地方
    // （用户：「输出完，滚动位置还是不对，应该跟空闲一样」）。等两帧让新布局落地，再补贴一次。
    LaunchedEffect(live, itemCount) {
        if (live || itemCount <= 0 || !scroll.stickToBottom) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (scroll.stickToBottom && !pressed) scroll.toBottom()
    }
    // 切换会话前存位置；打开时有记忆回记忆、没有回底。
    var lastConvId by remember { mutableStateOf(state.conversationId) }
    // 把当前会话 id 同步给滚动层：它在**每次滚动停下**时直接写 `spots[该会话]`，
    // 不依赖"切走那一刻"再存（那时 id 已经变了，会存错会话）。
    SideEffect { scroll.conversationId = state.conversationId }
    LaunchedEffect(state.conversationId) {
        if (lastConvId != state.conversationId) {
            // **离开时贴着底，就把位置彻底忘掉**（真机日志 2026-10-21）：用户先在 A 里上翻到 X，
            // 然后点「回到最新」贴到底，再切走 —— 贴底时锚点不再更新，X 一直留着，
            // 切回来就跳回"点追底之前"的位置。`anchors` 在这里清，`spots`/`lastSeen` 由
            // `saveSpot` 内部一起清（它拿到的是"离开的那个会话"的跟底状态）。
            if (scroll.wasFollowing(lastConvId)) anchors.remove(lastConvId)
            scroll.saveSpot(lastConvId)
            lastConvId = state.conversationId
        }
    }
    // **不能是 `rememberSaveable`**（issue #15）：它把值写进 SaveableStateRegistry，切走再切回同一个
    // 会话时 registry 会把 `true` 恢复回来 ⇒ `if (openedPinned) return` 直接跳过恢复 ——
    // 于是「切回 A，位置不是离开时的地方」。#4 分析里的「链路 3」，当时没修。
    // 换成普通 `remember(key)`：切走时实例丢弃，切回是全新的 `false`，恢复逻辑会真的跑一次。
    // **也不能放进 `LaunchedEffect` 的 key**（issue #16 根因 1）：函数体第一行就把 `openedPinned`
    // 写成 true，它一在 key 里，这一写就让旧协程在第一个挂起点被取消 —— 于是下面「按 key 的精确
    // 校正」几乎从不执行，真正决定位置的只剩依赖 `feedPrefix` 的粗定位。它只是闸门，不当 key。
    var openedPinned by remember(state.conversationId) { mutableStateOf(false) }
    // **`itemCount` 不能进 key**（issue #16 的最后一环）：恢复流程里有 `withFrameNanos` 等挂起点，
    // 而切会话/内容落地那一刻 itemCount 一定会变 —— key 一变协程立刻被取消，正好停在
    // 「粗定位已做、按 key 精确校正还没做」的中间态，表现就是「恢复到大差不差的位置、但不是原位」。
    // 改成在体内等 itemCount 就绪，协程只随 conversationId 重建。
    // **`itemCount` 是普通 Int**（组合作用域的 `val`）——直接写进 `snapshotFlow` 会把首帧那个值
    // **冻成常量**，于是冷路径（`loading = true` + 空 messages，磁盘补读晚一帧以上）下
    // `first { it > 0 }` 永远等不到，**整段恢复位置被静默跳过**（无崩溃、无日志）。
    // 同一事故在本文件 `messageIdsState` 那里已经修过一次，这是第二处。
    val itemCountNow by rememberUpdatedState(itemCount)
    // **`visible` 与 `feedPrefix` 同样是普通 `val`**（`visible` 是 remember 出来的 List、
    // `feedPrefix` 是 Int）——这个 effect 的 body 只在切会话时创建一次，捕获的是**切换那一帧**的值。
    // 冷路径（未命中 5 条 LRU）那一帧 `messages` 还是空 ⇒ `visible` 是空表 ⇒
    // `visible.indexOfFirst{…}` 恒 -1 ⇒ 粗定位整段跳过、精确校正也找不到 key ⇒ **退回底部**。
    // 这是同一事故的第三处（前两处：`messageIdsState`、`itemCount`）。
    val visibleNow by rememberUpdatedState(visible)
    val feedPrefixNow by rememberUpdatedState(feedPrefix)
    LaunchedEffect(state.conversationId) {
        if (openedPinned) return@LaunchedEffect
        snapshotFlow { itemCountNow }.first { it > 0 }
        if (openedPinned) return@LaunchedEffect
        openedPinned = true
        // 显式对齐一次：下面的读写都按"当前会话"取键，不能等 SideEffect 的时序（issue #4）。
        scroll.conversationId = state.conversationId
        // **以 `anchors` 为准**（issue #15）：它是按会话存、按消息 id 记的，切走时不用再存一次
        // （`ChatScroll.spots` 那条路要求「切走那一刻」正好读对，容易被时序坑到）。
        // 两条都查，是为了兼容本次改动之前已经存进 spots 的位置。
        val saved = anchors[state.conversationId] ?: scroll.takeSpot(state.conversationId)
        val savedKey = saved?.first
        if (savedKey == null) {
            // 没有记忆就回底 —— 这是**唯一**该回底的分支（issue #16）。
            scroll.stickToBottom = true
            scroll.toBottom()
            return@LaunchedEffect
        }
        // 恢复期间挂上闸：`scrollToItem` 之后布局还没定型，`canScrollBackward` 会短暂为假，
        // "滚到底就把贴底打开"的 collector 会立刻把下面这个 false 改回 true（链路 4）。
        scroll.restoring = true
        try {
            scroll.stickToBottom = false
            scroll.spotKey = savedKey
            scroll.spotOffset = saved.second
            // ① 粗定位：只是起点，允许差一两项。**用 `*Now` 里的最新值**，不能用 effect 创建时
            // 捕获的那一份（否则冷路径下 `visible` 是空表、`feedPrefix` 也是旧值）。
            // 决策走纯函数（`RestoreTarget`，带单测）：换算与"什么时候放弃"都有断言守着 ——
            // 这条链路出过三次同类事故，症状都是"位置没恢复、退回底部"，且不打日志。
            val targetFed = RestoreTarget.fedIndex(
                visibleIds = visibleNow.map { it.message.id },
                savedKey = savedKey as? String,
                feedPrefix = feedPrefixNow,
            )
            val approx = visibleNow.indexOfFirst { it.message.id == savedKey }
            android.util.Log.i(
                "suixing-pos",
                "restore chat=${state.conversationId} key=$savedKey approx=$approx rows=${visibleNow.size}",
            )
            pinTrace("restore key=$savedKey approx=$approx rows=${visibleNow.size} prefix=$feedPrefixNow")
            if (targetFed != null) {
                // 单 item 化：item 0 就是整页，`saved.second`（原先的 item 内偏移）
                // 正好等于「距最新端多少像素」⇒ 按它滚即可，比按 key 查索引更准。
                listState.scrollToItem(0, saved.second)
                withFrameNanos { }
            }
            // ② 精确校正：按 **key** 在 layoutInfo 里找全局 index（issue #16 第三步）。
            //    `target.index` 是 LazyColumn 自己给的，不需要任何 feedPrefix 换算 ——
            //    存/取时 prefix 不同（流式开始/结束）也不会影响最终位置。
            withFrameNanos { }
            val target = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == savedKey }
            if (target != null) {
                listState.scrollToItem(target.index, saved.second)
                withFrameNanos { }
                // 把实际落点写回锚点：这次恢复之后的"当前位置"就是它（下次切走时若没再滚过，
                // 用的就是这一份）。
                anchors[state.conversationId] = savedKey to saved.second
            } else {
                // key 完全找不到（消息被删 / 分支切换）：只能回底，别停在随机位置。
                scroll.stickToBottom = true
                scroll.toBottom()
            }
        } finally {
            scroll.restoring = false
        }
    }
    // 外观 → 气泡宽度. Measured against the screen, which is what the setting promises
    // in its own words: 标准 (0.94) stays wider than the transcript's own 16dp margins,
    // so the default leaves every card exactly as wide as it has always been, and only
    // 窄 pulls the reply in.
    val bubbleMaxWidth = with(LocalConfiguration.current) { screenWidthDp.dp } *
        settings.bubbleWidthFraction

    fun jumpToLatest() {
        scope.launch {
            scroll.stickToBottom = true
            if (itemCount > 0) {
                scroll.toBottom()
            }
        }
    }

    // 顶部提示条已移除，这里不再需要倒计时收起它的定时器（失败原因画在转录里的红色回执卡上）。
    // 文件路径点击 → 弹菜单（复制 / 让随行读取），LINK-CLICK-SPEC §3.2 一期。回调经 LocalPathClick
    // 下传，四个 MarkdownText 调用点与中间各层组件都不用加参数。lambda 用 remember 稳定引用：否则
    // 每次重组新建实例会让 InlineText 的 remember(pathClick) 失效、流式时每帧重排文本。
    // 内部 Scaffold 未重新缩进——重排 670 行是纯噪音 diff，Kotlin 不依赖缩进。
    var pathMenuPath by remember { mutableStateOf<String?>(null) }
    val onPathMenu = remember { { path: String -> pathMenuPath = path } }
    CompositionLocalProvider(LocalPathClick provides onPathMenu) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // No topBar slot: the header floats over the transcript as three discs, so the
        // scaffold must not reserve a strip for it — content scrolling behind the
        // buttons is the whole point of the floating header.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Light mode fades a whisper of brand colour into the bottom of the canvas, the
        // way the reference apps tint their own surface; dark stays flat because the same
        // gradient over #0F1412 bands on OLED.
        val canvas = if (LocalDarkTheme.current) {
            SolidColor(MaterialTheme.colorScheme.surface)
        } else {
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                ),
            )
        }
        // The top fade layer needs one solid colour: the canvas brush's own top stop.
        val canvasColor = MaterialTheme.colorScheme.surface
        // The header is a real bar again: it owns the status bar strip and the transcript
        // starts below it, so nothing scrolls under a translucent band of controls.
        // The transcript and the jump button share one box: the button belongs to the
        // reading area, not to the composer, so it must not sit under the pill.
        // **深色下不要再刷一遍整屏**（重复渲染清理）：`Scaffold(containerColor = background)`
        // 已经把整屏涂成 `background`，而深色主题里 `background` 与 `surface` 是同一个色值
        // （`theme/Color.kt` 都是 `0xFF0F1412`）⇒ 这层 `background(canvas)` 在深色下是纯重复绘制，
        // 每帧多一次全屏填充、像素完全不变。
        // 浅色下 `canvas` 是带一点品牌色的竖向渐变（不是纯色），必须保留。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (LocalDarkTheme.current) Modifier else Modifier.background(canvas))
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (state.loading) {
                    // Nothing of the conversation is known yet, so none of it is drawn —
                    // least of all the empty state, which is what made a cold start look
                    // like it had opened a new chat and then jumped to the real one.
                    LoadingSkeleton()
                } else if (visible.isEmpty() && !live) {
                    // Outside the list on purpose: an empty conversation recentres in
                    // whatever space the keyboard leaves, instead of the list's top
                    // padding shoving the hero off the top of the screen.
                    //
                    // **新会话页有它自己的底部让位**（用户：「新会话页为什么要和聊天页重复」）：
                    // 聊天页的 `stackFootprint` 里带着 12/16dp 的"转录与输入框之间的空气"，
                    // 那是转录需要的，空状态不需要 —— 照搬它就会在输入框上方多出一段空白。
                    // 这里只保留输入框的**真实占位**：内容高度 + 它自己的下内边距 + 手势条。
                    val heroBottom = with(LocalDensity.current) {
                        composerH + COMPOSER_BOTTOM_PAD + navBarPad(heldIme).toDp()
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = with(LocalDensity.current) {
                                    WindowInsets.statusBars.getTop(this).toDp()
                                } + 60.dp,
                                bottom = heroBottom,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyHero(onPick = { setDraft(text = it) }, assistant = assistant)
                    }
                } else {
                    CompositionLocalProvider(
                        LocalTypewriter provides settings.typewriter,
                        // 不在底部时展开要"向下展开"（块顶边钉住），需要摸到主列表状态。
                        LocalChatListState provides listState,
                        // 按下期间拦住「把选中内容滚进视口」的请求（issue #14 的修法，见上面的说明）。
                        LocalBringIntoViewSpec provides revealGuardSpec,
                    ) {
                    LazyColumn(
                        state = listState,
                        // **底锚**（reverseLayout + 数据倒序喂）：index 0 就是视觉最底，最后一张卡片
                        // 长高向上、底边不动 ⇒ 不再需要任何逐帧同步滚动。旧底锚失败在它还在用
                        // dispatchRawDelta 逐帧纠正，两处抢同一份列表，才"乱飞/抖/拉回"。
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            // 常驻入场层（0.8.284 回退）：条件挂载会导致本列表的布局修饰符链在
                            // `switching` 翻转时重建，展开/收起折叠块时出现残影（用户实测）。
                            //
                            // **所以这里不摘层，只在恒等状态时让它不干活**（#27）：
                            // `graphicsLayer` 的 lambda 是**绘制阶段**才读快照的，动画结束
                            // （p == 1f）后把 alpha 等写回默认值即可 —— 层还在（不会重建修饰符链、
                            // 不会有残影），但不再改变任何像素，合成器可以直接跳过它。
                            // 动画那 260ms 内行为完全不变。
                            // **常驻，不按 switchRunning 条件挂载**（0.8.284 的定案；此前注释写
                            // "常驻"、代码却挂在 `if (switchRunning)` 里，两者矛盾 —— 以注释为准）。
                            // 条件挂载会让整条修饰符链在开关翻转时**重建** ⇒ 重新测量 + 重新挂
                            // 离屏层，那一下正是切换会话掉帧的来源之一。
                            // 常驻的代价是一个 RenderNode 参与合成；省掉它的做法是让层在恒等
                            // 状态不改变任何像素（下面 p >= 1f 直接返回），而不是摘掉它。
                            .graphicsLayer {
                                val p = switchAlpha.value
                                if (p >= 1f) return@graphicsLayer
                                alpha = p
                                translationY = (1f - p) * 24.dp.toPx()
                                val sc = 0.92f + 0.08f * p
                                scaleX = sc
                                scaleY = sc
                            }
                            // 手指按下只干两件事：通知外壳冻结 IME clamp（选择期间键盘别收）；
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(PointerEventPass.Initial)
                                        if (down.changes.none { it.pressed }) continue
                                        val downY = down.changes.first().position.y
                                        val downAt = SystemClock.uptimeMillis()
                                        pressed = true
                                        // **不在这里 arm 守护**（跟手性）：原来每次按下都置 true，而
                                        // 只有「没移动且短于长按阈值」才会立刻撤销 ⇒ **任何拖动手势之后
                                        // 守护都会保持 [LONG_PRESS_SETTLE_MS]（400ms）**，正好盖住整段
                                        // 惯性滚动：这期间有一个逐帧协程在跑，还冻结着外壳的 IME clamp。
                                        // 现在改由下面的 `LaunchedEffect(pressed)` 在**真的到长按**时才 arm。
                                        pressMoved = false
                                        // **但 reveal 拦截要在按下瞬间就开**（长按回归根因，实测 lp_trace.log）：
                                        // 文本选区的 bringIntoView 比长按超时早 ~28ms 到达，头 5 条放行请求
                                        // 把列表推走 228px —— 等 guard-on 再拦已经晚了。上面注释否掉的是
                                        // 「arm 整套守护」（逐帧协程 + IME 冻结，会盖住惯性滚动）；
                                        // 这里只置 revealBlocked 标志（零成本、只拦 reveal 请求），
                                        // 识别为滚动时立刻解除 —— 与那条教训不冲突。
                                        revealBlocked.value = true
                                        lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
                                        var moved = false
                                        var released = false
                                        while (!released) {
                                            val next = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = next.changes.firstOrNull()
                                            // **任一方向**超过阈值就停跟。只认"手指上移"是错的：顶锚列表里
                                            // 读上面的内容是**手指下移**（把内容拉下来），那个手势从不触发
                                            // 取消 ⇒ 手指一松追底就重跑、内容被贴回底部（用户：「轻微滑动
                                            // 一点点，就被锁下来了」）。
                                            val dy = (change?.position?.y ?: downY) - downY
                                            // `!moved`：`stickToBottom` 的 setter 是 `SnapshotStateMap.put`，
                                            // 不做等值短路，而读它的地方就在 ChatScreen 自己的组合体里
                                            // （底边距 / CompositionLocal）⇒ 每个 move 事件写一次 =
                                            // 一次拖动写 60~120 次。只在本次手势首次越过阈值时写。
                                            if (!moved && change != null && change.pressed &&
                                                (dy > CANCEL_FOLLOW_PX || dy < -CANCEL_FOLLOW_PX)
                                            ) {
                                                scroll.stickToBottom = false
                                                moved = true
                                                pressMoved = true
                                                // 滚动不是选字：解除 reveal 拦截
                                                revealBlocked.value = false
                                            }
                                            released = next.changes.none { it.pressed }
                                        }
                                        pressed = false
                                        pressUpAt = SystemClock.uptimeMillis()
                                        // 短按/拖动都不需要撤销：守护从没 arm 过（见上面的说明），
                                        // 只有**长按**那条路才会打开它，并由 `LaunchedEffect(pressGuardOn)`
                                        // 里的 400ms 结算逻辑关掉。
                                    }
                                }
                            },
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            // 顶边：悬浮顶栏占着状态栏那一条。
                            top = with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            } + 76.dp,
                            // *composer 的让位走 contentPadding，不走列表项**（真机日志实锤：
                            // reverseLayout 下 top/bottom 的**视觉**归属不变（Compose 只是把
                            // before/after 的内部名对调：before=bottom padding），所以让位仍在
                            // 输入框这一侧。
                            // 读历史期间用冻结值（见 heldFootprint）。
                            // 没种过就退回当前值（**不要退回 0.dp**，那会让恢复位置被推移一次）。
                            bottom = if (scroll.stickToBottom || !state.runningHere) {
                                // **没有 run 时永远用实时值**（真机反馈：生成结束后滑到底，
                                // 按住期间最底卡片与输入框的间距不是 16dp，松手才恢复 ——
                                // 因为按压保护强制 stickToBottom=false，吃到了「运行中」
                                // 冻结的旧 heldFootprint。冻结只为流式期间输入框高度变化
                                // 不推内容；没有 run 时高度根本不会变，冻结只剩这个 bug）。
                                stackFootprint
                            } else {
                                heldFootprint ?: stackFootprint
                            },
                        ),
                        // **内容不满一屏时贴顶**（issue #19，用户：「新会话气泡就应该在上面，然后再流式
                        // 长下来，旧内容不就往上推了」）。底锚（reverseLayout）已经负责"满了之后"：
                        // index 0 钉在底部、新内容向上长、旧内容被推上去；arrangement 只在内容不满一屏时
                        // 参与，所以这里改一个词就够，不需要混合判断。
                        //
                        // 风险（issue 自己提的）：当年从 Top 改回 Bottom，注释写的是「短对话被顶到上面、
                        // 最后一张卡片长高就还是往下长」—— 那正是用户现在要的行为，所以大概率是当年
                        // 判断反了。若满屏那一刻有跳变，退回 `Alignment.Bottom` + offset 方案。
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                    ) {
                        if (live) {
                            // Keyed by conversation: the live row keeps its slot for as long
                            // as it is on screen, and a switch used to hand the next chat
                            // whatever the previous one had left in that slot.
                            // 贴底时它照旧在列表里：这时内容往上长正是你想要的。
                            if (live) {
                                    item(key = "streaming:${state.conversationId}") {
                                        // 流式行（喂序 index 0）每长高一点，上面的内容就被顶上去一点 ——
                                        // 往上翻着读旧内容时这是最烦人的漂移。**内容照常长**（issue #11：
                                        // 裁剪是死路），位置由上面那段 key 锚点纠正，这里只负责量高度。
                                        CompositionLocalProvider(LocalLiveRowLag provides laggingNow) {
                                        Column(Modifier.fillMaxWidth()) {
                                            // **只把气泡**放进「钉住 + 裁剪」里：状态行（思考中 · 12.3s）在自然内容的
                                            // 最底部，若跟气泡一起被裁，钉住期间整行连 spinner 都会消失（评审确证）。
                                            //
                                            // 修饰符顺序：`clipToBounds` 取的是**内层**尺寸 —— 必须写在
                                            // `liveRowLagHeight` **之前**（更外层）才按"报出的高度"裁；
                                            // 反过来它拿到的是内容自然高度，等于一条裁不到任何东西的 clip。
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clipToBounds()
                                                    .liveRowLagHeight(
                                                        // 读历史时钉住：高度不增长 ⇒ 上面的内容不动。
                                                        // 贴底时 0 ⇒ 直接用自然高度（行为与改动前完全一致）。
                                                        // 实验开关：false 时完全不钉。
                                                        reported = if (PIN_LIVE_HEIGHT) pinnedLiveHeight else 0,
                                                        natural = lagNatural,
                                                        outside = statusHeight,
                                                    )
                                                    // #17 定位用：记下这一行在屏幕上的 y（普通数组，不进快照）。
                                                    // 钉住期间它与 `armedTop` 的差值就是"用户看到的位移"。
                                                    .onGloballyPositioned { liveRowTop[0] = it.positionInRoot().y },
                                            ) {
                                            AssistantRow(
                                            // **不能给这个映射套 remember**（0.8.36x~0.8.405 瞬移的根因，
                                            // 机制见 LivePartsFreezeTest）：liveParts 的元素是「同一对象只追加」
                                            // （GrowableText），新旧列表逐元素 === 相等 ⇒ 列表 equals 恒 true
                                            // ⇒ remember 的 key 永远命中缓存 ⇒ 映射冻结 —— 屏幕只在列表结构
                                            // 变化（新块诞生）时刷新一次，该块积累的全部文本一次性放出。
                                            // parts 只有几个元素，直接映射，代价可以忽略。
                                            parts = state.liveParts.mapNotNull { part ->
                                                    when (part) {
                                                        is LivePart.Thinking ->
                                                            if (settings.showReasoning) {
                                                                Part.Thinking(
                                                                    text = part.text,
                                                                    tokens = part.tokens,
                                                                    elapsedMs = part.elapsedMs,
                                                                    // The run this block belongs to:
                                                                    // the saved message carries the
                                                                    // same id, so the block keeps its
                                                                    // identity when the live row is
                                                                    // replaced by the stored one.
                                                                    runId = state.liveRunId,
                                                                )
                                                            } else {
                                                                null
                                                            }

                                                        is LivePart.Tools ->
                                                            Part.Tools(part.calls.map(ToolLine::of))

                                                        is LivePart.Text -> Part.Text(part.text, part.bornAtMs)

                                                        // The stored copy of this line is hidden for as
                                                        // long as the run streams, so the live part is
                                                        // what puts it on screen the moment it is handed
                                                        // over. Same widget either way, which is why the
                                                        // bubble does not move when the run settles.
                                                        is LivePart.Steer -> Part.Steer(part.message)

                                                        // 重试留痕：这一轮的落盘消息在流式期间是藏起来的，
                                                        // 只有这里画了，用户才不用等整轮结束才知道刚才重试过。
                                                        is LivePart.Trace -> Part.Trace(part.note)

                                                        // 压缩块：运行中就画在它发生的位置，不再用气泡下面
                                                        // 那一行提示（用户：「在哪个部分进行的就在哪个部分
                                                        // 渲染」——思考 → 工具 → 压缩 → 继续）。
                                                        is LivePart.Compress -> Part.Compress(part.checkpoint)
                                                    }
                                                },
                                                streaming = true,
                                                // The live bubble is the persona's reply too:
                                                // without these two it fell back to the brand name
                                                // and feather while every stored bubble showed the
                                                // persona.
                                                name = assistant?.label ?: "随行",
                                                face = assistant?.face ?: "",
                                                avatarPath = assistant?.avatarPath ?: "",
                                                runningTool = state.runningTool,
                                                // **流式分支里不用它**（唯一的读取点是
                                                // `if (!streaming && …)` 那条 note）—— 传 null，
                                                // 否则 stats 每变一次都让整棵 live 气泡（Markdown 全块 +
                                                // 思考内层列表 + 工具行）重跑，而这时根本没人读它。
                                                liveStats = null,
                                                contextWindow = contextWindow,
                                                expandTools = settings.expandTools,
                                                expandReasoning = settings.expandReasoning,
                                                reasoningMaxHeight = settings.reasoningHeight.dp,
                                                followReasoning = settings.followReasoning,
                                                bubbleMaxWidth = bubbleMaxWidth,
                                                reasoningExpansion = reasoningExpansion,
                                            )
                                            }
                                            // 上 12dp：气泡白底 → 状态行顶。下：让位恒定 12dp（见 stackFootprint）。
                                            if (state.runningHere) {
                                                RunStatusLine(
                                                    text = liveStatusText,
                                                    startedAt = runStartedAt[runKey] ?: 0L,
                                                    // 压缩不再占用这一行（它有自己的过程块），所以时钟一直走。
                                                    timed = true,
                                                    // 量高度给外层用：状态行在裁剪之外，钉住值要含它、
                                                    // 气泡报出的高度要把它扣掉（见 `liveRowLagHeight` 的 `outside`）。
                                                    // 写普通数组，不进快照 ⇒ 不触发重组。
                                                    // **量在 padding 之前**：`outside` 要代表"状态行占掉的整段高度"
                                                    // （含 12dp 间距），否则气泡扣减与 item 总高度天生差 12dp。
                                                    modifier = Modifier
                                                        .onGloballyPositioned {
                                                            statusHeight[0] = it.size.height
                                                        }
                                                        .padding(start = 4.dp, top = 12.dp, bottom = 0.dp),
                                                )
                                            }
                                        }
                                        }
                                    }
                            }
                        } else if (state.runningHere) {
                            // Sent, not yet answered: the line holds the place the bubble is
                            // about to fill, so the reply lands where the user is already
                            // looking. It is also the only caption the user sees while the
                            // model is still thinking, which is why its clock starts at the
                            // send rather than at the first token.
                            item(key = "live-status:${state.conversationId}") {
                                RunStatusLine(
                                    text = liveStatusText,
                                    startedAt = runStartedAt[runKey] ?: 0L,
                                    timed = true,
                                    // 上下都 12dp：项间距已是 12dp，无需再 lift（原 lift 是从 16 里吃 4）。
                                    modifier = Modifier
                                        .padding(start = 4.dp, top = 0.dp, bottom = 0.dp),
                                )
                            }
                        }
                        // 没有 run 时手动压缩：状态行说「正在压缩上下文 · 用时 xx」。
                        // 没有这一行，点了压缩屏幕上什么都没有（用户：「啥响应也没有」）。
                        if (!state.runningHere && state.compressingSince > 0L) {
                            item(key = "compress-status:${state.conversationId}") {
                                RunStatusLine(
                                    text = "正在压缩上下文",
                                    startedAt = state.compressingSince,
                                    timed = true,
                                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
                                )
                            }
                        }
                        // 没有回执的实时失败（发送阶段错误等）画在最后一轮下面；有回执的失败由
                        // 回执自己跟在那一轮后面（内联），同文案不重复画。
                        if (errorLine) {
                            item(key = "error:${state.conversationId}") {
                                RunReasonLine(state.error.orEmpty())
                            }
                        }
                        // `asReversed()` 每次调用都返回一个**新包装对象**；`visible` 本身按
                        // revision 记忆，所以这里记住反转结果就够了。
                        // **contentType 不能省**（滚动卡顿，用户实测「助手→用户→助手」区每遍都卡）：
                        // 列表混着助手卡/用户气泡/系统回执/工具卡，没有 contentType 时 Compose
                        // 在不同形状的行之间复用组合槽 = 类型一换就整槽拆了重建。
                        // key 管身份，contentType 管复用 —— 两个都要。
                        // **整页装进单个 item**（滚动卡顿的结构解，方案 A）：`items()` 让每张卡
                        // 在被滚到视口时才第一次组合+排版（实测 6~8ms，跨 8.3ms 预算 ⇒ 每秒
                        // 7~10 次掉一拍，issue #43）。一页 12 轮（PAGE_TURNS），装进一个 item 后
                        // 永远在组合里 ⇒ 进入视口成本 0。流式内容仍在**独立** item
                        // （`streaming:`）里，每 token 的重组范围不变。`visible` 是正序
                        // （旧→新）：本 item 底边贴视口底 ⇒ 最后一行（最新）落在底部。
                        // 行间距从 LazyColumn 的 arrangement 搬进这里的 Column。
                        item(key = "transcript") {
                            val gapPx = with(LocalDensity.current) { 12.dp.roundToPx() }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val filledFrom =
                                (visible.size - filledRows.intValue).coerceAtLeast(0)
                            visible.forEachIndexed { visibleIndex, row ->
                            if (visibleIndex >= filledFrom) {
                            // One placeable per item: the list's own 16dp spacing applies
                            // between the placeables of a single item as well, which is what
                            // held the timestamp a full 16dp above the bubble it belongs to.
                            // Inside this Column the two sit 3dp apart, and the 16dp goes back
                            // to meaning what it says — the air between messages.
                            // **重行独立成一个 RenderNode**（issue #47 方向 2）：整页装进单个
                            // item 之后，整页共用一棵绘制树 —— 任何一处内容变化、以及每一次
                            // 滚动/动画，RenderThread 都要把这棵树整个重录（实测是主线程的
                            // 3.4 倍）。给「含思考块/工具卡」的重行挂一层，内容没变时它自己的
                            // 绘制列表可以被直接复用，整页重录退化成「只有脏行重录」。
                            //
                            // 只在重行上挂：轻行（用户气泡、纯正文）的绘制列表本来就短，多一层
                            // 反而增加合成开销和内存。层上不带任何视觉属性 ⇒ 渲染结果逐像素相同。
                            // **重卡判定要覆盖全部重内容**（issue #47 方向 2）：
                            // 原来只看 `Thinking` / `Tools`，漏掉两类最重的：
                            //  · `Compress`（上下文压缩卡，内部可含长清单）；
                            //  · **含图片的消息行** —— 图片不在 `Part` 里，而是消息级的
                            //    `message.allImages`（`ChatTopBar.kt:645` 逐个 ImageBlock），
                            //    解码位图是整页绘制列表里最重的一类。
                            // 轻行（用户气泡、纯正文）不挂层：它们的绘制列表本来就短，多一层
                            // 只增加合成开销与内存。层上没有任何视觉属性 ⇒ 渲染结果逐像素相同。
                            // 键用 `imageCount`（Int）而不是 `allImages`：后者是每次访问都重建
                            // List 的 getter（`ChatModels.kt:81` 的 ifEmpty/listOfNotNull），
                            // 拿它当 remember 键等于每次重组都换一个新对象、缓存必然失效。
                            val imageCount = row.message.imageCount
                            val heavyRow = remember(row.parts, imageCount) {
                                imageCount > 0 ||
                                    row.parts.any {
                                        it is Part.Thinking || it is Part.Tools || it is Part.Compress
                                    }
                            }
                            Column(
                                Modifier
                                    .then(if (heavyRow) Modifier.graphicsLayer() else Modifier)
                                    .onGloballyPositioned { coords ->
                                        rowHeights[visibleIndex] = coords.size.height + gapPx
                                    },
                            ) {
                                // 外观 → 显示时间: off means no labels at all, not fewer.
                                if (settings.showTimestamps && row.showTimeLabel) {
                                    TimeLabel(row.message.timestamp)
                                    // 3dp 太挤，时间像是贴在气泡上（用户：「间距再大一点」）。6dp 让它
                                    // 读作一行独立的抬头，又不会散开成两个东西。
                                    Spacer(Modifier.height(6.dp))
                                }
                                // 稳定引用（issue #5）：每次重组新建 lambda ⇒ `MessageRow` 的 equals
                                // 恒为 false ⇒ Compose 的跳过优化失效，每个可见项每帧都重组。
                                val onBranch = remember(viewModel) {
                                    { turnId: String, runId: String -> viewModel.switchBranch(turnId, runId) }
                                }
                                val onRegenerate = remember(viewModel) {
                                    { viewModel.regenerate() }
                                }
                                val onEdit = remember {
                                    { message: StoredMessage -> editing = message }
                                }
                                // 「提供方 / 模型」也按模型缓存：原来每帧每个可见项都新建一个 String。
                                val engineLabel = remember(row.message.model, providerOfModel) {
                                    providerOfModel[row.message.model]
                                        ?.let { "${it.label} / ${it.displayModel(row.message.model)}" }
                                        ?: row.message.model
                                }
                                MessageRow(
                                    row = row,
                                    assistantName = assistant?.label ?: "随行",
                                    assistantFace = assistant?.face ?: "",
                                    assistantAvatarPath = assistant?.avatarPath ?: "",
                                    showReasoning = settings.showReasoning,
                                    expandTools = settings.expandTools,
                                    expandReasoning = settings.expandReasoning,
                                    reasoningMaxHeight = settings.reasoningHeight.dp,
                                    followReasoning = settings.followReasoning,
                                    bubbleMaxWidth = bubbleMaxWidth,
                                    contextWindow = contextWindow,
                                    runElapsedMs = runElapsed[row.message.runId] ?: 0L,
                                    // The endpoint and the model that answered this round, as
                                    // one line: a message keeps the id it ran on, and the
                                    // owner is looked up from it. Just the id when no endpoint
                                    // claims it — a stale pick is not worth a wrong name.
                                    engineLabel = engineLabel,
                                    reasoningExpansion = reasoningExpansion,
                                    onBranch = onBranch,
                                    onRegenerate = onRegenerate,
                                    onEdit = onEdit,
                                )
                                // 中断/截断/失败的原因不再挂在这里：错误提醒统一画在列表最底
                                // （回执行 + 无回执时的实时红字），同一次失败只通知一次。
                            }
                            // The compression record sits under the last message it folded
                            // away: that is the point in the transcript where the model
                            // stopped seeing the conversation verbatim.
                            checkpointsByRow[row.message.id].orEmpty().forEach { checkpoint ->
                                // O(messages) 的扫描放到 remember 里：每次重组跑一遍是纯浪费，
                                // 键用 revision + checkpoint（两者任一变化才需要重算）。
                                val originals = remember(checkpoint, state.messagesRevision) {
                                    checkpointSpan(state.messages, checkpoint)
                                }
                                // A checkpoint whose span is not in this transcript (a
                                // branch switch, a trimmed conversation) is not drawn: half
                                // a record would read as data loss.
                                if (originals != null) {
                                    CheckpointCard(
                                        checkpoint = checkpoint,
                                        originals = originals,
                                    )
                                }
                            }
                            }
                        }
                            }
                        }
                        // 底锚：「加载更早」在**大索引端**（视觉顶部）。更老的轮次追加在喂序末尾，
                        // 现有 item 的索引不动，LazyColumn 按 key 钉住首可见项 ⇒ 阅读位不动。
                        if (page.hasEarlier) {
                            item(key = "load-earlier") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextButton(
                                        onClick = {
                                            // **先真的去磁盘读**（#30）：`state.messages` 现在只是
                                            // 尾部窗口，光把 `shownTurns` 加大不会让旧消息从磁盘长出来。
                                            // 读到了才扩窗口；没得读（已到文件开头）就不动，避免按钮骗人。
                                            if (viewModel.loadEarlier()) {
                                                shownTurnsByConversation =
                                                    shownTurnsByConversation +
                                                        (state.conversationId to shownTurns + PAGE_TURNS)
                                            }
                                        },
                                    ) {
                                        Text(
                                            text = "加载更早的 $PAGE_TURNS 轮 · 上面还有 ${page.earlier} 轮",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
            // Content scrolling behind the floating header fades out instead of being
            // cut by it. It starts at y=0 — behind the status strip too, or the first
            // line still hard-cuts under the clock — and runs statusBar + 96dp, the
            // taller band the ChatGPT header wears. Three stops (full, half, gone)
            // soften the edge the way a two-stop ramp cannot.
            val statusBarInset = with(LocalDensity.current) {
                WindowInsets.statusBars.getTop(this).toDp()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarInset + 96.dp)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to canvasColor,
                                0.55f to canvasColor.copy(alpha = 0.55f),
                                1f to canvasColor.copy(alpha = 0f),
                            ),
                        ),
                    ),
            )
            // 顶部浮层提示条已移除（用户：「有红色回执卡的提示」就够）：失败原因一律落在转录里
            // 那张红色回执卡上（RunReasonLine / FailureReceiptRow），不再浮一条会自己消失的横幅。
            // The floating header: two discs and the title capsule, no bar of its own.
            ChatTopBar(
                title = state.title,
                onNew = { viewModel.newConversation() },
                contextEntry = {
                    TopBarContextEntry(
                        used = contextUsed,
                        estimated = reading.estimated,
                        window = contextWindow,
                        sessionTokens = sessionTokens,
                        sessionEstimate = sessionEstimate,
                        sessionSplit = sessionSplit,
                        sessionTurns = sessionTurns,
                        sessionPerSecond = session?.perSecond ?: 0.0,
                        sessionCachePercent = session?.cachePercent ?: 0.0,
                        sessionElapsedMs = sessionElapsedMs,
                        statsFlow = viewModel.liveStats,
                    )
                },
            )
            // The jump-to-latest button lives inside the composer stack (first row of
            // its column): 12dp above whatever the stack's top row is, clear of the
            // pill — anchored to the reading area's bottom it hid behind the capsule.
            // One line per turn on the right edge; the old ◀ i/N ▶ row lived in the
            // composer and fought the status line for width.
            TurnScrubber(
                conversationId = state.conversationId,
                turns = turnIndices.size,
                // 延迟读（issue #26 原因一）：只有导航条用这个值，就在它内部求值。
                // 换算走纯函数（`TurnMarks`，带单测）：喂序 → 可见要**倒置**、
                // 比较时要补 `rowOffset` —— 两处都漏过一次（#16 的"偏一两项"）。
                current = {
                    TurnMarks.current(
                        firstVisibleFed = firstVisibleFed.value,
                        feedPrefix = feedPrefix,
                        visibleSize = visible.size,
                        rowOffset = rowOffset,
                        turnIndices = turnIndices,
                    )
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                labelOf = { turn ->
                    // A number alone cannot tell two turns about the same thing apart, so
                    val opening = visible.getOrNull(turnIndices.getOrElse(turn) { -1 } - rowOffset)
                        ?.message
                        ?.text
                        ?.lineSequence()
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                        .orEmpty()
                    if (opening.isEmpty()) {
                        "第 ${page.earlier + turn + 1} 轮"
                    } else {
                        "第 ${page.earlier + turn + 1} 轮 · ${opening.take(8)}"
                    }
                },
                onJumpTo = { turn ->
                    // `getOrNull`, not `turnIndices[turn]`: the row the finger was on is a row of
                    // the window the gesture started in, and a window can shrink under it — a
                    // retracted 插话, a branch switch, another conversation opened by a deep link.
                    // A jump to a turn that is no longer drawn is a no-op, never a crash.
                    // 底锚：turnIndices 是 visible 坐标（含 rowOffset），换算成喂序索引再跳。
                    turnIndices.getOrNull(turn)?.let { index ->
                        scope.launch {
                            // **跳历史必须先关追底**（issue #21）：否则 ChatScroll 的追底逻辑
                            // （`if (want.stick && …) scrollToItem(0)`）会在同一帧把列表拉回底部，
                            // animateScrollToItem 被打断 —— 用户看到的就是「点了没反应」。
                            // 用户实测「不在追底就不会」，正好印证触发条件就是 stickToBottom。
                            // 跳完保持 false：跳到历史本来就是为了读旧内容，不该被新内容拉回去。
                            scroll.stickToBottom = false
                            listState.animateScrollToItem(fedIndexOf(index - rowOffset))
                        }
                    }
                },
            )
            Box(Modifier.align(Alignment.BottomCenter)) {
                Composer(
                    input = input,
                    onInput = { setDraft(text = it) },
                    onRestoreSteer = { steer -> scope.launch { restoreQueuedSteer(steer) } },
                    running = state.runningHere,
                    statsFlow = viewModel.liveStats,
                    settings = settings,
                    assistantId = state.assistantId,
                    contextWindow = contextWindow,
                    contextUsed = contextUsed,
                    contextEstimated = reading.estimated,
                    // 面板三行：run 中用实测三块，空闲用按当前上下文算出来的那份（打开面板时刷新）。
                    contextBreak = liveStatsHere?.let {
                        Triple(it.breakSystem, it.breakTools, it.breakHistory)
                    } ?: state.contextBreakdown,
                    onContextPanelOpen = { viewModel.refreshContextBreakdown() },
                    sessionTokens = sessionTokens,
                    sessionEstimate = sessionEstimate,
                    sessionSplit = sessionSplit,
                    sessionTurns = sessionTurns,
                    sessionPerSecond = session?.perSecond ?: 0.0,
                    sessionCachePercent = session?.cachePercent ?: 0.0,
                    sessionElapsedMs = sessionElapsedMs,
                    conversationProviderId = state.providerId,
                    conversationModel = state.model,
                    conversationReasoningEffort = state.reasoningEffort,
                    onChooseReasoning = { viewModel.chooseReasoning(it) },
                    // The pick belongs to the conversation, not to the settings: it is
                    // written onto this chat alone, so choosing an endpoint here cannot
                    // change what any other conversation — or the app-wide default — runs.
                    onPickModel = { provider, model -> viewModel.chooseModel(provider.id, model) },
                    onPrefetchWindows = { viewModel.prefetchWindows() },
                    pendingSteers = state.pendingSteers,
                    pendingTurns = state.pendingTurns,
                    onPromoteTurn = { id -> viewModel.promoteTurn(state.conversationId, id) },
                    onRetractTurn = { id -> viewModel.retractTurn(state.conversationId, id) },
                    todos = state.todos,
                    onOpenTodos = { todoSheetOpen = true },
                    onPromoteSteer = { viewModel.promoteSteer(it) },
                    onRetractSteer = { viewModel.retractSteer(it) },
                    attachments = attachments,
                    onRemoveAttachment = { index ->
                        viewModel.removeAttachmentAt(state.conversationId, index)
                        setDraft(imageNames = draft.imageNames.toMutableList().apply { removeAt(index) })
                    },
                    onPickImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onTakePhoto = { cameraLauncher.launch(null) },
                    onPickFile = { filePicker.launch("*/*") },
                    onSend = {
                        // The view model clears this conversation's draft on success;
                        // a refused send keeps the text where the user left it.
                        val (body, images) = withAttachment(attachments, input)
                        viewModel.send(body, images)
                        // 发送 = 明确要回到最新：先放行追底再跳，并清掉阅读位置记忆。
                        scroll.stickToBottom = true
                        scroll.spotKey = null
                        jumpToLatest()
                    },
                    onStop = { viewModel.stop() },
                    onSteer = { text, images -> viewModel.steer(text, images) },
                    onRequestCompress = {
                        // run 中：请引擎在轮次边界折。**空闲时不在这里压** —— 菜单会改走
                        // onCompressNow（两条路径分开，压缩过程才画得出状态行）。
                        viewModel.requestCompress()
                    },
                    onCompressNow = { viewModel.compressNow() },
                    onFocused = {
                        // 底锚：只有本来就在底的转录才跟着键盘走；停在会话中段的不动（首可见项
                        // 被 LazyColumn 钉住，键盘挤压视口不会挪阅读位）。回到底＝贴底。
                        if (scroll.stickToBottom && itemCount > 0) {
                            scope.launch { scroll.toBottom() }
                        }
                    },
                    onMic = {
                        // In-app recognition needs the mic ourselves, unlike the old
                        // hand-off to the system recognizer's own app.
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            voiceOpen = true
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    voiceInput = settings.voiceInput,
                    approvalMode = settings.approvalMode,
                    // Written through the same repository the settings page writes, so the two
                    // entries cannot drift: the page re-reads the flow this update feeds.
                    onPickApproval = { mode ->
                        scope.launch { Core.settings.update { it.copy(approvalMode = mode) } }
                    },
                    onOpenSettings = onOpenSettings,
                    jumpToLatest = { jumpToLatest() },
                    // 按钮已移到 composer 之外（见下），这里只保留参数以免动调用链。
                    showJump = false,
                    onHeightChanged = { composerH = it },
                    // 底锚：键盘起落改的是列表底 padding，index0 就在底边上 ⇒ 一个滚动都不需要。
                    onPlaced = {},
                )
                // 「回到最新」浮钮：**画在 composer 之外**（兄弟节点），高度不参与 composer 的
                // 测量 ⇒ 它出现/消失不会改底部让位、不会把转录顶上去（用户报的「往上滑一点点就
                // 跳」）。位置：composer 顶再往上 12dp，右边缘 40dp 离屏边（与回合导航条留 12dp 气）。
                if (!scroll.atBottom && !state.loading) {
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_arrow_down),
                        contentDescription = "回到最新",
                        onClick = { jumpToLatest() },
                        size = 48.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        iconSize = 22.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = -(48.dp + 12.dp))
                            .padding(end = 24.dp),
                    )
                }
            }
        }
        if (voiceOpen) {
            VoiceOverlay(
                // 转写要用这份设置里的服务商与模型。
                settings = settings,
                // Just above the composer, never over it: the capsule's whole point is that the
                // user can still read what they are dictating into.
                bottomInset = composerH + 8.dp,
                onOpenSettings = onOpenSettings,
                onResult = { text -> setDraft(text = if (input.isBlank()) text else "$input $text") },
                onDismiss = { voiceOpen = false },
            )
        }
        // 任务清单的完整版：点输入框上方那一行打开。清单在跑完/清空时自己为空，所以这里不需要
        // 额外的开关——内容没了，表也就没什么可显示。
        if (todoSheetOpen) {
            // `state.todos`，不是 `todos`：那个名字是 Composer 的参数，在这一层不存在。
            TodoSheet(todos = state.todos, onDismiss = { todoSheetOpen = false })
        }
    }
    }

    // 文件路径菜单（LINK-CLICK-SPEC §3.2 一期）：复制路径、让随行读取。一期不碰 FileProvider
    // （那是二期「文件管理器打开 / 分享」）。「让随行读取」＝往会话发一条带该路径的用户消息，
    // agent 有 read_file/run_shell，能自己读；文件已在上一轮作为附件落进 inbox 的也照此办理。
    pathMenuPath?.let { path ->
        PathActionSheet(
            path = path,
            onDismiss = { pathMenuPath = null },
            onCopy = {
                clipboard.setText(AnnotatedString(path))
                pathMenuPath = null
                scope.launch { snackbarHostState.showSnackbar("已复制路径") }
            },
            onAskModel = {
                pathMenuPath = null
                viewModel.send("读取这个文件并总结：$path")
            },
        )
    }

    if (assistantsOpen) {
        AssistantSheet(
            assistants = settings.assistants,
            activeId = state.assistantId.ifBlank { settings.activeAssistantId },
            settings = settings,
            onUse = { viewModel.useAssistant(it) },
            onSetDefault = { viewModel.setDefaultAssistant(it) },
            // 编辑/新建：收掉半屏表，编辑器画在主窗口的全屏覆盖层（结构路，0.8.105）。
            onOpenEditor = { id ->
                assistantsOpen = false
                editorAssistantId = id
            },
            onDismiss = { assistantsOpen = false },
        )
    }

    // 助手编辑器：主窗口里的全屏一层，盖住聊天页的一切（含浮动头）。Dialog 窗口在这台设备上
    // 的状态栏行为修了四轮都不对，所以这一页不再有自己的窗口。进出场与导航页同一套动画
    // （FullScreenOverlay）；退场那 200ms 里内容靠 lastEditorId 留住，不会淡出一片空白。
    var lastEditorId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editorAssistantId) {
        if (editorAssistantId != null) lastEditorId = editorAssistantId
    }
    FullScreenOverlay(visible = editorAssistantId != null) {
        val id = editorAssistantId ?: lastEditorId
        if (id != null) {
            val servers by Core.mcp.servers.collectAsState()
            val toolNames = remember(servers) { Core.tools.names() }
            val target = settings.assistants.firstOrNull { it.id == id }
                ?: Assistant(id = id, avatar = "🧭")
            AssistantEditor(
                initial = target,
                settings = settings,
                toolNames = toolNames,
                backEnabled = editorAssistantId != null,
                onSave = {
                    viewModel.saveAssistant(it)
                    editorAssistantId = null
                },
                onDelete = if (settings.assistants.size > 1) {
                    {
                        viewModel.deleteAssistant(target)
                        editorAssistantId = null
                    }
                } else {
                    null
                },
                onDismiss = { editorAssistantId = null },
            )
        }
    }

    editing?.let { message ->
        EditDialog(
            message = message,
            onDismiss = { editing = null },
            onSave = { text ->
                viewModel.editMessage(message.id, text)
                editing = null
            },
            onResend = if (message.role == Roles.USER) {
                { text ->
                    viewModel.editAndResend(message.id, text)
                    editing = null
                }
            } else {
                null
            },
        )
    }
}

/**
 * 报出 [reported] 的高度，但内容按**不受限**的高度测量（真实自然高度写进 [natural]）。
 *
 * `reported <= 0` ⇒ 直接报自然高度（贴底时走这条，行为与改动前完全一致）。
 * 内容比 [reported] 高时由调用方加 `clipToBounds()` 裁掉 —— 被裁的只是这一帧多出来的边缘。
 */
/**
 * 钉住期间，**气泡**该报多高（不含状态行）。
 *
 * 钉的是"气泡 + 状态行"的总高（`LivePinState.height = natural + outside`），所以气泡要报
 * `总高 − 当前状态行高度`，这样 item 总高恒等于钉住值，上面的内容才不动。
 *
 * 两个边界都写在这里、并各有一条断言：
 * - `reported <= 0`（没钉）⇒ 直接用自然高度，行为与改动前完全一致；
 * - `outside >= reported`（小气泡 + 三行重试提示）⇒ 钳到 1px。**这是刻意的取舍**：
 *   这种情况下总高会比钉住值大（内容被推一下），换的是"错误提示要显示全"。
 */
internal fun laggedBubbleHeight(reported: Int, outside: Int, natural: Int): Int =
    if (reported > 0) (reported - outside).coerceAtLeast(1) else natural

private fun Modifier.liveRowLagHeight(
    reported: Int,
    natural: IntArray,
    /** 与本行同属一个 item、但**在裁剪之外**的那部分高度（状态行）。见下面的说明。 */
    outside: IntArray,
): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity),
        )
        natural[0] = placeable.height
        // **报的高度不超过内容实际高度**（真机白块：一轮失败/重试会把 liveParts 清空，
        // 而钉住的高度还是失败前的几千 px ⇒ 这个 item 留一大片空白）。
        // 钉住只用来「不许长高」，不该用来「占着没有内容的空位」。
        // **钉住期间无条件报钉住值**，不要 `minOf(reported, natural)`：
        // `pinnedLiveHeight` 钉住期间不跟踪自然高度，而 minOf 会跟 —— 高度成了「只降不升的
        // 单向棘轮」。思考块/工具块走 RevealHeight，收起时按 220ms 缓动**逐帧下降**，
        // 那 220ms 里报出的高度跟着掉、收起完再弹回 ⇒ 用户看到「读着读着跳一下再回来」。
        // 内容真变短（一轮失败/重试清空 liveParts）时会留一段空白，但那由 `liveForPin` 兜住
        // （没有活内容就不钉），且「读历史期间宁可空白也不要动」正是 #17 要的语义。
        // `reported` 是**整个 item** 的钉住高度；状态行在裁剪之外、不算进这里，
        // 所以要把它扣回来 —— 这样状态行换行（重试/429 的提示会有两三行）时，
        // 气泡的报出高度会反向缩小同样的量，**item 总高度不变** ⇒ 上面的内容不会被推。
        // 扣掉的是"最新输出"那一段，本来就在裁剪里，用户看不见。
        // **判据是 `reported`，不是扣完之后的 `own`**：状态行比被钉住的气泡还高时（小气泡 +
        // 三行 429 提示）`own <= 0`，若拿它当判据就会**静默放弃钉住**（而 `pinActive` 仍为 true
        // ⇒ 自动贴底被禁、内容瞬间跳到真实高度）。钉住期间永远至少报 1px。
        //
        // **「item 总高度不变」有一个例外**（写清楚，别让后来的人以为这里恒成立）：
        // 当 `outside`（状态行）本身就比 `reported` 还大时（小气泡 + 三行重试提示），
        // `own` 被钳到 1 ⇒ 总高 = 1 + outside > reported ⇒ **内容会被推一次**。
        // 这是刻意的取舍：另一种选择是把状态行一起裁掉（总高恒定），但那会违反
        // 「错误的提示要显示全」（用户明确要求过），而"读历史时看不到失败原因"比"推一下"更糟。
        // 触发条件很窄：只在钉住期间状态行**从 1~2 行涨到比气泡还高**的那一瞬。
        val height = laggedBubbleHeight(reported = reported, outside = outside[0], natural = placeable.height)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

