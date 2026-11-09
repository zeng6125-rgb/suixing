package com.suixing.agent.ui.settings

import com.suixing.agent.data.SecretBox
import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.CompletionDot
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.suixing.agent.ui.components.SettingsSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.SubagentModelSelection
import com.suixing.agent.data.resolve
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.decrypted
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.label
import com.suixing.agent.data.COMPRESS_MAX_PERCENT
import com.suixing.agent.data.COMPRESS_MIN_PERCENT
import com.suixing.agent.data.COMPRESS_STEP_PERCENT
import com.suixing.agent.llm.MAX_RETRY_ATTEMPTS
import com.suixing.agent.llm.MAX_RETRY_DELAY_SECONDS
import com.suixing.agent.llm.MIN_RETRY_DELAY_SECONDS
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LocalNetworkAccess
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.drawer.DrawerOrder
import com.suixing.agent.ui.drawer.DrawerOrderStore
import com.suixing.agent.ui.drawer.drawerSections
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.RowChevron
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.theme.AppearancePalette
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@Composable
internal fun ConversationManagerScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by Core.chats.conversations.collectAsStateWithLifecycle()
    val unreadCompleted by Core.conversationReads.unread.collectAsStateWithLifecycle()
    val runningIds by remember {
        Core.engine.runsFlow.map { it.keys }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptySet())
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingExport by remember { mutableStateOf<Conversation?>(null) }
    // 详情弹窗（用户：「会话管理要有会话的文件，可以看会话详细日志、大小等等」）：文件路径/大小/
    // 修改时间 + 原始 JSON 尾部。文件信息在 IO 上读，弹窗开着才读一次。
    var pendingInfo by remember { mutableStateOf<Conversation?>(null) }
    var chatFileInfo by remember { mutableStateOf<ChatRepository.ChatFile?>(null) }
    // 手动顺序只读（这一页不提供拖动）：读出来是为了让置顶区与抽屉长得一样 ——
    // 同一枚置顶在抽屉里排在最上面、在这里却按时间散着，这正是用户说的「置顶逻辑没理清」。
    val orderStore = remember(context) { DrawerOrderStore(context) }
    var drawerOrder by remember { mutableStateOf(DrawerOrder()) }
    LaunchedEffect(Unit) { drawerOrder = orderStore.load() }
    LaunchedEffect(pendingInfo) {
        // 换一条会话（或关掉弹窗）先把上一份文件信息清掉：读盘在 IO 上要几十毫秒，而弹窗
        // 在点下去那一帧就已经画出来了 —— 留着旧的会先闪一眼**上一条会话**的路径与大小
        // （issue #55 5.27：chatFileInfo 切会话不复位）。
        chatFileInfo = null
        val chat = pendingInfo ?: return@LaunchedEffect
        // **走仓库的 chatFile**：它知道日志在 `chats/store/<id>.<gen>.jsonl`。
        // 原来这里读旧的 `chats/<id>.json`，迁移之后那个文件不存在 ⇒ 对每个会话都显示
        // 「这个会话在磁盘上没有对应的文件」（用户实测）。
        chatFileInfo = withContext(Dispatchers.IO) { Core.chats.chatFile(chat.id) }
    }
    // 系统选择器（SAF）：不需要 FileProvider、不改 manifest，用户自己决定存到哪。
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val chat = pendingExport
        pendingExport = null
        if (uri == null || chat == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // **不能直接拷 `chats/<id>.json`**：迁移之后它不存在了（导出必然失败）。
                    // 也不能拷 JSONL 日志 —— 用户打不开。这里序列化成整份 JSON。
                    val text = Core.chats.exportJson(chat.id) ?: error("读不到会话内容")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("打不开目标文件")
                }.onFailure {
                    if (it is CancellationException) throw it
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "已导出「${chat.title}」" else "导出失败，换个位置再试")
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.CHATS.title, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // 排序/过滤只依赖这两个输入：算一次，别跟着每次重组重来。
        val sections = remember(conversations, drawerOrder) { drawerSections(conversations, drawerOrder) }
        val archivedList = remember(conversations) {
            conversations.filter { it.archived }.sortedByDescending { it.updatedAt }
        }
        // 活动会话的顺序（置顶区在前、普通区在后，与抽屉共用同一套排序）。
        // **提到 LazyColumn 外面**：下面要把它们的字节数一次性批量取好，见 sizes。
        val active = remember(sections) { sections.pinned + sections.normal }
        // **字节数一次批量取**（性能审计）：原来每行一个 LaunchedEffect 调 chatSize，
        // 那是每行一次 `withContext(Dispatchers.IO)` + 一次 `ChatRepository.lock`（那把锁和
        // 会话保存、流式写盘共用）；滚动一次就抢 N 次全局锁，而且 `remember` 在 item 作用域，
        // 滚出去再滚回来又重读一遍。现在一次线程切换、一次锁、一次遍历。
        var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        LaunchedEffect(active) {
            sizes = Core.chats.chatSizes(active.map { it.id })
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            // 与抽屉共用同一套排序（drawerSections）：置顶在前、置顶区按手动顺序、普通区按
            // updatedAt。这一页原来是纯时间序、也不显示置顶 —— 同一枚置顶两个页面两个样子。
            // `active` 已在 LazyColumn 外面算好（见 sizes 的批量取）。
            val archived = archivedList
            if (active.isEmpty() && archived.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有会话",
                        subtitle = "聊过一次之后，会话会出现在这里，可以打开、导出或删除。",
                        modifier = Modifier.padding(top = 56.dp),
                    )
                }
            } else {
                items(
                    active,
                    key = { it.id },
                    contentType = { "conversation-row" },
                ) { conversation ->
                    // **读 store 里的日志**（#40 之后磁盘上已无 `<id>.json`，旧路径恒返回 -1）
                    // ⇒ 副标题里的"体积"一直不显示。`chatFile` 给的就是 meta 指向的那份日志。
                    // 字节数已在页面级**批量取好**（sizes），这里只查表，不再每行做 IO。
                    val bytes = sizes[conversation.id] ?: -1L
                    // 副标题是一次相对时间格式化（DateUtils）+ 一次字节数格式化。按真正影响输出
                    // 的三个字段记住它：别跟着每次重组重来。文案、分隔符与原来逐字相同。
                    val subtitle = remember(
                        conversation.id,
                        conversation.updatedAt,
                        conversation.messageCount,
                        bytes,
                    ) {
                        "${lastUpdated(conversation.updatedAt)} · " +
                            "${conversation.messageCount} 条消息" +
                            (if (bytes >= 0) " · ${humanBytes(bytes)}" else "")
                    }
                    ConversationRow(
                        title = conversation.title,
                        pinned = conversation.pinned,
                        completedUnread = conversation.id in unreadCompleted && conversation.id !in runningIds,
                        subtitle = subtitle,
                        onOpen = { onOpenConversation(conversation.id) },
                        onInfo = { pendingInfo = conversation },
                        onExport = {
                            pendingExport = conversation
                            exporter.launch(exportFileName(conversation.title))
                        },
                        onDelete = { pendingDelete = conversation },
                    )
                }
                // 已归档：抽屉里看不到它们（归档就是把它从那份列表里收起来），这一页是唯一的
                // 入口 —— 用户指定「已归档的显示搞到会话管理那里」。分组排在活动会话下面，
                // 每行多一枚「取消归档」，其余动作（详情/导出/删除）与活动行一致。
                if (archived.isNotEmpty()) {
                    item(key = "archived-header", contentType = "header") {
                        Text(
                            text = "已归档（${archived.size}）",
                            style = SettingsStyle.sectionTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        archived,
                        key = { it.id },
                        contentType = { "conversation-row" },
                    ) { conversation ->
                        // 同活动会话行：这里的相对时间也只在 updatedAt / 条数变了时才重算。
                        val subtitle = remember(
                            conversation.id,
                            conversation.updatedAt,
                            conversation.messageCount,
                        ) {
                            "已归档 · ${lastUpdated(conversation.updatedAt)} · " +
                                "${conversation.messageCount} 条消息"
                        }
                        ConversationRow(
                            title = conversation.title,
                            completedUnread = conversation.id in unreadCompleted && conversation.id !in runningIds,
                            // **用 meta 的 messageCount**：`messages` 是空列表
                            // （`load()` 只读 meta），拿它算会显示成「0 条消息」。
                            subtitle = subtitle,
                            onOpen = { onOpenConversation(conversation.id) },
                            onInfo = { pendingInfo = conversation },
                            onExport = {
                                pendingExport = conversation
                                exporter.launch(exportFileName(conversation.title))
                            },
                            onDelete = { pendingDelete = conversation },
                            onUnarchive = {
                                scope.launch {
                                    Core.chats.setArchived(conversation.id, false)
                                    snackbar.showSnackbar("已取消归档「${conversation.title}」")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { chat ->
        SuixingDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个会话？") },
            text = {
                Text(
                    "「${chat.title}」的聊天记录（${chat.messageCount} 条消息）会被永久删除，" +
                        "不能撤销。工作区里的文件不受影响，用量统计继续保留。",
                )
            },
            confirmButton = { dialog ->
                TextButton(
                    onClick = {
                        dialog.closeThen {
                            pendingDelete = null
                            scope.launch {
                                try {
                                    Core.chats.delete(chat.id)
                                    snackbar.showSnackbar("已删除「${chat.title}」")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    snackbar.showSnackbar(failure.message ?: "会话删除失败，请重试。")
                                }
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { dialog -> TextButton(onClick = { dialog.closeThen { pendingDelete = null } }) { Text("取消") } },
        )
    }
    pendingInfo?.let { chat ->
        ChatInfoDialog(info = chatFileInfo, onDismiss = { pendingInfo = null })
    }
}
