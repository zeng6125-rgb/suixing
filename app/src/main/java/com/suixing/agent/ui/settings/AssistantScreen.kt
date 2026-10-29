package com.suixing.agent.ui.settings

import com.suixing.agent.ui.components.SettingsStyle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantListContent
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.navBarContentPadding

/**
 * 助手：一整页，不再是半屏的表。
 *
 * 抽屉和设置页的两行都落到这一页上（只有这一条路径），半屏的表只剩聊天页那个随手切换
 * 助手的窗口。列表与编辑器来自 [AssistantListContent]，和那张表共用同一份实现，所以
 * 「当前 / 默认 / 设为默认」只有一处定义。
 *
 * 返回键交给导航（NavHost 的默认行为），顶栏的返回按钮也走同一个 [onBack]；编辑助手时
 * 那个全屏对话框自己吃掉 BACK，于是「先关编辑、再退页面」是两级，不会一步跳出整页。
 */
@Composable
fun AssistantScreen(
    assistants: List<Assistant>,
    activeId: String,
    settings: AppSettings,
    onUse: (Assistant) -> Unit,
    onSetDefault: (Assistant) -> Unit,
    onSave: (Assistant) -> Unit,
    onDelete: (Assistant) -> Unit,
    onBack: () -> Unit,
) {
    // 编辑器是主窗口里的全屏覆盖层（结构路，0.8.105）：整页宿主就地画在 Scaffold 之上，
    // BACK 先关编辑、再退页面（编辑器自己的 BackHandler 优先）。
    var editingId by remember { mutableStateOf<String?>(null) }
    val servers by Core.mcp.servers.collectAsStateWithLifecycle()
    val toolNames = remember(servers) { Core.tools.names() + com.suixing.agent.agent.SUBAGENT_SPECS.map { it.name } }
    val editing = editingId?.let { id ->
        assistants.firstOrNull { it.id == id } ?: Assistant(id = id, avatar = "🧭")
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { PageTopBar(title = "助手", onBack = onBack) },
        ) { padding ->
            AssistantListContent(
                assistants = assistants,
                activeId = activeId,
                settings = settings,
                onUse = onUse,
                onSetDefault = onSetDefault,
                onOpenEditor = { editingId = it },
                // 整页不因为选中就退出：用户是来管理助手的，卡片上移到「当前」的那个勾就是
                // 回执；要回聊天页，返回键就在顶上。半屏的表必须关，否则挡住对话又说不清选没选中。
                onPicked = {},
                // 顶栏已经写着「助手」，列表里不再重复一遍标题（副标题留着，它说明这些卡片是什么）。
                title = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = SettingsStyle.PageMargin),
                // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
                bottomPadding = navBarContentPadding().calculateBottomPadding(),
            )
        }
        editing?.let { target ->
            FullScreenOverlay(visible = true) {
                AssistantEditor(
                    initial = target,
                    settings = settings,
                    toolNames = toolNames,
                    onSave = {
                        onSave(it)
                        editingId = null
                    },
                    onDelete = if (assistants.size > 1) {
                        {
                            onDelete(target)
                            editingId = null
                        }
                    } else {
                        null
                    },
                    onDismiss = { editingId = null },
                )
            }
        }
    }
}
