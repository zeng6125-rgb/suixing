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
    fun `root is not a channel at all while 允许使用 Root is off`() {
        // 安全语义：关掉之后无论设备是否 root、无论探测到什么，都不得解析到 ROOT。
        // 关＝不探测、也不会用 root —— 这一条就是「不会用」的落点。
        val rooted = device(accessibility = true, shell = true, root = true)
        for (preferred in listOf("", "hypervisor", CapabilityTiers.ACCESSIBILITY, CapabilityTiers.SHELL, CapabilityTiers.ROOT, CapabilityTiers.NONE)) {
            assertTrue(
                "preferred=$preferred must not reach root",
                rooted.shellTier(preferred, rootAllowed = false) != ShellTier.ROOT,
            )
        }
    }

    @Test
    fun `with root off the channel falls to what is left`() {
        // 关掉 root 不是「降级到标准档」：Shizuku 还在就照旧用 Shizuku，两个都没有才是标准档。
        assertEquals(ShellTier.SHIZUKU, device(shell = true, root = true).shellTier(CapabilityTiers.ROOT, rootAllowed = false))
        assertEquals(ShellTier.SHIZUKU, device(shell = true, root = true).shellTier("", rootAllowed = false))
        assertEquals(ShellTier.LOCAL, device(root = true).shellTier(CapabilityTiers.ROOT, rootAllowed = false))
        assertEquals(ShellTier.LOCAL, device(accessibility = true, root = true).shellTier("", rootAllowed = false))
        // 标准档本来就到不了 root，开关关掉后它的答案不变。
        assertEquals(ShellTier.LOCAL, device(shell = true, root = true).shellTier(CapabilityTiers.NONE, rootAllowed = false))
    }

    @Test
    fun `the switch on keeps the behaviour that was already there`() {
        // 默认（不传参）＝开关打开：老调用点与既有行为一字不变，默认值是 true 就是为了这个。
        val rooted = device(shell = true, root = true)
        assertEquals(ShellTier.ROOT, rooted.shellTier(CapabilityTiers.ROOT))
        assertEquals(ShellTier.ROOT, rooted.shellTier(CapabilityTiers.ROOT, rootAllowed = true))
        assertEquals(ShellTier.ROOT, rooted.shellTier("", rootAllowed = true))
    }

    @Test
    fun `an explicit mode=root still obeys the switch`() {
        // 显式档位是请求不是许可：开关关掉时，模型自己点名 root 也只能落到解析出来的通道。
        assertEquals("shizuku", shellChannel("root", ShellTier.SHIZUKU, rootAllowed = false))
        assertEquals("local", shellChannel("root", ShellTier.LOCAL, rootAllowed = false))
        // 打开时保持原样：显式要求 root 就走 root。
        assertEquals("root", shellChannel("root", ShellTier.ROOT, rootAllowed = true))
        // 别的档位不受这个开关影响（开关只管 root）。
        assertEquals("local", shellChannel("local", ShellTier.ROOT, rootAllowed = false))
        assertEquals("shizuku", shellChannel("shizuku", ShellTier.ROOT, rootAllowed = false))
        // 没指定档位（auto 或任何未知值）本来就该走解析结果。
        assertEquals("shizuku", shellChannel("auto", ShellTier.SHIZUKU, rootAllowed = true))
        assertEquals("local", shellChannel("hypervisor", ShellTier.LOCAL, rootAllowed = true))
    }

    @Test
    fun `naming root cannot get past the switch, through the whole decision`() {
        // 串起整条链：同一份 state 上先解析档位、再选通道 —— 开关关着时，
        // 模型无论怎么点名 root，最终选出来的通道都不是 root。
        val rooted = device(shell = true, root = true)
        for (preferred in listOf("", CapabilityTiers.ROOT, CapabilityTiers.ACCESSIBILITY)) {
            val resolved = rooted.shellTier(preferred, rootAllowed = false)
            assertTrue(
                "preferred=$preferred must not run as root",
                shellChannel("root", resolved, rootAllowed = false) != "root",
            )
        }
        // 开关打开时同一条链给出 root，说明上面否掉的不是「永远拿不到 root」。
        val on = rooted.shellTier(CapabilityTiers.ROOT, rootAllowed = true)
        assertEquals("root", shellChannel("root", on, rootAllowed = true))
    }

    @Test
    fun `an explicit tier never goes above the tier the user fixed`() {
        // 固定标准档：点名 shizuku 也落到 local —— 用户说「只用应用自身权限」就是只用它。
        val rooted = device(shell = true, root = true)
        assertEquals("local", shellChannel("shizuku", rooted.shellTier(CapabilityTiers.NONE), rootAllowed = true))
        assertEquals("local", shellChannel("root", rooted.shellTier(CapabilityTiers.NONE), rootAllowed = true))
    }

    @Test
    fun `an explicit tier inside the fixed range is still honoured`() {
        val rooted = device(accessibility = true, shell = true, root = true)
        // 固定无障碍档：它允许的范围到最高可用档，点名 shizuku 就在范围内。
        assertEquals("shizuku", shellChannel("shizuku", rooted.shellTier(CapabilityTiers.ACCESSIBILITY), rootAllowed = true))
        // 固定 Root：低档请求是允许的 —— 点名 shizuku 走 shizuku，既不被抬到 root 也不被否掉。
        assertEquals("shizuku", shellChannel("shizuku", rooted.shellTier(CapabilityTiers.ROOT), rootAllowed = true))
        assertEquals("local", shellChannel("local", rooted.shellTier(CapabilityTiers.ROOT), rootAllowed = true))
    }
}
