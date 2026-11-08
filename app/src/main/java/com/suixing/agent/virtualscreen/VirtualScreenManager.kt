package com.suixing.agent.virtualscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 一块只跑自己内容的副屏。
 *
 * 建屏用 `OWN_CONTENT_ONLY` 而不是 `PUBLIC`：AOSP 的 DisplayManagerService 只在带
 * `AUTO_MIRROR` 时才要求 `CAPTURE_VIDEO_OUTPUT`（`PUBLIC` 会自动加上这个 flag），
 * 非镜像的 `OWN_CONTENT_ONLY` 任何应用都能建——所以这里既不用 Shizuku 也不用 root。
 *
 * 取帧沿用同类项目的做法（Operit / Eta `VirtualDisplayController` / Mika `AgentDisplay`）：
 * ImageReader 出的是 RGBA_8888，和 `Bitmap.Config.ARGB_8888` 的内存排布一致，**直接
 * `copyPixelsFromBuffer`** 就够，不做通道交换，也不逐像素遍历；`rowStride` 带来的行填充
 * 用一张 padded 宽度的位图接住，再裁到真实宽度。
 *
 * 一张**全尺寸帧**始终留着给 agent 截图（看清文字靠它），预览另做一张缩到 [PREVIEW_MAX_HEIGHT]
 * 的小图并限速 [PREVIEW_INTERVAL_MS]——预览是给人看"它在动"，不需要全分辨率。
 */
object VirtualScreenManager {

    /** 副屏按主屏比例缩小，避免整屏 RGBA 每帧 11MB。 */
    private const val SCALE = 0.45f

    /** 预览最长边高度：与 Eta 的非高频档一致，再大只是浪费绘制。 */
    private const val PREVIEW_MAX_HEIGHT = 480

    /** 预览刷新间隔：约 6fps，够看出画面在动。 */
    private const val PREVIEW_INTERVAL_MS = 160L
    private const val FRAME_STATUS_INTERVAL_MS = 1_000L

    /**
     * 副屏密度：与像素乘同一个 [SCALE]，让 dp 与主屏一致（issue #80）。
     *
     * `dp = px / (dpi / 160)`：像素除以 0.45、密度不缩 ⇒ dp 也除以了 0.45。
     * 两个数一起缩，dp 才不变。下限 120dpi 是 `ldpi` 的档位，再低系统资源选择会退化到
     * 只有 `drawable-ldpi`/`nodpi` 可用。
     */
    internal fun scaledDensity(sourceDpi: Int): Int =
        (sourceDpi * SCALE).toInt().coerceAtLeast(120)

    data class State(
        val running: Boolean = false,
        val displayId: Int = -1,
        val width: Int = 0,
        val height: Int = 0,
        val lastFrameAt: Long = 0L,
    )

    private var display: VirtualDisplay? = null
    @Volatile
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastPreviewStamp = 0L
    private var lastFrameStatusStamp = 0L

    /** 全尺寸帧与它的真实（未含行填充）宽高，读写都在 [frameLock] 下。 */
    private val frameLock = Any()
    private var fullFrame: Bitmap? = null
    private var fullWidth = 0
    private var fullHeight = 0
    private val previewCanvas = Canvas()
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewSource = Rect()
    private val previewTarget = Rect()

    /**
     * 预览开关。关掉只停「缩图并发布」这一步：ImageReader 仍挂在副屏上当输出，
     * 屏上的应用照常跑——绝不用 `setSurface(null)` 换暂停。
     */
    @Volatile
    private var previewEnabled = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 最新一帧预览；预览关掉时上层不再读它，但采集照常，所以重新打开能立刻有画面。 */
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame

