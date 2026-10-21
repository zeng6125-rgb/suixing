package com.suixing.agent

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.ui.SuixingApp
import com.suixing.agent.ui.theme.SuixingTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * 回到前台时补一次悬浮窗。
     *
     * 「常驻」的开关可能比服务活得久：服务被系统回收过之后，设置里那个开关还亮着，而没有任何东西
     * 在画球 —— 用户看到的就是「明明开着却不显示」。这里是唯一自然的补救点（用户自己把 App 调回
     * 前台），而且只在服务真的不在时才启动，不打扰一个已经在跑的服务。
     */
    override fun onStart() {
        super.onStart()
        Core.appScope.launch {
            if (Core.settings.current().floatingConsole && !FloatConsoleService.isRunning()) {
                FloatConsoleService.start(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 启动窗口的底色来自主题，而主题里的 @color/window_background 是 uiMode 限定符
        // （values-night 里换）：用户**在 app 里强制深色**、而系统是浅色时，系统会先按浅色画
        // 一帧白，再切成 app 的深色（反向同理）。这里在 setContentView 之前换成不依赖系统
        // uiMode 的那一套（themes.xml 的 Theme.Suixing.Light / Theme.Suixing.Dark），读的是
        // Core.themeMode 这份同步快照——挂起的 settings.current() 在这一刻等不了。
        // 注意它改的是**本 Activity 窗口**的底色：系统画的启动窗口（Android 12+ 的启动画面）
        // 在我们进程起来之前就已经画完了，任何 app 内设置都改不到它。
        setTheme(
            when (Core.themeMode) {
                1 -> R.style.Theme_Suixing_Light
                2 -> R.style.Theme_Suixing_Dark
                else -> R.style.Theme_Suixing
            },
        )
        // The app draws its own headers and a floating nav bar, so it owns the
        // whole window instead of leaving two system-coloured strips behind.
        enableEdgeToEdge()
        // enableEdgeToEdge already asks for this, but saying it outright keeps the
        // gesture-bar strip out of the layout in both themes even if a future
        // enableEdgeToEdge call drops the flag.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        transparentNavigationBar()
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContent {
            val settings by Core.settings.flow.collectAsState(initial = AppSettings())
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            // The bars have to follow the theme, and the theme is a setting the user can
            // change while the app is running — deciding once in onCreate would freeze
            // whichever value was stored at launch. Transparent scrims either way: the
            // header paints the status bar area itself, so a coloured strip would show
            // as a seam above it.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // Re-asserted per theme: the style above carries a transparent scrim,
                // but a solid navigation bar colour is exactly the grey band under the
                // gesture pill the user photographed.
                transparentNavigationBar()
                // Transparent, and it has to stay that way: the keyboard's rounded corners reveal
                // whatever the window paints behind them, and the apps the user held up as
                // adapted show the wallpaper there. Painting the theme's surface here (as an
                // earlier attempt did) only swapped one wrong colour for another — the seam is
                // not a colour mismatch to fix but an area the window must not paint at all.
                // The chat canvas is drawn by the root composable, inset by the IME.
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            SuixingTheme(
                darkTheme = dark,
                dynamicColor = settings.paletteMode == 1,
                fontScale = settings.fontScale,
                cornerScale = settings.cornerScale,
            ) {
                SuixingApp()
            }
        }
    }

    /**
     * Without this the foreground notification that keeps a long run alive is
     * silently dropped on Android 13+, so the user has no way to see or stop it.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001
    }

    /**
     * 导航栏设为全透明 —— **行为与以前完全一致**，只是把废弃 API 收敛到一处。
     *
     * `Window.navigationBarColor` 在 targetSdk 35 起被标记废弃（系统强制 edge-to-edge），
     * 但本机最低支持 API 26，26–34 上它仍是让手势条那一条不再画灰底的**唯一**做法；
     * 删掉它会在老系统上留下一道灰带（历史上用户拍过这条灰带）。
     * 所以保留调用、用 `@Suppress("DEPRECATION")` 显式说明这是有意的，而不是改写行为。
     */
    @Suppress("DEPRECATION")
    private fun transparentNavigationBar() {
        window.navigationBarColor = Color.TRANSPARENT
    }
}
