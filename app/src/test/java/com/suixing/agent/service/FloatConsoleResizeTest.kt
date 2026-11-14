package com.suixing.agent.service

import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatConsoleResizeTest {
    @Test fun `releasing before layout saves the final requested size`() = runBlocking {
        val metrics = DisplayMetrics().apply {
            density = 1f
            widthPixels = 1000
            heightPixels = 1000
        }
        val resources = mockk<Resources>()
        every { resources.displayMetrics } returns metrics
        var panelLayout = LinearLayout.LayoutParams(260, 400)
        var contentLayout = LinearLayout.LayoutParams(260, 200)
        val service = spyk(FloatConsoleService()).apply {
            panel = mockk<LinearLayout>(relaxed = true)
            contentScroll = mockk<ScrollView>(relaxed = true)
            root = mockk<LinearLayout>(relaxed = true)
            params = WindowManager.LayoutParams()
        }
        every { service.resources } returns resources
        every { service.getSystemService(WindowManager::class.java) } returns null
        every { service.panel.width } returns 260
        every { service.panel.visibility } returns View.VISIBLE
        every { service.panel.layoutParams } answers { panelLayout }
        every { service.panel.layoutParams = any() } answers { panelLayout = firstArg() }
        every { service.contentScroll.height } returns 200
        every { service.contentScroll.layoutParams } answers { contentLayout }
        every { service.contentScroll.layoutParams = any() } answers { contentLayout = firstArg() }
        every { service.root.width } returns 260
        every { service.root.height } returns 448
        var saved = AppSettings()
        val observedSizes = mutableListOf<Pair<Int, Int>>()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.update(any()) } answers {
            saved = firstArg<(AppSettings) -> AppSettings>().invoke(saved)
            observedSizes += saved.consoleWidthDp to saved.consoleHeightDp
            true
        }
        val saveScope = this
        mockkObject(Core)
        mockkStatic(TypedValue::class)
        try {
            every { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, any(), metrics) } answers { secondArg() }
            every { Core.appScope } returns saveScope
            every { Core.settings } returns settings
            service.onResizeTouch(event(MotionEvent.ACTION_DOWN, 0f, 0f))
            service.onResizeTouch(event(MotionEvent.ACTION_MOVE, 40f, 60f))
            assertEquals(300, panelLayout.width)
            assertEquals(260, contentLayout.height)
            // MOVE and UP can arrive before the requested traversal updates View.width/height.
            service.onResizeTouch(event(MotionEvent.ACTION_UP, 40f, 60f))
            coroutineContext.job.children.toList().joinAll()
            assertEquals("the final move must survive persistence", 300, saved.consoleWidthDp)
            assertEquals("height must use the same final resize", 260, saved.consoleHeightDp)
            assertEquals("settings observers must receive one complete size", listOf(300 to 260), observedSizes)
            contentLayout.height = LinearLayout.LayoutParams.WRAP_CONTENT
            panelLayout.width = 260
            service.onResizeTouch(event(MotionEvent.ACTION_DOWN, 0f, 0f))
            service.onResizeTouch(event(MotionEvent.ACTION_UP, 0f, 0f))
            coroutineContext.job.children.toList().joinAll()
            assertEquals("a tap keeps the measured width", 260, saved.consoleWidthDp)
            assertEquals("an unresized wrap-content body keeps its measured height", 200, saved.consoleHeightDp)
        } finally {
            service.scope.cancel()
            unmockkStatic(TypedValue::class)
            unmockkObject(Core)
        }
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent = mockk<MotionEvent>().also {
        every { it.actionMasked } returns action
        every { it.rawX } returns x
        every { it.rawY } returns y
    }
}
