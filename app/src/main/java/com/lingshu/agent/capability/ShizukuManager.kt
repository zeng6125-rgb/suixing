package com.lingshu.agent.capability

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4210

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = runCatching {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching {
            if (isBinderAlive() && !isGranted()) Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun addBinderAliveListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        runCatching { Shizuku.addBinderDeadListener(listener) }
    }
}
