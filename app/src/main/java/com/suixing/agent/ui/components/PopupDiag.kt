package com.suixing.agent.ui.components

import com.suixing.agent.core.Core
import java.io.File

/**
 * 临时诊断：把每个小浮层的锚点/屏幕/卡片几何写进 filesDir/popup-diag.log。
 *
 * 只写文件、不碰界面。用来一次性确认「哪个浮层挂在哪个锚点上」，避免继续靠猜改定位。
 * 查完就删（连同调用点）。
 */
internal object PopupDiag {
    private val file = File(Core.appContext.filesDir, "popup-diag.log")
    private const val LIMIT = 256 * 1024

    fun log(tag: String, msg: String) {
        runCatching {
            if (file.length() > LIMIT) file.delete()
            file.appendText("$tag $msg\n")
        }
    }
}