    /**
     * 建屏。宽度、高度、**密度三者一起按 [SCALE] 缩放**，这样屏上的应用按原来的 dp 排布。
     *
     * 密度必须跟着像素一起缩（issue #80）。原来只缩了像素、`densityDpi` 沿用主屏：
     *
     * ```
     * 主屏   1156x2510 @ 480dpi  →  385 x 837 dp
     * 副屏    520x1129  @ 480dpi  →  173 x 376 dp   ← 不是"缩小"，是换了一块小得多的屏
     * ```
     *
     * 173dp 小于 `sw360dp` 这个几乎所有布局都在用的断点，系统会进极窄布局、应用的最小宽度
     * 限制被触发，界面上挤成一团或被裁掉。乘上 SCALE 之后：
     *
     * ```
     * 副屏    520x1129  @ 216dpi  →  385 x 837 dp   ← 与主屏一致
     * ```
     *
     * 已经建过就直接返回原来的 displayId，重复点不会叠出第二块屏。
     */
    fun start(context: Context): State {
        _state.value.takeIf { it.running }?.let { return it }
        val app = context.applicationContext
        val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = app.resources.displayMetrics
        val width = (metrics.widthPixels * SCALE).toInt().coerceAtLeast(320)
        val height = (metrics.heightPixels * SCALE).toInt().coerceAtLeast(320)

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        // 密度是 dp 换算的分母：只缩像素不缩它，屏上的 dp 会跟着缩，布局就变了。
        val densityDpi = scaledDensity(metrics.densityDpi)

        val created = try {
            displayManager.createVirtualDisplay(
                "JAgentVirtualScreen",
                width,
                height,
                densityDpi,
                imageReader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: error("系统未能创建虚拟屏。")
        } catch (error: Exception) {
            imageReader.close()
            throw error
        }
        val displayId = created.display?.displayId ?: run {
            created.release()
            imageReader.close()
            error("虚拟屏没有有效的 displayId。")
        }

        val handlerThread = HandlerThread("virtual-screen-frames").apply { start() }

        display = created
        reader = imageReader
        thread = handlerThread
        lastPreviewStamp = 0L
        lastFrameStatusStamp = 0L
        val next = State(running = true, displayId = displayId, width = width, height = height)
        _state.value = next
        imageReader.setOnImageAvailableListener({ r -> onFrame(r) }, Handler(handlerThread.looper))
        return next
    }

    fun setPreviewEnabled(enabled: Boolean) {
        synchronized(frameLock) {
            if (previewEnabled == enabled) return
            previewEnabled = enabled
            if (!enabled) {
                _frame.value = null
            } else {
                // Static screens may not draw another frame after reopening the preview.
                val source = fullFrame ?: return@synchronized
                _frame.value = createPreview(source, fullWidth, fullHeight)
                lastPreviewStamp = SystemClock.uptimeMillis()
            }
        }
    }

    fun stop() {
        val closingReader = reader
        reader = null
        closingReader?.setOnImageAvailableListener(null, null)
        runCatching { display?.release() }
        runCatching { closingReader?.close() }
        runCatching { thread?.quitSafely() }
        display = null
        reader = null
        thread = null
        lastPreviewStamp = 0L
        lastFrameStatusStamp = 0L
        synchronized(frameLock) {
            fullFrame?.recycle()
            fullFrame = null
            fullWidth = 0
            fullHeight = 0
            _frame.value = null
            previewEnabled = false
        }
        _state.value = State()
    }

    /**
     * 把本应用的一个 Activity 放到这块屏上。
     *
     * 私有屏的启动判据是 `display.getOwnerUid() == callingUid`（ActivityTaskSupervisor），
     * 自己启动自己不用任何权限；目标 Activity 只要声明了 `android:allowEmbedded="true"`。
     */
    fun launchOwn(context: Context): Boolean {
        val id = _state.value.displayId
        if (id < 0) return false
        val intent = android.content.Intent(context, VirtualScreenActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
        return runCatching { context.startActivity(intent, options) }.isSuccess
    }

    /**
     * 把任意第三方应用放到这块屏上。它们的 Activity 没有声明 allowEmbedded，普通应用身份
     * 会被 `ActivityTaskSupervisor` 拒；root 在 `isCallerAllowedToLaunchOnDisplay` 的第一道
     * INTERNAL_SYSTEM_WINDOW 检查就早退通过，所以这条路交给 root 的 `am start --display`。
     */
    suspend fun launchExternal(component: String, displayId: Int): Boolean {
        if (displayId < 0 || _state.value.displayId != displayId) return false
        val quotedComponent = "'" + component.replace("'", "'\\''") + "'"
        val result = com.suixing.agent.capability.Shell.root(
            "am start --display $displayId -n $quotedComponent",
        )
        return result.ok && !result.stdout.contains("Error", ignoreCase = true) &&
            !result.stderr.contains("Error", ignoreCase = true)
    }

    /**
     * 最新一帧的 JPEG，直接来自副屏的 ImageReader。
     *
     * agent 截图走这里而不是无障碍的 `takeScreenshot`：这条路径不依赖窗口焦点、没有系统
     * 截图冷却，副屏上没有前台窗口时也拿得到画面（Eta / Mika 同样把截图建在 ImageReader 上）。
     */
    suspend fun latestJpeg(maxWidth: Int, quality: Int, expectedDisplayId: Int? = null): ByteArray? = withContext(Dispatchers.Default) {
        val shot = synchronized(frameLock) {
            if (reader == null || (expectedDisplayId != null && _state.value.displayId != expectedDisplayId)) {
                return@synchronized null
            }
            val source = fullFrame ?: return@synchronized null
            // 在锁内拷贝：解码线程会往同一张位图里写下一帧，出锁再读就是撕裂的画面。
            runCatching {
                Bitmap.createBitmap(source, 0, 0, fullWidth, fullHeight)
            }.getOrNull()
        } ?: return@withContext null
        try {
            val scaled = if (shot.width > maxWidth) {
                val ratio = maxWidth.toFloat() / shot.width
                val scaledHeight = (shot.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(shot, maxWidth, scaledHeight, true)
            } else {
                shot
            }
            try {
                ByteArrayOutputStream().use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                        return@withContext null
                    }
                    out.toByteArray()
                }
            } finally {
                if (scaled !== shot) scaled.recycle()
            }
        } finally {
            shot.recycle()
        }
    }

    /** 有没有收到过帧：没有就说明这块屏还没画过东西。 */
    fun hasFrame(): Boolean = synchronized(frameLock) { fullFrame != null }

    /**
     * 一帧到了：先拷进全尺寸位图，再按需发布缩小的预览。
     *
     * 直接拷贝的原因见类注释：RGBA_8888 与 ARGB_8888 的排布一致。`rowStride` 可能比
     * `width * pixelStride` 大（驱动按对齐填充），所以位图按 `rowStride / pixelStride`
     * 的宽度建，最后裁剪到真实宽度。
     */
    private fun onFrame(reader: ImageReader) {
        if (reader !== this.reader) return
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (error: Throwable) {
            null
        } ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return
            val strideWidth = rowStride / pixelStride
            if (strideWidth < image.width) return

            val now = SystemClock.uptimeMillis()
            synchronized(frameLock) {
                // A callback queued before close must not revive the old display's frame.
                if (reader !== this.reader) return@synchronized
                val previous = fullFrame
                val target = if (previous != null && previous.width == strideWidth && previous.height == image.height) {
                    previous
                } else {
                    Bitmap.createBitmap(strideWidth, image.height, Bitmap.Config.ARGB_8888)
                }
                try {
                    target.copyPixelsFromBuffer(plane.buffer.rewind())
                } catch (error: Throwable) {
                    if (target !== previous) target.recycle()
                    throw error
                }
                if (target !== previous) {
                    previous?.recycle()
                    fullFrame = target
                }
                fullWidth = image.width
                fullHeight = image.height
                // Frame readiness belongs to the display, not to its optional preview.
                // Publish the first frame immediately, then bound metadata updates to 1 Hz.
                if (_state.value.lastFrameAt == 0L || now - lastFrameStatusStamp >= FRAME_STATUS_INTERVAL_MS) {
                    lastFrameStatusStamp = now
                    _state.value = _state.value.copy(lastFrameAt = System.currentTimeMillis())
                }
                if (!previewEnabled || now - lastPreviewStamp < PREVIEW_INTERVAL_MS) return@synchronized
                val preview = createPreview(target, image.width, image.height) ?: return@synchronized
                // Publish under the same lock as stop(), so a late frame cannot restore the preview.
                lastPreviewStamp = now
                _frame.value = preview
            }
        } catch (error: Throwable) {
            // 一帧坏了不影响后面的帧：丢掉这次，等下一帧。
        } finally {
            runCatching { image.close() }
        }
    }

    /** Crop row padding and scale in one draw; the backing frame stays owned by the display. */
    private fun createPreview(source: Bitmap, width: Int, height: Int): Bitmap? {
        val targetHeight = height.coerceAtMost(PREVIEW_MAX_HEIGHT)
        val targetWidth = (width.toLong() * targetHeight / height).toInt().coerceAtLeast(1)
        val preview = runCatching {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        try {
            previewSource.set(0, 0, width, height)
            previewTarget.set(0, 0, targetWidth, targetHeight)
            previewCanvas.setBitmap(preview)
            previewCanvas.drawBitmap(source, previewSource, previewTarget, previewPaint)
            return preview
        } catch (error: Exception) {
            preview.recycle()
            return null
        } finally {
            previewCanvas.setBitmap(null)
        }
    }
}
