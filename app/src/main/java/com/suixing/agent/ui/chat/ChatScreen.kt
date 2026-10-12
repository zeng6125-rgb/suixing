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
import android.view.ViewConfiguration
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
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
import com.suixing.agent.ui.components.LocalHeldImePx
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

private val SUGGESTIONS = listOf(
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
    // The unsent text is the view model's, keyed by conversation: typing here, opening
    // another chat and coming back restores exactly what was left, and nothing leaks
    // across the switch.
    val draft = state.draft
    val input = draft.text
    // The conversation is a key as well: the cache is per conversation, and two chats that both
    // hold a 「修改」-restored picture carry the *same* marker uri and label — without this, coming
    // back from one to the other kept the previous chat's decoded bitmap (the chip would show, and
    // send, the wrong picture).
    val attachment = remember(state.conversationId, draft.attachmentUri, draft.attachmentName) {
        viewModel.attachmentFor(state.conversationId)
    }
    fun setDraft(
        text: String = input,
        uri: String? = draft.attachmentUri,
        name: String? = draft.attachmentName,
    ) {
        viewModel.onDraftChange(state.conversationId, Draft(text, uri, name))
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
        val base64 = steer.imageBase64
        if (base64 == null) {
            setDraft(text = text)
            return
        }
        val bitmap = decodeAttachment(base64)
        if (bitmap == null) {
            setDraft(text = text)
            return
        }
        viewModel.cacheAttachment(state.conversationId, bitmapAttachment(bitmap, QUEUED_IMAGE_LABEL))
        setDraft(text = text, uri = QUEUED_ATTACHMENT_URI, name = QUEUED_IMAGE_LABEL)
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
    // The room the floating stack takes out of the transcript: the composer's height, the strip
    // under it, and its 12dp of air. Measured on the device (uiautomator bounds, keyboard down:
    // pill 297px + 33px + 60px = the 390px between the composer's top edge and the list's own
    // bottom edge), and derived here rather than measured from those two edges.
    //
    // Those two edges are written in a layout callback and read by the next frame's composition,
    // so the list was always laid out with the *previous* frame's room, and the keyboard moves
    // exactly one term of it — the strip under the composer, which grows back as the keys leave.
    // That one frame of lag is the last few pixels of a close: the card sat that frame's strip
    // closer to the composer, and the pin put it back on the next frame. The inset that moves
    // the strip is known here, before the layout, and it is the same clamped one the shell lays
    // itself out with.
    val stackFootprint = with(LocalDensity.current) {
        composerH + (navBarPad(heldIme) + COMPOSER_BOTTOM_PAD.roundToPx()).toDp()
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
                viewModel.cacheAttachment(state.conversationId, picked)
                setDraft(uri = uri.toString(), name = picked.label)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> attach(uri, AttachKind.IMAGE) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) {
            val picked = bitmapAttachment(bitmap)
            viewModel.cacheAttachment(state.conversationId, picked)
            setDraft(uri = null, name = picked.label)
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
    // whose gateway sends no usage block (or one stored before the counts existed). The
    // arithmetic is the engine's own ([estimateTokens], shared rather than copied), and the walk
    // runs on the same key as the sums below — one pass per row that lands, not one per publish.
    val transcriptEstimate = remember(rowsKey) { state.messages.sumOf { estimateTokens(it) } }
    // The number itself is picked by [ringReading], in the order that keeps it honest: the
    // endpoint's own count for the round that just reported, else the newest stored round's, else
    // the live run's estimate off the wire's characters, else the transcript's own size. The last
    // two are what take 「暂无数据」 off the card: many gateways ignore
    // `stream_options.include_usage` (see LlmClient's own note), and for those the ring had no
    // number for the whole conversation. A proportion labelled 「约」 beats a blank.
    val reading = ringReading(
        liveReported = state.stats?.lastPromptTokens ?: 0,
        newestStored = newestUsage?.prompt ?: 0,
        liveEstimate = state.stats?.let { it.breakSystem + it.breakTools + it.breakHistory } ?: 0,
        transcriptEstimate = transcriptEstimate,
    )
    val contextUsed = reading.tokens
    // The cache figure the ring's card shows is the whole conversation's, summed once in the
    // view model ([SessionStats.cachePercent]); the newest round's own ratio is no longer read
    // here, so nothing is derived from it.
    // What the conversation has spent in total, summed once in the view model rather than
    // walked here: a long transcript is thousands of messages, and the ring's card is
    // redrawn far more often than the number changes.
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
        state.checkpoints.mapNotNull { checkpoint ->
            val owner = byId[checkpoint.toMessageId]
                ?: visible.firstOrNull { row ->
                    row.rounds.any { it.id == checkpoint.toMessageId }
                }
            // Nothing to hang it on: a branch switch or a trimmed conversation. Dropping it
            // beats drawing it somewhere it does not belong.
            val id = owner?.message?.id ?: return@mapNotNull null
            val at = indexOf[id] ?: return@mapNotNull null
            at to checkpoint
        }.groupBy({ it.first }, { it.second })
            .mapKeys { (index, _) -> visible[index].message.id }
    }
    // The run caption's text, and whether it carries a clock: the compression note is a
    // fact about the context rather than progress, so it never shows a duration.
    val statsNote = state.stats?.note.orEmpty()
    val liveStatusText = statsNote.ifEmpty {
        // No ellipsis: 「正在思考…」 was read as a truncation of something longer. The line
        // already says the state, and the clock that follows says how long it has held.
        state.status.ifBlank { "正在思考中" }
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
    // The live card and, under it, its progress caption: both are items of the list, so
    // the count everything else scrolls by includes them.
    val itemCount = visible.size + rowOffset + if (live) 2 else 0

    // The user's own messages are the landmarks of a conversation, so the status line can
    // say "turn 2 of 5" and jump between them without the engine knowing anything about it.
    // Steered lines ride inside the turn they steer, so counting them would draw one
    // landmark per interjection — a question plus two steers is still one turn.
    // The indices are the list's own, [rowOffset] included, because that is what the strip
    // jumps to; the drawn turns are the tail of the conversation, which is why the labels
    // that name them are numbered from [TurnPage.earlier] rather than from one.
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
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentTurn = turnIndices.indexOfLast { it <= firstVisible }.coerceAtLeast(0) + 1

    // Auto-scrolling is the scroll position's decision, not the transcript's: while the
    // list sits at the end a growing reply keeps it there, and the moment the user drags
    // up to read something the view stops being yanked back down.
    //
    // Saved, like the scroll position it describes. Leaving the screen for the settings page
    // disposes this composition and coming back builds it again: a plain `remember` would hand the
    // reader a fresh `true`, so a chat they had scrolled up in would start following again the
    // moment the next token landed. The position is restored by the list's own saver; this is the
    // other half of that state, and the two have to agree or the transcript drifts.
    var following by rememberSaveable { mutableStateOf(true) }
    // A finger is on the transcript right now. Its one job is to stop the watcher below from
    // handing the follow back while the press is still going: the press took it away on purpose,
    // and a re-arm mid-press is what let the next streamed frame pin the list to its end under the
    // hand. Transient by nature — a rotation mid-press is not a state worth restoring.
    var pressed by remember { mutableStateOf(false) }
    // 手指有没有越过触摸斜率。**必须是 state**：`nestedScroll` 的连接要读它决定放不放行，而那个
    // 回调不在组合里跑 —— 只有活着 state 才读得到当前值。按下时清零、循环里越过斜率就置位。
    var fingerDragged by remember { mutableStateOf(false) }
    // 按下期间列表自己动过没有。**必须按帧采样**：指针事件只在手指移动时到达，而静止的长按
    // （选文本）期间 `isScrollInProgress` 一次也采不到 —— 真机日志里 `scrolled=false` 而位置
    // 却变了 636px，就是这一条采样空档。判定本身还靠「按下/抬起的位置对」，这一条只是让日志
    // 与判定都别再漏。
    var pressScrolled by remember { mutableStateOf(false) }
    // 按下那一刻的位置（供按住期间的逐帧守护用）。真机日志（2026-10-13 两条真长按）：
    // 抬手时净位移是 0，但 frameScrolled=true —— 选择机制的 bringIntoView 在**按住期间**把列表
    // 拉走又（部分）回来，用户看到的就是中间那一跳。nestedScroll 吃得掉增量、吃不掉直接跳转，
    // 所以守护放在这里：按住且手指没在拖时，位置一旦偏离按下点就同帧放回去。
    var pressDownIndex by remember { mutableIntStateOf(-1) }
    var pressDownOffset by remember { mutableIntStateOf(0) }
    // Set while the list is being scrolled by us. Without it the follow scroll itself trips
    // the "user scrolled away" branch below, following goes false, and the run stops
    // following its own output — which is exactly what it used to do.
    var programmatic by remember { mutableStateOf(false) }
    // When the last pin ran: the scroll watcher below sees our own pin's scrolling a beat late
    // (the programmatic guard is already down by then) and would read it as a user drag.
    var lastPinAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        pressScrolled = false
        while (true) {
            if (!programmatic && listState.isScrollInProgress) pressScrolled = true
            // 按住期间的逐帧守护：选择机制把选中行拉进视野是一次程序滚动，nestedScroll 吃不掉
            // 直接跳转；这里每帧比对按下点，偏了就同帧放回去 —— 用户看不到中间那一跳。
            // 手指在拖（fingerDragged）时不碰：那时的位移是用户自己的。
            if (!fingerDragged && pressDownIndex >= 0 &&
                (listState.firstVisibleItemIndex != pressDownIndex ||
                    listState.firstVisibleItemScrollOffset != pressDownOffset)
            ) {
                programmatic = true
                try {
                    listState.scrollToItem(pressDownIndex, pressDownOffset)
                } finally {
                    programmatic = false
                }
            }
            withFrameNanos { }
        }
    }
    LaunchedEffect(listState) {
        // The hand wins the moment it moves. `canScrollBackward` is false while the list sits
        // at its end and true the instant a drag lifts it off, so following is dropped on the
        // first pixel of an upward swipe — a distance threshold (the old 48px) meant a slow
        // stream kept the list pinned while the user dragged, and they had to fight it.
        snapshotFlow { listState.isScrollInProgress to listState.canScrollBackward }
            .collect { (scrolling, canScrollBack) ->
                scrollDiag(context, "watch:emit", listState, following, itemCount, ime = heldIme) // 量完删
                // Our own pin scrolls synchronously; by the time this collector sees the
                // scrolling flag the programmatic guard is already down, and it read the pin as
                // a user drag and killed following mid-transition — the card then froze while
                // the viewport kept growing, which is the dip. Ignore scrolling that lands
                // within 120ms of a pin; a real hand drag never does.
                if (scrolling && !programmatic && canScrollBack &&
                    SystemClock.uptimeMillis() - lastPinAt > 120
                ) {
                    following = false
                    scrollDiag(context, "watch:decision=drop(hand)", listState, following, itemCount, ime = heldIme) // 量完删
                } else if (!scrolling && !pressed && listState.atVeryEnd()) {
                    // Released at the end again — or the user tapped 跳最新, which sets
                    // following itself: either way the run may follow its output again.
                    //
                    // `!pressed` is the whole guard: without it this branch also fired while a
                    // finger was *down* on a bubble, re-arming the follow the press had just taken
                    // away — and the next streamed frame pinned the list to its end under the
                    // finger. That is the jump the user reports on pressing to select text, and it
                    // needs no keyboard: the press only has to happen while the list is at its end.
                    // A press that ends without moving (a tap) still re-arms, through the release
                    // path, which is where that decision belongs.
                    following = true
                    scrollDiag(context, "watch:decision=rearm(atEnd)", listState, following, itemCount, ime = heldIme) // 量完删
                }
            }
    }
    // A new conversation starts at its newest message, whoever was reading what before.
    // The list keeps one scroll state for the whole screen, so a switch would otherwise
    // land at the previous chat's offset. Not gated by autoFollow: that setting decides
    // what *new* messages do, while this is simply where a conversation opens.
    //
    // Once per conversation, not once per visit. A `LaunchedEffect` starts again every time its
    // composable enters the composition, and leaving for the settings page and coming back is
    // exactly that — so this pin used to re-run on every return: the scroll position the list had
    // just restored was thrown away and the transcript snapped to its end, with the follow
    // re-armed, which is what read as "the page reloaded". The flag is `rememberSaveable`, so it
    // survives that trip with the position it belongs to, and it resets when the conversation
    // really changes (the key) or when the process starts fresh.
    var pinned by rememberSaveable(state.conversationId) { mutableStateOf(false) }
    LaunchedEffect(state.conversationId, pinned) {
        if (pinned) return@LaunchedEffect
        pinned = true
        following = true
        if (itemCount <= 0) return@LaunchedEffect
        programmatic = true
        try {
            scrollDiag(context, "cold-pin:begin", listState, following, itemCount) // 量完删
            // Re-checked before each scroll, because `following` is not a key of this effect: a
            // hand that lands after it starts is in the middle of a pin it cannot stop, and this
            // pass is the one that makes the jump — a cold start is the only time the landing
            // point is wrong, so the pull to the end is the only time it shows.
            if (!following) return@LaunchedEffect
            listState.scrollToItem(itemCount - 1)
            scrollDiag(context, "cold-pin:pass1", listState, following, itemCount) // 量完删
            if (!following) return@LaunchedEffect
            listState.scrollBy(FOLLOW_SCROLL_PX)
            scrollDiag(context, "cold-pin:pass2", listState, following, itemCount) // 量完删
        } finally {
            programmatic = false
        }
    }
    // The item count the last pin saw. A pin that is only following growing text needs one
    // pass: the item's new height is measured in the very layout the scroll lands in, so the
    // end it reaches is the real end. The second pass exists for the frame an item was
    // *added* in, where its height is still unknown — and running it on every publish doubled
    // the work of a pin that already runs twelve times a second: four full re-measures of the
    // list per publish while an answer streams, which is one of the frames the ghost lives in.
    var pinnedCount by remember { mutableIntStateOf(-1) }
    // Every event that can change the height of the last item is a trigger: the streamed
    // text, a tool row appearing or finishing, and the status line — not just the count.
    LaunchedEffect(
        itemCount,
        state.streaming.length,
        state.reasoning.length,
        state.liveParts,
        state.status,
        settings.autoFollow,
        // `following` is a key, not only a check, and that is what makes a press stop a pin that is
        // already on its way: `scrollToItem` and `scrollBy` are suspending, so a gate re-checked
        // between them cannot stop the scroll they have already asked for — the press lands in that
        // suspension and the list moves under the finger anyway. Keying the effect on the flag makes
        // the press cancel the coroutine, and a cancelled scroll stops where it is.
        following,
    ) {
        // Re-taken before every scroll below, not just at the top. `following` is not a key of
        // this effect, so a finger that lands while a pin is in flight used to be dragged to the
        // end by the scrolls already on their way — which is the jump the user reported, and the
        // reason it only ever showed on a cold start: that is where the first pin takes the
        // two-pass path, with a frame wait between the passes that gives the hand time to land.
        fun gateOpen() = following && settings.autoFollow
        if (itemCount <= 0 || !gateOpen()) return@LaunchedEffect
        // A drag in flight pauses the pin: content updates land between the frames of a
        // gesture, and pinning on one of them yanks the list back to the end under the user's
        // finger — which is what made "scroll up a little" not enough. The release effect
        // above has already dropped `following` by then; this is the belt to that pair of
        // braces for the frame or two before it runs.
        if (listState.isScrollInProgress && !programmatic) return@LaunchedEffect
        val added = pinnedCount != itemCount
        pinnedCount = itemCount
        programmatic = true
        try {
            if (!gateOpen()) return@LaunchedEffect
            listState.scrollToItem(itemCount - 1)
            scrollDiag(context, "pin:pass1", listState, following, itemCount) // 量完删
            if (!gateOpen()) return@LaunchedEffect
            listState.scrollBy(FOLLOW_SCROLL_PX)
            scrollDiag(context, "pin:pass1b", listState, following, itemCount) // 量完删
            // A second pass once the new item has been measured. The first scroll runs in the
            // frame the item was added, when its height is still unknown, so the end it lands on
            // is short by exactly the height of what just appeared — the status line of a fresh
            // question ended up under the composer that way.
            //
            // Both passes are instant. Animating this was a mistake: it is a correction of a few
            // dozen pixels that the user did not ask to watch, and a moving scroll position after
            // every send is worse than a jump they never see.
            if (added) {
                withFrameNanos { }
                if (!gateOpen()) return@LaunchedEffect
                listState.scrollToItem(itemCount - 1)
                if (!gateOpen()) return@LaunchedEffect
                listState.scrollBy(FOLLOW_SCROLL_PX)
                scrollDiag(context, "pin:pass2", listState, following, itemCount) // 量完删
            }
        } finally {
            programmatic = false
        }
    }

    // The keyboard is not a scroll: it squeezes this screen's viewport from the bottom, so a
    // list pinned to its end keeps its offset and leaves its last card below the fold while
    // the composer rises above the keys.
    //
    // The trigger is the viewport's own height, not the IME inset. Measured on the device with
    // the keyboard up and down (uiautomator bounds): pinning on the inset value left the last
    // card 108px above the composer with the keyboard up against 48px with it down — the inset
    // effect ran on the last value it was handed, and the layout settled 60px later with nothing
    // left to re-pin it. The viewport height changes when the layout changes, which is the thing
    // that actually has to be answered.
    val viewportHeight by remember {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    // The keyboard's own inset, read raw: the platform animates it frame by frame, so this
    // effect re-runs on every frame of the keyboard's motion and the instant pin inside it is
    // what keeps the last card glued above the composer. This is the trigger that worked in
    // 0.8.15-0.8.17; the viewport-height variants that replaced it (single compensation, then a
    // frame loop) each regressed one direction of the transition, so it comes back as it was.
    val keyboardInset = WindowInsets.ime.getBottom(LocalDensity.current)
    // One keyboard transition has one direction. The platform's inset animation ends with a
    // small rebound of a few pixels the other way, and following it faithfully is what put a
    // visible one-frame hitch at the end of every close on a 120Hz display. Opposite-direction
    // changes below the clamp are the rebound, not a new transition: they are dropped. Real
    // frames of a transition are 30-80px, so the clamp cannot eat one.
    var followDir by remember { mutableStateOf(0) }
    var lastInset by remember { mutableStateOf(-1) }
    LaunchedEffect(keyboardInset) {
        val previous = lastInset
        lastInset = keyboardInset
        if (previous < 0) return@LaunchedEffect
        val delta = keyboardInset - previous
        if (delta == 0) return@LaunchedEffect
        val dir = if (delta > 0) 1 else -1
        if (followDir != 0 && dir != followDir && abs(delta) <= REBOUND_CLAMP_PX) return@LaunchedEffect
        followDir = dir
        // No pin here on purpose. This effect runs before layout, so a pin here uses last
        // frame's composer position and lands a few pixels short until the next frame — the
        // tiny end-of-close "settle" the user felt as a rebound. The pin lives in the composer's
        // layout callback instead (onPlaced), where the position is this frame's.
    }
    // The transition is over once the inset has been still for a beat: forget its direction so
    // the next one, whichever way it goes, is followed from its first frame.
    LaunchedEffect(keyboardInset) {
        delay(300)
        followDir = 0
    }
    // The keyboard is a layout change, so the list has to be moved by exactly what the
    // composer is moved by, in the same frame the composer moves.
    //
    // Two terms, because the room the stack takes out of the transcript has two: the scaffold
    // the composer sits on, which the clamped inset moves by the whole delta, and the strip the
    // composer pads itself by at the bottom, which grows back by that same delta as the keys
    // leave (it is the navigation bar's height once the keyboard is fully down, and nothing
    // while it is up). The list's own bottom padding carries both terms — see stackFootprint —
    // so compensating by the first one alone left the card that frame's strip short, which is
    // the settle the user kept feeling. Compensating here, before this frame's layout, puts the
    // last card where the composer is going to be in the frame it gets there. The clamp is the
    // same one the shell lays itself out with (LocalHeldImePx), so the platform's
    // end-of-animation rebound is not followed by either of them.
    //
    // The pin in the composer's layout callback below stays as the correction, but on its own
    // it is always one frame late: the LazyColumn is measured before the composer is placed,
    // so a scroll dispatched from that callback can only land in the next frame's layout.
    var lastHeldIme by remember { mutableIntStateOf(-1) }
    LaunchedEffect(heldIme) {
        val previous = lastHeldIme
        lastHeldIme = heldIme
        if (previous < 0 || heldIme == previous) return@LaunchedEffect
        if (!following || !settings.autoFollow) return@LaunchedEffect
        programmatic = true
        lastPinAt = SystemClock.uptimeMillis()
        try {
            scrollDiag(context, "ime:delta=$previous->$heldIme", listState, following, itemCount, ime = heldIme) // 量完删
            listState.dispatchRawDelta(
                (heldIme - previous).toFloat() +
                    (navBarPad(heldIme) - navBarPad(previous)).toFloat(),
            )
        } finally {
            programmatic = false
        }
    }
    val atEnd by remember { derivedStateOf { listState.atEnd() } }

    // 外观 → 气泡宽度. Measured against the screen, which is what the setting promises
    // in its own words: 标准 (0.94) stays wider than the transcript's own 16dp margins,
    // so the default leaves every card exactly as wide as it has always been, and only
    // 窄 pulls the reply in.
    val bubbleMaxWidth = with(LocalConfiguration.current) { screenWidthDp.dp } *
        settings.bubbleWidthFraction

    fun jumpToLatest() {
        scope.launch {
            following = true
            if (itemCount > 0) {
                programmatic = true
                try {
                    scrollDiag(context, "jump:latest", listState, following, itemCount) // 量完删
                    listState.scrollToItem(itemCount - 1)
                    listState.scrollBy(FOLLOW_SCROLL_PX)
                } finally {
                    programmatic = false
                }
            }
        }
    }

    // 提示条自己走：默认 10 秒（用户：「10 秒消失吧」），外观页可改成 5/30 秒或永不。
    // 键里带上秒数，改设置时重新计时；错误换了内容也重新计时（同一句话不重来）。
    LaunchedEffect(state.error, settings.bannerSeconds) {
        if (state.error == null) return@LaunchedEffect
        val seconds = settings.bannerSeconds
        if (seconds <= 0) return@LaunchedEffect
        delay(seconds * 1000L)
        viewModel.dismissError()
    }
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
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = with(LocalDensity.current) {
                                    WindowInsets.statusBars.getTop(this).toDp()
                                } + 60.dp,
                                bottom = stackFootprint + 40.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyHero(onPick = { setDraft(text = it) }, assistant = assistant)
                    }
                } else {
                    // In px, converted once: a raw pixel threshold would be a couple of
                    // millimetres on a dense screen and a centimetre on a sparse one.
                    val releaseDragPx = with(LocalDensity.current) { RELEASE_DRAG_DP.toPx() }
                    // 手指有没有真的拖动过：**触摸斜率**，也就是 Compose 的滚动开始生效的那条线。
                    // 手指没越过它，列表就绝不可能因为手指而滚动 —— 这是「静止长按」的判据（见
                    // restoresPositionAfterLongPress）。
                    val touchSlopPx = with(LocalDensity.current) {
                        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    }
                    // The gesture system's own long-press timeout, read rather than assumed: our
                    // constant sat below it (450 against the platform's 500), so a press in that
                    // window started a selection — the IME collapsing with it — while the release
                    // below still counted as a tap and handed the follow back, and the follow is
                    // what moves the list when the keyboard goes. One clock, not two.
                    val holdToSelectMs = LocalViewConfiguration.current.longPressTimeoutMillis
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // 按住期间不许动：手指按下、又没越过触摸斜率时，把一切滚动增量都吃掉。
                            //
                            // 为什么不能只靠事后还原（restoresPositionAfterLongPress）：框架把选中
                            // 行拉进视野是**先发生**的，用户已经看见了那段位移，我们再把位置放回去，
                            // 观感就是「中途滚了一下才滚回来」（用户复测的原话）。这里在滚动**到达
                            // 列表之前**就返回 Offset.Zero 把它消费掉，那次滚动根本不会发生。
                            //
                            // 放行条件只有一条：手指真的拖动过（越过触摸斜率）⇒ 用户自己要滚，一律
                            // 放行。`pressed`/`fingerDragged` 是长按路径一直在用的那两个瞬态量，读的是
                            // 活的 state（委托属性捕获的是 MutableState 本身），不是组合时的快照。
                            .nestedScroll(
                                object : NestedScrollConnection {
                                    override fun onPreScroll(
                                        available: Offset,
                                        source: NestedScrollSource,
                                    ): Offset =
                                        if (pressed && !fingerDragged) available else Offset.Zero

                                    override fun onPostScroll(
                                        consumed: Offset,
                                        available: Offset,
                                        source: NestedScrollSource,
                                    ): Offset =
                                        if (pressed && !fingerDragged) available else Offset.Zero
                                },
                            )
                            // A finger on the transcript releases the follow the instant it lands,
                            // not after a threshold. Waiting for the 450ms long press — or for 12dp
                            // of travel — meant a streaming answer kept pinning the list to its end
                            // under a finger that had already started selecting text, and the
                            // position, with the selection on it, moved. A drag wins that argument
                            // through the scroll watcher below; this gives a press the same right
                            // from its first frame. The events are only observed, at the initial
                            // pass, so selection and scrolling still receive them untouched —
                            // nothing here consumes anything.
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(PointerEventPass.Initial)
                                        if (down.changes.none { it.pressed }) continue
                                        val pressedAt = SystemClock.uptimeMillis()
                                        val downY = down.changes.first().position.y
                                        // Where the transcript stood when the finger landed. Compared
                                        // with the same pair at release: any difference means the
                                        // list moved, which is the one fact that must keep the follow
                                        // released (see restoresFollowAfterPress).
                                        val downIndex = listState.firstVisibleItemIndex
                                        val downOffset = listState.firstVisibleItemScrollOffset
                                        val wasFollowing = following
                                        following = false
                                        pressed = true
                                        fingerDragged = false
                                        pressDownIndex = downIndex
                                        pressDownOffset = downOffset
                                        scrollDiag(context, "press:down", listState, wasFollowing, itemCount, ime = heldIme) // 量完删
                                        var movedUp = false
                                        // Whether the list itself moved. A hand can travel less than
                                        // the 12dp above and still send the list a long way: a fling,
                                        // and — the case that matters — a drag *downward*, which is
                                        // the gesture that reads older messages. `programmatic` is
                                        // excluded so our own pin cannot pass for a hand: a tap that
                                        // happens to land during a pin is still a tap.
                                        var scrolled = false
                                        // 手指有没有越过触摸斜率。越过之前，Compose 的滚动根本不会
                                        // 因为手指而动 —— 所以「没越过 + 列表却动了」只可能是别的东西
                                        // 在滚（选择机制把选中行拉进视野）。这是长按还原位置的判据，
                                        // 也是 nestedScroll 放不放行的开关（它是外层那个 state，
                                        // 按下时已清零，这里只置位）。
                                        var released = false
                                        while (!released) {
                                            val next = awaitPointerEvent(PointerEventPass.Initial)
                                            released = next.changes.none { it.pressed }
                                            // Remembered, not acted on: the follow is already down.
                                            // An upward move is what separates a drag, which must
                                            // stay released, from a plain tap, which must not.
                                            val dy = downY - next.changes.first().position.y
                                            if (!released && dy > releaseDragPx) {
                                                movedUp = true
                                            }
                                            if (!released && abs(dy) > touchSlopPx) {
                                                fingerDragged = true
                                            }
                                            if (!released && !programmatic && listState.isScrollInProgress) {
                                                scrolled = true
                                            }
                                        }
                                        // A tap is not the user reaching into the content: short, never
                                        // dragged up, and a list that never moved. The long press that
                                        // starts a selection, any drag — in either direction — and
                                        // anything the list itself scrolled leave the follow released
                                        // until the scroll watcher re-arms it at the end.
                                        //
                                        // 「Never moved」 is the position pair, not the finger: a drag of
                                        // a few pixels, or one that ends before the scroll-progress flag
                                        // is ever sampled, still moved the list. And the end is the
                                        // absolute end — a few pixels short of it is where the reader
                                        // was, not where the follow wants to be.
                                        val atEndNow = listState.atVeryEnd()
                                        // The finger is up: from here the watcher may hand the
                                        // follow back, and the release rule below decides whether it
                                        // should.
                                        pressed = false
                                        // 按下期间列表动过没有：指针采样（手指动的拖拽）与帧采样
                                        // （静止长按里框架自己滚的）取或。
                                        val movedByList = scrolled || pressScrolled
                                        scrollDiag(context, "press:up:movedUp=$movedUp:fingerDragged=$fingerDragged:scrolled=$scrolled:frameScrolled=$pressScrolled:atEnd=$atEndNow", listState, following, itemCount, ime = heldIme) // 量完删
                                        val held = SystemClock.uptimeMillis() - pressedAt
                                        val nowIndex = listState.firstVisibleItemIndex
                                        val nowOffset = listState.firstVisibleItemScrollOffset
                                        val listMoved = nowIndex != downIndex || nowOffset != downOffset
                                        // 静止的长按（选文本）**期间列表自己动了**：那是选择机制把内容
                                        // 拉进视野，不是用户的意图，用户看到的就是「长按一下，位置跳了」。
                                        // 把位置放回按下时的地方，并把 follow 还原成按下前的值 —— 长按
                                        // 之后的状态与长按之前完全一致。手指越过触摸斜率动过的（拖拽/
                                        // 滑动）不碰：那时位置变化就是用户自己造成的。
                                        if (restoresPositionAfterLongPress(
                                                fingerDragged = fingerDragged,
                                                listMoved = listMoved,
                                                heldMs = held,
                                                holdMs = holdToSelectMs,
                                            )
                                        ) {
                                            // 受限挂起上下文（`awaitPointerEventScope`）里不能直接调
                                            // `scrollToItem` —— 它是 LazyListState 的挂起成员，编译器
                                            // 只允许受限范围内的那些。滚动作交给屏幕自己的 scope，
                                            // 判定与日志留在这里。
                                            scrollDiag(
                                                context,
                                                "press:up:restore-position=$downIndex+$downOffset",
                                                listState,
                                                wasFollowing,
                                                itemCount,
                                                ime = heldIme,
                                            ) // 量完删
                                            following = wasFollowing
                                            val restoreIndex = downIndex
                                            val restoreOffset = downOffset
                                            scope.launch {
                                                programmatic = true
                                                try {
                                                    listState.scrollToItem(restoreIndex, restoreOffset)
                                                } finally {
                                                    programmatic = false
                                                }
                                            }
                                        } else if (restoresFollowAfterPress(
                                                movedUp = movedUp,
                                                scrolledDuringPress = movedByList,
                                                downIndex = downIndex,
                                                downOffset = downOffset,
                                                nowIndex = nowIndex,
                                                nowOffset = nowOffset,
                                                atVeryEnd = atEndNow,
                                                heldMs = held,
                                                holdMs = holdToSelectMs,
                                            )
                                        ) {
                                            following = wasFollowing
                                        }
                                        pressDownIndex = -1
                                    }
                                }
                            },
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            // The floating header owns the status bar strip, so the
                            // first bubble starts below its discs with 16dp of air:
                            // status bar + 4dp lift + 56dp row + 16dp gap.
                            top = with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            } + 76.dp,
                            // The composer floats over the list, so the list leaves exactly the
                            // room the stack occupies plus the air below it. Measured as the
                            // distance from the composer's top edge to the list's own bottom
                            // edge — not as the composer's height, which is what left the two
                            // states different: the space *under* the composer changes with the
                            // keyboard (96px with it down, 36px with it up on this device), and
                            // 16dp of air, the number the user measured as right with the
                            // keyboard down: the last card reads as separate from the field
                            // without a band of empty canvas under it.
                            bottom = stackFootprint + 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (page.hasEarlier) {
                            // The way back into the turns that are not drawn yet, and the only
                            // way there: the page is a suffix of the conversation, so an older
                            // turn is reached from the oldest end forward, never by jumping into
                            // the middle of a turn the grouping has not seen. One page a tap;
                            // reading is not following, so the follow is dropped with it.
                            item(key = "load-earlier") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextButton(
                                        onClick = {
                                            following = false
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
                        itemsIndexed(visible, key = { _, row -> row.message.id }) { _, row ->
                            // One placeable per item: the list's own 16dp spacing applies
                            // between the placeables of a single item as well, which is what
                            // held the timestamp a full 16dp above the bubble it belongs to.
                            // Inside this Column the two sit 3dp apart, and the 16dp goes back
                            // to meaning what it says — the air between messages.
                            Column {
                                // 外观 → 显示时间: off means no labels at all, not fewer.
                                if (settings.showTimestamps && row.showTimeLabel) {
                                    TimeLabel(row.message.timestamp)
                                    Spacer(Modifier.height(3.dp))
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
                                // 中断/截断/失败的原因属于**这一轮的回答**，所以画在这张卡片里，
                                // 而不是浮在屏幕上的通知条（用户原话「要输出在卡片里面」）。
                                // 只挂在最后一张卡片上：它就是刚刚结束的那一轮。
                                if (row.message.id == visible.lastOrNull()?.message?.id) {
                                    state.error?.let { reason -> RunReasonLine(reason) }
                                }
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
                        if (live) {
                            // Keyed by conversation: the live row keeps its slot for as long
                            // as it is on screen, and a switch used to hand the next chat
                            // whatever the previous one had left in that slot.
                            item(key = "streaming:${state.conversationId}") {
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
                                    // 上下对齐（账在下面两行算）：卡片自身的下内边距是 **10dp**
                                    // （AssistantRow 的 Column，`:2651`），所以「卡片底 → 状态行顶」
                                    // ＝ 10 + top；「状态行底 → composer 顶」＝ bottom + **16dp**
                                    // （列表底部余白 `contentPadding.bottom = stackFootprint + 16.dp`，
                                    // `:1112`）。取 top = 8、bottom = 2 ⇒ 两边都是 **18dp**，相等。
                                    // 键盘抬起/收起都一致：那 16dp 是相对 composer 顶边算的。
                                    if (state.runningHere) {
                                        RunStatusLine(
                                            text = liveStatusText,
                                            startedAt = runStartedAt[runKey] ?: 0L,
                                            timed = statsNote.isEmpty(),
                                            modifier = Modifier.padding(
                                                start = 4.dp,
                                                top = 8.dp,
                                                bottom = 2.dp,
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
                                    timed = statsNote.isEmpty(),
                                    // 上下对齐（与卡片下方那一行同一套账）：上面是**列表间距 16dp**
                                    // 加这里的 top，下面是这里的 bottom 加**列表底部余白 16dp**
                                    // （contentPadding.bottom = stackFootprint + 16dp，`:1112`）。
                                    // 取 top = bottom = 2dp ⇒ 上下都是 18dp，与「卡片 → 状态行」
                                    // 那条的 18dp 完全相同（卡片自身下内边距 10dp + top 8dp）。
                                    // 键盘抬起/收起都一样：那 16dp 是相对 composer 顶边算的，
                                    // composer 动了它跟着动，间隙本身不变。
                                    modifier = Modifier.padding(
                                        start = 4.dp,
                                        top = 2.dp,
                                        bottom = 2.dp,
                                    ),
                                )
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
            // 浮层提示条只留给**没有卡片可依附**的情况：有回答卡片时，中断/截断/失败的原因画在
            // 那张卡片里面（见 RunReasonLine）——用户原话「要输出在卡片里面」。
            state.error?.takeIf { visible.isEmpty() }?.let { message ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 顶栏那一行占 status bar + 4dp lift + 56dp，所以 64dp 就是「按钮下方一点点」；
                        // 68dp 时它离顶栏还有一段，读起来像浮在内容中间（用户要求往上一点）。
                        .padding(top = statusBarInset + 64.dp),
                ) {
                    ErrorCard(message = message, onDismiss = { viewModel.dismissError() })
                }
            }
            // The floating header: two discs and the title capsule, no bar of its own.
            ChatTopBar(
                title = state.title,
                onNew = { viewModel.newConversation() },
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
                    // the label carries the opening words of the question too — but only
                    // its first line and only 8 characters, because the label floats over
                    // the transcript and has to be readable in the glance it gets.
                    // Numbered from [TurnPage.earlier]: the drawn turns are the tail of the
                    // conversation, and calling the oldest one drawn 「第 1 轮」 would be a lie.
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
                    // Choosing a turn is reading, not following: the stream must not drag
                    // the view back down while the user is somewhere else in the answer.
                    following = false
                    // `getOrNull`, not `turnIndices[turn]`: the row the finger was on is a row of
                    // the window the gesture started in, and a window can shrink under it — a
                    // retracted 插话, a branch switch, another conversation opened by a deep link.
                    // A jump to a turn that is no longer drawn is a no-op, never a crash.
                    turnIndices.getOrNull(turn)?.let { index ->
                        scrollDiag(context, "jump:turn=$index", listState, following, itemCount) // 量完删
                        scope.launch { listState.animateScrollToItem(index) }
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
                    sessionTokens = sessionTokens,
                    sessionEstimate = sessionEstimate,
                    sessionSplit = sessionSplit,
                    sessionTurns = sessionTurns,
                    sessionPerSecond = session?.perSecond ?: 0.0,
                    sessionCachePercent = session?.cachePercent ?: 0,
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
                    attachment = attachment,
                    onClearAttachment = {
                        viewModel.cacheAttachment(state.conversationId, null)
                        setDraft(uri = null, name = null)
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
                        viewModel.send(input, attachment?.base64)
                    },
                    onStop = { viewModel.stop() },
                    onSteer = { text, image -> viewModel.steer(text, image) },
                    onRequestCompress = { viewModel.requestCompress() },
                    onFocused = {
                        // Only a transcript that was already at its end follows the
                        // keyboard. Scrolling to the last item would put its *top* at the
                        // top of the viewport — the bottom padding is what makes that
                        // reachable — which is the drift the field tap used to cause, and
                        // a reader parked mid-conversation must not be dragged anywhere.
                        if (following && itemCount > 0) {
                            scope.launch {
                                programmatic = true
                                try {
                                    listState.scrollToItem(itemCount - 1)
                                    listState.scrollBy(FOLLOW_SCROLL_PX)
                                } finally {
                                    programmatic = false
                                }
                            }
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
                    showJump = !atEnd,
                    onHeightChanged = { composerH = it },
                    onPlaced = {
                        // The keyboard follow lives here, in the composer's own layout
                        // callback: this runs after the composer has been placed for this
                        // frame, so the pin uses the position the user is actually seeing.
                        // Pinning from the inset effect instead ran before layout and landed a
                        // few pixels short every frame, which read as a settle at the end of a
                        // close. At the end dispatchRawDelta consumes nothing and triggers no
                        // further layout, so the loop stops by itself.
                        if (following && settings.autoFollow) {
                            programmatic = true
                            lastPinAt = SystemClock.uptimeMillis()
                            try {
                                listState.dispatchRawDelta(FOLLOW_SCROLL_PX)
                            } finally {
                                programmatic = false
                            }
                        }
                    },
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
 * The chat header: a solid bar that owns the status bar strip. Three slots, nothing
 * else — drawer handle, the conversation's name, new conversation. The floating
 * console moved to the drawer: a fourth disc here only ever read as clutter over the
 * transcript.
 *
 * The model switcher used to live here too and was moved down to the composer: a pill
 * between two round buttons left the conversation's name about four characters wide.
 */
@Composable
private fun ChatTopBar(
    title: String,
    onNew: () -> Unit,
) {
    // Read outside the click lambda: the composition local is a composable read, and the
    // lambda is not composable.
    val openDrawer = LocalOpenSidebar.current
    // No surface of its own: the header floats over the transcript and the list scrolls
    // behind it, so each button carries its own disc and shadow instead of a bar colour.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // The whole bar sits 4dp lower than the status strip: the discs read as
            // floating over the transcript, not glued to the notch.
            .padding(top = 4.dp)
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Both discs come from the shared helpers at their 40dp/20dp default — see the
        // table in CircleActionButton — so the header, the composer and a page's back
        // button are the same disc. The capsule between them is [PressPill], the same
        // shape and shadow the composer's input uses: this bar floats over the transcript
        // and has no surface of its own, so the two pills are what the user reads as "a
        // field I can type in" and "the conversation I am in".
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_menu_soft),
            contentDescription = "打开侧边栏",
            onClick = { openDrawer?.invoke() },
        )
        PressPill(
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
        ) {
            val style = titleBarStyle()
            Text(
                text = title,
                // 行盒收紧到字形高度，居中才是真的居中（用户：「标题是不是没有垂直居中」）。
                // 默认 includeFontPadding 会在行盒**顶部**加一段 ascent 留白，中文的视觉重量又
                // 偏上，两个不对称叠起来，CenterVertically 居中的是一个比字高、且上重下轻的盒子
                // ⇒ 字读起来偏离胶囊中心。收紧后行盒＝字形盒，居中的就是字本身；旧的
                // `padding(top = 3.dp)` 光学补偿是在补行盒的不对称，根除了就不需要它了。
                style = style.copy(
                    lineHeight = style.fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_compose),
            contentDescription = "新对话",
            onClick = onNew,
        )
    }
}

/** The bar's title: 15sp medium, scaled off titleMedium so 字号 still applies. */
@Composable
private fun titleBarStyle(): TextStyle {
    val title = MaterialTheme.typography.titleMedium
    return title.copy(fontSize = title.fontSize * (15f / 17f))
}

/**
 * The thinking level a run in this conversation would actually send.
 *
 * One chain, read by both the caption and the sheet so they cannot disagree with the wire:
 * the conversation's own pick first, then the endpoint's own declaration (only meaningful
 * while that endpoint owns its sampling), then the app-wide default. The view model's
 * `chooseReasoning` writes to the first link and nothing else.
 */
private fun reasoningWire(
    settings: AppSettings,
    provider: ProviderConfig?,
    conversationLevel: String?,
): String = conversationLevel
    ?: provider?.takeIf { it.ownSampling }?.reasoningEffort
    ?: settings.reasoningEffort

/** A flat text dropdown: label plus a caret, no container of its own. */
@Composable
private fun UnifiedSelector(
    settings: AppSettings,
    /** The conversation's persona and its own pick, so the caption names what a run sends. */
    assistantId: String?,
    conversationProviderId: String?,
    conversationModel: String?,
    /** The conversation's own thinking level, if it ever picked one. */
    conversationReasoningEffort: String?,
    onClick: () -> Unit,
) {
    val assistant = assistantId?.let { settings.assistant(it) } ?: settings.activeAssistant()
    val choice = settings.choiceFor(conversationProviderId, conversationModel, assistant)
    val provider = settings.providers.firstOrNull { it.id == choice.providerId }
        ?: settings.providers.firstOrNull {
            it.baseUrl.trimEnd('/') == choice.baseUrl.trimEnd('/')
        }
    val wire = reasoningWire(settings, provider, conversationReasoningEffort)
    val level = REASONING_LEVELS.firstOrNull { it.first == wire }?.second ?: "默认"
    val model = provider?.displayModel(choice.model)
        ?.takeIf { it != choice.model }
        ?: choice.model.substringAfter('/').ifBlank { "选模型" }
    Row(
        modifier = Modifier
            // No cap on the row: the composer's own Spacer(weight) absorbs whatever is
            // left, and capping the row was what squeezed the name down to a single
            // ellipsis while 200dp of the row sat empty. The name carries its own cap
            // instead, and the badge and the caret take the width they need.
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = model,
            // labelMedium as it comes: it is 13sp already, and writing the same number again
            // as a literal also pinned the size against 「外观 → 字号」, which scales the ramp.
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Weighted, so it is measured last and gets whatever the badge and the caret leave
            // — they are fixed short things and must never be the ones to give way. `fill =
            // false` keeps a short name from stretching its own tap target across the row.
            // Wide enough for a real model id (Qwen3.8-Flash), narrow enough that the badge,
            // the ring, the mic and the send button still fit one row.
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 160.dp),
        )
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                text = level,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                // Never ellipsized: the level is a fixed short word, and a "xh…" badge is
                // both unreadable and pointless — the name beside it is what gets cut.
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * What the transcript area holds while the conversation to open is still being read.
 *
 * Deliberately almost nothing: the header is already drawn (it is identical in every state)
 * and the body carries one quiet line. Anything more — the empty state, an input field with
 * its hint — is a conversation the user is not in, which is what made a cold start look like
 * it had opened a new chat first and then jumped to the real one.
 */
@Composable
private fun LoadingSkeleton() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "加载会话中…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * Every model the user ticked used to live in a dropdown here; the 生成设置 sheet
 * owns the list now, so this menu is gone with its last caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyHero(onPick: (String) -> Unit, assistant: Assistant? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own mark on its gradient disc: the launcher asset is pure white for
        // the home screen, and white on a light canvas would vanish. A persona with an uploaded
        // picture shows that picture instead — this hero is where the user is about to talk to
        // *it*, and an empty chat was the one assistant surface still showing the app's feather.
        if (assistant?.avatarPath.isNullOrBlank()) {
            SuixingMark(size = 92.dp)
        } else {
            AssistantAvatar(assistant = assistant, size = 92.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text("我们开始吧", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "说一句话，它就能读屏、点击、翻文件、跑命令。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        // A short screen with the keyboard up must not let the suggestions push the
        // hero off: the chip column scrolls inside 40% of the display height.
        val chipMax = with(LocalConfiguration.current) { screenHeightDp.dp } * 0.4f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = chipMax)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS.forEach { suggestion ->
                    Surface(
                        onClick = { onPick(suggestion) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = suggestion,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "首次使用请先到「能力」页打开权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_circle_alert), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(ImageVector.vectorResource(R.drawable.ic_x), contentDescription = "关闭", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * One bubble of the transcript: the message that owns it, the parts of the reply in the
 * order they arrived, and whether it opens a new time bucket.
 */
internal data class ChatRow(
    val message: StoredMessage,
    val showTimeLabel: Boolean,
    val parts: List<Part> = emptyList(),
    val branch: Branch? = null,
    /**
     * Every round the reply took, oldest first.
     *
     * The engine stores one assistant message per round, so a reply that ran a tool
     * arrives as two or three of them. They are one answer to the user, so they are
     * folded into one bubble here; [message] stays the newest round because that is
     * the one the action row and the time label belong to.
     */
    val rounds: List<StoredMessage> = listOf(message),
) {
    val usage: Usage? get() = Usage.of(rounds)
}

/**
 * One piece of a reply, in the order it arrived.
 *
 * A single run can think, call tools, think again, call more tools and finally answer.
 * Each of those is its own part and keeps its own block: merging two rounds of thinking
 * into one hides where the model changed its mind, and starting a second bubble for the
 * next round is what used to make one answer arrive as three.
 */
/**
 * One tool call, from either side of the run.
 *
 * The live run and the stored transcript draw the same row, so a call that starts while
 * the model is still working and the same call after it lands are the same widget — which
 * is what stops the tool block from flickering into a different shape when the run ends.
 */
internal data class ToolLine(
    val name: String,
    val args: String,
    val done: Boolean,
    val ok: Boolean,
    val elapsedMs: Long,
    /** The stored result; absent while the call is still running. */
    val message: StoredMessage? = null,
) {
    companion object {
        fun of(message: StoredMessage): ToolLine = ToolLine(
            name = message.toolName.orEmpty(),
            args = message.toolArgs.orEmpty(),
            done = true,
            ok = message.toolOk != false,
            elapsedMs = message.elapsedMs,
            message = message,
        )

        fun of(call: LiveTool): ToolLine = ToolLine(
            name = call.name,
            args = call.args,
            done = call.done,
            ok = call.ok,
            elapsedMs = call.elapsedMs,
            message = call.message,
        )
    }
}

internal sealed interface Part {
    /**
     * One round of thinking, with that round's own numbers.
     *
     * [runId] is the run that produced it, and it is what makes a think the same block
     * before and after it is stored: the live row and the saved message are two different
     * composables, and only the run id is common to both. Blank for a message stored
     * before runs were keyed.
     */
    data class Thinking(
        val text: String,
        val tokens: Int,
        val elapsedMs: Long,
        val runId: String = "",
    ) : Part

    /** The tool calls made after the part before this one. */
    data class Tools(val calls: List<ToolLine>) : Part

    /** The words the model wrote. */
    data class Text(val text: String) : Part

    /**
     * A line the user typed mid-answer.
     *
     * It is a part of this turn rather than a turn of its own: that is what keeps the
     * bubble whole while still drawing the line exactly where it landed, and it is why
     * the transcript does not read as though the model volunteered something.
     */
    data class Steer(val message: StoredMessage) : Part
}

/**
 * What a reply cost.
 *
 * Context size is cumulative, so only the newest round knows the real prompt size and
 * only its cache count is meaningful; tokens and time add up across rounds instead.
 */
internal data class Usage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val reasoning: Int = 0,
    val cached: Int = 0,
    val elapsedMs: Long = 0,
) {
    val known: Boolean get() = prompt > 0 || completion > 0

    /** Output tokens per second, or zero when the endpoint never said how long it took. */
    val perSecond: Double
        get() = if (elapsedMs <= 0 || completion <= 0) 0.0 else completion * 1000.0 / elapsedMs

    companion object {
        fun of(rounds: List<StoredMessage>): Usage? {
            if (rounds.none { it.usageKnown }) return null
            val newest = rounds.last()
            return Usage(
                prompt = newest.promptTokens,
                completion = rounds.sumOf { it.completionTokens },
                reasoning = rounds.sumOf { it.reasoningTokens },
                cached = newest.cachedTokens,
                elapsedMs = rounds.sumOf { it.elapsedMs },
            )
        }
    }
}

/**
 * The `‹ 2/3 ›` switcher under a reply that has siblings, plus 重新回答.
 *
 * [index] is where [runs] the user is looking at, so the arrows are pure
 * navigation and never rewrite the transcript.
 */
internal data class Branch(
    val turnId: String,
    val runs: List<String>,
    val index: Int,
    /** Only the last question in the transcript is worth answering again. */
    val canRegenerate: Boolean,
)

/**
 * Whether a stored message has anything to draw at all.
 *
 * A turn that only asked for tools stores an assistant message with empty text. It
 * still belongs in the wire history, but drawing its bubble is what produced the
 * stray empty bubbles between tool chips.
 */
private fun StoredMessage.isVisible(): Boolean = when (role) {
    Roles.USER -> text.isNotBlank() || imageBase64 != null
    Roles.ASSISTANT -> text.isNotBlank() || !reasoning.isNullOrBlank()
    Roles.TOOL -> true
    // 失败回执（VM 在 Failed 时写的一条 system 消息）：它存在的意义就是被看见，所以有字就画。
    // 磁盘上此前没有任何 system 消息（全仓 11 处 StoredMessage 构造点 + 真机两份 chat JSON 核实），
    // 上下文摘要只进 wire 不落盘，所以这个分支不会被摘要误触发。
    Roles.SYSTEM -> text.isNotBlank()
    else -> false
}

/**
 * Folds the transcript into bubbles.
 *
 * Every tool result is claimed by the assistant bubble above it, so a reply and the
 * tools it ran read as one block; a text-less assistant turn is skipped rather than
 * drawn as an empty bubble.
 */
internal fun groupRows(
    messages: List<StoredMessage>,
    activeRuns: Map<String, String>,
    running: Boolean,
): List<ChatRow> {
    val turnOf = Transcript.turnOf(messages)
    val runs = Transcript.runsByTurn(messages, turnOf)
    val chosen = Transcript.chosenRuns(messages, activeRuns)
    val shown = Transcript.active(messages, activeRuns)
    // A steer line is the user talking mid-answer, not a new question: only a real
    // question can be re-answered or own the branch switcher.
    val lastQuestion = shown.lastOrNull { it.role == Roles.USER && !it.steer }?.id.orEmpty()

    // The switcher hangs off the last reply a turn actually drew, so a text-less
    // turn of the model cannot swallow it.
    val tail = HashMap<String, String>()
    shown.forEach { message ->
        val turn = turnOf[message.id].orEmpty()
        if (message.role == Roles.ASSISTANT && message.isVisible() && turn.isNotEmpty()) {
            tail[turn] = message.id
        }
    }

    fun branchFor(message: StoredMessage): Branch? {
        val turn = turnOf[message.id].orEmpty()
        if (turn.isEmpty() || tail[turn] != message.id) return null
        val siblings = runs[turn].orEmpty()
        // Re-answering is only offered on the newest question; older ones would have
        // to invalidate everything below them, which branch switching never does.
        val regenerate = turn == lastQuestion && !running
        if (siblings.size < 2 && !regenerate) return null
        return Branch(
            turnId = turn,
            runs = siblings,
            index = siblings.indexOf(chosen[turn]).coerceAtLeast(0),
            canRegenerate = regenerate,
        )
    }

    val rows = mutableListOf<ChatRow>()
    var previous: StoredMessage? = null
    // The bubble that is still collecting, and the run it belongs to. Another round
    // of that same run folds into it rather than starting a second bubble.
    var openIndex = -1
    var openRun: String? = null

    shown.forEach { message ->
        when {
            message.role == Roles.TOOL -> {
                val index = openIndex
                if (index >= 0) {
                    val open = rows[index]
                    // Consecutive calls share one block; a call that arrives after
                    // thinking or prose starts a new one, so the sequence stays honest.
                    val parts = if (open.parts.lastOrNull() is Part.Tools) {
                        open.parts.dropLast(1) +
                            Part.Tools((open.parts.last() as Part.Tools).calls + ToolLine.of(message))
                    } else {
                        open.parts + Part.Tools(listOf(ToolLine.of(message)))
                    }
                    rows[index] = open.copy(parts = parts)
                } else {
                    // A tool result with no bubble above it still gets drawn — and it opens
                    // the bubble, claiming the run that produced it. The round that asked for
                    // the call can carry neither text nor thinking (a pure tool-call round),
                    // which makes it invisible to the assistant branch below, so nothing is
                    // open by the time its result lands. Leaving this card unclaimed is what
                    // split one answer: every following result of the same run started yet
                    // another card, and the round that finally wrote prose could not fold
                    // back in because its run no longer matched an open one.
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = listOf(Part.Tools(listOf(ToolLine.of(message)))),
                    )
                    previous = message
                    openIndex = rows.lastIndex
                    openRun = message.runId.orEmpty()
                }
            }

            message.role == Roles.ASSISTANT && message.isVisible() -> {
                val run = message.runId.orEmpty()
                // Thinking first, then the words: that is the order they arrived in, and
                // a round that only thought contributes only a thinking part.
                val arrived = buildList {
                    message.reasoning?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        add(Part.Thinking(it, message.reasoningTokens, message.elapsedMs, run))
                    }
                    if (message.text.isNotBlank()) add(Part.Text(message.text))
                }
                val index = openIndex
                // A round folds into the bubble already open when its run is that row's run. The
                // comparison used to demand a *non-empty* run as well, and that is the one thing it
                // must not do: `Transcript.BASE_RUN` is the empty string — the run id every message
                // written before branching shares — so two rounds of one legacy answer could never
                // fold, and they drew as two cards, the first of them a process header with no prose
                // under it. That is the whole of this rule; a first version of the fix also let a
                // "tool card with no assistant round yet" be claimed by any round, and the test for
                // it proved the branch unreachable: `groupRows` is fed `Transcript.active(...)`,
                // which keeps one run per turn, so every assistant and tool message of a turn
                // already carries the same run id and the plain comparison folds them all.
                if (index >= 0 && run == openRun) {
                    val open = rows[index]
                    rows[index] = open.copy(
                        message = message,
                        parts = open.parts + arrived,
                        rounds = open.rounds + message,
                        // The switcher only hangs off the reply's last round, which
                        // is this one.
                        branch = branchFor(message) ?: open.branch,
                    )
                } else {
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = arrived,
                        branch = branchFor(message),
                    )
                    openIndex = rows.lastIndex
                    openRun = run
                }
                previous = message
            }

            // A text-less assistant turn: nothing to draw, and its tools fall through
            // to the bubble that is already open.
            message.role == Roles.ASSISTANT -> Unit

            // 插话: drawn where it was typed, inside the turn it interrupted, and the
            // bubble stays open so the model's next round appends after it.
            message.role == Roles.USER && message.steer -> {
                val index = openIndex
                if (index >= 0) {
                    val open = rows[index]
                    rows[index] = open.copy(parts = open.parts + Part.Steer(message))
                } else {
                    // Typed before the model had written anything back yet.
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = listOf(Part.Steer(message)),
                    )
                }
                previous = message
            }

            message.isVisible() -> {
                rows += ChatRow(
                    message = message,
                    showTimeLabel = needsTimeLabel(previous, message),
                )
                previous = message
                // 失败回执自己成一行、不接管 open 气泡：它后面不该再往里折工具行或正文
                // （run 已经结束）。普通可见消息维持原行为（关掉 open 气泡）。
                if (message.role != Roles.SYSTEM) {
                    openIndex = -1
                    openRun = null
                }
            }

            else -> Unit
        }
    }
    return rows
}

/**
 * How many turns of a conversation are drawn when it is opened, and how many each 「加载更早」
 * adds. Twelve is two of the scrubber's own six-row windows: enough that a chat opens on real
 * scrollback rather than a screenful, small enough that the grouping, the checkpoint map and the
 * turn landmarks are priced on a tail instead of on a transcript that can run to thousands of
 * messages — and the strip can be unlocked a whole window further back before the reader meets
 * the drawn edge and the button takes over.
 */
internal const val PAGE_TURNS = 12

/**
 * The part of a conversation the transcript draws.
 *
 * [from] is the message it starts at, [drawn] of the conversation's [turns] are in it, and
 * [earlier] is what that leaves above it — which is also the number the oldest drawn turn really
 * is, since a turn is what the reader counts in. A page is always a suffix, the newest turns:
 * that is where a conversation is read from and where a streaming answer lives.
 */
internal data class TurnPage(val from: Int, val turns: Int, val drawn: Int) {
    /** Turns above the drawn part. */
    val earlier: Int get() = turns - drawn
    val hasEarlier: Boolean get() = earlier > 0
}

/**
 * Which page of [messages] a request for [want] turns draws. The cut is made on a turn boundary
 * and never inside one: a run's rounds belong to the card the grouping builds for them, and half
 * of one drawn alone would be a card of tools whose question is not on screen. [want] below a
 * single turn is read as one: a page with nothing in it is not a page.
 */
internal fun turnPage(messages: List<StoredMessage>, want: Int): TurnPage {
    val starts = messages.indices.filter {
        messages[it].role == Roles.USER && !messages[it].steer
    }
    if (starts.isEmpty()) return TurnPage(from = 0, turns = 0, drawn = 0)
    val drawn = want.coerceIn(1, starts.size)
    val first = starts.size - drawn
    // A conversation that opens with something other than a question keeps it, as long as its
    // first turn is drawn; from the second turn on, the page starts at the question itself.
    return TurnPage(
        from = if (first == 0) 0 else starts[first],
        turns = starts.size,
        drawn = drawn,
    )
}

@Composable
private fun MessageRow(
    row: ChatRow,
    assistantName: String,
    assistantFace: String = "",
    /** The persona's uploaded picture, if it has one; see [AssistantAvatar]. */
    assistantAvatarPath: String = "",
    showReasoning: Boolean,
    expandTools: Boolean,
    /** 外观 → 历史思考块默认展开; see [ReasoningBlock.expandReasoning]. */
    expandReasoning: Boolean = true,
    reasoningMaxHeight: Dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    /** What this row's whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
    /** 「提供方 / 模型」 of the round this row shows; blank when it cannot be resolved. */
    engineLabel: String = "",
    /** The screen's table of open thinking blocks; see [ReasoningExpansion]. */
    reasoningExpansion: ReasoningExpansion,
    onBranch: (String, String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: (StoredMessage) -> Unit,
) {
    when (row.message.role) {
        Roles.USER -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            UserBubble(message = row.message, onEdit = onEdit)
            MessageActions(message = row.message, onEdit = onEdit)
        }

        Roles.ASSISTANT -> AssistantRow(
            // The setting hides thinking entirely; everything else keeps its place in
            // the sequence either way.
            parts = if (showReasoning) row.parts else row.parts.filterNot { it is Part.Thinking },
            streaming = false,
            name = assistantName,
            face = assistantFace,
            avatarPath = assistantAvatarPath,
            branch = row.branch,
            usage = row.usage,
            contextWindow = contextWindow,
            runElapsedMs = runElapsedMs,
            engineLabel = engineLabel,
            message = row.message,
            expandTools = expandTools,
            expandReasoning = expandReasoning,
            reasoningMaxHeight = reasoningMaxHeight,
            followReasoning = followReasoning,
            bubbleMaxWidth = bubbleMaxWidth,
            reasoningExpansion = reasoningExpansion,
            onBranch = onBranch,
            onRegenerate = onRegenerate,
            onEdit = onEdit,
        )

        // 失败回执：run 最终失败时 VM 落盘的一条 system 消息。它不是模型的回答，所以不带头像、
        // 不带操作行 —— 一条全宽的红色小卡，像气泡一样留在会话里，重开还在。
        Roles.SYSTEM -> FailureReceiptRow(text = row.message.text)

        // A tool result with no bubble above it still renders as one, card only. It is
        // `row.parts` rather than the row's own message: once this card is the run's open
        // bubble, the calls that followed it — and a 插话 typed while they ran — live in the
        // parts, and rebuilding from the message alone would draw only the first of them.
        else -> AssistantRow(
            parts = row.parts,
            streaming = false,
            face = assistantFace,
            avatarPath = assistantAvatarPath,
            bubbleMaxWidth = bubbleMaxWidth,
            reasoningExpansion = reasoningExpansion,
        )
    }
}

/**
 * The user's own line, right-aligned.
 *
 * [onEdit] is still what 修改 goes through, but it is reached from the actions row under the
 * bubble — never from a long press: that gesture belongs to text selection, which is what a
 * user reaches for when they want to copy what they wrote.
 */
@Composable
private fun UserBubble(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(max = 300.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // 插话 was typed mid-answer, not as a new question; the badge is how the
            // transcript stays honest about that after the run is over.
            if (message.steer) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "插话",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
            }
            message.imageBase64?.let { ImageBlock(it) }
            if (message.text.isNotBlank()) {
                // The user's own words are plain text, never parsed as Markdown — someone typing
                // "1. do this" means the list item, not a heading. The style is still the
                // assistant body's, so the two bubbles agree on size and leading.
                // Selectable: long-press is how a phone copies text, and the bubble used to
                // spend that gesture on the edit dialog instead.
                SelectionContainer {
                    Text(message.text, style = markdownBody())
                }
            }
            if (message.edited) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** The small 复制 / 编辑 row under a bubble. */
@Composable
private fun MessageActions(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.padding(top = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TinyAction(ImageVector.vectorResource(R.drawable.ic_copy), "复制") {
            clipboard.setText(AnnotatedString(message.text))
        }
        TinyAction(ImageVector.vectorResource(R.drawable.ic_square_pen), "修改") { onEdit(message) }
    }
}

@Composable
private fun TinyAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The reply's own numbers, in the card's bottom row: two buttons, one for the clock and one
 * for the cost.
 *
 * They are two questions — 「它花了多久」 and 「它用了多少」 — so they are two controls rather
 * than one card with everything in it; each opens its own detail. The numbers come off the
 * stored message, so a reopened conversation shows the same figures it showed when the answer
 * arrived, and a field the endpoint never reported is left out rather than filled with a zero.
 *
 * Both cards open upward: the row sits at the bottom of an answer, and a card opening downward
 * would cover the composer.
 */
@Composable
private fun TurnMetrics(
    /** What the whole run took, not one round's call. */
    elapsedMs: Long,
    /** How long the endpoint took to say anything at all; 0 when it never streamed. */
    firstTokenMs: Long,
    promptTokens: Int,
    completionTokens: Int,
    cachedTokens: Int,
    /** 「提供方 / 模型」 that produced this round; blank leaves the line out. */
    engineLabel: String = "",
) {
    val total = promptTokens + completionTokens
    if (elapsedMs <= 0 && total <= 0) return
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<MetricCard?>(null) }
    // What the card last showed: the popup stays composed through its exit animation, and an
    // empty card sliding away is worse than the one the user just closed.
    var shown by remember { mutableStateOf(MetricCard.TIME) }
    // `open` read once, not asserted: the check and the use are two reads of a state that a
    // recomposition can move between, and `!!` on the second is the shape that turns that into a
    // crash. Same fix as the one in Common.kt.
    open?.let { shown = it }
    val elapsed = remember(elapsedMs) {
        String.format(Locale.getDefault(), "%.1f秒", elapsedMs / 1000.0)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (elapsedMs > 0) {
                MetricButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_clock),
                    text = elapsed,
                    label = "用时详情",
                    onClick = { open = MetricCard.TIME },
                )
            }
            if (total > 0) {
                MetricButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_database),
                    text = "用量 ${shortTokens(total)} tok",
                    label = "用量详情",
                    onClick = { open = MetricCard.USAGE },
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart)) {
            // Focus-less, like the composer's own cards and for the same reason (see [MenuCard]'s
            // `focusable`): a focusable popup window takes the keyboard away from the field, and a
            // reader looking up what a turn cost has not left the conversation. This card sits in
            // the transcript, so it was the one place still doing it — the composer's two cards
            // were already focus-less. A focus-less window can see neither a tap outside itself
            // nor BACK, so both are handled here: the scrim is a window of its own and is composed
            // before the card, because windows stack in composition order.
            if (open != null) {
                Popup(properties = PopupProperties(focusable = false)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { open = null },
                    )
                }
                BackHandler { open = null }
            }
            MenuCard(
                visible = open != null,
                onDismiss = { open = null },
                alignment = Alignment.BottomStart,
                offset = with(LocalDensity.current) { IntOffset(0, -8.dp.roundToPx()) },
                // The card's own Column already swallows the taps that land on it (see MenuCard).
                focusable = false,
            ) {
                // Intrinsic width: every row is as wide as the widest one, which is what puts the
                // numbers on one column while the popup still hugs its content. The cap is what
                // keeps a long 「提供方 / 模型」 line from pushing the card past the window.
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(
                            min = 208.dp,
                            max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp),
                        ),
                ) {
                when (shown) {
                    MetricCard.TIME -> {
                        if (elapsedMs > 0) RingDetail("总用时", elapsed)
                        if (firstTokenMs > 0) {
                            RingDetail(
                                "首 token 平均（TTFT）",
                                remember(firstTokenMs) {
                                    String.format(
                                        Locale.getDefault(),
                                        "%.1f秒",
                                        firstTokenMs / 1000.0,
                                    )
                                },
                            )
                        }
                        if (completionTokens > 0 && elapsedMs > 0) {
                            RingDetail(
                                "输出速度（TPS）",
                                remember(completionTokens, elapsedMs) {
                                    String.format(
                                        Locale.getDefault(),
                                        "%.1f tok/s",
                                        completionTokens * 1000.0 / elapsedMs,
                                    )
                                },
                            )
                        }
                    }

                    MetricCard.USAGE -> {
                        // Which endpoint answered, and which of its models: the two things every
                        // figure below is read against, and the only line here that is not a
                        // number. Left out when the id matches no endpoint on this device.
                        if (engineLabel.isNotBlank()) RingDetail("提供方 / 模型", engineLabel)
                        if (total > 0) RingDetail("本轮用量", "${shortTokens(total)} tok")
                        if (promptTokens > 0) {
                            RingDetail("输入", "${shortTokens(promptTokens)} tok")
                            RingDetail("缓存读取", "${shortTokens(cachedTokens)} tok")
                            RingDetail(
                                "未缓存输入",
                                "${shortTokens((promptTokens - cachedTokens).coerceAtLeast(0))} tok",
                            )
                            if (cachedTokens > 0) {
                                RingDetail(
                                    "缓存命中",
                                    remember(promptTokens, cachedTokens) {
                                        String.format(
                                            Locale.getDefault(),
                                            "%.2f%%",
                                            cachedTokens * 100.0 / promptTokens,
                                        )
                                    },
                                )
                            }
                        }
                        if (completionTokens > 0) {
                            RingDetail("输出", "${shortTokens(completionTokens)} tok")
                        }
                    }
                }
                }
            }
        }
    }
}

/** Which of the two detail cards [TurnMetrics] is showing. */
private enum class MetricCard { TIME, USAGE }

/** One icon + value pair of [TurnMetrics], tappable: it opens that number's detail card. */
@Composable
private fun MetricButton(icon: ImageVector, text: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 15dp, the same as the copy and edit icons beside it: at 14dp these two read as a
            // size smaller than the controls they sit next to.
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun AssistantRow(
    /** The reply's pieces in arrival order: thinking, tools, prose, thinking again… */
    parts: List<Part>,
    streaming: Boolean,
    /** The assistant's name, for the card's own header line. */
    name: String = "随行",
    /** The persona's emoji face; blank falls back to the brand feather. */
    face: String = "",
    /** The picture the user uploaded for this persona; blank falls back to [face]. */
    avatarPath: String = "",
    /** The tool this reply is waiting on right now, so its card can say so live. */
    runningTool: String? = null,
    branch: Branch? = null,
    usage: Usage? = null,
    /** The engine's live speed, present only while this bubble is the one streaming. */
    liveStats: StreamStats? = null,
    /** The model's window, for the context readout; 0 means it was never set. */
    contextWindow: Int = 0,
    /** What the whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
    /** 「提供方 / 模型」 for the usage card; blank leaves that line out. */
    engineLabel: String = "",
    message: StoredMessage? = null,
    expandTools: Boolean = false,
    /** 外观 → 历史思考块默认展开; see [ReasoningBlock.expandReasoning]. */
    expandReasoning: Boolean = true,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 气泡宽度: the card's cap. [Dp.Infinity] leaves it at the row's own width. */
    bubbleMaxWidth: Dp = Dp.Infinity,
    /** The screen's table of open thinking blocks; see [ReasoningExpansion]. */
    reasoningExpansion: ReasoningExpansion,
    onBranch: (String, String) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
    onEdit: (StoredMessage) -> Unit = {},
) {
    Surface(
        // 外观 → 气泡宽度 first, then fillMaxWidth: the cap has to be the outer
        // constraint, or the inner fill would win and the card could never narrow.
        modifier = Modifier
            .widthIn(max = bubbleMaxWidth)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // A hairline instead of a shadow: elevation 0 keeps a long transcript flat, and
        // the outline is what separates a near-white card from a white canvas in light
        // mode, where the two surfaces are 8% apart.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)) {
            // The avatar sits in the card's header rather than beside it: the reply is one
            // object, and an avatar floating outside the card made it two.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One composable for all three faces — the uploaded picture, the persona's
                // emoji, the brand feather — so this card, the assistant sheet and the editor
                // cannot disagree about what a persona looks like.
                AssistantAvatar(
                    face = face,
                    avatarPath = avatarPath,
                    size = 24.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = name,
                    style = metaStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The summary opens the bubble and waits for the run to settle: while it
            // streams the numbers are still moving, and a card that changes shape under the
            // reader's eyes is worse than no card at all.
            // 头像行→10→首块（无汇总卡的流式态也走这一条）。
            Spacer(Modifier.height(10.dp))
            val settled = usage?.takeIf { it.known }
            if (!streaming && (settled != null || parts.isNotEmpty())) {
                TurnSummary(
                    parts = parts,
                    usage = usage,
                    live = null,
                    contextWindow = contextWindow,
                    // The run's note, when there was one: it lives on the stats for as long as
                    // the run does, which is exactly the window in which this card is drawn
                    // for a live turn. A settled turn from an earlier launch has no note to
                    // show — the compression record is the separator card's job.
                    note = liveStats?.note.orEmpty(),
                    reasoningMaxHeight = reasoningMaxHeight,
                    followReasoning = followReasoning,
                    // The card's thinks are settled ones: this is what decides whether they open.
                    expandReasoning = expandReasoning,
                    // The stored blocks answer to their message, so the table keeps their
                    // state apart from the live run's and from every other message's.
                    messageId = message?.id.orEmpty(),
                    reasoningExpansion = reasoningExpansion,
                    onEdit = onEdit,
                )
                // 头像行→10→首块、末块→10→正文/气泡底: the card's own air below is
                // this spacer, so a bubble without a card does not stack two of them.
                Spacer(Modifier.height(10.dp))
            }
            val lastText = parts.indexOfLast { it is Part.Text }
            // key(index): without it every part's remembered state was allocated by
            // position, so a new round appearing at the front moved one block's switch onto
            // its neighbour.
            parts.forEachIndexed { index, part ->
                // Guarded before key(index), not inside it: a non-local return out of the
                // key group leaves that group unclosed, and Compose aborts the composition
                // with "Start/end imbalance". Same conditions, evaluated outside the group.
                if (part is Part.Thinking && !streaming) return@forEachIndexed
                if (part is Part.Tools && !streaming) return@forEachIndexed
                if (part is Part.Text && !streaming && index != lastText) return@forEachIndexed
                key(index) {
                    val last = index == parts.lastIndex
                    when (part) {
                        is Part.Thinking -> {
                            // A settled reply keeps its thinking inside the summary card
                            // alone: a second copy beside the answer is what made one
                            // answer read as a stack of blocks. Live it still streams here.
                            ReasoningBlock(
                                text = part.text,
                                // A live block is the one still growing; a settled round's
                                // thinking is complete even while the run continues.
                                streaming = streaming && last,
                                // A live block keeps its slot for the whole run, and the
                                // stored copy of the same run answers to the same key.
                                blockId = thinkSlot(parts, index, "live"),
                                expansion = reasoningExpansion,
                                reasoningTokens = part.tokens,
                                elapsedMs = part.elapsedMs,
                                // Whether a block nobody has touched is open is the appearance
                                // setting's business, and the block owns that rule: live ones are
                                // always open, settled ones follow 历史思考块默认展开. A tap on
                                // the header is the only thing that closes one, and the expansion
                                // table remembers it across the hand-over to the stored message.
                                expandReasoning = expandReasoning,
                                maxHeight = reasoningMaxHeight,
                                followReasoning = followReasoning,
                            )
                            if (!last) Spacer(Modifier.height(10.dp))
                        }

                        is Part.Tools -> {
                            // Same rule as thinking: settled tool rows live in the summary
                            // card's expanded list, not flat beside the answer.
                            // Consecutive tool rounds merge into one block: one card per
                            // run of calls, not a card inside a card per round.
                            val run = mutableListOf(part.calls)
                            var cursor = index + 1
                            while (cursor < parts.size && parts[cursor] is Part.Tools) {
                                run.add((parts[cursor] as Part.Tools).calls)
                                cursor++
                            }
                            ToolBundle(
                                tools = run.flatten(),
                                // Only the newest block can be the one waiting on a tool.
                                runningTool = runningTool.takeIf { cursor > parts.lastIndex },
                                startExpanded = expandTools,
                            )
                            if (!last) Spacer(Modifier.height(10.dp))
                        }

                        is Part.Text -> {
                            // Only the last stretch of prose is drawn once the run is over:
                            // the model's "let me check" before a tool is not part of its
                            // answer. While it streams every stretch shows, because that is
                            // what it is doing right now.
                            // Selectable once it has settled, and only then: text that grows a
                            // line at a time is not selectable in practice, and wrapping a
                            // container that re-measures its content on every frame is what
                            // made the live bubble flash the previous layout.
                            // Selectable from the frame AFTER the bubble lands, never in the
                            // frame it lands in. The hand-over from the live row to the
                            // stored one builds this subtree from scratch, and wrapping the
                            // whole answer in a SelectionContainer in that same frame makes
                            // it re-measure text that was laid out a frame ago without one —
                            // the frame that then still paints the old layout is the ghost
                            // at the end of every stream. One frame later the same wrap costs
                            // its measure and nothing is drawn stale.
                            var selectable by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                selectable = true
                            }
                            if (streaming || !selectable) {
                                MarkdownText(text = part.text)
                            } else {
                                SelectionContainer { MarkdownText(text = part.text) }
                            }
                            if (streaming && last) {
                                Spacer(Modifier.height(4.dp))
                                BlinkingCursor()
                            }
                            if (!last) Spacer(Modifier.height(10.dp))
                        }

                        is Part.Steer -> {
                            // Right-aligned, like any user line, but inside the reply: the
                            // indentation is what says "I said this while it was working".
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                UserBubble(message = part.message, onEdit = onEdit)
                            }
                            if (!last) Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
            if (parts.isEmpty() && streaming) TypingDots()
            if (message != null && !streaming) {
                // The last block carries no trailing spacer of its own, so the air
                // above this row is its own 10dp.
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionBar(
                        branch = branch,
                        onBranch = onBranch,
                        onRegenerate = onRegenerate,
                    )
                    Spacer(Modifier.weight(1f))
                    // The round's clock and cost, right-aligned with the card's own copy and
                    // edit controls: they are all "about this answer", and the row reads as one
                    // group when they share the right edge.
                    TurnMetrics(
                        // The run's own clock when we have it: one round's call is not what
                        // the status line counted while the answer was arriving.
                        elapsedMs = if (runElapsedMs > 0) runElapsedMs else message.elapsedMs,
                        firstTokenMs = message.firstTokenMs,
                        promptTokens = message.promptTokens,
                        completionTokens = message.completionTokens,
                        cachedTokens = message.cachedTokens,
                        engineLabel = engineLabel,
                    )
                    MessageActions(message = message, onEdit = onEdit)
                }
            }
        }
    }
}

/**
 * The row under a reply that says "this answer is one of several". Arrows only
 * appear once a second answer exists; re-answering is offered on the newest
 * question alone, because replacing an older answer would orphan everything the
 * conversation built on top of it.
 */
@Composable
private fun ActionBar(
    branch: Branch?,
    onBranch: (String, String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (branch != null && branch.runs.size > 1) {
            val atStart = branch.index <= 0
            val atEnd = branch.index >= branch.runs.lastIndex
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_chevron_left),
                label = "上一个回答",
                enabled = !atStart,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index - 1]) },
            )
            Text(
                text = "${branch.index + 1}/${branch.runs.size}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                label = "下一个回答",
                enabled = !atEnd,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index + 1]) },
            )
        }
        if (branch?.canRegenerate == true) {
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_rotate_cw),
                label = "重新回答",
                enabled = true,
                onClick = onRegenerate,
            )
        }
    }
}

