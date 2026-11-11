package com.suixing.agent.service

import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiScreenshotFeedbackTest {
    private lateinit var fixture: CaptureFixture

    @Before fun setUp() {
        mockkObject(TouchFx)
        mockkStatic(Bitmap::class)
        fixture = CaptureFixture()
        every { TouchFx.unbindService(any()) } just Runs
        coEvery { TouchFx.withoutOperationStatus<Unit>(any()) } coAnswers {
            fixture.hiddenDepth.incrementAndGet()
            try {
                firstArg<suspend () -> Unit>().invoke()
            } finally {
                fixture.hiddenDepth.decrementAndGet()
            }
        }
    }

    @After fun tearDown() {
        try {
            if (::fixture.isInitialized) {
                fixture.releaseCompression()
                fixture.service.onDestroy()
                runBlocking { withTimeout(TIMEOUT_MS) { fixture.ownerJob.join() } }
            }
        } finally {
            unmockkObject(TouchFx)
            unmockkStatic(Bitmap::class)
        }
    }

    @Test fun successfulCaptureRestoresFeedbackBeforeEncodingFinishes() = runBlocking {
        val call = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.screenshotJpeg(maxWidth = 64) }
        try {
            val callback = withTimeout(TIMEOUT_MS) { fixture.requested.await() }
            assertEquals(1, fixture.hiddenDepth.get())
            callback.onSuccess(fixture.screenshot)
            withTimeout(TIMEOUT_MS) { fixture.compressionStarted.await() }
            val encoding = fixture.ownerJob.children.single()

            assertEquals(0, fixture.hiddenDepth.get())
            assertFalse(call.isCompleted)
            assertEquals(0, fixture.bufferCloses.get())

            fixture.releaseCompression()
            assertArrayEquals(fixture.jpeg, withTimeout(TIMEOUT_MS) { call.await() })
            withTimeout(TIMEOUT_MS) { encoding.join(); fixture.bufferClosed.await() }
            assertEquals(1, fixture.bufferCloses.get())
        } finally {
            fixture.releaseCompression()
            call.cancelAndJoin()
        }
    }

    @Test fun failedCaptureRestoresFeedbackWithoutEncoding() = runBlocking {
        val call = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.screenshotJpeg(maxWidth = 64) }
        try {
            val callback = withTimeout(TIMEOUT_MS) { fixture.requested.await() }
            assertEquals(1, fixture.hiddenDepth.get())

            callback.onFailure(1)

            assertNull(withTimeout(TIMEOUT_MS) { call.await() })
            assertEquals(0, fixture.hiddenDepth.get())
            assertEquals(0, fixture.wraps.get())
            assertEquals(0, fixture.bufferCloses.get())
        } finally {
            call.cancelAndJoin()
        }
    }

    @Test fun cancellingTheFrameWaitClosesALateBufferWithoutEncoding() = runBlocking {
        val call = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.screenshotJpeg(maxWidth = 64) }
        try {
            val callback = withTimeout(TIMEOUT_MS) { fixture.requested.await() }
            assertEquals(1, fixture.hiddenDepth.get())

            call.cancel(CancellationException("cancelled while capturing"))
            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.hiddenDepth.get())
            callback.onSuccess(fixture.screenshot)

            assertEquals(1, fixture.bufferCloses.get())
            assertEquals(0, fixture.wraps.get())
            assertFalse(fixture.compressionStarted.isCompleted)
        } finally {
            call.cancelAndJoin()
        }
    }

    @Test fun cancellationBeforeSuccessfulCaptureResumesClosesTheBufferOnce() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val call = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            fixture.service.screenshotJpeg(maxWidth = 64)
        }
        try {
            val callback = withTimeout(TIMEOUT_MS) { fixture.requested.await() }
            callback.onSuccess(fixture.screenshot)
            assertEquals(1, fixture.hiddenDepth.get())
            assertEquals(0, fixture.bufferCloses.get())

            call.cancel(CancellationException("cancelled before capture resumed"))
            dispatcher.drain()

            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.hiddenDepth.get())
            assertEquals(1, fixture.bufferCloses.get())
            assertEquals(0, fixture.wraps.get())
        } finally {
            call.cancel()
            dispatcher.drain()
            call.join()
        }
    }

    @Test fun cancellationDuringEncodingKeepsTheBufferUntilTheWorkFinishes() = runBlocking {
        val call = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.screenshotJpeg(maxWidth = 64) }
        try {
            val callback = withTimeout(TIMEOUT_MS) { fixture.requested.await() }
            callback.onSuccess(fixture.screenshot)
            withTimeout(TIMEOUT_MS) { fixture.compressionStarted.await() }
            val encoding = fixture.ownerJob.children.single()
            assertEquals(0, fixture.hiddenDepth.get())

            call.cancel(CancellationException("cancelled while encoding"))

            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.bufferCloses.get())
            fixture.releaseCompression()
            withTimeout(TIMEOUT_MS) { encoding.join(); fixture.bufferClosed.await() }
            assertEquals(1, fixture.bufferCloses.get())
        } finally {
            fixture.releaseCompression()
            call.cancelAndJoin()
        }
    }

    private class CaptureFixture {
        val service = spyk(SuixingAccessibilityService())
        val ownerJob = SuixingAccessibilityService::class.java.getDeclaredField("screenshotJob").let {
            it.isAccessible = true
            it.get(service) as Job
        }
        val hiddenDepth = AtomicInteger()
        val wraps = AtomicInteger()
        val bufferCloses = AtomicInteger()
        val requested = CompletableDeferred<TakeScreenshotCallback>()
        val compressionStarted = CompletableDeferred<Unit>()
        val bufferClosed = CompletableDeferred<Unit>()
        val jpeg = byteArrayOf(0x21, 0x43, 0x65)
        private val compressionGate = CountDownLatch(1)
        private val buffer = mockk<HardwareBuffer>()
        private val colorSpace = mockk<ColorSpace>()
        private val wrapped = mockk<Bitmap>()
        private val software = mockk<Bitmap>()
        val screenshot = mockk<ScreenshotResult>()

        init {
            every { service.mainExecutor } returns Executor { it.run() }
            every { service.takeScreenshot(any(), any(), any()) } answers {
                requested.complete(thirdArg<TakeScreenshotCallback>())
                Unit
            }
            every { screenshot.hardwareBuffer } returns buffer
            every { screenshot.colorSpace } returns colorSpace
            every { buffer.close() } answers {
                bufferCloses.incrementAndGet()
                bufferClosed.complete(Unit)
                Unit
            }
            every { Bitmap.wrapHardwareBuffer(buffer, colorSpace) } answers {
                wraps.incrementAndGet()
                wrapped
            }
            every { wrapped.copy(Bitmap.Config.ARGB_8888, false) } returns software
            every { wrapped.recycle() } just Runs
            every { software.width } returns 64
            every { software.recycle() } just Runs
            every { software.compress(Bitmap.CompressFormat.JPEG, 72, any()) } answers {
                compressionStarted.complete(Unit)
                if (compressionGate.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    thirdArg<OutputStream>().write(jpeg)
                    true
                } else false
            }
        }

        fun releaseCompression() { compressionGate.countDown() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val actions = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { actions.add(block) }
        fun drain() {
            while (true) {
                val action = actions.poll() ?: return
                action.run()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
