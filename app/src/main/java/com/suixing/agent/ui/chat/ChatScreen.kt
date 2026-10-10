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
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.forAssistant
import com.suixing.agent.data.groupModels
import com.suixing.agent.data.modelOffers
import com.suixing.agent.data.choiceFor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.DrawerButton
import com.suixing.agent.ui.components.FeatherIcon
import com.suixing.agent.ui.components.FlatIconButton
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MarkdownText
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.components.markdownBody
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val attachment = remember(draft.attachmentUri, draft.attachmentName) {
        viewModel.attachmentFor(state.conversationId)
    }
    fun setDraft(
        text: String = input,
        uri: String? = draft.attachmentUri,
        name: String? = draft.attachmentName,
    ) {
        viewModel.onDraftChange(state.conversationId, Draft(text, uri, name))
    }
    var editing by remember { mutableStateOf<StoredMessage?>(null) }
    // The composer stack's own measured height (navigation bar inset included: the
    // modifier sits inside the measured node), so the list's bottom padding is what
    // the stack actually occupies instead of a guess that leaves a band in both the
    // keyboard-up and keyboard-down states.
    var composerH by remember { mutableStateOf(0.dp) }
    // Where the composer's top edge sits in the window, and where the transcript's bottom edge
    // does: their difference is the room the list has to leave for the floating stack, and it is
    // the only form of that number that survives the keyboard changing what is below it.
    var composerTopY by remember { mutableStateOf(0f) }
    var transcriptBottomY by remember { mutableStateOf(0f) }
    // The room the floating stack takes out of the transcript: from its top edge to the list's
    // bottom edge. Falls back to the composer's own height until both edges have been measured,
    // which is the same number the two agreed on before the keyboard was ever opened.
    val stackFootprint = with(LocalDensity.current) {
        val measured = (transcriptBottomY - composerTopY).coerceAtLeast(0f)
        if (measured > 0f) measured.toDp() else composerH
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

    // Tool-only assistant turns carry no text of their own, so their tool calls are
    // folded into the bubble above them instead of standing alone in the transcript.
    // Only the branch the user picked is drawn; the other answers stay on disk.
    // liveRunId is a key AND a filter: the rounds of the run still streaming are
    // already in `messages` (every event is appended there as it lands), so without
    // the filter groupRows drew them as their own settled bubble beside the live one
    // — the double bubble. The filter is what makes the settle frame atomic: the
    // rounds leave the stored list in the same state update that empties liveParts.
    val visible = remember(
        state.messages,
        state.activeRuns,
        state.runningHere,
        state.liveRunId,
    ) {
        groupRows(state.messages, state.activeRuns, state.runningHere)
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
    // What the ring left of the mic draws: the newest round's own count, or the live run's
    // estimate while it streams — the same pair the summary card reads, so the two agree.
    // The cache count is the newest round's too: context is cumulative, so an older round's
    // cache figure says nothing about the window as it stands now.
    val newestUsage = visible.lastOrNull { it.usage != null }?.usage
    val contextUsed = state.stats?.lastPromptTokens?.takeIf { it > 0 }
        ?: newestUsage?.prompt
        ?: 0
    // The newest round's hit rate, in the drawer's own convention: cached over prompt, to two
    // decimals, and nothing when the endpoint reported no prompt to divide by. A whole
    // percentage hid the difference between 96.4% and 96.9%, which is exactly the range a
    // long conversation sits in.
    val cachePercent = newestUsage?.takeIf { it.prompt > 0 }?.let { it.cached * 100.0 / it.prompt } ?: 0.0
    // What the conversation has spent in total, summed once in the view model rather than
    // walked here: a long transcript is thousands of messages, and the ring's card is
    // redrawn far more often than the number changes.
    val sessionTokens = viewModel.sessionStats.collectAsState(initial = null).value?.tokens ?: 0
    val sessionTurns = viewModel.sessionStats.collectAsState(initial = null).value?.turns ?: 0
    // What each run actually took, summed over its own messages. A message's own `elapsedMs`
    // is one round's call — the model thinking, then calling a tool, then thinking again are
    // several messages in one run — while the status line the user watched counted the whole
    // run. Showing the round's number under the card is what made the two disagree.
    val runElapsed = remember(state.messages) {
        state.messages.asSequence()
            .filter { !it.runId.isNullOrEmpty() && it.elapsedMs > 0 }
            .groupBy { it.runId }
            .mapValues { (_, rows) -> rows.sumOf { it.elapsedMs } }
    }
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

    // The live card and, under it, its progress caption: both are items of the list, so
    // the count everything else scrolls by includes them.
    val itemCount = visible.size + if (live) 2 else 0

    // The user's own messages are the landmarks of a conversation, so the status line can
    // say "turn 2 of 5" and jump between them without the engine knowing anything about it.
    // Steered lines ride inside the turn they steer, so counting them would draw one
    // landmark per interjection — a question plus two steers is still one turn.
    val turnIndices = remember(visible) {
        visible.indices.filter {
            visible[it].message.role == Roles.USER && !visible[it].message.steer
        }
    }
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentTurn = turnIndices.indexOfLast { it <= firstVisible }.coerceAtLeast(0) + 1

    // Auto-scrolling is the scroll position's decision, not the transcript's: while the
    // list sits at the end a growing reply keeps it there, and the moment the user drags
    // up to read something the view stops being yanked back down.
    var following by remember { mutableStateOf(true) }
    // Set while the list is being scrolled by us. Without it the follow scroll itself trips
    // the "user scrolled away" branch below, following goes false, and the run stops
    // following its own output — which is exactly what it used to do.
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        // The hand wins the moment it moves. `canScrollBackward` is false while the list sits
        // at its end and true the instant a drag lifts it off, so following is dropped on the
        // first pixel of an upward swipe — a distance threshold (the old 48px) meant a slow
        // stream kept the list pinned while the user dragged, and they had to fight it.
        snapshotFlow { listState.isScrollInProgress to listState.canScrollBackward }
            .collect { (scrolling, canScrollBack) ->
                if (scrolling && !programmatic && canScrollBack) {
                    following = false
                } else if (!scrolling && listState.atEnd(slack = 8)) {
                    // Released at the end again — or the user tapped 跳最新, which sets
                    // following itself: either way the run may follow its output again.
                    following = true
                }
            }
    }
    // A new conversation starts at its newest message, whoever was reading what before.
    // The list keeps one scroll state for the whole screen, so a switch would otherwise
    // land at the previous chat's offset. Not gated by autoFollow: that setting decides
    // what *new* messages do, while this is simply where a conversation opens.
    LaunchedEffect(state.conversationId) {
        following = true
        if (itemCount <= 0) return@LaunchedEffect
        programmatic = true
        try {
            listState.scrollToItem(itemCount - 1)
            listState.scrollBy(FOLLOW_SCROLL_PX)
        } finally {
            programmatic = false
        }
    }
    // Every event that can change the height of the last item is a trigger: the streamed
    // text, a tool row appearing or finishing, and the status line — not just the count.
    LaunchedEffect(
        itemCount,
        state.streaming.length,
        state.reasoning.length,
        state.liveParts,
        state.status,
        settings.autoFollow,
    ) {
        if (itemCount <= 0 || !following || !settings.autoFollow) return@LaunchedEffect
        // A drag in flight pauses the pin: content updates land between the frames of a
        // gesture, and pinning on one of them yanks the list back to the end under the user's
        // finger — which is what made "scroll up a little" not enough. The release effect
        // above has already dropped `following` by then; this is the belt to that pair of
        // braces for the frame or two before it runs.
        if (listState.isScrollInProgress && !programmatic) return@LaunchedEffect
        programmatic = true
        try {
            // Instant, not animated: the list is being held at its end while content grows, and
            // animating that both fought the platform's own keyboard animation (the card shot up
            // and then settled back) and made the keyboard-down case — which was already right —
            // feel wrong. The keyboard's own animation is the only motion this needs.
            listState.scrollToItem(itemCount - 1)
            listState.scrollBy(FOLLOW_SCROLL_PX)
            // A second pass after the new item has been measured. The first scroll runs in the
            // frame the item was added, when its height is still unknown, so the end it lands on
            // is short by exactly the height of what just appeared — which is how the status
            // line of a fresh question ended up under the composer.
            withFrameNanos { }
            listState.scrollToItem(itemCount - 1)
            listState.scrollBy(FOLLOW_SCROLL_PX)
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
    LaunchedEffect(viewportHeight) {
        if (itemCount <= 0 || !following || !settings.autoFollow) return@LaunchedEffect
        // One frame first: the new height is already known while the list is still laid out at
        // the old one, and scrolling against that lands on the old end.
        withFrameNanos { }
        programmatic = true
        try {
            listState.scrollToItem(itemCount - 1)
            listState.scrollBy(FOLLOW_SCROLL_PX)
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
                    listState.scrollToItem(itemCount - 1)
                    listState.scrollBy(FOLLOW_SCROLL_PX)
                } finally {
                    programmatic = false
                }
            }
        }
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
                        EmptyHero(onPick = { setDraft(text = it) })
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // The list's own bottom edge, for the padding above: it is the
                            // boundary the floating composer is measured against.
                            .onGloballyPositioned { coordinates ->
                                transcriptBottomY = coordinates.boundsInWindow().bottom
                            }
                            // A long press is the user reaching into the content — almost
                            // always to select text — and the follow scroll below would pull
                            // the end back under their finger: the position moved, and the
                            // selection went with it. A drag already wins that argument; this
                            // gives a press the same right. The events are only observed, at
                            // the initial pass, so selection and scrolling still receive them
                            // untouched — nothing here consumes anything.
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(PointerEventPass.Initial)
                                        if (down.changes.none { it.pressed }) continue
                                        val pressedAt = SystemClock.uptimeMillis()
                                        while (true) {
                                            val next = awaitPointerEvent(PointerEventPass.Initial)
                                            if (next.changes.none { it.pressed }) break
                                        }
                                        if (SystemClock.uptimeMillis() - pressedAt >= HOLD_TO_SELECT_MS) {
                                            following = false
                                        }
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
                                    showReasoning = settings.showReasoning,
                                    expandReasoning = settings.expandReasoning,
                                    expandTools = settings.expandTools,
                                    reasoningMaxHeight = settings.reasoningHeight.dp,
                                    bubbleMaxWidth = bubbleMaxWidth,
                                    contextWindow = contextWindow,
                                    runElapsedMs = runElapsed[row.message.runId] ?: 0L,
                                    reasoningExpansion = reasoningExpansion,
                                    onBranch = { turnId, runId -> viewModel.switchBranch(turnId, runId) },
                                    onRegenerate = { viewModel.regenerate() },
                                    onEdit = { editing = it },
                                )
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
                                            }
                                        },
                                        streaming = true,
                                        // The live bubble is the persona's reply too:
                                        // without these two it fell back to the brand name
                                        // and feather while every stored bubble showed the
                                        // persona.
                                        name = assistant?.label ?: "随行",
                                        face = assistant?.face ?: "",
                                        runningTool = state.runningTool,
                                        liveStats = state.stats,
                                        contextWindow = contextWindow,
                                        expandReasoning = settings.expandReasoning,
                                        expandTools = settings.expandTools,
                                        reasoningMaxHeight = settings.reasoningHeight.dp,
                                        bubbleMaxWidth = bubbleMaxWidth,
                                        reasoningExpansion = reasoningExpansion,
                                    )
                                    // 8dp under the card: the run's progress belongs to the
                                    // bubble it describes, and 3dp read as cramped against it.
                                    // 16dp above, none below: the card's own bottom padding and
                                    // the list's spacing already supply the air underneath, so an
                                    // equal padding here left the line sitting high between the
                                    // card above it and the gap below.
                                    if (state.runningHere) {
                                        RunStatusLine(
                                            text = liveStatusText,
                                            startedAt = runStartedAt[runKey] ?: 0L,
                                            timed = statsNote.isEmpty(),
                                            modifier = Modifier.padding(start = 4.dp, top = 16.dp),
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
                                    // 8dp above and below, on top of the list's own 16dp: the
                                    // caption read as cramped against the cards on both sides.
                                    modifier = Modifier.padding(
                                        start = 4.dp,
                                        top = 8.dp,
                                        bottom = 8.dp,
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
            // The error banner rides below the header's control row and above the fade
            // layer: in the column it sat under the status strip, and the fade ate its
            // text. status bar + 4dp lift + 56dp row + 8dp of air.
            state.error?.let { message ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = statusBarInset + 68.dp),
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
                turns = turnIndices.size,
                current = currentTurn,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                onJumpTo = { turn ->
                    // Choosing a turn is reading, not following: the stream must not drag
                    // the view back down while the user is somewhere else in the answer.
                    following = false
                    scope.launch { listState.animateScrollToItem(turnIndices[turn]) }
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
                    running = state.runningHere,
                    stats = state.stats,
                    settings = settings,
                    assistantId = state.assistantId,
                    contextWindow = contextWindow,
                    contextUsed = contextUsed,
                    cachePercent = cachePercent,
                    sessionTokens = sessionTokens,
                    sessionTurns = sessionTurns,
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
                    onSteer = { viewModel.steer(it) },
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
                    onOpenSettings = onOpenSettings,
                    jumpToLatest = { jumpToLatest() },
                    showJump = !atEnd,
                    onHeightChanged = { composerH = it },
                    onTopInWindow = { composerTopY = it },
                )
            }
            }
        }
        if (voiceOpen) {
            VoiceOverlay(
                onResult = { text -> setDraft(text = if (input.isBlank()) text else "$input $text") },
                onDismiss = { voiceOpen = false },
            )
        }
    }

    if (assistantsOpen) {
        AssistantSheet(
            assistants = settings.assistants,
            activeId = state.assistantId.ifBlank { settings.activeAssistantId },
            settings = settings,
            onUse = { viewModel.useAssistant(it) },
            onSetDefault = { viewModel.setDefaultAssistant(it) },
            onSave = { viewModel.saveAssistant(it) },
            onDelete = { viewModel.deleteAssistant(it) },
            onDismiss = { assistantsOpen = false },
        )
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
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_menu_soft),
            contentDescription = "打开侧边栏",
            onClick = { openDrawer?.invoke() },
            iconSize = 22.dp,
        )
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                ),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = title,
                    style = titleBarStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_compose),
            contentDescription = "新对话",
            onClick = onNew,
            iconSize = 22.dp,
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = model,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
            ),
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
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
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
            text = "正在打开…",
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
private fun EmptyHero(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own mark on its gradient disc: the launcher asset is pure white for
        // the home screen, and white on a light canvas would vanish.
        SuixingMark(size = 92.dp)
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
private data class ChatRow(
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
private data class ToolLine(
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

private sealed interface Part {
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
private data class Usage(
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
private data class Branch(
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
    else -> false
}

/**
 * Folds the transcript into bubbles.
 *
 * Every tool result is claimed by the assistant bubble above it, so a reply and the
 * tools it ran read as one block; a text-less assistant turn is skipped rather than
 * drawn as an empty bubble.
 */
private fun groupRows(
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
                    // A tool result with no bubble above it (a transcript that starts
                    // mid-turn) still gets drawn, just on its own.
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = listOf(Part.Tools(listOf(ToolLine.of(message)))),
                    )
                    previous = message
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
                if (index >= 0 && run.isNotEmpty() && run == openRun) {
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
                openIndex = -1
                openRun = null
            }

            else -> Unit
        }
    }
    return rows
}

@Composable
private fun MessageRow(
    row: ChatRow,
    assistantName: String,
    assistantFace: String = "",
    showReasoning: Boolean,
    expandReasoning: Boolean,
    expandTools: Boolean,
    reasoningMaxHeight: Dp,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    /** What this row's whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
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
            branch = row.branch,
            usage = row.usage,
            contextWindow = contextWindow,
            runElapsedMs = runElapsedMs,
            message = row.message,
            expandReasoning = expandReasoning,
            expandTools = expandTools,
            reasoningMaxHeight = reasoningMaxHeight,
            bubbleMaxWidth = bubbleMaxWidth,
            reasoningExpansion = reasoningExpansion,
            onBranch = onBranch,
            onRegenerate = onRegenerate,
            onEdit = onEdit,
        )

        // A tool result with no bubble above it still renders as one, card only.
        else -> AssistantRow(
            parts = listOf(Part.Tools(listOf(ToolLine.of(row.message)))),
            streaming = false,
            face = assistantFace,
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
            .size(28.dp)
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
) {
    val total = promptTokens + completionTokens
    if (elapsedMs <= 0 && total <= 0) return
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<MetricCard?>(null) }
    // What the card last showed: the popup stays composed through its exit animation, and an
    // empty card sliding away is worse than the one the user just closed.
    var shown by remember { mutableStateOf(MetricCard.TIME) }
    if (open != null) shown = open!!
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
            MenuCard(
                visible = open != null,
                onDismiss = { open = null },
                alignment = Alignment.BottomStart,
                offset = with(LocalDensity.current) { IntOffset(0, -8.dp.roundToPx()) },
            ) {
                // Intrinsic width: every row is as wide as the widest one, which is what puts the
                // numbers on one column while the popup still hugs its content.
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 208.dp)) {
                when (shown) {
                    MetricCard.TIME -> {
                        if (elapsedMs > 0) RingDetail("总用时", elapsed)
                        if (firstTokenMs > 0) {
                            RingDetail(
                                "首 token",
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
                                "速度",
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
                        if (total > 0) RingDetail("本轮用量", "${shortTokens(total)} tok")
                        if (promptTokens > 0) {
                            RingDetail("入", "${shortTokens(promptTokens)} tok")
                            RingDetail("缓存 token", "${shortTokens(cachedTokens)} tok")
                            RingDetail(
                                "未缓存",
                                "${shortTokens((promptTokens - cachedTokens).coerceAtLeast(0))} tok",
                            )
                            if (cachedTokens > 0) {
                                RingDetail(
                                    "缓存命中率",
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
    message: StoredMessage? = null,
    expandReasoning: Boolean = false,
    expandTools: Boolean = false,
    reasoningMaxHeight: Dp = 180.dp,
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
                // The persona's own face first: a persona the user gave an emoji keeps
                // it, and only a blank face falls back to the brand feather.
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (face.isNotBlank()) {
                            Text(text = face, fontSize = 14.sp, maxLines = 1)
                        } else {
                            Icon(
                                imageVector = FeatherIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
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
                                // Thinking is the reason to run a thinking model at all, so a
                                // live block starts open. A stored one does not, whatever the
                                // 思考过程默认展开 setting says: that setting is about watching
                                // thoughts arrive, and honouring it here is what kept re-opening
                                // blocks the user had already watched fold. Anything the user
                                // opened by hand is in the expansion table and still wins.
                                startExpanded = streaming && expandReasoning,
                                maxHeight = reasoningMaxHeight,
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
                            if (streaming) {
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
        shape = RoundedCornerShape(16.dp),
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
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (expanded) 0.dp else 16.dp,
                            bottomEnd = if (expanded) 0.dp else 16.dp,
                        ),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
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
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = MaterialTheme.typography.labelLarge.fontSize * (13f / 14f),
                    ),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false: the title yields width to whatever sits after it
                    // rather than claiming all of it. With a plain weight(1f) the chip
                    // beside it was squeezed to zero width and stacked its own characters
                    // vertically, which is what made one header 200dp tall.
                    modifier = Modifier.weight(1f, fill = false),
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
                        modifier = Modifier.weight(1f, fill = false),
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
    startExpanded: Boolean = false,
    /**
     * Inside an expanded summary card the block is a row of the card's grid, not a
     * card of its own: no surface, no second header height — just the icon slot, the
     * title and the chain, lined up with the tool rows beside it.
     */
    nested: Boolean = false,
    /** How tall the chain's inner scroll may grow; the appearance page owns it. */
    maxHeight: Dp = 180.dp,
) {
    // The table decides, and it outlives this composable: a think opens while it streams,
    // folds when it settles, and a header the user tapped is theirs from then on — the
    // automatic fold never overwrites a manual choice, and a manual fold is never undone.
    val expanded = expansion.stored(blockId) ?: (streaming || startExpanded)
    var wasStreaming by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        if (wasStreaming && !streaming && expansion.stored(blockId) == null) {
            expansion.set(blockId, false)
        }
        wasStreaming = streaming
    }
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
            val cap = 200
            var uncapped by remember { mutableStateOf(false) }
            val shown = if (uncapped || lines.size <= cap) lines else lines.subList(0, cap)
            val inner = rememberLazyListState()
            LaunchedEffect(shown.size, expanded) {
                if (expanded && streaming && shown.isNotEmpty()) {
                    inner.animateScrollToItem(shown.lastIndex)
                }
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
                items(shown.size) { index ->
                    val line = shown[index]
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
                    if (index == shown.lastIndex && streaming) BlinkingCursor()
                }
                if (!uncapped && lines.size > cap) {
                    item {
                        TextButton(onClick = { uncapped = true }) {
                            Text("展开全部 ${lines.size} 行")
                        }
                    }
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
        // A real gap after the title as well as between the items: with none, 「思考过程256
        // tokens」 read as one word — the same complaint the " · " join earned, one word to
        // the left.
        if (meta.isNotEmpty()) Spacer(Modifier.width(8.dp))
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
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = toggle,
                    ),
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
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = MaterialTheme.typography.labelLarge.fontSize * (13f / 14f),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                status()
                if (!expanded && firstLine.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = firstLine,
                        style = metaStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
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
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onToggle,
            )
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
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
                            // A stored think starts folded, whatever 思考过程默认展开 says:
                            // that setting is about watching a think arrive, and the user's
                            // rule is that a finished one folds unless they opened it
                            // themselves — which the expansion table records, and it always
                            // wins over this default.
                            startExpanded = false,
                            nested = true,
                            maxHeight = reasoningMaxHeight,
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
 */
@Composable
private fun TurnScrubber(
    turns: Int,
    current: Int,
    modifier: Modifier = Modifier,
    onJumpTo: (Int) -> Unit,
) {
    if (turns <= 1) return
    val scheme = MaterialTheme.colorScheme
    // The last few turns, always including the one being read: sampling the whole
    // conversation into five evenly spaced lines is what made "which line is which turn"
    // impossible to tell, and made the jump land somewhere the user did not point at.
    val window = remember(turns, current) {
        val last = turns - 1
        val start = (last - SCRUBBER_VISIBLE + 1).coerceAtLeast(0)
            .coerceAtMost((current - 1).coerceAtLeast(0))
        start until (start + SCRUBBER_VISIBLE).coerceAtMost(turns)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        window.forEach { turn ->
            val read = turn < current - 1
            val active = turn == current - 1
            Box(
                modifier = Modifier
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                    ) { onJumpTo(turn) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        // The turn being read is the longest and the only full-strength
                        // brand line. It used to fall into the "not reached" branch and come
                        // out grey, which is exactly backwards.
                        .width(if (active) 24.dp else if (read) 20.dp else 12.dp)
                        .height(if (active) 3.dp else 2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            when {
                                active -> scheme.primary
                                read -> scheme.primary.copy(alpha = 0.45f)
                                else -> scheme.outlineVariant
                            },
                        ),
                )
            }
        }
    }
}

/** How many turns the scrubber draws before the window slides up to the newest ones. */
private const val SCRUBBER_VISIBLE = 6

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
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
    cachePercent: Double,
    /** Everything this conversation has ever spent, for the ring's detail card. */
    sessionTokens: Int,
    /** How many questions that tally was built from. */
    sessionTurns: Int,
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
    onSteer: (String) -> Boolean,
    /** Asks the live run to fold the conversation at its next turn boundary. */
    onRequestCompress: () -> Boolean,
    /** Lines queued for the run that is going, oldest first. */
    pendingSteers: List<PendingSteer>,
    /** Sends a queued line now, cutting the round in flight. */
    onPromoteSteer: (String) -> Unit,
    /** Drops a queued line before the model reads it; 「修改」 uses it too. */
    onRetractSteer: (String) -> Unit,
    onFocused: () -> Unit,
    onMic: () -> Unit,
    /** The empty provider list's way out: straight into the model settings page. */
    onOpenSettings: () -> Unit,
    /** Scrolls the transcript to its newest line; the stack's own corner button. */
    jumpToLatest: () -> Unit,
    /** Shown while the transcript is scrolled away from its end. */
    showJump: Boolean,
    /** The stack's measured height, so the list can pad by what it truly occupies. */
    onHeightChanged: (Dp) -> Unit,
    /** The composer's top edge in window coordinates, for the transcript's bottom padding. */
    onTopInWindow: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // Where the model button's centre sits, in window pixels: the 生成设置 sheet is centred
    // on it rather than on the composer or the screen, which is what "right above the model
    // button" means. Read from the layout, because the row's paddings and the label's own
    // width decide it.
    var modelButtonCenter by remember { mutableStateOf(0f) }
    val ctx = LocalContext.current
    // No recognizer on the device means the mic entry would be a dead control, and
    // the user has no patience for those: the row simply drops it.
    val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
    // Nothing here paints a background: the transcript scrolls right down to the gesture
    // bar behind the pill, so the composer reads as something floating over the
    // conversation rather than an opaque strip that cuts it off.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The gesture bar's strip, but never on top of the keyboard: when the keyboard is
            // up it already covers that strip, and padding for it as well pushed the card a
            // navigation bar's height above the keyboard — the gap that grew the moment the
            // keyboard opened, while the keyboard-down spacing looked right.
            .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 12.dp)
            // Measured after the navigation-bar inset is applied, so the number the
            // list pads by is the stack's true footprint on screen.
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) }
            .onGloballyPositioned { coordinates ->
                onTopInWindow(coordinates.boundsInWindow().top)
            },
    ) {
        Column {
            // Zero-height anchor at the stack's top: the generation popup hangs off
            // it, bottom-aligned, so it opens upward over the transcript instead of
            // over the keys it belongs to.
            Box(Modifier.fillMaxWidth().height(0.dp)) {
                // Composed whether or not it is open: a card that is taken down the moment
                // it is dismissed has no time to animate out, and this one has to (see
                // MenuCard). When closed it draws nothing at all.
                GenerationSheet(
                    settings = settings,
                    assistantId = assistantId,
                    conversationProviderId = conversationProviderId,
                    conversationModel = conversationModel,
                    conversationReasoningEffort = conversationReasoningEffort,
                    onChooseReasoning = onChooseReasoning,
                    modelButtonCenterPx = modelButtonCenter,
                    visible = sheetOpen,
                    onPickModel = onPickModel,
                    onOpenSettings = onOpenSettings,
                    onDismiss = { sheetOpen = false },
                )
            }
            // The jump-to-latest button rides the stack's own top: 12dp above whatever
            // the next row is, end-aligned, so it can never hide behind the pill.
            if (showJump) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Clear of the scrubber column on the right edge: 4dp edge gap +
                        // 24dp longest line + 12dp of air, so the disc never sits on a
                        // turn line.
                        .padding(end = 40.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
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
            // Queued lines sit directly above the field they came from: the user's own words
            // on their way to the model, not messages yet — so they are inset from the pill
            // (visibly narrower than it, which is what stops them reading as part of it) and
            // each one carries its own actions as icons.
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
                                // Appended, not substituted: the field may hold something the
                                // user is in the middle of typing, and taking a queued line back
                                // must not throw that away without a word.
                                onInput(
                                    if (input.isBlank()) {
                                        queued.text
                                    } else {
                                        "${input.trimEnd()} ${queued.text}"
                                    },
                                )
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
                        // run instead of starting a second one.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val text = input.trim()
                                if (text.isEmpty()) return@KeyboardActions
                                if (running) {
                                    if (onSteer(text)) onInput("")
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
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(40.dp),
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
                            // Opens upward from the button it belongs to.
                            alignment = Alignment.BottomStart,
                            offset = with(density) { IntOffset(0, -8.dp.roundToPx()) },
                            modifier = Modifier.widthIn(max = 200.dp),
                        ) {
                            MenuItemRow(ImageVector.vectorResource(R.drawable.ic_image), "图片") {
                                menuOpen = false
                                onPickImage()
                            }
                            MenuItemRow(ImageVector.vectorResource(R.drawable.ic_camera), "拍照") {
                                menuOpen = false
                                onTakePhoto()
                            }
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_file),
                                label = "文件",
                            ) {
                                menuOpen = false
                                onPickFile()
                            }
                            // Only while a run is going: the engine consumes the request at
                            // a turn boundary, and with no run there is no boundary — a
                            // greyed-out entry would only invite a tap that cannot work.
                            if (running) {
                                MenuItemRow(ImageVector.vectorResource(R.drawable.ic_minimize_2), "压缩上下文") {
                                    menuOpen = false
                                    val asked = onRequestCompress()
                                    Toast.makeText(
                                        ctx,
                                        if (asked) {
                                            "已请求压缩，本轮结束后生效"
                                        } else {
                                            "没有正在进行的任务，无需压缩"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
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
                        // Measured on the caption, not on the flexible box: the sheet is
                        // centred on the model button, and the box now spans the free space
                        // the controls leave.
                        Box(
                            Modifier.onGloballyPositioned {
                                modelButtonCenter = it.boundsInWindow().center.x
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
                    // Left of the mic, where the user asked for it: the mic is the control
                    // reached for mid-thought, and this is the gauge that says whether the
                    // next question still fits in the window.
                    ContextRing(
                        used = contextUsed,
                        window = contextWindow,
                        cachePercent = cachePercent,
                        sessionTokens = sessionTokens,
                        sessionTurns = sessionTurns,
                        live = stats,
                    )
                    // The mic only earns its slot when a recognizer actually exists on
                    // the device: a dead control is worse than no control.
                    if (speechAvailable) {
                        IconButton(
                            onClick = onMic,
                            enabled = !running,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_mic),
                                contentDescription = "语音输入",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    val hasSomething = input.isNotBlank() || attachment != null
                    // While a run is going, a field with something in it sends that line to the
                    // run; an empty field stops it. One button, and what it does is what the
                    // field in front of the user says — the separate 插话 button only ever made
                    // the user wonder which of the two they wanted.
                    val steers = running && input.isNotBlank()
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
                                steers -> if (onSteer(input.trim())) onInput("")
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
 * that box, so it never covers the field it belongs to. Horizontally it is centred on the
 * model button — [modelButtonCenterPx] is that button's centre, measured in the composer —
 * and nudged back inside the screen when the button sits too close to an edge for a sheet
 * this wide. Centring on the button is the whole point: anchored to the composer's end it
 * used to open off to the right, which read as "the sheet is somewhere else".
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
        properties = PopupProperties(focusable = true),
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

/** One row of a popup menu: icon, label, and the whole row as the target. */
@Composable
private fun MenuItemRow(icon: ImageVector, label: String, onClick: () -> Unit) {
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
    modelButtonCenterPx: Float,
    /** Whether the card is open. It animates in and out on this, so it stays composed. */
    visible: Boolean,
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
    var sheetWidth by remember { mutableStateOf(0) }
    val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val edge = with(density) { 8.dp.toPx() }
    val nudge = if (sheetWidth <= 0 || anchorLeft == 0f) {
        0f
    } else {
        val width = sheetWidth.toFloat()
        val left = (modelButtonCenterPx - width / 2f)
            .coerceIn(edge, (screenPx - edge - width).coerceAtLeast(edge))
        left - anchorLeft
    }
    // Zero-height, full-width probe inside the composer's anchor box: it reads that box's
    // left edge, which is the origin the popup's own alignment is measured from.
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorLeft = it.boundsInWindow().left },
    )
    // Which page the sheet is on: the light one — the model that will answer and how hard it
    // thinks — or the full list of models. The list became a page of its own because the user
    // asked for the first page to stay small.
    var picking by remember { mutableStateOf(false) }
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
        modifier = Modifier.widthIn(max = 300.dp).onSizeChanged { sheetWidth = it.width },
    ) {
        if (!picking) {
            // Page one: one big name to tap, one slider to drag, nothing else.
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
                                .padding(top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = owner.shortLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = scheme.primary,
                                modifier = Modifier.weight(1f),
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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
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
 * Every one of these used to be swallowed — the card simply vanished — so a device whose
 * recognizer will not serve third-party apps looked exactly like a dead microphone.
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
 * In-app dictation: a floating card over a scrim, with the recognizer's partial
 * results live in the middle.
 *
 * [SpeechRecognizer] owns the microphone itself; this only draws the card and reads
 * what it reports. A result closes the card. A failure keeps it open with the reason —
 * and, because some devices (MIUI's AsrService answers ERROR_CLIENT) only let their own
 * speech screen bind the recognizer, offers the system's input as the way out.
 */
@Composable
private fun VoiceOverlay(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var partial by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<VoicePhase>(VoicePhase.Preparing) }
    // Retrying means a new recognizer: the failed one is spent, and reusing it would replay
    // the same refusal.
    var attempt by remember { mutableStateOf(0) }
    // One automatic hand-off per visit, or a device that refuses us would throw the system
    // speech screen at the user again on every retry.
    var fellBack by remember { mutableStateOf(false) }
    val recognizer = remember(attempt) {
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    phase = VoicePhase.Listening
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    phase = VoicePhase.Thinking
                }

                override fun onError(error: Int) {
                    // "Nobody spoke" is not a failure to report: the card closes the way it
                    // always did. Everything else stays on screen with its reason.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        onDismiss()
                    } else {
                        phase = VoicePhase.Failed(voiceErrorMessage(error), error)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (best.isNotEmpty()) onResult(best)
                    onDismiss()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }
    DisposableEffect(recognizer) {
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
            },
        )
        onDispose {
            recognizer.cancel()
            recognizer.destroy()
        }
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
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrEmpty()) {
            onResult(text)
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
        // Our own recognizer would keep the microphone while the system screen wants it.
        recognizer.cancel()
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
            phase = VoicePhase.Failed("这台设备没有可用的系统语音输入", 0)
        }
    }
    val failure = phase as? VoicePhase.Failed
    LaunchedEffect(failure?.code) {
        // A recognizer that refuses this app is the MIUI case; hand the job to the system's
        // own input once, without making the user ask for it.
        if (failure?.code == SpeechRecognizer.ERROR_CLIENT && !fellBack) {
            fellBack = true
            useSystemInput()
        }
    }
    val listening = phase is VoicePhase.Listening
    val scheme = MaterialTheme.colorScheme
    // Two rings breathing out of the disc while the mic is open: the only motion on
    // the card, and it stops the moment recognition ends.
    val pulse = rememberInfiniteTransition(label = "voice-pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "voice-ring",
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "voice-ring-alpha",
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.25f),
                )
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (listening) {
                    Box(
                        Modifier
                            .size(88.dp * ring)
                            .clip(CircleShape)
                            .background(scheme.primary.copy(alpha = ringAlpha)),
                    )
                    Box(
                        Modifier
                            .size(88.dp * (1f + (ring - 1f) * 0.6f))
                            .clip(CircleShape)
                            .background(scheme.primary.copy(alpha = ringAlpha * 0.6f)),
                    )
                }
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_mic),
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = when {
                    failure != null -> failure.message
                    partial.isNotEmpty() -> partial
                    phase is VoicePhase.Listening -> "请说话…"
                    phase is VoicePhase.Thinking -> "正在识别…"
                    else -> "正在准备麦克风…"
                },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = if (partial.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                if (failure != null) {
                    // The two ways out of a recognizer that will not work: the system's own
                    // speech screen, or one more try with a fresh recognizer.
                    TextButton(onClick = useSystemInput) { Text("改用系统语音输入") }
                    Button(
                        onClick = {
                            partial = ""
                            phase = VoicePhase.Preparing
                            attempt++
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("重试")
                    }
                } else {
                    Button(
                        onClick = { recognizer.stopListening() },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("完成")
                    }
                }
            }
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
 * The touch target is 32dp, not the 48dp the primary controls use: this row also carries the
 * model caption, the mic and the send button, and at 48dp the caption would be too narrow to
 * read on a 360dp-wide screen.
 */
@Composable
private fun ContextRing(
    used: Int,
    window: Int,
    cachePercent: Double,
    /** Everything this conversation has ever spent, prompt + completion. */
    sessionTokens: Int,
    /** How many questions that tally was built from, so 「累计」 cannot read as 「本轮」. */
    sessionTurns: Int,
    live: StreamStats?,
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val fraction = if (known) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val tone = if (fraction >= 0.8f) scheme.error else scheme.primary
    val track = scheme.surfaceVariant
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { open = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.5.dp.toPx()
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
        // The card's anchor: a zero-height box at the ring's top edge, so the popup opens
        // upward from the ring instead of covering the row it belongs to.
        Box(Modifier.align(Alignment.TopCenter)) {
            if (open) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    offset = with(LocalDensity.current) { IntOffset(0, -8.dp.roundToPx()) },
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    ContextRingCard(
                        used = used,
                        window = window,
                        cachePercent = cachePercent,
                        sessionTokens = sessionTokens,
                        sessionTurns = sessionTurns,
                        live = live,
                    )
                }
            }
        }
    }
}

/** The ring's detail: the numbers a 22dp arc cannot show. */
@Composable
private fun ContextRingCard(
    used: Int,
    window: Int,
    cachePercent: Double,
    sessionTokens: Int,
    sessionTurns: Int,
    live: StreamStats?,
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known) used * 100 / window else 0
    Column(
        modifier = Modifier
            .widthIn(max = 240.dp)
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
            Text(
                text = "暂无数据",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            // Same intrinsic-width trick as the metric cards: the numbers share one right edge
            // without the card asking for the screen's width.
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 208.dp)) {
            RingDetail(
                "上下文",
                if (known) {
                    "${shortTokens(used)} / ${shortTokens(window)}（$percent%）"
                } else {
                    // No invented denominator, and no editor either: where the window is
                    // filled in is the settings page, which also fills it from the endpoint's
                    // own context_window when the user has not said otherwise. The ring only
                    // reports.
                    "${shortTokens(used)} · 窗口未设置 · 到设置里填"
                },
            )
            if (cachePercent > 0) {
                // Two decimals, and formatted once per value rather than on every recomposition:
                // the drawer's own convention, at the precision a long conversation needs.
                val hit = remember(cachePercent) {
                    String.format(Locale.getDefault(), "%.2f%%", cachePercent)
                }
                RingDetail("缓存命中", hit)
            }
            live?.let {
                RingDetail(
                    "本轮",
                    "入 ${shortTokens(it.lastPromptTokens)} · 出 ${shortTokens(it.tokensOut)}",
                )
            }
            if (sessionTokens > 0) {
                // The whole conversation's spend, not the context on screen: every round
                // re-sends the context, so this is the number a bill would be built from —
                // and it is the same tally the usage page shows. Labelled 「累计」 rather
                // than 「总量」 because it counts each round's re-send, which is why it can
                // dwarf the 「上下文」 line right above it.
                RingDetail("会话累计", "${shortTokens(sessionTokens)} tok · $sessionTurns 轮")
            }
            }
        }
    }
}

/**
 * One label/value line of the ring's and the metrics' cards.
 *
 * The value is right-aligned on a shared column rather than set beside the label: a stack of
 * 「入 83.6k / 缓存 token 82.4k / 未缓存 1.2k」 reads as a table when the numbers line up on one
 * edge, and as prose when each number starts wherever its label happened to end. The card
 * itself is given a floor width so that column has room to exist.
 */
@Composable
private fun RingDetail(label: String, value: String) {
    Row(
        // Fills its parent, and the parent is sized to the widest row (see the cards): that is
        // what lines the numbers up. It deliberately does not ask for the screen's width — the
        // first attempt did, and a popup that should hug its content grew to the full screen.
        modifier = Modifier.fillMaxWidth(),
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = { expanded = !expanded },
                )
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
            Text(
                text = if (undelivered) "${steer.text}（未送达）" else steer.text,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // An undelivered line has no round left to interrupt: the action starts a new turn
            // instead, and the label has to say so.
            QueueAction(
                icon = R.drawable.ic_arrow_up,
                label = if (undelivered) "发送" else "立即插话",
                onClick = onPromote,
            )
            QueueAction(icon = R.drawable.ic_square_pen, label = "修改", onClick = onEdit)
            QueueAction(icon = R.drawable.ic_trash, label = "删除", onClick = onDelete)
        }
    }
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
    Row(modifier = Modifier.padding(start = 10.dp)) {
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
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
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

/** True while the last item is on screen — the state that lets a reply auto-scroll. */
private fun LazyListState.atEnd(slack: Int = 24): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + slack
}

/** Far more than any transcript: the list clamps this to its own end. */
private const val FOLLOW_SCROLL_PX = 200_000f

/**
 * How long a press has to last before it counts as the user reaching into the content.
 *
 * The platform's own long-press threshold is 500ms and text selection starts there; a shorter
 * window here would drop the follow scroll on an ordinary tap, which is not an interaction with
 * the position at all.
 */
private const val HOLD_TO_SELECT_MS = 450L

/** How many queued lines are drawn before the rest collapse into one count. */
private const val STEER_ROWS_SHOWN = 3

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
        if (full) {
            // The thumbnail is for recognising the shot; reading it needs the whole
            // screen and a pinch.
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val transformable = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
            Dialog(onDismissRequest = { full = false }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { full = false },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = decoded,
                        contentDescription = "工具截图",
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
        }
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
