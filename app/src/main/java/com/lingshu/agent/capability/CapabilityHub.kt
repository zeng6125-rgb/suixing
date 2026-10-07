package com.lingshu.agent.capability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lingshu.agent.service.LingshuAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The three privilege tiers the agent can act through. */
enum class Tier {
    /** Accessibility service: screen reading, gestures, screenshots. */
    ACCESSIBILITY,

    /** Shizuku / ADB shell: `am`, `pm`, `input`, `/sdcard`, app data. */
    SHELL,

    /** Root shell. */
    ROOT,
}

data class CapabilityState(
    val accessibility: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val root: Boolean = false,
    val rootProbed: Boolean = false,
) {
    val shellReady: Boolean get() = shizukuAlive && shizukuGranted
    val rootReady: Boolean get() = rootProbed && root
    val allFilesAccess: Boolean get() = shellReady || rootReady
}

class CapabilityHub(private val context: Context) {
    private val _state = MutableStateFlow(CapabilityState())
    val state: StateFlow<CapabilityState> = _state.asStateFlow()

    init {
        ShizukuManager.addBinderAliveListener { refresh() }
        ShizukuManager.addBinderDeadListener { refresh() }
        ShizukuManager.addPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(
            accessibility = LingshuAccessibilityService.isConnected(),
            shizukuInstalled = ShizukuManager.isInstalled(context),
            shizukuAlive = ShizukuManager.isBinderAlive(),
            shizukuGranted = ShizukuManager.isGranted(),
        )
    }

    fun onAccessibilityChanged(connected: Boolean) {
        _state.value = _state.value.copy(accessibility = connected)
    }

    suspend fun probeRoot(): Boolean = withContext(Dispatchers.IO) {
        val available = Shell.rootAvailable()
        _state.value = _state.value.copy(root = available, rootProbed = true)
        available
    }

    fun requestShizuku() {
        ShizukuManager.requestPermission()
        refresh()
    }

    fun openShizukuApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${ShizukuManager.SHIZUKU_PACKAGE}")
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun accessibilityComponent(): ComponentName =
        ComponentName(context, LingshuAccessibilityService::class.java)
}
