package com.suixing.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
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
            // Forcing light or dark in settings must also flip the status bar icons.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !dark
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
}
