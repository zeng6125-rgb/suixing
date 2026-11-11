package com.suixing.agent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolFxTest {
    @Test fun bashAndLegacyShellBothReportActualUiCommands() {
        for (tool in listOf("bash", "run_shell")) {
            assertTrue(shellToolDrivesUi(tool, "input tap 10 20"))
            assertTrue(shellToolDrivesUi(tool, "input text example"))
            assertTrue(shellToolDrivesUi(tool, "am start -n example/.Main"))
        }
    }

    @Test fun readOnlyBashCommandsRemainNeutral() {
        for (command in listOf("ls -la", "cat report.txt", "dumpsys activity")) {
            assertFalse(shellToolDrivesUi("bash", command))
        }
    }

    @Test fun explicitMainDisplayAndLauncherCommandsStartFeedback() {
        for (tool in listOf("bash", "run_shell")) {
            assertTrue(shellToolDrivesUi(tool, "input -d 0 tap 10 20"))
            assertTrue(shellToolDrivesUi(tool, "monkey -p example.app -c android.intent.category.LAUNCHER 1"))
        }
    }

    @Test fun explicitVirtualDisplayCommandsLeaveMainScreenFeedbackAlone() {
        assertFalse(shellToolDrivesUi("bash", "input -d 4 tap 10 20"))
        assertFalse(shellToolDrivesUi("bash", "am start --display 4 -n example/.Main"))
        assertFalse(shellToolDrivesUi("bash", "monkey --display-id 4 -p example.app 1"))
        assertTrue(shellToolDrivesUi("bash", "am start --display 4 -n example/.Main; input tap 10 20"))
    }

    @Test fun existingLongPressAndDragCommandsKeepTheirMainScreenFeedback() {
        for (action in listOf("longpress", "draganddrop")) {
            assertTrue(shellToolDrivesUi("bash", "input $action 10 20 30 40 800"))
            assertTrue(shellToolDrivesUi("run_shell", "input -d 0 $action 10 20 30 40 800"))
            assertFalse(shellToolDrivesUi("bash", "input -d 4 $action 10 20 30 40 800"))
        }
    }

    @Test fun unrelatedToolsCannotBeMisclassifiedAsShellUiActions() {
        assertFalse(shellToolDrivesUi("read_file", "input tap 10 20"))
        assertFalse(shellToolDrivesUi("mcp_bash", "input tap 10 20"))
    }

    @Test fun absentCommandsDoNotTriggerAnEpisode() {
        assertFalse(shellToolDrivesUi("bash", null))
        assertFalse(shellToolDrivesUi("bash", ""))
    }
}
