package com.suixing.agent.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.voice.Dictation
import com.suixing.agent.voice.DictationRecorder
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.forAssistant
import com.suixing.agent.data.groupModels
import com.suixing.agent.data.label
import com.suixing.agent.data.modelOffers
import com.suixing.agent.data.choiceFor
import com.suixing.agent.ui.assistant.AssistantAvatar
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.DrawerButton
import com.suixing.agent.ui.components.FlatIconButton
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.LocalPathClick
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MarkdownText
import com.suixing.agent.ui.components.PressPill
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.components.markdownBody
import com.suixing.agent.ui.components.rememberPopupMotion
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
internal fun ChatTopBar(
    title: String,
    onNew: () -> Unit,
    /** 标题胶囊右侧的入口（用户：「原圆环的内容移至标题胶囊右侧，可以换个 icon」）；null 不画。 */
    contextEntry: (@Composable RowScope.() -> Unit)? = null,
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
        // Both discs come from the shared helpers at their 40dp/20dp default — see the
        // table in CircleActionButton — so the header, the composer and a page's back
        // button are the same disc. The capsule between them is [PressPill], the same
        // shape and shadow the composer's input uses: this bar floats over the transcript
        // and has no surface of its own, so the two pills are what the user reads as "a
        // field I can type in" and "the conversation I am in".
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_menu_soft),
            contentDescription = "打开侧边栏",
            onClick = { openDrawer?.invoke() },
        )
        PressPill(
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
        ) {
            val style = titleBarStyle()
            Text(
                text = title,
                // **标题要让位**（用户：「标题一长就把会话数据按钮挤没了」）：Row 先量无 weight 的
                // 子项（右侧入口按钮拿到它的固有宽度），标题再拿剩下的、超长省略 —— 按钮永远不会
                // 被挤走。之前标题没有宽度约束，长标题按固有宽吃满整行，按钮和 Spacer 一起被压成 0。
                modifier = Modifier.weight(1f),
                // 行盒收紧到字形高度，居中才是真的居中（用户：「标题是不是没有垂直居中」）。
                style = style.copy(
                    lineHeight = style.fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (contextEntry != null) {
                contextEntry.invoke(this)
            }
        }
        FlatPainterButton(
            painter = painterResource(R.drawable.ic_compose),
            contentDescription = "新对话",
            onClick = onNew,
        )
    }
}

/** The bar's title: 15sp medium, scaled off titleMedium so 字号 still applies. */
@Composable
internal fun titleBarStyle(): TextStyle {
    val title = MaterialTheme.typography.titleMedium
    return title.copy(fontSize = title.fontSize * (15f / 17f))
}

/**
 * The thinking level a run in this conversation would actually send.
 *
 * One chain, read by both the caption and the sheet so they cannot disagree with the wire:
 * the conversation's own pick first, then the endpoint's own declaration (only meaningful
 * while that endpoint owns its sampling), then the app-wide default. The view model's
 * `chooseReasoning` writes to the first link and nothing else.
 */
internal fun reasoningWire(
    settings: AppSettings,
    provider: ProviderConfig?,
    conversationLevel: String?,
): String = conversationLevel
    ?: provider?.takeIf { it.ownSampling }?.reasoningEffort
    ?: settings.reasoningEffort

