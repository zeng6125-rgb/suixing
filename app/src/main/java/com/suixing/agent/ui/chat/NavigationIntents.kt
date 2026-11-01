package com.suixing.agent.ui.chat

/**
 * 会话导航的**意图代次**（issue #67 P0-1）。
 *
 * 新建、打开、启动恢复共用同一个单调计数器：每次用户动作 `begin()` 一次，
 * 谁拿到最新的号谁说了算。任何挂起点（`withContext`、磁盘读写、设置读取）之后、
 * 提交 `_state` 之前先问 [isCurrent] —— 过期的工作只能清理自己创建的资源，
 * 绝不能把用户后来选的会话覆盖掉。
 *
 * 抽成独立类的原因：这是并发正确性里唯一能**离线确定复现**的部分。
 * ViewModel 里的整条链路要注入假仓库才能测，而"后来的意图是否让前者失效"这件事，
 * 本身是纯逻辑。
 */
internal class NavigationIntents {

    private var latest: Long = 0L

    /** 声明一次新意图，返回它的代次号（严格递增）。 */
    fun begin(): Long {
        latest += 1
        return latest
    }

    /** 这个代次是否仍是最新意图。 */
    fun isCurrent(token: Long): Boolean = token == latest

    /** 当前代次（启动恢复等场景需要在稍后重新确认时用）。 */
    fun current(): Long = latest
}
