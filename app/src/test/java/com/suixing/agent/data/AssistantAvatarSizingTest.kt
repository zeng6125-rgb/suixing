package com.suixing.agent.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a gallery photo on its way into an assistant's avatar.
 *
 * Two halves, and both have bitten a phone camera roll: the decode has to throw pixels away
 * *before* it allocates them, and the result has to be capped without distorting the face.
 * The arithmetic is what this file pins down; the encode itself needs a device.
 */
class AssistantAvatarSizingTest {

    // ------------------------------------------------------------------ the cap

    @Test
    fun `a landscape photo is capped on its long side`() {
        assertEquals(256 to 192, avatarSize(4000, 3000))
    }

    @Test
    fun `a portrait photo is capped on its long side`() {
        assertEquals(192 to 256, avatarSize(3000, 4000))
    }

    @Test
    fun `a square photo stays square`() {
        assertEquals(256 to 256, avatarSize(1000, 1000))
    }

    @Test
    fun `a picture already small enough is left alone`() {
        assertEquals(120 to 90, avatarSize(120, 90))
        assertEquals(256 to 100, avatarSize(256, 100))
    }

    /** A panorama is still a picture: neither side may be rounded away to zero. */
    @Test
    fun `a very wide picture keeps a pixel of height`() {
        val (width, height) = avatarSize(6000, 30)
        assertEquals(256, width)
        assertTrue("height was $height", height >= 1)
    }

    @Test
    fun `nonsense dimensions do not produce an empty bitmap`() {
        assertEquals(1 to 1, avatarSize(0, 0))
        assertEquals(1 to 1, avatarSize(-5, 100))
    }

    // ------------------------------------------------------- the decode's sampling

    /**
     * The sampled decode has to stay *at or above* the cap: sampling is the cheap half of the
     * shrink, and a sample that lands under 256 would mean upscaling it back, which loses more
     * than it saves.
     */
    @Test
    fun `sampling never lands under the cap`() {
        listOf(4000 to 3000, 1080 to 1920, 800 to 800, 1024 to 768).forEach { (width, height) ->
            val sample = avatarSampleSize(width, height)
            val longest = maxOf(width, height) / sample
            assertTrue("$width x $height sampled by $sample gave $longest", longest >= AVATAR_MAX_PX)
        }
    }

    /** And it is the *largest* such power of two, so a 4000px photo is not decoded at 250px. */
    @Test
    fun `sampling takes the largest power of two that still fits`() {
        assertEquals(8, avatarSampleSize(4000, 3000))
        assertEquals(4, avatarSampleSize(1080, 1920))
        assertEquals(2, avatarSampleSize(800, 800))
        // 512/2 is exactly the cap, so the sample may take that step and no more.
        assertEquals(2, avatarSampleSize(512, 512))
    }

    @Test
    fun `a picture that needs no sampling is not sampled`() {
        assertEquals(1, avatarSampleSize(256, 256))
        assertEquals(1, avatarSampleSize(300, 200))
        assertEquals(1, avatarSampleSize(0, 0))
    }

    /** Sampling then capping lands exactly on the cap for a square source. */
    @Test
    fun `sample and cap agree on the stored size`() {
        val sample = avatarSampleSize(4000, 4000)
        val sampled = 4000 / sample
        assertEquals(256 to 256, avatarSize(sampled, sampled))
    }

    // ------------------------------------------------------------- 裁剪框换算

    /**
     * 1× 就是「短边贴住方框」：1000×500 的图，300px 的框，比例 0.6 ⇒ 框里是一个 500px 的方块，
     * 落在短边的正中间（左右各切掉 250px）。
     */
    @Test
    fun `the frame at rest takes the centred square of the short side`() {
        assertEquals(AvatarCropRect(250, 0, 500), avatarCropRect(1000, 500, 300f, 0.6f, 0f, 0f))
    }

    /** 竖图同理，只是切在上下：500×1000 ⇒ 方块 500px，垂直居中。 */
    @Test
    fun `a portrait picture is cut vertically instead`() {
        assertEquals(AvatarCropRect(0, 250, 500), avatarCropRect(500, 1000, 300f, 0.6f, 0f, 0f))
    }

    /** 放大到 2× 时框里只剩一半：方块 250px，中心不动 ⇒ 左上角各让出 125px。 */
    @Test
    fun `zooming in halves the square the frame shows`() {
        assertEquals(AvatarCropRect(375, 125, 250), avatarCropRect(1000, 500, 300f, 1.2f, 0f, 0f))
    }

