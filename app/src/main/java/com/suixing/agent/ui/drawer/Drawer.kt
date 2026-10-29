package com.suixing.agent.ui.drawer

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.Conversation
import com.suixing.agent.ui.drawer.DrawerOrder
import com.suixing.agent.ui.drawer.DrawerOrderStore
import com.suixing.agent.ui.assistant.AssistantAvatar
import com.suixing.agent.ui.components.SettingsSwitch
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.drawer.drawerSections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/** 抽屉面板宽度（ModalDrawerSheet 与收起残影共用）。 */
internal val DRAWER_SHEET_WIDTH = 300

/**
 * 抽屉收起：**统一一条规格**，160ms + `FastOutLinearInEasing`（用户指定：260 → 210 → 180 → 170 → 160）。
 *
 * 之前遮罩那条和助手那条不一样，原因是有两条关闭入口：
 *  · 我们自己的 `closeDrawer()`（点助手、返回键、会话行）—— 参数由我们传；
 *  · M3 内部 `Scrim` 的 `onClose = { drawerState.close() }`
 *    （`NavigationDrawerKt$ModalNavigationDrawer$2$2$1$1`）—— 参数来自
 *    `DrawerState.anchoredDraggableMotionSpec`，App 模块拿不到（`$material3` 内部可见）。
 * 所以点遮罩始终是库默认 `TweenSpec(256ms)` + `FastOutSlowInEasing`，和助手那条不同。
 *
 * 那条内部规格**改不了**（试过反射：`ModalNavigationDrawer` 每次重组都会把它重写，
 * 见 `NavigationDrawerKt` 里 `lambda$11$lambda$10` 的三处 set ⇒ 下一帧就失效）。
 * 所以改成**遮罩由我们这层接管**：`scrimColor = Color.Transparent` 关掉库的视觉，
 * 另起一层只覆盖面板右侧的自带遮罩（见 ModalNavigationDrawer 之后那段），点击走
 * `closeDrawer()` ⇒ 与助手 / 返回 / 会话行同一条 [DRAWER_CLOSE_SPEC]。
 * 打开不受影响：打开是显式传 spec 的（260ms 减速进场）。
 */
private const val DRAWER_CLOSE_MS = 160

/** 抽屉收起规格（四条关闭路径共用）。`FiniteAnimationSpec` 不可变，顶层单例即可。 */
internal val DRAWER_CLOSE_SPEC: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
    tween(DRAWER_CLOSE_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing)

/**
 * The left drawer: conversations on top, everything else at the bottom.
 *
 * 面板本体是 M3 的 [ModalDrawerSheet]（用户点名「不要自绘」）：拖动跟手、遮罩与位移都归库。
 * 圆角、底色、宽度、elevation 全部显式覆盖成自绘那版的值——换库不许换设计。
 */
