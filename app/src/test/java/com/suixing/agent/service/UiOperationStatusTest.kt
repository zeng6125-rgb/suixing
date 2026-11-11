package com.suixing.agent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UiOperationStatusTest {
    @Test fun failedActionRemainsVisibleInsteadOfDisappearing() {
        val status = UiOperationStatus()
        val token = status.begin("正在输入")

        assertTrue(status.end(token, completed = false, now = 100))
        assertEquals("输入失败", status.current(100))
        assertEquals("输入失败", status.current(899))
        assertNull(status.current(900))
    }

    @Test fun waitingOnTheMainScreenHasAVisibleStatus() {
        assertEquals("正在等待", uiOperationLabel("ui_wait", displayId = -1))
        assertNull(uiOperationLabel("ui_wait", displayId = 4))
    }

    @Test fun latestActiveOperationRemainsVisibleWhenAnEarlierReadEnds() {
        val status = UiOperationStatus()
        val read = status.begin("正在读取屏幕")
        val screenshot = status.begin("正在截图")

        assertEquals("正在截图", status.current(100))
        assertFalse(status.end(read, completed = true, now = 100))
        assertEquals("正在截图", status.current(101))
        assertTrue(status.end(screenshot, completed = false, now = 102))
        assertEquals("截图失败", status.current(102))
    }

    @Test fun completingTheLatestOperationKeepsEarlierActiveWorkVisible() {
        val status = UiOperationStatus()
        status.begin("正在读取屏幕")
        val screenshot = status.begin("正在截图")

        assertTrue(status.end(screenshot, completed = true, now = 100))
        assertEquals("正在读取屏幕", status.current(101))
    }

    @Test fun successRemainsForEightHundredMillisecondsAndThenDisappears() {
        val status = UiOperationStatus()
        val token = status.begin("正在点击")

        assertTrue(status.end(token, completed = true, now = 100))
        assertEquals("点击完成", status.current(100))
        assertEquals("点击完成", status.current(899))
        assertNull(status.current(900))
    }

    @Test fun failureDoesNotPublishSuccess() {
        val status = UiOperationStatus()
        val token = status.begin("正在输入")

        assertTrue(status.end(token, completed = false, now = 100))
        assertEquals("输入失败", status.current(100))
        assertEquals(UiOperationResult.Failure, status.snapshot(100)?.result)
    }

    @Test fun startingAnotherOperationClearsThePreviousCompletion() {
        val status = UiOperationStatus()
        val first = status.begin("正在点击")
        assertTrue(status.end(first, completed = true, now = 100))

        val next = status.begin("正在滑动")
        assertEquals("正在滑动", status.current(101))
        assertTrue(status.end(next, completed = false, now = 102))
        assertEquals("滑动失败", status.current(102))
    }

    @Test fun supportedMainScreenToolsHaveFixedActionLabels() {
        val labels = mapOf(
            "ui_dump" to "正在读取屏幕",
            "ui_screenshot" to "正在截图",
            "ui_tap" to "正在点击",
            "ui_click_text" to "正在点击",
            "ui_long_press" to "正在长按",
            "ui_swipe" to "正在滑动",
            "ui_type" to "正在输入",
            "ui_type_into" to "正在输入",
            "ui_key" to "正在发送按键",
            "ui_current_app" to "正在识别应用",
            "ui_wait" to "正在等待",
            "open_app" to "正在打开应用",
        )

        for ((tool, label) in labels) {
            assertEquals(tool, label, uiOperationLabel(tool, displayId = -1))
            assertNull(tool, uiOperationLabel(tool, displayId = 4))
        }
    }

    @Test fun readingAnExistingScreenshotDoesNotClaimScreenOperation() {
        assertNull(uiOperationLabel("ui_screenshot", displayId = -1,
            screenshotPath = "/storage/emulated/0/Download/screenshot.png"))
        assertNull(uiOperationLabel("read_file", displayId = -1))
        assertNull(uiOperationLabel("bash", displayId = -1))
        assertNull(uiOperationLabel("run_shell", displayId = -1))
    }

    @Test fun cancellationKeepsItsOwnerAndDoesNotClaimSuccess() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val token = status.begin("正在滑动", owner)

        assertTrue(status.end(token, completed = false, now = 100, cancelled = true))
        assertEquals(UiOperationSnapshot("滑动已停止", owner, UiOperationResult.Cancelled), status.snapshot(100))
        assertNull(status.snapshot(900))
    }

    @Test fun endingAnotherOperationDoesNotChangeTheVisibleOwner() {
        val status = UiOperationStatus()
        val earlier = status.begin("正在读取屏幕", UiOperationOwner("first-chat", "first-run"))
        val owner = UiOperationOwner("second-chat", "second-run")
        status.begin("正在点击", owner)

        status.end(earlier, completed = true, now = 100)
        assertEquals(UiOperationSnapshot("正在点击", owner), status.snapshot(101))
    }

    @Test fun screenshotRestoresTheLatestOperationInsteadOfTheHiddenCopy() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val first = status.begin("正在读取屏幕", owner)
        status.hideForCapture()
        assertTrue(status.hiddenForCapture)
        assertNull(status.snapshot(100))

        status.end(first, completed = true, now = 101)
        status.begin("正在点击", owner)
        assertNull(status.snapshot(102))
        status.restoreAfterCapture()

        assertFalse(status.hiddenForCapture)
        assertEquals(UiOperationSnapshot("正在点击", owner), status.snapshot(103))
    }

    @Test fun overlappingScreenshotsDoNotRestoreTheWindowBeforeBothFinish() {
        val status = UiOperationStatus()
        status.begin("正在截图")
        status.hideForCapture()
        status.hideForCapture()
        status.restoreAfterCapture()
        assertTrue(status.hiddenForCapture)
        assertNull(status.snapshot(100))

        status.restoreAfterCapture()
        assertFalse(status.hiddenForCapture)
        assertEquals("正在截图", status.current(101))
    }

    @Test fun ownedActionReturnsToThinkingAfterCompletionExpires() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val token = status.begin("正在点击", owner)

        assertTrue(status.end(token, completed = true, now = 100))
        assertEquals(UiOperationSnapshot("正在思考", owner), status.snapshot(900))
        assertEquals("正在思考", status.current(20_000))
        assertEquals(owner, status.snapshot(20_000)?.owner)
    }

    @Test fun latestOwnedSessionRemainsSelectedAfterOverlappingOperationsEnd() {
        val status = UiOperationStatus()
        val earlierOwner = UiOperationOwner("first-chat", "first-run")
        val latestOwner = UiOperationOwner("second-chat", "second-run")
        val read = status.begin("正在读取屏幕", earlierOwner)
        val tap = status.begin("正在点击", latestOwner)

        assertTrue(status.end(tap, completed = true, now = 100))
        assertEquals(UiOperationSnapshot("正在读取屏幕", earlierOwner), status.snapshot(101))
        assertFalse(status.end(read, completed = true, now = 102))
        assertEquals(UiOperationSnapshot("正在思考", latestOwner), status.snapshot(900))
    }

    @Test fun theNextOperationCanReturnToAnEarlierOwnedSession() {
        val status = UiOperationStatus()
        val firstOwner = UiOperationOwner("first-chat", "first-run")
        val secondOwner = UiOperationOwner("second-chat", "second-run")
        val first = status.begin("正在点击", firstOwner)
        status.end(first, completed = true, now = 100)
        val second = status.begin("正在读取屏幕", secondOwner)
        status.end(second, completed = true, now = 200)
        assertEquals(UiOperationSnapshot("正在思考", secondOwner), status.snapshot(1_000))

        val resumed = status.begin("正在滑动", firstOwner)
        assertEquals(UiOperationSnapshot("正在滑动", firstOwner), status.snapshot(1_001))
        status.end(resumed, completed = true, now = 1_100)
        assertEquals(UiOperationSnapshot("正在思考", firstOwner), status.snapshot(1_900))
    }

    @Test fun screenshotRestoresThinkingAfterTheCompletionHoldExpires() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val token = status.begin("正在截图", owner)
        status.hideForCapture()

        assertTrue(status.end(token, completed = true, now = 100))
        assertNull(status.snapshot(20_000))
        status.restoreAfterCapture()

        assertFalse(status.hiddenForCapture)
        assertEquals(UiOperationSnapshot("正在思考", owner), status.snapshot(20_000))
    }

    @Test fun readOnlyToolGapKeepsTheOwnedThinkingSession() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val token = status.begin("正在点击", owner)
        status.end(token, completed = true, now = 100)

        assertNull(uiOperationLabel("read_file", displayId = -1))
        assertNull(uiOperationLabel("bash", displayId = -1))
        assertEquals(UiOperationSnapshot("正在思考", owner), status.snapshot(20_000))
    }

    @Test fun finishingOneOwnerKeepsTheOtherOwnerThinking() {
        val status = UiOperationStatus()
        val firstOwner = UiOperationOwner("first-chat", "first-run")
        val secondOwner = UiOperationOwner("second-chat", "second-run")
        val first = status.begin("正在点击", firstOwner)
        status.end(first, completed = true, now = 100)
        val second = status.begin("正在读取屏幕", secondOwner)
        status.end(second, completed = true, now = 200)

        status.finish(secondOwner)

        assertEquals(setOf(firstOwner), status.ownersFlow.value)
        assertTrue(status.hasSession)
        assertEquals(UiOperationSnapshot("正在思考", firstOwner), status.snapshot(20_000))
        status.finish(firstOwner)
        assertFalse(status.hasSession)
        assertNull(status.snapshot(20_001))
    }

    @Test fun finishingAnOwnerRemovesActiveWorkAndPreventsLateCompletion() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val read = status.begin("正在读取屏幕", owner)
        val tap = status.begin("正在点击", owner)
        status.end(tap, completed = true, now = 100)

        status.finish(owner)

        assertFalse(status.hasSession)
        assertNull(status.snapshot(101))
        assertFalse(status.end(read, completed = true, now = 102))
        assertNull(status.snapshot(900))
    }

    @Test fun finishingAnOldRunDoesNotClearANewRunInTheSameChat() {
        val status = UiOperationStatus()
        val oldOwner = UiOperationOwner("screen-chat", "old-run")
        val newOwner = UiOperationOwner("screen-chat", "new-run")
        val old = status.begin("正在读取屏幕", oldOwner)
        val current = status.begin("正在点击", newOwner)

        status.finish(oldOwner)

        assertEquals(setOf(newOwner), status.ownersFlow.value)
        assertEquals(UiOperationSnapshot("正在点击", newOwner), status.snapshot(100))
        assertFalse(status.end(old, completed = true, now = 101))
        status.end(current, completed = true, now = 200)
        assertEquals(UiOperationSnapshot("正在思考", newOwner), status.snapshot(1_000))
    }

    @Test fun cancellationClearsAllOperationsForItsOwnerAndKeepsTheStoppedHold() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val earlier = status.begin("正在读取屏幕", owner)
        val latest = status.begin("正在滑动", owner)

        assertTrue(status.end(latest, completed = false, now = 100, cancelled = true))

        assertFalse(status.hasSession)
        assertEquals(UiOperationSnapshot("滑动已停止", owner, UiOperationResult.Cancelled), status.snapshot(899))
        assertFalse(status.end(earlier, completed = true, now = 899))
        assertNull(status.snapshot(900))
    }

    @Test fun cancellingAnOlderOperationClearsOnlyItsOwnersSession() {
        val status = UiOperationStatus()
        val cancelledOwner = UiOperationOwner("first-chat", "first-run")
        val remainingOwner = UiOperationOwner("second-chat", "second-run")
        val earlier = status.begin("正在读取屏幕", cancelledOwner)
        val sameOwner = status.begin("正在截图", cancelledOwner)
        val latest = status.begin("正在点击", remainingOwner)

        assertFalse(status.end(earlier, completed = false, now = 100, cancelled = true))

        assertEquals(setOf(remainingOwner), status.ownersFlow.value)
        assertFalse(status.end(sameOwner, completed = true, now = 101))
        assertEquals(UiOperationSnapshot("正在点击", remainingOwner), status.snapshot(102))
        status.end(latest, completed = true, now = 200)
        assertEquals(UiOperationSnapshot("正在思考", remainingOwner), status.snapshot(1_000))
    }

    @Test fun captureVisibilityDoesNotEndTheLogicalSession() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        val token = status.begin("正在截图", owner)
        status.hideForCapture()
        status.hideForCapture()
        status.end(token, completed = true, now = 100)

        assertTrue(status.hasSession)
        assertEquals(setOf(owner), status.ownersFlow.value)
        assertNull(status.snapshot(20_000))
        status.restoreAfterCapture()
        assertTrue(status.hasSession)
        assertNull(status.snapshot(20_001))
        status.restoreAfterCapture()

        assertTrue(status.hasSession)
        assertEquals(UiOperationSnapshot("正在思考", owner), status.snapshot(20_002))
    }

    @Test fun thinkingKeepsTheActualRunsCancellationCallback() {
        val status = UiOperationStatus()
        val owner = UiOperationOwner("screen-chat", "screen-run")
        var stopped = false
        val cancelRun = { stopped = true; true }
        val token = status.begin("正在点击", owner, cancelRun)
        status.end(token, completed = true, now = 100)

        val thinking = status.snapshot(900)

        assertEquals("正在思考", thinking?.label)
        assertEquals(owner, thinking?.owner)
        assertSame(cancelRun, thinking?.cancelRun)
        assertTrue(thinking?.cancelRun?.invoke() == true)
        assertTrue(stopped)
    }
}
