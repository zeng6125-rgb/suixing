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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.text.AnnotatedString
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

// 语音浮层：录音、转写、波形、引擎选择。

/**
 * 随行自己的听写：输入框上方一条细胶囊，屏幕上从头到尾只有它。
 *
 * 录音走 [DictationRecorder]（`AudioRecord`，16k/mono/PCM16 → wav），转写走 [Dictation]：把 wav
 * 发给用户已经配置好的那个服务商。**这条路完全不碰 `SpeechRecognizer`** —— 之前两版都栽在它上面：
 * 设备端模型没装、`EXTRA_PREFER_OFFLINE` 也拦不住 ROM 自己的识别面板，而那个面板被用户否了两次。
 *
 * 手势与按钮：长按胶囊＝说完了（失败态＝重来一次），「说完了」按钮做同一件事，× 取消；端点检测
 * （静音约 1.2 秒或录满 60 秒）会自己收尾。没配转写模型时**不录音**，直接说清楚并给一个「去设置」，
 * 绝不静默回落到系统识别；系统识别只在用户主动点「改用系统识别」时才出现。
 */
@Composable
internal fun VoiceOverlay(
    /** 这一份设置：转写要用它的服务商与模型，所以由调用方直接传进来。 */
    settings: AppSettings,
    /** How far up the composer reaches: the capsule sits just above it, never over it. */
    bottomInset: Dp,
    /** 「去设置」的去处：没填转写模型时用得上。 */
    onOpenSettings: () -> Unit,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<VoicePhase>(VoicePhase.Preparing) }
    // The microphone's own level, 0..1: what the capsule's bars are drawn from.
    var level by remember { mutableStateOf(0f) }
    var elapsed by remember { mutableStateOf(0L) }
    // Set the moment this card hands the job to the system's own speech screen. That screen is
    // a screen, not a dialog behind ours, so drawing the card while it is up stacks the two.
    var handedOff by remember { mutableStateOf(false) }
    // 我们自己的录音：这条路完全不碰 SpeechRecognizer，所以屏幕上不会出现任何系统识别面板。
    val recorder = remember { DictationRecorder(context) }
    val asrReady = remember(settings.asrModel, settings.baseUrl, settings.apiKey) {
        Dictation.configured(settings)
    }

    fun finish() {
        if (phase !is VoicePhase.Listening) return
        // The state moves first, on this thread: the guard above is what stops a second tap (and
        // the auto-stop that fires at the same moment) from stopping the recorder twice, and it
        // has to close before any of the work is handed off.
        phase = VoicePhase.Thinking
        scope.launch {
            // `stop()` writes the wav — up to sixty seconds of 16-bit mono at 16kHz, about 1.9MB —
            // and this runs from the card's own buttons and from the recorder's auto-stop, all of
            // them on the main thread. Writing it there is a stall the user sees as a frozen
            // capsule, so the file is written and the transcription requested off it.
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                phase = VoicePhase.Failed("没有录到声音", 0)
                return@launch
            }
            val outcome = Dictation.transcribe(settings, wav)
            wav.delete()
            outcome
                .onSuccess { text ->
                    onResult(text)
                    onDismiss()
                }
                .onFailure { error -> phase = VoicePhase.Failed(error.message ?: "转写失败", 0) }
        }
    }

    fun begin() {
        if (!asrReady) {
            // 不静默回落到系统识别：那正是用户否掉两次的东西，缺什么就说什么。
            phase = VoicePhase.Failed("还没填语音转写模型", 0)
            return
        }
        if (!recorder.start()) {
            phase = VoicePhase.Failed("拿不到麦克风", 0)
            return
        }
        // 端点检测在录音线程上判定，收尾必须回到主线程。
        recorder.onAutoStop = { scope.launch { finish() } }
        phase = VoicePhase.Listening
    }

    DisposableEffect(Unit) {
        begin()
        onDispose { recorder.cancel() }
    }
    // 秒数与竖条：从录音器读，10 次/秒足够，也省掉一条动画。
    LaunchedEffect(phase) {
        while (phase is VoicePhase.Listening) {
            level = recorder.level
            elapsed = recorder.elapsedMs
            delay(100)
        }
        level = 0f
    }
    // The way out when the in-app recognizer will not have us: the system's own speech
    // screen, which on this device is the one that can reach the installed recognizer.
    val systemInput = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrEmpty()) {
            onResult(text)
            // The same pair the in-app recognizer's onResults calls: the words go into the field
            // and the card closes. Without it the overlay stayed composed after the system screen
            // was gone — nothing drawn, because it had been handed off, but still open, so the
            // next tap on the mic (voiceOpen already true) brought an overlay back that could
            // never draw again.
            onDismiss()
        } else {
            // Backing out of the system screen is the user's own decision and says nothing;
            // an OK with no words is the recognizer having heard nothing, which used to look
            // exactly like a cancel.
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(context, "没有识别到内容", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }
    val useSystemInput: () -> Unit = {
        // 我们自己的录音要先放手，否则系统界面拿不到麦克风。
        recorder.cancel()
        phase = VoicePhase.Preparing
        // From here on the system screen owns the job, and this card stops drawing: it is not a
        // dialog behind ours, it is the foreground.
        handedOff = true
        try {
            systemInput.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                },
            )
        } catch (missing: ActivityNotFoundException) {
            // Nothing was handed off after all: putting the card back is the only way the user
            // ever sees why there is no way out on this device.
            handedOff = false
            phase = VoicePhase.Failed("这台设备没有可用的系统语音输入", 0)
        }
    }
    val failure = phase as? VoicePhase.Failed
    val listening = phase is VoicePhase.Listening
    val scheme = MaterialTheme.colorScheme
    val title = when {
        failure != null -> failure.message
        listening -> "正在听… " + formatDictationTime(elapsed)
        phase is VoicePhase.Thinking -> "正在转写…"
        else -> "正在准备麦克风…"
    }
    // Nothing to draw once the system screen has it. Everything above still runs — the launcher
    // and its result callback are what close this overlay from here.
    if (handedOff) return
    Box(Modifier.fillMaxSize()) {
        // Tap anywhere to give up — but with no scrim: the transcript stays readable while the mic
        // is open, which is the point of a capsule. The dark overlay over the whole screen is half
        // of what made the old card read as 丑.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + 8.dp)
                .fillMaxWidth()
                // One gesture for the two things a finger wants here: 长按 ends the sentence while
                // it is listening, and asks for one more try once it has failed. Everything else
                // that used to be a button on the old card lives here now.
                .pointerInput(phase) {
                    detectTapGestures(
                        onLongPress = {
                            // 长按＝「说完了」，正在录音时唯一的快捷手势；失败态长按＝重来一次。
                            if (listening) finish() else if (failure != null) begin()
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                contentColor = scheme.onSurface,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (failure == null) {
                        VoiceBars(level = level, active = listening, tint = scheme.primary)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = if (failure != null) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.titleSmall
                            },
                            color = if (failure != null) scheme.error else scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 转写走的是哪个模型，以及那个不占按钮的手势。留空时这行就是「去设置里
                        // 填一个」的提示，所以失败态的说明在下面那行按钮上。
                        Text(
                            text = when {
                                failure != null -> "长按重试"
                                listening -> "长按说完 · 转写：${settings.asrModel.ifBlank { "未配置" }}"
                                else -> "转写：${settings.asrModel.ifBlank { "未配置" }}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    CircleActionButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_x),
                        contentDescription = "取消",
                        onClick = onDismiss,
                        containerColor = scheme.surfaceContainerHighest,
                        contentColor = scheme.onSurfaceVariant,
                        shadowElevation = 0.dp,
                    )
                }
            }
            if (listening) {
                // 唯一的主按钮：说完了。端点检测也会自动收尾，这个按钮是给「我现在就说完了」用的。
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { finish() }) { Text("说完了") }
            }
            if (failure != null) {
                // 失败态两条路：补配置，或者重来。系统识别只在用户主动点的时候才出现 —— 那个面板
                // 被否掉过两次，不能自动弹。
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!asrReady) {
                        Button(onClick = { onOpenSettings(); onDismiss() }) { Text("去设置") }
                    } else {
                        TextButton(onClick = { begin() }) { Text("重试") }
                    }
                    TextButton(onClick = useSystemInput) { Text("改用系统识别") }
                }
            }
        }
    }
}