@Composable
internal fun Drawer(
    /** 抽屉是否开着（= drawerState.isOpen）：只用来在拉开时收起键盘。 */
    open: Boolean,
    route: String,
    conversations: List<Conversation>,
    activeId: String,
    runningId: Set<String>,
    onNew: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onRenameConversation: (Conversation, String) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onToggleArchive: (Conversation) -> Unit,
    consoleOn: Boolean,
    onToggleConsole: () -> Unit,
    onNavigate: (String) -> Unit,
    onAssistants: () -> Unit,
) {
    val unreadCompleted by Core.conversationReads.unread.collectAsStateWithLifecycle()
    // 左上角的标识跟着**当前助手**走：设置过头像就画头像，没头像仍画「随行」标。
    //
    // **只订阅"当前助手"这一个值**（原来订阅整份 settings 流）：抽屉里只有左上角那枚标识
    // 需要它，而整份流一旦有任何设置变化（切 provider、改窗口大小…）都会让**整个抽屉重组** ——
    // 5 个会话行、每行的 `animateItem`、每行的拖动手势全部重跑一遍。抽屉一开就掉帧，
    // 而抽屉里其实只画了 5 行会话。
    val drawerAssistant by remember {
        Core.settings.flow.map { settings -> settings.activeAssistant() }
    }.collectAsStateWithLifecycle(initialValue = null)
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Every delete entry in the drawer funnels through this one confirmation: a swipe
    // of the thumb on a row must not be able to erase a conversation outright.
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingRename by remember { mutableStateOf<Conversation?>(null) }
    // 长按出来的菜单：只记「哪一行的菜单开着」，于是抽屉里同时最多只有一个菜单。
    var menuFor by remember { mutableStateOf<String?>(null) }
    // 抽屉的手动顺序：置顶区/普通区各一张 id 表（见 DrawerOrderStore 的注释）。
    // 它是查看偏好而不是会话数据，所以既不挂在 Conversation 上，也不进存储格式。
    val orderStore = remember(context) { DrawerOrderStore(context) }
    val orderState = remember { mutableStateOf(DrawerOrder()) }
    // 排序模式：抽屉头部那枚把手开关。开着时行尾的垃圾桶换成拖拽把手 ——
    // 长按行仍然是菜单，两个手势各占一处，不互相抢。
    var sortMode by remember { mutableStateOf(false) }
    val draggingState = remember { mutableStateOf<String?>(null) }
    val dragPreview = remember { mutableStateOf<DrawerSections?>(null) }
    val dragTracker = remember { DrawerDragTracker() }
    val listState = rememberLazyListState()
    // 每行上次看到的 updatedAt：数值变大 = 那条会话来了新消息 ⇒ 把它从普通表里摘掉，
    // 它就按 updatedAt 冒回普通区顶部（用户：「新会话照常出现在普通会话上面」）。
    val lastSeen = remember { HashMap<String, Long>() }
    var orderDirty by remember { mutableStateOf(false) }
    // 手势回调里要读最新的会话列表与分区，但 pointerInput 的 block 只能建一次
    // （中途换实例会把进行中的手势取消），所以这两份最新值走 rememberUpdatedState 转发。
    val conversationsNow = rememberUpdatedState(conversations)
    // **先 remember 再转发**（性能审计 #5）：原来把 `drawerSections(...)` 写在
    // `rememberUpdatedState` 的参数位 —— 那只包住了「转发最新值」，没包住计算，
    // 于是外壳每次重组都白算一遍 filter + associateBy + 2×sortedBy（O(n log n)）。
    // 同文件 :1001 已是正确写法。
    val baseSections = remember(conversations, orderState.value) {
        drawerSections(conversations, orderState.value)
    }
    val sections = dragPreview.value ?: baseSections
    val sectionsNow = rememberUpdatedState(sections)
    LaunchedEffect(conversations, orderState.value) {
        // Real updates take precedence over a gesture's frozen preview: a deleted or
        // completed conversation must not stay drawable or overwrite its new baseline.
        dragPreview.value = null
        draggingState.value = null
    }
    // 导出与「设置 → 会话」页同一套：系统选择器（SAF），不碰 FileProvider、不改 manifest。
    var pendingExport by remember { mutableStateOf<Conversation?>(null) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val chat = pendingExport
        pendingExport = null
        if (uri == null || chat == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // **从 store 导出**（#40 之后磁盘上已经没有 `<id>.json`）：原来这里直接读旧文件，
                    // 旧格式删掉后它必然不存在 ⇒ 每次导出都失败。`exportJson` 会拼出完整 JSON。
                    val text = Core.chats.exportJson(chat.id) ?: error("读不到会话内容")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
                    } ?: error("打不开目标文件")
                }.isSuccess
            }
            // 抽屉里没有 SnackbarHost（它是一块面板，不是一个 Scaffold），所以这里用 Toast：
            // 「复制/导出到底成没成」必须当场看见，否则用户只能靠猜。
            Toast.makeText(
                context,
                if (ok) "已导出「${chat.title}」" else "导出失败，换个位置再试",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) {
            // Five 56dp rows plus the head do not fit above the keyboard on a short
            // screen, and the list column can only shrink to zero — the bottom rows,
            // 设置 included, would sit off-screen with nothing able to scroll them.
            // Dropping the keyboard gives the panel the height it was laid out for.
            keyboard?.hide()
        } else {
            // 抽屉一关，排序模式就失效：它是一次性模式，不该跨次拉开残留
            // （用户：「进行了长按等其它操作（例如关闭侧边栏），自动退出拖动模式」）。
            sortMode = false
            draggingState.value = null
            dragPreview.value = null
            // 抽屉一关，长按菜单也不该留在屏幕上（它是 focusable = false 的弹窗，不会自己消失）。
            menuFor = null
        }
    }

    // 上次手动排的顺序。读失败 = 空表 = 退回时间序：偏好文件坏了不该拦着人看会话列表。
    LaunchedEffect(Unit) { orderState.value = orderStore.load() }

    // 落盘分两个入口：拖动松手、切换置顶是明确操作，立刻写；「新消息回顶」只挪基线，走 debounce。
    val persistOrder: () -> Unit = remember {
        {
            val snapshot = orderState.value.prunedTo(conversationsNow.value.map { it.id }.toSet())
            scope.launch { orderStore.save(snapshot) }
        }
    }

    // 会话列表一变就做两件事：剔除已删除的 id（残留 id 会在列表里占位，看着像排序没生效），
    // 以及把「updatedAt 变大」的那几行从普通表里摘掉。
    LaunchedEffect(conversations) {
        val known = conversations.map { it.id }.toHashSet()
        lastSeen.keys.retainAll(known)
        if (conversations.isEmpty()) return@LaunchedEffect
        val current = orderState.value
        var next = current.prunedTo(known)
        val bumped = conversations
            .filter { chat -> lastSeen[chat.id]?.let { chat.updatedAt > it } == true }
            .map { it.id }
        conversations.forEach { lastSeen[it.id] = it.updatedAt }
        val normalIds = next.normal.toHashSet()
        val dropped = bumped.filterTo(HashSet()) { it in normalIds }
        if (dropped.isNotEmpty()) next = next.copy(normal = next.normal.filterNot { it in dropped })
        if (next != current) orderState.value = next
        if (dropped.isNotEmpty()) orderDirty = true
    }

    // 「新消息回顶」攒 1.5s 落一次盘：应用被杀最多丢这一次位置修正；用户拖出来的顺序是
    // 松手即写，不会丢。
    LaunchedEffect(orderDirty) {
        if (!orderDirty) return@LaunchedEffect
        delay(1500)
        orderStore.save(orderState.value.prunedTo(conversationsNow.value.map { it.id }.toSet()))
        orderDirty = false
    }

    // 「排序模式是一次性模式」：任何非拖动的交互都从这一个口子关掉它，
    // 免得一半路径忘了关、模式残留到下一次拉开抽屉。
    val exitSortMode: () -> Unit = {
        sortMode = false
        draggingState.value = null
        dragPreview.value = null
    }

    // 面板：库负责拖动/回弹/遮罩/位移，视觉参数逐条覆盖回自绘那版——
    // 宽度 300dp（库的 sizeIn 区间是 240–360dp）、圆角只有右侧两角 30dp（库默认四角 16dp）、
    // 底色 surfaceContainerHigh（库默认 surfaceContainerLow）、elevation 0（库默认 16dp 的
    // tonal elevation，那会把底色再调亮一档，用户看了几个版本的颜色就变了）。
    // windowInsets 归零：状态栏/导航栏/键盘的内边距一直由下面这层 Column 自己管，库那层
    // 再补一次就是双重内边距。
    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            // **宽度只有一处定义**（`DRAWER_SHEET_WIDTH`）：透明点击层的裁剪边界按它算，
            // 两处各写一个魔数会在改宽时错位（点击层盖住面板 / 留缝）。
            .width(DRAWER_SHEET_WIDTH.dp),
        drawerShape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
        drawerContainerColor = scheme.surfaceContainerHigh,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                // **不要 imePadding()**（issue #1：长按改名弹窗拉起输入法时，背后侧边栏变空）：
                // manifest 是 adjustNothing（窗口不随键盘缩放），但 IME inset 仍会报给 Activity，
                // `imePadding()` 于是给这个 fillMaxHeight 的抽屉底部垫上整块键盘高度
                // （约 1000px）⇒ 内容区被压到接近 0 ⇒ 列表看起来被清空。
                // 抽屉是整高的侧边面板，键盘盖住底部时列表本身可以滚，不需要让位。
                .padding(vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (drawerAssistant?.avatarPath.isNullOrBlank()) {
                    SuixingMark(size = 30.dp)
                } else {
                    AssistantAvatar(assistant = drawerAssistant, size = 30.dp)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("随行", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "跟着你走的手机智能体",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // 排序开关：把手图标 = 「拖」这件事本身。开着时行尾的垃圾桶换把手，
                // 于是「拖」与「长按菜单」各占一处，不用在同一个手势里二选一。
                IconButton(
                    onClick = { sortMode = !sortMode },
                    modifier = Modifier.size(38.dp),
                ) {
                    DrawerGripIcon(
                        tint = if (sortMode) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onNew, modifier = Modifier.size(38.dp)) {
                    // The same glyph the chat header's compose button draws, at the same
                    // 22dp: the drawer and the bar are two doors to one action, and they
                    // read as two until the drawing is shared.
                    Icon(
                        painter = painterResource(R.drawable.ic_compose),
                        contentDescription = "新对话",
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = scheme.outlineVariant,
            )
            Spacer(Modifier.height(6.dp))

            // 置顶区在上、普通区在下（用户：「置顶的就是最上面的呀」）。顺序来自两张 id 表：
            // 置顶区只在置顶表里挪；普通区「新会话/新消息在前 + 手动基线」；跨区拖动在
            // reorderWithinSection 里被直接拒掉，所以怎么拖都越不过置顶那条线。
            // 排序要 filter + associateBy + 两次 sortedBy：不该每次重组都重算。
            // 埋点：抽屉每重组一次记一行（看打开抽屉时重组了多少次）。

            // 两个区块共用同一行（只差顺序，不差样子）：抽成局部 lambda，免得把二十来行
            // 回调抄两遍。animateItem 只在 item 作用域里拿得到，所以由调用处传进来。
            val drawerRow: @Composable (Conversation, Modifier) -> Unit = { conversation, anim ->
                // 把手的拖动手势建一次就固定住：手势进行中如果 block 换实例，Compose 会把
                // 这次拖动取消，于是「拖到一半断了」。
                val dragHandle = remember(conversation.id) {
                    Modifier.pointerInput(conversation.id) {
                        try {
                            detectDragGestures(
                            onDragStart = {
                                val row = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == conversation.id }
                                if (row != null) {
                                    draggingState.value = conversation.id
                                    dragPreview.value = sectionsNow.value
                                    dragTracker.pointerY = row.offset + row.size / 2f
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val dragging = draggingState.value
                                if (dragging != null) {
                                    dragTracker.pointerY += amount.y
                                    val hit = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            dragTracker.pointerY >= it.offset &&
                                                dragTracker.pointerY <= it.offset + it.size
                                        }
                                        ?.key as? String
                                    if (hit != null) {
                                        val reordered = previewDrawerReorder(
                                            sections = dragPreview.value ?: sectionsNow.value,
                                            dragging = dragging,
                                            target = hit,
                                        )
                                        if (reordered != null) dragPreview.value = reordered
                                    }
                                }
                            },
                            onDragEnd = {
                                if (draggingState.value != null) dragPreview.value?.let { preview ->
                                    orderState.value = DrawerOrder(
                                        pinned = preview.pinned.map { it.id },
                                        normal = preview.normal.map { it.id },
                                    ).prunedTo(conversationsNow.value.map { it.id }.toSet())
                                    persistOrder()
                                }
                                dragPreview.value = null
                                draggingState.value = null
                            },
                            onDragCancel = {
                                dragPreview.value = null
                                draggingState.value = null
                            },
                            )
                        } finally {
                            if (draggingState.value == conversation.id) {
                                draggingState.value = null
                                dragPreview.value = null
                            }
                        }
                    }
                }
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == activeId,
                    running = conversation.id in runningId,
                    completedUnread = conversation.id in unreadCompleted,
                    menuOpen = menuFor == conversation.id,
                    sortMode = sortMode,
                    dragging = draggingState.value == conversation.id,
                    dragHandleModifier = dragHandle,
                    modifier = anim,
                    // 点行、长按、以及菜单里的每一个动作都先退出排序模式：用户要的是
                    // 「做了别的事就别再停在拖动态」。
                    onClick = {
                        // 埋点：抽屉里点会话到底发生了什么（用户：要点好几下）。
                        exitSortMode()
                        onOpenConversation(conversation)
                    },
                    onLongClick = {
                        exitSortMode()
                        menuFor = conversation.id
                    },
                    onMenuDismiss = {
                        exitSortMode()
                        menuFor = null
                    },
                    onRename = {
                        exitSortMode()
                        menuFor = null
                        pendingRename = conversation
                    },
                    onDelete = {
                        exitSortMode()
                        menuFor = null
                        pendingDelete = conversation
                    },
                    onTogglePin = {
                        exitSortMode()
                        menuFor = null
                        // 置顶就追加到置顶表末尾（置顶区有自己的顺序）；取消置顶只从置顶表
                        // 摘掉，于是它按 updatedAt 回到普通区该在的位置。
                        orderState.value = pinToggled(orderState.value, conversation)
                        persistOrder()
                        onTogglePin(conversation)
                    },
                    onToggleArchive = {
                        exitSortMode()
                        menuFor = null
                        onToggleArchive(conversation)
                    },
                    onCopyTitle = {
                        exitSortMode()
                        menuFor = null
                        copyTitle(context, conversation.title)
                    },
                    onExport = {
                        exitSortMode()
                        menuFor = null
                        pendingExport = conversation
                        exporter.launch(exportName(conversation.title))
                    },
                )
            }
            // 置顶区空着时连标签都不出现：空标签只会让人以为还有东西没加载出来。
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) {
                if (sections.pinned.isNotEmpty()) {
                    item(key = "drawer-pinned-label") {
                        Text(
                            text = "置顶",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    items(sections.pinned, key = { it.id }, contentType = { "conversation" }) { // 只保留位移动画。
drawerRow(
                        it,
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    ) }
                    if (sections.normal.isNotEmpty()) {
                        item(key = "drawer-section-divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                color = scheme.outlineVariant,
                            )
                        }
                    }
                }
                items(sections.normal, key = { it.id }, contentType = { "conversation" }) { // 只保留位移动画。
drawerRow(
                        it,
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    ) }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = scheme.outlineVariant,
            )
            Spacer(Modifier.height(6.dp))
            // 助手这一行开的是半屏表，不是整页：在抽屉里挑助手是「挑完接着聊」，
            // 不该被推到一整页上去。整页仍是设置页「助手」那一行的目的地
            // （route = "assistants"），两个宿主共用同一份 AssistantListContent。
            DrawerAction(
                label = "助手",
                icon = painterResource(R.drawable.ic_bot),
                selected = route == "assistants",
                onClick = onAssistants,
            )
            DrawerAction(
                label = "定时任务",
                icon = painterResource(R.drawable.ic_clock),
                selected = route == "schedules",
                onClick = { onNavigate("schedules") },
            )
            DrawerAction(
                label = "文件",
                icon = painterResource(R.drawable.ic_folder_open),
                selected = route == "files",
                onClick = { onNavigate("files") },
            )
            // The console's switch moved out of the chat header into here: on
            // without the overlay grant would read ON with no ball, so the grant
            // page comes first and the switch flips when the grant lands.
            DrawerAction(
                label = "悬浮窗控制台",
                icon = painterResource(R.drawable.ic_picture_in_picture_2),
                selected = false,
                // The whole row toggles, both ways: this row is the app's only
                // console switch (the settings page is text only), so a row that
                // could only turn it on left the user tapping a dead row while the
                // ball was up. The Switch keeps its own handler; a child consumes
                // the tap, so the two never fire together.
                onClick = onToggleConsole,
                trailing = {
                    // 与设置页同一个开关（尺寸 0.88、实心主色轨道）：悬浮控制台和设置里的
                    // 「常驻控制台」是同一件事，两处长得不一样会让人以为是两个开关。
                    SettingsSwitch(
                        checked = consoleOn,
                        onCheckedChange = { onToggleConsole() },
                        // 抽屉底色比设置页深一档，同样的 0.08 关态在这里几乎看不见（用户定 0.14）。
                        offTrackTint = 0.14f,
                    )
                },
            )
            DrawerAction(
                label = "设置",
                icon = painterResource(R.drawable.ic_settings),
                selected = route == "settings",
                onClick = { onNavigate("settings") },
            )
        }
    }
    // 改名：空标题或取消 ⇒ 一个字都不改（这里没有「清空标题」这种需求，标题是行的身份）。
    pendingRename?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        SuixingDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("标题") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { scope ->
                TextButton(
                    onClick = {
                        scope.closeThen {
                            pendingRename = null
                            onRenameConversation(target, draft)
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { scope -> TextButton(onClick = { scope.closeThen { pendingRename = null } }) { Text("取消") } },
        )
    }
    pendingDelete?.let { target ->
        SuixingDialog(
            onDismissRequest = { pendingDelete = null },
            // 与「设置 → 会话」页同一句话：说清删掉的是什么、有多少、能不能撤销。
            title = { Text("删除这个会话？") },
            text = {
                Text(
                    // **用 meta 里的条数**：列表只带 meta，`messages` 恒为空（#30）⇒ 原来这里永远显示 0 条。
                    text = "「${target.title}」的聊天记录（${target.messageCount} 条消息）会被永久删除，" +
                        "不能撤销。工作区里的文件不受影响。",
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = { scope ->
                TextButton(onClick = {
                    scope.closeThen {
                        onDeleteConversation(target)
                        pendingDelete = null
                    }
                }) {
                    Text("删除", color = scheme.primary)
                }
            },
            dismissButton = { scope ->
                TextButton(onClick = { scope.closeThen { pendingDelete = null } }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 复制标题到剪贴板。抽屉里没有 SnackbarHost（它是一块面板，不是 Scaffold），所以用 Toast
 * 报结果 —— 与导出同一条理由：这类动作「成没成」必须当场看得见。
 */
private fun copyTitle(context: android.content.Context, title: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    // 服务拿不到时不能报「已复制」：这条提示只有真的写进去了才该出现。
    val ok = clipboard != null &&
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Suixing", title)) }.isSuccess
    Toast.makeText(
        context,
        if (ok) "已复制标题" else "复制失败",
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * 导出文件名。与「设置 → 会话」页那个同名函数同一形状（标题 + .json），只是它是 private 的，
 * 这里用不了；两处都只做一件事：把标题里能当路径用的字符换掉。
 */
private fun exportName(title: String): String {
    val clean = title.replace('/', '／').replace('\\', '＼').trim()
    return (clean.ifEmpty { "会话" }) + ".json"
}

/**
 * 抽屉长按菜单里的一行：尺寸与反馈都照对话框里那颗 `TextButton` 抄。
 *
 * 为什么不用 `DropdownMenuItem`：它自带 M3 的列表语言（17sp Regular、48dp 行高、整行方角
 * 涟漪、onSurface 文字色），和对话框按钮（14sp Medium、40dp、胶囊涟漪、主色）摆在一起就是
 * 两套东西 —— 用户点名的正是这个不一致。尺寸集中在这个函数里，将来对话框若改，两处一起改。
 *
 * 字色：常规项用 onSurface（就是那个弹层的正文色；用户看过主色那版说「为什么是绿色的字」），
 * 破坏性项仍用 error（用户明确说「删除的红色没毛病」）。
 */
@Composable
internal fun DrawerMenuAction(
    text: String,
    destructive: Boolean = false,
    /** 行首图标；没有就只画文字。 */
    iconRes: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 缩进与「添加」弹层里那些行是**同一组数字**（`ui/chat/ChatComposer.kt:420-455` 的 MenuItemRow）：
    // 行自己只留 4dp 左右内边距，其余空白全部由 MenuCard 自带的 18dp 提供。
    // 左边距因此是 18 + 4 = 22dp。改之前那版是给 DropdownMenu 用的（它自带 12dp 留白）：
    // 外层 8dp + 内层 16dp 叠成 42dp，用户看到的就是「项离左边太远」。外层 padding 已整层删掉。
    // clip 与触感也照 MenuItemRow：shapes.small + ripple()（不再是整行胶囊），涟漪正好铺满可见形状。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .height(40.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconRes?.let { res ->
            Icon(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (destructive) scheme.error else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (destructive) scheme.error else scheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * 会话行左侧的运行标记：一段 270° 的弧在转，而不是一个静止的点。
 *
 * 自绘而不是用 Material 的 Autorenew/Sync：那两个图标画的是「刷新」语义，会被读成
 * 一个可以点的动作，而这里只是状态。只有运行中的行才建这段动画——整抽屉一起转比
 * 没有标记更糟。
 */
@Composable
internal fun RunningSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "running")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "angle",
    )
    val arc = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier.semantics { contentDescription = "正在运行" },
    ) {
        val stroke = 1.6.dp.toPx()
        val diameter = size.minDimension
        // 内缩半个线宽，圆头端点才不会在 14dp 的盒子里被切掉。
        drawArc(
            color = arc,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun DrawerAction(
    label: String,
    /** A Lucide vector from res/drawable: the drawer rows all draw from one icon set. */
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
    /** A control on the row's end instead of the chevron-less plain row. */
    trailing: (@Composable () -> Unit)? = null,
) {
    // 尺寸一组（用户：「会话列表下方的五个按钮，整体小一点」）：行高 46dp、图标 20dp、
    // 字号 14sp、行内左右 14dp、行间 1dp —— 四处同比缩 18% 左右，46dp 是可点高度的底线。
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                // 与会话列表标题**同一个样式**（用户：「五个按钮的标题字号改和会话列表一样大」）。
                // 原来用 `bodyLarge.copy(fontSize = 14.sp)`：字号数值相同，但 bodyLarge 的行高
                // 是 24sp（bodyMedium 是 20sp），同一行里文字看着更大、行也更松 —— 直接取同一个
                // 样式对象，主题里若改 bodyMedium 两者也会一起变。
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

/** 拖动时指针落在列表坐标系里的哪个 y（不参与重组，省掉每帧一次的重绘）。 */
private class DrawerDragTracker {
    var pointerY: Float = 0f
}


/**
 * 行尾的「更多」：三个圆点。仓库没有 ellipsis 图标，按既有约定（见 [DrawerGripIcon]）
 * 用 Canvas 画，不新造 drawable。
 */
@Composable
internal fun DrawerMoreIcon(tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val radius = 1.5.dp.toPx()
        val step = size.width / 4f
        val y = size.height / 2f
        for (index in 1..3) {
            drawCircle(color = tint, radius = radius, center = Offset(step * index, y))
        }
    }
}

/**
 * 拖拽把手：三根短线。仓库里没有 grip 图标，按既有约定（抽屉的置顶标记也是复用 ic_star）
 * 不新造 drawable，用一个 16dp 的 Canvas 画出来。
 */
@Composable
internal fun DrawerGripIcon(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val start = size.width * 0.16f
        val end = size.width * 0.84f
        DrawerGripFractions.forEach { fraction ->
            val y = size.height * fraction
            drawLine(
                color = tint,
                start = Offset(start, y),
                end = Offset(end, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private val DrawerGripFractions = floatArrayOf(0.3f, 0.5f, 0.7f)
