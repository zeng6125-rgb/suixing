package com.suixing.agent.tools

import com.suixing.agent.agent.RunLedger
import com.suixing.agent.data.JOB_DONE
import com.suixing.agent.data.JOB_RUNNING
import com.suixing.agent.data.JobInfo
import com.suixing.agent.data.JobStatus
import com.suixing.agent.data.JobStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JobToolsLedgerTest {
    private val args = JsonObject(emptyMap())
    private val unchanged = ToolResult.success("same result")
    private val running = JobStatus(JobInfo("job-1", "download", "command", 0, "/log"),
        JOB_RUNNING, 1000, null, "waiting")
    private val jobs = mockk<JobStore> {
        every { takeFileChanges(any(), any()) } returns ObservedFileChanges(emptyList())
    }
    private val tools = JobTools(jobs)

    private suspend fun list(status: JobStatus): ToolResult {
        every { jobs.list() } returns listOf(status)
        return tools.execute("job_list", args)
    }

    @Test fun `job elapsed time preserves output but does not reset repeated work`() = runBlocking {
        val ledger = RunLedger(maxRounds = 30)
        ledger.record(1, "job_list", args, list(running))
        ledger.record(2, "run_shell", args, unchanged)
        ledger.record(3, "run_shell", args, unchanged)
        val later = list(running.copy(elapsedMs = 2000))
        assertEquals("1 background job(s):\n- job-1 [running] 2.0s  download | last: waiting", later.output)
        ledger.record(4, "job_list", args, later)
        ledger.record(5, "run_shell", args, unchanged)
        assertNotNull(ledger.warning())
    }

    @Test fun `job status exit and actual output changes reset repeated work`() = runBlocking {
        for (changed in listOf(running.copy(status = JOB_DONE), running.copy(exitCode = 0),
            running.copy(lastLine = "received 2.0s of data"))) {
            val ledger = RunLedger(maxRounds = 30)
            ledger.record(1, "job_list", args, list(running))
            ledger.record(2, "run_shell", args, unchanged)
            ledger.record(3, "run_shell", args, unchanged)
            ledger.record(4, "job_list", args, list(changed))
            ledger.record(5, "run_shell", args, unchanged)
            ledger.record(6, "run_shell", args, unchanged)
            assertNull(ledger.warning())
            ledger.record(7, "run_shell", args, unchanged)
            assertNotNull(ledger.warning())
        }
    }

    @Test fun `job list polling never warns by itself`() = runBlocking {
        val ledger = RunLedger(maxRounds = 30)
        val result = list(running)
        for (step in 1..7) {
            ledger.record(step, "job_list", args, result)
            assertNull(ledger.warning())
        }
    }
}
