package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.chat.markdown.LocalPreparedMarkdown

/** Stored message rendering; transcript keys, paging and checkpoints stay with the list. */
@Composable
internal fun ChatMessageRowHost(
    row: ChatRow,
    readingKey: String,
    settings: AppSettings,
    assistant: Assistant?,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    runElapsedMs: Long,
    providerOfModel: Map<String, ProviderConfig>,
    reasoningExpansion: ReasoningExpansion,
    rowDocuments: RowMarkdownCache,
    actions: ChatScreenActions,
    onEdit: (StoredMessage) -> Unit,
) {
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
            // 3dp → 6dp 是「太挤，时间像贴在气泡上」那次改的；现在再放到 12dp
            // （用户要求），让时间读作一个完整的抬头行，与正文块之间拉开一档。
            // 注意：这个 Spacer 对**用户行与助手行共用**（都在同一个 Column 里），
            // 所以两边一起变，正好也保证同一条转录里两处抬头间距一致。
            Spacer(Modifier.height(12.dp))
        }
        // 稳定引用（issue #5）：每次重组新建 lambda ⇒ `MessageRow` 的 equals
        // 恒为 false ⇒ Compose 的跳过优化失效，每个可见项每帧都重组。
        val onBranch = remember(actions) {
            { turnId: String, runId: String -> actions.switchBranch(turnId, runId) }
        }
        val onRegenerate = remember(actions) {
            { actions.regenerate() }
        }
        // 「提供方 / 模型」也按模型缓存：原来每帧每个可见项都新建一个 String。
        // **第二个键必须包 IdentityKey**（`providerOfModel` 是普通 Map）：
        // 直接拿 Map 当 remember 键 = 每行每拍对它做整表深比较
        // （P 个 provider × models/enabled 列表 + aliases Map）。
        // 与文件上方自己写的「不要拿 providers 的 Map 当键」同一条原则。
        val engineLabel = remember(row.message.model, IdentityKey(providerOfModel)) {
            providerOfModel[row.message.model]
                ?.let { "${it.label} / ${it.displayModel(row.message.model)}" }
                ?: row.message.model
        }
        CompositionLocalProvider(
            LocalPreparedMarkdown provides rowDocuments.documentsFor(row.message.id).value,
            LocalReadingRowKey provides readingKey,
        ) {
            MessageRow(
                row = row,
                assistantName = assistant?.label ?: "JAgent",
                assistantFace = assistant?.face ?: "",
                assistantAvatarPath = assistant?.avatarPath ?: "",
                showReasoning = settings.showReasoning,
                expandTools = settings.expandTools,
                expandReasoning = settings.expandReasoning,
                reasoningMaxHeight = settings.reasoningHeight.dp,
                followReasoning = settings.followReasoning,
                bubbleMaxWidth = bubbleMaxWidth,
                assistantCard = settings.assistantCard,
                flatPad = settings.flatPaddingDp.dp,
                contextWindow = contextWindow,
                runElapsedMs = runElapsedMs,
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
}
