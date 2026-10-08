package com.lingshu.agent.ui.chat

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.data.Assistant
import com.lingshu.agent.data.Conversation
import com.lingshu.agent.data.ProviderConfig
import com.lingshu.agent.data.Roles
import com.lingshu.agent.data.StoredMessage
import com.lingshu.agent.data.Transcript
import com.lingshu.agent.core.Core
import com.lingshu.agent.ui.assistant.AssistantSheet
import com.lingshu.agent.ui.components.LingshuOrb
import com.lingshu.agent.ui.components.LingshuTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val conversations by viewModel.conversations.collectAsState()
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }

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

    // Tool-only assistant turns carry no text of their own, so their tool calls are
    // folded into the bubble above them instead of standing alone in the transcript.
    // Only the branch the user picked is drawn; the other answers stay on disk.
    val visible = remember(state.messages, state.activeRuns, state.running) {
        groupRows(state.messages, state.activeRuns, state.running)
    }
    val live = state.streaming.isNotEmpty() || state.reasoning.isNotEmpty()
    val assistant = settings.assistant(state.assistantId) ?: settings.activeAssistant()

    val itemCount = visible.size + if (live) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChatHeader(
                title = state.title,
                subtitle = listOf(
                    assistant?.label ?: "灵枢",
                    if (settings.configured) settings.shortModel else "还没配置模型",
                ).joinToString(" · "),
                assistant = assistant,
                settings = settings,
                conversations = conversations,
                onOpenAssistants = { assistantsOpen = true },
                onOpen = { viewModel.openConversation(it) },
                onDelete = { viewModel.deleteConversation(it) },
                onNew = { viewModel.newConversation() },
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Composer(
                input = input,
                onInput = { input = it },
                running = state.running,
                status = state.status,
                onSend = { if (viewModel.send(input)) input = "" },
                onStop = { viewModel.stop() },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { message ->
                ErrorCard(message = message, onDismiss = { viewModel.dismissError() })
            }
            if (visible.isEmpty() && !live) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyHero(onPick = { input = it })
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(visible, key = { _, row -> row.message.id }) { _, row ->
                        if (row.showTimeLabel) TimeLabel(row.message.timestamp)
                        MessageRow(
                            row = row,
                            onBranch = { turnId, runId -> viewModel.switchBranch(turnId, runId) },
                            onRegenerate = { viewModel.regenerate() },
                        )
                    }
                    if (live) {
                        item(key = "streaming") {
                            AssistantRow(
                                text = state.streaming,
                                reasoning = state.reasoning,
                                streaming = true,
                                avatar = assistant?.face ?: "🪶",
                            )
                        }
                    }
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
}

@Composable
private fun ChatHeader(
    title: String,
    subtitle: String,
    assistant: Assistant?,
    settings: AppSettings,
    conversations: List<Conversation>,
    onOpenAssistants: () -> Unit,
    onOpen: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onNew: () -> Unit,
    onPickModel: (ProviderConfig, String) -> Unit,
) {
    var historyOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    LingshuTopBar(
        title = title,
        subtitle = subtitle,
        leading = {
            // The face in the corner is the way into the assistant picker, which is
            // why it is a button rather than decoration.
            Surface(
                onClick = onOpenAssistants,
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = assistant?.face ?: "🪶", fontSize = 19.sp)
                }
            }
        },
        actions = {
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
            Box {
                IconButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Filled.History, contentDescription = "历史会话")
                }
                DropdownMenu(
                    expanded = historyOpen,
                    onDismissRequest = { historyOpen = false },
                ) {
                    if (conversations.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("还没有会话") },
                            onClick = { historyOpen = false },
                        )
                    }
                    conversations.forEach { conversation ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = conversation.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { onDelete(conversation) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            },
                            onClick = {
                                onOpen(conversation)
                                historyOpen = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新会话")
            }
        },
    )
}

/** The model name in the header; tapping it opens the switcher. */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 84.dp),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
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
                            imageVector = Icons.Filled.Check,
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
        LingshuOrb(size = 136.dp)
        Spacer(Modifier.height(10.dp))
        Text("灵枢", style = MaterialTheme.typography.headlineSmall)
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
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * One bubble of the transcript: the message that owns it, the tool calls it made,
 * and whether it opens a new time bucket.
 */
private data class ChatRow(
    val message: StoredMessage,
    val showTimeLabel: Boolean,
    val tools: List<StoredMessage> = emptyList(),
    val branch: Branch? = null,
)

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
    val lastQuestion = shown.lastOrNull { it.role == Roles.USER }?.id.orEmpty()

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
    var bucket: MutableList<StoredMessage>? = null

    shown.forEach { message ->
        when {
            message.role == Roles.TOOL -> {
                val open = bucket
                if (open != null) {
                    open.add(message)
                } else {
                    // A tool result with no bubble above it (a transcript that starts
                    // mid-turn) still gets drawn, just on its own.
                    rows += ChatRow(message, needsTimeLabel(previous, message))
                    previous = message
                }
            }

            message.role == Roles.ASSISTANT && message.isVisible() -> {
                val tools = mutableListOf<StoredMessage>()
                rows += ChatRow(message, needsTimeLabel(previous, message), tools, branchFor(message))
                previous = message
                bucket = tools
            }

            // A text-less assistant turn: nothing to draw, and its tools fall through
            // to the bubble that is already open.
            message.role == Roles.ASSISTANT -> Unit

            message.isVisible() -> {
                rows += ChatRow(message, needsTimeLabel(previous, message))
                previous = message
                bucket = null
            }

            else -> Unit
        }
    }
    return rows
}

