package com.suixing.agent.core

import java.io.File
import java.io.FileOutputStream

/**
 * 单文件原子写的公共实现（issue #69 P1-B）。
 *
 * 仓库里已有五处各自实现了一遍「写 `.tmp` → fsync → rename」，语义却不一样：
 * 有的 rename 失败后回退成直接覆盖（半截文件风险），有的直接抛错。这里统一成一种：
 *
 * 1. 写临时文件并 `fsync`（掉电也不会留下半截内容）；
 * 2. `renameTo` 原子替换；
 * 3. **rename 失败就抛错，绝不退回原地覆盖** —— 原地写失败会毁掉旧文件，
 *    而旧文件是用户唯一的一份数据。
 *
 * 调用方负责错误口径（例如设置偏好"写失败当没写成"，提醒"写失败要能重试"）。
 */
internal object AtomicFiles {

    fun writeBytes(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            // **用 `Files.move` 而不是 `renameTo`**：后者在「目标已存在」时于部分平台
            // （Windows 开发机）直接失败，Android 上才是原子替换。先试 ATOMIC_MOVE，
            // 不支持再退回普通替换；两者都失败才抛 —— 绝不退回原地覆盖旧文件。
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            // **目录项也要落盘**：rename 只保证"名字指向哪个文件"这一步原子，改名这件事本身
            // 仍在页缓存里；断电后可能出现"文件在、名字没生效"。文件系统的目录不支持 fsync
            // 时忽略（best effort，与 `ChatStore.syncDirectory` 同一口径）。
            runCatching {
                java.nio.channels.FileChannel
                    .open(target.parentFile!!.toPath(), java.nio.file.StandardOpenOption.READ)
                    .use { it.force(true) }
            }
        } finally {
            temp.delete()
        }
    }

    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))
}
