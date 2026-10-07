package com.lingshu.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.ui.LingshuApp
import com.lingshu.agent.ui.theme.LingshuTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
