package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.ShellTier
import com.suixing.agent.tools.TIER_ACCESSIBILITY
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.TIER_SHELL

/**
 * 固定执行档次下「为什么现在跑不了」，null 表示能跑。
 *
 * 只读传入的 [CapabilityHub]，不碰 run 状态；从 [AgentEngine.RunHandle] 搬出来。
 */

    /**
     * Why [tier] cannot run right now, or null when it can. The channel question is the same one
     * the tools ask ([CapabilityHub.shellTier]), so a gate never promises a route the tool below
     * it would refuse — 固定 标准档 or a tier that is not ready yet is reported as such instead of
     * as a missing permission the user already granted.
     */
    internal fun blockedReason(tier: String, capabilities: CapabilityHub): String? {
        val state = capabilities.state.value
        return when (tier) {
            TIER_ACCESSIBILITY ->
                // 无障碍是首选通道，但 Shizuku / root 也能完成同一批操作
                // （input / screencap / uiautomator），所以有其中任一即可放行。
                if (state.accessibility || capabilities.shellTier() != ShellTier.LOCAL) {
                    null
                } else {
                    "还不能操作屏幕：请到「能力」页开启无障碍服务，或授权 Shizuku / root 中的任意一个。"
                }
            TIER_SHELL ->
                when {
                    capabilities.shellTier() != ShellTier.LOCAL -> null
                    // Something is granted, so the tier the user fixed is what is blocking: saying
                    // 「缺少 Shizuku（或 root）权限」 here would send them to grant it again.
                    capabilities.preferred.value.isNotEmpty() && (state.shellReady || state.rootReady) ->
                        "这类操作没有可用通道：已固定执行档次，但它还没就绪。" +
                            "请到「能力」页开启你固定的那一档，或把执行档次改回「自动」。"
                    else -> "缺少 Shizuku（或 root）权限：请到「能力」页授权后重试。"
                }
            TIER_ROOT ->
                if (state.rootReady) null else "缺少 root 权限，当前无法执行该工具。"
            else -> null
        }
    }
