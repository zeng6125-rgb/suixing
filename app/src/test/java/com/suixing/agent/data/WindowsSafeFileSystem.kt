package com.suixing.agent.data

import java.io.IOException
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.buffer

/**
 * okio 的 [FileSystem.atomicMove] 在 JVM 上映射到 `Files.move(..., ATOMIC_MOVE)`；
 * **Windows 上该调用在目标文件已存在时会直接抛异常**（MoveFileEx 不带 REPLACE_EXISTING）。
 *
 * DataStore 从 1.2.0 起改用 okio 存储，于是「第一次写盘创建文件成功、之后每次覆盖写都失败」，
 * 表现为 `java.io.IOException: Unable to rename ...tmp to ....preferences_pb`
 * （它的提示文案「multiple instances」在这里是误导，实际只有一个实例）。
 *
 * **只在测试里用**：测试宿主是 Windows；真机是 Linux，`ATOMIC_MOVE` 覆盖是合法的，
 * 生产代码不需要也不应该走这条降级路径。
 */
internal object WindowsSafeFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
    override fun atomicMove(source: Path, target: Path) {
        try {
            super.atomicMove(source, target)
        } catch (failure: IOException) {
            // 降级：先删目标再移动。测试验证的是 ConversationReads 的逻辑与落盘语义，
            // 不是 Windows 的 rename 语义，所以这里退一步不影响被测行为。
            if (delegate.exists(target)) delegate.delete(target)
            // okio 的 FileSystem 没有 `move`（只有 `atomicMove`），所以「非原子移动」=
            // 复制内容 + 删源。
            delegate.source(source).use { input ->
                delegate.sink(target).buffer().use { output -> output.writeAll(input) }
            }
            delegate.delete(source)
        }
    }
}
