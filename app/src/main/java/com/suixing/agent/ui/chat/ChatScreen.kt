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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
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



internal val SUGGESTIONS = listOf(
    "看看现在屏幕上是什么",
    "把下载目录里最新的三个文件列出来",
    "搜一下今天有什么科技新闻",
    "打开设置看看还剩多少存储空间",
)

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
    var editing by remember { mutableStateOf<StoredMessage?>(null) }
    // The composer stack's own measured height: the node inside all of its paddings, so the
    // gesture bar's strip and its own 12dp of air are not in it (they are added back below).
    var composerH by remember { mutableStateOf(0.dp) }
    // The keyboard's clamped inset, and the gesture bar's own: the composer's bottom padding is
    // the difference between them (see the stack's own modifier), and it is the one term of the
    // stack's footprint that moves while the keyboard does.
    val heldIme = LocalHeldImePx.current
    val navBarPx = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this) }
    fun navBarPad(ime: Int): Int = (navBarPx - ime).coerceAtLeast(0)
    // 让位＝**只用一个来源**：输入框自己的高度 + 它的下内边距 + 手势条。键盘动画中唯一在变的量是
    // heldIme，而它正是输入框自身位置的同一个驱动量 ⇒ 两者同步移动，可见空隙在任何一帧都一样
    // （用户要恒定）。原来用「列表底 − 输入框顶」两个节点的测量值相减，两个 onGloballyPositioned
    // 不同帧到位 ⇒ 动画中间态抖动。
    val stackFootprint = with(LocalDensity.current) {
        // `composerH` 是**内边距以内**的高度：`onSizeChanged` 挂在
        // `.padding(bottom = COMPOSER_BOTTOM_PAD)` **之后**（TurnScrubber.kt:757-758），
        // Compose 的修饰符顺序让 padding 先吃掉空间，所以测到的不含这 12dp。
        // 这里把它加回来才是输入框真实的占位 —— **不要删**（我删过一次，是错的）。
        composerH + COMPOSER_BOTTOM_PAD + navBarPad(heldIme).toDp() +
            if (state.runningHere) 12.dp else 16.dp
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
    // in it, and 加载更早 only ever grows it. The count resets with the conversation: the
    // window is a property of the chat being read, not of the screen that reads it.
    // Saved for the same reason as the scroll position and the follow below: a visit to the
    // settings page disposes this composition, and a plain `remember` would collapse the transcript
    // back to the newest page — the reader's 加载更早 taps undone, which reads as the page having
    // reloaded. The key still resets it when the conversation really changes.
    var shownTurns by rememberSaveable(state.conversationId) { mutableIntStateOf(PAGE_TURNS) }
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
    val page = remember(rowsKey, shownTurns) { turnPage(state.messages, shownTurns) }
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
    // 回执（落盘的 system 消息）**内联画在它那一轮后面**（用户：「一轮错误就跟在一轮后面」）——
    // MessageRow 的分发里画（Roles.SYSTEM → FailureReceiptRow）。这里只取一遍供去重：同文案的
    // 实时行不再画，一次失败只通知一次（用户报的「两个提醒」）。
    val receipts = remember(state.conversationId, state.messagesRevision, page) {
        state.messages.drop(page.from).filter { it.role == Roles.SYSTEM }
    }
    // 没有回执的失败（发送阶段错误等）的实时行，画在最后一轮下面。去重见 [isSameFailure]：
    // 只按完全相等比会漏，同一次失败被两条路径各写一遍时屏幕上是两张提示。
    val errorLine = state.error?.let { err -> receipts.none { isSameFailure(it.text, err) } } ?: false
    val reasoning = if (settings.showReasoning) state.reasoning else ""
    // Whatever the run produces first — a tool call, a thought or a word — opens the live
    // bubble. Waiting for text is what used to leave a tool call stranded on its own until
    // the reply arrived and merged the two into one.
    val live = state.liveParts.isNotEmpty()
    val assistant = settings.assistant(state.assistantId) ?: settings.activeAssistant()
    // The chain a run takes, resolved once for the whole screen. The context readouts
    // below divide by the window of the model that actually sends, which is not the
    // app-wide default's window once a conversation or a persona picks its own.
    val contextWindow = settings.contextWindowFor(
        settings.choiceFor(state.providerId, state.model, assistant).model,
    )
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
    val newestUsage = visible.lastOrNull { it.usage != null }?.usage
    // What the endpoint never priced, sized off the text: the ring's floor for a conversation
    val transcriptEstimate = remember(rowsKey, state.checkpoints) {
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
        kept.sumOf { estimateTokens(it) } + (last?.summary?.length ?: 0) / 3
    }
    // 圆环读数＝**真实数字**：当前会话的上下文大小 / 模型窗口（用户：「就是按照真实数字啊…
    // 当前会话上下文/上下文窗口的数字」）。分子只认实测口径：网关回报的本轮 prompt_tokens
    // （含按会话保持的最近一次）→ 落盘回报；只有整个会话从没被网关报过用量时才用 /4 估算兜底
    // 并标「约」。跳变的老根因是两个口径轮流上屏，所以估算值绝不与实测值并列成链。
    val heldPrompt = rememberSaveable(state.conversationId) { mutableIntStateOf(0) }
    val reported = state.stats?.lastPromptTokens ?: 0
    LaunchedEffect(reported) {
        if (reported > 0) heldPrompt.intValue = reported
    }
    val reading = ringReading(
        liveReported = if (reported > 0) reported else heldPrompt.intValue,
        newestStored = newestUsage?.prompt ?: 0,
        liveEstimate = state.stats?.let { it.breakSystem + it.breakTools + it.breakHistory } ?: 0,
        transcriptEstimate = transcriptEstimate,
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
    val sessionElapsedMs = remember(rowsKey) { state.messages.sumOf { it.elapsedMs } }
    // The conversation's spend, split the way the endpoint reports it, for the ring's card: the
    // prompt the cache could not answer, the cache reads, and the output. The card used to give
    // the total and the hit rate only; the user asked for the three parts by name —
    // 「未缓存输入」「缓存读取」「输出」 — and in full, because these are the figures a bill is
    // checked against and a rounded one cannot be checked against anything.
    val sessionSplit = remember(rowsKey) { sessionSplitOf(state.messages) }
    // Which compression records belong under which row. A record names the first and last
    // message it folded away, so it is drawn under the row that holds that last message —
    // the place in the transcript where the model stopped seeing the conversation verbatim.
    val checkpointsByRow = remember(state.checkpoints, visible) {
        val byId = visible.associateBy { it.message.id }
        val indexOf = visible.withIndex().associate { (index, row) -> row.message.id to index }
        val orphanToTop = mutableListOf<ContextCheckpoint>()
        val placed = state.checkpoints.mapNotNull { checkpoint ->
            // **锚点是压缩发生的位置**（anchorId），不是被折叠掉的最后一条（toMessageId）：
            // 按后者定位，压缩块会跑到旧历史里，跑完这一轮就"消失"在屏幕上方（用户反馈）。
            // 旧盘没有 anchorId（空串）⇒ 回落到 toMessageId，行为与以前一致。
            val anchor = checkpoint.anchorId.ifEmpty { checkpoint.toMessageId }
            val owner = byId[anchor]
                ?: visible.firstOrNull { row ->
                    row.rounds.any { it.id == anchor }
                }
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
    val itemCount = visible.size + rowOffset +
        (if (errorLine) 1 else 0) + if (live || state.runningHere) 1 else 0

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
    val feedPrefix = (if (live || state.runningHere) 1 else 0) +
        (if (!state.runningHere && state.compressingSince > 0L) 1 else 0) +
        (if (errorLine) 1 else 0)
    fun fedIndexOf(oldVisible: Int) = feedPrefix + (visible.size - 1 - oldVisible)

    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    // 底锚下 firstVisibleItemIndex 是**视觉最底**那条的喂序索引：倒着换回 visible 坐标。
    val firstVisibleOld = (visible.size - 1 - (firstVisible - feedPrefix))
        .coerceIn(0, (visible.size - 1).coerceAtLeast(0))
    val currentTurn = turnIndices.indexOfLast { it <= firstVisibleOld + rowOffset }
        .coerceAtLeast(0) + 1

    // ===== 滚动与阅读位置：底锚列表，逻辑全在 ChatScroll.kt =====
    // 底锚（index 0 = 视觉最底）下长高向上、底边不动；追底＝回到 index 0；取消＝手指轻扫 4dp。
    // 这一层没有任何"纠正"代码。
    var pressed by remember { mutableStateOf(false) }
    var pressUpAt by remember { mutableLongStateOf(0L) }
    var pressGuardOn by remember { mutableStateOf(false) }
    // composition local 只能在组合里读：先在组合层捕获，守护协程里用这个引用。
    val setSelectionActive = LocalSetSelectionActive.current
    // **临时诊断（查完就删）**：长按复制会跳位置，且只有本次进程第一次出现。
    // 记下"按下 / 抬手 / 守护撤销"几个时刻的滚动坐标，就能看出那次跳动是程序滚的、
    // 还是列表自己重新锚定的。
    val traceCtx = LocalContext.current
    // **临时诊断，release 也写**（用户明确要求：「写到 release 啊，到时候再删了」）：
    // 用来抓「长按复制跳位置」。查完连这个函数一起删。
    fun scrollTrace(tag: String) {
        runCatching {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            val line = "${System.currentTimeMillis()} $tag " +
                "first=${first?.key}@${first?.offset} " +
                "idx=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} " +
                "canBack=${listState.canScrollBackward} total=${info.totalItemsCount}\n"
            val f = java.io.File(traceCtx.filesDir, "scroll-jump.log")
            if (f.length() > 128 * 1024) f.writeText("")
            f.appendText(line)
        }
    }
    LaunchedEffect(pressGuardOn) {
        if (!pressGuardOn) return@LaunchedEffect
        scrollTrace("guard-on")
        setSelectionActive?.invoke(true)
        try {
            while (pressGuardOn) {
                if (!pressed && SystemClock.uptimeMillis() - pressUpAt >= LONG_PRESS_SETTLE_MS) {
                    pressGuardOn = false
                }
                withFrameNanos { }
            }
        } finally {
            // 组合被带走（切会话/退页）也要解冻，否则外壳的 IME clamp 永远停摆。
            scrollTrace("guard-off")
            setSelectionActive?.invoke(false)
        }
    }
    val scroll = rememberChatScroll(
        listState = listState,
        autoFollow = settings.autoFollow,
        pressed = pressed,
        pressGuardOn = pressGuardOn,
        // `listState` 来自 `rememberLazyListState()`（第 273 行），**全应用共用一个实例**，
        // 切会话时不重建 —— 所以阅读锚点必须自己按会话隔离，否则旧锚会去纠正新会话。
        conversationId = state.conversationId,
    )
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
    LaunchedEffect(state.conversationId) {
        if (lastConvId != state.conversationId) {
            scroll.saveSpot(lastConvId)
            lastConvId = state.conversationId
        }
    }
    var openedPinned by rememberSaveable(state.conversationId) { mutableStateOf(false) }
    LaunchedEffect(state.conversationId, itemCount, openedPinned) {
        if (openedPinned || itemCount <= 0) return@LaunchedEffect
        openedPinned = true
        val saved = scroll.takeSpot(state.conversationId)
        val idx = if (saved?.first != null) {
            visible.indexOfFirst { it.message.id == saved.first }
        } else {
            -1
        }
        if (idx >= 0 && saved != null) {
            scroll.stickToBottom = false
            scroll.spotKey = saved.first
            scroll.spotOffset = saved.second
            // 喂序索引＝底锚倒序里 visible 第 idx 行的位置；offset 即 firstVisibleItemScrollOffset。
            listState.scrollToItem(fedIndexOf(idx), saved.second)
        } else {
            scroll.stickToBottom = true
            scroll.toBottom()
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
        Box(
            Modifier
                .fillMaxSize()
                .background(canvas)
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
                        LocalReadingPinned provides !scroll.stickToBottom,
                        LocalTypewriter provides settings.typewriter,
                        // 不在底部时展开要"向下展开"（块顶边钉住），需要摸到主列表状态。
                        LocalChatListState provides listState,
                    ) {
                    LazyColumn(
                        state = listState,
                        // **底锚**（reverseLayout + 数据倒序喂）：index 0 就是视觉最底，最后一张卡片
                        // 长高向上、底边不动 ⇒ 不再需要任何逐帧同步滚动。旧底锚失败在它还在用
                        // dispatchRawDelta 逐帧纠正，两处抢同一份列表，才"乱飞/抖/拉回"。
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            // 手指按下只干两件事：通知外壳冻结 IME clamp（选择期间键盘别收）；
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(PointerEventPass.Initial)
                                        if (down.changes.none { it.pressed }) continue
                                        val downY = down.changes.first().position.y
                                        val downAt = SystemClock.uptimeMillis()
                                        pressed = true
                                        pressGuardOn = true
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
                                            if (change != null && change.pressed &&
                                                (dy > CANCEL_FOLLOW_PX || dy < -CANCEL_FOLLOW_PX)
                                            ) {
                                                scroll.stickToBottom = false
                                                moved = true
                                            }
                                            released = next.changes.none { it.pressed }
                                        }
                                        pressed = false
                                        pressUpAt = SystemClock.uptimeMillis()
                                        // 短按（没移动、也没到长按阈值）= 点折叠条/点按钮这类点击：抬手即放行追底。
                                        // 守护窗口是给"长按选文字"的（400ms 内不许列表自己动，否则选区跟着跑），
                                        // 但展开动画只有 180ms（PROCESS_EXPAND_MS）：不区分的话整个展开都落在守护窗口里，
                                        // 列表一动不动、等守护撤了再一次性补上整段高度 —— 这就是"卡片长高后被瞬移到上面"。
                                        if (!moved && SystemClock.uptimeMillis() - downAt <
                                            viewConfiguration.longPressTimeoutMillis
                                        ) {
                                            pressGuardOn = false
                                        }
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
                            bottom = stackFootprint,
                        ),
                        // 内容不满一屏时也压到底：默认的 top 排列在倒序里会把短对话顶到上面，
                        // 最后一张卡片长高就还是往下长。内容溢出后 arrangement 不参与。
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                    ) {
                        if (live) {
                            // Keyed by conversation: the live row keeps its slot for as long
                            // as it is on screen, and a switch used to hand the next chat
                            // whatever the previous one had left in that slot.
                            item(key = "streaming:${state.conversationId}") {
                                // **不做任何补偿滚动**（四版都没成：闪退 / 抖 / 锁）。
                                // 贴底时由底锚跟随；往上翻时内容长高会让视图缓慢上移，
                                // 但不会锁住、也不会抖 —— 这是刻意选择的取舍。
                                Column {
                                    AssistantRow(
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

                                                is LivePart.Text -> Part.Text(part.text)

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
                                        liveStats = state.stats,
                                        contextWindow = contextWindow,
                                        expandTools = settings.expandTools,
                                        expandReasoning = settings.expandReasoning,
                                        reasoningMaxHeight = settings.reasoningHeight.dp,
                                        followReasoning = settings.followReasoning,
                                        bubbleMaxWidth = bubbleMaxWidth,
                                        reasoningExpansion = reasoningExpansion,
                                    )
                                    // 上下都 12dp（用户要求）：气泡白底 → 状态行顶 = top = 12dp；
                                    // 状态行底 → 输入框顶 = 下方让位（跑的时候 12dp），所以这里 bottom = 0。
                                    if (state.runningHere) {
                                        RunStatusLine(
                                            text = liveStatusText,
                                            startedAt = runStartedAt[runKey] ?: 0L,
                                            // 压缩不再占用这一行（它有自己的过程块），所以时钟一直走。
                                            timed = true,
                                            modifier = Modifier.padding(
                                                start = 4.dp,
                                                top = 12.dp,
                                                bottom = 0.dp,
                                            ),
                                        )
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
                                    // 上下都 12dp（用户：「状态行的空隙也是错的，12dp 啊」）：
                                    // 下 = contentPadding(12dp) 直接给；上 = 16dp 项间距 − lift 4dp。
                                    modifier = Modifier
                                        .liftIntoGap(4.dp)
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
                        items(visible.asReversed(), key = { it.message.id }) { row ->
                            // One placeable per item: the list's own 16dp spacing applies
                            // between the placeables of a single item as well, which is what
                            // held the timestamp a full 16dp above the bubble it belongs to.
                            // Inside this Column the two sit 3dp apart, and the 16dp goes back
                            // to meaning what it says — the air between messages.
                            Column {
                                // 外观 → 显示时间: off means no labels at all, not fewer.
                                if (settings.showTimestamps && row.showTimeLabel) {
                                    TimeLabel(row.message.timestamp)
                                    // 3dp 太挤，时间像是贴在气泡上（用户：「间距再大一点」）。6dp 让它
                                    // 读作一行独立的抬头，又不会散开成两个东西。
                                    Spacer(Modifier.height(6.dp))
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
                                    engineLabel = providerOfModel[row.message.model]
                                        ?.let { "${it.label} / ${it.displayModel(row.message.model)}" }
                                        ?: row.message.model,
                                    reasoningExpansion = reasoningExpansion,
                                    onBranch = { turnId, runId -> viewModel.switchBranch(turnId, runId) },
                                    onRegenerate = { viewModel.regenerate() },
                                    onEdit = { editing = it },
                                )
                                // 中断/截断/失败的原因不再挂在这里：错误提醒统一画在列表最底
                                // （回执行 + 无回执时的实时红字），同一次失败只通知一次。
                            }
                            // The compression record sits under the last message it folded
                            // away: that is the point in the transcript where the model
                            // stopped seeing the conversation verbatim.
                            checkpointsByRow[row.message.id].orEmpty().forEach { checkpoint ->
                                val originals = checkpointSpan(state.messages, checkpoint)
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
                                            shownTurns += PAGE_TURNS
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
                        live = state.stats,
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
                current = currentTurn,
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
                        scope.launch { listState.animateScrollToItem(fedIndexOf(index - rowOffset)) }
                    }
                },
            )
            // The composer floats over the transcript instead of owning a strip at the bottom:
            // content scrolls behind the pill, which is what makes it read as a floating input
            // rather than a bar that cuts the conversation off. Not drawn while the
            // conversation is still being opened: an input field with a "say something" hint
            // is part of what made the loading frame look like a new chat.
            if (!state.loading) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                Composer(
                    input = input,
                    onInput = { setDraft(text = it) },
                    onRestoreSteer = { steer -> scope.launch { restoreQueuedSteer(steer) } },
                    running = state.runningHere,
                    stats = state.stats,
                    settings = settings,
                    assistantId = state.assistantId,
                    contextWindow = contextWindow,
                    contextUsed = contextUsed,
                    contextEstimated = reading.estimated,
                    // 面板三行：run 中用实测三块，空闲用按当前上下文算出来的那份（打开面板时刷新）。
                    contextBreak = state.stats?.let {
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

