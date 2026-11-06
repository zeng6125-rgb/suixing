package com.suixing.agent.virtualscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一块只跑自己内容的副屏。
 *
 * 用的是 `OWN_CONTENT_ONLY` 而不是 `PUBLIC`。AOSP 的 DisplayManagerService 只在带
 * `AUTO_MIRROR` 时才要求 `CAPTURE_VIDEO_OUTPUT`（`PUBLIC` 会自动加上这个 flag），
 * 非镜像的 `OWN_CONTENT_ONLY` 任何应用都能建——所以这里既不用 Shizuku 也不用 root。
 * 见 `DisplayManagerService.java` 里 createVirtualDisplay 的那段 SecurityException 文案。
 *
 * 输出是一块**常驻**的 ImageReader surface：预览开关只停消费帧，绝不 `setSurface(null)`，
 * 那会暂停这块屏、连带把上面的应用挂住。
 */
object VirtualScreenManager {

    /** 预览帧最密约 5fps：够看出画面在动，又不至于每帧都做一次 RGBA→ARGB 转换。 */
    private const val FRAME_INTERVAL_MS = 200L

    /** 副屏按主屏比例缩小，避免整屏 RGBA 每帧 11MB。 */
    private const val SCALE = 0.45f

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
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastFrameStamp = 0L

    /**
     * 预览开关。关掉只停「转换成 Bitmap」这一步：ImageReader 仍挂在副屏上当输出，
     * 屏上的应用照常跑——绝不用 `setSurface(null)` 换暂停。
     */
    @Volatile
    private var previewEnabled = true

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 最新一帧；预览关掉时上层不再读它，但采集照常，所以重新打开能立刻有画面。 */
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
     * dp 一致时资源选择会随密度降档（`xxhdpi` → `hdpi`），这是仿真一颗小屏无法避免的；
     * 要连资源档位都一致，只能按原分辨率建屏（内存与每帧拷贝都是 5 倍），不划算。
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

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        // 密度是 dp 换算的分母：只缩像素不缩它，屏上的 dp 会跟着缩，布局就变了。
        val densityDpi = scaledDensity(metrics.densityDpi)

        val created = displayManager.createVirtualDisplay(
            "JAgentVirtualScreen",
            width,
            height,
            densityDpi,
            imageReader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val displayId = created.display?.displayId ?: -1

        val handlerThread = HandlerThread("virtual-screen-frames").apply { start() }
        imageReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image != null) {
                try {
                    val now = System.currentTimeMillis()
                    if (previewEnabled && now - lastFrameStamp >= FRAME_INTERVAL_MS) {
                        lastFrameStamp = now
                        image.toBitmap()?.let { bitmap ->
                            // 不 recycle 上一帧：Compose 可能正在拿它绘制，回收会 use-after-free。
                            _frame.value = bitmap
                            _state.value = _state.value.copy(lastFrameAt = now)
                        }
                    }
                } finally {
                    image.close()
                }
            }
        }, Handler(handlerThread.looper))

        display = created
        reader = imageReader
        thread = handlerThread
        lastFrameStamp = 0L
        val next = State(running = true, displayId = displayId, width = width, height = height)
        _state.value = next
        return next
    }

    fun setPreviewEnabled(enabled: Boolean) {
        previewEnabled = enabled
        if (!enabled) _frame.value = null
    }

    fun stop() {
        reader?.setOnImageAvailableListener(null, null)
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { thread?.quitSafely() }
        display = null
        reader = null
        thread = null
        lastFrameStamp = 0L
        _frame.value = null
        previewEnabled = true
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
        val result = com.suixing.agent.capability.Shell.root(
            "am start --display $displayId -n $component",
        )
        return result.ok && !result.stdout.contains("Error")
    }

    private fun Image.toBitmap(): Bitmap? = try {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val stridePixels = width + rowPadding / pixelStride
        val padded = IntArray(stridePixels * height)
        plane.buffer.asIntBuffer().get(padded)
        // ImageReader 给的是 RGBA_8888，Bitmap 要 ARGB_8888：交换 R 与 B。
        for (i in 0 until width * height) {
            val row = i / width
            val column = i % width
            val value = padded[row * stridePixels + column]
            padded[i] = (value and 0xFF00FF00.toInt()) or
                ((value and 0xFF) shl 16) or
                ((value shr 16) and 0xFF)
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(padded, 0, width, 0, 0, width, height)
        }
    } catch (error: Throwable) {
        null
    }
}
