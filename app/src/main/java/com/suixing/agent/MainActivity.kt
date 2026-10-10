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
import com.suixing.agent.ui.SuixingApp
import com.suixing.agent.ui.theme.SuixingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app draws its own headers and a floating nav bar, so it owns the
        // whole window instead of leaving two system-coloured strips behind.
        enableEdgeToEdge()
        // enableEdgeToEdge already asks for this, but saying it outright keeps the
        // gesture-bar strip out of the layout in both themes even if a future
        // enableEdgeToEdge call drops the flag.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
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
                window.navigationBarColor = Color.TRANSPARENT
                // The window's own background is what the system draws in the strip the
                // keyboard covers, so it shows through the keyboard's rounded corners. Left at
                // the platform default it is a pale seam against the app's background; set here
                // per theme because the app's theme is a setting of its own, not the system's.
                window.setBackgroundDrawable(
                    ColorDrawable(if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND),
                )
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

        /** The two schemes' own background colours; see Color.kt and the window background. */
        const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
        const val DARK_BACKGROUND = 0xFF0F1412.toInt()
    }
}