/**
 * Which recognizer an attempt is using, and what the capsule prints for it.
 *
 * The order is the fallback order: the on-device model answers without any system panel; the
 * classic service with `EXTRA_PREFER_OFFLINE` still answers locally on a ROM that ships an offline
 * engine; and only a device with neither falls back to the online service — the one that puts the
 * ROM's own speech screen on top of the app.
 */
internal enum class VoiceEngine(val label: String) {
    ON_DEVICE("设备端识别"),
    SYSTEM("系统识别服务（优先离线）"),
}

/**
 * 胶囊上的录音计时：`0:07`。分钟只在超过一分钟时出现，平时两位数字更安静。
 */
internal fun formatDictationTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) {
        "0:${seconds.toString().padStart(2, '0')}"
    } else {
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}

/**
 * Five bars that answer the microphone: taller as the level rises, and still while it is quiet.
 *
 * The middle one is the tallest so the shape reads as a level rather than as a random row of
 * blocks — the four grey squares the user saw were a placeholder, this is the real input.
 */
@Composable
internal fun VoiceBars(level: Float, active: Boolean, tint: Color) {
    // 权重表与圆角提到文件级：录音期间 level 每 100ms 变一次。
    val shape = VoiceBarWeights
    val shown = if (active) level else 0.12f
    Row(
        modifier = Modifier.height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        shape.forEach { weight ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((6f + 14f * shown * weight).dp)
                    .clip(VoiceBarShape)
                    .background(tint.copy(alpha = if (active) 1f else 0.5f)),
            )
        }
    }
}

/** 语音条权重（中间最高）。 */
private val VoiceBarWeights = floatArrayOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)

/** 语音条圆角。 */
private val VoiceBarShape = RoundedCornerShape(2.dp)
