
package com.suixing.agent.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.data.AppSettings
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.chat.VoicePhase
import com.suixing.agent.voice.Dictation
import com.suixing.agent.voice.DictationRecorder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    val level = remember { mutableFloatStateOf(0f) }
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
            var wav: File? = null
            val outcome = try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { wav = recorder.stop() }
                val recorded = wav
                if (recorded == null) {
                    phase = VoicePhase.Failed("没有录到声音", 0)
                    return@launch
                }
                Dictation.transcribe(settings, recorded)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { wav?.delete() }
            }
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
        // Assign before the recorder thread can detect silence.
        recorder.onAutoStop = { scope.launch { finish() } }
        phase = VoicePhase.Listening
        if (!recorder.start()) {
            phase = VoicePhase.Failed("拿不到麦克风", 0)
            return
        }
    }

    DisposableEffect(Unit) {
        begin()
        onDispose { recorder.cancel() }
    }
    // 秒数与竖条：从录音器读，10 次/秒足够，也省掉一条动画。
    LaunchedEffect(phase) {
        while (phase is VoicePhase.Listening) {
            level.floatValue = recorder.level
            elapsed = recorder.elapsedMs / 1000 * 1000
            delay(100)
        }
        level.floatValue = 0f
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
                        VoiceBars(level = { level.floatValue }, active = listening, tint = scheme.primary)
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
internal fun VoiceBars(level: () -> Float, active: Boolean, tint: Color) {
    val shape = VoiceBarWeights
    val density = LocalDensity.current
    // Match Row's per-child pixel rounding, including fractional display densities.
    val barWidth = with(density) { 3.dp.roundToPx() }
    val canvasWidth = with(density) { (barWidth * (shape.size * 2 - 1)).toDp() }
    Canvas(Modifier.width(canvasWidth).height(22.dp)) {
        val shown = if (active) level() else 0.12f
        shape.forEachIndexed { index, weight ->
            val barHeight = (6f + 14f * shown * weight).dp.toPx().roundToInt().coerceIn(0, size.height.toInt())
            val radius = minOf(2.dp.toPx(), barWidth / 2f, barHeight / 2f)
            drawRoundRect(
                color = tint.copy(alpha = if (active) 1f else 0.5f),
                topLeft = Offset((index * barWidth * 2).toFloat(), ((size.height.toInt() - barHeight) / 2).toFloat()),
                size = Size(barWidth.toFloat(), barHeight.toFloat()),
                cornerRadius = CornerRadius(radius),
            )
        }
    }
}

/** 语音条权重（中间最高）。 */
private val VoiceBarWeights = floatArrayOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
