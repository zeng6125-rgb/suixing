package com.suixing.agent.ui.automation

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.service.VirtualScreenOverlay
import com.suixing.agent.service.TouchFx
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SettingsSwitch
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.virtualscreen.VirtualScreenControl
import com.suixing.agent.virtualscreen.VirtualScreenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val UI_AUTOMATION_ROUTE = "ui_automation"

private data class LaunchChoice(val packageName: String, val label: String)

/** Automation controls live here; the display preview remains an independent overlay. */
@Composable
fun UiAutomationScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "UI自动化", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            item(key = "virtual-screen") { VirtualScreenCard() }
            item(key = "edge-light") { EdgeLightCard() }
            item(key = "automation-permissions") { AutomationPermissionsCard { onOpen("capability") } }
        }
    }
}

private fun VirtualScreenManager.State.forStatusCard() =
    copy(lastFrameAt = if (lastFrameAt > 0L) 1L else 0L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VirtualScreenCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by remember {
        // Only first-frame readiness affects this card, not the continuously changing timestamp.
        VirtualScreenManager.state.map { it.forStatusCard() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = VirtualScreenManager.state.value.forStatusCard())
    val previewVisible by VirtualScreenOverlay.visibility.collectAsStateWithLifecycle()
    val displayedId = state.displayId
    val displayedPreview = previewVisible
    var busyOperation by remember { mutableStateOf<String?>(null) }
    val busy = busyOperation != null
    var lastOpenedApp by remember(state.displayId) { mutableStateOf<LaunchChoice?>(null) }
    var choosingApp by remember(state.displayId) { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var noticeDisplayId by remember { mutableIntStateOf(displayedId) }
    var noticeIsError by remember { mutableStateOf(false) }
    var previewResetKey by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<LaunchChoice>?>(null) }
    var loadingApps by remember { mutableStateOf(false) }
    var appListError by remember { mutableStateOf("") }
    fun act(operation: String, action: suspend () -> Unit) {
        if (busyOperation != null) return
        busyOperation = operation
        scope.launch {
            try {
                notice = ""
                noticeDisplayId = displayedId
                noticeIsError = false
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previewResetKey++
                noticeIsError = true
                notice = error.message ?: "虚拟屏操作失败"
            } finally {
                busyOperation = null
            }
        }
    }
    fun loadApps() {
        if (loadingApps) return
        loadingApps = true
        appListError = ""
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) {
                    val manager = context.packageManager
                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    manager.queryIntentActivities(launcher, 0).mapNotNull { info ->
                        val name = info.activityInfo?.packageName ?: return@mapNotNull null
                        LaunchChoice(name, runCatching { info.loadLabel(manager).toString() }.getOrDefault(name))
                    }.distinctBy { it.packageName }.sortedBy { it.label }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appListError = "读取应用列表失败：${error.message ?: "暂时不可用"}"
            } finally {
                loadingApps = false
            }
        }
    }
    SuixingCard {
        AutomationCardHeader(
            title = "虚拟屏",
            icon = R.drawable.ic_picture_in_picture_2,
            subtitle = if (state.running) {
                val frameStatus = if (state.lastFrameAt > 0L) "画面已就绪" else "等待应用画面"
                "分辨率 ${state.width} × ${state.height} · $frameStatus"
            } else "让 Agent 在独立屏幕里操作应用。",
            trailing = {
                StatusPill(text = when {
                    !state.running -> "未开启"
                    state.lastFrameAt == 0L -> "等待画面"
                    else -> "运行中"
                }, tone = if (state.running && state.lastFrameAt > 0L) Tone.OK else Tone.OFF)
            },
        )
        Spacer(Modifier.height(SettingsStyle.FieldGap))
        if (state.running) {
            SettingRow(
                title = "悬浮预览",
                subtitle = if (displayedPreview) "显示中；收起后，屏内应用继续运行。" else "已收起；屏内应用继续运行。",
                control = {
                    key(displayedId, previewResetKey) {
                        SettingsSwitch(checked = displayedPreview, enabled = !busy,
                            onCheckedChange = { visible -> act(if (visible) "正在显示预览…" else "正在收起预览…") {
                                VirtualScreenControl.preview(context, visible, displayedId)
                            } })
                    }
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap),
                verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
            ) {
                Button(enabled = !busy, onClick = {
                    if (busyOperation == null) {
                        choosingApp = true
                        if (apps == null) loadApps()
                    }
                }) { Text("打开应用") }
                TextButton(enabled = !busy, onClick = { act("正在关闭虚拟屏…") {
                    VirtualScreenControl.close(displayedId)
                } }) { Text("关闭虚拟屏") }
            }
            lastOpenedApp?.let { app ->
                Text("最近从此页打开：${app.label}", style = SettingsStyle.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = SettingsStyle.FieldGap))
            }
            Text("关闭后，Agent 恢复操作主屏。", style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SettingsStyle.FieldGap))
        } else {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { act("正在开启虚拟屏…") {
                val opened = VirtualScreenControl.open(context)
                if (!VirtualScreenOverlay.isShowing) {
                    noticeDisplayId = opened.displayId
                    noticeIsError = true
                    notice = "虚拟屏已开启；显示预览需要无障碍或悬浮窗权限。"
                }
            } }) { Text("开启虚拟屏") }
        }
        busyOperation?.let { Text(it, style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SettingsStyle.FieldGap)) }
        if (notice.isNotBlank() && noticeDisplayId == displayedId) Text(notice, style = SettingsStyle.description,
            color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SettingsStyle.FieldGap))
    }
    if (choosingApp && state.running) {
        var pendingChoice by remember { mutableStateOf(lastOpenedApp) }
        var query by remember { mutableStateOf("") }
        var closing by remember { mutableStateOf(false) }
        val choices = remember(apps, query) {
            val search = query.trim()
            apps.orEmpty().let { all ->
                if (search.isEmpty()) all
                else all.filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
            }
        }
        SuixingDialog(
            onDismissRequest = { choosingApp = false },
            properties = DialogProperties(dismissOnBackPress = !closing, dismissOnClickOutside = !closing),
            title = { Text("在虚拟屏打开应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap)) {
                    CompactOutlinedTextField(value = query, onValueChange = { if (!closing) query = it }, enabled = !closing,
                        label = { Text("搜索应用名或包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    when {
                        loadingApps -> Text("正在读取应用列表…")
                        appListError.isNotBlank() -> {
                            Text(appListError, style = SettingsStyle.description, color = MaterialTheme.colorScheme.error)
                            TextButton(enabled = !closing, onClick = { loadApps() }) { Text("重试") }
                        }
                        apps.isNullOrEmpty() -> Text("未读取到可启动应用。")
                        choices.isEmpty() -> Text("没有匹配的应用。")
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(choices, key = { it.packageName }, contentType = { "launch-choice" }) { app ->
                                TextButton(enabled = !closing, onClick = { if (!closing) pendingChoice = app },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text((if (app.packageName == pendingChoice?.packageName) "✓ " else "") + app.label)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { dialog ->
                TextButton(enabled = !busy && !closing && !loadingApps &&
                    (apps?.any { it.packageName == pendingChoice?.packageName } == true), onClick = confirm@{
                    if (closing) return@confirm
                    val chosen = pendingChoice ?: return@confirm
                    val targetDisplayId = displayedId
                    closing = true
                    dialog.closeThen {
                        choosingApp = false
                        act("正在打开 ${chosen.label}…") {
                            VirtualScreenControl.launchTarget(context, chosen.packageName, targetDisplayId)
                            lastOpenedApp = chosen
                            noticeDisplayId = targetDisplayId
                            notice = "已在虚拟屏启动 ${chosen.label}。"
                        }
                    }
                }) { Text("打开") }
            },
            dismissButton = { dialog ->
                TextButton(enabled = !closing, onClick = {
                    if (!closing) {
                        closing = true
                        dialog.closeThen { choosingApp = false }
                    }
                }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EdgeLightCard() {
    val settings by remember {
        Core.settings.flow.map<AppSettings, Pair<Boolean, Boolean>?> { it.edgeLight to it.edgeLightInForeground }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    fun saveEdgePreference(on: Boolean, foregroundOnly: Boolean) {
        val suppression = if (!on) TouchFx.suppressEdgeForPreferenceChange(foregroundOnly) else null
        scope.launch {
            var saved = false
            try {
                saved = Core.settings.update {
                    if (foregroundOnly) it.copy(edgeLightInForeground = on) else it.copy(edgeLight = on)
                }
            } finally {
                if (!saved && suppression != null) TouchFx.cancelEdgePreferenceChange(suppression)
            }
        }
    }
    SuixingCard {
        AutomationCardHeader(title = "边缘光", icon = R.drawable.ic_stat_spark,
            subtitle = if (settings == null) "正在读取设置…" else "Agent 操作手机时的边缘提示。")
        Spacer(Modifier.height(SettingsStyle.FieldGap))
        SettingRow(title = "显示边缘光", control = {
            SettingsSwitch(checked = settings?.first == true, enabled = settings != null,
                onCheckedChange = { on -> saveEdgePreference(on, foregroundOnly = false) })
        })
        SettingRow(title = "在 JAgent 前台显示", subtitle = "总开关关闭时不生效。", control = {
            SettingsSwitch(checked = settings?.second == true, enabled = settings?.first == true,
                onCheckedChange = { on -> saveEdgePreference(on, foregroundOnly = true) })
        })
    }
}

@Composable
private fun AutomationPermissionsCard(onManage: () -> Unit) {
    val readiness by remember {
        Core.capabilities.state.map { it.accessibility to it.rootReady }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Core.capabilities.state.value.let { it.accessibility to it.rootReady })
    SuixingCard {
        AutomationCardHeader(title = "自动化权限", icon = R.drawable.ic_shield_user)
        Spacer(Modifier.height(SettingsStyle.FieldGap))
        SettingRow(title = "界面识别与文字定位", subtitle = "需要无障碍服务。", control = {
            Text(if (readiness.first) "已连接" else "未连接", style = SettingsStyle.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        SettingRow(title = "副屏打开第三方应用", subtitle = "需要 root 权限。", control = {
            Text(if (readiness.second) "已就绪" else "未就绪", style = SettingsStyle.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
        Text("坐标点击和滑动也可使用 Shizuku 或 root。", style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onManage, modifier = Modifier.align(Alignment.End)) { Text("管理权限") }
    }
}

@Composable
private fun AutomationCardHeader(
    title: String,
    icon: Int,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(SettingsStyle.ControlGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = SettingsStyle.cardTitle)
            subtitle?.let { Text(it, style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(SettingsStyle.ControlGap))
            trailing()
        }
    }
}
