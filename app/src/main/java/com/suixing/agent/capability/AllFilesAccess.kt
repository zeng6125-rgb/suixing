package com.suixing.agent.capability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 「所有文件访问权限」的两个设置页，按顺序试：单个应用的入口更准，全局列表是有些 ROM 才有的那个。
 * 「所有文件访问权限」只有 Android 11（API 30）起存在，更早的版本返回空列表。
 *
 * 三个调用方启动方式不同 —— ui/files/FilesScreen.kt 与 ui/capability/CapabilityScreen.kt 走
 * launcher（要读回授权结果，需要在手 Intent），capability/CapabilityHub.kt 直接 startActivity
 * 并只关心成败 —— 所以这里只给列表，启动方式各自保留。改一处即三处同步。
 */
internal fun allFilesAccessIntents(context: Context): List<Intent> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
}
