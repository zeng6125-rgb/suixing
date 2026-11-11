package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.Shell
import com.suixing.agent.capability.ShellResult
import com.suixing.agent.capability.ShellTier
import com.suixing.agent.service.TouchFx
import com.suixing.agent.ui.settings.toolCopyZh
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BashToolsTest {
    private lateinit var shellTools: ShellTools
    private lateinit var bashTools: BashTools
    private lateinit var registry: ToolRegistry
    private lateinit var capabilities: CapabilityHub

    private fun args(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private suspend fun run(raw: String): ToolResult = registry.find("bash")!!.run(args(raw))

    @Before fun setUp() {
        mockkObject(Shell, TouchFx)
        every { Shell.bundledBash() } returns "/native/libbash.so"
        every { TouchFx.traceCall(any(), tool = any(), detail = any(), matched = any()) } just Runs
        coEvery { Shell.local(any(), any(), any()) } returns ShellResult(true, "native output", "", 0)
        capabilities = mockk(relaxed = true)
        every { capabilities.shellTier() } returns ShellTier.LOCAL
        shellTools = ShellTools(mockk<Context>(relaxed = true), capabilities)
        bashTools = BashTools(shellTools)
        registry = ToolRegistry::class.java.getDeclaredConstructor().let {
            it.isAccessible = true
            it.newInstance()
        }
        registry.add(shellTools)
        registry.add(bashTools)
    }

    @After fun tearDown() {
        unmockkObject(Shell, TouchFx)
    }

    @Test fun nativeSchemaDoesNotRequirePrivilegedCapabilitiesOrOfferPrivilegeSelectors() {
        val tool = registry.find("bash")!!
        assertEquals(TIER_NONE, tool.tier)
        assertEquals("Shell", registry.groupOf("bash"))
        assertEquals(setOf("command", "observe_paths"), tool.spec.parameters["properties"]!!.jsonObject.keys)
        assertEquals(listOf("command"), tool.spec.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(registry.promptSummary().contains("- bash:"))
        assertTrue(toolCopyZh("bash", "fallback").contains("原生 Bash"))
    }

    @Test fun registryExecutionHonorsLocalCeilingAndRealBashEvenWhenOverridesRequestRootAndSh() = runBlocking {
        val result = run("""{"command":"printf native","mode":"root","shell":"sh"}""")
        assertTrue(result.ok)
        assertTrue(result.output.startsWith("[local(app)+bash] exit=0\n"))
        assertTrue(result.output.contains("native output"))
        coVerify(exactly = 1) { Shell.local("printf native", "bash", null) }
        coVerify(exactly = 0) { Shell.root(any(), any()) }
        coVerify(exactly = 0) { Shell.shizuku(any(), any()) }
        coVerify(exactly = 0) { capabilities.ensureRootProbed() }
        verify(exactly = 1) { TouchFx.traceCall(any(), tool = "bash", detail = "printf native", matched = any()) }
    }

    @Test fun legacyRunShellStillUsesItsExistingAutoPrivilegeRoute() = runBlocking {
        every { capabilities.shellTier() } returns ShellTier.ROOT
        coEvery { Shell.root("printf legacy") } returns ShellResult(true, "legacy output", "", 0)
        val result = registry.find("run_shell")!!.run(args("""{"command":"printf legacy"}"""))
        assertTrue(result.ok)
        assertTrue(result.output.startsWith("[root+bash] exit=0\n"))
        coVerify(exactly = 1) { Shell.root("printf legacy") }
        coVerify(exactly = 0) { Shell.local(any(), any(), any()) }
    }

    @Test fun nativeBashUsesGrantedRootWithoutSwitchingToSh() = runBlocking {
        every { capabilities.shellTier() } returns ShellTier.ROOT
        coEvery { Shell.root("printf native", "bash") } returns ShellResult(true, "native root output", "", 0)
        val result = run("""{"command":"printf native"}""")
        assertTrue(result.ok)
        assertTrue(result.output.startsWith("[root+bash] exit=0\n"))
        coVerify(exactly = 1) { Shell.root("printf native", "bash") }
        coVerify(exactly = 0) { Shell.local(any(), any(), any()) }
    }

    @Test fun nativeBashUsesGrantedShizukuWithoutSwitchingToSh() = runBlocking {
        every { capabilities.shellTier() } returns ShellTier.SHIZUKU
        coEvery { Shell.shizuku("printf native", "bash") } returns ShellResult(true, "native shell output", "", 0)
        val result = run("""{"command":"printf native"}""")
        assertTrue(result.ok)
        assertTrue(result.output.startsWith("[shizuku(shell)+bash] exit=0\n"))
        coVerify(exactly = 1) { Shell.shizuku("printf native", "bash") }
        coVerify(exactly = 0) { Shell.local(any(), any(), any()) }
        coVerify(exactly = 0) { Shell.root(any(), any()) }
    }

    @Test fun deniedShizukuBashFailsWithoutTryingRootOrLocalShell() = runBlocking {
        every { capabilities.shellTier() } returns ShellTier.SHIZUKU
        coEvery { Shell.shizuku("printf native", "bash") } returns ShellResult(false, "", "permission denied", 13)
        val result = run("""{"command":"printf native"}""")
        assertFalse(result.ok)
        assertTrue(result.output.contains("exit=13"))
        assertTrue(result.output.contains("permission denied"))
        coVerify(exactly = 0) { Shell.root(any(), any()) }
        coVerify(exactly = 0) { Shell.local(any(), any(), any()) }
    }

    @Test fun missingAndBlankCommandsNeverStartAProcess() = runBlocking {
        for (raw in listOf("{}", """{"command":"   "}""")) {
            val result = run(raw)
            assertFalse(result.ok)
            assertTrue(result.output.contains("command"))
        }
        coVerify(exactly = 0) { Shell.local(any(), any(), any()) }
        verify(exactly = 0) { TouchFx.traceCall(any(), any(), any(), any()) }
    }

    @Test fun failureKeepsStdoutStderrAndTheActualExitCode() = runBlocking {
        coEvery { Shell.local(any(), any(), any()) } returns ShellResult(false, "partial", "syntax error", 2)
        val result = run("""{"command":"printf partial"}""")
        assertFalse(result.ok)
        assertTrue(result.output.contains("exit=2"))
        assertTrue(result.output.contains("partial"))
        assertTrue(result.output.contains("[stderr] syntax error"))
    }

    @Test fun seccompFailureIsReturnedWithoutAnAutomaticShellRetry() = runBlocking {
        coEvery { Shell.local(any(), any(), any()) } returns ShellResult(false, "", "seccomp rejected Bash", 159)
        val result = run("""{"command":"printf native"}""")
        assertFalse(result.ok)
        assertTrue(result.output.contains("exit=159"))
        assertTrue(result.output.contains("seccomp"))
        coVerify(exactly = 1) { Shell.local("printf native", "bash", null) }
        coVerify(exactly = 0) { Shell.root(any()) }
        coVerify(exactly = 0) { Shell.shizuku(any()) }
    }

    @Test fun unavailableBundledBashIsNotReportedAsASuccessfulShCall() = runBlocking {
        every { Shell.bundledBash() } returns null
        coEvery { Shell.local(any(), any(), any()) } returns ShellResult(false, "", "没有随包 bash", -1)
        val result = run("""{"command":"printf native"}""")
        assertFalse(result.ok)
        assertTrue(result.output.contains("exit=-1"))
        assertTrue(result.output.contains("没有随包 bash"))
        coVerify(exactly = 1) { Shell.local("printf native", "bash", null) }
    }

    @Test fun cancellationRemainsCancellationRatherThanAnOrdinaryToolFailure() = runBlocking {
        coEvery { Shell.local(any(), any(), any()) } throws CancellationException("user stop")
        val failure = runCatching { run("""{"command":"printf native"}""") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("user stop", failure?.message)
    }

    @Test fun settingsWriteReadbackUsesTheSameStrictBashRoute() = runBlocking {
        coEvery { Shell.local("settings put global example 1", "bash", null) } returns ShellResult(true, "", "", 0)
        coEvery { Shell.local("settings get global example", "bash", null) } returns ShellResult(true, "1", "", 0)
        val result = run("""{"command":"settings put global example 1"}""")
        assertTrue(result.ok)
        assertTrue(result.output.contains("verified=true"))
        coVerify(exactly = 1) { Shell.local("settings put global example 1", "bash", null) }
        coVerify(exactly = 1) { Shell.local("settings get global example", "bash", null) }
    }

    @Test fun hiddenNativeToolsDisappearFromBothCatalogAndDispatch() {
        val restricted = registry.excluding(setOf("bash"))
        assertNull(restricted.find("bash"))
        assertFalse(restricted.names().contains("bash"))
        assertFalse(restricted.promptSummary().contains("- bash:"))
        assertNotNull(registry.find("bash"))
    }

    @Test fun dynamicToolsCannotShadowTheNativeBashEntry() {
        val shadow = object : Tool {
            override val spec = ToolSpec("bash", "shadow", JsonObject(emptyMap()))
            override val tier = TIER_NONE
            override suspend fun run(args: JsonObject) = ToolResult.success("shadow")
        }
        val failure = runCatching { registry.replaceDynamic("mcp_", listOf(shadow)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(bashTools.specs.single(), registry.find("bash")!!.spec)
    }
}
