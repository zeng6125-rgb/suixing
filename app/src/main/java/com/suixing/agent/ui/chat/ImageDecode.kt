package com.suixing.agent.ui.chat

/**
 * 截图解码的两个纯逻辑：**降采样倍率** 与 **已解码图片的 LRU 缓存**。
 *
 * 为什么需要（issue #38 方向 7）：
 * `ImageBlock` 里的 `remember` 随组合销毁 —— 列表项一旦滚出视口，解码结果就没了；
 * 再滚回来又要**完整解码一张 1080p 截图**（几十毫秒）。于是"上下滑动很慢"不是布局慢，
 * 而是一路上在反复解码。这里做两件事：
 *
 * 1. **降采样**：显示高度上限 240dp（约 720px），按 2 的幂降采样再解码 —— 内存与耗时都按平方下降；
 * 2. **小容量 LRU**：解码结果按内容键缓存几个，滚回来直接命中。
 *
 * 容量刻意很小（默认 4）：一张 1080p 解码后约 8MB，而这里缓存的是降采样后的（约 1~2MB）。
 */
internal object ImageDecode {

    /**
     * 解码时的 `inSampleSize`（必须是 2 的幂）。
     *
     * 目标是"解码后的高度不超过 [maxHeight]"：`inSampleSize = 2^n` 满足 `height / 2^n >= maxHeight`
     * 时取 n-1。原图本来就比目标矮 ⇒ 返回 1（不缩放）。
     */
    fun sampleSizeFor(width: Int, height: Int, maxHeight: Int): Int {
        if (width <= 0 || height <= 0 || maxHeight <= 0) return 1
        var sample = 1
        // 每次翻倍后仍高于目标才继续，避免缩得比需要的小。
        while (height / (sample * 2) >= maxHeight) sample *= 2
        return sample
    }
}

/**
 * 按**访问顺序**淘汰的少量图片缓存。
 *
 * 与 `MessageCache` 同一思路：条目数固定、`get` 会续命。key 用"内容指纹"（长度 + 首尾片段），
 * 不用整段 base64 当 key（那本身就要复制几十 KB）。
 */
internal class DecodedImageCache(private val capacity: Int = 4) {

    private val map = object : LinkedHashMap<String, Any>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(key: String): Any? = map[key]

    @Synchronized
    fun put(key: String, value: Any) {
        if (key.isEmpty()) return
        map[key] = value
    }

    @Synchronized
    fun sizeForTest(): Int = map.size

    /** 内容指纹：长度 + 首尾各 24 字符。碰撞概率极低，且碰撞只影响"命中错的图"而不会崩。 */
    fun keyOf(base64: String): String {
        val n = base64.length
        if (n <= 48) return "$n|$base64"
        return "$n|${base64.take(24)}|${base64.takeLast(24)}"
    }
}