@Composable
private fun MessageRow(
    row: ChatRow,
    onBranch: (String, String) -> Unit,
    onRegenerate: () -> Unit,
) {
    when (row.message.role) {
        Roles.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            UserBubble(row.message)
        }

        Roles.ASSISTANT -> AssistantRow(
            text = row.message.text,
            reasoning = row.message.reasoning.orEmpty(),
            streaming = false,
            tools = row.tools,
            branch = row.branch,
            onBranch = onBranch,
            onRegenerate = onRegenerate,
        )

        // A tool result with no bubble above it still renders as one, chips only.
        else -> AssistantRow(text = "", reasoning = "", streaming = false, tools = listOf(row.message))
    }
}

@Composable
private fun UserBubble(message: StoredMessage) {
    Surface(
        modifier = Modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
            message.imageBase64?.let { ImageBlock(it) }
            if (message.text.isNotBlank()) {
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun AssistantRow(
    text: String,
    streaming: Boolean,
    reasoning: String = "",
    tools: List<StoredMessage> = emptyList(),
    avatar: String = "🪶",
    branch: Branch? = null,
    onBranch: (String, String) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = avatar, fontSize = 13.sp)
            }
        }
        // The branch switcher hangs under the bubble, so the width cap belongs to the
        // whole column rather than to the bubble on its own.
        Column(Modifier.widthIn(max = 320.dp)) {
            Surface(
                shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                    if (reasoning.isNotBlank()) {
                        // The thinking is only "live" while no answer text has arrived yet.
                        ReasoningBlock(reasoning, streaming = streaming && text.isBlank())
                        if (text.isNotBlank() || streaming || tools.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    when {
                        text.isBlank() && streaming -> TypingDots()
                        text.isBlank() -> Unit
                        else -> {
                            SelectionContainer {
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (streaming) {
                                Spacer(Modifier.height(4.dp))
                                BlinkingCursor()
                            }
                        }
                    }
                    // The tools this reply ran live inside the same bubble, so a turn
                    // reads as one block instead of a bubble followed by loose chips.
                    if (tools.isNotEmpty()) {
                        if (text.isNotBlank() || reasoning.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                        }
                        tools.forEachIndexed { index, tool ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            ToolChip(tool)
                        }
                    }
                }
            }
            if (branch != null) {
                Spacer(Modifier.height(6.dp))
                BranchBar(branch = branch, onBranch = onBranch, onRegenerate = onRegenerate)
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
private fun BranchBar(
    branch: Branch,
    onBranch: (String, String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (branch.runs.size > 1) {
            val atStart = branch.index <= 0
            val atEnd = branch.index >= branch.runs.lastIndex
            BranchArrow(
                icon = Icons.Filled.ChevronLeft,
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
                icon = Icons.Filled.ChevronRight,
                label = "下一个回答",
                enabled = !atEnd,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index + 1]) },
            )
        }
        if (branch.canRegenerate) {
            BranchArrow(
                icon = Icons.Filled.Refresh,
                label = "重新回答",
                enabled = true,
                onClick = onRegenerate,
            )
            Text(
                text = "重新回答",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
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
 * The model's thinking. Open while it streams and collapsed once the answer is
 * there, but tappable either way so a long chain of thought never buries the reply.
 */
@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "正在思考…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 60,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
private fun ToolChip(message: StoredMessage) {
    var expanded by remember { mutableStateOf(false) }
    val ok = message.toolOk != false
    val tone = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
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
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val args = message.toolArgs?.takeIf { it.isNotBlank() && it != "{}" }
            if (args != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = args,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 14 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val output = message.toolOutput?.takeIf { it.isNotBlank() }
            if (output != null && expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
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

@Composable
private fun TimeLabel(timestamp: Long) {
    Text(
        text = formatTime(timestamp),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    running: Boolean,
    status: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 10.dp),
        ) {
            if (running) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = status.ifBlank { "正在思考…" },
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(26.dp),
                    color = scheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(onClick = onMic, enabled = !running) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "语音输入",
                                tint = if (running) scheme.onSurfaceVariant else scheme.primary,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInput,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                            cursorBrush = SolidColor(scheme.primary),
                            maxLines = 5,
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) {
                                        Text(
                                            text = "让灵枢做点什么…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (running) onStop() else onSend() },
                    enabled = running || input.isNotBlank(),
                    modifier = Modifier.size(50.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (running) scheme.error else scheme.primary,
                    ),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (running) "停止" else "发送",
                    )
                }
            }
        }
    }
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
