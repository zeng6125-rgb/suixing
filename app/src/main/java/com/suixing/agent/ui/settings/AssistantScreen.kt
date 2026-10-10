package com.suixing.agent.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.ui.assistant.AssistantListContent
import com.suixing.agent.ui.components.PageTopBar

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
            onSave = onSave,
            onDelete = onDelete,
            // 整页不因为选中就退出：用户是来管理助手的，卡片上移到「当前」的那个勾就是
            // 回执；要回聊天页，返回键就在顶上。半屏的表必须关，否则挡住对话又说不清选没选中。
            onPicked = {},
            // 顶栏已经写着「助手」，列表里不再重复一遍标题（副标题留着，它说明这些卡片是什么）。
            title = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 列表自己滚动，导航栏的内边距就留在滚动区之外：最后一行不会被手势条压住。
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
