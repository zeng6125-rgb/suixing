package com.lingshu.agent.ui.chat

import android.app.Activity
import android.graphics.BitmapFactory
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingshu.agent.data.Roles
import com.lingshu.agent.data.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }

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

    val itemCount = state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { historyOpen = true }) {
                        Icon(Icons.Filled.History, contentDescription = "历史会话")
                    }
                    IconButton(onClick = { viewModel.newConversation() }) {
                        Icon(Icons.Filled.Add, contentDescription = "新会话")
                    }
                    DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
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
                                        conversation.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { viewModel.deleteConversation(conversation) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                                    }
                                },
                                onClick = {
                                    viewModel.openConversation(conversation)
                                    historyOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (state.running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = state.status.ifBlank { "正在思考…" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(
                            onClick = {
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
                                    scope.launch {
                                        snackbarHostState.showSnackbar("没有可用的语音识别应用")
                                    }
                                }
                            },
                            enabled = !state.running,
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = "语音输入")
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("让灵枢做点什么…") },
                            maxLines = 5,
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (state.running) {
                                    viewModel.stop()
                                } else if (viewModel.send(input)) {
                                    input = ""
                                }
                            },
                            enabled = state.running || input.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = if (state.running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (state.running) "停止" else "发送",
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.messages.isEmpty() && state.streaming.isEmpty()) {
                EmptyState(Modifier.align(Alignment.Center))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (state.streaming.isNotEmpty()) {
                    item(key = "streaming") {
                        AssistantBubble(text = state.streaming, streaming = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("灵枢", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "说一句话，灵枢就能读屏、点击、翻文件、跑命令。\n先去「能力」页把权限打开。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: StoredMessage) {
    when (message.role) {
        Roles.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                modifier = Modifier.widthIn(max = 320.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    message.imageBase64?.let { ImageBlock(it) }
                    if (message.text.isNotBlank()) Text(message.text)
                }
            }
        }

        Roles.ASSISTANT -> Row(Modifier.fillMaxWidth()) {
            AssistantBubble(text = message.text, streaming = false)
        }

        Roles.TOOL -> ToolCard(message)

        else -> Unit
    }
}

@Composable
private fun AssistantBubble(text: String, streaming: Boolean) {
    Card(
        modifier = Modifier.widthIn(max = 340.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (text.isNotBlank()) {
                Text(text)
            } else if (streaming) {
                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToolCard(message: StoredMessage) {
    var expanded by remember { mutableStateOf(false) }
    val ok = message.toolOk != false
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.toolName.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (ok) "完成" else "失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.toolArgs?.takeIf { it.isNotBlank() && it != "{}" }?.let { args ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = args,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 12 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            message.toolOutput?.takeIf { it.isNotBlank() }?.let { output ->
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (expanded) output else output.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) 60 else 8,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded && output.length > 400) {
                    Text(
                        text = "点击展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            message.imageBase64?.let { ImageBlock(it) }
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
