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
import kotlinx.coroutines.flow.collectLatest
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
    /**
     * 这一次按下里手指移动过没有（issue #14）。
     *
     * 守护协程要用它区分「长按选字」和「按住拖动」：前者要锁住列表，后者锁住就是跟手指打架。
     * 以前 `moved` 只是 pointerInput 里的局部变量，外面看不到。
     */
    var pressMoved by remember { mutableStateOf(false) }
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
            if (lockKey == null) {
                scrollTrace("lock-skip reason=no-key")
            } else {
                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == lockKey }
                when {
                    target == null -> scrollTrace("lock-skip reason=offscreen lock=$lockKey")
                    target.index == listState.firstVisibleItemIndex ->
                        scrollTrace("lock-skip reason=same idx=${target.index}")
                    else -> {
                        // **只记录，不执行**（用户：「滑上一点，就滚下来一点，滑多了就不会」）。
                        //
                        // 这条纠正拿的是**按下那一刻**的 lockKey/lockOffset。正常上滑后
                        // `firstVisibleItemIndex` 变了、而按下时那条 item 仍在屏幕内 ⇒ 它把你
                        // 纠回按下时的位置 = 把刚滑的那段原样撤回；滑得够远时那条 item 已滚出
                        // 屏幕、`target == null` ⇒ 不纠 → 所以「滑多了反而正常」。
                        // 用户报的两半现象（小滑回弹 / 大滑正常）正是这一条的行为。
                        //
                        // 去掉执行 ≠ 放弃 #14：这里把「本来会纠到哪」写进 scroll-jump.log，
                        // 真机长按复制时读日志即可判定 SelectionContainer 重排到底移了多少 ——
                        // 那才是 issue #14 要求的第一步（先定位，再决定用哪种机制）。
                        scrollTrace("lock-would-correct to=${target.index}/$lockOffset")
                    }
                }
            }
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
    // 流式行的高度（由 streaming item 的 onSizeChanged 写）。
    var liveHeight by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(state.conversationId) {
        // 初值已经是 0，这里只负责把它推到 1：从 0 起跑，不需要 `snapTo`，
        // 也就没有「snapTo 生效之前先露一帧」的窗口。
        switchAlpha.animateTo(1f, tween(210, easing = LinearOutSlowInEasing))
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
    val anchorKey: Any? = anchors[state.conversationId]?.first
    // **只记「消息行」的 key**（issue #15 的失败原因之一）：首可见项经常是 `load-earlier`、
    // `streaming:<id>`、`live-status:<id>` 这类临时行，它们的 key 在切回来时**找不到对应消息**
    // （`visible.indexOfFirst { it.message.id == saved.first }` 恒为 -1），于是恢复直接掉回底部。
    // 现在记锚点时跳过这些行，取第一个真正的消息 id。
    val messageIds = remember(visible) { visible.mapTo(HashSet()) { it.message.id } }
    // 记锚点：**不能看 `isScrollInProgress`**（issue #11 补充评论）。流式内容长高时 LazyColumn 的
    // 内部重排会被算成「正在滚动」，于是这个值在流式期间一直是 true —— 锚点永远记不下来，
    // 纠正逻辑也就永远检测不到 drift（用户实测：「抖动没了，但位置仍会持续漂移」）。
    //
    // 现在改成「**手指抬起那一刻记**」（issue #16 的建议）。上一版是
    // `snapshotFlow(位置).collectLatest { delay(120); … }` 的 debounce：
    // 位置稳定 120ms 才会写一次。流式期间位置每 40ms 变一次 ⇒ collectLatest 一直取消 ⇒
    // **锚点不会被更新**（它不是"永远是 null"：用户停手之后仍会写一次；但如果他只在流式期间
    // 滚动过，就可能一次都没写上）。用 `pressed` 的下降沿触发，与位置变化频率无关。
    LaunchedEffect(listState) {
        snapshotFlow { pressed }.collect { isPressed ->
            if (isPressed) return@collect
            // 恢复位置的那几帧不记：那时首可见项还是上一个会话留下的布局（issue #15）。
            if (scroll.stickToBottom || scroll.restoring) return@collect
            // **只记消息行**：首可见项常是 `load-earlier` / `streaming:` 这类临时行，
            // 它们的 key 切回来时找不到对应消息（issue #16 的核心）。
            val first = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key in messageIds } ?: return@collect
            // 它就是首可见项就照实记偏移；被上面临时行压着就记 0（`scrollToItem` 的 offset 不能为负）。
            val offset = if (first.index == listState.firstVisibleItemIndex) {
                listState.firstVisibleItemScrollOffset
            } else {
                0
            }
            anchors[state.conversationId] = first.key to offset
        }
    }
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
    LaunchedEffect(state.conversationId, itemCount) {
        if (openedPinned || itemCount <= 0) return@LaunchedEffect
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
            // ① 粗定位：`fedIndexOf` 只是起点，允许差一两项。
            val approx = visible.indexOfFirst { it.message.id == savedKey }
            if (approx >= 0) {
                listState.scrollToItem(fedIndexOf(approx), saved.second)
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
                            // **切换会话的淡入**（issue #12）：`openConversation` 是一帧内把整个
                            // state 换掉（没有 loading 态可等），所以只能给新内容一个入场。
                            // 用 graphicsLayer 而不是 alpha 修饰符：alpha 在 layer 块里读，动画期间
                            // 不触发重组；也不做 AnimatedContent —— 那会同时持有两份 composition，
                            // 对 30MB 的会话内存峰值翻倍（issue 自己提的第 4 点）。
                            .graphicsLayer {
                                // 形态 = **淡入 + 从下方 24dp 升起 + 缩放到 1.0**（issue #12
                                // 评论 5 的方案 A，lead 定的方向）。纯 alpha 只有「蒙层在消失」，
                                // 没有方向感也没有层次，用户说的「不优雅、动画效果烂」指的是这个。
                                //
                                // 缩放取 0.92（不是上一版凭感觉的 0.97）：这是**本仓库自己的**
                                // 入场约定 —— `ChatComposer.kt:334` 的弹层就是
                                // `fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f)`。
                                val p = switchAlpha.value
                                alpha = p
                                translationY = (1f - p) * 24.dp.toPx()
                                val s = 0.92f + 0.08f * p
                                scaleX = s
                                scaleY = s
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
                                        pressGuardOn = true
                                        pressMoved = false
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
                                                pressMoved = true
                                            }
                                            released = next.changes.none { it.pressed }
                                        }
                                        pressed = false
                                        pressUpAt = SystemClock.uptimeMillis()
                                        // 短按（没移动、也没到长按阈值）= 点折叠条/点按钮这类点击：抬手即放行追底。
                                        // 守护窗口是给"长按选文字"的（400ms 内不许列表自己动，否则选区跟着跑），
                                        // 但展开动画只有 220ms（PROCESS_EXPAND_MS）：不区分的话整个展开都落在守护窗口里，
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
                        // **内容不满一屏时贴顶**（issue #19，用户：「新会话气泡就应该在上面，然后再流式
                        // 长下来，旧内容不就往上推了」）。底锚（reverseLayout）已经负责"满了之后"：
                        // index 0 钉在底部、新内容向上长、旧内容被推上去；arrangement 只在内容不满一屏时
                        // 参与，所以这里改一个词就够，不需要混合判断。
                        //
                        // 风险（issue 自己提的）：当年从 Top 改回 Bottom，注释写的是「短对话被顶到上面、
                        // 最后一张卡片长高就还是往下长」—— 那正是用户现在要的行为，所以大概率是当年
                        // 判断反了。若满屏那一刻有跳变，退回 `Alignment.Bottom` + offset 方案。
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
                    ) {
                        if (live) {
                            // Keyed by conversation: the live row keeps its slot for as long
                            // as it is on screen, and a switch used to hand the next chat
                            // whatever the previous one had left in that slot.
                            item(key = "streaming:${state.conversationId}") {
                                // 流式行（喂序 index 0）每长高一点，上面的内容就被顶上去一点 ——
                                // 往上翻着读旧内容时这是最烦人的漂移。**内容照常长**（issue #11：
                                // 裁剪是死路），位置由上面那段 key 锚点纠正，这里只负责量高度。
                                Column(Modifier.onSizeChanged { liveHeight = it.height }) {
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
                                            shownTurnsByConversation =
                                                shownTurnsByConversation +
                                                    (state.conversationId to shownTurns + PAGE_TURNS)
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

