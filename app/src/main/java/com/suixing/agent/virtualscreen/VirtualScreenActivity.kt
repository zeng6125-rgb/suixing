package com.suixing.agent.virtualscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.ui.theme.SuixingTheme
import kotlinx.coroutines.delay

/**
 * 跑在虚拟屏上的宿主页。
 *
 * 它存在的意义是**让那块屏有内容可显示**：副屏是私有的、只有本应用能往上放 Activity，
 * 所以先放这个自己人，用来验证「建屏 → 启动 → 出帧」这条链路。界面上那行秒数每秒变
 * 一次，预览里能看出画面在动，而不是一张静止的图。
 *
 * `android:allowEmbedded="true"` 是硬要求：ActivityTaskSupervisor 对非可信屏幕只放行
 * 声明了嵌入能力的 Activity（ActivityTaskSupervisor.java 里 display.isTrusted() 那段）。
 */
class VirtualScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SuixingTheme(paletteMode = 2) {
                var seconds by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        seconds += 1
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101010))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "JAgent 虚拟屏",
                        color = Color(0xFFEDEDED),
                        fontSize = 22.sp,
                    )
                    Text(
                        text = "已运行 $seconds 秒",
                        color = Color(0xFF8FD6B6),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 34.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = "displayId=${display?.displayId ?: -1}",
                        color = Color(0xFF9A958C),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
