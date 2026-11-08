package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.R
import com.suixing.agent.data.AppSettings
import com.suixing.agent.ui.components.CircleActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Composer subscriptions and event adapters have their own recomposition boundary. */
@Composable
internal fun BoxScope.ChatComposerPanel(
    state: ChatUiState,
    settings: AppSettings,
    actions: ChatScreenActions,
    inputBindings: ChatInputBindings,
    dialogs: ChatDialogState,
    contextReadout: ChatContextReadout,
    subagentUi: SubagentUiState,
    slashTools: List<Pair<String, String>>,
    latestUserAt: Long,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    scroll: ChatScroll,
    itemCount: Int,
    imePxState: State<Int>,
    onOpenSettings: () -> Unit,
    jumpToLatest: () -> Unit,
    onHeightChanged: (Dp) -> Unit,
) {
    val overhead = contextReadout.overhead
    val contextUsed = contextReadout.contextUsed
    val assistant = contextReadout.assistant
    val contextWindow = contextReadout.contextWindow
    val reading = contextReadout.reading
    val draft = state.draft
    val attachments = inputBindings.controller.attachments
    var todoSheetOpen by dialogs.todoSheetOpenState
    fun currentInput(): String = inputBindings.controller.currentInput()
    fun setDraft(
        text: String = currentInput(),
        uri: String? = draft.attachmentUri,
        name: String? = draft.attachmentName,
        imageNames: List<String> = draft.imageNames,
    ) = inputBindings.controller.setDraft(text, uri, name, imageNames)
    suspend fun restoreQueuedSteer(steer: PendingSteer): Int? = inputBindings.controller.restoreQueuedSteer(steer)
    val composerContextBreak = remember(overhead, contextUsed) {
        overhead?.let { (system, tools) ->
            val history = (contextUsed - system - tools).coerceAtLeast(0)
            if (contextUsed > 0) Triple(system, tools, history) else Triple(0, 0, 0)
        }
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
        ComposerDraftText(actions, state.conversationId) { input ->
        val goal by actions.goal.collectAsStateWithLifecycle()
        Composer(
            subagentQueue = {
                SubagentInlineQueue(state.conversationId, state.liveRunId, state.runningHere) { subagentUi.show(it) }
            },
            input = input,
            slashTools = slashTools,
            goal = goal?.takeIf { it.showInComposer(state.conversationId, latestUserAt) },
            onClearGoal = rememberStableCallback0 { actions.clearGoal() },
            onResumeGoal = rememberStableCallback0 { actions.resumeGoal() },
            onInput = rememberStableCallback1<String> { setDraft(text = it) },
            onRestoreSteer = rememberStableCallback2<PendingSteer, Boolean, Unit> { steer, nextTurn ->
                scope.launch {
                    val missing = try {
                        restoreQueuedSteer(steer)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // The row stays in its queue: the user can try again.
                        snackbarHostState.showSnackbar(
                            "恢复失败，队列里的这条还在：${failure.message ?: "未知错误"}",
                        )
                        return@launch
                    }
                    if (missing == null) {
                        snackbarHostState.showSnackbar("会话已切换，这条仍留在队列里")
                        return@launch
                    }
                    // Commit first, retract second (issue #76 F-01): the queue entry is
                    // what the user falls back to when the restore did not land.
                    if (nextTurn) actions.retractTurn(state.conversationId, steer.id) else actions.retractSteer(steer.id)
                    if (missing > 0) {
                        snackbarHostState.showSnackbar(
                            "有 $missing 张图片未能恢复，文字已放回输入框",
                        )
                    }
                }
            },
            running = state.runningHere,
            settings = settings,
            assistantId = state.assistantId,
            assistantName = assistant?.label ?: "JAgent",
            contextWindow = contextWindow,
            contextUsed = reading?.tokens,
            contextEstimated = reading?.estimated == true,
            contextBreak = composerContextBreak,
            onContextPanelOpen = rememberStableCallback0 { actions.refreshContextOverhead() },
            conversationProviderId = state.providerId,
            conversationModel = state.model,
            conversationReasoningEffort = state.reasoningEffort,
            onChooseReasoning = rememberStableCallback1 { actions.chooseReasoning(it) },
            // The pick belongs to the conversation, not to the settings: it is
            // written onto this chat alone, so choosing an endpoint here cannot
            // change what any other conversation — or the app-wide default — runs.
            onPickModel = rememberStableCallback2 { provider, model ->
                actions.chooseModel(provider.id, model)
            },
            onPrefetchWindows = rememberStableCallback0 { actions.prefetchWindows() },
            pendingSteers = state.pendingSteers,
            pendingTurns = state.pendingTurns,
            onPromoteTurn = rememberStableCallback1 { id ->
                actions.promoteTurn(state.conversationId, id)
            },
            onRetractTurn = rememberStableCallback1 { id ->
                actions.retractTurn(state.conversationId, id)
            },
            todos = state.todos,
            onOpenTodos = rememberStableCallback0 { todoSheetOpen = true },
            onPromoteSteer = rememberStableCallback1 { actions.promoteSteer(it) },
            onRetractSteer = rememberStableCallback1 { actions.retractSteer(it) },
            attachments = attachments,
            onRemoveAttachment = rememberStableCallback1 { index ->
                actions.removeAttachmentAt(state.conversationId, index)
                setDraft(imageNames = draft.imageNames.toMutableList().apply { removeAt(index) })
            },

            onPickImage = rememberStableCallback0 { inputBindings.pickImage() },
            onTakePhoto = rememberStableCallback0 { inputBindings.takePhoto() },
            onPickFile = rememberStableCallback0 { inputBindings.pickFile() },
            onSend = rememberStableCallback0 {
                // The view model clears this conversation's draft on success;
                // a refused send keeps the text where the user left it.
                val (body, images) = withAttachment(attachments, input)
                val accepted = actions.send(body, images)
                // 斜杠指令也创建消息与工具卡；只有发送被接受才回到最新。
                if (shouldJumpToLatest(accepted)) {
                    // 发送 = 明确要回到最新：先放行追底再跳，并清掉阅读位置记忆。
                    scroll.allowBottomResume = true
                    scroll.stickToBottom = true
                    jumpToLatest()
                }
            },
            onStop = rememberStableCallback0 { actions.stop() },
            onSteer = rememberStableCallback2 { text, images -> actions.steer(text, images) },
            onRequestCompress = rememberStableCallback0 {
                // run 中：请引擎在轮次边界折。**空闲时不在这里压** —— 菜单会改走
                // onCompressNow（两条路径分开，压缩过程才画得出状态行）。
                actions.requestCompress()
            },
            onCompressNow = rememberStableCallback0 { actions.compressNow() },
            onFocused = rememberStableCallback0 {
                // 底锚：只有本来就在底的转录才跟着键盘走；停在会话中段的不动（首可见项
                // 被 LazyColumn 钉住，键盘挤压视口不会挪阅读位）。回到底＝贴底。
                if (scroll.stickToBottom && itemCount > 0) {
                    scope.launch { scroll.toBottom() }
                }
            },
            onMic = rememberStableCallback0 { inputBindings.openMic() },
            voiceInput = settings.voiceInput,
            approvalMode = state.approvalMode ?: settings.approvalMode,
            // Written through the same repository the settings page writes, so the two
            // entries cannot drift: the page re-reads the flow this update feeds.
            onPickApproval = rememberStableCallback1 { mode ->
                actions.chooseApproval(mode)
            },
            onOpenSettings = onOpenSettings,
            jumpToLatest = rememberStableCallback0 { jumpToLatest() },
            // 按钮已移到 composer 之外（见下），这里只保留参数以免动调用链。
            showJump = false,
            onHeightChanged = rememberStableCallback1(onHeightChanged),
            // 底锚：键盘起落改的是列表底 padding，index0 就在底边上 ⇒ 一个滚动都不需要。
        )
        // 「回到最新」浮钮：**画在 composer 之外**（兄弟节点），高度不参与 composer 的
        // 测量 ⇒ 它出现/消失不会改底部让位、不会把转录顶上去（用户报的「往上滑一点点就
        // 跳」）。位置：composer 顶再往上 12dp，右边缘 40dp 离屏边（与回合导航条留 12dp 气）。
        // 「回到最新」浮钮自己订阅 atBottom（见 [JumpToLatestButton] 的说明）。
        JumpToLatestButton(
            scroll = scroll,
            loading = state.loading,
            onClick = { jumpToLatest() },
        )
        }
    }
}
/**
 * 「回到最新」浮钮。
 *
 * **自己订阅 `atBottom`**（性能审计 A2）：原来是在 ChatScreen 的组合体里读它 ——
 * 到/离底那一下会重组**整页**（约 1959 行、含 60 参数的 Composer），而这个浮钮只是页角
 * 一枚按钮。抽成独立组合后，`atBottom` 翻转的失效范围只剩它自己。
 */
@Composable
private fun BoxScope.JumpToLatestButton(
    scroll: ChatScroll,
    loading: Boolean,
    onClick: () -> Unit,
) {
    if (scroll.atBottom || loading) return
    CircleActionButton(
        icon = ImageVector.vectorResource(R.drawable.ic_arrow_down),
        contentDescription = "回到最新",
        onClick = onClick,
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
