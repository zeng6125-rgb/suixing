package com.suixing.agent.service

import android.content.Context
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.terminal.TerminalCaller
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiOperationLifecycleTest {
    private val context = mockk<Context>()

    @Before fun setUp() {
        mockkObject(TouchFx)
        every { TouchFx.beginUiOperation(context, any(), any(), any()) } returns 21L
        every { TouchFx.endUiOperation(any(), any(), any()) } just Runs
        coEvery { TouchFx.withoutInputControls<ToolResult>(any()) } coAnswers {
            firstArg<suspend () -> ToolResult>().invoke()
        }
    }

    @After fun tearDown() {
        unmockkObject(TouchFx)
    }

    @Test fun successfulToolReturnsItsResultAndPublishesCompletion() = runBlocking {
        val result = ToolResult.success("已点击")

        assertSame(result, TouchFx.withUiOperation(context, "正在点击") {
            verify(exactly = 1) { TouchFx.beginUiOperation(context, "正在点击") }
            verify(exactly = 0) { TouchFx.endUiOperation(any(), any()) }
            result
        })

        coVerifyOrder {
            TouchFx.beginUiOperation(context, "正在点击")
            TouchFx.withoutInputControls<ToolResult>(any())
            TouchFx.endUiOperation(21L, true)
        }
        verify(exactly = 1) { TouchFx.endUiOperation(21L, true) }
    }

    @Test fun failedToolReturnsItsFailureWithoutPublishingCompletion() = runBlocking {
        val result = ToolResult.failure("手势被系统拒绝")

        assertSame(result, TouchFx.withUiOperation(context, "正在点击") { result })

        verify(exactly = 1) { TouchFx.beginUiOperation(context, "正在点击") }
        verify(exactly = 1) { TouchFx.endUiOperation(21L, false) }
        verify(exactly = 0) { TouchFx.endUiOperation(any(), true) }
    }

    @Test fun cancellationPropagatesAndPublishesStoppedStatusWithoutCompletion() = runBlocking {
        val cancellation = CancellationException("任务已取消")

        val thrown = runCatching {
            TouchFx.withUiOperation(context, "正在滑动") { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        verify(exactly = 1) { TouchFx.beginUiOperation(context, "正在滑动") }
        verify(exactly = 1) { TouchFx.endUiOperation(21L, false, cancelled = true) }
        verify(exactly = 0) { TouchFx.endUiOperation(any(), true) }
        coVerify(exactly = 1) { TouchFx.withoutInputControls<ToolResult>(any()) }
    }

    @Test fun toolExceptionPropagatesAndClearsTheOperationWithoutCompletion() = runBlocking {
        val failure = IllegalStateException("无障碍服务已断开")

        val thrown = runCatching {
            TouchFx.withUiOperation(context, "正在输入") { throw failure }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        verify(exactly = 1) { TouchFx.endUiOperation(21L, false) }
        verify(exactly = 0) { TouchFx.endUiOperation(any(), true) }
    }

    @Test fun toolsWithoutALabelExecuteWithoutOpeningAnOperation() = runBlocking {
        val result = ToolResult.success("已等待")

        assertSame(result, TouchFx.withUiOperation(context, null) { result })

        verify(exactly = 0) { TouchFx.beginUiOperation(any(), any()) }
        verify(exactly = 0) { TouchFx.endUiOperation(any(), any()) }
        coVerify(exactly = 0) { TouchFx.withoutInputControls<ToolResult>(any()) }
    }

    @Test fun operationBelongsToTheActualToolCaller() = runBlocking {
        val result = ToolResult.success("已点击")
        withContext(TerminalCaller("screen-chat", "screen-run")) {
            assertSame(result, TouchFx.withUiOperation(context, "正在点击") { result })
        }

        verify(exactly = 1) {
            TouchFx.beginUiOperation(context, "正在点击", UiOperationOwner("screen-chat", "screen-run"))
        }
        verify(exactly = 1) { TouchFx.endUiOperation(21L, true, cancelled = false) }
    }

    @Test fun readingAndWaitingKeepControlsAvailable() = runBlocking {
        val result = ToolResult.success("已读取")
        assertSame(result, TouchFx.withUiOperation(context, "正在读取屏幕") { result })
        assertSame(result, TouchFx.withUiOperation(context, "正在等待") { result })

        coVerify(exactly = 0) { TouchFx.withoutInputControls<ToolResult>(any()) }
        verify(exactly = 2) { TouchFx.endUiOperation(21L, true, cancelled = false) }
    }

    @Test fun operationForwardsTheActualRunsCancellationCallback() = runBlocking {
        var cancellations = 0
        val cancelRun = { cancellations++; true }
        var forwarded: (() -> Boolean)? = null
        val owner = UiOperationOwner("screen-chat", "screen-run")
        every { TouchFx.beginUiOperation(context, "正在点击", owner, any()) } answers {
            forwarded = arg(3)
            21L
        }
        val result = ToolResult.success("已点击")

        withContext(TerminalCaller(owner.chatId, owner.runId, cancelRun)) {
            assertSame(result, TouchFx.withUiOperation(context, "正在点击") { result })
        }

        assertSame(cancelRun, forwarded)
        assertTrue(forwarded?.invoke() == true)
        assertEquals(1, cancellations)
    }

    @Test fun nativeShellUiOperationsUseTheInputBarrier() = runBlocking {
        val result = ToolResult.success("已操作屏幕")

        assertSame(result, TouchFx.withUiOperation(context, "正在操作屏幕") { result })

        coVerify(exactly = 1) { TouchFx.withoutInputControls<ToolResult>(any()) }
        verify(exactly = 1) { TouchFx.endUiOperation(21L, true, cancelled = false) }
    }
}