@Composable
private fun BranchArrow(
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
private val PROCESS_TITLE_MAX = 160.dp

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
private fun Modifier.blockPress(shape: Shape, onClick: () -> Unit): Modifier {
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
 * The chrome every folded process block shares: thinking, a tool call and the turn's own
 * summary all draw through this, so a reply that thinks, calls, thinks again reads as one
 * repeating shape instead of three unrelated widgets stacked in a column.
 *
 * The header *is* the summary line — there is no second row above it — which is what
 * keeps a turn that ran ten tools the height of one row.
 */
@Composable
private fun ProcessCard(
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // From the theme, not a 16dp literal: SettingsScreen's AdvancedSection documents itself
        // as "same chrome as the process cards in the chat screen", and 16 written twice stops
        // being one shape the moment 外观 → 圆角 moves off the standard tier.
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Exactly 36dp folded: the fourth time the user has nailed this
                    // card's height down, so it is a fixed height, not a minimum —
                    // nothing in the row wraps (every text is maxLines 1).
                    .height(36.dp)
                    // 整块按下反馈（见 blockPress）：这一行是整条卡片的表头，宽度铺满、还是展开
                    // 后整块内容的入口，涟漪在这里画出来就是个横跨半个屏幕的圆。
                    .blockPress(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (expanded) 0.dp else 16.dp,
                            bottomEnd = if (expanded) 0.dp else 16.dp,
                        ),
                        onClick = onToggle,
                    )
                    .padding(horizontal = 12.dp),
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
            if (expanded) {
                // Header and content are one card; the hairline is their only seam.
                HorizontalDivider(color = scheme.outlineVariant)
                // The grid the expanded rows share: 12dp of side air, 8dp above and
                // below, and every row inside lines its own column up to this edge.
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
private fun metaStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(
    fontSize = MaterialTheme.typography.labelMedium.fontSize * (12f / 13f),
)

/** The trailing status of a [ProcessCard]: a short phrase, never a sentence. */
@Composable
private fun ProcessStatus(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
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
private fun ProcessSpinner(tone: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(13.dp),
        strokeWidth = 2.dp,
        color = tone,
    )
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
private class ReasoningExpansion(
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
private fun thinkSlot(parts: List<Part>, index: Int, fallback: String): String {
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
private fun ReasoningBlock(
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
            val inner = rememberLazyListState()
            // Whether this chain is chasing its own tail, and whether the hand has taken it
            // over. The same pair, and the same rules, as the transcript's own follow — see
            // the screen's `following`/`programmatic` above.
            var follow by remember(blockId) { mutableStateOf(true) }
            var autoScrolled by remember(blockId) { mutableStateOf(false) }
            var lastAutoAt by remember(blockId) { mutableLongStateOf(0L) }
            LaunchedEffect(inner) {
                snapshotFlow { inner.isScrollInProgress to inner.canScrollBackward }
                    .collect { (scrolling, canScrollBack) ->
                        if (scrolling && !autoScrolled && canScrollBack &&
                            SystemClock.uptimeMillis() - lastAutoAt > 120
                        ) {
                            // The hand wins the moment it lifts the chain off its tail; the
                            // next frame of the stream must not yank it back down.
                            follow = false
                        } else if (!scrolling && inner.atVeryEnd()) {
                            // Released at the bottom again: the chain may chase its tail once
                            // more. A chain that never left its end keeps following anyway.
                            //
                            // 与正文同一条规则：判「到底」只能用 [atVeryEnd]，**不要再回到容差**
                            // （原来的 `atEnd(slack = 8)`）。思维链是 px 级的滚动，滚几个像素就落在
                            // 8px 容差里 ⇒ 手指一松就重新追尾，和用户报的「往上滑一点点，又追底」
                            // 是同一个形态；这个内层列表有自己的一套 follow/autoScrolled，所以它
                            // 不会因为正文修好了而跟着好。
                            follow = true
                        }
                        autoScrolled = false
                    }
            }
            // Every growth of the chain is a trigger — that is what "跟随最新" is for.
            LaunchedEffect(lines.size, expanded, follow, followReasoning) {
                if (!expanded || !streaming || !followReasoning || !follow) return@LaunchedEffect
                if (inner.isScrollInProgress && !autoScrolled) return@LaunchedEffect
                if (lines.isEmpty()) return@LaunchedEffect
                autoScrolled = true
                lastAutoAt = SystemClock.uptimeMillis()
                // Instant, not animated. The chain grows every frame or two while the think
                // streams, and an [animateScrollToItem] that is still in flight when the next
                // line lands is simply dropped — the view froze mid-chain, which is exactly
                // what the user saw. A jump they cannot watch beats a follow that never arrives.
                inner.scrollToItem(lines.lastIndex)
            }
            LazyColumn(
                state = inner,
                modifier = Modifier
                    .fillMaxWidth()
                    // Configurable in 外观: a chain of thought is context, and the
                    // answer has to stay on screen while it is being read.
                    .heightIn(max = maxHeight),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines.size) { index ->
                    val line = lines[index]
                    if (line.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                    } else {
                        MarkdownText(
                            text = line,
                            // 13sp with roomier leading than bodySmall's: a long chain of
                            // thought is read, not skimmed.
                            style = thinkingStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index == lines.lastIndex && streaming) BlinkingCursor()
                }
            }
        }
    }
    val toggle = {
        // Written to the table, not to a local: the choice has to survive the hand-over
        // from the live block to the stored one.
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
            Spacer(Modifier.width(8.dp))
            ProcessSpinner(MaterialTheme.colorScheme.primary)
        }
    }
    if (nested) {
        // A row of the summary card's grid: no surface of its own, its icon slot on
        // the same left edge as the tool rows, its chain indented to the name column.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            // The tap target is the header row alone, clipped to a rounded rectangle before the
            // ripple is attached. On the Column it used to cover the whole block — chain
            // included — so the ripple spilled over the text and a tap anywhere in the chain
            // folded it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 折叠块统一（与 ProcessCard 表头、ToolRow 同一套）：固定 36dp 行高 + 12dp
                    // 水平内边距 + labelMedium 标题。折叠态每行都是 maxLines=1，固定高度不会裁字。
                    .height(36.dp)
                    .padding(horizontal = 12.dp)
                    // 整块按下反馈（见 blockPress）：这一行铺满整条卡片的宽度，涟漪在这么宽的行上
                    // 是一大片弧形，不是「按下这一行」。
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
                    // Takes what four characters need and hands the rest to the preview: the
                    // half-row share this used to hold was the band of nothing after it.
                    // [status] is called after the gap below, since this row draws its own
                    // header instead of going through [ProcessCard].
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
            if (expanded) {
                Column(Modifier.padding(start = 44.dp, top = 4.dp)) { chain() }
            }
        }
    } else {
        ProcessCard(
            icon = ImageVector.vectorResource(R.drawable.ic_brain),
            title = "思考过程",
            expanded = expanded,
            onToggle = toggle,
            preview = firstLine.takeIf { it.isNotEmpty() },
            previewTail = if (streaming && firstLine.isNotEmpty()) {
                { BlinkingCursor() }
            } else {
                null
            },
            trailing = status,
        ) {
            chain()
        }
    }
}

/** 13sp / 20sp leading, scaled off bodySmall so 字号 still applies to both. */
@Composable
private fun thinkingStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return body.copy(lineHeight = body.fontSize * (20f / 13f))
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
private fun ToolBundle(
    tools: List<ToolLine>,
    runningTool: String? = null,
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    var openTool by remember { mutableStateOf<String?>(null) }
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
            // Same family as the thinking block: bounded at 320dp with its own lazy list,
            // so a run of twenty calls does not stretch the bubble past the screen.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tools.size) { index ->
                    val tool = tools[index]
                    // Stored calls key on their message id; a live one has no id yet.
                    val key = tool.message?.id ?: "live-$index"
                    ToolRow(
                        line = tool,
                        expanded = openTool == key,
                        onToggle = { openTool = if (openTool == key) null else key },
                    )
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
private fun ToolDetail(line: ToolLine) {
    val scheme = MaterialTheme.colorScheme
    val args = line.args.takeIf { it.isNotBlank() && it != "{}" }
    if (args != null) {
        Text(
            text = args,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val output = line.message?.toolOutput?.takeIf { it.isNotBlank() }
    if (output != null) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
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
            Box(
                Modifier
                    .weight(1f)
                    // Bounded before it scrolls. A scrollable measured with the infinite
                    // height a lazy list hands its items throws, and that is exactly what
                    // made tapping a tool row crash the app.
                    .heightIn(max = 132.dp),
            ) {
                val scroll = rememberScrollState()
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    // No maxLines here: the box above is the bound, and the whole output
                    // has to stay reachable by scrolling inside it.
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                )
            }
            val clip = LocalClipboardManager.current
            IconButton(
                onClick = { clip.setText(AnnotatedString(output)) },
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
    line.message?.imageBase64?.let { ImageBlock(it) }
}

@Composable
private fun ToolRow(line: ToolLine, expanded: Boolean, onToggle: () -> Unit) {
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
            .padding(horizontal = 12.dp),
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
        if (expanded) {
            // The detail lines up under the name column, not under the icon slot.
            Column(Modifier.padding(start = 44.dp, top = 6.dp)) {
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
private fun TurnSummary(
    parts: List<Part>,
    usage: Usage?,
    live: StreamStats?,
    contextWindow: Int,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 历史思考块默认展开; passed on to each think in this card. */
    expandReasoning: Boolean = true,
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
    val thoughts = parts.filterIsInstance<Part.Thinking>()
    val calls = parts.filterIsInstance<Part.Tools>().flatMap { it.calls }
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
            add("出 ${live.tokensOut}")
            if (live.tokPerSec > 0) add(String.format(Locale.US, "%.1f tok/s", live.tokPerSec))
            if (live.elapsedMs > 0) add(formatDuration(live.elapsedMs))
        } else if (settled != null) {
            if (settled.prompt > 0) add("入 ${settled.prompt}")
            if (settled.completion > 0) add("出 ${settled.completion}")
            if (settled.perSecond > 0) {
                add(String.format(Locale.US, "%.1f tok/s", settled.perSecond))
            }
            if (settled.elapsedMs > 0) add(formatDuration(settled.elapsedMs))
        }
    }
    // The numbers ride inside the title rather than in their own slot, so they shorten
    // with it and the chip after them is never the thing that gets squeezed.
    val header = buildList {
        add(if (headline.isEmpty()) "本轮用量" else "本轮：${headline.joinToString(" · ")}")
        addAll(tail)
        // After the numbers, before nothing else: the compression line is a fact about this
        // turn, so it rides in the same title and folds away with the card.
        if (note.isNotBlank()) add(note)
    }.joinToString(" · ")
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            nested = true,
                            maxHeight = reasoningMaxHeight,
                            followReasoning = followReasoning,
                        )

                        is Part.Tools -> {
                            var openTool by remember { mutableStateOf<String?>(null) }
                            part.calls.forEachIndexed { index, call ->
                                // Name + status on the row, arguments and output behind its
                                // own tap — the same two-level row the live bundle draws.
                                val callKey = call.message?.id ?: "summary-$index"
                                ToolRow(
                                    line = call,
                                    expanded = openTool == callKey,
                                    onToggle = {
                                        openTool = if (openTool == callKey) null else callKey
                                    },
                                )
                            }
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
                    }
                }
            }
            val detail = buildList {
                if (settled != null) {
                    if (settled.prompt > 0) add("入 ${settled.prompt}")
                    if (settled.completion > 0) add("出 ${settled.completion}")
                    if (settled.reasoning > 0) add("思考 ${settled.reasoning}")
                    if (settled.cached > 0) add("缓存 ${settled.cached}")
                } else if (live != null) {
                    add("出 ${live.tokensOut}")
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
private fun ContextChip(tokens: Int, window: Int, live: StreamStats? = null) {
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
private fun TurnScrubber(
    /** The conversation whose window this is: opening another one resets it. */
    conversationId: String,
    turns: Int,
    current: Int,
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
    val window = remember(turns, current, unlocked) { scrubberWindow(turns, current, unlocked) }
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
                val read = turn < current - 1
                val active = turn == current - 1
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

/**
 * Which line a finger at [y] is on. Rows are found by their centre rather than by their box,
 * so the air between two lines belongs to whichever is nearer — that is what allows the strip
 * to be packed tighter than a fingertip is wide.
 */
private fun scrubberRowFor(y: Float, rowPx: Float, pitchPx: Float, rows: Int): Int {
    if (rows <= 1) return 0
    return ((y - rowPx / 2f) / pitchPx).roundToInt().coerceIn(0, rows - 1)
}

/**
 * The turns the strip draws: [SCRUBBER_VISIBLE] lines ending at the newest turn, slid up so the
 * turn being read is on them, and — while a finger has pulled past the top line — slid up
 * [unlocked] turns further, so the turns above the window can be reached.
 *
 * The window is a fixed number of lines that moves rather than one that grows. Growing it would
 * change the rail's height mid-drag, and the rail is centred: every line would shift down by
 * half of what was added, under a finger that is holding one of them. Sliding keeps every line
 * where it is and changes only which turn each one names, so the line under the finger is always
 * the line the label is on.
 */
internal fun scrubberWindow(turns: Int, current: Int, unlocked: Int): IntRange {
    if (turns <= 0) return IntRange.EMPTY
    val last = turns - 1
    val reading = (last - SCRUBBER_VISIBLE + 1).coerceAtLeast(0)
        .coerceAtMost((current - 1).coerceAtLeast(0))
    val start = (reading - unlocked.coerceAtLeast(0)).coerceAtLeast(0)
    return start until (start + SCRUBBER_VISIBLE).coerceAtMost(turns)
}

/**
 * How far the strip has been unlocked by a finger at [y], given how far it already was: the top
 * line gives ground from the moment the finger is on it — the band the hit test calls the top
 * line, its centre half a row either side — and every further row of upward travel above it
 * unlocks one more turn. Monotonic — coming back down the strip does not re-lock the turns it
 * just reached, or the drag would take them away again the moment it turned around.
 */
internal fun scrubberPull(y: Float, rowPx: Float, pitchPx: Float, pull: Int): Int {
    if (pitchPx <= 0f) return pull
    val topBand = rowPx / 2f + pitchPx / 2f
    val overshoot = topBand - y
    if (overshoot < 0f) return pull
    return maxOf(pull, (overshoot / pitchPx).toInt() + 1)
}

/** How many turns the scrubber draws before the window slides up to the newest ones. */
internal const val SCRUBBER_VISIBLE = 6

/** The rail's width, and so every row's hit width: the longest line the rail draws. */
private val SCRUBBER_RAIL = 24.dp

/**
 * One row's box. Shorter than the old 18dp because the finger is matched to the nearest line
 * instead of having to land inside a box — the rows no longer need to be big enough to hit,
 * only far enough apart to tell apart.
 */
private val SCRUBBER_ROW = 14.dp

/** Air between rows: half the old 6dp, which was most of what made the strip read as sparse. */
private val SCRUBBER_GAP = 3.dp

/** How far the finger travels before a press turns into a drag. Aether's own threshold. */
private val SCRUBBER_DRAG_THRESHOLD = 18.dp

/** Drifting this far left of the rail drops the jump. Aether's own exit tolerance. */
private val SCRUBBER_EXIT_TOLERANCE = 28.dp

/**
 * How long a still finger waits before the label appears. Shorter than the platform's long
 * press because a scrubber has to answer at once, but long enough that a plain tap on a line
 * never flashes a label on its way to jumping.
 */
private const val SCRUBBER_LONG_PRESS_MS = 220L

/** The label's fade in, and its fade out once the finger lifts. Aether's own pair. */
private const val SCRUBBER_LABEL_IN_MS = 150
private const val SCRUBBER_LABEL_OUT_MS = 110

/**
 * How long the label takes to glide to the line the finger has reached. Deliberately the same
 * 110ms as the line's own growth above, so the two land together and the label never outruns
 * the line it names.
 */
private const val SCRUBBER_SELECT_MS = 110

/** Air between the rail and the label. */
private val SCRUBBER_LABEL_GAP = 6.dp

/** The label's longest line, so it never covers the bubble it floats over. */
private val SCRUBBER_LABEL_MAX = 180.dp

/**
 * The label bubble's own cap: [SCRUBBER_LABEL_MAX], or what is left of the window once the rail it
 * sits beside, its gap and the two margins are taken out.
 *
 * The bubble is drawn to the rail's left and the rail hugs the right edge, so a flat 180dp ran off
 * the left of a narrow window — the split-screen case, where the window is not the display.
 */
private fun scrubberLabelMax(windowWidth: Dp): Dp = minOf(
    SCRUBBER_LABEL_MAX,
    windowWidth - POPUP_EDGE * 2 - SCRUBBER_RAIL - SCRUBBER_LABEL_GAP,
)

/** How far a line grows once its label is out, and while it is merely touched. */
private const val SCRUBBER_PRESS_SCALE = 1.4f
private const val SCRUBBER_TOUCH_SCALE = 1.12f

/** Aether's motion curve, borrowed for the row scale and the label's entry and exit. */
private val SCRUBBER_EASING = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

/** How far a queued line is inset from the pill on each side, so it reads as narrower. */
private val STEER_QUEUE_INSET = 12.dp

/**
 * Where the context went.
 *
 * The three shares come from the engine's own estimate for the run in flight; a stored
 * reply has no such numbers, so for it the dialog shows only the header — better a short
 * dialog than three invented rows.
 */
@Composable
private fun ContextBreakdownDialog(
    tokens: Int,
    window: Int,
    live: StreamStats?,
    onDismiss: () -> Unit,
) {
    val percent = if (window > 0) tokens * 100 / window else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
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
private fun ContextBreakdownRow(label: String, value: Int, total: Int, tone: Color) {
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

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        modifier = Modifier.alpha(alpha),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The live run's progress line: the engine's own sentence, plus how long the run has been
 * going. The clock lives in here — one tick a second re-composes this caption and nothing
 * else, and the elapsed time is read off the wall clock on each tick, so a line that is
 * re-created (a switch away and back) still shows the true time rather than restarting.
 */
@Composable
private fun RunStatusLine(
    text: String,
    /** When the run began, or 0 while the effect that records it has yet to run. */
    startedAt: Long,
    /** False for the compression note: that is a fact, not a progress bar. */
    timed: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(startedAt) {
        if (startedAt <= 0) return@LaunchedEffect
        while (true) {
            delay(1000)
            tick++
        }
    }
    val caption = remember(tick, startedAt, text, timed) {
        if (!timed || startedAt <= 0) text
        else "$text，用时 ${formatRunElapsed(System.currentTimeMillis() - startedAt)}"
    }
    Text(
        text = caption,
        style = statusLineStyle(),
        color = color,
        // 不截断（用户：「错误的提示要显示全」）。重试/失败那行是这条状态线写的（VM 的
        // Retrying/Failed 分支），一行省略号会把「服务器限流（429），用…」的后半句吃掉，
        // 而那一半正是用户要读的原因。允许换行；它本来就短，只有失败原因会长。
        modifier = modifier,
    )
}

@Composable
private fun TimeLabel(timestamp: Long) {
    Text(
        text = formatTime(timestamp),
        modifier = Modifier.fillMaxWidth(),
        // The label's distance from its bubble is decided by the caller (3dp inside the
        // item's Column); this only tightens the text's own line box, so the glyphs sit
        // closer to the top of their slot than labelSmall's 16sp box would put them.
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    /**
     * Takes a queued line back out of the queue and into the composer — its text **and its
     * picture**. A callback of its own rather than `onInput(text)`: the attachment lives in the
     * view model's cache, not in this composable, so only the caller can put both halves back.
     */
    onRestoreSteer: (PendingSteer) -> Unit,
    running: Boolean,
    /** The live run's stats; the context ring reads its note. */
    stats: StreamStats?,
    settings: AppSettings,
    /** The persona this conversation runs with, not the app-wide active one. */
    assistantId: String,
    /** The window the context ring divides by; 0 hides the ring (no denominator). */
    contextWindow: Int,
    /** Tokens the newest round filled of that window, and how many of them were cached. */
    contextUsed: Int,
    /** True when [contextUsed] is this app's own estimate rather than the endpoint's count. */
    contextEstimated: Boolean = false,
    /** Everything this conversation has ever spent, for the ring's detail card. */
    sessionTokens: Int,
    /** The text-based floor for the ring's cumulative row, when nothing was ever priced. */
    sessionEstimate: Int = 0,
    /** That spend in the endpoint's own three parts, for the same card. */
    sessionSplit: SessionSplit,
    /** How many questions that tally was built from. */
    sessionTurns: Int,
    /** The session's own output speed, cache hit rate and wall clock, for the ring's card. */
    sessionPerSecond: Double,
    sessionCachePercent: Int,
    sessionElapsedMs: Long,
    /** This conversation's own model pick, or null while it follows persona/defaults. */
    conversationProviderId: String?,
    conversationModel: String?,
    /** This conversation's own thinking level, or null while it follows the defaults. */
    conversationReasoningEffort: String?,
    onPickModel: (ProviderConfig, String) -> Unit,
    /** Asks the endpoint once what context windows its models advertise; see prefetchWindows. */
    onPrefetchWindows: () -> Unit,
    /** Writes a thinking level onto this conversation alone. */
    onChooseReasoning: (String) -> Unit,
    attachment: Attachment?,
    onClearAttachment: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Queues a line for the running model; false means it was dropped. */
    onSteer: (String, String?) -> Boolean,
    /** Asks the live run to fold the conversation at its next turn boundary. */
    onRequestCompress: () -> Boolean,
    /** Lines queued for the run that is going, oldest first. */
    pendingSteers: List<PendingSteer>,
    /**
     * Lines typed in 排队 mode, waiting to be the next turn. Drawn in the same stack as
     * [pendingSteers] and through the same row — same three actions — but the row is told these are
     * next turns, so it says 「下一轮发送」 instead of calling them interruptions.
     */
    pendingTurns: List<PendingTurn>,
    /** 「立即插入」 on a waiting turn: hand it to the run in flight as a steer. */
    onPromoteTurn: (String) -> Unit,
    /** Drops one waiting turn. */
    onRetractTurn: (String) -> Unit,
    /**
     * The agent's own task board, as its last `todo_write` left it. Drawn above the queue, and
     * only while this run is going: the board is the run's plan, not a second transcript.
     */
    todos: List<TodoItem> = emptyList(),
    /** 点「任务 2/5」那一行：完整清单在 ChatScreen 里画（这个 composable 拿不到那个状态）。 */
    onOpenTodos: () -> Unit,
    /** Sends a queued line now, cutting the round in flight. */
    onPromoteSteer: (String) -> Unit,
    /** Drops a queued line before the model reads it; 「修改」 uses it too. */
    onRetractSteer: (String) -> Unit,
    onFocused: () -> Unit,
    onMic: () -> Unit,
    /** 设置里的「语音输入」：关着的时候这一排里连麦克风都不画。 */
    voiceInput: Boolean,
    /** 工具审批 mode in force, and the picker that changes it; see [ApprovalMode]. */
    approvalMode: ApprovalMode,
    onPickApproval: (ApprovalMode) -> Unit,
    /** The empty provider list's way out: straight into the model settings page. */
    onOpenSettings: () -> Unit,
    /** Scrolls the transcript to its newest line; the stack's own corner button. */
    jumpToLatest: () -> Unit,
    /** Shown while the transcript is scrolled away from its end. */
    showJump: Boolean,
    /**
     * The stack's own measured height, so the list can pad by the room it truly takes out of the
     * transcript (the caller adds the strip under it and its own 12dp of air).
     */
    onHeightChanged: (Dp) -> Unit,
    /** Called once the stack has been placed for this frame: the caller's follow pin rides it. */
    onPlaced: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // The window's own width, which is what a popup has to fit inside. `screenWidthDp` is the
    // window's, not the display's, so a card that fits here also fits a split-screen window.
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { windowWidth.toPx() }
    val popupEdgePx = with(density) { POPUP_EDGE.toPx() }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // The approval mode's own card, anchored to its button like the other two.
    var approvalOpen by remember { mutableStateOf(false) }
    var approvalAnchorLeft by remember { mutableStateOf(0f) }
    var approvalWidth by remember { mutableStateOf(0) }
    // The model capsule's right edge in window pixels: the sheet is aligned to it so the card
    // hangs off the button that opened it rather than off the free space around it. Measured
    // here because the capsule is this row's child, and read by [GenerationSheet].
    var capsuleRight by remember { mutableStateOf(0f) }
    // Where the model button's centre sits, in window pixels: the 生成设置 sheet is centred
    // on it rather than on the composer or the screen, which is what "right above the model
    // button" means. Read from the layout, because the row's paddings and the label's own
    // width decide it.
    val ctx = LocalContext.current
    // The two cards this row opens are focus-less (see [MenuCard]'s focusable): a focusable
    // popup window takes the keyboard away from the field, the shell then lays the composer
    // out a keyboard lower, and the card stays anchored to where the composer was — 250dp
    // above the button it belongs to, which is the 偏上 the user photographed. A focus-less
    // window cannot see a tap outside its own bounds and cannot see BACK, so both are caught
    // here. The scrim is a window of its own, composed before the cards: windows stack in
    // composition order, so a scrim composed after them would cover them instead.
    if (menuOpen || sheetOpen || approvalOpen) {
        Popup(properties = PopupProperties(focusable = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        menuOpen = false
                        sheetOpen = false
                        approvalOpen = false
                    },
            )
        }
    }
    // Focus-less popups do not receive BACK either: without this the press falls through to
    // the activity and finishes it.
    BackHandler(enabled = menuOpen || sheetOpen || approvalOpen) {
        menuOpen = false
        sheetOpen = false
        approvalOpen = false
    }
    // Nothing here paints a background: the transcript scrolls right down to the gesture
    // bar behind the pill, so the composer reads as something floating over the
    // conversation rather than an opaque strip that cuts it off.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The gesture bar's strip, but never on top of the keyboard: when the keyboard is
            // up it already covers that strip, and padding for it as well pushed the card a
            // navigation bar's height above the keyboard — the band the user photographed.
            // The shell's clamped keyboard height is what to subtract (clamped, so the
            // platform's end-of-animation rebound cannot flicker the band back for a frame).
            .padding(
                bottom = with(density) {
                    (WindowInsets.navigationBars.getBottom(this) - LocalHeldImePx.current)
                        .coerceAtLeast(0)
                        .toDp()
                },
            )
            .padding(horizontal = 16.dp)
            // The air under the pill, named: the list's own padding is this plus the strip
            // above, and it is measured here, inside the padding (see COMPOSER_BOTTOM_PAD).
            .padding(top = 6.dp, bottom = COMPOSER_BOTTOM_PAD)
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) }
            // Placed for this frame: the caller's follow pin is dispatched from here.
            .onGloballyPositioned { onPlaced() },
    ) {
        Column {
            // The jump-to-latest button rides the stack's own top: 12dp above whatever
            // the next row is, end-aligned, so it can never hide behind the pill.
            if (showJump) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 24dp inside this box, and the box itself is 16dp inside the screen, so
                        // the disc's right edge sits 40dp from the screen edge. The scrubber's
                        // own column is 4dp of edge gap plus 24dp of line, i.e. 28dp: 12dp of air
                        // between the disc and a turn line, which is what keeps them from reading
                        // as one control. It used to be 40dp here, which put the disc 56dp out —
                        // far enough that the user asked for it closer to the ring.
                        .padding(end = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // Deliberately one step up from the shared 40dp disc: this button is
                    // the only one that floats alone over the transcript with no bar behind
                    // it, and it has to be found without looking. Keep it a size larger
                    // than the header's discs rather than flattening it into the scale.
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_arrow_down),
                        contentDescription = "回到最新",
                        onClick = jumpToLatest,
                        size = 48.dp,
                        containerColor = scheme.surface,
                        iconSize = 22.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // No progress line here any more: the run's caption lives in the transcript,
            // where the answer is going to appear. Pinned above the pill it read as a
            // second, competing status area — and it carried no clock, so the wait looked
            // untimed while the user was staring at it.
            attachment?.let {
                AttachmentChip(attachment = it, onRemove = onClearAttachment)
                Spacer(Modifier.height(8.dp))
            }
            // The agent's own board, above the queue: what it is doing right now, then what the
            // user has queued for it. Same inset and spacing as the queue rows below, because
            // they answer the same question — "what is going on" — and a board in different
            // clothes would read as another app's card.
            //
            // Drawn only while this conversation's run is going: the plan belongs to the run
            // that made it, and once the answer has landed the transcript is the record. The
            // board itself is emptied when a run starts (see ChatViewModel.start), so a run that
            // never touches the todo tools cannot inherit the last one's list.
            if (running && todos.isNotEmpty()) {
                val rows = todoRows(todos)
                val done = todos.count { it.status == TODO_COMPLETED }
                // 一行，只一行：进行中的那条（没有就取第一条待办），右边是完成数/总数。用户的话：
                // 「不要堆多行，就一行就行了」——整条清单改由点击这一行打开（见 TodoSheet）。
                val current = rows.firstOrNull { it.status == TODO_IN_PROGRESS } ?: rows.firstOrNull()
                if (current != null) {
                    // 与排队行同一个内缩：它们同属「输入框上方那一列」，宽度必须一致。
                    Box(Modifier.padding(horizontal = STEER_QUEUE_INSET)) {
                        TodoQueueRow(
                            item = current,
                            tally = "$done/${todos.size}",
                            onClick = onOpenTodos,
                        )
                    }
                }
                // Tight to what follows, exactly as the queue is: these rows belong to the field
                // they sit above, not to the transcript behind them.
                Spacer(Modifier.height(3.dp))
            }
            // Queued lines sit directly above the field they came from: the user's own words
            // on their way to the model, not messages yet — so they are inset from the pill
            // (visibly narrower than it, which is what stops them reading as part of it) and
            // each one carries its own actions as icons.
            // 排队 turns first: they are the ones that have not gone anywhere yet, so they sit
            // closest to the field they will be sent from. Without this row the user pressed 插话
            // again and again — the line left the field with nothing on screen to show for it.
            if (pendingTurns.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STEER_QUEUE_INSET),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pendingTurns.take(STEER_ROWS_SHOWN).forEach { queued ->
                        // The same row, the same three actions — what changes is what they mean and
                        // what the row says, which `nextTurn` carries.
                        SteerQueueRow(
                            steer = PendingSteer(queued.id, queued.text, queued.imageBase64),
                            undelivered = false,
                            nextTurn = true,
                            onPromote = { onPromoteTurn(queued.id) },
                            onEdit = {
                                onRestoreSteer(PendingSteer(queued.id, queued.text, queued.imageBase64))
                                onRetractTurn(queued.id)
                            },
                            onDelete = { onRetractTurn(queued.id) },
                        )
                    }
                    if (pendingTurns.size > STEER_ROWS_SHOWN) {
                        Text(
                            text = "还有 ${pendingTurns.size - STEER_ROWS_SHOWN} 条待发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                // 与下面 pendingSteers 块同一个 3dp（用户：「插话进队列是紧贴的，点了立即插话
                // 就有缝隙了」——两种状态的间距必须恒定）。队列行属于它将要发出的那个输入框，
                // 但「紧贴」和「恒贴 3dp」之间选恒定：状态切换时布局不跳。
                Spacer(Modifier.height(3.dp))
            }
            if (pendingSteers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STEER_QUEUE_INSET),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pendingSteers.take(STEER_ROWS_SHOWN).forEach { queued ->
                        SteerQueueRow(
                            steer = queued,
                            // The run is over, so nothing will ever read this line unless the
                            // user sends it again — the row says so.
                            undelivered = !running,
                            onPromote = { onPromoteSteer(queued.id) },
                            onEdit = {
                                // Both halves go back, text and picture — see restoreQueuedSteer.
                                // The line is then retracted, so nothing is sent twice.
                                onRestoreSteer(queued)
                                onRetractSteer(queued.id)
                            },
                            onDelete = { onRetractSteer(queued.id) },
                        )
                    }
                    if (pendingSteers.size > STEER_ROWS_SHOWN) {
                        Text(
                            text = "还有 ${pendingSteers.size - STEER_ROWS_SHOWN} 条排队中",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                // Tight to the pill: a queued line belongs to the field it will be sent from,
                // and a gap here made it look like a floating card of its own.
                Spacer(Modifier.height(3.dp))
            }
            // Opaque surface with one real soft shadow: the drawn concentric rings read
            // as a halo, and 86% alpha let the transcript bleed through the pill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    ),
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                // Light uses the canvas colour; dark steps one surface up, because there
                // `surface` IS the canvas and the pill would vanish into it.
                color = if (LocalDarkTheme.current) scheme.surfaceContainer else scheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column {
                    // Row 1: the field itself, transparent on the capsule — no border,
                    // no container, just text with room to breathe above the controls.
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) onFocused()
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            color = scheme.onSurface,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 5,
                        // Enter sends — and while a run is live it sends the line to that
                        // run instead of starting a second one. 「回车键发送」 turned off makes
                        // the key a newline instead: the field is already multi-line, and the
                        // action button under it is then the only way to submit.
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (settings.enterKeySends) {
                                ImeAction.Send
                            } else {
                                ImeAction.Default
                            },
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val text = input.trim()
                                // A picture with no caption is still something to send: only an
                                // entirely empty composer does nothing here.
                                if (text.isEmpty() && attachment == null) return@KeyboardActions
                                if (running) {
                                    // The attachment rides along: 排队 mode turns this into the next
                                    // turn, 立即 mode into a steer, and either way a picture the user
                                    // attached belongs to the line.
                                    //
                                    // The field is cleared by the **view model**, not here — see
                                    // ChatViewModel.steer, which clears on both accepted paths. This
                                    // used to clear as well, and that second writer is why the text and
                                    // the picture kept coming back: `setDraft`'s defaults come from
                                    // *this composition's* captured draft, so `onInput("")` wrote the old
                                    // uri/name straight back into `_drafts`, and the collector pushed it
                                    // onto the screen a frame later.
                                    //
                                    // A refusal (the run ended between the last frame and this tap)
                                    // falls through to an ordinary send, so the line is never left
                                    // sitting in the box with nothing happening.
                                    if (!onSteer(text, attachment?.base64)) onSend()
                                } else {
                                    onSend()
                                }
                            },
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = when {
                                            pendingSteers.isNotEmpty() ->
                                                "还有 ${pendingSteers.size} 条排队中…"

                                            running -> "插一句话…"
                                            else -> "让随行做点什么…"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    // Row 2: attach, the two flat selectors, mic, action — the controls
                    // live under the text now. 48dp of content, not 44: the buttons carry
                    // a 48dp minimum touch target, and a shorter row squeezed them into
                    // ellipses with the circle clipped to a 40x36 box.
                    Row(
                        modifier = Modifier
                            // 10dp, and the box beside it adds 7dp more (a 36dp box around a 22dp
                            // glyph): the 「+」 sits 17dp from the capsule's edge, which is the
                            // 16–18dp the user asked for. The row's own padding is the only lever
                            // that moves that edge without moving the gap between the two buttons.
                            .padding(start = 10.dp, end = 12.dp, bottom = 8.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        // Zero: the two buttons' own boxes supply the only gap between their glyphs,
                        // and every dp spent here is a dp the user sees between the icons.
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                    // The anchor's own left edge and the card's width, both measured: the card opens
                    // to the right of this button, and on a window narrower than the button plus
                    // the card it used to hang off the screen's right edge.
                    var plusAnchorLeft by remember { mutableStateOf(0f) }
                    var plusWidth by remember { mutableStateOf(0) }
                    Box(Modifier.onGloballyPositioned { plusAnchorLeft = it.boundsInWindow().left }) {
                        IconButton(
                            onClick = { menuOpen = true },
                            // 36dp around the glyph's original 22dp. What the eye measures is the
                            // glyph's distance to the capsule's edge (the row's 10dp plus this box's
                            // 7dp) and to the glyph beside it (7 + 0 + 8 = 15dp), and both are set by
                            // this one number — so it is chosen once rather than tuned twice: at
                            // 40dp the pair would sit 19dp apart, outside the 14–16dp asked for. The
                            // 「不要浪费空间」 the user pointed at was the 11dp a side that the 44dp
                            // box carried; this one carries 7dp.
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_plus),
                                contentDescription = "添加图片或文件",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // The same card as the model sheet: one popup style for the whole
                        // screen, instead of a Material dropdown here and a card there.
                        MenuCard(
                            visible = menuOpen,
                            onDismiss = { menuOpen = false },
                            // Opens upward from the button it belongs to. BottomStart puts the
                            // popup's bottom edge on the anchor's bottom edge, and the anchor is the
                            // button's own 36dp box — so the offset clears that box plus the 8dp of
                            // air the card is meant to leave above it (-8dp alone left the card
                            // sitting on the button).
                            alignment = Alignment.BottomStart,
                            offset = with(density) {
                                IntOffset(
                                    popupNudge(
                                        anchorLeft = plusAnchorLeft,
                                        width = plusWidth,
                                        windowPx = screenWidthPx,
                                        edgePx = popupEdgePx,
                                    ).roundToInt(),
                                    -44.dp.roundToPx(),
                                )
                            },
                            // Same reason as the model sheet: this card hangs off a button of
                            // the composer too, and a focusable popup here would move the
                            // composer out from under it.
                            focusable = false,
                            modifier = Modifier
                                .widthIn(max = popupCap(200.dp, windowWidth))
                                .onSizeChanged { plusWidth = it.width },
                        ) {
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_image),
                                label = "图片",
                                onClick = {
                                    menuOpen = false
                                    onPickImage()
                                },
                            )
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_camera),
                                label = "拍照",
                                onClick = {
                                    menuOpen = false
                                    onTakePhoto()
                                },
                            )
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_file),
                                label = "文件",
                                onClick = {
                                    menuOpen = false
                                    onPickFile()
                                },
                            )
                            // Always listed: the entry disappearing between runs made users
                            // think the feature was gone. With no run in flight the engine
                            // refuses and the toast says why — a visible refusal beats a
                            // missing menu item.
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
                                label = "压缩上下文",
                                onClick = {
                                    menuOpen = false
                                    val asked = onRequestCompress()
                                    Toast.makeText(
                                        ctx,
                                        if (asked) {
                                            "已请求压缩，本轮结束后生效"
                                        } else {
                                            "压缩在任务进行中才生效：等下一轮开跑再点它"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                    // 工具审批, right of the add button: the same choice the settings page
                    // offers (自动运行 / 每次询问 / 禁止), one tap from the field it governs —
                    // asking for approval is a decision about the question being typed, and
                    // walking to 设置 mid-thought is how the setting stayed on whatever it was.
                    // The icon is the mode itself, so the row says which one is in force without
                    // being opened: a tick when it runs on its own, a shield while it asks, an
                    // alert shield when it refuses.
                    Box(
                        Modifier.onGloballyPositioned {
                            approvalAnchorLeft = it.boundsInWindow().left
                        },
                    ) {
                        IconButton(
                            onClick = { approvalOpen = true },
                            // Same 36dp box and the same glyph size as the 「+」 beside it: the two are
                            // one visual group on the row's left, so they are measured together.
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(approvalIcon(approvalMode)),
                                contentDescription = "工具审批：${approvalMode.label}",
                                tint = when (approvalMode) {
                                    ApprovalMode.DENY -> scheme.error
                                    ApprovalMode.AUTO -> scheme.primary
                                    else -> scheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        MenuCard(
                            visible = approvalOpen,
                            onDismiss = { approvalOpen = false },
                            alignment = Alignment.BottomStart,
                            offset = with(density) {
                                IntOffset(
                                    popupNudge(
                                        anchorLeft = approvalAnchorLeft,
                                        width = approvalWidth,
                                        windowPx = screenWidthPx,
                                        edgePx = popupEdgePx,
                                    ).roundToInt(),
                                    -44.dp.roundToPx(),
                                )
                            },
                            focusable = false,
                            modifier = Modifier
                                .widthIn(max = popupCap(220.dp, windowWidth))
                                .onSizeChanged { approvalWidth = it.width },
                        ) {
                            ApprovalMode.entries.forEach { mode ->
                                MenuItemRow(
                                    icon = ImageVector.vectorResource(approvalIcon(mode)),
                                    label = mode.label,
                                    selected = mode == approvalMode,
                                    onClick = {
                                        approvalOpen = false
                                        onPickApproval(mode)
                                    },
                                )
                            }
                            // The mode is read when a run starts (see ApprovalGate), so saying
                            // so here is the difference between a setting and a surprise.
                            Text(
                                text = "下一次任务生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                    }
                    // One selector for both decisions: the model and the reasoning
                    // level live together in the 生成设置 sheet, so the row keeps a
                    // single caption instead of two competing ones.
                    //
                    // The caption is the row's flexible child (weight, not a fixed cap):
                    // capping it at 104dp was what squeezed a long model id down to a single
                    // ellipsis while the rest of the row sat empty, and it could still
                    // overflow a 360dp screen once the ring, the mic and the send button
                    // were all present. Weighted, it takes exactly the space the controls
                    // leave — never more — so the row can neither clip the send button nor
                    // starve the name. The name itself caps at 160dp and ellipsizes after
                    // that, which is the one truncation the user asked for.
                    Box(Modifier.weight(1f)) {
                        // The sheet hangs off the capsule itself, not off a probe at the top
                        // of the stack: anchored there it opened above the whole composer, and
                        // the user asked for it above the model button. Right-aligned inside
                        // the free space so the capsule sits against the ring, as asked.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                // The probe and the capsule are siblings here, and that is the whole
                                // of it: as a *wrapper* around them the probe stretched this box —
                                // a Box is as wide as its widest child, and a full-width child makes
                                // it the whole free space — and the capsule inside then sat at that
                                // box's own start (TopStart, the default), i.e. the model selector
                                // at the far left of an empty row with the ring way off to the
                                // right. That is exactly the screenshot the user sent: the
                                // right-alignment was written and had nothing to align.
                                //
                                // Zero-height probe at the capsule's TOP edge: the popup hangs
                                // off it bottom-aligned, so its bottom edge lands 8dp above the
                                // capsule. Hung on the capsule box itself, BottomStart put the
                                // popup's bottom on the capsule's bottom — covering the button
                                // that opened it, which is what the user photographed.
                                Box(Modifier.fillMaxWidth().height(0.dp)) {
                                    GenerationSheet(
                                        settings = settings,
                                        assistantId = assistantId,
                                        conversationProviderId = conversationProviderId,
                                        conversationModel = conversationModel,
                                        conversationReasoningEffort = conversationReasoningEffort,
                                        onChooseReasoning = onChooseReasoning,
                                        visible = sheetOpen,
                                        capsuleRight = capsuleRight,
                                        onPickModel = onPickModel,
                                        onOpenSettings = onOpenSettings,
                                        onDismiss = { sheetOpen = false },
                                    )
                                }
                                // The capsule's own right edge, measured: the sheet hangs off
                                // the button that opened it, and the free space around the
                                // button is wider than the button whenever the model name is
                                // short — right-aligning to the free space left the card that
                                // far to the right of the control it belongs to.
                                Box(
                                    Modifier.onGloballyPositioned {
                                        capsuleRight = it.boundsInWindow().right
                                    },
                                ) {
                                UnifiedSelector(
                                settings = settings,
                                assistantId = assistantId,
                                conversationProviderId = conversationProviderId,
                                conversationModel = conversationModel,
                                conversationReasoningEffort = conversationReasoningEffort,
                                onClick = {
                                    sheetOpen = true
                                    // The sheet is the one place models are looked at, so
                                    // this is where the endpoint's own window numbers are
                                    // worth asking for — once per endpoint per process.
                                    onPrefetchWindows()
                                },
                                )
                                }
                        }
                    }
                    // Left of the mic, where the user asked for it: the mic is the control
                    // reached for mid-thought, and this is the gauge that says whether the
                    // next question still fits in the window.
                    ContextRing(
                        used = contextUsed,
                        estimated = contextEstimated,
                        window = contextWindow,
                        sessionTokens = sessionTokens,
                        sessionEstimate = sessionEstimate,
                        sessionSplit = sessionSplit,
                        sessionTurns = sessionTurns,
                        sessionPerSecond = sessionPerSecond,
                        sessionCachePercent = sessionCachePercent,
                        sessionElapsedMs = sessionElapsedMs,
                        live = stats,
                    )
                    // The ring's own right margin: the row packs its children edge to edge, and the
                    // gauge sat hard against whatever follows it — the mic when 语音输入 is on, the
                    // send button when it is not. Four dp, and nothing else in the row moves: the
                    // model capsule to its left takes the free space either way.
                    Spacer(Modifier.width(4.dp))
                    // 设置里的「语音输入」是唯一的开关，默认关：关着时这里什么都不画，连占位都不留。
                    // 开着也不需要系统识别器了——我们自己的录音链路走 AudioRecord，不会再弹系统面板。
                    if (voiceInput) {
                        IconButton(
                            onClick = onMic,
                            enabled = !running,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_mic),
                                contentDescription = "语音输入",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    val hasSomething = input.isNotBlank() || attachment != null
                    // While a run is going, a field with something in it sends that line to the
                    // run; an empty field stops it. One button, and what it does is what the
                    // field in front of the user says — the separate 插话 button only ever made
                    // the user wonder which of the two they wanted.
                    //
                    // "Something" is text **or** a picture: a screenshot with no caption is still a
                    // turn the user wants to send, and judging by text alone left that case with no
                    // way out at all — the button stayed on 停止 and the attachment could not go
                    // anywhere until the run ended. 停止 is for an empty field, nothing else.
                    val steers = running && hasSomething
                    val sendable = running || hasSomething
                    CircleActionButton(
                        icon = if (running && !steers) {
                            ImageVector.vectorResource(R.drawable.ic_stop_filled)
                        } else {
                            ImageVector.vectorResource(R.drawable.ic_arrow_up)
                        },
                        contentDescription = when {
                            steers -> "插话发送"
                            running -> "停止"
                            else -> "发送"
                        },
                        onClick = {
                            when {
                                // Cleared by the view model when it takes the line, not here — see the
                                // note on the Enter path above. A refusal falls through to a normal
                                // send, so the text cannot be stranded in the field.
                                steers -> if (!onSteer(input.trim(), attachment?.base64)) onSend()
                                running -> onStop()
                                else -> onSend()
                            }
                        },
                        enabled = sendable,
                        size = 40.dp,
                        // Three states, three colours: a live run offers stop, a field with
                        // something in it turns brand-coloured so the button reads as ready,
                        // and an empty field stays a quiet grey circle.
                        containerColor = if (running || hasSomething) {
                            scheme.primary
                        } else {
                            scheme.surfaceVariant
                        },
                        contentColor = if (running || hasSomething) {
                            scheme.onPrimary
                        } else {
                            scheme.onSurfaceVariant
                        },
                        // Flat: the pill's own lift is the only depth in this corner.
                        shadowElevation = 0.dp,
                        iconSize = 22.dp,
                    )
                    }
                }
            }
        }
    }
            }
}

/** The composer's status line: plain 13sp text with no container of its own. */
@Composable
private fun statusLineStyle(): TextStyle = MaterialTheme.typography.labelMedium

/**
 * 思考强度 beside the model chip: the same six levels as the settings slider, written
 * through on selection. It lands on the provider the active assistant is bound to when
 * that provider owns its sampling knobs, and on the global setting otherwise — the same
 * precedence [AppSettings.forAssistant] resolves with.
 */
/**
 * The one sheet both composer captions open: which model answers and how hard it
 * thinks are one decision, so they share one surface — model name and level badge on
 * top, the six-step slider in the middle, the provider's enabled models as radio rows
 * below. Every change writes through at once; there is no confirm button to forget.
 *
 * It hangs off the composer's zero-height anchor box (see `ComposerStack`, the `Box` at the
 * top of the stack): the sheet opens upward from there and its bottom edge lands 8dp above
 * that box, so it never covers the field it belongs to. Horizontally its right edge lines up
 * with the composer's content edge, and it is nudged back inside the screen when that would
 * hang it off an edge. Right-aligned because centring it on the capsule put the sheet's mass
 * left of the ring, which the user read as left-aligned.
 */
/** How long a popup card's exit animation runs before the popup is taken down. */
private const val MENU_EXIT_MS = 140L

/**
 * The app's one popup card: rounded, shadowed, and animated in and out.
 *
 * Every menu on this screen wears it — the 生成设置 sheet and the 「+」 attachment menu — so
 * they read as one control instead of as two unrelated leftovers. The caller keeps passing
 * [visible]; this keeps the popup composed until the exit animation has played, because a
 * popup taken down the moment it is dismissed has no time to animate out.
 */
@Composable
private fun MenuCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment,
    offset: IntOffset,
    modifier: Modifier = Modifier,
    /**
     * Whether the popup's own window takes focus.
     *
     * False by default, and every caller in this file passes it explicitly. That default is the
     * lesson of the 「点圆环把输入法关了」 report: a focusable popup window takes the keyboard away
     * from the field, the shell then drops the whole composer by the keyboard's height, and a card
     * anchored 8dp above a button of that row is left floating a keyboard above it — the card the
     * user photographed as 偏上. Only a card whose own subject *is* the keyboard (a field inside
     * it) should ask for `true`; everything hanging off this screen's bars is focus-less, which
     * means it can see neither a tap outside itself nor BACK, so both live with the caller: see the
     * scrim and BackHandler in [Composer], the ring's card, and the metric card's call site.
     */
    focusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var keepMounted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            keepMounted = true
        } else {
            delay(MENU_EXIT_MS)
            keepMounted = false
        }
    }
    // A plain return from this composable, before any group is opened: while the card is
    // closed there is no popup at all, and nothing to intercept a touch with.
    if (!keepMounted) return
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
        ) {
            Column(
                modifier = modifier
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                content = content,
            )
        }
    }
}

/** The margin a popup keeps from the window's own edges. */
private val POPUP_EDGE = 8.dp

/**
 * A popup's width cap: the width it wants, never wider than the window it opens in.
 *
 * [windowWidth] is `screenWidthDp`, the window's own width — in split screen the display's width
 * would hand a card a size the window does not have. A card wider than its window is a card whose
 * right half is off the screen: the model sheet's 340dp needs a 356dp window to fit.
 */
private fun popupCap(nominal: Dp, windowWidth: Dp): Dp = minOf(nominal, windowWidth - POPUP_EDGE * 2)

/**
 * How far a popup anchored at [anchorLeft] has to move to stay inside the window.
 *
 * The card is [width] wide and opens to the right of its anchor, so only its left edge can be
 * moved; [edgePx] of air is kept on both sides. The same rule the model sheet's own nudge uses,
 * shared here so the card hung off the row's left end obeys it too.
 */
private fun popupNudge(anchorLeft: Float, width: Int, windowPx: Float, edgePx: Float): Float {
    if (width <= 0 || anchorLeft == 0f) return 0f
    val left = anchorLeft.coerceIn(edgePx, (windowPx - edgePx - width).coerceAtLeast(edgePx))
    return left - anchorLeft
}

/**
 * [popupNudge] for a card centred on its anchor instead of left-aligned to it — the ring's own
 * detail card, which is centred on a ring that sits at the row's right end.
 */
private fun popupNudgeCentred(anchorCentre: Float, width: Int, windowPx: Float, edgePx: Float): Float {
    if (width <= 0 || anchorCentre == 0f) return 0f
    val desired = anchorCentre - width / 2f
    val left = desired.coerceIn(edgePx, (windowPx - edgePx - width).coerceAtLeast(edgePx))
    return left - desired
}

/** One row of a popup menu: icon, label, and the whole row as the target. */
@Composable
private fun MenuItemRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** Ticked on the right when this row is the one in force; the caller says which. */
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The thinking-strength slider: a gradient-filled track with a round handle, drawn here
 * rather than taken from Material.
 *
 * The stock slider's hairline rail and its flanking icons were the look the user called
 * old-fashioned; this one carries the app's own colours and nothing else. The value is a
 * level index, so a drag snaps to the nearest step as it goes.
 */
@Composable
private fun LevelSlider(levelIndex: Int, levels: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val last = (levels - 1).coerceAtLeast(1)
    var width by remember { mutableStateOf(0) }
    val fraction = (levelIndex.toFloat() / last).coerceIn(0f, 1f)
    val pick: (Float) -> Unit = { x ->
        if (width > 0) {
            val index = (x / width * last).roundToInt().coerceIn(0, last)
            if (index != levelIndex) onChange(index)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(levels) { detectTapGestures { spot -> pick(spot.x) } }
            .pointerInput(levels) {
                detectHorizontalDragGestures { change, _ -> pick(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val trackHeight = 8.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val top = (size.height - trackHeight) / 2f
            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = radius,
            )
            // Filled up to the handle, in a brand gradient: a flat colour read as a
            // progress bar rather than as a control that can be moved.
            val filled = (size.width * fraction).coerceAtLeast(trackHeight)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(scheme.primary.copy(alpha = 0.55f), scheme.primary),
                ),
                topLeft = Offset(0f, top),
                size = Size(filled, trackHeight),
                cornerRadius = radius,
            )
            val handle = 11.dp.toPx()
            val centre = Offset(
                x = (size.width * fraction).coerceIn(handle, (size.width - handle).coerceAtLeast(handle)),
                y = size.height / 2f,
            )
            // A surface ring inside the handle, so it stays visible where it overlaps the
            // filled part of the rail.
            drawCircle(color = scheme.surface, radius = handle, center = centre)
            drawCircle(color = scheme.primary, radius = handle - 2.dp.toPx(), center = centre)
        }
    }
}

@Composable
private fun GenerationSheet(
    settings: AppSettings,
    /** The persona the conversation runs with: the sheet must name the endpoint a run
     *  will actually reach, and the app-wide active persona is a different one. */
    assistantId: String?,
    /** This conversation's own pick, or null while it follows the persona and defaults. */
    conversationProviderId: String?,
    conversationModel: String?,
    /** The conversation's own thinking level, if it ever picked one. */
    conversationReasoningEffort: String?,
    /** Writes a level onto this conversation alone; see the view model's chooseReasoning. */
    onChooseReasoning: (String) -> Unit,
    /** The model button's centre, in window pixels; 0 until the composer has measured it. */
    /** Whether the card is open. It animates in and out on this, so it stays composed. */
    visible: Boolean,
    /**
     * The model capsule's right edge in window pixels, or 0 before the composer has measured
     * it. The sheet is right-aligned to this edge rather than to the free space around the
     * capsule: the free space is wider than the button whenever the model name is short, and
     * the card then sat that far to the right of the control it belongs to.
     */
    capsuleRight: Float,
    onPickModel: (ProviderConfig, String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val assistant = assistantId?.let { settings.assistant(it) } ?: settings.activeAssistant()
    // One chain for the whole screen — this conversation's pick, then the persona's pins,
    // then the app-wide default — so the header, the tick and the request all agree.
    val choice = settings.choiceFor(conversationProviderId, conversationModel, assistant)
    // The endpoint the choice lands on; a conversation that never picked falls through to
    // the base url the app is talking to, and a provider with anything enabled is the last
    // witness. Null only when there is genuinely nothing configured.
    val provider = settings.providers.firstOrNull { it.id == choice.providerId }
        ?: settings.providers.firstOrNull {
            it.baseUrl.trimEnd('/') == choice.baseUrl.trimEnd('/')
        }
        ?: settings.providers.firstOrNull { it.enabled.isNotEmpty() }
    val wire = reasoningWire(settings, provider, conversationReasoningEffort)
    val levelIndex = REASONING_LEVELS.indexOfFirst { it.first == wire }.coerceAtLeast(0)
    // Every endpoint with ticked models can offer one: this picker is the only place to
    // switch endpoints without a detour through settings, so it lists them all instead
    // of only the endpoint the assistant happens to point at. Inside one endpoint the
    // ids split by upstream vendor (an aggregating proxy prefixes them).
    val offers = remember(settings.providers, choice.model, choice.providerId) {
        modelOffers(settings.providers, choice.model, provider)
    }
    val density = LocalDensity.current
    // The anchor's own left edge and the sheet's own width, both measured: the first is
    // where the popup's alignment starts counting from, the second is what decides how far
    // a centred sheet would hang off an edge.
    var anchorLeft by remember { mutableStateOf(0f) }
    var anchorRight by remember { mutableStateOf(0f) }
    var sheetWidth by remember { mutableStateOf(0) }
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenPx = with(density) { windowWidth.toPx() }
    val edge = with(density) { POPUP_EDGE.toPx() }
    // Right-aligned to the capsule that opened it, not centred: centred put the sheet's mass to
    // the left of the ring, which read as left-aligned. Falls back to the free space's own right
    // edge until the capsule has been measured — the two agree whenever the name is long enough
    // to fill the row, which is the case the old anchor was written for.
    val nudge = if (sheetWidth <= 0 || anchorLeft == 0f) {
        0f
    } else {
        val width = sheetWidth.toFloat()
        val right = if (capsuleRight > 0f) capsuleRight else anchorRight
        val left = (right - width)
            .coerceIn(edge, (screenPx - edge - width).coerceAtLeast(edge))
        left - anchorLeft
    }
    // Zero-height, full-width probe inside the composer's anchor box: it reads that box's
    // edges, which are the origin the popup's own alignment is measured from.
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                anchorLeft = it.boundsInWindow().left
                anchorRight = it.boundsInWindow().right
            },
    )
    // Which page the sheet is on: the light one — the model that will answer and how hard it
    // thinks — or the full list of models. The list became a page of its own because the user
    // asked for the first page to stay small.
    var picking by remember { mutableStateOf(false) }
    // 关掉就回第一页（用户：「选了模型进列表页，关掉再开还停在列表页，要点返回才到思考强度页」）。
    // 这张卡关着也保持 composed（为了进出动画），页状态不会随卸载重置，所以显式在 visible 落
    // false 时清掉。只清页、不清 [folded]：折叠是用户对着列表做的选择，重开还在更顺手。
    LaunchedEffect(visible) {
        if (!visible) picking = false
    }
    // Providers whose list is folded away. The one answering this conversation starts open:
    // the list is what the page is for, and folding everything would hide it.
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    val modelLabel = provider?.displayModel(choice.model)
        ?.takeIf { it != choice.model }
        ?: choice.model.substringAfter('/').ifBlank { "选模型" }
    MenuCard(
        // Anchored to the composer's own top edge, not a sheet from the screen's bottom:
        // the picker is a control of the row that opened it. Start-aligned plus [nudge] is
        // how the card ends up centred on the model button.
        visible = visible,
        onDismiss = onDismiss,
        alignment = Alignment.BottomStart,
        offset = with(density) { IntOffset(nudge.roundToInt(), -8.dp.roundToPx()) },
        // Focus-less: opening this card must not take the keyboard off the field, or the
        // composer falls by the keyboard's height under a card anchored to where it used to
        // be. See [MenuCard]'s focusable and the scrim in [Composer].
        focusable = false,
        // One width per page. Page one is one model name and one slider, and 340dp is 94% of a
        // 360dp screen — at that width the card read as a full-width panel rather than as
        // something hanging off the button that opened it, so page one keeps 300dp (content
        // 264dp after MenuCard's own 18dp padding either side). The list page is the one that
        // needs the room: its provider and vendor headings are single names the user is reading
        // to find a model, and 264dp of content cut the long ones at the card's edge. The 340dp
        // that first fixed this is back, but only while the list is up.
        modifier = Modifier
            .widthIn(max = popupCap(if (picking) 340.dp else 300.dp, windowWidth))
            .onSizeChanged { sheetWidth = it.width },
    ) {
        if (!picking) {
            // Page one: one big name to tap, one slider to drag, nothing else.
            //
            // Hugs its own content instead of the card's cap. The only control here that
            // could have decided the width is the slider, and a slider is as wide as what it
            // slides on — so a fillMaxWidth slider pinned the card to its maximum, which is
            // what made a card holding two short lines look like a panel. widthIn is written
            // first on purpose: the intrinsic size is then coerced into the floor and the cap,
            // so a short model id gives a narrow card and a long one is still cut off at the
            // cap rather than at the screen's edge.
            Column(
                modifier = Modifier
                    .widthIn(min = 200.dp, max = 260.dp)
                    .width(IntrinsicSize.Max),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                    ) { picking = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                    contentDescription = "选择模型",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "思考强度",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(shape = CircleShape, color = scheme.primaryContainer) {
                    Text(
                        text = REASONING_LEVELS[levelIndex].second,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LevelSlider(levelIndex = levelIndex, levels = REASONING_LEVELS.size) { index ->
                // The level belongs to this conversation, like the model pick beside it:
                // writing it to the app-wide default here is what re-timed every other chat.
                onChooseReasoning(REASONING_LEVELS[index].first)
            }
            }
        } else {
            // Page two: every endpoint's models, the one that answers this conversation first
            // and already open.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picking = false }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_left),
                        contentDescription = "返回",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "选择模型",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // No endpoint has anything ticked: the picker has nothing to offer, so the
            // way out is the settings page. Asking the offers list would hide that way
            // out as soon as the active model is the only thing left in it.
            if (settings.providers.none { it.enabled.isNotEmpty() }) {
                Text(
                    text = "还没有启用任何模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                    Text("去设置里拉取")
                }
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    offers.forEach { (owner, ids) ->
                        // Folded per endpoint, with the one answering this conversation left
                        // open: the page exists to show models, and a column of folded
                        // headers would hide the very thing it is for.
                        val open = !(folded[owner.id] ?: (owner.id != choice.providerId))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(),
                                ) { folded[owner.id] = open }
                                // 4dp of side air, not none: this row clips itself to
                                // shapes.small for its ripple, and a label sitting flush against
                                // that edge had the descender of a name like "qwen" cut by the
                                // corner arc — worse the rounder the 外观 corner setting is. The
                                // vendor rows below are inset by the same 4dp.
                                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = owner.shortLabel,
                                // bodyMedium, the size the model rows themselves are set in:
                                // two levels of heading over the rows read as a list of three
                                // unrelated sizes when they were smaller than what they head.
                                // Weight and colour are what separate the levels now — SemiBold
                                // in the accent colour here, onSurfaceVariant on the vendor
                                // rows below.
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = scheme.primary,
                                // Two lines and an ellipsis, not "it wraps by itself": a
                                // heading here is usually a host or a `vendor/model` pair, one
                                // ASCII run with no break opportunity inside it, and such a run
                                // cannot wrap — it was cut at the card's edge with nothing to
                                // say it had been. Two lines let a real name through; the
                                // ellipsis is the honest signal for the ones that still do not
                                // fit. `fill = false` stays, so the chevron keeps the right edge
                                // either way.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = if (open) {
                                    ImageVector.vectorResource(R.drawable.ic_chevron_up)
                                } else {
                                    ImageVector.vectorResource(R.drawable.ic_chevron_down)
                                },
                                contentDescription = if (open) "折叠" else "展开",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        // A folded endpoint simply has nothing to draw; an empty list keeps
                        // the block below identical for both states. The type is written out
                        // because `emptyList()` on its own gives the compiler nothing to
                        // infer from — `groupModels` returns (group, ids) pairs.
                        val groups: List<Pair<String, List<String>>> =
                            if (open) groupModels(ids) else emptyList()
                        groups.forEach { (group, groupIds) ->
                            if (groups.size > 1) {
                                Text(
                                    text = group,
                                    // Same size class as the provider titles above and as the
                                    // model rows below: one size for the whole list, with the
                                    // colour and the weight telling the levels apart. Two lines
                                    // and an ellipsis for the same reason as the provider title
                                    // above: a vendor id is one unbreakable ASCII run, so with no
                                    // cap it was cut at the card's edge with no sign of it, and
                                    // the user reported exactly that.
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                )
                            }
                            groupIds.forEach { id ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(),
                                        ) { onPickModel(owner, id) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        // Ticked on the resolved choice, never on the raw
                                        // settings: a conversation that picked its own model
                                        // shows that one, and one that never picked shows
                                        // whatever the persona and the default resolve to.
                                        selected = owner.id == choice.providerId &&
                                            id == choice.model,
                                        onClick = null,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = owner.displayModel(id),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the dictation card is doing.
 *
 * The card used to be a single `listening` flag, which is why a recognizer that refused the
 * app closed the card without a word: there was no state in which a failure could be shown.
 */
private sealed interface VoicePhase {
    /** Between startListening and onReadyForSpeech: the recognizer is warming up. */
    object Preparing : VoicePhase

    object Listening : VoicePhase

    /** Speech ended; the recognizer is turning it into text, and the mic is closed. */
    object Thinking : VoicePhase

    /** It failed. [message] says why in plain words, [code] keeps the raw code for retry. */
    data class Failed(val message: String, val code: Int) : VoicePhase
}

/**
 * The recognizer's error codes in words a user can act on.
 *
 * Kept even though the in-app recognizer is gone: the system-activity fallback still reports
 * codes through this table when the ROM's own screen answers with one.
 */
private fun voiceErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_CLIENT -> "识别服务拒绝了这个应用（ERROR_CLIENT）"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，请稍后再试（ERROR_RECOGNIZER_BUSY）"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        "没有录音权限（ERROR_INSUFFICIENT_PERMISSIONS）"

    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "网络不可用（ERROR_NETWORK）"

    SpeechRecognizer.ERROR_SERVER -> "识别服务出错（ERROR_SERVER）"
    SpeechRecognizer.ERROR_AUDIO -> "录音出错（ERROR_AUDIO）"
    SpeechRecognizer.ERROR_NO_MATCH -> "没听清（ERROR_NO_MATCH）"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到说话（ERROR_SPEECH_TIMEOUT）"
    else -> "识别失败（错误码 $error）"
}

/**
 * 随行自己的听写：输入框上方一条细胶囊，屏幕上从头到尾只有它。
 *
 * 录音走 [DictationRecorder]（`AudioRecord`，16k/mono/PCM16 → wav），转写走 [Dictation]：把 wav
 * 发给用户已经配置好的那个服务商。**这条路完全不碰 `SpeechRecognizer`** —— 之前两版都栽在它上面：
 * 设备端模型没装、`EXTRA_PREFER_OFFLINE` 也拦不住 ROM 自己的识别面板，而那个面板被用户否了两次。
 *
 * 手势与按钮：长按胶囊＝说完了（失败态＝重来一次），「说完了」按钮做同一件事，× 取消；端点检测
 * （静音约 1.2 秒或录满 60 秒）会自己收尾。没配转写模型时**不录音**，直接说清楚并给一个「去设置」，
 * 绝不静默回落到系统识别；系统识别只在用户主动点「改用系统识别」时才出现。
 */
@Composable
private fun VoiceOverlay(
    /** 这一份设置：转写要用它的服务商与模型，所以由调用方直接传进来。 */
    settings: AppSettings,
    /** How far up the composer reaches: the capsule sits just above it, never over it. */
    bottomInset: Dp,
    /** 「去设置」的去处：没填转写模型时用得上。 */
    onOpenSettings: () -> Unit,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<VoicePhase>(VoicePhase.Preparing) }
    // The microphone's own level, 0..1: what the capsule's bars are drawn from.
    var level by remember { mutableStateOf(0f) }
    var elapsed by remember { mutableStateOf(0L) }
    // Set the moment this card hands the job to the system's own speech screen. That screen is
    // a screen, not a dialog behind ours, so drawing the card while it is up stacks the two.
    var handedOff by remember { mutableStateOf(false) }
    // 我们自己的录音：这条路完全不碰 SpeechRecognizer，所以屏幕上不会出现任何系统识别面板。
    val recorder = remember { DictationRecorder(context) }
    val asrReady = remember(settings.asrModel, settings.baseUrl, settings.apiKey) {
        Dictation.configured(settings)
    }

    fun finish() {
        if (phase !is VoicePhase.Listening) return
        // The state moves first, on this thread: the guard above is what stops a second tap (and
        // the auto-stop that fires at the same moment) from stopping the recorder twice, and it
        // has to close before any of the work is handed off.
        phase = VoicePhase.Thinking
        scope.launch {
            // `stop()` writes the wav — up to sixty seconds of 16-bit mono at 16kHz, about 1.9MB —
            // and this runs from the card's own buttons and from the recorder's auto-stop, all of
            // them on the main thread. Writing it there is a stall the user sees as a frozen
            // capsule, so the file is written and the transcription requested off it.
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                phase = VoicePhase.Failed("没有录到声音", 0)
                return@launch
            }
            val outcome = Dictation.transcribe(settings, wav)
            wav.delete()
            outcome
                .onSuccess { text ->
                    onResult(text)
                    onDismiss()
                }
                .onFailure { error -> phase = VoicePhase.Failed(error.message ?: "转写失败", 0) }
        }
    }

    fun begin() {
        if (!asrReady) {
            // 不静默回落到系统识别：那正是用户否掉两次的东西，缺什么就说什么。
            phase = VoicePhase.Failed("还没填语音转写模型", 0)
            return
        }
        if (!recorder.start()) {
            phase = VoicePhase.Failed("拿不到麦克风", 0)
            return
        }
        // 端点检测在录音线程上判定，收尾必须回到主线程。
        recorder.onAutoStop = { scope.launch { finish() } }
        phase = VoicePhase.Listening
    }

    DisposableEffect(Unit) {
        begin()
        onDispose { recorder.cancel() }
    }
    // 秒数与竖条：从录音器读，10 次/秒足够，也省掉一条动画。
    LaunchedEffect(phase) {
        while (phase is VoicePhase.Listening) {
            level = recorder.level
            elapsed = recorder.elapsedMs
            delay(100)
        }
        level = 0f
    }
    // The way out when the in-app recognizer will not have us: the system's own speech
    // screen, which on this device is the one that can reach the installed recognizer.
    val systemInput = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        voiceTrace(
            context,
            "systemActivity result=${result.resultCode} chars=${text?.length ?: 0}",
        )
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrEmpty()) {
            onResult(text)
            // The same pair the in-app recognizer's onResults calls: the words go into the field
            // and the card closes. Without it the overlay stayed composed after the system screen
            // was gone — nothing drawn, because it had been handed off, but still open, so the
            // next tap on the mic (voiceOpen already true) brought an overlay back that could
            // never draw again.
            onDismiss()
        } else {
            // Backing out of the system screen is the user's own decision and says nothing;
            // an OK with no words is the recognizer having heard nothing, which used to look
            // exactly like a cancel.
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(context, "没有识别到内容", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }
    val useSystemInput: () -> Unit = {
        // 这是**用户主动**选的兜底，不再自动交接：那个系统面板正是被否掉两次的东西，只在用户
        // 自己要的时候才拉起来。诊断仍留痕，顺便记下这台机器本来会走哪条识别引擎。
        voiceTrace(context, "handoff=systemActivity systemEngine=${voiceEngine(context).name}")
        // 我们自己的录音要先放手，否则系统界面拿不到麦克风。
        recorder.cancel()
        phase = VoicePhase.Preparing
        // From here on the system screen owns the job, and this card stops drawing: it is not a
        // dialog behind ours, it is the foreground.
        handedOff = true
        try {
            systemInput.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                },
            )
        } catch (missing: ActivityNotFoundException) {
            // Nothing was handed off after all: putting the card back is the only way the user
            // ever sees why there is no way out on this device.
            handedOff = false
            phase = VoicePhase.Failed("这台设备没有可用的系统语音输入", 0)
        }
    }
    val failure = phase as? VoicePhase.Failed
    val listening = phase is VoicePhase.Listening
    val scheme = MaterialTheme.colorScheme
    val title = when {
        failure != null -> failure.message
        listening -> "正在听… " + formatDictationTime(elapsed)
        phase is VoicePhase.Thinking -> "正在转写…"
        else -> "正在准备麦克风…"
    }
    // Nothing to draw once the system screen has it. Everything above still runs — the launcher
    // and its result callback are what close this overlay from here.
    if (handedOff) return
    Box(Modifier.fillMaxSize()) {
        // Tap anywhere to give up — but with no scrim: the transcript stays readable while the mic
        // is open, which is the point of a capsule. The dark overlay over the whole screen is half
        // of what made the old card read as 丑.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + 8.dp)
                .fillMaxWidth()
                // One gesture for the two things a finger wants here: 长按 ends the sentence while
                // it is listening, and asks for one more try once it has failed. Everything else
                // that used to be a button on the old card lives here now.
                .pointerInput(phase) {
                    detectTapGestures(
                        onLongPress = {
                            // 长按＝「说完了」，正在录音时唯一的快捷手势；失败态长按＝重来一次。
                            if (listening) finish() else if (failure != null) begin()
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                contentColor = scheme.onSurface,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (failure == null) {
                        VoiceBars(level = level, active = listening, tint = scheme.primary)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = if (failure != null) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            },
                            color = if (failure != null) scheme.error else scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 转写走的是哪个模型，以及那个不占按钮的手势。留空时这行就是「去设置里
                        // 填一个」的提示，所以失败态的说明在下面那行按钮上。
                        Text(
                            text = when {
                                failure != null -> "长按重试"
                                listening -> "长按说完 · 转写：${settings.asrModel.ifBlank { "未配置" }}"
                                else -> "转写：${settings.asrModel.ifBlank { "未配置" }}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_x),
                        contentDescription = "取消",
                        onClick = onDismiss,
                        containerColor = scheme.surfaceContainerHighest,
                        contentColor = scheme.onSurfaceVariant,
                        shadowElevation = 0.dp,
                    )
                }
            }
            if (listening) {
                // 唯一的主按钮：说完了。端点检测也会自动收尾，这个按钮是给「我现在就说完了」用的。
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { finish() }) { Text("说完了") }
            }
            if (failure != null) {
                // 失败态两条路：补配置，或者重来。系统识别只在用户主动点的时候才出现 —— 那个面板
                // 被否掉过两次，不能自动弹。
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!asrReady) {
                        Button(onClick = { onOpenSettings(); onDismiss() }) { Text("去设置") }
                    } else {
                        TextButton(onClick = { begin() }) { Text("重试") }
                    }
                    TextButton(onClick = useSystemInput) { Text("改用系统识别") }
                }
            }
        }
    }
}

/**
 * Which recognizer an attempt is using, and what the capsule prints for it.
 *
 * The order is the fallback order: the on-device model answers without any system panel; the
 * classic service with `EXTRA_PREFER_OFFLINE` still answers locally on a ROM that ships an offline
 * engine; and only a device with neither falls back to the online service — the one that puts the
 * ROM's own speech screen on top of the app.
 */
private enum class VoiceEngine(val label: String) {
    ON_DEVICE("设备端识别"),
    SYSTEM("系统识别服务（优先离线）"),
}

/**
 * The engine this device can actually offer — the availability test the capsule reports.
 *
 * `isOnDeviceRecognitionAvailable` is API 31+ and a device without the model installed answers
 * false, which is exactly the case that used to end up on the online panel. Everything else keeps
 * the classic service, where the offline preference is the best that can be asked for.
 */
private fun voiceEngine(context: Context): VoiceEngine {
    val sdk = Build.VERSION.SDK_INT
    val onDevice = sdk >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    val engine = if (onDevice) VoiceEngine.ON_DEVICE else VoiceEngine.SYSTEM
    // 诊断：用户报「还是走系统界面」时，这一行就能区分「设备端模型没装」与「ROM 拒绝了第三方」。
    voiceTrace(
        context,
        "engine sdk=$sdk isOnDeviceAvailable=$onDevice engine=$engine " +
            "preferOffline=${engine != VoiceEngine.ON_DEVICE}",
    )
    return engine
}

/**
 * 语音链的诊断日志：`filesDir/voice-trace.log`。
 *
 * 三条路径（设备端 / 经典服务 / 系统识别 Activity）在屏幕上长得一样，用户报「丑、还是走系统的」
 * 时无法只从截图分辨；这个文件把每一步写下来：选了哪条引擎、识别器回了什么错误码、有没有把系统
 * Activity 拉起来。只写日志，不改行为。
 */
private fun voiceTrace(context: Context, line: String) {
    val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    val app = context.applicationContext
    voiceTraceWorker.execute { appendBoundedLog(app, "voice-trace.log", "$stamp $line") }
}

/**
 * One line into a diagnostic file that cannot grow without limit.
 *
 * Both logs in this file are read by a human debugging a phone, and neither is worth a byte of
 * storage that outlives the session that wrote it: past 32KB the file is cut back to its last
 * 16KB, so the newest history is what survives. Runs on the caller's worker, never on a
 * composition or a gesture — the append is disk IO.
 */
private fun appendBoundedLog(app: Context, name: String, line: String) {
    runCatching {
        val file = File(app.filesDir, name)
        if (file.length() > 32 * 1024) {
            file.writeText(file.readText().takeLast(16 * 1024))
        }
        file.appendText("${SystemClock.uptimeMillis()} $line\n")
    }
}

private val voiceTraceWorker = Executors.newSingleThreadExecutor()

/**
 * 胶囊上的录音计时：`0:07`。分钟只在超过一分钟时出现，平时两位数字更安静。
 */
private fun formatDictationTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) {
        "0:${seconds.toString().padStart(2, '0')}"
    } else {
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}

/**
 * Five bars that answer the microphone: taller as the level rises, and still while it is quiet.
 *
 * The middle one is the tallest so the shape reads as a level rather than as a random row of
 * blocks — the four grey squares the user saw were a placeholder, this is the real input.
 */
@Composable
private fun VoiceBars(level: Float, active: Boolean, tint: Color) {
    val shape = listOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
    val shown = if (active) level else 0.12f
    Row(
        modifier = Modifier.height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        shape.forEach { weight ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((6f + 14f * shown * weight).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint.copy(alpha = if (active) 1f else 0.5f)),
            )
        }
    }
}
/** What the next question carries along: an image for the model, or a file on disk. */
data class Attachment(
    val base64: String,
    val label: String,
    val preview: ImageBitmap? = null,
)

private enum class AttachKind { IMAGE, FILE }

/**
 * The context window as a ring, left of the mic.
 *
 * A gauge rather than a number: 22dp of arc is all the room this row has, and the figures are
 * one tap away. The arc turns red at 80% of the window — where the engine starts folding the
 * conversation and where the next question may no longer fit — so the colour carries the
 * warning and the card carries the detail. With no window configured (0 = unlimited) the ring
 * is still drawn, as a neutral track with no arc, and the card says so and offers the way to
 * settings: the user asked for a gauge, and "this model has no window set" is exactly the kind
 * of thing a gauge should be able to tell them. No denominator is invented — a made-up
 * fraction would be decoration pretending to be data.
 *
 * The touch target is 26dp, not the 48dp the primary controls use: this row also carries the
 * model caption, the mic and the send button, and at 48dp the caption would be too narrow to
 * read on a 360dp-wide screen. The arc is 22dp of that, which is what leaves room for the
 * percentage in the middle: 22dp less the 2dp stroke either side is 18dp of clear space, and
 * three digits at 7sp measure about 11.7dp by 8dp.
 */
@Composable
private fun ContextRing(
    used: Int,
    /** True when [used] is this app's own estimate; the card says 「约」 when it is. */
    estimated: Boolean = false,
    window: Int,
    /** Everything this conversation has ever spent, prompt + completion. */
    sessionTokens: Int,
    /** The text-based floor for the cumulative row, when nothing in the session was priced. */
    sessionEstimate: Int = 0,
    /** That spend in the endpoint's own three parts. */
    sessionSplit: SessionSplit,
    /** How many questions that tally was built from, so 「累计」 cannot read as 「本轮」. */
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Int,
    sessionElapsedMs: Long,
    live: StreamStats?,
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val fraction = if (known) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val tone = if (fraction >= 0.8f) scheme.error else scheme.primary
    val track = scheme.surfaceVariant
    var open by remember { mutableStateOf(false) }
    // A focus-less popup does not receive BACK either: without this the press falls through to the
    // activity and finishes it. Same pair the composer's own cards use.
    BackHandler(enabled = open) { open = false }
    // The card opens upward, centred on the ring — and the ring sits at the row's right end, so a
    // 264dp card centred on it hung off the screen's right edge. Both the anchor and the card are
    // measured and the card is slid back inside the window, the same rule the model sheet uses.
    var ringAnchorCentreX by remember { mutableStateOf(0f) }
    var ringCardWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val ringScreenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val ringEdgePx = with(density) { POPUP_EDGE.toPx() }
    // The number in the middle, as a whole percent: the arc is a shape and the number is the
    // figure behind it. A window that was never set has no percentage to show (0 = unlimited,
    // not 0%), and 0% is a ring with no arc to read it against.
    val percent = if (known) (fraction * 100).roundToInt() else 0
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { open = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            val ringSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = stroke),
            )
            if (known && fraction > 0f) {
                drawArc(
                    color = tone,
                    // From twelve o'clock, clockwise: how a gauge is read.
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        // 9sp, two steps up from the 7sp the arc's clear space was first sized for: Bold at 7sp
        // was read as "could be a little bigger" twice, the second time with a figure. Three
        // digits at 9sp are about 15dp of the 18dp of clear space inside the arc, so the
        // longest reading still fits. Bold, not Medium: at this size the user read the Medium
        // weight as thin, and the ring is the only place these digits appear — the extra weight
        // costs nothing elsewhere.
        if (percent > 0) {
            Text(
                text = percent.toString(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
            )
        }
        // The card's anchor: a zero-height box at the ring's top edge, so the popup opens
        // upward from the ring instead of covering the row it belongs to.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { ringAnchorCentreX = it.boundsInWindow().center.x },
        ) {
            // A focusable window takes the keyboard away from the field below — the user asked why
            // tapping the ring closes the input method, and that is the whole of the answer: the
            // popup grabs focus, the IME goes, and the composer is then laid out a keyboard lower
            // while this card stays where it was. The card is focus-less instead, which means its
            // window can see neither a tap outside itself nor BACK; both are caught here, the way
            // the composer's own cards do it (see [MenuCard]'s `focusable`). The scrim is a window
            // of its own, composed before the card: windows stack in composition order, so a scrim
            // composed after it would cover it instead.
            if (open) {
                Popup(properties = PopupProperties(focusable = false)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { open = false },
                    )
                }
                Popup(
                    alignment = Alignment.BottomCenter,
                    offset = with(density) {
                        IntOffset(
                            popupNudgeCentred(
                                anchorCentre = ringAnchorCentreX,
                                width = ringCardWidth,
                                windowPx = ringScreenWidthPx,
                                edgePx = ringEdgePx,
                            ).roundToInt(),
                            -8.dp.roundToPx(),
                        )
                    },
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Box(
                        Modifier
                            .onSizeChanged { ringCardWidth = it.width }
                            // A focus-less window only receives what lands inside it, and drawing
                            // its own background is not the same as consuming a touch: without
                            // this the tap on the card would fall through to the transcript the
                            // card is floating over.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                    ) {
                        ContextRingCard(
                            used = used,
                            estimated = estimated,
                            window = window,
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
    }
}

/** The ring's detail: the numbers a 22dp arc cannot show. */
@Composable
private fun ContextRingCard(
    used: Int,
    /** True when [used] is this app's own estimate rather than a count the endpoint sent. */
    estimated: Boolean = false,
    window: Int,
    sessionTokens: Int,
    /** The text-based floor for the cumulative row, when nothing in the session was priced. */
    sessionEstimate: Int = 0,
    sessionSplit: SessionSplit,
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Int,
    sessionElapsedMs: Long,
    live: StreamStats?,
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known) used * 100 / window else 0
    Column(
        modifier = Modifier
            // 264dp, not 240: the value on the 「上下文占用」 line is a sentence of its own
            // (「12.3k / 200k（6%）」, or the 窗口未设置 form) and the old cap ellipsized it.
            // Still inside a 360dp screen's own margin, and still narrower than the
            // transcript's full width. Capped against the window, so a split-screen window
            // gets a card that fits it rather than one that hangs off its edge.
            .widthIn(max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp))
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (used <= 0) {
            // A brand-new conversation has nothing to report yet, and saying so is not the same
            // as saying there is no data: 「暂无数据」 on a chat the user has not typed into yet
            // reads as a fault. Everything else here has been estimated by now, so this branch
            // means an empty transcript and nothing else.
            Text(
                text = if (sessionTurns <= 0) "新会话 · 还没有用量" else "暂无数据",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            // Same intrinsic-width trick as the metric cards: the numbers share one right edge
            // without the card asking for the screen's width.
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 208.dp)) {
            RingDetail(
                "上下文占用",
                if (known) {
                    // 「约」 only when the number is this app's own estimate: the endpoint's own
                    // count is a fact and reads as one, and an estimate presented as a fact is
                    // the one thing this card must not do.
                    "${if (estimated) "约 " else ""}${shortTokens(used)} / ${shortTokens(window)}（$percent%）"
                } else {
                    // No invented denominator, and no editor either: where the window is
                    // filled in is the settings page, which also fills it from the endpoint's
                    // own context_window when the user has not said otherwise. The ring only
                    // reports.
                    "${if (estimated) "约 " else ""}${shortTokens(used)} · 窗口未设置 · 到设置里填"
                },
            )
            // The conversation's own hit rate, not the newest round's: the user asked for
            // 「总缓存命中率」 and a label of exactly 「缓存命中」. SessionStats.cachePercent is
            // the whole session's cached tokens over its whole prompt, so it is the total this
            // line is for; the newest round's own ratio was the wrong number under the wrong
            // name. It sits here, above the speed and the clock, because it is a reading of the
            // context the line above it is about.
            // Drawn even when it reads 0%: the line used to be skipped whenever the ratio came
            // out at zero, and the user — who had asked for this reading by name — read the
            // missing row as the reading having been dropped. A priced session with no cache
            // hits yet is a 0%, not a gap.
            RingDetail("缓存命中", "$sessionCachePercent%")
            // The rows below are in three groups, in this order: 上下文 (the two above) →
            // 本会话累计 → 速度与时间. They used to be stacked in the order the features landed,
            // which put 「输出速度」 above the total and the two per-round lines at the very bottom
            // — the user asked for the ordering to make sense. The grouping is carried by the
            // names (「累计…」) rather than by a divider or a heading: every row is already a
            // label/value pair, so a separator would add chrome and answer nothing.
            //
            // The per-round pair that used to sit here (「本轮输入」/「本轮输出」) is gone: the user
            // asked for it off, and it was the only consumer of the round's own two figures — the
            // arc's number and these totals come from other sources, so nothing else went with it.
            if (sessionTokens > 0) {
                // 本会话累计: the whole conversation's spend, not the context on screen. Every
                // round re-sends the context, so this is the number a bill would be built from —
                // and the same tally the usage page shows. Labelled 「累计」 rather than 「总量」
                // because it counts each round's re-send, which is why it can dwarf the
                // 「上下文占用」 line above it.
                RingDetail("累计用量", "${fullTokens(sessionTokens.toLong())} tok · $sessionTurns 轮")
                // The split under the total, by the names the user asked for: what the cache
                // could not answer, what it did, and what came back. All three are the whole
                // conversation's, like the total above them.
                RingDetail("未缓存输入", "${fullTokens(sessionSplit.uncached)} tok")
                RingDetail("缓存读取", "${fullTokens(sessionSplit.cached)} tok")
                RingDetail("输出", "${fullTokens(sessionSplit.output)} tok")
            } else if (sessionEstimate > 0) {
                // Nothing was ever priced — a gateway that sends no usage block, or rows written
                // before the counts existed — so the total is the same text-based floor the line
                // above uses, times the turns that re-sent it. 「约」 says what it is, and the
                // split stays out rather than being invented: the cache halves cannot be guessed
                // from a character count, and a made-up 「缓存读取」 is worse than a missing one.
                RingDetail(
                    "累计用量",
                    "约 ${fullTokens(sessionEstimate.toLong())} tok · $sessionTurns 轮",
                )
            }
            // 速度与时间 last: they are about the work, not about the window or the bill.
            if (sessionPerSecond > 0) {
                RingDetail(
                    "输出速度",
                    remember(sessionPerSecond) {
                        String.format(Locale.getDefault(), "%.1f tok/s", sessionPerSecond)
                    },
                )
            }
            if (sessionElapsedMs > 0) {
                RingDetail(
                    "总用时",
                    remember(sessionElapsedMs) { formatRunElapsed(sessionElapsedMs) },
                )
            }
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
private fun RingDetail(label: String, value: String) {
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
private fun shortTokens(tokens: Int): String = when {
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
private fun fullTokens(tokens: Long): String = String.format(Locale.US, "%,d", tokens)

/**
 * 量完删 — an instrument, not a feature (task-9).
 *
 * The jump the user reported (a long press moving the transcript) reproduces only on their own
 * device, after a cold start, and it is a race between a finger landing and a pin already in
 * flight; adb is out of bounds, so the three moments that decide it are written to
 * `filesDir/scroll-diag.log`: a conversation's opening pin, a finger landing, and a finger
 * lifting. `press:up:movedUp=…:scrolled=…:atEnd=…` is the line that settles it —
 * `movedUp=false scrolled=true` on an upward read means the gesture was a scroll and the follow
 * must stay released; `scrolled=false` while the list plainly moved means the flag missed its
 * scroll, and the `press:down` line's `first=`/`itemCount` say where it started.
 *
 * Bounded and off the main thread, exactly like [voiceTrace]: the file keeps its last 16KB once
 * it passes 32KB, and the append runs on the shared diagnostic worker, so the press path never
 * touches the disk. That bound is the fix for what this was — a log that grew without limit on
 * the one path that must stay cheap.
 *
 * Delete this, its three call sites (all marked 量完删) and the log once the user confirms the
 * jump is gone; the fix it was measuring shipped in 0.8.77.
 */
private fun scrollDiag(
    context: Context,
    event: String,
    state: LazyListState,
    following: Boolean,
    itemCount: Int,
    /**
     * The keyboard inset at the moment of the line, when the caller knows it. A long press that
     * ends with the IME gone is the signature of the selection path (the field lost focus); a
     * press that ends with the same inset as it started is not.
     */
    ime: Int = -1,
) {
    val app = context.applicationContext
    // `itemCount` is what the **caller's lambda captured**, and a long-lived effect
    // (`LaunchedEffect(listState)`, `pointerInput(Unit)`) captures it from the composition that
    // created the lambda — so the watch lines print the 0 of the first frame while the press lines,
    // created later, print the real count. `items=` is read live off the list itself and is the
    // number to trust; the two disagreeing is the stale capture, not a different list.
    val line = "$event" +
        " following=$following" +
        " itemCount=$itemCount" +
        " items=${state.layoutInfo.totalItemsCount}" +
        " first=${state.firstVisibleItemIndex}+${state.firstVisibleItemScrollOffset}" +
        " scrolling=${state.isScrollInProgress}" +
        " canBack=${state.canScrollBackward}" +
        " canForward=${state.canScrollForward}" +
        (if (ime >= 0) " ime=$ime" else "")
    voiceTraceWorker.execute { appendBoundedLog(app, "scroll-diag.log", line) }
}

/** The three parts of a conversation's spend, as the endpoint itself reports them. */
private data class SessionSplit(val uncached: Long, val cached: Long, val output: Long)

/**
 * Sums [SessionSplit] off the stored usage rows, the same rows [SessionStats] divides its hit
 * rate by, so the parts and the rate are two readings of one measurement.
 */
private fun sessionSplitOf(messages: List<StoredMessage>): SessionSplit = SessionSplit(
    uncached = messages.sumOf { it.promptTokens.toLong() - it.cachedTokens.toLong() }.coerceAtLeast(0L),
    cached = messages.sumOf { it.cachedTokens.toLong() },
    output = messages.sumOf { it.completionTokens.toLong() },
)

/**
 * The messages a compression record covers, or null when this transcript no longer holds
 * both ends of the span — a branch switch, or a conversation trimmed by hand.
 */
private fun checkpointSpan(
    messages: List<StoredMessage>,
    checkpoint: ContextCheckpoint,
): List<StoredMessage>? {
    val from = messages.indexOfFirst { it.id == checkpoint.fromMessageId }
    val to = messages.indexOfFirst { it.id == checkpoint.toMessageId }
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
private fun CheckpointCard(checkpoint: ContextCheckpoint, originals: List<StoredMessage>) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 整块按下反馈（见 blockPress）：检查点卡的表头也是铺满宽度的一行，展开后是整段
                // 摘要的入口；涟漪在这种宽度上同样是一大片圆。
                .blockPress(MaterialTheme.shapes.small, onClick = { expanded = !expanded })
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_minimize_2),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "已压缩上下文 · ${formatTime(checkpoint.at)} · " +
                    "${checkpoint.messages} 条消息 → 摘要",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) ImageVector.vectorResource(R.drawable.ic_chevron_up) else ImageVector.vectorResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    text = "压缩前约 ${shortTokens(checkpoint.promptTokens)} tokens · " +
                        "${checkpoint.messages} 条消息 · ${formatTime(checkpoint.at)}",
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "被压缩的原文（${originals.size} 条）",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = scheme.onSurfaceVariant,
                        )
                        originals.forEach { original ->
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
private fun SteerQueueRow(
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
            val label = steer.text.ifBlank { if (steer.imageBase64 != null) "（图片）" else steer.text }
            // The picture itself, at the size this 36dp bar can hold. Decoded here rather than
            // through ImageBlock: that one is built for a bubble (its own size plus the full-screen
            // preview dialog), so it would be a second layout path, not a reuse.
            var thumb by remember(steer.imageBase64) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(steer.imageBase64) {
                thumb = steer.imageBase64?.let { decodeAttachment(it, target = THUMB_PX)?.asImageBitmap() }
            }
            // Tapping the thumbnail opens the picture at full size. The decode happens on the tap,
            // not in the row: the 24dp copy above is downsampled to [THUMB_PX] and blowing it up
            // would show exactly the blur the user is trying to avoid, so the base64 is decoded
            // again — on IO — at a size worth looking at.
            val rowScope = rememberCoroutineScope()
            val rowContext = LocalContext.current
            var fullPicture by remember(steer.imageBase64) { mutableStateOf<ImageBitmap?>(null) }
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
                            val base64 = steer.imageBase64 ?: return@clickable
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
                    keepKeyboard = true,
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
private fun TodoQueueRow(
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
private fun RunReasonLine(reason: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(14.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
            modifier = Modifier.weight(1f),
        )
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
private fun FailureReceiptRow(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.errorContainer.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(14.dp).padding(top = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 完整任务清单：输入框上方那一行点开就是它。
 *
 * 那一行只放得下一条 —— 进行中的那条 + 完成数/总数（用户：「不要堆多行，就一行就行了」），
 * 清单本身得有地方看，所以点它开半屏表（站内既有的范式，见 AssistantSheet）。只读：改状态是
 * 模型自己的事，这里给的是「这个任务一共要做哪些、做到哪了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoSheet(todos: List<TodoItem>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = todos.count { it.status == TODO_COMPLETED }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("任务清单", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "已完成 $done/${todos.size}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(todos) { item -> TodoSheetRow(item) }
            }
        }
    }
}

/** 清单里的一行：状态标记 + 事项；完成的划掉一点、进行中的用品牌色。 */
@Composable
private fun TodoSheetRow(item: TodoItem) {
    val scheme = MaterialTheme.colorScheme
    val done = item.status == TODO_COMPLETED
    val active = item.status == TODO_IN_PROGRESS
    val ink = when {
        active -> scheme.primary
        done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(
                when {
                    done -> R.drawable.ic_circle_check
                    // 没有专门的「进行中」图标：箭头朝右＝正在往前走，比再画一个圆点更不含糊。
                    active -> R.drawable.ic_chevron_right
                    else -> R.drawable.ic_list_checks
                },
            ),
            contentDescription = null,
            tint = when {
                active -> scheme.primary
                done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                else -> scheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.subject,
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The board rows worth drawing: the items still open, in the order the model wrote them, and —
 * once everything is done — the last few that finished.
 *
 * Finished items leave the row as they finish, which is what "创建、完成、消失" describes: the
 * plan on screen is what is *left*, and the tally is what says how far along the whole board is.
 * Showing the whole list instead would push the one item being worked on off the bottom of a
 * three-row strip as soon as a plan grew past it.
 */
internal fun todoRows(todos: List<TodoItem>, limit: Int = TODO_ROWS_SHOWN): List<TodoItem> {
    if (limit <= 0) return emptyList()
    val open = todos.filterNot { it.status == TODO_COMPLETED }
    return if (open.isEmpty()) todos.takeLast(limit) else open.take(limit)
}

/**
 * One icon action of a queued row.
 *
 * The touch target is the 40dp node the ripple and the click live on, with the glyph drawn
 * inside it: a 16dp icon with a 16dp hit area is a control the user misses half the time.
 * [label] is the accessibility name and the thing to grep for.
 */
@Composable
private fun QueueAction(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The chip above the pill: a thumbnail, the name, and a way out of it. */
@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The same inset the queue rows and the board above use: the strip and the rows under it are
    // one column of things belonging to the field, and 10dp against their 12dp put the picture two
    // pixels left of the bars below it — the misalignment the user pointed at.
    Row(modifier = Modifier.padding(start = STEER_QUEUE_INSET)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val preview = attachment.preview
                var full by remember { mutableStateOf(false) }
                // The picture and its name are one tap target; 移除 stays its own button beside
                // them. Making the whole chip clickable would put "preview" and "delete" on the
                // same 26dp of glass, which is how a user deletes what they meant to look at.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (preview != null) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { full = true },
                            )
                    } else {
                        Modifier
                    },
                ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "查看图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
                }
                // The attachment's own bitmap, not a scaled-down copy: this is the picture the user
                // picked, at the size it was stored, so opening it shows what will be sent.
                if (full && preview != null) {
                    ImagePreviewDialog(
                        bitmap = preview,
                        contentDescription = attachment.label,
                        keepKeyboard = true,
                    ) { full = false }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_x),
                        contentDescription = "移除",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * What the ring draws, and whether the number is the endpoint's own or this app's estimate.
 *
 * Four sources, in one order, and the order is the whole point:
 *
 * 1. [liveReported] — the count the endpoint itself sent for the round in flight. The only
 *    honest measure of 「上下文用了多少」 (see `StreamStats.lastPromptTokens`).
 * 2. [newestStored] — the newest round in the transcript that carries a count. This is what the
 *    ring reads on a conversation that was just opened, and between the start of a round and
 *    the endpoint's usage block at its end.
 * 3. [liveEstimate] — the run's own character-based estimate of the wire, used while a round is
 *    going and before its usage block lands.
 * 4. [transcriptEstimate] — the transcript's own size, for a conversation that is *not* running
 *    and whose stored rounds carry no counts at all: a gateway that ignores
 *    `stream_options.include_usage` (see LlmClient's own note), or rows written before the counts
 *    existed. Without it the card was empty in exactly that case — every line zero, 「暂无数据」
 *    on a conversation with plenty of text in it — which is the report this answers.
 *
 * The last two are estimates, so [estimated] is set and the card writes 「约」: a proportion is not
 * a price, but a proportion that says 6% beats a blank.
 */
internal data class RingReading(val tokens: Int, val estimated: Boolean)

/**
 * The glyph for an approval mode, from the drawables the app already ships.
 *
 * A tick when tools run on their own, a person-with-shield while every call is put to the user,
 * an alert shield when they are refused. The icon is the state, which is what makes the row's
 * button readable without opening it.
 */
private fun approvalIcon(mode: ApprovalMode): Int = when (mode) {
    ApprovalMode.AUTO -> R.drawable.ic_circle_check
    ApprovalMode.ASK -> R.drawable.ic_shield_user
    ApprovalMode.DENY -> R.drawable.ic_shield_alert
}

/** The reading for the four sources above; 0 means there is nothing to draw at all. */
internal fun ringReading(
    liveReported: Int,
    newestStored: Int,
    liveEstimate: Int,
    transcriptEstimate: Int = 0,
): RingReading = when {
    liveReported > 0 -> RingReading(liveReported, estimated = false)
    newestStored > 0 -> RingReading(newestStored, estimated = false)
    liveEstimate > 0 -> RingReading(liveEstimate, estimated = true)
    transcriptEstimate > 0 -> RingReading(transcriptEstimate, estimated = true)
    else -> RingReading(0, estimated = false)
}

/** True while the last item is on screen — the state that lets a reply auto-scroll. */
private fun LazyListState.atEnd(slack: Int = 24): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + slack
}

/**
 * True only at the very end of the transcript: there is nothing left to scroll toward.
 *
 * Deliberately **not** [atEnd] with a small slack, which is the shape that produced 「往上滑一点点，
 * 又追底」. The slack is pixels, so a drag of a few pixels — just past the touch slop, under the
 * 12dp travel threshold — left the list inside the tolerance: the watcher read that as "released at
 * the end again" and re-armed the follow, and the next streamed frame pulled the transcript to the
 * bottom. The reader had moved the list; they had just not moved it far. `canScrollForward` is the
 * list's own answer to "is anything below", so it cannot disagree with the real position the way an
 * arithmetic tolerance can.
 */
private fun LazyListState.atVeryEnd(): Boolean = !canScrollForward

/**
 * Whether a gesture that has just ended may hand the follow back to the transcript.
 *
 * Only a tap does, and a tap is all three of these: it was short, it never dragged upward, and the
 * list never moved. The last one is the one that was missing. Keyed on "did not drag up" alone, a
 * drag *downward* — the gesture that reads older messages, and the one a flick is made of — counted
 * as a tap, so the follow was handed back the instant the finger lifted and the next publish hauled
 * the transcript to its end again: the position chasing the bottom, on every scroll.
 *
 * `atEnd` is the second half of the same idea: the follow may only be re-armed while the list is
 * still where the follow wants it, so a tap that ends anywhere else cannot restore it either.
 *
 * [holdMs] is the caller's own long-press timeout, and the caller passes the platform's rather than
 * this file's constant — see the constant's own note for the window that made a selection long
 * press look like a tap.
 *
 * **What the caller owes this function.** [scrolled] has to mean "the transcript moved", and it
 * cannot be derived from `isScrollInProgress` sampling alone: that flag is read on pointer events,
 * so a short drag can end before it is ever seen, and it is suppressed entirely while our own pin
 * runs. The caller therefore passes the **visible position at press-down and at release**
 * ([restoresFollowAfterPress] does the comparison) — a difference of one pixel is a moved list. And
 * [atEnd] has to mean the **absolute** end, not "within a tolerance of it": a reader a few pixels
 * off the end is where the follow must not return to, which is the whole shape of 「往上滑一点点，
 * 又追底」.
 */
internal fun restoresFollow(
    movedUp: Boolean,
    scrolled: Boolean,
    atEnd: Boolean,
    heldMs: Long,
    holdMs: Long = HOLD_TO_SELECT_MS,
): Boolean = !movedUp && !scrolled && atEnd && heldMs < holdMs

/**
 * The release decision as the list actually sees it, which is the half [restoresFollow] cannot
 * answer on its own.
 *
 * Two inputs are computed here rather than at the call site, because both were wrong in the same
 * way — they asked "did the finger travel?" instead of "did the transcript move?":
 *
 *  - **the position comparison**: [downIndex]/[downOffset] are the first visible item at press-down
 *    and [nowIndex]/[nowOffset] at release. Any difference at all means the list moved, however
 *    small the gesture was and however briefly it lasted — and a moved list must never have the
 *    follow handed back, or the next publish hauls the reader back to the bottom. This is the
 *    signal the scroll-progress sampling misses: a short drag can end before the flag is ever read,
 *    and a drag that only just passed the touch slop moves the list by a few pixels.
 *  - **[atVeryEnd]**: the caller passes whether the list is at its absolute end, not "within a
 *    tolerance of it". A reader who scrolled up by a few pixels is not at the end, and must not be
 *    treated as one.
 *
 * A tap that moved nothing and ended at the absolute end still restores: tapping a card to open it
 * is not an interaction with the position.
 */
internal fun restoresFollowAfterPress(
    movedUp: Boolean,
    scrolledDuringPress: Boolean,
    downIndex: Int,
    downOffset: Int,
    nowIndex: Int,
    nowOffset: Int,
    atVeryEnd: Boolean,
    heldMs: Long,
    holdMs: Long = HOLD_TO_SELECT_MS,
): Boolean = restoresFollow(
    movedUp = movedUp,
    scrolled = scrolledDuringPress || downIndex != nowIndex || downOffset != nowOffset,
    atEnd = atVeryEnd,
    heldMs = heldMs,
    holdMs = holdMs,
)

/**
 * Whether a press that the list moved **on its own** has to be undone.
 *
 * The case this exists for, from a real log: at the very end (`canForward=false`), a finger goes
 * down and does not move; ~330ms later the list is 636px further up with `isScrollInProgress=true`,
 * and at release `movedUp=false` — the finger never travelled. That is not a gesture. It is the text
 * selection machinery (`SelectionContainer`) bringing the selected line into view, and it is the
 * 「长按滚动位置变」 the user keeps reporting: a stationary long press is a request to select, not a
 * request to move.
 *
 * [fingerDragged] is whether the finger ever travelled past the **touch slop** — the threshold at
 * which Compose's scrollable starts moving the list from a finger at all. That is what makes this
 * rule decidable: below the slop the finger *cannot* have scrolled the list, so a list that moved
 * anyway was moved by something else. (An earlier version of this asked "did the list move during
 * the press" as well, which is self-contradictory: the framework's own scroll sets that flag, so the
 * branch could never fire — the unit test for the real log caught it.)
 *
 * [listMoved] is the position pair at press-down against release.
 */
internal fun restoresPositionAfterLongPress(
    fingerDragged: Boolean,
    listMoved: Boolean,
    heldMs: Long,
    holdMs: Long = HOLD_TO_SELECT_MS,
): Boolean = !fingerDragged && listMoved && heldMs >= holdMs

/** Far more than any transcript: the list clamps this to its own end. */
private const val FOLLOW_SCROLL_PX = 200_000f

/**
 * How long a press has to last before it counts as the user reaching into the content.
 *
 * The platform's own long-press threshold is 500ms and text selection starts there; a shorter
 * window here would drop the follow scroll on an ordinary tap, which is not an interaction with
 * the position at all.
 *
 * This is the **fallback**, and the value used to be 450 — *below* the platform's own 500, which is
 * the window this constant's own reasoning warned about: a press of 450–500ms is a selection long
 * press to the system (the IME starts collapsing under it) while `restoresFollow` still called it a
 * tap and handed the follow back, after which the keyboard-inset follow and the composer's pin
 * moved the list — the long-press jump, still there after every other fix. The caller now passes
 * the platform's live value (`LocalViewConfiguration.longPressTimeoutMillis`), which is 500 unless
 * the device or an accessibility setting says otherwise; this constant only covers a caller that
 * has none.
 */
private const val HOLD_TO_SELECT_MS = 500L

/** How far an upward drag has to travel to release the follow, in dp (converted per density). */
private val RELEASE_DRAG_DP = 12.dp

/**
 * The air the composer leaves under its pill, in dp.
 *
 * Named because two places have to agree on it: the composer's own bottom padding, and the room
 * the transcript leaves for the stack (the caller's stackFootprint). They were a literal 12.dp
 * in one of them and a measured number in the other, which is how the two drifted a frame apart.
 */
private val COMPOSER_BOTTOM_PAD = 12.dp

/** Largest opposite-direction inset change treated as the platform animation's rebound. */
private const val REBOUND_CLAMP_PX = 24

/**
 * The size of a viewport change that is a jump rather than a frame of an animation.
 *
 * The platform animates the IME inset frame by frame where it can — deltas of a few pixels, and
 * the list follows them one for one, which is what makes the motion match the keyboard. A ROM
 * that hands the whole inset over at once produces one delta of several hundred pixels instead,
 * and that is the one worth animating.
 */
private const val KEYBOARD_JUMP_PX = 40f

/** Matches the platform's own keyboard animation, so the two read as one motion. */
private const val KEYBOARD_ANIM_MS = 220

/** How many queued lines are drawn before the rest collapse into one count. */
private const val STEER_ROWS_SHOWN = 3

/**
 * The draft's `attachmentUri` for a picture taken back out of the queue by 「修改」.
 *
 * A marker, not a uri: the picture lives in the view model's attachment cache and nowhere else, so
 * this exists only to make the composer re-read that cache (its `remember` is keyed on the draft's
 * uri and name). After a restart the cache is empty and the chip is simply absent, which is the
 * honest outcome — the alternative is a draft pointing at a uri that cannot be read.
 */
private const val QUEUED_ATTACHMENT_URI = "queued://steer-image"

/** What the restored picture is called in the composer's chip. */
private const val QUEUED_IMAGE_LABEL = "图片"

/** Target width of a queue row's thumbnail, in pixels: the row draws it at 24dp. */
private const val THUMB_PX = 96

/**
 * Target width for a picture opened from a queue row, in pixels.
 *
 * Larger than any phone's screen is tall, so nothing is lost on the way in, while still leaving the
 * decoder a bound: a 12-megapixel photo decoded at full size is ~48 MB and an OOM on a cheap device.
 */
private const val FULL_PREVIEW_PX = 2160

/**
 * How many board rows are drawn above the queue — the same three as the queue, for the same
 * reason: this stack sits over the field the user is typing into, and a long plan must not
 * push that field off the screen. The tally covers the items that do not fit.
 */
private const val TODO_ROWS_SHOWN = 3

private const val ATTACHMENT_MAX_WIDTH = 1280

private fun readAttachment(context: Context, uri: Uri, kind: AttachKind): Attachment? = when (kind) {
    AttachKind.IMAGE -> decodeImage(context, uri)
    AttachKind.FILE -> importFile(context, uri)
}

private fun decodeImage(context: Context, uri: Uri): Attachment? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var scale = 1
    while (bounds.outWidth / scale > ATTACHMENT_MAX_WIDTH || bounds.outHeight / scale > ATTACHMENT_MAX_WIDTH) {
        scale *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = scale }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null
    bitmapAttachment(bitmap, displayName(context, uri) ?: "图片")
}.getOrNull()

/** Camera and gallery both land here: one JPEG, small enough to send every turn. */
private fun bitmapAttachment(bitmap: Bitmap, label: String = "照片"): Attachment {
    val scaled = if (bitmap.width > ATTACHMENT_MAX_WIDTH) {
        val ratio = ATTACHMENT_MAX_WIDTH.toFloat() / bitmap.width
        Bitmap.createScaledBitmap(
            bitmap,
            ATTACHMENT_MAX_WIDTH,
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val bytes = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
    return Attachment(
        base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
        label = label,
        preview = scaled.asImageBitmap(),
    )
}

/**
 * Decodes stored base64 back into a bitmap, off the main thread.
 *
 * Used by the queue row's 24dp thumbnail and by 「修改」, which puts the picture back into the
 * composer: both hold bytes, not a uri, so neither can go through [readAttachment]. Downsampled to
 * about [target] pixels wide, because the callers draw it small and the full JPEG would cost
 * megabytes per row. Null when the bytes are not an image, which every caller treats as "no
 * picture" rather than as an error worth showing.
 */
private suspend fun decodeAttachment(base64: String, target: Int = 512): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

/**
 * A picked file is copied into the app's own folder, because the agent reads absolute
 * paths and a `content://` handle means nothing to it. The path goes into the question.
 */
private fun importFile(context: Context, uri: Uri): Attachment? = runCatching {
    val name = displayName(context, uri) ?: "文件"
    val inbox = File(context.getExternalFilesDir(null) ?: context.filesDir, "inbox").apply { mkdirs() }
    val target = File(inbox, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    Attachment(base64 = "", label = target.absolutePath)
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

/**
 * Rewrites a message. A question can also be re-asked from the edited text, which
 * throws away everything the model said afterwards — those answers were written for
 * a prompt that no longer exists.
 */
@Composable
private fun EditDialog(
    message: StoredMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResend: ((String) -> Unit)?,
) {
    var draft by remember(message.id) { mutableStateOf(message.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (message.role == Roles.USER) "修改这条消息" else "修改这条回复") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 300.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                if (message.role == Roles.USER) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "「重新发送」会丢掉这条之后的所有内容，让模型重新回答。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (onResend != null) {
                TextButton(
                    onClick = { onResend(draft) },
                    enabled = draft.isNotBlank(),
                ) { Text("重新发送") }
            } else {
                TextButton(onClick = { onSave(draft) }, enabled = draft.isNotBlank()) { Text("保存") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onResend != null) {
                    TextButton(onClick = { onSave(draft) }, enabled = draft.isNotBlank()) {
                        Text("只保存")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * The one full-screen picture viewer: a bubble's screenshot, the attachment chip above the field and
 * a queued line's thumbnail all open this.
 *
 * Shared rather than copied because it is not merely a Dialog: the black scrim, the pinch-and-pan
 * transform and tap-anywhere-to-close are the behaviour the user already knows from the transcript,
 * and a second implementation would drift from it. A `Dialog` and not a focusable overlay, so it
 * cannot take the keyboard away from the field — the same rule the composer's own cards follow.
 */
@Composable
private fun ImagePreviewDialog(
    bitmap: ImageBitmap,
    contentDescription: String,
    /**
     * Draw it without taking window focus, for the two callers that sit right above the field the
     * user is typing in — the attachment chip and the queued rows. A focusable window takes the
     * keyboard away, and the composer would jump under the user's hands for a tap meant to look at a
     * picture. The transcript's own screenshots keep the ordinary dialog.
     */
    keepKeyboard: Boolean = false,
    onDismiss: () -> Unit,
) {
    // Reading a shot needs the whole screen and a pinch.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformable = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    val viewer: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformable)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
    if (keepKeyboard) {
        // Focus-less, like the composer's own cards: it cannot see BACK, which is why the scrim
        // above closes on a tap anywhere — the same trade the model sheet makes.
        Popup(properties = PopupProperties(focusable = false)) { viewer() }
    } else {
        Dialog(onDismissRequest = onDismiss) { viewer() }
    }
}

@Composable
private fun ImageBlock(base64: String) {
    // Decoding a 1080p screenshot costs tens of milliseconds; doing it inside
    // composition stalls the frame and can ANR while the list scrolls.
    var bitmap by remember(base64) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(base64) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { decoded ->
        Spacer(Modifier.height(6.dp))
        Image(
            bitmap = decoded,
            contentDescription = "工具截图",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = { full = true },
                ),
        )
        if (full) ImagePreviewDialog(decoded, contentDescription = "工具截图") { full = false }
    }
}

private const val TIME_LABEL_GAP_MS = 10 * 60 * 1000L

private fun needsTimeLabel(previous: StoredMessage?, current: StoredMessage): Boolean {
    if (previous == null) return true
    return current.timestamp - previous.timestamp > TIME_LABEL_GAP_MS
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val pattern = if (now - timestamp < 24 * 60 * 60 * 1000L) "HH:mm" else "M月d日 HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

private fun formatDuration(millis: Long): String =
    if (millis < 1000) "${millis}ms" else String.format(Locale.getDefault(), "%.1fs", millis / 1000.0)

/** 12 秒 / 1 分 03 秒 — how the run caption counts, never more than two units. */
private fun formatRunElapsed(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) {
        "$seconds 秒"
    } else {
        "${seconds / 60} 分 ${(seconds % 60).toString().padStart(2, '0')} 秒"
    }
}
