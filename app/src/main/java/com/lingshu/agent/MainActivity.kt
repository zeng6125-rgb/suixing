package com.lingshu.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.ui.LingshuApp
import com.lingshu.agent.ui.theme.LingshuTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
            LingshuTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                LingshuApp()
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