/** A flat text dropdown: label plus a caret, no container of its own. */
@Composable
internal fun UnifiedSelector(
    settings: AppSettings,
    /** The conversation's persona and its own pick, so the caption names what a run sends. */
    assistantId: String?,
    conversationProviderId: String?,
    conversationModel: String?,
    /** The conversation's own thinking level, if it ever picked one. */
    conversationReasoningEffort: String?,
    onClick: () -> Unit,
) {
    val assistant = assistantId?.let { settings.assistant(it) } ?: settings.activeAssistant()
    val choice = settings.choiceFor(conversationProviderId, conversationModel, assistant)
    val provider = settings.providers.firstOrNull { it.id == choice.providerId }
        ?: settings.providers.firstOrNull {
            it.baseUrl.trimEnd('/') == choice.baseUrl.trimEnd('/')
        }
    val wire = reasoningWire(settings, provider, conversationReasoningEffort)
    val level = REASONING_LEVELS.firstOrNull { it.first == wire }?.second ?: "默认"
    val model = provider?.displayModel(choice.model)
        ?.takeIf { it != choice.model }
        ?: choice.model.substringAfter('/').ifBlank { "选模型" }
    Row(
        modifier = Modifier
            // No cap on the row: the composer's own Spacer(weight) absorbs whatever is
            // left, and capping the row was what squeezed the name down to a single
            // ellipsis while 200dp of the row sat empty. The name carries its own cap
            // instead, and the badge and the caret take the width they need.
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = model,
            // labelMedium as it comes: it is 13sp already, and writing the same number again
            // as a literal also pinned the size against 「外观 → 字号」, which scales the ramp.
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Weighted, so it is measured last and gets whatever the badge and the caret leave
            // — they are fixed short things and must never be the ones to give way. `fill =
            // false` keeps a short name from stretching its own tap target across the row.
            // Wide enough for a real model id (Qwen3.8-Flash), narrow enough that the badge,
            // the ring, the mic and the send button still fit one row.
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 160.dp),
        )
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                text = level,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                // Never ellipsized: the level is a fixed short word, and a "xh…" badge is
                // both unreadable and pointless — the name beside it is what gets cut.
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * What the transcript area holds while the conversation to open is still being read.
 *
 * Deliberately almost nothing: the header is already drawn (it is identical in every state)
 * and the body carries one quiet line. Anything more — the empty state, an input field with
 * its hint — is a conversation the user is not in, which is what made a cold start look like
 * it had opened a new chat first and then jumped to the real one.
 */
@Composable
internal fun LoadingSkeleton() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "加载会话中…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * Every model the user ticked used to live in a dropdown here; the 生成设置 sheet
 * owns the list now, so this menu is gone with its last caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyHero(onPick: (String) -> Unit, assistant: Assistant? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own mark on its gradient disc: the launcher asset is pure white for
        // the home screen, and white on a light canvas would vanish. A persona with an uploaded
        // picture shows that picture instead — this hero is where the user is about to talk to
        // *it*, and an empty chat was the one assistant surface still showing the app's feather.
        if (assistant?.avatarPath.isNullOrBlank()) {
            SuixingMark(size = 92.dp)
        } else {
            AssistantAvatar(assistant = assistant, size = 92.dp)
        }
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
internal fun MessageRow(
    row: ChatRow,
    assistantName: String,
    assistantFace: String = "",
    /** The persona's uploaded picture, if it has one; see [AssistantAvatar]. */
    assistantAvatarPath: String = "",
    showReasoning: Boolean,
    expandTools: Boolean,
    /** 外观 → 历史思考块默认展开; see [ReasoningBlock.expandReasoning]. */
    expandReasoning: Boolean = true,
    reasoningMaxHeight: Dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    /** What this row's whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
    /** 「提供方 / 模型」 of the round this row shows; blank when it cannot be resolved. */
    engineLabel: String = "",
    /** The screen's table of open thinking blocks; see [ReasoningExpansion]. */
    reasoningExpansion: ReasoningExpansion,
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

        // 整张卡片包一层 SelectionContainer（用户：「气泡外层没包」）：只包 MarkdownText 时，
        // 从回答拖到思考块/工具块就断在边界上。**只对已落地的行**加 —— 流式行的守卫见
        // AssistantRow 里 Part.Text 分支的注释（实时气泡每帧重测会闪），那一处不动。
        // 按需挂载（#27/#42③）：平时不包（重卡组合减负），长按武装后才可选中。
        Roles.ASSISTANT -> SelectableWhenArmed {
            AssistantRow(
                // The setting hides thinking entirely; everything else keeps its place in
                // the sequence either way.
                //
                // **记住过滤结果**：`List<Part>` 是 unstable 参数，每次重组都 `filterNot` 出新 list
                // ⇒ 下游（每个 Markdown 块、思考列表、工具卡）永远跳不过。`row.parts` 本身跨重组
                // 是稳定的（`groupRows` 按 revision 记忆），所以这个 remember 会命中。
                parts = remember(row.parts, showReasoning) {
                    if (showReasoning) row.parts else row.parts.filterNot { it is Part.Thinking }
                },
                streaming = false,
                name = assistantName,
                face = assistantFace,
                avatarPath = assistantAvatarPath,
                branch = row.branch,
                usage = row.usage,
                contextWindow = contextWindow,
                runElapsedMs = runElapsedMs,
                engineLabel = engineLabel,
                message = row.message,
                expandTools = expandTools,
                expandReasoning = expandReasoning,
                reasoningMaxHeight = reasoningMaxHeight,
                followReasoning = followReasoning,
                bubbleMaxWidth = bubbleMaxWidth,
                reasoningExpansion = reasoningExpansion,
                onBranch = onBranch,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
            )
        }

        // 失败回执：run 最终失败时 VM 落盘的一条 system 消息。它不是模型的回答，所以不带头像、
        // 不带操作行 —— 一条全宽的红色小卡，像气泡一样留在会话里，重开还在。
        Roles.SYSTEM -> FailureReceiptRow(text = row.message.text)

        // A tool result with no bubble above it still renders as one, card only. It is
        // `row.parts` rather than the row's own message: once this card is the run's open
        // bubble, the calls that followed it — and a 插话 typed while they ran — live in the
        // parts, and rebuilding from the message alone would draw only the first of them.
        else -> SelectableWhenArmed {
            AssistantRow(
                parts = row.parts,
                streaming = false,
                face = assistantFace,
                avatarPath = assistantAvatarPath,
                bubbleMaxWidth = bubbleMaxWidth,
                reasoningExpansion = reasoningExpansion,
            )
        }
    }
}

