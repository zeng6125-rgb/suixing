package com.suixing.agent.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val UI_OPERATION_HOLD_MS = 800L

internal data class UiOperationOwner(val chatId: String, val runId: String)
internal enum class UiOperationResult { Success, Failure, Cancelled }
internal data class UiOperationSnapshot(
    val label: String,
    val owner: UiOperationOwner? = null,
    val result: UiOperationResult? = null,
    val cancelRun: (() -> Boolean)? = null,
)

internal class UiOperationStatus {
    companion object {
        val shared = UiOperationStatus()
    }

    private val active = linkedMapOf<Long, UiOperationSnapshot>()
    private val sessions = linkedMapOf<UiOperationOwner, UiOperationSnapshot>()
    private val owners = MutableStateFlow<Set<UiOperationOwner>>(emptySet())
    val ownersFlow = owners.asStateFlow()
    val hasSession: Boolean get() = owners.value.isNotEmpty()
    private var nextToken = 0L
    private var completed: UiOperationSnapshot? = null
    private var completedUntil = 0L
    private var captureDepth = 0

    @Synchronized
    fun begin(label: String, owner: UiOperationOwner? = null, cancelRun: (() -> Boolean)? = null): Long {
        completed = null
        val operation = UiOperationSnapshot(label, owner, cancelRun = cancelRun)
        if (owner != null) {
            sessions.remove(owner)
            sessions[owner] = operation.copy(label = "正在思考")
            owners.value = sessions.keys.toSet()
        }
        return (++nextToken).also { active[it] = operation }
    }

    @Synchronized
    fun end(token: Long, completed: Boolean, now: Long, cancelled: Boolean = false): Boolean {
        val operation = active.remove(token) ?: return false
        if (cancelled) operation.owner?.let(::finish)
        if (token != nextToken) return false
        val result = when {
            cancelled -> UiOperationResult.Cancelled
            completed -> UiOperationResult.Success
            else -> UiOperationResult.Failure
        }
        val suffix = when (result) {
            UiOperationResult.Success -> "完成"
            UiOperationResult.Failure -> "失败"
            UiOperationResult.Cancelled -> "已停止"
        }
        this.completed = operation.copy(label = operation.label.removePrefix("正在") + suffix, result = result)
        completedUntil = now + UI_OPERATION_HOLD_MS
        return true
    }

    @Synchronized
    fun finish(owner: UiOperationOwner) {
        sessions.remove(owner)
        active.entries.removeAll { it.value.owner == owner }
        if (completed?.owner == owner) completed = null
        owners.value = sessions.keys.toSet()
    }

    @Synchronized
    fun snapshot(now: Long): UiOperationSnapshot? {
        if (now >= completedUntil) completed = null
        if (captureDepth > 0) return null
        return active.values.lastOrNull() ?: completed ?: sessions.values.lastOrNull()
    }

    fun current(now: Long): String? = snapshot(now)?.label

    @get:Synchronized
    val hiddenForCapture: Boolean get() = captureDepth > 0

    @Synchronized fun hideForCapture() { captureDepth++ }
    @Synchronized fun restoreAfterCapture() { captureDepth-- }
}

internal fun uiOperationInjectsInput(label: String): Boolean = when (label) {
    "正在点击", "正在长按", "正在滑动", "正在输入", "正在发送按键", "正在操作屏幕" -> true
    else -> false
}

internal fun uiOperationLabel(tool: String, displayId: Int, screenshotPath: String? = null): String? {
    if (displayId >= 0) return null
    return when (tool) {
        "ui_dump" -> "正在读取屏幕"
        "ui_screenshot" -> if (screenshotPath.isNullOrBlank()) "正在截图" else null
        "ui_tap", "ui_click_text" -> "正在点击"
        "ui_long_press" -> "正在长按"
        "ui_swipe" -> "正在滑动"
        "ui_type", "ui_type_into" -> "正在输入"
        "ui_key" -> "正在发送按键"
        "ui_current_app" -> "正在识别应用"
        "ui_wait" -> "正在等待"
        "open_app" -> "正在打开应用"
        else -> null
    }
}
