package com.suixing.agent.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.suixing.agent.ui.components.DrawerButton
import com.suixing.agent.ui.components.MarkdownText
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
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<StoredMessage?>(null) }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (spoken.isNotEmpty()) {
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
        }
    }

    var assistantsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var attachment by remember { mutableStateOf<Attachment?>(null) }

    fun attach(uri: Uri?, kind: AttachKind) {
        if (uri == null) return
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readAttachment(context, uri, kind) }
            if (picked == null) {
                snackbarHostState.showSnackbar(if (kind == AttachKind.FILE) "读不到这个文件" else "读不到这张图片")
            } else {
                attachment = picked
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> attach(uri, AttachKind.IMAGE) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap -> if (bitmap != null) attachment = bitmapAttachment(bitmap) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> attach(uri, AttachKind.FILE) }

    // Tool-only assistant turns carry no text of their own, so their tool calls are
    // folded into the bubble above them instead of standing alone in the transcript.
    // Only the branch the user picked is drawn; the other answers stay on disk.
    val visible = remember(state.messages, state.activeRuns, state.running) {
        groupRows(state.messages, state.activeRuns, state.running)
    }
    val reasoning = if (settings.showReasoning) state.reasoning else ""
    val live = state.streaming.isNotEmpty() || reasoning.isNotEmpty()
    val assistant = settings.assistant(state.assistantId) ?: settings.activeAssistant()

    val itemCount = visible.size + if (live) 1 else 0

    // Auto-scrolling is the scroll position's decision, not the transcript's: while the
    // list sits at the end a growing reply keeps it there, and the moment the user drags
    // up to read something the view stops being yanked back down.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.atEnd() }
            .collect { (scrolling, atEnd) ->
                if (scrolling && !atEnd) following = false else if (atEnd) following = true
            }
    }
    // A new conversation starts at its newest message, whoever was reading what before.
    LaunchedEffect(state.conversationId) { following = true }
    // The streamed text grows without changing the item count, so its length is part of
    // the trigger too.
    LaunchedEffect(itemCount, state.streaming.length, state.reasoning.length, settings.autoFollow) {
        if (itemCount <= 0 || !following || !settings.autoFollow) return@LaunchedEffect
        listState.scrollToItem(itemCount - 1)
        // Clamped by the list itself: a reply taller than the viewport should keep its
        // end on screen rather than its first line.
        listState.scrollBy(FOLLOW_SCROLL_PX)
    }
    val atEnd by remember { derivedStateOf { listState.atEnd() } }

    fun jumpToLatest() {
        scope.launch {
            following = true
            if (itemCount > 0) {
                listState.scrollToItem(itemCount - 1)
                listState.scrollBy(FOLLOW_SCROLL_PX)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Composer(
                input = input,
                onInput = { input = it },
                running = state.running,
                // 运行在别的会话里时，状态行得点名是哪一个：否则「正在思考…」会让人以为
                // 是眼前这条对话在跑。
                status = when {
                    !state.running -> ""
                    state.runningHere -> state.status
                    else -> "『${state.runningTitle}』正在运行"
                },
                stats = state.stats,
                notice = state.notice,
                attachment = attachment,
                onClearAttachment = { attachment = null },
                onPickImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onTakePhoto = { cameraLauncher.launch(null) },
                onPickFile = { filePicker.launch("*/*") },
                onSend = {
                    if (viewModel.send(input, attachment?.base64)) {
                        input = ""
                        attachment = null
                    }
                },
                onStop = { viewModel.stop() },
                onSteer = { viewModel.steer(it) },
                onFocused = {
                    // Tapping the field with the keyboard still down should reveal the
                    // newest message rather than leaving the user mid-transcript.
                    if (itemCount > 0) scope.launch { listState.animateScrollToItem(itemCount - 1) }
                },
                onMic = {
                    // The recognizer runs in its own app, so this needs no
                    // RECORD_AUDIO permission from us.
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                    }
                    runCatching { voiceLauncher.launch(intent) }.onFailure {
                        scope.launch { snackbarHostState.showSnackbar("没有可用的语音识别应用") }
                    }
                },
            )
        },
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
        // The header floats over the transcript, so the list reserves the bar's height as
        // padding instead of the scaffold reserving an opaque band for it.
        val barHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
        // The transcript and the jump button share one box: the button belongs to the
        // reading area, not to the composer, so it must not sit under the pill.
        Box(
            Modifier
                .fillMaxSize()
                .background(canvas)
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                state.error?.let { message ->
                    Box(Modifier.padding(top = barHeight)) {
                        ErrorCard(message = message, onDismiss = { viewModel.dismissError() })
                    }
                }
                if (visible.isEmpty() && !live) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHero(onPick = { input = it })
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Content scrolls from under the floating bar, so the top padding
                        // is the bar itself rather than a gutter.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = if (state.error != null) 10.dp else barHeight,
                            bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(visible, key = { _, row -> row.message.id }) { _, row ->
                            if (row.showTimeLabel) TimeLabel(row.message.timestamp)
                            MessageRow(
                                row = row,
                                avatar = assistant?.face ?: "🪶",
                                showReasoning = settings.showReasoning,
                                expandReasoning = settings.expandReasoning,
                                expandTools = settings.expandTools,
                                contextWindow = settings.contextWindow,
                                onBranch = { turnId, runId -> viewModel.switchBranch(turnId, runId) },
                                onRegenerate = { viewModel.regenerate() },
                                onEdit = { editing = it },
                            )
                        }
                        if (live) {
                            item(key = "streaming") {
                                // The live reply is one round in progress, so its parts are
                                // just what has arrived so far: thinking, then prose.
                                val liveParts = buildList {
                                    if (reasoning.isNotBlank()) {
                                        add(
                                            Part.Thinking(
                                                text = reasoning,
                                                tokens = state.stats?.thinkingTokens ?: 0,
                                                elapsedMs = state.stats?.elapsedMs ?: 0,
                                            ),
                                        )
                                    }
                                    if (state.streaming.isNotBlank()) {
                                        add(Part.Text(state.streaming))
                                    }
                                }
                                AssistantRow(
                                    parts = liveParts,
                                    streaming = true,
                                    runningTool = state.runningTool,
                                    avatar = assistant?.face ?: "🪶",
                                    liveStats = state.stats,
                                    contextWindow = settings.contextWindow,
                                    expandReasoning = settings.expandReasoning,
                                    expandTools = settings.expandTools,
                                )
                            }
                        }
                    }
                }
            }
            ChatTopBar(
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
                onNew = { viewModel.newConversation() },
            )
            if (!atEnd) {
                // Bottom-right corner of the reading area: thumb-reachable, and it only
                // exists while the transcript is scrolled away from its end.
                Surface(
                    onClick = { jumpToLatest() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 14.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "回到最新",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(11.dp).size(20.dp),
                    )
                }
            }
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
 * The chat header: no bar and no background, just three controls floating over the
 * transcript. The conversation's own name is not repeated here — it is on the drawer row
 * that opened it, and a second copy up here only competed with the model chip for room.
 */