/**
 * The user's own line, right-aligned.
 *
 * [onEdit] is still what 修改 goes through, but it is reached from the actions row under the
 * bubble — never from a long press: that gesture belongs to text selection, which is what a
 * user reaches for when they want to copy what they wrote.
 */
/** 气泡统一圆角：文件级常量，避免每次组合分配。 */
private val BubbleShape = RoundedCornerShape(24.dp)

@Composable
internal fun UserBubble(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(max = 300.dp),
        shape = BubbleShape,
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
            message.allImages.forEach { ImageBlock(it) }
            if (message.text.isNotBlank()) {
                // The user's own words are plain text, never parsed as Markdown — someone typing
                // "1. do this" means the list item, not a heading. The style is still the
                // assistant body's, so the two bubbles agree on size and leading.
                // Selectable: long-press is how a phone copies text, and the bubble used to
                // spend that gesture on the edit dialog instead.
                SelectableWhenArmed {
                    Text(message.text, style = markdownBody())
                }
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
internal fun MessageActions(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    // 复制走 [rememberCopyToClipboard]：写剪贴板 + Toast（用户：「复制后给个提示」）。
    val copy = rememberCopyToClipboard()
    Row(
        modifier = Modifier.padding(top = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TinyAction(ImageVector.vectorResource(R.drawable.ic_copy), "复制") {
            copy(message.text)
        }
        TinyAction(ImageVector.vectorResource(R.drawable.ic_square_pen), "修改") { onEdit(message) }
    }
}

@Composable
internal fun TinyAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
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

/**
 * The reply's own numbers, in the card's bottom row: two buttons, one for the clock and one
 * for the cost.
 *
 * They are two questions — 「它花了多久」 and 「它用了多少」 — so they are two controls rather
 * than one card with everything in it; each opens its own detail. The numbers come off the
 * stored message, so a reopened conversation shows the same figures it showed when the answer
 * arrived, and a field the endpoint never reported is left out rather than filled with a zero.
 *
 * Both cards open upward: the row sits at the bottom of an answer, and a card opening downward
 * would cover the composer.
 */
@Composable
internal fun TurnMetrics(
    /** What the whole run took, not one round's call. */
    elapsedMs: Long,
    /** How long the endpoint took to say anything at all; 0 when it never streamed. */
    firstTokenMs: Long,
    promptTokens: Int,
    completionTokens: Int,
    cachedTokens: Int,
    /** 「提供方 / 模型」 that produced this round; blank leaves the line out. */
    engineLabel: String = "",
    /** 官方口径 TPS（ΣcompletionTokens / ΣdecodeMs），由调用方从 [Usage.perSecond] 算好传入。 */
    tps: Double = 0.0,
) {
    val total = promptTokens + completionTokens
    if (elapsedMs <= 0 && total <= 0) return
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<MetricCard?>(null) }
    // What the card last showed: the popup stays composed through its exit animation, and an
    // empty card sliding away is worse than the one the user just closed.
    var shown by remember { mutableStateOf(MetricCard.TIME) }
    // `open` read once, not asserted: the check and the use are two reads of a state that a
    // recomposition can move between, and `!!` on the second is the shape that turns that into a
    // crash. Same fix as the one in Common.kt.
    open?.let { shown = it }
    val elapsed = remember(elapsedMs) {
        String.format(Locale.getDefault(), "%.1f秒", elapsedMs / 1000.0)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (elapsedMs > 0) {
                MetricButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_clock),
                    text = elapsed,
                    label = "用时详情",
                    onClick = { open = MetricCard.TIME },
                )
            }
            if (total > 0) {
                MetricButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_database),
                    text = remember(total) { "用量 ${shortTokens(total)} tok" },
                    label = "用量详情",
                    onClick = { open = MetricCard.USAGE },
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart)) {
            // Focus-less, like the composer's own cards and for the same reason (see [MenuCard]'s
            // `focusable`): a focusable popup window takes the keyboard away from the field, and a
            // reader looking up what a turn cost has not left the conversation. This card sits in
            // the transcript, so it was the one place still doing it — the composer's two cards
            // were already focus-less. A focus-less window can see neither a tap outside itself
            // nor BACK, so both are handled here: the scrim is a window of its own and is composed
            // before the card, because windows stack in composition order.
            //
            // 纯遮罩：它只负责接住卡片的退场时间（卡片自己的 140ms 动画在 [MenuCard] 里），所以
            // 这里不加动画修饰符 —— 它本来就什么都不画。`keeping` 只做一件事：退场那 110ms 让这层
            // 仍在组合里，别让「点卡片外面」在卡片还看得见的时候漏到下面的对话流上。
            if (open != null) {
                Popup(properties = PopupProperties(focusable = false)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { open = null },
                    )
                }
                BackHandler { open = null }
            }
            val density = LocalDensity.current
            val topBarCardProvider = remember(density) {
                val gap = with(density) { 8.dp.roundToPx() }
                val edge = with(density) { 8.dp.roundToPx() }
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): androidx.compose.ui.unit.IntOffset {
                        // 与触发按钮左沿对齐（原来贴屏幕右边，所以都从右侧出现）。
                        val x = anchorBounds.left.coerceIn(
                            edge,
                            (windowSize.width - edge - popupContentSize.width).coerceAtLeast(edge),
                        )
                        val y = anchorBounds.top - popupContentSize.height - gap
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            }

            MenuCard(
                visible = open != null,
                onDismiss = { open = null },
                alignment = Alignment.TopStart,
                // **出现在按钮下方**（用户截图：原来盖在按钮上/上方）。8dp 间距，横向夹在屏幕内。
                // **provider 必须 remember**（优化清单 chat-P1）：原来这里在组合体里
                // `run { … }` 现造一个匿名对象，每次重组都是新实例 ⇒ `MenuCard` 参数恒变、
                // 弹卡开着时每次都重跑定位。
                popupPositionProvider = topBarCardProvider,
                offset = IntOffset.Zero,
                // The card's own Column already swallows the taps that land on it (see MenuCard).
                focusable = false,
            ) {
                // Intrinsic width: every row is as wide as the widest one, which is what puts the
                // numbers on one column while the popup still hugs its content. The cap is what
                // keeps a long 「提供方 / 模型」 line from pushing the card past the window.
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(
                            min = 208.dp,
                            max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp),
                        ),
                ) {
                when (shown) {
                    MetricCard.TIME -> {
                        if (elapsedMs > 0) RingDetail("总用时", elapsed)
                        if (firstTokenMs > 0) {
                            RingDetail(
                                "首 token 平均（TTFT）",
                                remember(firstTokenMs) {
                                    String.format(
                                        Locale.getDefault(),
                                        "%.1f秒",
                                        firstTokenMs / 1000.0,
                                    )
                                },
                            )
                        }
                        if (tps > 0) {
                            // 官方口径由调用方从 Usage.perSecond（Σcompletion ÷ ΣdecodeMs）算好传进来 ——
                            // 不在这里现算，因为 elapsedMs 是整轮 run 墙钟、completionTokens 是单轮，
                            // 口径错配会把 200 算成 40。显示用官方取整（≥10 整数、<10 一位小数）。
                            val tpsLabel = remember(tps) { "${Tps.format(tps)} tok/s" }
                            RingDetail("输出速度（TPS）", tpsLabel)
                        }
                    }

                    MetricCard.USAGE -> {
                        // Which endpoint answered, and which of its models: the two things every
                        // figure below is read against, and the only line here that is not a
                        // number. Left out when the id matches no endpoint on this device.
                        if (engineLabel.isNotBlank()) RingDetail("提供方 / 模型", engineLabel)
                        if (total > 0) RingDetail("本轮用量", "${shortTokens(total)} tok")
                        if (promptTokens > 0) {
                            RingDetail("输入", "${shortTokens(promptTokens)} tok")
                            RingDetail("缓存读取", "${shortTokens(cachedTokens)} tok")
                            RingDetail(
                                "未缓存输入",
                                "${shortTokens((promptTokens - cachedTokens).coerceAtLeast(0))} tok",
                            )
                            if (cachedTokens > 0) {
                                RingDetail(
                                    "缓存命中",
                                    remember(promptTokens, cachedTokens) {
                                        String.format(
                                            Locale.getDefault(),
                                            "%.2f%%",
                                            cachedTokens * 100.0 / promptTokens,
                                        )
                                    },
                                )
                            }
                        }
                        if (completionTokens > 0) {
                            RingDetail("输出", "${shortTokens(completionTokens)} tok")
                        }
                    }
                }
                }
            }
        }
    }
}

