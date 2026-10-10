package com.suixing.agent.capability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.service.SuixingAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/**
 * The channel a privileged operation actually runs through, in ascending order of privilege.
 *
 * The same three ways in as [Shell]: the app's own uid, the shell uid through Shizuku, and uid 0.
 */
enum class ShellTier {
    LOCAL,
    SHIZUKU,
    ROOT,
}

/**
 * The values `AppSettings.preferredCapability` holds: the 执行档次 the user fixed on the 能力 page.
 *
 * Declared beside the state that resolves them, so the keys and the resolution cannot drift
 * apart; `tools/Tool.kt` re-exports the same strings as its `TIER_*` constants.
 */
object CapabilityTiers {
    /** 标准档: the app's own uid only. */
    const val NONE = "none"

    /** 无障碍: the screen route, not a shell channel. */
    const val ACCESSIBILITY = "accessibility"

    /** Shizuku / ADB shell. */
    const val SHELL = "shell"

    /** Root shell. */
    const val ROOT = "root"
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

    /**
     * Which channel privileged work runs through, given the tier the user fixed in 「能力 → 执行档次」.
     *
     * The single place this is decided: the shell tools, the ui tools' fallback and the engine's
     * pre-execution gate all ask here, so what the settings page promises and what actually runs
     * cannot disagree. The rules, in the user's words — 「权限的档次就按执行档次设置的执行，然后
     * 没指定档次时优先权限最高的」:
     *
     *  - nothing fixed (`""`, or a value this build does not know) → the highest channel the device
     *    offers right now. That is what 自动 means on the picker, and the user's rule verbatim;
     *  - [CapabilityTiers.ROOT] or [CapabilityTiers.SHELL] fixed → that channel; while it is not
     *    ready yet, the highest ready channel *below* it. A fixed tier is a ceiling as well as a
     *    preference — fixing Shizuku on a device that also has root must not quietly run as root;
     *  - 标准档 ([CapabilityTiers.NONE]) fixed → [ShellTier.LOCAL], always. The picker's own text
     *    is 「只用应用自身权限」; a fixed 标准档 that escalated to root would make that text a lie.
     *    Note 标准档 is fixed, not "nothing fixed": the picker clears back to `""` for 自动;
     *  - 无障碍 ([CapabilityTiers.ACCESSIBILITY]) fixed → the highest ready channel, since that
     *    tier is about the screen route (picked inside the ui tools, which try the accessibility
     *    service first) and the 无障碍 card promises the Shizuku / root fallback behind it.
     *
     * Root counts as ready only once it has been probed ([rootProbed]): resolving never triggers the
     * superuser prompt, which the 能力 page asks for explicitly (see [CapabilityHub.restoreRoot]).
     */
    fun shellTier(preferred: String): ShellTier {
        // What the tier the user fixed asks for; nothing fixed (自动) and 无障碍 ask for the top.
        val fixed = when (preferred) {
            CapabilityTiers.ROOT -> ShellTier.ROOT
            CapabilityTiers.SHELL -> ShellTier.SHIZUKU
            CapabilityTiers.NONE -> ShellTier.LOCAL
            else -> ShellTier.ROOT
        }
        // The highest channel at or below [fixed] this device can actually reach right now. LOCAL is
        // always reachable, so this never comes back empty — and it never goes above [fixed].
        return when (fixed) {
            ShellTier.ROOT -> when {
                rootReady -> ShellTier.ROOT
                shellReady -> ShellTier.SHIZUKU
                else -> ShellTier.LOCAL
            }
            ShellTier.SHIZUKU -> if (shellReady) ShellTier.SHIZUKU else ShellTier.LOCAL
            ShellTier.LOCAL -> ShellTier.LOCAL
        }
    }
}

class CapabilityHub(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow(CapabilityState())
    val state: StateFlow<CapabilityState> = _state.asStateFlow()

    private val _preferred = MutableStateFlow("")

    /**
     * The 执行档次 the user fixed, mirrored from settings; empty means 自动. Read through
     * [shellTier] rather than directly, so nothing decides a channel on its own.
     *
     * A flow and not a one-time read: the 能力 page can change it between two tool calls, and the
     * next one should already obey the new tier.
     */
    val preferred: StateFlow<String> = _preferred.asStateFlow()

    /** Outlives any screen: probing must not be tied to a composable's lifetime. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ShizukuManager.addBinderAliveListener { refresh() }
        ShizukuManager.addBinderDeadListener { refresh() }
        ShizukuManager.addPermissionResultListener { _, _ -> refresh() }
        refresh()
        scope.launch { settings.flow.collect { _preferred.value = it.preferredCapability } }
        scope.launch { restoreRoot() }
    }

    /**
     * The channel privileged work should run through now — [CapabilityState.shellTier] over the
     * live state and the fixed tier. Everything that used to pick root-or-Shizuku for itself asks
     * this instead.
     */
    fun shellTier(): ShellTier = state.value.shellTier(preferred.value)

    /**
     * Puts the remembered root answer on screen without asking for it again.
     *
     * `su -c id` is not free: the superuser app shows a prompt, and on a cold start
     * that prompt arrives before the user has asked for anything. The answer was
     * written down last time precisely so it does not have to be asked twice; only a
     * device that has never been probed ([rootCheckedAt] == 0) gets looked at, and
     * even then only after the cached cards are already on screen.
     */
    private suspend fun restoreRoot() {
        val saved = runCatching { settings.current() }.getOrNull() ?: return
        if (saved.rootCheckedAt > 0L) {
            _state.value = _state.value.copy(root = saved.rootAvailable, rootProbed = true)
            return
        }
        probeRoot()
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
        runCatching {
            settings.update { it.copy(rootAvailable = available, rootCheckedAt = System.currentTimeMillis()) }
        }
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
