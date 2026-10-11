package com.suixing.agent.capability

import com.suixing.agent.tools.shellChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `a probed root is reachable with nothing else to switch on`() {
        // root 像别的能力一样自动探测：探测到可用，档位选择器就是唯一的决定者。
        // （曾经有过一个「允许使用 Root」开关，用户否掉了它：档次已经够了。）
        val rooted = device(accessibility = true, shell = true, root = true)
        assertEquals(ShellTier.ROOT, rooted.shellTier(""))
        assertEquals(ShellTier.ROOT, rooted.shellTier(CapabilityTiers.ROOT))
        // 探测不到就只有下面两档，没有别的闸门要过。
        assertEquals(ShellTier.SHIZUKU, device(shell = true).shellTier(""))
    }

    @Test
    fun `an explicit mode=root is clamped to what the device resolved`() {
        // 显式档位是请求不是许可：设备解析不到 root 时，模型自己点名 root 也只能落到解析结果。
        assertEquals("shizuku", shellChannel("root", ShellTier.SHIZUKU))
        assertEquals("local", shellChannel("root", ShellTier.LOCAL))
        // 解析到 root 就走 root：这一条是「root 自动探测」的直接后果。
        assertEquals("root", shellChannel("root", ShellTier.ROOT))
        // 别的档位本来就在请求之下，钳制不动它们。
        assertEquals("local", shellChannel("local", ShellTier.ROOT))
        assertEquals("shizuku", shellChannel("shizuku", ShellTier.ROOT))
        // 没指定档位（auto 或任何未知值）本来就该走解析结果。
        assertEquals("shizuku", shellChannel("auto", ShellTier.SHIZUKU))
        assertEquals("local", shellChannel("hypervisor", ShellTier.LOCAL))
    }

    @Test
    fun `naming root cannot exceed the resolved tier, through the whole decision`() {
        // 串起整条链：先解析档位、再选通道。root 没探测到（rootProbed=false）时，
        // 模型无论怎么点名 root，最终选出来的通道都不是 root。
        val notProbed = device(shell = true, root = true, rootProbed = false)
        for (preferred in listOf("", CapabilityTiers.ROOT, CapabilityTiers.ACCESSIBILITY)) {
            val resolved = notProbed.shellTier(preferred)
            assertTrue(
                "preferred=$preferred must not run as root",
                shellChannel("root", resolved) != "root",
            )
        }
        // 探测到之后同一条链给出 root，说明上面否掉的不是「永远拿不到 root」。
        val probed = device(shell = true, root = true)
        assertEquals("root", shellChannel("root", probed.shellTier(CapabilityTiers.ROOT)))
    }

    @Test
    fun `an explicit tier never goes above the tier the user fixed`() {
        // 固定标准档：点名 shizuku 也落到 local —— 用户说「只用应用自身权限」就是只用它。
        val rooted = device(shell = true, root = true)
        assertEquals("local", shellChannel("shizuku", rooted.shellTier(CapabilityTiers.NONE)))
        assertEquals("local", shellChannel("root", rooted.shellTier(CapabilityTiers.NONE)))
    }

    @Test
    fun `an explicit tier inside the fixed range is still honoured`() {
        val rooted = device(accessibility = true, shell = true, root = true)
        // 固定无障碍档：它允许的范围到最高可用档，点名 shizuku 就在范围内。
        assertEquals("shizuku", shellChannel("shizuku", rooted.shellTier(CapabilityTiers.ACCESSIBILITY)))
        // 固定 Root：低档请求是允许的 —— 点名 shizuku 走 shizuku，既不被抬到 root 也不被否掉。
        assertEquals("shizuku", shellChannel("shizuku", rooted.shellTier(CapabilityTiers.ROOT)))
        assertEquals("local", shellChannel("local", rooted.shellTier(CapabilityTiers.ROOT)))
    }
}