/** Which of the two detail cards [TurnMetrics] is showing. */
internal enum class MetricCard { TIME, USAGE }

/** One icon + value pair of [TurnMetrics], tappable: it opens that number's detail card. */
@Composable
internal fun MetricButton(icon: ImageVector, text: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 15dp, the same as the copy and edit icons beside it: at 14dp these two read as a
            // size smaller than the controls they sit next to.
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 文本选区容器。
 *
 * **曾经做过「按需武装」（长按超时才挂 `SelectionContainer`）—— 已废弃并回退**：
 * 真机反馈长按复制响应变慢、偶尔长按不出菜单、选区出现后滑动卡。原因是挂载时机错了：
 * 武装发生在长按超时**之后**，而选区手势在超时那一刻已经开始，容器在同一手势中途
 * 出现/嵌套重挂会把选区取消掉。选区容器必须在**内容出现时就在**。
 *
 * 滚动成本的账单因此回到「常驻」—— 但这是用户明确选择的取舍：复制是高频手势，
 * 滚动优化不能拿它换。
 */
@Composable
internal fun SelectableWhenArmed(content: @Composable () -> Unit) {
    SelectionContainer { content() }
}

@Composable
internal fun AssistantRow(
    /** The reply's pieces in arrival order: thinking, tools, prose, thinking again… */
    parts: List<Part>,
    streaming: Boolean,
    /** The assistant's name, for the card's own header line. */
    name: String = "随行",
    /** The persona's emoji face; blank falls back to the brand feather. */
    face: String = "",
    /** The picture the user uploaded for this persona; blank falls back to [face]. */
    avatarPath: String = "",
    /** The tool this reply is waiting on right now, so its card can say so live. */
    runningTool: String? = null,
    branch: Branch? = null,
    usage: Usage? = null,
    /** The engine's live speed, present only while this bubble is the one streaming. */
    liveStats: StreamStats? = null,
    /** The model's window, for the context readout; 0 means it was never set. */
    contextWindow: Int = 0,
    /** What the whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
    /** 「提供方 / 模型」 for the usage card; blank leaves that line out. */
    engineLabel: String = "",
    message: StoredMessage? = null,
    expandTools: Boolean = false,
    /** 外观 → 历史思考块默认展开; see [ReasoningBlock.expandReasoning]. */
    expandReasoning: Boolean = true,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 气泡宽度: the card's cap. [Dp.Infinity] leaves it at the row's own width. */
    bubbleMaxWidth: Dp = Dp.Infinity,
    /** The screen's table of open thinking blocks; see [ReasoningExpansion]. */
    reasoningExpansion: ReasoningExpansion,
    onBranch: (String, String) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
    onEdit: (StoredMessage) -> Unit = {},
) {
    Surface(
        // 外观 → 气泡宽度 first, then fillMaxWidth: the cap has to be the outer
        // constraint, or the inner fill would win and the card could never narrow.
        modifier = Modifier
            .widthIn(max = bubbleMaxWidth)
            .fillMaxWidth(),
        shape = BubbleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // A hairline instead of a shadow: elevation 0 keeps a long transcript flat, and
        // the outline is what separates a near-white card from a white canvas in light
        // mode, where the two surfaces are 8% apart.
        // 每帧新建 BorderStroke ⇒ remember 住（颜色变才重建；颜色在组合期先读）。
        border = run {
            val outline = MaterialTheme.colorScheme.outlineVariant
            remember(outline) { BorderStroke(1.dp, outline) }
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)) {
            // The avatar sits in the card's header rather than beside it: the reply is one
            // object, and an avatar floating outside the card made it two.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One composable for all three faces — the uploaded picture, the persona's
                // emoji, the brand feather — so this card, the assistant sheet and the editor
                // cannot disagree about what a persona looks like.
                AssistantAvatar(
                    face = face,
                    avatarPath = avatarPath,
                    size = 24.dp,
                )
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
            Spacer(Modifier.height(6.dp))
            val settled = usage?.takeIf { it.known }
            if (!streaming && (settled != null || parts.isNotEmpty())) {
                TurnSummary(
                    parts = parts,
                    usage = usage,
                    live = null,
                    contextWindow = contextWindow,
                    // The run's note, when there was one: it lives on the stats for as long as
                    // the run does, which is exactly the window in which this card is drawn
                    // for a live turn. A settled turn from an earlier launch has no note to
                    // show — the compression record is the separator card's job.
                    note = liveStats?.note.orEmpty(),
                    reasoningMaxHeight = reasoningMaxHeight,
                    followReasoning = followReasoning,
                    // The card's thinks are settled ones: this is what decides whether they open.
                    expandReasoning = expandReasoning,
                    // 工具块与运行时同一套默认展开设置（用户：「展开时原样展开」）。
                    expandTools = expandTools,
                    // The stored blocks answer to their message, so the table keeps their
                    // state apart from the live run's and from every other message's.
                    messageId = message?.id.orEmpty(),
                    reasoningExpansion = reasoningExpansion,
                    onEdit = onEdit,
                )
                // 头像行→10→首块、末块→10→正文/气泡底: the card's own air below is
                // this spacer, so a bubble without a card does not stack two of them.
                Spacer(Modifier.height(6.dp))
            }
            // R5：只随 parts 变。live 行每拍都会重建 parts（映射本身故意不 remember，
            // 见 LivePartsFreezeTest），但 `it is Part.Text` 只看元素**类型**、不看内容 ——
            // 元素实例相等时结果必然相等，所以这条 remember 不会冻住任何仍在增长的文字。
            val lastText = remember(parts) { parts.indexOfLast { it is Part.Text } }
            // key(index): without it every part's remembered state was allocated by
            // position, so a new round appearing at the front moved one block's switch onto
            // its neighbour.
            parts.forEachIndexed { index, part ->
                // Guarded before key(index), not inside it: a non-local return out of the
                // key group leaves that group unclosed, and Compose aborts the composition
                // with "Start/end imbalance". Same conditions, evaluated outside the group.
                if (part is Part.Thinking && !streaming) return@forEachIndexed
                if (part is Part.Tools && !streaming) return@forEachIndexed
                if (part is Part.Text && !streaming && index != lastText) return@forEachIndexed
                // **留痕与压缩块在存盘行只由「过程」卡画**（展开才会组合）：这里再画一次就是
                // 同一份内容出现两份（用户实测「展开后重试留痕重复」）。插话（Steer）不在此列 ——
                // 那是用户自己说的话，任何时候都要在正文流里看得见，所以它反而从展开体里删掉了。
                if (part is Part.Trace && !streaming) return@forEachIndexed
                if (part is Part.Compress && !streaming) return@forEachIndexed
                key(index) {
                    val last = index == parts.lastIndex
                    when (part) {
                        is Part.Thinking -> {
                            // A settled reply keeps its thinking inside the summary card
                            // alone: a second copy beside the answer is what made one
                            // answer read as a stack of blocks. Live it still streams here.
                            // 正在长的那一块才走打字机；已经结束的思考块一次给全（关掉开关时
                            // rememberTypedText 直接原样返回，这条分支等于不存在）。
                            // **打字机条件绝不能带 `&& last`**：思考块一旦不再是最后一个
                            // （正文/工具开始流），它当场退出打字机 ⇒ 没打完的字一次性全出 ——
                            // 这就是「先逐字、后瞬移」（用户连续反馈三轮）。
                            // `streaming` 本身够了：块还在屏幕上就继续把剩下的字打完。
                            val thinkingLive = streaming && last
                            ReasoningBlock(
                                // **直接渲染，不做打字机**（与正文、与 DSH 的流式一致）：
                                // 改文本式的打字机每拍都要重解析 Markdown，而且一旦起点被算成
                                // 全文就完全不逐字（表现随版本漂移）。光标直接贴真实末尾。
                                text = part.text,
                                // A live block is the one still growing; a settled round's
                                // thinking is complete even while the run continues.
                                streaming = thinkingLive,
                                // A live block keeps its slot for the whole run, and the
                                // stored copy of the same run answers to the same key.
                                blockId = thinkSlot(parts, index, "live"),
                                expansion = reasoningExpansion,
                                reasoningTokens = part.tokens,
                                elapsedMs = part.elapsedMs,
                                // Whether a block nobody has touched is open is the appearance
                                // setting's business, and the block owns that rule: live ones are
                                // always open, settled ones follow 历史思考块默认展开. A tap on
                                // the header is the only thing that closes one, and the expansion
                                // table remembers it across the hand-over to the stored message.
                                expandReasoning = expandReasoning,
                                maxHeight = reasoningMaxHeight,
                                followReasoning = followReasoning,
                            )
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        is Part.Tools -> {
                            // Same rule as thinking: settled tool rows live in the summary
                            // card's expanded list, not flat beside the answer.
                            // Consecutive tool rounds merge into one block: one card per
                            // run of calls, not a card inside a card per round.
                            // **记住合并结果**（性能）：`tools: List<ToolLine>` 是 unstable 参数。
                            // 开了 strong skipping 时按 **equals（结构）** 比较：复用同一实例能让
                            // `AbstractList.equals` 首行 `o === this` 短路，省掉逐条 `ToolLine` 的深比较
                            // （每条带 toolArgs / toolOutput 字符串，工具输出可能几十 KB）。
                            // **合并结果与"块尾下标"同一趟算完并 remember**（优化清单 chat-P2）：
                            // 原来 `tools` 在 remember 里扫一遍、紧接着又在组合体里线性扫到块尾
                            // 找 cursor ⇒ 多块 Tool 时是 O(n²)，且 cursor 每次重组都重算。
                            val bundle = remember(parts, index) {
                                val calls = buildList {
                                    var c = index
                                    while (c < parts.size && parts[c] is Part.Tools) {
                                        addAll((parts[c] as Part.Tools).calls)
                                        c++
                                    }
                                }
                                // cursor = 这一串 Tools 之后的第一块。
                                var cursor = index
                                while (cursor < parts.size && parts[cursor] is Part.Tools) cursor++
                                calls to cursor
                            }
                            val tools = bundle.first
                            val cursor = bundle.second
                            ToolBundle(
                                tools = tools,
                                // Only the newest block can be the one waiting on a tool.
                                runningTool = runningTool.takeIf { cursor > parts.lastIndex },
                                startExpanded = expandTools,
                            )
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        is Part.Text -> {
                            // Only the last stretch of prose is drawn once the run is over:
                            // the model's "let me check" before a tool is not part of its
                            // answer. While it streams every stretch shows, because that is
                            // what it is doing right now.
                            // Selectable once it has settled, and only then: text that grows a
                            // line at a time is not selectable in practice, and wrapping a
                            // container that re-measures its content on every frame is what
                            // made the live bubble flash the previous layout.
                            // Selectable from the frame AFTER the bubble lands, never in the
                            // frame it lands in. The hand-over from the live row to the
                            // stored one builds this subtree from scratch, and wrapping the
                            // whole answer in a SelectionContainer in that same frame makes
                            // it re-measure text that was laid out a frame ago without one —
                            // the frame that then still paints the old layout is the ghost
                            // at the end of every stream. One frame later the same wrap costs
                            // its measure and nothing is drawn stale.
                            // **初值按进入组合时的状态定**（滚动跳变的根因，用户实测
                            // 「助手→用户→助手」区滚动不连续）：原来是恒 false、下一帧翻 true，
                            // 滚动时每条已定型的行进入视口都会经历「无选区→有选区」的重新测量，
                            // 一行跳一帧。已定型的行从第一帧就是选区版 ⇒ 不再跳；
                            // 流式行保持原节奏（首帧无选区、下一帧加上，护住流结束那一帧的
                            // 「文本+选区同帧变化」重影）。
                            var selectable by remember { mutableStateOf(!streaming) }
                            if (!selectable) {
                                LaunchedEffect(Unit) {
                                    withFrameNanos { }
                                    selectable = true
                                }
                            }
                            // 流式时逐字放（外观 → 打字机效果）；settled 直接全文，选区也用全文。
                            // **与思考块完全同一套**（用户：正文的打字机一直不正常，「直接用思考
                            // 流式那一套」）：不再依赖 `part.bornAtMs` —— 那个值一旦大于 500ms
                            // 就会被判成"很久以前"，于是整段直接给全、一个字都不打。改用
                            // [rememberTypedTextFromBirth]：块首次组合时记下时刻，live 块必然
                            // 年龄≈0 ⇒ 永远从 0 开始打。
                            // **恢复打字机引入之前的渲染方式**（`git log -S rememberTypedText`
                            // 找到的那一版）：`MarkdownText(text = part.text)` + 默认
                            // `settled = true`（走块缓存）。那一版流式是正常的 —— 后来为了逐字
                            // 传入 `settled = !streaming` 把它切到了"每帧重解析"的路径，
                            // 整段文本每 16ms 重排一次，Compose 合并后就成了"一下子蹦出一大段"。
                            // 光标仍然内联（`cursor = streaming`），位置 = 真实文本末行末尾。
                            // **按 v0.8.100 那版恢复**（用户：「之前就是可以的」）：
                            // 直接渲染 `part.text`，不做打字机；`settled = false` 走
                            // `rememberGrowingMarkdown` 的**增量解析**路径（只重解析尾部），
                            // 而不是后来新增的 settled 分支。
                            // **逐字显影**（用户要"流式必须做好"）：流式通道修通之后
                            // （LiveParts 的版本号修复），打字机终于能真正工作 —— 之前它
                            // "不生效"是因为屏幕压根没在重组。
                            // live 块（bornAtMs > 0）从 0 开始逐字放；已定型/历史内容直接全文，
                            // 不重播。速度随积压自适应（Typewriter.typeStep），chunk 再大也连续。
                            val liveText = part.bornAtMs > 0L
                            val shown = rememberTypedText(
                                text = part.text,
                                enabled = streaming || liveText,
                                startAtZero = liveText,
                            )
                            if (streaming || !selectable || shown.length < part.text.length) {
                                MarkdownText(
                                    text = shown,
                                    settled = !streaming,
                                    // **正文不画光标**（用户：「正文要不就不要那个光标效果」）：
                                    // 正文是逐字显影的，光标跟着打字进度走本来就容易显得滞后；
                                    // 思考块保留光标（那里更新是逐行、观感更稳）。
                                    cursor = false,
                                )
                            } else {
                                SelectableWhenArmed { MarkdownText(text = part.text) }
                            }

                            if (!last) Spacer(Modifier.height(6.dp))
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
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        // 重试留痕：与思考块/工具块同一套栅格（36dp 行高 + 24dp 图标槽 + 8dp +
                        // labelMedium），所以图标与标题左边线和它们对齐；间距只用 part 之间
                        // 那 6dp，不再自己加边距、也不做位移。
                        is Part.Trace -> {
                            TraceBlock(part.note)
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        // 压缩块：位置就是它发生的地方。
                        is Part.Compress -> {
                            CompressionBlock(part.checkpoint)
                            if (!last) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            if (parts.isEmpty() && streaming) TypingDots()
            if (message != null && !streaming) {
                // The last block carries no trailing spacer of its own, so the air
                // above this row is its own 10dp.
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionBar(
                        branch = branch,
                        onBranch = onBranch,
                        onRegenerate = onRegenerate,
                    )
                    Spacer(Modifier.weight(1f))
                    // The round's clock and cost, right-aligned with the card's own copy and
                    // edit controls: they are all "about this answer", and the row reads as one
                    // group when they share the right edge.
                    TurnMetrics(
                        // The run's own clock when we have it: one round's call is not what
                        // the status line counted while the answer was arriving.
                        elapsedMs = if (runElapsedMs > 0) runElapsedMs else message.elapsedMs,
                        firstTokenMs = message.firstTokenMs,
                        promptTokens = message.promptTokens,
                        completionTokens = message.completionTokens,
                        cachedTokens = message.cachedTokens,
                        engineLabel = engineLabel,
                        // 官方口径 TPS：usage 是这个 run 所有轮的聚合（Σcompletion、ΣdecodeMs），
                        // perSecond 已按 decodeMs 算好。null（端点没报 usage）就不画那一行。
                        tps = usage?.perSecond ?: 0.0,
                    )
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
internal fun ActionBar(
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
                icon = ImageVector.vectorResource(R.drawable.ic_chevron_left),
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
                icon = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                label = "下一个回答",
                enabled = !atEnd,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index + 1]) },
            )
        }
        if (branch?.canRegenerate == true) {
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_rotate_cw),
                label = "重新回答",
                enabled = true,
                onClick = onRegenerate,
            )
        }
    }
}

