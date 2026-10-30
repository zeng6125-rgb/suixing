
package com.suixing.agent.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import com.suixing.agent.ui.chat.markdown.SettledBlocks
import com.suixing.agent.ui.chat.markdown.MdBlock
import com.suixing.agent.ui.components.bottomInsetPadding
import com.suixing.agent.ui.chat.markdown.LocalPreparedMarkdown
import com.suixing.agent.ui.chat.markdown.prepareMarkdownTexts
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import com.suixing.agent.data.choiceFor
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.LocalDrawerBusy
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.chat.markdown.LocalPathClick
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.theme.LocalDarkTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val SUGGESTIONS = listOf(
    "看看现在屏幕上是什么",
    "把下载目录里最新的三个文件列出来",
    "搜一下今天有什么科技新闻",
    "打开设置看看还剩多少存储空间",
)

/**
 * **A/B 实验开关**：读历史时要不要「钉住流式行的布局高度」（#17 的方案）。
 *
 * `true` = 现状（钉住，防止被推着走）；`false` = 关掉钉住（只测量、不冻结高度）。
 *
 * 用途：用户报「往上滑、再快速下滑会出现白色块」。白块是近期才高频出现的，而钉住正是
 * `dd4f998`（0.8.320）引入的 —— 出一版 `false` 的包做**单变量对照**：
 * 白块消失 ⇒ 与钉住这套有关；白块照旧 ⇒ 无关，回到基础列表找。
 * 对照做完把它改回 `true`（或删掉这个开关）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    /** The generation popup's empty-list escape hatch into the model settings page. */
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.screenState.collectAsStateWithLifecycle()
    // **只认本页会读的字段**（见 [sameForChat]）：整份按值订阅意味着任何一处设置写入都会让这
    // 1941 行重新跑一遍 —— 拖动/收起悬浮控制台、在设置页敲语音转写模型都会命中，而这四个字段
    // 本页一个都不读。判定等价时不向下游下发。
    val settings by remember {
        Core.settings.flow.distinctUntilChanged { a, b -> a.sameForChat(b) }
    }.collectAsStateWithLifecycle(initialValue = AppSettings())
    val positionStorageReady by Core.conversationViewports.ready.collectAsStateWithLifecycle()
    val openingBookmark = remember(state.conversationId, positionStorageReady) {
        if (positionStorageReady) Core.conversationViewports.get(state.conversationId) else null
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 顶层拿一次剪贴板：文件路径菜单的「复制」用（见 [PathActionSheet] 接线处）。
    val clipboard = LocalClipboardManager.current
    // The unsent text is the view model's, keyed by conversation: typing here, opening
    // another chat and coming back restores exactly what was left, and nothing leaks
    // across the switch.
    val draft = state.draft
    fun currentInput(): String = viewModel.draftFor(state.conversationId).text
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
        text: String = currentInput(),
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
        val input = currentInput()
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
    // **按会话重置**（评审 S4）：它持有的是某条消息对象，切会话后若还能被读到，
    // `editMessage(id, …)` 会把 A 的编辑改到 B 上。目前靠"编辑弹窗是模态"侥幸挡住，
    // 但任何非模态切换路径（通知/deep link/分屏）都会踩。
    var editing by remember(state.conversationId) { mutableStateOf<StoredMessage?>(null) }
    // The composer stack's own measured height: the node inside all of its paddings, so the
    // gesture bar's strip and its own 12dp of air are not in it (they are added back below).
    var composerH by remember { mutableStateOf(0.dp) }
    // The keyboard's clamped inset, and the gesture bar's own: the composer's bottom padding is
    // the difference between them (see the stack's own modifier), and it is the one term of the
    // stack's footprint that moves while the keyboard does.
    // 键盘高度通过 State 引用下传，在底部避让的布局节点读；动画不逐帧订阅整页组合。
    val imePxState = LocalHeldImePx.current
    // 手势条高度在组合期算一次：它是 @Composable 调用（`WindowInsets.navigationBars`），
    // 而且它在键盘动画期间不变。
    val navBarPx = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this) }
    // 让位＝**只用一个来源**：输入框自己的高度 + 它的下内边距 + 手势条。键盘动画中唯一在变的量是
    // heldIme，而它正是输入框自身位置的同一个驱动量 ⇒ 两者同步移动，可见空隙在任何一帧都一样
    // （用户要恒定）。原来用「列表底 − 输入框顶」两个节点的测量值相减，两个 onGloballyPositioned
    // 不同帧到位 ⇒ 动画中间态抖动。
    // 读历史期间沿用的底边距（见 LazyColumn 的 `bottom`）。
    // **null = 还没种过**，不是 0.dp（评审确证）：用 0.dp 当哨兵时，恢复位置进会话会先以
    // 0 当作底边距、等 `composerH` 量到才补成真实值 ⇒ 底边距 0 → ~一个输入框高，
    // 而 `scrollToItem` 已经执行过 ⇒ 整段恢复位置被推移一次。
    // **按会话 key**（评审）：底边距是"读历史期间冻结的那一个"，不同会话的输入框/让位不同，
    // 不 key 的话切到另一个会话会沿用上一个会话冻结值。
    var heldFootprint by remember(state.conversationId) { mutableStateOf<Dp?>(null) }
    // **不含手势条那一截**：它在布局期由列表/空状态自己让出来（[keyboardAwareBottomPad]），
    // 这样这一项不随键盘逐帧变 —— 否则它就是上面说过的「每帧整页重组」。
    // 几何上与旧写法等价：列表视口少了 navBarPad、正文底边距也少了 navBarPad，
    // 最后一张卡与输入框之间的距离一分不差（键盘全程恒定）。
    val stackFootprint = with(LocalDensity.current) {
        // `composerH` 是**内边距以内**的高度：`onSizeChanged` 挂在
        // `.padding(bottom = COMPOSER_BOTTOM_PAD)` **之后**（TurnScrubber.kt:757-758），
        // Compose 的修饰符顺序让 padding 先吃掉空间，所以测到的不含这 12dp。
        // 这里把它加回来才是输入框真实的占位 —— **不要删**（我删过一次，是错的）。
        composerH + COMPOSER_BOTTOM_PAD +
            // **恒定 12dp**（用户定案：所有垂直间距统一 12dp —— 静态项间距、状态行上下、
            // 底部让位全一致；顺带保持「run 结束时几何完全不变」）。
            12.dp +
            // **手势条那一截改由 contentPadding 让**（用户：「手势条有背景颜色会挡到内容」）。
            // 原来它写在列表的布局修饰符里（[keyboardAwareFullPad]），那是**裁剪视口**：
            // 内容到条子上沿就被切掉，条子下面只剩一底色 —— 正是「不沉浸」。
            // 设置页一直是 contentPadding 写法（只让最后一行避开条子，滚动中内容从条下穿过），
            // 这里对齐它；键盘那一截仍在布局期让（见 keyboardAwareImePad），不引入逐帧重组。
            with(LocalDensity.current) { navBarPx.toDp() }
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

    fun keepAttachment(chatId: String, attachment: Attachment) {
        viewModel.addAttachment(chatId, attachment)
        val current = viewModel.draftFor(chatId)
        viewModel.onDraftChange(chatId, current.copy(imageNames = current.imageNames + attachment.label))
    }

    fun attach(uri: Uri?, kind: AttachKind) {
        if (uri == null) return
        val chatId = state.conversationId
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readAttachment(context, uri, kind) }
            if (picked == null) {
                snackbarHostState.showSnackbar(if (kind == AttachKind.FILE) "读不到这个文件" else "读不到这张图片")
            } else {
                // 多附件：追加而不是覆盖（用户：「图片为什么只能添加一张」）。draft 的 imageNames
                // 是 remember 的重读触发器，标签列与缓存列同序。
                keepAttachment(chatId, picked)
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
            val chatId = state.conversationId
            scope.launch {
                val picked = withContext(Dispatchers.Default) { bitmapAttachment(bitmap) }
                keepAttachment(chatId, picked)
            }
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
    val displayedChat = rememberUpdatedState(state.conversationId)
    var shownTurnsByConversation by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.mapSaver<Map<String, Int>>(
            // Other chats retain their in-process window; only the displayed one enters Bundle.
            save = { windows -> windows.filterKeys { it == displayedChat.value } },
            restore = { saved -> saved.mapValues { (_, turns) -> turns as Int } },
        ),
    ) { mutableStateOf(emptyMap<String, Int>()) }
    // 同一组合里只建一张 id→index 表，三处共用（issue #54 B-7）。
    val messagePositions = remember(state.conversationId, state.messagesRevision) {
        state.messages.firstPositions()
    }
    val bookmarkWindow = openingBookmark?.let { bookmark ->
        val added = if (bookmark.followLatest) 0L else (state.turnCount.toLong() - bookmark.totalTurns).coerceAtLeast(0)
        (bookmark.shownTurns.toLong() + added).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } ?: PAGE_TURNS
    val anchorWindow = remember(state.conversationId, state.messagesRevision, openingBookmark) {
        if (openingBookmark == null || openingBookmark.followLatest) PAGE_TURNS else {
            // issue #53 B-2：用一次建表替换「每落一条消息扫一遍全表」的线性查找。
            // 语义保持 indexOfFirst：各自最早命中取最小 ⇒ 与「第一个满足任一条件的下标」等价。
            val positions = messagePositions
            val candidates = buildList {
                positions[openingBookmark.messageId]?.let(::add)
                openingBookmark.nearbyMessageIds.forEach { positions[it]?.let(::add) }
                openingBookmark.runId?.let { rid ->
                    val i = state.messages.indexOfFirst { it.runId == rid }
                    if (i >= 0) add(i)
                }
            }
            val at = candidates.minOrNull() ?: -1
            if (at < 0) PAGE_TURNS else state.messages.asSequence().drop(at)
                .count { it.role == Roles.USER && !it.steer } + 1
        }
    }
    val shownTurns = maxOf(shownTurnsByConversation[state.conversationId] ?: PAGE_TURNS, bookmarkWindow, anchorWindow)
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
    // **轮数总数要按磁盘上的真实值报**（#30）：`state.messages` 现在只是"最近 N 轮"这个窗口，
    // 用它算出来的 `turns` 就等于窗口里的轮数 ⇒ `earlier` 恒为 0 ⇒ 「加载更早」不出现、
    // 而且「第 N 轮」永远显示成第 1 轮（用户能直接看见的错）。
    // 总数来自落盘 meta 的 `turnCount`（由 ChatStore 维护，**是轮数不是条数**）。
    val page = remember(rowsKey, shownTurns, state.turnCount) {
        val drawn = turnPage(state.messages, shownTurns)
        if (state.turnCount > drawn.turns) drawn.withTotal(state.turnCount) else drawn
    }
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
    // **转录形状 token**：`visible` 每到这些输入变化就重建一次，下游派生只需要知道
    // 「重建过没有」。原来它们直接拿 `visible` 当 `remember` 的键 —— 而 `remember` 比较键用
    // `equals`，即每次重组都要把**每一行的正文、思考、工具输出、图片 base64 深比较一遍**
    // （`StoredMessage`/`Part` 都是 data class，行也是每拍新建的，引用短路的便宜都吃不到）。
    // 流式期每秒几十拍、一页十几轮 ⇒ 主线程上白跑的字符比较。换成这个 token：键仍是原来那几项
    // （标量 + 小 Map），比较是引用相等，真实重建才换新实例。
    val visibleShape = remember(
        state.conversationId,
        state.messagesRevision,
        page,
        state.activeRuns,
        state.runningHere,
        state.liveRunId,
    ) { Any() }
    val visible = remember(visibleShape) {
        // `drop()` 每次都把窗口内的引用复制成新 List；流式期这条路径每 16ms 跑一次，
        // 长会话（上千条）就是每秒几十万次引用拷贝。`subList` 是视图，零拷贝。
        val pageMessages = state.messages.subList(page.from.coerceIn(0, state.messages.size), state.messages.size)
        groupRows(pageMessages, state.activeRuns, state.runningHere)
            .filterNot { it.message.runId == state.liveRunId && state.liveRunId.isNotEmpty() }
    }
    // Long settled bodies are parsed only for the next mount batch. Those rows consume the
    // returned blocks directly; there is no competing full-page parse or global cache prewarm.
    // The physical feed uses the same old-to-new order as this projection.
    val rowKeys = remember(visibleShape) { presentationKeys(state.conversationId, visible) }
    val rowKeyIndices = remember(rowKeys) { rowKeys.withIndex().associate { it.value to it.index } }
    val viewport = remember(state.conversationId, positionStorageReady) {
        ConversationViewport(state.conversationId, openingBookmark)
    }
    val listState = viewport.listState

    // Markdown preparation is cached by message; navigation reads LazyList's own layout.
    val rowDocuments = remember(state.conversationId) { HashMap<String, Map<String, List<MdBlock>>>() }
    val visibleIds = remember(visibleShape) { visible.mapTo(HashSet()) { it.message.id } }
    SideEffect {
        rowDocuments.keys.retainAll(visibleIds)
    }
    val navigationToken = remember(state.conversationId) { intArrayOf(0) }
    val navigationJob = remember(state.conversationId) { arrayOfNulls<Job>(1) }
    DisposableEffect(navigationJob) {
        onDispose { navigationJob[0]?.cancel() }
    }
    // 回执（落盘的 system 消息）**内联画在它那一轮后面**（用户：「一轮错误就跟在一轮后面」）——
    // MessageRow 的分发里画（Roles.SYSTEM → FailureReceiptRow）。这里只取一遍供去重：同文案的
    // 实时行不再画，一次失败只通知一次（用户报的「两个提醒」）。
    val receipts = remember(state.conversationId, state.messagesRevision, page) {
        state.messages.subList(page.from.coerceIn(0, state.messages.size), state.messages.size)
            .filter { it.role == Roles.SYSTEM }
    }
    // 没有回执的失败（发送阶段错误等）的实时行，画在最后一轮下面。去重见 [isSameFailure]：
    // 只按完全相等比会漏，同一次失败被两条路径各写一遍时屏幕上是两张提示。
    val errorLine = remember(state.error, receipts) {
        state.error?.let { err -> receipts.none { isSameFailure(it.text, err) } } ?: false
    }
    // Content activity is observed separately; the reply card itself exists from run start.
    // 流式气泡开关与 pin 信号都只需要「有没有活内容」这个布尔：
    // 收 VM 的 liveActive（distinct 去抖），思考期的 40ms 批次不再重组整屏。
    val live by viewModel.liveActive.collectAsStateWithLifecycle()
    // **每次重组都扫一遍助手表**（流式期 12.5 Hz）。下面两行把 provider×model 的解析记住了，
    // 理由一模一样，唯独这行漏了；助手表本身也很少变，所以按它的输入记。
    val assistant = remember(settings.assistants, settings.activeAssistantId, state.assistantId) {
        settings.assistant(state.assistantId) ?: settings.activeAssistant()
    }
    // The chain a run takes, resolved once for the whole screen. The context readouts
    // below divide by the window of the model that actually sends, which is not the
    // app-wide default's window once a conversation or a persona picks its own.
    // 每次重组都要扫 provider×model（流式期 12.5 Hz），而这三个输入很少变 —— 记住它。
    // 同一个 remember 里把**生效的模型名**一并取出：圆环面板要用它说明"这个窗口属于谁"
    // （issue #49 修法 2：切 provider 后分母变了，用户需要看出变的是窗口）。
    val activeModel = remember(settings, state.providerId, state.model, assistant) {
        settings.choiceFor(state.providerId, state.model, assistant).model
    }
    val contextWindow = remember(settings, state.providerId, state.model, assistant) {
        settings.contextWindowFor(activeModel)
    }
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
    // **包 remember**（性能优化，值不变）：这是对可见行的 O(n) 反查，原来每次 ChatScreen
    // 重组都跑一遍 —— 滚动时每秒好几次、每次扫几百行。键用上面的形状 token（O(1) 引用比较），
    // 不要拿 `visible` 本身当键：那等于每次重组都把整页正文深比较一遍。
    val newestUsage = remember(visibleShape) { visible.lastOrNull { it.usage != null }?.usage }
    // What the endpoint never priced, sized off the text: the ring's floor for a conversation
    // 键用 **messagesRevision**，不是 rowsKey：`rowsKey` 是（会话, 条数, 末条 id），
    // 编辑某条消息、或流式把文本追加进已落盘消息时，条数与末条 id 都不变 ⇒ 估算值不刷新，
    // 圆环读数会停在旧数字。revision 是 ViewModel 在每次发布时统一盖的章。
    // **逐条缓存 token 估算**（审计 1§2）：`estimateTokens` 对正文做逐字符 CJK 判定，
    // 12 轮 + 工具输出可达 5~20 万字符 ⇒ 每次 revision 一次几 ms 的主线程尖峰，
    // 与 frame.log 里「成对 24ms+16ms、每 100~350 帧一次」的形态吻合。
    // 消息是不可变值对象 ⇒ 按 id 缓存，用 text.hashCode()（String 自带缓存）验证内容未变。
    //
    // **缓存改成进程级、不再按会话重建**（切换会话卡的主线程开销之一，见 tokenCache 的 KDoc）：
    // 原来是 `remember(state.conversationId)` ⇒ 每次切会话换一张**空表** ⇒ 整页正文被逐字符
    // 重估一遍（就发生在组合期、主线程）；切回来再估一遍。内容指纹（stamp）本来就在条目里，
    // 全局表不会串数据；LRU 上限防内存。纯函数与单测见 TokenEstimateCache。
    val transcriptEstimate = remember(state.messagesRevision, state.checkpoints) {
        val last = state.checkpoints.lastOrNull()
        // issue #53 B-2：同一次建表里查 id（firstPositions 保留首个重复 id 的语义）。
        val positions = messagePositions
        val start = last?.let { cp ->
            val i = positions[cp.toMessageId] ?: -1
            if (i >= 0) i + 1 else 0
        } ?: 0
        val kept = if (start in 1..state.messages.lastIndex) {
            state.messages.subList(start, state.messages.size)
        } else {
            state.messages
        }
        var sum = 0
        kept.forEach { message ->
            val stamp = message.text.hashCode().toLong() * 31 +
                message.toolOutput.orEmpty().length +
                message.toolArgs.orEmpty().length * 7
            sum += TokenEstimateCache.tokensFor(message, stamp)
        }
        sum + (last?.summary?.length ?: 0) / 3
    }
    // 圆环读数＝**真实数字**：当前会话的上下文大小 / 模型窗口（用户：「就是按照真实数字啊…
    // 当前会话上下文/上下文窗口的数字」）。分子只认实测口径：网关回报的本轮 prompt_tokens
    // （含按会话保持的最近一次）→ 落盘回报；只有整个会话从没被网关报过用量时才用 /4 估算兜底
    // 并标「约」。跳变的老根因是两个口径轮流上屏，所以估算值绝不与实测值并列成链。
    // tokens 是上次请求的绝对用量，没有绑定窗口。切 provider 仍保留这个会话的最近实测，
    // 分母单独按当前模型计算；下次请求上报后再更新，避免切模型即丢失唯一实测。
    // **按会话保持**（审计 5.25）：`rememberSaveable(state.conversationId)` 在 inputs 变化时会
    // 重新初始化（不是按 key 分槽保存）⇒ A→B→A 后归 0，圆环掉回估算档 —— 正是上面这段注释
    // 要消除的口径轮换。改成进程内的 per-chat 表：切回某个会话沿用它上次的实测数。
    // 代价是旋转/进程重建后不再恢复这一档（下面的落盘实测 persistedContext 照旧接住）。
    val heldPrompts = remember { mutableStateMapOf<String, Int>() }
    val heldPrompt = heldPrompts[state.conversationId] ?: 0
    // 请求级快照（prompt/breaks 变才发射）——body 不再订阅 120ms 的 elapsedMs。
    val runStatsNow by viewModel.requestStats.collectAsStateWithLifecycle()
    // **实时统计属于"正在跑的那一次请求"**：它挂在 ViewModel 上全局共享，切会话/新建会话时
    // 不会自己清空。原来直接拿它当圆环读数 ⇒ 新建的空会话会显示上一个会话的 40% / 409.5K
    // （用户实测截图）。所以只在**这个会话自己正在跑**时才认它；其余场合交给下面三档
    // （本会话的实测快照 heldPrompt / 落盘实测 persistedContext / 估算兜底）。
    val liveStatsHere = runStatsNow.takeIf { state.runningHere }
    val reported = liveStatsHere?.lastPromptTokens ?: 0
    LaunchedEffect(state.conversationId, reported) {
        if (reported > 0) heldPrompts[state.conversationId] = reported
    }
    // 落盘的实测上下文（issue #41 真问题 1）：run 结束瞬间前两级归零，这一档接住圆环。
    // meta 里 O(1) 命中（totalsAt==messageCount 时），每轮落盘自动更新。
    // 统计在 IO 线程重读，StateFlow 的旧值不一定属于本页；不能让别的会话抢占圆环读数。
    val session = viewModel.sessionStats.collectAsStateWithLifecycle(initialValue = null).value
        ?.forConversation(state.conversationId)
    val persistedContext = session?.contextTokens ?: 0
    val reading = ringReading(
        liveReported = if (reported > 0) reported else heldPrompt,
        newestStored = newestUsage?.prompt ?: 0,
        liveEstimate = liveStatsHere?.let { it.breakSystem + it.breakTools + it.breakHistory } ?: 0,
        transcriptEstimate = transcriptEstimate,
        persistedContext = persistedContext,
    )
    // 切会话时算一次兜底读数（网关没报过用量时那两行占比要有数）。
    LaunchedEffect(state.conversationId, viewport) {
        if (state.conversationId.isEmpty()) return@LaunchedEffect
        // Tool/plugin estimation is not needed to display the transcript's first frame.
        snapshotFlow { viewport.presented && viewport.entrance.value >= 1f }.first { it }
        viewModel.refreshContextBreakdown()
    }
    val contextUsed = reading.tokens
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
    // **整个会话**的墙钟合计（原来是 `state.messages.sumOf{…}`，那只是 12 轮窗口）。
    val sessionElapsedMs = session?.elapsedMs ?: 0L
    // The conversation's spend, split the way the endpoint reports it, for the ring's card: the
    // prompt the cache could not answer, the cache reads, and the output. The card used to give
    // the total and the hit rate only; the user asked for the three parts by name —
    // 「未缓存输入」「缓存读取」「输出」 — and in full, because these are the figures a bill is
    // checked against and a rounded one cannot be checked against anything.
    // **整个会话**的三块花费（原来 `sessionSplitOf(state.messages)` 只算窗口）。
    val sessionSplit = session?.split ?: SessionSplit(0, 0, 0)
    // Which compression records belong under which row. A record names the first and last
    // message it folded away, so it is drawn under the row that holds that last message —
    // the place in the transcript where the model stopped seeing the conversation verbatim.
    // **记忆键必须真正决定结果**（本轮对抗评审证伪了初版）：`placeCheckpoints` 的输出是按
    // **每行的 `message.id`** 作键、按**全部轮次 id** 反查锚点，所以形状至少要包含这两样。
    // 初版用 `rowKeys` 是错的 —— 多轮行的 rowKey 只有 `rounds.first().id`（不含 message.id），
    // 于是"末轮换了个 id、首轮没变"这种形态会在键相同的情况下产出不同结果（评审给了反例）。
    // 现在的形状 = 每行的 (message.id, 全部轮次 id)；比较时全是同一批 String 实例，走引用短路。
    val checkpointRowsShape = remember(visibleShape) {
        visible.map { row -> row.message.id to row.rounds.map { it.id } }
    }
    val checkpointsByRow = remember(state.checkpoints, checkpointRowsShape) {
        placeCheckpoints(visible, state.checkpoints)
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
    val runElapsedClock = rememberRunElapsed(runStartedAt[runKey] ?: 0L, state.runningHere, clockKey = runKey)
    // Which thinking blocks are open, per conversation. One table for the screen, because
    // the live block and the stored block it becomes are two different composables — a
    // remember inside the block was born fresh on that hand-over, which is why a think
    // never folded when it settled — and because the live row's key was shared by every
    // conversation, so two chats were reading the same switch.
    val reasoningExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val reasoningExpansion = remember(state.conversationId) {
        ReasoningExpansion(state.conversationId, reasoningExpanded)
    }
    SideEffect {
        if (state.liveRunId.isNotBlank() && reasoningExpansion.stored("time:${state.liveRunId}") == null) {
            // Do not insert a newly-created timestamp above an already-read live reply.
            reasoningExpansion.set("time:${state.liveRunId}", false)
        }
    }

    // The 「加载更早」 line sits above the transcript as an item of its own, so every row
    // below it is one item further down than its place in [visible].
    val rowOffset = if (page.hasEarlier) 1 else 0
    // The live card (with its progress caption inside) is one item; the error line and the
    // 「加载更早」 line are items of their own. 底锚下这个数只用于「有没有内容」与插入回底。
    // 槽位账本走纯函数（`ListSlots`，带单测）：这两个数原来是**各写一遍**的，而且已经数得不一致
    // —— `feedPrefix` 数了「压缩中」那一行、`itemCount` 没数。今天少一项只影响 `<= 0` 这类判据，
    // 但列表以后再加一行，另一处必然漏（这条链路已经出过三次同类事故）。
    val slots = ListSlots.of(
        hasLiveCard = live || state.runningHere,
        runningHere = state.runningHere,
        compressing = state.compressingSince > 0L,
        hasErrorLine = errorLine,
        visibleRows = visible.size,
        hasLoadEarlier = page.hasEarlier,
    )
    val itemCount = slots.total

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
        val starts = visible.indices
            .filter {
                visible[it].message.role == Roles.USER && !visible[it].message.steer
            }
            .map { it + rowOffset }
        // **一行用户消息都没认出来时不能留空**：`TurnMarks.current` 见到空表就直接
        // `return newest` ⇒ "当前轮"恒等于最新那一轮 ⇒ 条数永远 5、高亮永远在最下面、
        // 点条也全错位（三种反馈同因）。宁可退化成一"行"一轮，也不能让整条导航条失效。
        if (starts.isEmpty()) visible.indices.map { it + rowOffset } else starts
    }
    // Keep the scrubber's legacy reverse coordinate, while physical rows are chronological.
    // Temporary rows are now at the end; only load-earlier precedes stored messages.
    val feedPrefix = slots.feedPrefix

    // **不在 ChatScreen 组合体里读 `.value`**（issue #26 原因一）：`firstVisibleItemIndex` 滚动时
    // 每跨一个 item 就变一次，一旦在这里读，整个 ChatScreen（1600 行、几十个 Composable 调用）
    // 就要重跑一遍。把读取推迟到 `TurnScrubber` 内部（见下面 `current = { … }`），
    // 跨边界时只有导航条自己重组。用 `remember` 持有 State 对象而不是 `by` —— `by` 一取就立刻读了。
    // **单 item 化之后 `firstVisibleItemIndex` 恒为 0**，读数导航必须改口径：
    // 用视口里已量到的行按像素反查「28% 读数线」落在哪一行（`TurnMarks.probeOffset`，
    // 带单测）。仍然用 `remember` 持有 State、把 `.value` 的读取推迟到 `TurnScrubber`
    // 内部（issue #26 原因一），滚动时只有导航条自己重组。
    // The list's measured visible rows determine the turn at the 28% reading probe.
    val currentPhysicalRow = remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val probe = TurnMarks.probeOffset(layout.viewportStartOffset, layout.viewportEndOffset)
            layout.visibleItemsInfo.lastOrNull { it.offset <= probe }?.index
                ?: layout.visibleItemsInfo.firstOrNull()?.index ?: 0
        }
    }

    // Reading is natively top-anchored; explicit follow-to-end lives in ChatScroll.kt.
    // A swipe disables following, so neither streaming nor settlement seeks the list.
    var pressed by remember { mutableStateOf(false) }
    var pressUpAt by remember { mutableLongStateOf(0L) }
    // 用 `mutableStateOf` 持有而不是 `by remember`：守护标记还要被下面的 bring-into-view 规格读到
    // （它不在组合里跑，只能拿状态对象）。
    val pressGuardState = remember { mutableStateOf(false) }
    var pressGuardOn by pressGuardState
    /**
     * 这一次按下里手指移动过没有（issue #14）。
     *
     * 守护协程要用它区分「长按选字」和「按住拖动」：前者要锁住列表，后者锁住就是跟手指打架。
     * 以前 `moved` 只是 pointerInput 里的局部变量，外面看不到。
     */
    var pressMoved by remember { mutableStateOf(false) }
    // **长按才 arm**（跟手性）：400ms 的守护窗口是给「长按选文字」用的（选区建立期间不许列表
    // 自己动），但原来每次按下都 arm ⇒ 每个拖动手势之后 400ms 都有一个逐帧协程 + 冻结 IME clamp，
    // 正好落在惯性滚动期，观感就是「滑完发涩」。
    // 长按阈值：composable 作用域里没有 `PointerInputScope.viewConfiguration`，要取 Local。
    val viewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
    val selectionFocus = LocalFocusManager.current
    val selectionTracker = remember(state.conversationId) { SelectionFocusTracker() }
    // **组合期不订阅 `pressed`**（审计 1§3）：原来 `LaunchedEffect(pressed)` 的 key
    // 是状态读 —— 每次按下/抬起都把整个 ChatScreen（58 个子组合）重组一遍，反向手势
    // 100ms 内 3~4 次。改成在协程里 snapshotFlow 订阅：读发生在协程，组合期零订阅。
    LaunchedEffect(Unit) {
        snapshotFlow { pressed }.collectLatest { down ->
            if (!down) return@collectLatest
            delay(viewConfig.longPressTimeoutMillis)
            if (!pressMoved) pressGuardOn = true
        }
    }
    // composition local 只能在组合里读：先在组合层捕获，守护协程里用这个引用。
    val setSelectionActive = LocalSetSelectionActive.current
    // **分批填充**（打开/切换会话时 12 轮一次性组合 = 100~150ms，frame.log 实测：
    val scroll = rememberChatScroll(
        // 内容版本：消息条数 + 流式/思考字符数。只在真正"长高"时变，滚动本身不会触发。
        listState = listState,
        autoFollow = settings.autoFollow,
        pressedNow = { pressed },
        guardNow = { pressGuardOn },
        conversationId = state.conversationId,
        initialFollowing = openingBookmark?.followLatest != false,
    )

    val transcriptPosition = remember(listState) { TranscriptPosition(listState) }
    val followPositionNow = rememberUpdatedState {
        settings.autoFollow && scroll.stickToBottom && !scroll.restoring &&
            !pressed && !pressGuardOn && !listState.isScrollInProgress
    }
    val readingPositionNow = rememberUpdatedState {
        !scroll.stickToBottom && !scroll.restoring && !pressed && !pressGuardOn &&
            !listState.isScrollInProgress && viewport.presented && viewport.entrance.value >= 1f
    }
    SideEffect {
        transcriptPosition.following = { followPositionNow.value() }
        transcriptPosition.reading = { readingPositionNow.value() }
    }
    LaunchedEffect(transcriptPosition) {
        snapshotFlow {
            listOf(scroll.stickToBottom, scroll.restoring, pressed, pressGuardOn, listState.isScrollInProgress)
        }.collect { transcriptPosition.interactionChanged() }
    }

    // A successful page publishes a larger turn window, even if grouping/filtering keeps the
    // same row count. Never release restoration merely because the read was accepted.
    var loadingEarlier by remember(state.conversationId) { mutableStateOf(false) }
    val earlierRequestToken = remember(state.conversationId) { intArrayOf(0) }
    suspend fun prepareRows(rows: List<ChatRow>) {
        fun sourceIsCurrent(): Boolean {
            val current = viewModel.screenState.value
            return current.conversationId == state.conversationId &&
                current.messagesRevision == state.messagesRevision && current.activeRuns == state.activeRuns &&
                current.liveRunId == state.liveRunId && current.runningHere == state.runningHere
        }
        for (row in rows) {
            val texts = row.parts.filterIsInstance<Part.Text>().map { it.text }
            val current = rowDocuments[row.message.id]
            if (current == null || texts.any { it.length >= SettledBlocks.MIN_CHARS && it !in current }) {
                val prepared = prepareMarkdownTexts(texts)
                // A parser may finish after a branch/page/chat changed during suspension.
                // Its old document must not publish into, or evict, the current row cache.
                if (!sourceIsCurrent()) return
                rowDocuments[row.message.id] = prepared
            }
        }
        if (!sourceIsCurrent()) return
        val retained = rows.mapTo(HashSet()) { it.message.id }
        listState.layoutInfo.visibleItemsInfo.forEach { item ->
            rowKeyIndices[item.key]?.let { retained.add(visible[it].message.id) }
        }
        rowDocuments.keys.retainAll(retained)
    }
    key(state.conversationId) {
        BindConversationViewport(
            viewport, viewModel, scroll, visible, rowKeys, rowKeyIndices, rowOffset, itemCount, shownTurns, state.turnCount,
            MaterialTheme.typography.bodyMedium.fontSize.value * LocalDensity.current.fontScale,
            positionStorageReady, state.messagesRevision, ::prepareRows,
        )
    }
    // 正常按合并和放置解除 restoring；异常准备等待有上限，异步失败由 onComplete 解锁。
    // 贴底时持续记住当前占位；离开底部（开始读历史）就冻住它 ——
    // 输入框高度在流式期间会变（状态行出现/消失），不冻住就会把整列内容推几 dp。
    LaunchedEffect(stackFootprint, scroll.stickToBottom) {
        // 贴底时持续跟随；**另外**只要还没种过（0.dp）就种一次 —— 恢复位置进会话时
        // `stickToBottom` 一开始就是 false，条件永远不成立 ⇒ 整个阅读期间底边距是 0。
        // 贴底时持续跟随；没种过（null）也种一次 —— 恢复位置进会话时 `stickToBottom` 一开始就是
        // false，否则整个阅读期间都取不到值。
        if (scroll.stickToBottom || heldFootprint == null) heldFootprint = stackFootprint
    }
    // **#14 的根因（真机日志 2026-10-21 00:25 坐实，不是推断）**：
    // 长按建立选区后，框架会为「把选中的内容滚进视口」发起一次 bring-into-view，走的就是
    // `LocalBringIntoViewSpec`。日志证据：`guard-on` 在 0/0，365ms 后开始逐帧位移
    // （d=148/137/59/52/44/36/30 … 1），同期请求序列 `want=-621 → -473 → -336 → … → -1`，
    // 合计 621px，与 `lock-would-correct offset-drift=621` 完全吻合；全程 `moved=false`（手指没动）。
    // `size=2507 / container=2510` —— 被选中的消息几乎有整屏高，框架试图把它整个露出来。
    //
    // 修法是**在源头拒绝**，不是事后纠回去（事后纠会先跳一下再跳回来）：
    // 按下期间让「要滚多远」恒为 0，选区的 bring-into-view 就推不动列表。
    //
    // 为什么还要一段「安静期」：请求是逐帧来的，只挡到抬手的话动画还没收完，
    // 之后的帧照样把剩下的距离滚掉。所以每次被挡的请求都续期，请求停止 500ms 后才放行。
    // 选区按需武装（#27/#42③）：guard 武装（长按建立选区）→ true；
    // 滚动开始且手指不在屏上 → false（滚动会取消文本选区，解除安全）。
    val revealBlocked = remember { mutableStateOf(false) }
    val lastRevealRequestAt = remember { mutableLongStateOf(0L) }
    val defaultBringIntoView = LocalBringIntoViewSpec.current
    val revealGuardSpec = remember(defaultBringIntoView) {
        // **严格等价**：不拦的时候，两个成员都原样转发 —— 位移量 `calculateScrollDistance`
        // 与动画规格 `scrollAnimationSpec`。少转发后者会落到接口默认实现上，
        // 只要原规格用的不是接口默认，滚动动画就变了。
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float>
                get() = defaultBringIntoView.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                if (!revealBlocked.value) {
                    return defaultBringIntoView.calculateScrollDistance(offset, size, containerSize)
                }
                // 逐帧续期：被挡的请求还在来，说明这套 reveal 动画没结束，别提前放行。
                lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
                return 0f
            }
        }
    }
    // 安静期：请求停止 500ms（远长于 60fps 的一帧）才放行，避免动画收尾时把剩下的距离滚掉。
    // **组合期不读 `revealBlocked`**：原来 `LaunchedEffect(revealBlocked.value)` 把
    // 一个 State 当 key —— 每次长按武装/解除各重组一次整屏，而这台机器上长按/甩动都在
    // 手势热路径上。改成协程内 snapshotFlow 订阅：读发生在协程，组合期零订阅。
    LaunchedEffect(Unit) {
        snapshotFlow { revealBlocked.value }.collectLatest { blocked ->
            if (!blocked) return@collectLatest
            while (revealBlocked.value) {
                delay(100)
                if (SystemClock.uptimeMillis() - lastRevealRequestAt.longValue > 500L) {
                    revealBlocked.value = false
                }
            }
        }
    }
    LaunchedEffect(pressGuardOn) {
        if (!pressGuardOn) return@LaunchedEffect
        // 按下就开始拦「把选中内容滚进视口」的请求（#14）：框架的这次滚动是为了露出选区，
        // 而用户按住的正是眼前这段文字，露出它没有意义，只会把列表推走。
        lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
        revealBlocked.value = true
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
            // **行为保留、日志去掉**：按下到抬手后 400ms 内，如果列表位置被推走
            // （选区重排造成，手指没动），就关掉追底 —— 而不是把位置纠回去（0.8.242 的教训）。
            if (lockKey != null) {
                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == lockKey }
                when {
                    target == null -> Unit
                    target.index == listState.firstVisibleItemIndex -> {
                        val drift = listState.firstVisibleItemScrollOffset - lockOffset
                        if (abs(drift) > 1 && !pressMoved) scroll.stickToBottom = false
                    }
                    else -> if (!pressMoved) scroll.stickToBottom = false
                }
            }
            // 组合被带走（切会话/退页）也要解冻，否则外壳的 IME clamp 永远停摆。
            setSelectionActive?.invoke(false)
        }
    }
    // Only the target list is animated, after its first valid viewport is ready.
    val switchEntrance = viewport.entrance
    // **按 item key 锚定阅读位置**（issue #11，取代 #10 的「钉住高度」）。
    LaunchedEffect(viewModel) {
        Core.engine.runsFlow.collect { runs ->
            val currentChat = viewModel.state.value.conversationId
            runStartedAt.keys.removeAll { key ->
                val chatId = key.substringBefore(':')
                chatId != currentChat && chatId !in runs
            }
        }
    }
    LaunchedEffect(viewModel, scroll) {
        viewModel.conversations.map { chats -> chats.mapTo(HashSet()) { it.id } }
            .distinctUntilChanged()
            .collect { ids ->
                val retained = ids + viewModel.state.value.conversationId
                shownTurnsByConversation = shownTurnsByConversation.filterKeys { it in retained }
                reasoningExpanded.keys.removeAll { it.substringBefore(':') !in retained }
                runStartedAt.keys.removeAll { it.substringBefore(':') !in retained }
            }
    }
    //
    // **为什么"看到偏差再纠正"必败**（三版都失败）：流式行每长高 δ，列表在**同一帧的测量**
    // 里就把上面的内容顶上去 δ；任何"测量之后才知道 δ"的纠正都发生在下一帧 ⇒ 永远慢一拍 ⇒
    // 用户看到的是「内容一直在爬」，而不是「跳一下就停」（末态对只是收尾对账抹掉了残差）。
    //
    // **做法**：把流式行的**高度变化推迟一帧**（布局报的是上一帧的自然高度），然后在
    // **帧回调里**（下一帧测量之前）同时做两件事：① 把报出的高度前进到新值；② 把同样的 δ
    // 交给列表 `dispatchRawDelta`。两件事落在**同一帧的测量**里 ⇒ 净位移 0 ⇒ 不漂。
    // 这正是 `RevealHeight` 已验证的铁律（补偿量与高度变化必须同帧生效，真机实测 ±1px）。
    //
    // **不是冻结**：内容照常生长、照常可见，只是高度晚一帧生效（最多裁掉 16ms 宽的一条边缘）。
    // **不是移出列表**：流式行仍在 LazyColumn 里。
    // 只在「用户上翻读历史」时启用；贴底时高度直接跟自然值，行为与以前完全一致。
    // 外观 → 气泡宽度. Measured against the screen, which is what the setting promises
    // in its own words: 标准 (0.94) stays wider than the transcript's own 16dp margins,
    // so the default leaves every card exactly as wide as it has always been, and only
    // 窄 pulls the reply in.
    val bubbleMaxWidth = with(LocalConfiguration.current) { screenWidthDp.dp } *
        settings.bubbleWidthFraction

    fun jumpToLatest() {
        viewport.followOverride = true
        scope.launch {
            scroll.allowBottomResume = true
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
        // **深色下不要再刷一遍整屏**（重复渲染清理）：`Scaffold(containerColor = background)`
        // 已经把整屏涂成 `background`，而深色主题里 `background` 与 `surface` 是同一个色值
        // （`theme/Color.kt` 都是 `0xFF0F1412`）⇒ 这层 `background(canvas)` 在深色下是纯重复绘制，
        // 每帧多一次全屏填充、像素完全不变。
        // 浅色下 `canvas` 是带一点品牌色的竖向渐变（不是纯色），必须保留。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (LocalDarkTheme.current) Modifier else Modifier.background(canvas))
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (state.loading || !viewport.prepared) {
                    // Nothing of the conversation is known yet, so none of it is drawn —
                    // least of all the empty state, which is what made a cold start look
                    // like it had opened a new chat and then jumped to the real one.
                    LoadingSkeleton()
                } else if (visible.isEmpty() && !live && !state.runningHere) {
                    // Outside the list on purpose: an empty conversation recentres in
                    // whatever space the keyboard leaves, instead of the list's top
                    // padding shoving the hero off the top of the screen.
                    //
                    // **新会话页有它自己的底部让位**（用户：「新会话页为什么要和聊天页重复」）：
                    // 聊天页的 `stackFootprint` 里带着 12/16dp 的"转录与输入框之间的空气"，
                    // 那是转录需要的，空状态不需要 —— 照搬它就会在输入框上方多出一段空白。
                    // 这里只保留输入框的**真实占位**：内容高度 + 它自己的下内边距（手势条那一截
                    // 由下面的修饰符在布局期让出来，见 [keyboardAwareBottomPad]）。
                    val heroBottom = with(LocalDensity.current) {
                        composerH + COMPOSER_BOTTOM_PAD
                    }
                    Box(
                        Modifier
                            // 键盘抬起/收回时，这里只重新测量（不重组）——空状态也挂着那个
                            // 60 参数的 Composer，逐帧重组一样会掉帧。
                            .keyboardAwareImePad(navBarPx, imePxState)
                            .fillMaxSize()
                            .graphicsLayer {
                                val progress = switchEntrance.value
                                alpha = ConversationEntrance.alpha(progress)
                                translationY = ConversationEntrance.remainingTravel(progress) * 12.dp.toPx()
                            }
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
                        LocalTypewriter provides settings.typewriter,
                        // 不在底部时展开要"向下展开"（块顶边钉住），需要摸到主列表状态。
                        LocalChatListState provides listState,
                        LocalTranscriptPosition provides transcriptPosition,
                        // 按下期间拦住「把选中内容滚进视口」的请求（issue #14 的修法，见上面的说明）。
                        LocalBringIntoViewSpec provides revealGuardSpec,
                        LocalSelectionFocusTracker provides selectionTracker,
                    ) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = viewport.presented,
                        // Top-relative native anchoring keeps text already read inside a growing
                        // message in place. Follow-to-end is explicit and pauses on user scrolling.
                        reverseLayout = false,
                        modifier = Modifier
                            .transcriptPosition(transcriptPosition)
                            // 手势条那一截**在布局期让出来**（键盘盖住它时让位是 0）：
                            // 放在 `fillMaxSize()` 之外，列表拿到的是「窗口 − 键盘 − 手势条」。
                            .keyboardAwareImePad(navBarPx, imePxState)
                            .fillMaxSize()
                            // 常驻入场层（0.8.284 回退）：条件挂载会导致本列表的布局修饰符链在
                            // `switching` 翻转时重建，展开/收起折叠块时出现残影（用户实测）。
                            //
                            // **所以这里不摘层，只在恒等状态时让它不干活**（#27）：
                            // `graphicsLayer` 的 lambda 是**绘制阶段**才读快照的，动画结束
                            // （p == 1f）后把 alpha 等写回默认值即可 —— 层还在（不会重建修饰符链、
                            // 不会有残影），但不再改变任何像素，合成器可以直接跳过它。
                            // **常驻，不按 switchRunning 条件挂载**（0.8.284 的定案；此前注释写
                            // "常驻"、代码却挂在 `if (switchRunning)` 里，两者矛盾 —— 以注释为准）。
                            // 条件挂载会让整条修饰符链在开关翻转时**重建** ⇒ 重新测量 + 重新挂
                            // 离屏层，那一下正是切换会话掉帧的来源之一。
                            // 常驻的代价是一个 RenderNode 参与合成；省掉它的做法是让层在恒等
                            // 状态不改变任何像素（下面 p >= 1f 直接返回），而不是摘掉它。
                             .graphicsLayer {
                                 val progress = switchEntrance.value
                                 alpha = ConversationEntrance.alpha(progress)
                                 translationY = ConversationEntrance.remainingTravel(progress) * ConversationEntrance.TRAVEL_DP.dp.toPx()
                             }
                            // 手指按下只干两件事：通知外壳冻结 IME clamp（选择期间键盘别收）；
                            .pointerInput(state.conversationId) {
                                try {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitPointerEvent(PointerEventPass.Initial)
                                            if (down.changes.none { it.pressed }) continue
                                            if (navigationJob[0]?.isActive == true) {
                                                navigationJob[0]?.cancel()
                                                scroll.restoring = false
                                            }
                                            val downY = down.changes.first().position.y
                                            val downAt = SystemClock.uptimeMillis()
                                            pressed = true
                                            // **不在这里 arm 守护**（跟手性）：原来每次按下都置 true，而
                                            // 只有「没移动且短于长按阈值」才会立刻撤销 ⇒ **任何拖动手势之后
                                            // 守护都会保持 [LONG_PRESS_SETTLE_MS]（400ms）**，正好盖住整段
                                            // 惯性滚动：这期间有一个逐帧协程在跑，还冻结着外壳的 IME clamp。
                                            // 现在改由下面的 `LaunchedEffect(pressed)` 在**真的到长按**时才 arm。
                                            pressMoved = false
                                            // **但 reveal 拦截要在按下瞬间就开**（长按回归根因，实测 lp_trace.log）：
                                            // 文本选区的 bringIntoView 比长按超时早 ~28ms 到达，头 5 条放行请求
                                            // 把列表推走 228px —— 等 guard-on 再拦已经晚了。上面注释否掉的是
                                            // 「arm 整套守护」（逐帧协程 + IME 冻结，会盖住惯性滚动）；
                                            // 这里只置 revealBlocked 标志（零成本、只拦 reveal 请求），
                                            // 识别为滚动时立刻解除 —— 与那条教训不冲突。
                                            revealBlocked.value = true
                                            lastRevealRequestAt.longValue = SystemClock.uptimeMillis()
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
                                                // `!moved`：`stickToBottom` 的 setter 是 `SnapshotStateMap.put`，
                                                // 不做等值短路，而读它的地方就在 ChatScreen 自己的组合体里
                                                // （底边距 / CompositionLocal）⇒ 每个 move 事件写一次 =
                                                // 一次拖动写 60~120 次。只在本次手势首次越过阈值时写。
                                                if (!moved && change != null && change.pressed &&
                                                    (dy > CANCEL_FOLLOW_PX || dy < -CANCEL_FOLLOW_PX)
                                                ) {
                                                    scroll.allowBottomResume = true
                                                    scroll.stickToBottom = false
                                                    moved = true
                                                    pressMoved = true
                                                    // 滚动不是选字：解除 reveal 拦截
                                                    revealBlocked.value = false
                                                }
                                                released = next.changes.none { it.pressed }
                                            }
                                            pressed = false
                                            pressUpAt = SystemClock.uptimeMillis()
                                            // Selection's own release/drag-end handlers run in Main.
                                            // Clearing focus after them prevents an old manager from
                                            // re-showing its toolbar after a blank tap closed it.
                                            awaitPointerEvent(PointerEventPass.Final)
                                            if (selectionTracker.active && !moved && pressUpAt - downAt < viewConfig.longPressTimeoutMillis) {
                                                 selectionTracker.clear()
                                                selectionFocus.clearFocus()
                                            }
                                            // 短按/拖动都不需要撤销：守护从没 arm 过（见上面的说明），
                                            // 只有**长按**那条路才会打开它，并由 `LaunchedEffect(pressGuardOn)`
                                            // 里的 400ms 结算逻辑关掉。
                                        }
                                    }
                                } finally {
                                    // A chat switch cancels the old gesture as well as its captured
                                    // selection tracker. It must not leave IME/follow guards armed.
                                    pressed = false
                                    pressUpAt = SystemClock.uptimeMillis()
                                    pressMoved = false
                                    pressGuardOn = false
                                    revealBlocked.value = false
                                    setSelectionActive?.invoke(false)
                                }
                            },
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            // 顶边：悬浮顶栏占着状态栏那一条。
                            top = with(LocalDensity.current) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            } + 76.dp,
                            // Composer space belongs below the chronological transcript.
                            // Keep it stable throughout reading, including run completion.
                            // 没种过就退回当前值（**不要退回 0.dp**，那会让恢复位置被推移一次）。
                            bottom = if (scroll.stickToBottom) {
                                stackFootprint
                            } else {
                                // Finishing a run must not shorten the scroll range under
                                // a reader near its end. Release this reservation when the
                                // reader intentionally returns to latest, not on completion.
                                heldFootprint ?: stackFootprint
                            },
                        ),
                        // Short chats begin at the top. Growing text extends below existing
                        // text; only the explicit latest-follow policy scrolls toward the end.
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                    ) {
                        // Chronological items anchor the already-rendered text at its top.
                        if (page.hasEarlier) {
                            item(key = "load-earlier", contentType = "load-earlier") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextButton(
                                        enabled = !loadingEarlier && viewport.presented,
                                        onClick = {
                                            scroll.stickToBottom = false
                                            val chatId = state.conversationId
                                            val token = ++earlierRequestToken[0]
                                            loadingEarlier = true
                                            fun finish(added: Boolean) {
                                                if (viewModel.state.value.conversationId != chatId || earlierRequestToken[0] != token) return
                                                if (added) {
                                                    shownTurnsByConversation = shownTurnsByConversation + (chatId to shownTurns + PAGE_TURNS)
                                                }
                                                loadingEarlier = false
                                            }
                                            // Stable lazy keys preserve the current message and offset in
                                            // the same layout as the page insertion; no visible correction.
                                            if (page.from > 0) finish(true)
                                            else if (!viewModel.loadEarlier(onComplete = ::finish)) finish(false)
                                        },
                                    ) {
                                        Text(
                                            text = if (loadingEarlier) "正在加载更早的内容…"
                                                else "加载更早的 $PAGE_TURNS 轮 · 上面还有 ${page.earlier} 轮",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                        items(
                            count = visible.size,
                            key = { fed -> rowKeys[fed] },
                            contentType = { fed -> visible[fed].message.role },
                        ) { fed ->
                            val row = visible[fed]
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // One placeable per item: the list's own 16dp spacing applies
                            // between the placeables of a single item as well, which is what
                            // held the timestamp a full 16dp above the bubble it belongs to.
                            // Inside this Column the two sit 3dp apart, and the 16dp goes back
                            // to meaning what it says — the air between messages.
                            // **重行独立成一个 RenderNode**（issue #47 方向 2）：LazyColumn 把
                            // 所有可见项画进**同一棵**绘制树 —— 任何一处内容变化、以及每一次
                            // 滚动/动画，RenderThread 都要把这棵树整个重录（实测是主线程的
                            // 3.4 倍）。给「含思考块/工具卡」的重行挂一层，内容没变时它自己的
                            // 绘制列表可以被直接复用，整棵重录退化成「只有脏行重录」。
                            //
                            // 只在重行上挂：轻行（用户气泡、纯正文）的绘制列表本来就短，多一层
                            // 反而增加合成开销和内存。层上不带任何视觉属性 ⇒ 渲染结果逐像素相同。
                            // **重卡判定要覆盖全部重内容**（issue #47 方向 2）：
                            // 原来只看 `Thinking` / `Tools`，漏掉两类最重的：
                            //  · `Compress`（上下文压缩卡，内部可含长清单）；
                            //  · **含图片的消息行** —— 图片不在 `Part` 里，而是消息级的
                            //    `message.allImages`（`ChatTopBar.kt:645` 逐个 ImageBlock），
                            //    解码位图是整页绘制列表里最重的一类。
                            // 轻行（用户气泡、纯正文）不挂层：它们的绘制列表本来就短，多一层
                            // 只增加合成开销与内存。层上没有任何视觉属性 ⇒ 渲染结果逐像素相同。
                            // 键用 `imageCount`（Int）而不是 `allImages`：后者是每次访问都重建
                            // List 的 getter（`ChatModels.kt:81` 的 ifEmpty/listOfNotNull），
                            // 拿它当 remember 键等于每次重组都换一个新对象、缓存必然失效。
                            // **不再 remember**（本轮审计）：`row.parts` 每个 delta 都新建一个 List，
                            // 拿它当键 ⇒ 每拍每个可见行都要先做一次整行的深比较（`remember` 比较键用
                            // `equals`）才能判断"没变"，而这段本体的成本只是 O(parts) 的类型判断。
                            // 去掉之后省一次深比较，判定结果不变。
                            val imageCount = row.message.imageCount
                            val heavyRow = imageCount > 0 ||
                                row.parts.any {
                                    it is Part.Thinking || it is Part.Tools || it is Part.Compress
                                }
                            Column(
                                Modifier
                                    .then(if (heavyRow) Modifier.graphicsLayer() else Modifier),
                            ) {
                                // 外观 → 显示时间: off means no labels at all, not fewer.
                                if (settings.showTimestamps && row.showTimeLabel &&
                                    reasoningExpansion.stored("time:${row.message.runId}") != false) {
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
                                CompositionLocalProvider(
                                    LocalPreparedMarkdown provides rowDocuments[row.message.id].orEmpty(),
                                    LocalReadingRowKey provides rowKeys[fed],
                                ) {
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
                                }
                                // 中断/截断/失败的原因不再挂在这里：错误提醒统一画在列表最底
                                // （回执行 + 无回执时的实时红字），同一次失败只通知一次。
                            }
                            // The compression record sits under the last message it folded
                            // away: that is the point in the transcript where the model
                            // stopped seeing the conversation verbatim.
                            checkpointsByRow[row.message.id].orEmpty().forEach { checkpoint ->
                                // O(messages) 的扫描放到 remember 里：每次重组跑一遍是纯浪费，
                                // 键用 revision + checkpoint（两者任一变化才需要重算）。
                                val originals = remember(checkpoint, state.messagesRevision) {
                                    checkpointSpan(state.messages, checkpoint, messagePositions)
                                }
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
                        }

                        // 没有回执的实时失败（发送阶段错误等）画在最后一轮下面；有回执的失败由
                        // 回执自己跟在那一轮后面（内联），同文案不重复画。
                        if (errorLine) {
                            item(key = "error:${state.conversationId}", contentType = "error") {
                                RunReasonLine(state.error.orEmpty())
                            }
                        }
                        // 没有 run 时手动压缩：状态行说「正在压缩上下文 · 用时 xx」。
                        // 没有这一行，点了压缩屏幕上什么都没有（用户：「啥响应也没有」）。
                        if (!state.runningHere && state.compressingSince > 0L) {
                            item(key = "compress-status:${state.conversationId}", contentType = "compress-status") {
                                RunStatusLine(
                                    text = "正在压缩上下文",
                                    startedAt = state.compressingSince,
                                    timed = true,
                                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
                                )
                            }
                        }
                        // Keep one keyed assistant item from the initial wait through the last token.
                        if (live || state.runningHere) {
                                    item(key = liveRowKey(state.conversationId, state.liveRunId), contentType = "assistant") {
                                        // 流式行（喂序 index 0）每长高一点，上面的内容就被顶上去一点 ——
                                        // 往上翻着读旧内容时这是最烦人的漂移。**内容照常长**（issue #11：
                                        // 裁剪是死路），位置由上面那段 key 锚点纠正，这里只负责量高度。
                                        CompositionLocalProvider(LocalReadingRowKey provides liveRowKey(state.conversationId, state.liveRunId)) {
                                        Column(Modifier.fillMaxWidth()) {
                                            // **只把气泡**放进「钉住 + 裁剪」里：状态行（思考中 · 12.3s）在自然内容的
                                            // 最底部，若跟气泡一起被裁，钉住期间整行连 spinner 都会消失（评审确证）。
                                            //
                                            // 修饰符顺序：`clipToBounds` 取的是**内层**尺寸 —— 必须写在
                                            // `liveRowLagHeight` **之前**（更外层）才按"报出的高度"裁；
                                            // 反过来它拿到的是内容自然高度，等于一条裁不到任何东西的 clip。
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clipToBounds()

                                            ) {
                                            LiveMessageParts(viewModel, state.conversationId, state.liveRunId,
                                                settings.showReasoning) { liveParts ->
                                            AssistantRow(
                                            // Versioned text is refreshed; unchanged tool lists keep their identity.
                                            parts = liveParts,
                                                streaming = true,
                                                runningElapsed = runElapsedClock,
                                                // The live bubble is the persona's reply too:
                                                // without these two it fell back to the brand name
                                                // and feather while every stored bubble showed the
                                                // persona.
                                                name = assistant?.label ?: "随行",
                                                face = assistant?.face ?: "",
                                                avatarPath = assistant?.avatarPath ?: "",
                                                runningTool = state.runningTool,
                                                // **流式分支里不用它**（唯一的读取点是
                                                // `if (!streaming && …)` 那条 note）—— 传 null，
                                                // 否则 stats 每变一次都让整棵 live 气泡（Markdown 全块 +
                                                // 思考内层列表 + 工具行）重跑，而这时根本没人读它。
                                                liveStats = null,
                                                contextWindow = contextWindow,
                                                expandTools = settings.expandTools,
                                                expandReasoning = settings.expandReasoning,
                                                reasoningMaxHeight = settings.reasoningHeight.dp,
                                                followReasoning = settings.followReasoning,
                                                bubbleMaxWidth = bubbleMaxWidth,
                                                reasoningExpansion = reasoningExpansion,
                                                runId = state.liveRunId,
                                            )
                                            }
                                            }
                                            // 上 12dp：气泡白底 → 状态行顶。下：让位恒定 12dp（见 stackFootprint）。
                                            if (state.runningHere) {
                                                RunStatusLine(
                                                    text = liveStatusText,
                                                    startedAt = runStartedAt[runKey] ?: 0L,
                                                    elapsed = runElapsedClock,
                                                    // 压缩不再占用这一行（它有自己的过程块），所以时钟一直走。
                                                    timed = true,
                                                    // 量高度给外层用：状态行在裁剪之外，钉住值要含它、
                                                    // 气泡报出的高度要把它扣掉（见 `liveRowLagHeight` 的 `outside`）。
                                                    // 写普通数组，不进快照 ⇒ 不触发重组。
                                                    // **量在 padding 之前**：`outside` 要代表"状态行占掉的整段高度"
                                                    // （含 12dp 间距），否则气泡扣减与 item 总高度天生差 12dp。
                                                    modifier = Modifier
                                                        .padding(start = 4.dp, top = 12.dp, bottom = 0.dp),
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
                        statsFlow = viewModel.liveStats,
                        model = activeModel,
                    )
                },
            )
            // The jump-to-latest button lives inside the composer stack (first row of
            // its column): 12dp above whatever the stack's top row is, clear of the
            // pill — anchored to the reading area's bottom it hid behind the capsule.
            // One line per turn on the right edge; the old ◀ i/N ▶ row lived in the
            // composer and fought the status line for width.
            // **窗口第一轮的全局轮号**：`page.earlier` = 上面还有几轮没加载 ⇒ 窗口从
            // `earlier + 1` 开始（`TurnPage.earlier = turns - drawn`）。
            // **不要用 `turnIndices.size` 反推**（这里原来就是）：流式期间最新那一轮的行被
            // `runId != liveRunId` 过滤掉，`turnIndices` 比 `page.drawn` 少一项 ⇒ 反推出来的
            // 起点偏一轮 ⇒ 标签念错一句、点最新那条还会越界（`getOrNull` 拿到 null，
            // 于是一下都不动 —— 用户说的「点不中 / 对应不上」）。全局轮号才是真坐标。
            val firstDrawnTurn = page.earlier + 1
            // **点击后立刻高亮目标轮**：导航是动画（240–340ms），若等滚动经过再更新高亮，
            // 观感就是"点了没反应"。这里先乐观置位，导航结束时清掉（用户要求实时响应）。
            var pendingTurn by remember(state.conversationId) { mutableStateOf<Int?>(null) }
            val scrubberSettling by remember(viewport, scroll) {
                derivedStateOf {
                    scrubberHighlightSettling(
                        restoring = scroll.restoring,
                        presented = viewport.presented,
                        entrance = viewport.entrance.value,
                    )
                }
            }
            ScrubberPresentation(state.conversationId,
                presented = { viewport.presented },
                entrance = { switchEntrance.value }) { entranceModifier ->
                TurnScrubber(
                    conversationId = state.conversationId,
                    totalTurns = page.turns,
                    // 已加载的轮数用 `page.drawn`（窗口的**定义**）：条数、窗口起点、
                    // 「已加载到第几轮」全按它算，与 `firstDrawnTurn` 同一口径。
                    drawnTurns = page.drawn,
                    current = {
                        pendingTurn ?: if (live && currentPhysicalRow.value >= rowOffset + visible.size) {
                            page.turns.coerceAtLeast(1)
                        } else TurnMarks.currentAtRow(
                            physicalIndex = currentPhysicalRow.value,
                            turnIndices = turnIndices,
                            drawnTurns = page.drawn,
                            totalTurns = page.turns,
                        )
                    },
                    // 恢复位置及会话入场完成前不做补间，避免高亮从临时轮次向真实轮次下扫。
                    settling = scrubberSettling,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .then(entranceModifier)
                        // 键盘抬起时导航条跟着上移（用户：「抬起时为什么导航条不动」）。
                        // 整页不再按 IME 缩高之后，CenterEnd 的居中点是满高的 H/2，而旧结构里
                        // 居中在 (H − I) 的框内 ⇒ 等价于往上挪 I/2。只在**放置阶段**读 IME，
                        // 不触发重组、不重测列表。
                        .offset { androidx.compose.ui.unit.IntOffset(0, -(imePxState.value / 2)) }
                        // **离右边缘 4dp**（用户要求在 4dp 上「稍微收一点」的同时定了这个数）。
                        // 观感/位置只按你说的数字动。
                        .padding(end = 4.dp),
                    labelOf = { turn ->
                        // `turn` 是**全局轮号**（1 基），标签直接用它。
                        // 找这一轮的开头文字要换算成**窗口内**下标（0 基）：`turnIndices` 里装的是
                        // 窗口内各轮的开头行，窗口第一轮 = 全局 `firstDrawnTurn`。
                        val inWindow = turn - firstDrawnTurn
                        val opening = visible.getOrNull(
                            turnIndices.getOrElse(inWindow) { -1 } - rowOffset,
                        )
                            ?.message
                            ?.text
                            ?.lineSequence()
                            ?.firstOrNull { it.isNotBlank() }
                            ?.trim()
                            .orEmpty()
                        if (opening.isEmpty()) {
                            "第 $turn 轮"
                        } else {
                            "第 $turn 轮 · ${opening.take(8)}"
                        }
                    },
                    onJumpTo = { turn ->
                        // `turn` 是**全局轮号** ⇒ 换成窗口内下标再查 `turnIndices`。
                        // **最新那一轮在流式期间可能不在 `turnIndices` 里**（`groupRows` 把
                        // `runId == liveRunId` 的行过滤掉了 ⇒ 它的开头行被滤走，列表尾部少一项）：
                        // 原来 `getOrNull` 拿到 null 就**整段跳过** —— 点最新那根一下不动
                        // （用户：「点条的也不对应」）。它没有行可指，但它就是"最新"，
                        // 底锚下 feed index 0 即底部 ⇒ 明确滚到底，不吞这次点击。
                        val inWindow = turn - firstDrawnTurn
                        val index = turnIndices.getOrNull(inWindow)
                        pendingTurn = turn
                        val token = ++navigationToken[0]
                        val previousNavigation = navigationJob[0]
                        previousNavigation?.cancel()
                        navigationJob[0] = scope.launch {
                            try {
                                previousNavigation?.join()
                                // **等呈现要有上限**（审计 5.28）：`presented` 由恢复协程在
                                // 「定位 + 补读更早页」之后置位，那次恢复若早退或被取消，这个
                                // `first{}` 永不返回；而 `pendingTurn` 只在 token 匹配的 finally
                                // 里清除 ⇒ 导航条高亮会一直钉在被点的那一轮。超时只是让这次跳转
                                // 作废（用户再点一下），不会动滚动几何与命中逻辑。
                                if (withTimeoutOrNull(NAVIGATION_PRESENT_TIMEOUT_MS) {
                                        snapshotFlow { viewport.presented }.first { it }
                                    } == null) return@launch
                                if (token != navigationToken[0] || viewModel.state.value.conversationId != state.conversationId) return@launch
                                scroll.allowBottomResume = false
                                scroll.stickToBottom = false
                                scroll.restoring = true
                                val row = index?.let { visible.getOrNull(it - rowOffset) }
                                if (row != null) {
                                    val layout = listState.layoutInfo
                                    // Both nearby and distant targets arrive through scrolling.
                                    viewport.navigation.navigate(listState, index,
                                        TurnMarks.navigationScrollOffset(layout.viewportStartOffset, layout.viewportEndOffset))
                                } else if (inWindow >= turnIndices.size) {
                                    viewport.navigation.navigate(listState, (itemCount - 1).coerceAtLeast(0), TRANSCRIPT_END_OFFSET)
                                }
                            } finally {
                                if (token == navigationToken[0]) pendingTurn = null
                                if (token == navigationToken[0] && scroll.conversationId == state.conversationId) scroll.restoring = false
                            }
                        }
                    },
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    // IME 在**放置阶段**读取（offset 的 lambda 属于布局/放置，不属于组合），
                    // 键盘高度每帧变化只移动输入框的位置：不改约束、不重测 Scaffold、
                    // 不重组那个 60 参数的 Composer。输入框自己内部还有一段
                    // `(navbar − ime)` 的让位（ChatComposer.kt:941），两处不重复扣。
                    .offset { androidx.compose.ui.unit.IntOffset(0, -imePxState.value) },
            ) {
                ComposerDraftText(viewModel, state.conversationId) { input ->
                Composer(
                    subagentChatId = state.conversationId,
                    input = input,
                    onInput = { setDraft(text = it) },
                    onRestoreSteer = { steer -> scope.launch { restoreQueuedSteer(steer) } },
                    running = state.runningHere,
                    settings = settings,
                    assistantId = state.assistantId,
                    contextWindow = contextWindow,
                    contextUsed = contextUsed,
                    contextEstimated = reading.estimated,
                    // 面板三行：run 中用实测三块，空闲用按当前上下文算出来的那份（打开面板时刷新）。
                    contextBreak = liveStatsHere?.let {
                        Triple(it.breakSystem, it.breakTools, it.breakHistory)
                    } ?: state.contextBreakdown,
                    onContextPanelOpen = { viewModel.refreshContextBreakdown() },
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
                        scroll.allowBottomResume = true
                        scroll.stickToBottom = true
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
                )
                // 「回到最新」浮钮：**画在 composer 之外**（兄弟节点），高度不参与 composer 的
                // 测量 ⇒ 它出现/消失不会改底部让位、不会把转录顶上去（用户报的「往上滑一点点就
                // 跳」）。位置：composer 顶再往上 12dp，右边缘 40dp 离屏边（与回合导航条留 12dp 气）。
                if (!scroll.atBottom && !state.loading) {
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_arrow_down),
                        contentDescription = "回到最新",
                        onClick = { jumpToLatest() },
                        size = 44.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        // 与浮层卡片同一套阴影（用户：「参考浮层卡片的阴影效果」）：
                        // PopupStyle.kt 的规格是 elevation 10dp + ambient/spot 同为 18% 黑，
                        // 扩散大、浓度低、没有方向性 —— 比默认 elevation 阴影（偏灰、发脏）高级。
                        shadowElevation = 10.dp,
                        shadowColor = Color.Black.copy(alpha = 0.18f),
                        iconSize = 20.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            // 跟着按钮直径走（44 + 12 = 56dp），否则缩小后离 composer 会多出 4dp 空隙。
                            .offset(y = -(44.dp + 12.dp))
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
                onResult = { text ->
                    val input = currentInput()
                    setDraft(text = if (input.isBlank()) text else "$input $text")
                },
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
            val servers by Core.mcp.servers.collectAsStateWithLifecycle()
            val toolNames = remember(servers) { Core.tools.names() + com.suixing.agent.agent.SUBAGENT_SPECS.map { it.name } }
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
 * 把「手势条让位」从组合期搬到**布局期**：键盘盖住手势条时让位是 0，键盘收起时是完整的手势条高度。
 *
 * 为什么需要它（用户：「输入框被输入法抬起收回时掉帧」）：这一项原来算在 `stackFootprint` 里，
 * 而 `stackFootprint` 是 `LazyColumn` 的 `contentPadding` —— 组合期参数。键盘高度在动画的每一帧
 * 都变，于是整页 ChatScreen（含那个 60 参数的 Composer）每帧重组一次。放到布局期后，
 * 键盘动画只让这一个节点重新测量/放置，**零重组**，而且间距与输入框逐帧同步（输入框自己也是
 * 在布局期读同一个值，见 `TurnScrubber.kt` 的 `.layout{}` 与 `Common.kt:595`）。
 *
 * 形制照抄 `Common.kt` 的 `imePaddingBottom`：新 `maxHeight` 必须夹在 `[minHeight, maxHeight]`，
 * 否则 `Constraints` 会抛 "maxHeight(n) must be >= minHeight(m)"（0.8.434 那个闪退的同一类）。
 */
private fun Modifier.keyboardAwareBottomPad(
    navBarPx: Int,
    ime: androidx.compose.runtime.State<Int>,
): Modifier = bottomInsetPadding { (navBarPx - ime.value).coerceAtLeast(0) }

/**
 * 聊天页整棵不再按 IME 缩高之后，列表要自己把键盘占的那一截让出来。
 *
 * **只让键盘超出导航条的那一截**，导航条本身已挪进 `stackFootprint`（contentPadding）：
 * 视口在键盘收起时铺到屏幕底（内容从手势条下穿过），键盘弹起时只再缩 `max(IME − N, 0)`。
 * 底部净空 = contentPadding 的 N + 这里的 max(IME − N, 0) = **max(IME, N)** ——
 * 与改前的 `max(N − IME, 0) + IME` 完全相等，不会重复让位，也不会多空一截。
 * 仍走 `bottomInsetPadding`（布局期），键盘动画只让这两个节点重测，不重组整页。
 */
private fun Modifier.keyboardAwareImePad(
    navBarPx: Int,
    ime: androidx.compose.runtime.State<Int>,
): Modifier = bottomInsetPadding { (ime.value - navBarPx).coerceAtLeast(0) }

/**
 * 导航条跳转最多等「内容已呈现」多久（审计 5.28）。
 *
 * 恢复协程置 `presented` 之前要定位、必要时补读更早的页；它若早退/被取消，跳转协程会永远
 * 停在 `snapshotFlow { presented }.first { it }` 上，而 `pendingTurn` 只在 token 匹配的
 * finally 里清除 ⇒ 高亮一直钉在被点的那一轮。10s 远长于一次正常恢复，超时只是让这次跳转
 * 作废，用户再点一下即可。
 */
private const val NAVIGATION_PRESENT_TIMEOUT_MS = 10_000L

/**
 * 两份设置**在聊天页看来**是否等价。
 *
 * 本页按值订阅整份 [AppSettings]，而整份里任何一个字段变了都会让它整页重新跑一遍。这里抹平的
 * 是「本页一个都不读、但别处写得挺勤」的四个字段：
 *
 * - `consoleWidthDp` / `consoleHeightDp` / `floatingConsole`：悬浮控制台拖动结束时写
 *   （`service/FloatConsoleService.kt:912`、`:924`），而控制台正是盖在聊天页上拖的。
 *
 * **`asrModel` 不在这个名单里，别再加回来**：本页自己不读它，但把整份 `settings` 传给了
 * `VoiceOverlay`（`ChatScreen.kt:1784`），那里 `VoiceOverlay.kt:109` 用它判断「转写是否已配置」、
 * `:325-326` 直接显示模型名。抹平它 = 用户改完转写模型回来，语音卡仍显示旧模型或「未配置」，
 * 而且不会自愈。**判断"本页不读"必须连子组合一起看**，只看本文件会漏（这条就是这么发现的）。
 *
 * 抹平之后这些写入不再触发整页重组；本页真正读的字段（`assistants`、`providers`、
 * `showReasoning`、`bubbleWidthFraction`…）照常下发。
 *
 * **黑名单式**，故意不做白名单：将来新增字段若忘了写进来，最坏只是少省一次重组，
 * 绝不会让页面读到过期值。
 */
internal fun AppSettings.sameForChat(other: AppSettings): Boolean =
    copy(consoleWidthDp = 0, consoleHeightDp = 0, floatingConsole = false) ==
        other.copy(consoleWidthDp = 0, consoleHeightDp = 0, floatingConsole = false)
