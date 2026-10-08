package com.suixing.agent.capability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.suixing.agent.service.SuixingAccessibilityService
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
            accessibility = SuixingAccessibilityService.isConnected(),
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

    /** Returns false when Shizuku is not running, so the UI can explain the no-op. */
    fun requestShizuku(): Boolean {
        val requested = ShizukuManager.requestPermission()
        refresh()
        return requested
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

    /**
     * Returns false when the settings screen could not be opened. "All files access"
     * only exists from Android 11 (API 30); on older releases both intents fail.
     */
    fun openAllFilesSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .recoverCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .isSuccess
    }

    fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun accessibilityComponent(): ComponentName =
        ComponentName(context, SuixingAccessibilityService::class.java)
}