    /**
     * 把图往左拖（offset 为负）＝看右边：方块贴住图片的右缘，正好落在 1000-500=500 上。
     * 符号约定与下面那条越界用例一致：位移是**图片的**移动方向，不是框的。
     *
     * 手势本身会把位移夹在「图不出框」的范围内，这个函数是第二道：即使传进来一个越界的位移，
     * 也不能要一个超出图片的矩形 —— `Bitmap.createBitmap` 会直接抛。
     */
    @Test
    fun `dragging to the edge lands on the picture's own edge`() {
        assertEquals(AvatarCropRect(500, 0, 500), avatarCropRect(1000, 500, 300f, 0.6f, -150f, 0f))
        assertEquals(AvatarCropRect(0, 0, 500), avatarCropRect(1000, 500, 300f, 0.6f, 150f, 0f))
    }

    /**
     * 越界拖动夹到图片的**边缘**，而不是弹回中间 —— 拖到头就是看到边。
     *
     * 位移的正负号＝**图片相对框的移动方向**（与手势里的 `pan` 同一个符号）：负值＝图被往左
     * （往上）拖，于是框看到的是图的右边（下边），裁剪矩形的 `left`/`top` **变大**；正值相反。
     * 这一条把两个方向、两个轴都钉住，免得下次再把它读反。
     *
     * 注意 1000×500 的图在纵向没有余量（方块 500px 正好等于高度），所以纵向用例换成竖图：
     * 竖图只有上下有余量，横向反而没有。
     */
    @Test
    fun `an out of range drag is clamped to the picture's own edge`() {
        // 横向：往左拖到头 ⇒ 看右缘；往右拖到头 ⇒ 看左缘。
        assertEquals(AvatarCropRect(500, 0, 500), avatarCropRect(1000, 500, 300f, 0.6f, -10_000f, 0f))
        assertEquals(AvatarCropRect(0, 0, 500), avatarCropRect(1000, 500, 300f, 0.6f, 10_000f, 0f))
        // 纵向：往上拖到头 ⇒ 看下缘；往下拖到头 ⇒ 看上缘。
        assertEquals(AvatarCropRect(0, 500, 500), avatarCropRect(500, 1000, 300f, 0.6f, 0f, -10_000f))
        assertEquals(AvatarCropRect(0, 0, 500), avatarCropRect(500, 1000, 300f, 0.6f, 0f, 10_000f))
    }

    /** 不管怎么拖、怎么缩放，要出来的矩形永远在图片里，且至少 1px。 */
    @Test
    fun `the rectangle never leaves the picture`() {
        val sizes = listOf(1000 to 500, 500 to 1000, 800 to 800, 37 to 4000)
        val scales = listOf(0.2f, 0.6f, 1f, 2.4f, 8f)
        val offsets = listOf(-5000f, -150f, 0f, 150f, 5000f)
        for ((width, height) in sizes) {
            for (scale in scales) {
                for (x in offsets) {
                    for (y in offsets) {
                        val rect = avatarCropRect(width, height, 300f, scale, x, y)
                        assertTrue(
                            "$width x $height at $scale with ($x, $y) gave $rect",
                            rect.left >= 0 && rect.top >= 0 &&
                                rect.size >= 1 &&
                                rect.left + rect.size <= width &&
                                rect.top + rect.size <= height,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `nonsense crop inputs do not produce an empty rectangle`() {
        assertEquals(AvatarCropRect(0, 0, 1), avatarCropRect(0, 0, 300f, 1f, 0f, 0f))
        assertEquals(AvatarCropRect(0, 0, 1), avatarCropRect(100, 100, 0f, 1f, 0f, 0f))
        assertEquals(AvatarCropRect(0, 0, 1), avatarCropRect(100, 100, 300f, 0f, 0f, 0f))
    }

    // ------------------------------------------------------------- the JSON

    /**
     * An assistant saved by an older build has no `avatarPath` key at all, and it must still
     * load — the field is an addition, not a migration.
     */
    @Test
    fun `an assistant without the avatar key still decodes`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"id":"default","name":"随行","avatar":"🪶","description":"全能手机智能体"}"""
        val assistant = json.decodeFromString(Assistant.serializer(), old)
        assertEquals("default", assistant.id)
        assertEquals("🪶", assistant.face)
        assertEquals("", assistant.avatarPath)
    }
}
