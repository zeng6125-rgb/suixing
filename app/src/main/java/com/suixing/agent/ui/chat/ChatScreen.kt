package com.suixing.agent.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ripple
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
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Transcript
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.DrawerButton
import com.suixing.agent.ui.components.FeatherIcon
import com.suixing.agent.ui.components.FlatIconButton
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MarkdownText
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.components.markdownBody
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val itemCount = visible.size + if (live) 1 else 0

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
        snapshotFlow { listState.isScrollInProgress to listState.atEnd(slack = 48) }
            .collect { (scrolling, atEnd) ->
                if (scrolling && !programmatic && !atEnd) following = false
                else if (atEnd) following = true
            }
    }
    // A new conversation starts at its newest message, whoever was reading what before.
    LaunchedEffect(state.conversationId) { following = true }
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
        programmatic = true
        try {
            listState.scrollToItem(itemCount - 1)
            // Clamped by the list itself: a reply taller than the viewport should keep its
            // end on screen rather than its first line.
            listState.scrollBy(FOLLOW_SCROLL_PX)
        } finally {
            programmatic = false
        }
    }

    val atEnd by remember { derivedStateOf { listState.atEnd() } }

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
                if (visible.isEmpty() && !live) {
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
                                bottom = composerH + 40.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyHero(onPick = { setDraft(text = it) })
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            // The floating header owns the status bar strip, so the
                            // first bubble starts below its discs with 16dp of air:
                            // status bar + 4dp lift + 56dp row + 16dp gap.
                            top = with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            } + 76.dp,
                            // The composer floats over the list, so the list leaves
                            // exactly the room the stack occupies plus 16dp of air —
                            // measured, not guessed, so neither the keyboard-up nor the
                            // keyboard-down state leaves a blank band.
                            bottom = composerH + 40.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(visible, key = { _, row -> row.message.id }) { _, row ->
                            if (row.showTimeLabel) TimeLabel(row.message.timestamp)
                            MessageRow(
                                row = row,
                                assistantName = assistant?.label ?: "随行",
                                assistantFace = assistant?.face ?: "",
                                showReasoning = settings.showReasoning,
                                expandReasoning = settings.expandReasoning,
                                expandTools = settings.expandTools,
                                reasoningMaxHeight = settings.reasoningHeight.dp,
                                contextWindow = settings.contextWindowFor(settings.model),
                                onBranch = { turnId, runId -> viewModel.switchBranch(turnId, runId) },
                                onRegenerate = { viewModel.regenerate() },
                                onEdit = { editing = it },
                            )
                        }
                        if (live) {
                            item(key = "streaming") {
                                AssistantRow(
                                    parts = state.liveParts.mapNotNull { part ->
                                        when (part) {
                                            is LivePart.Thinking -> if (settings.showReasoning) {
                                                Part.Thinking(
                                                    text = part.text,
                                                    tokens = part.tokens,
                                                    elapsedMs = part.elapsedMs,
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
                                    // The live bubble is the persona's reply too: without
                                    // these two it fell back to the brand name and feather
                                    // while every stored bubble showed the persona.
                                    name = assistant?.label ?: "随行",
                                    face = assistant?.face ?: "",
                                    runningTool = state.runningTool,
                                    liveStats = state.stats,
                                    contextWindow = settings.contextWindowFor(settings.model),
                                    expandReasoning = settings.expandReasoning,
                                    expandTools = settings.expandTools,
                                    reasoningMaxHeight = settings.reasoningHeight.dp,
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
            // rather than a bar that cuts the conversation off.
            Box(Modifier.align(Alignment.BottomCenter)) {
                Composer(
                    input = input,
                    onInput = { setDraft(text = it) },
                    running = state.runningHere,
                    // The status line names the run on screen; a run in another
                    // conversation is none of this composer's business.
                    status = if (state.runningHere) state.status else "",
                    stats = state.stats,
                    settings = settings,
                    onPickModel = { provider, model ->
                        scope.launch {
                            Core.settings.update {
                                it.copy(
                                    baseUrl = provider.baseUrl,
                                    apiKey = provider.apiKey,
                                    model = model,
                                )
                            }
                        }
                    },
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
                    onFocused = {
                        // Tapping the field with the keyboard still down should reveal the
                        // newest message rather than leaving the user mid-transcript.
                        if (itemCount > 0) scope.launch { listState.animateScrollToItem(itemCount - 1) }
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
                )
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
            tint = MaterialTheme.colorScheme.onSurface,
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
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The bar's title: 15sp medium, scaled off titleMedium so 字号 still applies. */
@Composable
private fun titleBarStyle(): TextStyle {
    val title = MaterialTheme.typography.titleMedium
    return title.copy(fontSize = title.fontSize * (15f / 17f))
}

/** A flat text dropdown: label plus a caret, no container of its own. */
@Composable
private fun UnifiedSelector(settings: AppSettings, onClick: () -> Unit) {
    val assistant = settings.assistant(settings.activeAssistantId) ?: settings.activeAssistant()
    val provider = settings.providers.firstOrNull { it.id == assistant?.providerId }
    val own = provider?.takeIf { it.ownSampling }
    val wire = own?.reasoningEffort ?: settings.reasoningEffort
    val level = REASONING_LEVELS.firstOrNull { it.first == wire }?.second ?: "默认"
    val model = provider?.displayModel(settings.model)
        ?.takeIf { it != settings.model }
        ?: settings.shortModel.ifBlank { "选模型" }
    Row(
        modifier = Modifier
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
            modifier = Modifier.weight(1f, fill = false),
        )
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                text = level,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
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
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
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
    /** One round of thinking, with that round's own numbers. */
    data class Thinking(val text: String, val tokens: Int, val elapsedMs: Long) : Part

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
                        add(Part.Thinking(it, message.reasoningTokens, message.elapsedMs))
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
    contextWindow: Int,
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
            message = row.message,
            expandReasoning = expandReasoning,
            expandTools = expandTools,
            reasoningMaxHeight = reasoningMaxHeight,
            onBranch = onBranch,
            onRegenerate = onRegenerate,
            onEdit = onEdit,
        )

        // A tool result with no bubble above it still renders as one, card only.
        else -> AssistantRow(
            parts = listOf(Part.Tools(listOf(ToolLine.of(row.message)))),
            streaming = false,
            face = assistantFace,
        )
    }
}

@Composable
private fun UserBubble(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .pointerInput(message.id) {
                detectTapGestures(onLongPress = { onEdit(message) })
            },
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
// "1. do this" means the list item, not a heading. The style is still the assistant
// body's, so the two bubbles agree on size and leading.
            Text(message.text, style = markdownBody())
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
        TinyAction(Icons.Rounded.ContentCopy, "复制") {
            clipboard.setText(AnnotatedString(message.text))
        }
        TinyAction(Icons.Rounded.Edit, "修改") { onEdit(message) }
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
    message: StoredMessage? = null,
    expandReasoning: Boolean = false,
    expandTools: Boolean = false,
    reasoningMaxHeight: Dp = 180.dp,
    onBranch: (String, String) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
    onEdit: (StoredMessage) -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                    expandReasoning = expandReasoning,
                    reasoningMaxHeight = reasoningMaxHeight,
                    onEdit = onEdit,
                )
                // 头像行→10→首块、末块→10→正文/气泡底: the card's own air below is
                // this spacer, so a bubble without a card does not stack two of them.
                Spacer(Modifier.height(10.dp))
            }
            val lastText = parts.indexOfLast { it is Part.Text }
            parts.forEachIndexed { index, part ->
                val last = index == parts.lastIndex
                when (part) {
                    is Part.Thinking -> {
                        // A settled reply keeps its thinking inside the summary card
                        // alone: a second copy beside the answer is what made one
                        // answer read as a stack of blocks. Live it still streams here.
                        if (!streaming) return@forEachIndexed
                        ReasoningBlock(
                            text = part.text,
                            // A live block is the one still growing; a settled round's
                            // thinking is complete even while the run continues.
                            streaming = streaming && last,
                            reasoningTokens = part.tokens,
                            elapsedMs = part.elapsedMs,
                            // Thinking is the reason to run a thinking model at all, so a
                            // live block starts open; it settles into the setting's state
                            // once the reply is stored and drawn as its own message.
                            startExpanded = expandReasoning || streaming,
                            maxHeight = reasoningMaxHeight,
                        )
                        if (!last) Spacer(Modifier.height(10.dp))
                    }

                    is Part.Tools -> {
                        // Same rule as thinking: settled tool rows live in the summary
                        // card's expanded list, not flat beside the answer.
                        if (!streaming) return@forEachIndexed
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
                        // Only the last stretch of prose is drawn once the run is over: the
                        // model's "let me check" before a tool is not part of its answer.
                        // While it streams every stretch shows, because that is what it is
                        // doing right now.
                        if (!streaming && index != lastText) return@forEachIndexed
                        SelectionContainer {
                            MarkdownText(text = part.text)
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
                icon = Icons.Rounded.ChevronLeft,
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
                icon = Icons.Rounded.ChevronRight,
                label = "下一个回答",
                enabled = !atEnd,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index + 1]) },
            )
        }
        if (branch?.canRegenerate == true) {
            BranchArrow(
                icon = Icons.Rounded.Refresh,
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
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
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
 * The model's thinking, folded into the reply's own card.
 *
 * It is drawn in its own [LazyColumn] so a 10k-character chain of thought only ever
 * composes the handful of lines on screen — laying the whole string out as one [Text]
 * is what makes a long think stutter. Whether it is open survives scrolling away,
 * because it is remembered per message.
 */
@Composable
private fun ReasoningBlock(
    text: String,
    streaming: Boolean,
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
    // The setting only decides where a block starts; once the user taps the header the
    // choice sticks to that message for as long as it stays in the list.
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    // A streaming think opens on its own and closes when it settles, but a header the
    // user tapped is theirs: the auto-close must not fight a manual choice.
    var userToggled by remember { mutableStateOf(false) }
    var wasStreaming by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        if (streaming) {
            expanded = true
        } else if (wasStreaming && !userToggled) {
            expanded = false
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
        expanded = !expanded
        userToggled = true
    }
    val status: @Composable () -> Unit = {
        if (meta.isNotEmpty()) ProcessStatus(meta.joinToString(" · "))
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
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = toggle,
                )
                .padding(vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Psychology,
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
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
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
            icon = Icons.Rounded.Psychology,
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
        icon = Icons.Rounded.Build,
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
                    imageVector = Icons.Rounded.ContentCopy,
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
                        imageVector = Icons.Rounded.Build,
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
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
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
    expandReasoning: Boolean = false,
    reasoningMaxHeight: Dp = 180.dp,
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
    }.joinToString(" · ")
    ProcessCard(
        icon = Icons.Rounded.Insights,
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
            parts.forEach { part ->
                when (part) {
                    is Part.Thinking -> ReasoningBlock(
                        text = part.text,
                        streaming = false,
                        reasoningTokens = part.tokens,
                        elapsedMs = part.elapsedMs,
                        startExpanded = expandReasoning,
                        nested = true,
                        maxHeight = reasoningMaxHeight,
                    )

                    is Part.Tools -> {
                        var openTool by remember { mutableStateOf<String?>(null) }
                        part.calls.forEachIndexed { index, call ->
                            // Name + status on the row, arguments and output behind its
                            // own tap — the same two-level row the live bundle draws.
                            val key = call.message?.id ?: "summary-$index"
                            ToolRow(
                                line = call,
                                expanded = openTool == key,
                                onToggle = { openTool = if (openTool == key) null else key },
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
 * More than thirty turns sample down to thirty evenly spaced lines so the column never
 * grows past the screen; the current turn is the brand-coloured longer line.
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
    // One slot per turn, so a tap lands exactly where the eye aims. Past five turns the
    // column samples five evenly spaced turns instead of growing past the screen; each
    // slot still jumps to its own turn, not to a quarter's head.
    val slots = remember(turns) {
        if (turns <= SCRUBBER_SLOTS) {
            List(turns) { it }
        } else {
            List(SCRUBBER_SLOTS) { i ->
                kotlin.math.round(i.toDouble() * (turns - 1) / (SCRUBBER_SLOTS - 1)).toInt()
            }.distinct()
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        slots.forEach { turn ->
            val reached = turn < current - 1
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
                        // Reached turns are the long brand lines, the ones ahead short
                        // outline ones; the turn being read is brand and bold.
                        .width(if (active) 24.dp else if (reached) 20.dp else 12.dp)
                        .height(if (active) 3.dp else 2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (reached) scheme.primary else scheme.outlineVariant),
                )
            }
        }
    }
}

private const val SCRUBBER_SLOTS = 5

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

@Composable
private fun TimeLabel(timestamp: Long) {
    Text(
        text = formatTime(timestamp),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    running: Boolean,
    status: String,
    /** The live run's stats; the status line reads its compression note. */
    stats: StreamStats?,
    settings: AppSettings,
    onPickModel: (ProviderConfig, String) -> Unit,
    attachment: Attachment?,
    onClearAttachment: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Queues a line for the running model; false means it was dropped. */
    onSteer: (String) -> Boolean,
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
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
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
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 12.dp)
            // Measured after the navigation-bar inset is applied, so the number the
            // list pads by is the stack's true footprint on screen.
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) },
    ) {
        Column {
            // Zero-height anchor at the stack's top: the generation popup hangs off
            // it, bottom-aligned, so it opens upward over the transcript instead of
            // over the keys it belongs to.
            Box(Modifier.fillMaxWidth().height(0.dp)) {
                if (sheetOpen) {
                    GenerationSheet(
                        settings = settings,
                        onPickModel = onPickModel,
                        onOpenSettings = onOpenSettings,
                        onDismiss = { sheetOpen = false },
                    )
                }
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
                        icon = Icons.Rounded.ArrowDownward,
                        contentDescription = "回到最新",
                        onClick = jumpToLatest,
                        size = 48.dp,
                        containerColor = scheme.surface,
                        iconSize = 22.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // One plain line, 8dp above the pill row: a run's progress is a caption for
            // the pill, not a panel of its own.
            val note = stats?.note.orEmpty()
            val statusText = when {
                !running -> ""
                note.isNotEmpty() -> note
                else -> status.ifBlank { "正在思考…" }
            }
            if (running) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 16dp of air above when this line opens the stack: it then sits
                        // under the transcript's last card, not against it. With the jump
                        // disc above, that disc's own 12dp spacer is the gap.
                        .padding(
                            start = 4.dp,
                            end = 4.dp,
                            top = if (showJump) 0.dp else 16.dp,
                            bottom = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            style = statusLineStyle(),
                            color = if (note.isNotEmpty()) scheme.tertiary else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            attachment?.let {
                AttachmentChip(attachment = it, onRemove = onClearAttachment)
                Spacer(Modifier.height(8.dp))
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
                                        text = if (running) "插一句话…" else "让随行做点什么…",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    // Row 2: attach, the two flat selectors, mic, action — the controls
                    // live under the text now, one 44dp line.
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "添加图片或文件",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("图片") },
                                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onPickImage()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onTakePhoto()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("文件") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onPickFile()
                                },
                            )
                        }
                    }
                    // One selector for both decisions: the model and the reasoning
                    // level live together in the 生成设置 sheet, so the row keeps a
                    // single caption instead of two competing ones.
                    UnifiedSelector(settings = settings, onClick = { sheetOpen = true })
                    Spacer(Modifier.weight(1f))
                    // The mic only earns its slot when a recognizer actually exists on
                    // the device: a dead control is worse than no control.
                    if (speechAvailable) {
                        IconButton(
                            onClick = onMic,
                            enabled = !running,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = "语音输入",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    // 插话发送: only while a run is live and there is something to say —
                    // an always-present second button would be a dead control most of
                    // the time, and the stop button beside it still owns the slot.
                    if (running && input.isNotBlank()) {
                        CircleActionButton(
                            icon = Icons.Rounded.ArrowUpward,
                            contentDescription = "插话发送",
                            onClick = { if (onSteer(input.trim())) onInput("") },
                            size = 40.dp,
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                            iconSize = 22.dp,
                        )
                    }
                    val hasSomething = input.isNotBlank() || attachment != null
                    val sendable = running || hasSomething
                    CircleActionButton(
                        icon = if (running) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = if (running) "停止" else "发送",
                        onClick = { if (running) onStop() else onSend() },
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
 */
@Composable
private fun GenerationSheet(
    settings: AppSettings,
    onPickModel: (ProviderConfig, String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val assistant = settings.assistant(settings.activeAssistantId) ?: settings.activeAssistant()
    // The persona's own provider first; a persona saved before providers existed has a
    // blank id, so the base url the app is talking to is the next best witness, and a
    // provider with anything enabled is the one after that. Null only when there is
    // genuinely nothing configured.
    val provider = settings.providers.firstOrNull { it.id == assistant?.providerId }
        ?: settings.providers.firstOrNull {
            it.baseUrl.trimEnd('/') == settings.baseUrl.trimEnd('/')
        }
        ?: settings.providers.firstOrNull { it.enabled.isNotEmpty() }
    val own = provider?.takeIf { it.ownSampling }
    val wire = own?.reasoningEffort ?: settings.reasoningEffort
    val levelIndex = REASONING_LEVELS.indexOfFirst { it.first == wire }.coerceAtLeast(0)
    // The model in use is always a row, even when the provider list lost it: picking
    // what you already run must never be impossible.
    val models = (provider?.enabled.orEmpty() + settings.model).distinct()
    Popup(
        // Anchored to the composer's own top edge, not a sheet from the screen's
        // bottom: the picker is a control of the row that opened it.
        alignment = Alignment.BottomEnd,
        offset = with(LocalDensity.current) { IntOffset(0, -8.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider?.displayModel(settings.model) ?: settings.shortModel,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = scheme.primaryContainer) {
                    Text(
                        text = REASONING_LEVELS[levelIndex].second,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Slider(
                value = levelIndex.toFloat(),
                onValueChange = { index ->
                    val level = REASONING_LEVELS[index.toInt()].first
                    scope.launch {
                        Core.settings.update { stored ->
                            if (own != null) {
                                stored.copy(
                                    providers = stored.providers.map {
                                        if (it.id == own.id) it.copy(reasoningEffort = level) else it
                                    },
                                )
                            } else {
                                stored.copy(reasoningEffort = level)
                            }
                        }
                    }
                },
                valueRange = 0f..REASONING_LEVELS.lastIndex.toFloat(),
                steps = REASONING_LEVELS.size - 2,
            )
            Text(
                text = "思考强度 · ${REASONING_LEVELS[levelIndex].second}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            models.forEach { id ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) {
                            if (provider != null) onPickModel(provider, id)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = id == settings.model,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = provider?.displayModel(id) ?: id,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (provider == null || provider.enabled.isEmpty()) {
                Text(
                    text = "这个提供方还没启用模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                    Text("去设置里拉取")
                }
            }
        }
    }
}

/**
 * In-app dictation: a floating card over a scrim, with the recognizer's partial
 * results live in the middle. The system recognizer's own app gave no partials and no
 * way to cancel mid-sentence, which is what the old hand-off cost.
 *
 * [SpeechRecognizer] owns the microphone itself; this only draws the card and reads
 * what it reports. Recognition ending — a result or an error — closes the card.
 */
@Composable
private fun VoiceOverlay(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var partial by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(true) }
    val recognizer = remember {
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) {
                    listening = false
                    onDismiss()
                }

                override fun onResults(results: Bundle?) {
                    listening = false
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
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = partial.ifEmpty { if (listening) "请说话…" else "…" },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = if (partial.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
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

/** What the next question carries along: an image for the model, or a file on disk. */
data class Attachment(
    val base64: String,
    val label: String,
    val preview: ImageBitmap? = null,
)

private enum class AttachKind { IMAGE, FILE }

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
                        imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
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
                        imageVector = Icons.Rounded.Close,
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
