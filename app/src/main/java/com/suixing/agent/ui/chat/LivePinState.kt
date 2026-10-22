package com.suixing.agent.ui.chat

/**
 * 「读历史时钉住流式行的布局高度」的状态机（issue #17）。
 *
 * 为什么单独抽出来：这个机制**连续几版"改了却没生效"**，两次都是状态机的问题 ——
 *  1. 判定用的 `liveForPin` 是个普通 `val`，被 `LaunchedEffect(listState)` 的
 *     `snapshotFlow` 一次性捕获 ⇒ 永远停在首帧的 `false` ⇒ 钉住从未生效；
 *  2. 解除钉住时只清了高度，**没有清 latch** ⇒ 之后再也钉不回去。
 * 这两条都属于"看起来对、实际是常量"的静默失败，编译和界面看不出来，只有把逻辑变成
 * 可断言的纯函数才能守住。所以这里不碰 Compose/滚动，只做状态转移。
 *
 * 语义：
 *  - **要钉** = 用户不在底部（在读上面的内容）**且**这一轮还有活内容（正文/思考/工具）。
 *  - 钉住后的高度 = 捕获那一刻的自然高度 **+ 裁剪之外的其它部分**（状态行）。
 *    后者必须计入，否则状态行换行（重试/429 提示两三行）时上面的内容仍会被推。
 *  - 高度没量到（`natural <= 0`，布局还没跑过）时不钉，并告诉调用方"等一帧再来" ——
 *    **绝不能用 0 去钉**（那是"钉成 0 高度"，比不钉更糟）。
 */
internal class LivePinState {
    /** 现在是否处于钉住状态。调用方要把它同步给滚动层（禁止"到底"自动打开贴底）。 */
    var active: Boolean = false
        private set

    /** 钉住的总高度（像素）；0 表示未钉住。 */
    var height: Int = 0
        private set

    /**
     * 喂一次当前判定条件。
     *
     * @param notAtBottom 用户不在底部（`!stickToBottom && !restoring`）
     * @param hasLiveContent 这一轮还有活内容
     * @param natural 流式行当前的自然高度（布局量出来的，0 = 还没量到）
     * @param outside 与流式行同属一个 item、但在裁剪之外的其它高度（状态行）
     * @return `true` = 需要钉住但高度还没量到；调用方应等一帧后**再调一次**
     */
    fun update(
        notAtBottom: Boolean,
        hasLiveContent: Boolean,
        natural: Int,
        outside: Int = 0,
    ): Boolean {
        val want = notAtBottom && hasLiveContent
        if (!want) {
            // 不需要钉（回到底部 / 这一轮结束 / 失败清空了活内容）：一律清干净。
            // **不要只清 height 而留着 active** —— 那就是 v0.8.341 那个"再也钉不回去"的 bug。
            active = false
            height = 0
            return false
        }
        if (active) return false          // 已经在钉：高度在捕获时就定死了，不再跟踪自然高度
        if (natural <= 0) return true     // 还没量到：等一帧
        active = true
        height = natural + outside.coerceAtLeast(0)
        return false
    }

    /** 切会话时调用：高度与状态都必须清零，否则新会话会沿用上个会话的高度。 */
    fun reset() {
        active = false
        height = 0
    }
}
