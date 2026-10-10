package com.suixing.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression net for the one decision that says which privilege tier runs: [CapabilityState.shellTier].
 *
 * The user's rule is 「权限的档次就按执行档次设置的执行，然后没指定档次时优先权限最高的」. Every
 * privilege check used to answer it for itself — root first in the shell tools, Shizuku in the ui
 * fallback — so a tier picked on the 能力 page changed nothing about what ran. These cases pin the
 * three answers that matter: nothing fixed takes the highest channel the device offers, a fixed tier
 * takes that tier, and a fixed tier that is not ready yet never reaches past itself.
 */
class ShellTierTest {

    private fun device(
        accessibility: Boolean = false,
        shell: Boolean = false,
        root: Boolean = false,
        rootProbed: Boolean = true,
    ): CapabilityState = CapabilityState(
        accessibility = accessibility,
        shizukuAlive = shell,
        shizukuGranted = shell,
        root = root,
        rootProbed = rootProbed,
    )

    @Test
    fun `nothing fixed takes the highest channel the device offers`() {
        assertEquals(ShellTier.ROOT, device(shell = true, root = true).shellTier(""))
        assertEquals(ShellTier.SHIZUKU, device(shell = true).shellTier(""))
        assertEquals(ShellTier.LOCAL, device().shellTier(""))
        // A height the device cannot reach is not chosen just because root was never asked for.
        assertEquals(ShellTier.SHIZUKU, device(shell = true, root = true, rootProbed = false).shellTier(""))
    }

    @Test
    fun `an unknown value is treated as nothing fixed`() {
        // A settings file written by a future build must fall back to the rule, not to LOCAL.
        assertEquals(ShellTier.ROOT, device(shell = true, root = true).shellTier("hypervisor"))
    }

    @Test
    fun `a fixed tier is the channel that runs`() {
        val rootAndShell = device(shell = true, root = true)
        assertEquals(ShellTier.ROOT, rootAndShell.shellTier(CapabilityTiers.ROOT))
        assertEquals(ShellTier.SHIZUKU, rootAndShell.shellTier(CapabilityTiers.SHELL))
        assertEquals(ShellTier.LOCAL, device(shell = true).shellTier(CapabilityTiers.NONE))
    }

    @Test
    fun `standard tier never escalates, even with root ready`() {
        // 标准档 is a fixed choice, not "nothing fixed": the picker's text is 「只用应用自身权限」,
        // and it clears back to "" for 自动. Root being ready must not override it.
        val rootAndShell = device(accessibility = true, shell = true, root = true)
        assertEquals(ShellTier.LOCAL, rootAndShell.shellTier(CapabilityTiers.NONE))
    }

    @Test
    fun `a fixed tier that is not ready falls to the highest ready tier below it`() {
        assertEquals(ShellTier.SHIZUKU, device(shell = true).shellTier(CapabilityTiers.ROOT))
        assertEquals(ShellTier.LOCAL, device().shellTier(CapabilityTiers.ROOT))
        // Fixed Shizuku on a device that has root: a downgrade to LOCAL, never up to root.
        assertEquals(ShellTier.LOCAL, device(root = true).shellTier(CapabilityTiers.SHELL))
        assertEquals(ShellTier.SHIZUKU, device(shell = true, root = true).shellTier(CapabilityTiers.SHELL))
    }

    @Test
    fun `accessibility fixes the screen route and leaves the shell channel to the device`() {
        // ui_* tries the accessibility service first; the fallback behind it is the one the
        // 无障碍 card promises, which is the highest channel that is ready.
        assertEquals(ShellTier.ROOT, device(accessibility = true, shell = true, root = true).shellTier(CapabilityTiers.ACCESSIBILITY))
        assertEquals(ShellTier.LOCAL, device(accessibility = true).shellTier(CapabilityTiers.ACCESSIBILITY))
    }

    @Test
    fun `an unprobed root does not count as ready`() {
        // Resolution must not trigger the superuser prompt; only the 能力 page asks for root.
        assertEquals(ShellTier.SHIZUKU, device(shell = true, root = true, rootProbed = false).shellTier(CapabilityTiers.ROOT))
        assertEquals(ShellTier.LOCAL, device(root = true, rootProbed = false).shellTier(""))
    }
}
