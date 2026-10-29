package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏预览解图的降采样（审计 D-3）。
 *
 * 守两件事：
 *  - 本 App 自己写进去的附件（[ATTACHMENT_MAX_WIDTH] = 1280）**不采样** —— 预览与改动前逐
 *    像素相同，观感不变；
 *  - 更大的图（更早版本/别的来源写下的 base64）按 2 的幂降采样，最长边不超过上限。
 */
class ImagePreviewSampleTest {

    @Test
    fun `app's own attachments decode at full size`() {
        // 1280×（任意高度）是 [bitmapAttachment] 的输出上限。
        assertEquals(1, previewSampleSize(1280, 960))
        assertEquals(1, previewSampleSize(960, 1280))
        assertEquals(1, previewSampleSize(320, 240))
    }

    @Test
    fun `right at the cap still decodes at full size`() {
        assertEquals(1, previewSampleSize(2048, 1536))
    }

    @Test
    fun `larger images are halved until they fit`() {
        assertEquals(2, previewSampleSize(2049, 1024))
        assertEquals(2, previewSampleSize(4096, 4096))
        assertEquals(4, previewSampleSize(8192, 100))
    }

    @Test
    fun `unknown bounds are never sampled`() {
        // inJustDecodeBounds 失败时是 0（不是图片/读不出尺寸）—— 按原样解，交给调用方判空。
        assertEquals(1, previewSampleSize(0, 0))
        assertEquals(1, previewSampleSize(-1, 100))
    }

    @Test
    fun `decoded longest side never exceeds the cap`() {
        for (side in listOf(2049, 3000, 4096, 5000, 12_000)) {
            val decoded = side / previewSampleSize(side, side)
            assertTrue("side=$side decoded=$decoded", decoded <= 2048)
        }
    }
}
