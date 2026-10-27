package com.suixing.agent.llm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.URI

/**
 * Android 17（API 37）起的局域网访问限制。
 *
 * 从 targetSdk ≥ 37 开始，应用**默认不能**访问局域网：只有 `INTERNET` 不够，还必须声明并在运行时
 * 获得 `ACCESS_LOCAL_NETWORK`。没有它时，发往内网地址的 TCP 连接通常表现为**超时** ——
 * 和「地址/端口写错」「不在同一网络」长得一模一样，只看日志分不出来。
 *
 * 官方说明：https://developer.android.com/privacy-and-security/local-network-permission
 *
 * 这里只做三件纯事：判断平台是否要求、判断是否已授权、判断目标地址是否可能落在局域网。
 * 请求授权的动作留给 UI（只有 Activity 能弹系统对话框）。
 */
internal object LocalNetworkAccess {

    /** Android 17（API 37）是第一个要求该权限的版本。 */
    private const val SDK_LOCAL_NETWORK = 37

    /** 更早的系统没有这条权限，**绝不**弹无关授权。 */
    val requiredByPlatform: Boolean get() = Build.VERSION.SDK_INT >= SDK_LOCAL_NETWORK

    fun granted(context: Context): Boolean =
        !requiredByPlatform ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 这次请求会不会因为缺权限而注定超时。
     *
     * 只有「平台要求 + 尚未授权 + 目标像局域网」三条同时成立才拦；公网地址永远照常发，
     * 不会因为用户没给局域网权限就全面封掉网络。
     */
    fun blockedByMissingPermission(context: Context, url: String): Boolean =
        requiredByPlatform && !granted(context) && isLocalTarget(url)

    fun isLocalTarget(url: String): Boolean = isLocalHost(hostOf(url))

    /** 目标主机是否可能在内网。只看**地址形态**，不做 DNS 解析（那要联网且在后台线程）。 */
    fun isLocalHost(raw: String): Boolean {
        // `URI.getHost()` 对 IPv6 字面量返回的是带方括号的 `[::1]`；先去掉再判。
        val host = raw.trim().removePrefix("[").removeSuffix("]").removeSuffix(".").lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (host.endsWith(".local") || host.endsWith(".localhost")) return true
        if (host.contains(':')) return isLocalIpv6(host)
        if (IPV4.matches(host)) return isLocalIpv4(host)
        // 单段主机名（`myserver`、`nas`）只能靠局域网内的 mDNS/NetBIOS 或 hosts 解析到内网。
        return !host.contains('.')
    }

    private fun isLocalIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val a = parts[0]
        val b = parts[1]
        return a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254)
    }

    private fun isLocalIpv6(host: String): Boolean {
        if (host == "::1" || host == "::") return true
        val mapped = if (host.startsWith("::ffff:")) host.removePrefix("::ffff:") else host
        if (mapped.contains('.')) return isLocalIpv4(mapped.substringAfterLast(':'))
        // fe80::/10 链路本地、fc00::/7 唯一本地地址（ULA）。
        return mapped.startsWith("fe80") || mapped.startsWith("fc") || mapped.startsWith("fd")
    }

    /** 从 URL 里取主机名；`http://host:port/path`、裸 `host:port` 都能取。失败返回空串。 */
    fun hostOf(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        runCatching { URI(trimmed).host }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        val authority = withoutScheme.substringBefore('/').substringAfterLast('@')
        return if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
        else authority.substringBefore(':')
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
}
