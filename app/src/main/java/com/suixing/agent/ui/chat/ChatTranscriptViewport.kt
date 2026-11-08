package com.suixing.agent.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.components.bottomInsetPadding

/** The existing transcript layout, with gestures and data preparation supplied separately. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatTranscriptViewport(
    state: ChatUiState,
    settings: AppSettings,
    transcript: ChatTranscriptProjection,
    contextReadout: ChatContextReadout,
    presentation: ChatRunPresentation,
    actions: ChatScreenActions,
    input: ChatInputBindings,
    transcriptPosition: TranscriptPosition,
    revealGuardSpec: BringIntoViewSpec,
    gesture: ChatReadingGesture,
    scroll: ChatScroll,
    selectionTracker: SelectionFocusTracker,
    viewConfig: ViewConfiguration,
    selectionFocus: FocusManager,
    setSelectionActive: ((Boolean) -> Unit)?,
    composerH: Dp,
    navBarPx: Int,
    imePxState: State<Int>,
    heldFootprint: () -> Dp?,
    stackFootprint: Dp,
    bubbleMaxWidth: Dp,
    live: Boolean,
    loadingEarlier: Boolean,
    loadEarlier: () -> Unit,
    onEdit: (StoredMessage) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val visible = transcript.visible
    val rowKeys = transcript.rowKeys
    val viewport = transcript.viewport
    val errorLine = transcript.errorLine
    val listState = transcript.listState
    val rowDocuments = transcript.rowDocuments
    val checkpointsByRow = transcript.checkpointsByRow
    val messagePositions = transcript.messagePositions
    val runElapsed = transcript.runElapsed
    val page = transcript.page
    val navigationJob = transcript.navigationJob
    val assistant = contextReadout.assistant
    val activeChoice = contextReadout.activeChoice
    val providerOfModel = contextReadout.providerOfModel
    val contextWindow = contextReadout.contextWindow
    val reasoningExpansion = presentation.reasoningExpansion
    val liveStatusText = presentation.liveStatusText
    val runStartedAt = presentation.runStartedAt
    val runKey = presentation.runKey
    val runElapsedClock = presentation.runElapsedClock
    val switchEntrance = viewport.entrance
    fun setDraft(text: String) = input.controller.setDraft(text = text)
    Column(Modifier.fillMaxSize()) {
        if (state.loading || !viewport.prepared) {
            // Nothing of the conversation is known yet, so none of it is drawn —
            // least of all the empty state, which is what made a cold start look
            // like it had opened a new chat and then jumped to the real one.
            ConversationLoading()
        } else if (visible.isEmpty() && !live && !state.runningHere) {
            // Outside the list on purpose: an empty conversation recentres in
            // whatever space the keyboard leaves, instead of the list's top
            // padding shoving the hero off the top of the screen.
            //
            // **新会话页有它自己的底部让位**（用户：「新会话页为什么要和聊天页重复」）：
            // 聊天页的 `stackFootprint` 里带着 12/16dp 的"转录与输入框之间的空气"，
            // 那是转录需要的，空状态不需要 —— 照搬它就会在输入框上方多出一段空白。
            // 这里只保留输入框的**真实占位**：内容高度 + 它自己的下内边距（手势条那一截
            // 由下面的修饰符在布局期让出来，见 [keyboardAwareImePad]）。
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
                        translationY = ConversationEntrance.offsetYDp(progress).dp.toPx()
                    }
                    .padding(
                        top = with(LocalDensity.current) {
                            WindowInsets.statusBars.getTop(this).toDp()
                        } + 60.dp,
                        bottom = heroBottom,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                EmptyHero(
                    onPick = { setDraft(text = it) }, assistant = assistant,
                    modelConfigured = activeChoice.model.isNotBlank() && activeChoice.baseUrl.isNotBlank(),
                    onOpenSettings = onOpenSettings,
                )
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
                    // 状态不改变任何像素（alpha 回到 1f），而不是摘掉它。
                     .graphicsLayer {
                         val progress = switchEntrance.value
                         alpha = ConversationEntrance.alpha(progress)
                         // 阅读位置恢复完才淡入；位移只在绘制层，列表项布局不动。
                         translationY = ConversationEntrance.offsetYDp(progress).dp.toPx()
                     }
                    // 手指按下只干两件事：通知外壳冻结 IME clamp（选择期间键盘别收）；

                    .chatTranscriptGesture(state.conversationId, gesture, navigationJob, scroll,
                        selectionTracker, viewConfig, selectionFocus, setSelectionActive),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    // 顶边：悬浮顶栏占着状态栏那一条。
                    top = with(LocalDensity.current) {
                        WindowInsets.statusBars.getTop(this).toDp()
                    } + 76.dp,
                    // Composer space belongs below the chronological transcript.
                    // **始终用冻住的值**：原来贴底时用实时 stackFootprint、离底时用
                    // heldFootprint，run 完成时 stickToBottom 一翻，afterContentPadding
                    // 跟着变 ⇒ 阅读锚点的 Y 被推移一段，叠加 live→settled 的高度差
                    // 就是「对话完成后位置跳变」。heldFootprint 在贴底时也会被种下
                    // （见上面的 LaunchedEffect），所以贴底行为不变，只是不再切换。
                    // 没种过就退回当前值（**不要退回 0.dp**，那会让恢复位置被推移一次）。
                    bottom = heldFootprint() ?: stackFootprint,
                ),
                // Short chats begin at the top. Growing text extends below existing
                // text; only the explicit latest-follow policy scrolls toward the end.
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            ) {
                chatTranscriptItems(
                    state = state,
                    settings = settings,
                    assistant = assistant,
                    visible = visible,
                    rowKeys = rowKeys,
                    runElapsed = runElapsed,
                    providerOfModel = providerOfModel,
                    reasoningExpansion = reasoningExpansion,
                    rowDocuments = rowDocuments,
                    actions = actions,
                    onEdit = onEdit,
                    checkpointsByRow = checkpointsByRow,
                    messagePositions = messagePositions,
                    bubbleMaxWidth = bubbleMaxWidth,
                    contextWindow = contextWindow,
                    hasEarlier = page.hasEarlier,
                    earlierCount = page.earlier,
                    loadingEarlier = loadingEarlier,
                    presented = viewport.presented,
                    onLoadEarlier = loadEarlier,
                    errorLine = errorLine,
                    live = live,
                    liveStatusText = liveStatusText,
                    runStartedAt = runStartedAt[runKey] ?: 0L,
                    runElapsedClock = runElapsedClock,
                )

            }
            }
        }
    }

}
/**
 * 聊天页整棵不再按 IME 缩高之后，列表要自己把键盘占的那一截让出来。
 *
 * **只让键盘超出导航条的那一截**，导航条本身已挪进 `stackFootprint`（contentPadding）：
 * 视口在键盘收起时铺到屏幕底（内容从手势条下穿过），键盘弹起时只再缩 `max(IME − N, 0)`。
 * 底部净空 = contentPadding 的 N + 这里的 max(IME − N, 0) = **max(IME, N)** ——
 * 与改前的 `max(N − IME, 0) + IME` 完全相等，不会重复让位，也不会多空一截。
 * 仍走 `bottomInsetPadding`（布局期），键盘动画只让这两个节点重测，不重组整页。
 */
internal fun Modifier.keyboardAwareImePad(
    navBarPx: Int,
    ime: androidx.compose.runtime.State<Int>,
): Modifier = bottomInsetPadding { (ime.value - navBarPx).coerceAtLeast(0) }