@Composable
private fun ChatTopBar(
    settings: AppSettings,
    onPickModel: (ProviderConfig, String) -> Unit,
    onNew: () -> Unit,
) {
    var modelsOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerButton()
        Spacer(Modifier.width(8.dp))
        Box {
            ModelChip(
                label = settings.shortModel.ifBlank { "选模型" },
                onClick = { modelsOpen = true },
            )
            DropdownMenu(
                expanded = modelsOpen,
                onDismissRequest = { modelsOpen = false },
            ) {
                ModelMenu(settings = settings) { provider, model ->
                    onPickModel(provider, model)
                    modelsOpen = false
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onNew,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "新对话",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The model name in the header; tapping it opens the switcher. */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "切换模型",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Every model the user ticked, grouped by the provider it came from, so switching
 * between a LAN proxy's upstream vendors takes two taps.
 */
@Composable
private fun ModelMenu(settings: AppSettings, onPick: (ProviderConfig, String) -> Unit) {
    val groups = settings.enabledModels().groupBy { it.first.id }.values
    if (groups.isEmpty()) {
        DropdownMenuItem(
            text = { Text("还没启用模型，去「设置」里拉取") },
            onClick = {},
        )
        return
    }
    groups.forEach { pairs ->
        val provider = pairs.first().first
        Text(
            text = provider.label,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pairs.forEach { (owner, modelId) ->
            val active = modelId == settings.model &&
                owner.baseUrl.trimEnd('/') == settings.baseUrl.trimEnd('/')
            DropdownMenuItem(
                text = { Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    if (active) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "当前",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                onClick = { onPick(owner, modelId) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyHero(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own mark, tinted to the brand colour: the launcher asset is pure
        // white for the home screen, and white on a light canvas would vanish.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(92.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text("我们开始吧", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "说一句话，它就能读屏、点击、翻文件、跑命令。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
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
private sealed interface Part {
    /** One round of thinking, with that round's own numbers. */
    data class Thinking(val text: String, val tokens: Int, val elapsedMs: Long) : Part

    /** The tool calls made after the part before this one. */
    data class Tools(val calls: List<StoredMessage>) : Part

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
                            Part.Tools((open.parts.last() as Part.Tools).calls + message)
                    } else {
                        open.parts + Part.Tools(listOf(message))
                    }
                    rows[index] = open.copy(parts = parts)
                } else {
                    // A tool result with no bubble above it (a transcript that starts
                    // mid-turn) still gets drawn, just on its own.
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = listOf(Part.Tools(listOf(message))),
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
    avatar: String,
    showReasoning: Boolean,
    expandReasoning: Boolean,
    expandTools: Boolean,
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
            avatar = avatar,
            branch = row.branch,
            usage = row.usage,
            contextWindow = contextWindow,
            message = row.message,
            expandReasoning = expandReasoning,
            expandTools = expandTools,
            onBranch = onBranch,
            onRegenerate = onRegenerate,
            onEdit = onEdit,
        )

        // A tool result with no bubble above it still renders as one, card only.
        else -> AssistantRow(
            parts = listOf(Part.Tools(listOf(row.message))),
            streaming = false,
            avatar = avatar,
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
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
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
            .clickable(onClick = onClick),
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
    /** The tool this reply is waiting on right now, so its card can say so live. */
    runningTool: String? = null,
    avatar: String = "🪶",
    branch: Branch? = null,
    usage: Usage? = null,
    /** The engine's live speed, present only while this bubble is the one streaming. */
    liveStats: StreamStats? = null,
    /** The model's window, for the context readout; 0 means it was never set. */
    contextWindow: Int = 0,
    message: StoredMessage? = null,
    expandReasoning: Boolean = false,
    expandTools: Boolean = false,
    onBranch: (String, String) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
    onEdit: (StoredMessage) -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = avatar, fontSize = 11.sp)
            }
        }
        // The reply is not a card, it is the canvas: only the tool block keeps a surface,
        // because tools are things that happened rather than the model speaking.
        Column(Modifier.weight(1f)) {
            parts.forEachIndexed { index, part ->
                val last = index == parts.lastIndex
                when (part) {
                    is Part.Thinking -> {
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
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    is Part.Tools -> {
                        ToolBundle(
                            tools = part.calls,
                            // Only the newest block can be the one waiting on a tool.
                            runningTool = runningTool.takeIf { last },
                            startExpanded = expandTools,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    is Part.Text -> {
                        SelectionContainer {
                            MarkdownText(
                                text = part.text,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (streaming && last) {
                            Spacer(Modifier.height(4.dp))
                            BlinkingCursor()
                        }
                        Spacer(Modifier.height(10.dp))
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
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (parts.isEmpty() && streaming) TypingDots()
            val settled = usage?.takeIf { it.known }
            if (settled != null || liveStats != null) {
                UsageLine(settled, liveStats, contextWindow)
            }
            if (message != null && !streaming) {
                Spacer(Modifier.height(2.dp))
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
 * The model's thinking, folded into the reply's own bubble.
 *
 * It is drawn in its own [LazyColumn] so a 10k-character chain of thought only ever
 * composes the handful of lines on screen — laying the whole string out as one
 * [Text] is what makes a long think stutter. The header stays tappable, and whether
 * it is open survives scrolling away because it is remembered per message.
 *
 * There is deliberately no surface of its own: thinking belongs to the reply, and a
 * second card inside the bubble is what made one answer read as three stacked blocks.
 */
@Composable
private fun ReasoningBlock(
    text: String,
    streaming: Boolean,
    reasoningTokens: Int = 0,
    elapsedMs: Long = 0,
    startExpanded: Boolean = false,
) {
    // The setting only decides where a block starts; once the user taps the header the
    // choice sticks to that message for as long as it stays in the list.
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    val lines = remember(text) { text.split('\n') }
    val inner = rememberLazyListState()
    LaunchedEffect(lines.size, expanded) {
        if (expanded && streaming && lines.isNotEmpty()) inner.scrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (streaming) "正在思考…" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            val meta = buildList {
                if (reasoningTokens > 0) add("$reasoningTokens tokens")
                if (elapsedMs > 0) add(formatDuration(elapsedMs))
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Rounded.KeyboardArrowUp
                } else {
                    Icons.Rounded.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(7.dp))
            LazyColumn(
                state = inner,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    if (line.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                    } else {
                        MarkdownText(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index == lines.lastIndex && streaming) BlinkingCursor()
                }
            }
        }
    }
}

/**
 * Every tool call of one turn behind one summary line: the row is the whole story until
 * the user asks for more, because ten tool calls drawn flat filled the screen with
 * arguments nobody had asked to read yet.
 *
 * Two levels on purpose. The summary opens the list of calls; a single call opens its own
 * arguments and output. Arguments are the noisy part, so they stay behind the second tap
 * even once the list is open.
 */
@Composable
private fun ToolBundle(
    tools: List<StoredMessage>,
    runningTool: String? = null,
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    var openTool by remember { mutableStateOf<String?>(null) }
    val failures = tools.count { it.toolOk == false }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = when {
                        failures > 0 -> MaterialTheme.colorScheme.error
                        runningTool != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (tools.size == 1) "调用 1 个工具" else "调用 ${tools.size} 个工具",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    // The name of the tool being waited on is the only useful thing this
                    // row can say while it is waiting.
                    runningTool != null -> {
                        Text(
                            text = runningTool,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    failures > 0 -> Text(
                        text = "$failures 个失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        text = "完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                tools.forEach { tool ->
                    ToolRow(
                        message = tool,
                        expanded = openTool == tool.id,
                        onToggle = {
                            openTool = if (openTool == tool.id) null else tool.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolRow(message: StoredMessage, expanded: Boolean, onToggle: () -> Unit) {
    val ok = message.toolOk != false
    val tone = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = message.toolName.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (ok) "完成" else "失败",
                style = MaterialTheme.typography.labelSmall,
                color = tone,
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        val args = message.toolArgs?.takeIf { it.isNotBlank() && it != "{}" }
        // Arguments are the noisy part of a tool call, so they wait behind this row's
        // own tap even after the list itself is open.
        if (expanded && args != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = args,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 14,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            val output = message.toolOutput?.takeIf { it.isNotBlank() }
            if (output != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 60,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            message.imageBase64?.let { ImageBlock(it) }
        }
    }
}

/**
 * What the reply cost, in the provider's own numbers, plus how full the context is.
 *
 * [contextWindow] is the user's own setting for the model's window; 0 means they never
 * told us, and then only the raw prompt size is shown rather than a made-up ratio.
 */
@Composable
private fun UsageLine(usage: Usage?, live: StreamStats? = null, contextWindow: Int = 0) {
    val parts = buildList {
        if (live != null) {
            // The provider only reports its counts with the last chunk, so a running
            // bubble shows the engine's estimate — the numbers that actually move.
            add("出 ${live.tokensOut}")
            if (live.thinkingTokens > 0) add("思考 ${live.thinkingTokens}")
            if (live.tokPerSec > 0) {
                add(String.format(Locale.US, "%.1f tok/s", live.tokPerSec))
            }
            if (live.elapsedMs > 0) add(formatDuration(live.elapsedMs))
        } else if (usage != null) {
            if (usage.prompt > 0) add("入 ${usage.prompt}")
            if (usage.completion > 0) add("出 ${usage.completion}")
            if (usage.reasoning > 0) add("思考 ${usage.reasoning}")
            if (usage.cached > 0) add("缓存 ${usage.cached}")
            if (usage.perSecond > 0) {
                add(String.format(Locale.US, "%.1f tok/s", usage.perSecond))
            }
            if (usage.elapsedMs > 0) add(formatDuration(usage.elapsedMs))
        }
    }
    // The prompt size of the newest round is what is actually sitting in the window now.
    // While a run streams, the engine's copy of the endpoint's last report is fresher than
    // anything already stored, so it wins; a settled reply has no live stats and falls
    // back to its own numbers.
    val context = live?.lastPromptTokens?.takeIf { it > 0 } ?: usage?.prompt ?: 0
    if (parts.isEmpty() && context <= 0) return
    // The meta line is 12sp. Written as a fraction of labelMedium rather than a bare
    // 12.sp so the user's font scale still applies to it: the theme scales every slot,
    // and a hard size here would quietly opt this one line out.
    val meta = MaterialTheme.typography.labelMedium.copy(
        fontSize = MaterialTheme.typography.labelMedium.fontSize * (12f / 13f),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString(" · "),
                style = meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (context > 0) {
            if (parts.isNotEmpty()) Spacer(Modifier.width(6.dp))
            ContextChip(tokens = context, window = contextWindow)
        }
    }
}

/** 上下文 Xk, or Xk / Yk once the window is known; it turns amber near the ceiling. */
@Composable
private fun ContextChip(tokens: Int, window: Int) {
    val full = window > 0 && tokens * 100 / window >= 80
    val tone = if (full) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
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
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = MaterialTheme.typography.labelMedium.fontSize * (12f / 13f),
            ),
            color = tone,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
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
    /** 被拦下的动作或别的会话里跑挂的 run，说给用户听的那一句。 */
    notice: String?,
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
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    // The pill floats over the transcript rather than sitting in an opaque bar, so the
    // conversation stays readable right down to the bottom edge.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 12.dp),
    ) {
        Column {
            if (running) {
                // A compression note outranks the status text: it explains why the run is
                // suddenly working from a shorter history, which "正在思考…" cannot.
                val note = stats?.note.orEmpty()
                Row(
                    modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = if (note.isNotEmpty()) scheme.tertiary else scheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = note.ifEmpty { status.ifBlank { "正在思考…" } },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (note.isNotEmpty()) scheme.tertiary else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            notice?.let { text ->
                Row(
                    modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = scheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (running) {
                // 插话 is typed into this same field and sent with enter; a second box
                // below it made it ambiguous which one starts a run and which joins one.
                Spacer(Modifier.height(2.dp))
            }
            attachment?.let {
                AttachmentChip(attachment = it, onRemove = onClearAttachment)
                Spacer(Modifier.height(8.dp))
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                // Light stays pure white so the pill does not read as a grey fill; in dark
                // `surface` IS the canvas, so the pill steps one surface up to stay a
                // visible object rather than a shadow with nothing inside it.
                color = if (LocalDarkTheme.current) scheme.surfaceContainer else scheme.surface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    // Centred, not bottom-aligned: the icons are 48dp while a single line
                    // of bodyLarge plus its padding is 48dp too, so the two line up.
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "添加图片或文件",
                                tint = scheme.onSurfaceVariant,
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
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier
                            .weight(1f)
                            // 12dp + 24dp line + 12dp = the 48dp of the icon buttons
                            // beside it, so one line of text sits dead centre.
                            .padding(vertical = 12.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) onFocused()
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
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
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    IconButton(
                        onClick = onMic,
                        enabled = !running,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = "语音输入",
                            tint = if (running) scheme.onSurfaceVariant else scheme.onSurfaceVariant,
                        )
                    }
                    // 插话发送: only while a run is live and there is something to say —
                    // an always-present second button would be a dead control most of
                    // the time, and the stop button beside it still owns the slot.
                    if (running && input.isNotBlank()) {
                        FilledIconButton(
                            onClick = { if (onSteer(input.trim())) onInput("") },
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = scheme.primaryContainer,
                                contentColor = scheme.onPrimaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = "插话发送",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    FilledIconButton(
                        onClick = { if (running) onStop() else onSend() },
                        enabled = running || input.isNotBlank() || attachment != null,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (running) scheme.primary else scheme.surfaceVariant,
                            contentColor = if (running) scheme.onPrimary else scheme.onSurfaceVariant,
                            disabledContainerColor = scheme.surfaceVariant,
                            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Icon(
                            imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (running) "停止" else "发送",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** What the next question carries along: an image for the model, or a file on disk. */
private data class Attachment(
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
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small),
        )
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
