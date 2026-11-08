package com.suixing.agent.terminal

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxInstallerLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aCancelledOwnerCannotLeaveTheInstallationMutexLocked() = runBlocking {
        val files = temporary.newFolder("files")
        val context = mockk<Context>(relaxed = true) { every { filesDir } returns files }
        val owner = Job().apply { cancel() }
        val installer = LinuxInstaller(context, CoroutineScope(owner + Dispatchers.Unconfined))
        val distro = LINUX_DISTRIBUTIONS.first()
        installer.install(distro)
        installer.remove(distro)
        assertEquals(LinuxPhase.NOT_INSTALLED, installer.states.value.getValue(distro.id).phase)
    }
}
