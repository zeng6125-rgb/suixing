package com.suixing.agent.ui.chat

/**
 * 「同一个会话同一时刻只允许一个在跑」的守卫（例如合并草稿）。
 *
 * 为什么单独抽出来：三个入口（冷启动 / 切回 / 缓存命中）都可能同时进来，
 * 重复合并会各自生成一份新 id ⇒ 后写的那次撞上"只能变长"护栏（或触发一次整份重写）。
 * 这段并发语义留在 `ChatViewModel` 里**无法单测**（它的构造依赖整个 `Core`），
 * 而"守卫漏了/删早了"的后果正是那种只在窄窗口出现的问题 —— 必须能被断言。
 *
 * 语义：
 * - [tryEnter] 只有在没有任何人持有该 key 时才成功；
 * - [leave] 幂等（重复调用无害）；
 * - **必须放在 `finally` 里**，否则一次异常就会让该会话永远合并不了。
 */
internal class InFlightGuard {

    private val keys = HashSet<String>()

    /** 尝试进入；返回 `false` 表示同一个 key 已经在跑。 */
    @Synchronized
    fun tryEnter(key: String): Boolean = keys.add(key)

    @Synchronized
    fun leave(key: String) {
        keys.remove(key)
    }

    @Synchronized
    fun isRunning(key: String): Boolean = key in keys

    @Synchronized
    fun sizeForTest(): Int = keys.size
}
