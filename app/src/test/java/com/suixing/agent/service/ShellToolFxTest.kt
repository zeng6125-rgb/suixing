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

    @Test fun unrelatedToolsCannotBeMisclassifiedAsShellUiActions() {
        assertFalse(shellToolDrivesUi("read_file", "input tap 10 20"))
        assertFalse(shellToolDrivesUi("mcp_bash", "input tap 10 20"))
    }

    @Test fun absentCommandsDoNotTriggerAnEpisode() {
        assertFalse(shellToolDrivesUi("bash", null))
        assertFalse(shellToolDrivesUi("bash", ""))
    }
}
