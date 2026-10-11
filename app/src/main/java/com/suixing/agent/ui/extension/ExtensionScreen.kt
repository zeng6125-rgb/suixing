package com.suixing.agent.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.launch

/**
 * 记忆页：模型自己写下的长期备忘，写进去就进下一次请求的提示词。
 *
 * MCP 与技能原来和记忆挤在这一页的三个标签下；两者现在都归工具页
 * ([com.suixing.agent.ui.settings.ToolsScreen])，这一页只剩记忆，所以没有标签栏了。
 * 路由名与函数名仍叫 extension：它们是内部字符串，改名要动的地方比它留下的信息多。
 */
@Composable
fun ExtensionScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var memoryRefresh by remember { mutableIntStateOf(0) }
    var memoryNew by remember { mutableIntStateOf(0) }

    // 根节点必须自己铺不透明底：转场里进页是**不淡入**的（见 SuixingApp 顶部那段），这一页
    // 若透出外壳的底，进页的那一帧就会看到上一页的残影。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            PageTopBar(
                title = "记忆",
                subtitle = "模型自己写下的长期备忘",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { memoryRefresh++ }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_rotate_cw), contentDescription = "刷新", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { memoryNew++ }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_plus), contentDescription = "新增记忆", modifier = Modifier.size(20.dp))
                    }
                },
            )
            MemorySection(
                refresh = memoryRefresh,
                newRequest = memoryNew,
                onNotify = { text -> scope.launch { snackbar.showSnackbar(text) } },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
