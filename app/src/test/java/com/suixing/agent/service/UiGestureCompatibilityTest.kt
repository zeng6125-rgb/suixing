package com.suixing.agent.service

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiGestureCompatibilityTest {
    private lateinit var service: SuixingAccessibilityService

    @Before fun setUp() {
        mockkObject(TouchFx)
        mockkConstructor(GestureDescription.Builder::class)
        val description = mockk<GestureDescription>()
        every { anyConstructed<GestureDescription.Builder>().addStroke(any()) } answers {
            self as GestureDescription.Builder
        }
        every { anyConstructed<GestureDescription.Builder>().build() } returns description
        // The JVM Android stub reports a pre-R SDK, where this method is unavailable.
        every { anyConstructed<GestureDescription.Builder>().setDisplayId(any()) } throws
            NoSuchMethodError("GestureDescription.Builder.setDisplayId requires API 30")
        every { TouchFx.tap(any(), any(), any()) } just Runs
        every { TouchFx.unbindService(any()) } just Runs
        service = spyk(SuixingAccessibilityService())
        every { service.dispatchGesture(any(), any(), any()) } answers {
            secondArg<GestureResultCallback>().onCompleted(firstArg())
            true
        }
    }

    @After fun tearDown() {
        try {
            if (::service.isInitialized) service.onDestroy()
        } finally {
            unmockkConstructor(GestureDescription.Builder::class)
            unmockkObject(TouchFx)
        }
    }

    @Test fun secondaryDisplayIsUnsupportedBeforeApi30() = runBlocking {
        assertFalse(service.tap(30f, 40f, displayId = 7))
        verify(exactly = 0) { service.dispatchGesture(any(), any(), any()) }
    }

    @Test fun defaultDisplayStillDispatchesBeforeApi30() = runBlocking {
        assertTrue(service.tap(30f, 40f))
        verify(exactly = 0) { anyConstructed<GestureDescription.Builder>().setDisplayId(any()) }
    }
}
