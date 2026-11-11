package com.suixing.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.view.Display
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.Shell
import com.suixing.agent.capability.ShellResult
import com.suixing.agent.capability.ShellTier
import com.suixing.agent.service.SuixingAccessibilityService
import com.suixing.agent.service.TouchFx
import com.suixing.agent.service.UiOperationResult
import com.suixing.agent.service.UiOperationStatus
import com.suixing.agent.virtualscreen.VirtualScreenManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sun.misc.Unsafe
import java.io.File
import java.io.OutputStream

class UiToolsScreenshotFeedbackTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val context = mockk<Context>()
    private val capabilities = mockk<CapabilityHub>()
    private val service = mockk<SuixingAccessibilityService>()
    private val feedback = UiOperationStatus()
    private val virtual = MutableStateFlow(VirtualScreenManager.State())
    private val sdkField = Build.VERSION::class.java.getDeclaredField("SDK_INT")
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as Unsafe
    }
    private var originalSdk = 0
    private lateinit var files: File
    private lateinit var tools: UiTools

    @Before fun setUp() {
        originalSdk = Build.VERSION.SDK_INT
        setSdk(Build.VERSION_CODES.R)
        files = temporary.newFolder("files")
        every { context.filesDir } returns files
        every { context.getExternalFilesDir(null) } returns files
        every { capabilities.shellTier() } returns ShellTier.LOCAL
        mockkObject(SuixingAccessibilityService.Companion, VirtualScreenManager, TouchFx, Shell)
        every { SuixingAccessibilityService.instance() } returns service
        every { VirtualScreenManager.state } returns virtual
        every { TouchFx.beginUiOperation(context, any(), null, null) } answers {
            feedback.begin(secondArg())
        }
        every { TouchFx.endUiOperation(any(), any(), any()) } answers {
            feedback.end(firstArg(), secondArg(), now = 100L, cancelled = thirdArg())
            Unit
        }
        coEvery { TouchFx.withoutOperationStatus<Any?>(any()) } coAnswers {
            feedback.hideForCapture()
            try {
                firstArg<suspend () -> Any?>().invoke()
            } finally {
                feedback.restoreAfterCapture()
            }
        }
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), Base64.NO_WRAP) } answers {
            assertFalse(feedback.hiddenForCapture)
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        tools = UiTools(context, capabilities)
    }

    @After fun tearDown() {
        unmockkStatic(Base64::class, BitmapFactory::class)
        unmockkObject(Shell, TouchFx, VirtualScreenManager, SuixingAccessibilityService.Companion)
        setSdk(originalSdk)
    }

    @Test fun reloadsSavedImageWithoutMainScreenFeedback() = runBlocking {
        val image = byteArrayOf(12, 34, 56)
        val saved = temporary.newFile("old.jpg").apply { writeBytes(image) }

        val result = tools.execute("ui_screenshot", buildJsonObject { put("path", saved.absolutePath) })

        assertImage(result, image)
        assertNull(feedback.snapshot(100L))
        coVerify(exactly = 0) { TouchFx.withoutOperationStatus<Any?>(any()) }
        coVerify(exactly = 0) { service.screenshotJpeg(any(), any(), any()) }
    }

    @Test fun virtualFrameDoesNotHideOrStartMainScreenFeedback() = runBlocking {
        virtual.value = VirtualScreenManager.State(running = true, displayId = 7, width = 1080, height = 1920)
        val image = byteArrayOf(3, 2, 1)
        coEvery { VirtualScreenManager.latestJpeg(640, 81, 7) } coAnswers {
            assertFalse(feedback.hiddenForCapture)
            image
        }

        val result = tools.execute("ui_screenshot", buildJsonObject {
            put("max_width", 640)
            put("quality", 81)
        })

        assertImage(result, image)
        assertNull(feedback.snapshot(100L))
        coVerify(exactly = 0) { TouchFx.withoutOperationStatus<Any?>(any()) }
        coVerify(exactly = 0) { service.screenshotJpeg(any(), any(), any()) }
    }

    @Test fun accessibilityCaptureIsNotWrappedInToolWideHiding() = runBlocking {
        val image = byteArrayOf(4, 5, 6)
        coEvery { service.screenshotJpeg(800, 65, Display.DEFAULT_DISPLAY) } coAnswers {
            assertFalse(feedback.hiddenForCapture)
            assertEquals("正在截图", feedback.current(100L))
            image
        }

        val result = tools.execute("ui_screenshot", buildJsonObject {
            put("max_width", 800)
            put("quality", 65)
        })

        assertImage(result, image)
        assertEquals(UiOperationResult.Success, feedback.snapshot(100L)?.result)
        coVerify(exactly = 0) { TouchFx.withoutOperationStatus<Any?>(any()) }
    }

    @Test fun privilegedCaptureRestoresFeedbackBeforeImageProcessing() = runBlocking {
        every { SuixingAccessibilityService.instance() } returns null
        every { capabilities.shellTier() } returns ShellTier.ROOT
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val image = byteArrayOf(8, 6, 4, 2)
        val scratch = File(files, "screen.png")
        var captureWasHidden = false
        var decodeWasHidden = true
        var compressWasHidden = true
        coEvery { Shell.root("screencap -p ${scratch.absolutePath}", "auto") } coAnswers {
            captureWasHidden = feedback.hiddenForCapture
            scratch.writeBytes(png)
            ShellResult(true, "", "", 0)
        }
        val bitmap = mockk<Bitmap>()
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeByteArray(any(), 0, png.size) } answers {
            decodeWasHidden = feedback.hiddenForCapture
            assertArrayEquals(png, firstArg<ByteArray>())
            bitmap
        }
        every { bitmap.width } returns 1
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, any()) } answers {
            compressWasHidden = feedback.hiddenForCapture
            thirdArg<OutputStream>().write(image)
            true
        }
        every { bitmap.recycle() } just Runs

        val result = tools.execute("ui_screenshot", JsonObject(emptyMap()))

        assertImage(result, image)
        assertTrue(captureWasHidden)
        assertFalse(decodeWasHidden)
        assertFalse(compressWasHidden)
        assertFalse(feedback.hiddenForCapture)
        assertFalse(scratch.exists())
        assertArrayEquals(image, File(files, "shots").listFiles()!!.single().readBytes())
        coVerify(exactly = 1) { TouchFx.withoutOperationStatus<Any?>(any()) }
    }

    private fun assertImage(result: ToolResult, expected: ByteArray) {
        assertTrue(result.output, result.ok)
        assertArrayEquals(expected, java.util.Base64.getDecoder().decode(result.imageBase64))
    }

    private fun setSdk(value: Int) {
        unsafe.putIntVolatile(unsafe.staticFieldBase(sdkField), unsafe.staticFieldOffset(sdkField), value)
    }
}
